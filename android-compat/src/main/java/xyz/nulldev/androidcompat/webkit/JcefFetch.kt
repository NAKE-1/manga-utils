package xyz.nulldev.androidcompat.webkit

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import org.cef.browser.CefBrowser
import org.cef.browser.CefRendering
import org.cef.callback.CefCookieVisitor
import org.cef.handler.CefRenderHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.awt.Rectangle
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel

/**
 * Runs an HTTP request through a **real Chromium engine** (JCEF), offscreen, instead of OkHttp.
 *
 * This is the only thing that gets past Cloudflare on hostile sources (MangaFire): OkHttp on the desktop
 * JVM can't reproduce Chrome's TLS/JA4 handshake (no GREASE/ALPS via Conscrypt) or Chrome's HTTP/2
 * fingerprint, so Cloudflare flags it no matter what cookies we hold. JCEF *is* Chrome, so its handshake
 * is genuine. We keep a small **pool** of offscreen browsers per host (each clears Cloudflare once and
 * holds the cookies), then run the request as a same-origin `fetch()` from one of them — so it carries the
 * real fingerprint, the cf_clearance, the Referer, and (crucially) the extension's `vrf` already in the URL.
 *
 * The pool exists because a library update fans out ~dozens of concurrent /api calls at once: a single
 * browser serialises them (and can race its own evaluateJavaScript callbacks), so a leased pool spreads the
 * load across N real browsers, one in-flight eval each.
 *
 * No window — same offscreen rendering the extension WebViews use.
 */
object JcefFetch {
    private val log = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }

    // Env-tunable via MU_JCEF_POOL. Each browser is a full Chromium (RAM-heavy) — raise it if you have
    // RAM and a large library queues on "no free browser"; LOWER it (e.g. 2) if the box is memory-bound
    // and browsers OOM. Default 3.
    private val POOL_SIZE = System.getenv("MU_JCEF_POOL")?.toIntOrNull()?.coerceIn(1, 12) ?: 3

    // A stale cf_clearance makes the same-origin fetch come back as one of these CF interstitials.
    private val CHALLENGE_MARKERS = listOf("just a moment", "challenge-platform", "cf-mitigated", "cf_chl", "cf-browser-verification", "checking your browser")
    private val INTERACTIVE_MARKERS = listOf("captcha_required", "@waf/challenge", "verify you're human", "click the shapes")
    private const val RATE_LIMIT_BACKOFF_MS = 3_000L

    data class Result(val status: Int, val body: String, val headers: Map<String, String>)

    private val pools = ConcurrentHashMap<String, HostPool>()
    private val wedge = ConcurrentHashMap<String, AtomicInteger>() // consecutive "no free browser" per host

    // MU_JCEF_HEADED=1 → fetch-pool browsers render into real (invisible) AWT windows on the Xvfb display so
    // Cloudflare's Turnstile sees a genuine browser and lets JCEF through itself (the offscreen browser can't
    // pass it in a container). The interactive WebView stays offscreen. Off by default — unset the env to revert.
    private val HEADED = System.getenv("MU_JCEF_HEADED") == "1"
    private val headedFrames = ConcurrentHashMap<CefBrowser, java.awt.Frame>() // one host window per headed browser

    /** Live per-host pool state for the dev UI. */
    data class PoolStat(val host: String, val size: Int, val busy: Int, val free: Int, val max: Int)

    fun poolStatus(): List<PoolStat> =
        pools.values.map { PoolStat(it.host, it.size, it.busy, it.free, it.max) }.sortedBy { it.host }

    /** Dispose every browser in every pool (recovers a wedged pool without a container restart). */
    fun resetPools(): Int {
        var n = 0
        pools.values.toList().forEach { n += it.disposeAll() }
        pools.clear(); wedge.clear()
        log.info { "JCEF: reset ALL browser pools ($n browser(s) disposed)" }
        return n
    }

    /** Dispose one host's pool. The next fetch to that host builds a fresh one. */
    fun resetPool(host: String): Int {
        val n = pools.remove(host)?.disposeAll() ?: 0
        wedge.remove(host)
        if (n > 0) log.info { "JCEF: reset pool for $host ($n browser(s))" }
        return n
    }

    /**
     * Inject a cookie into the shared CEF jar for [host]. Used to seed FlareSolverr's `cf_clearance` (which
     * FS earns with its *headed* browser) into JCEF — because JCEF's offscreen/headless browser can't pass
     * Cloudflare's Turnstile in a container, but it CAN present a clearance cookie FS already obtained on the
     * same IP. cf_clearance is a cookie, not a one-time token, so this transplant is valid as long as
     * Cloudflare doesn't re-bind it to the exact browser fingerprint. Returns true if CEF accepted it.
     */
    fun setCookie(
        host: String,
        name: String,
        value: String,
        domain: String? = null,
        path: String = "/",
        secure: Boolean = true,
        httpOnly: Boolean = false,
        expiresEpochSec: Long? = null,
    ): Boolean {
        val mgr = runCatching { CefCookieManager.getGlobalManager() }.getOrNull() ?: return false
        val now = java.util.Date()
        val cookie = CefCookie(
            name, value,
            domain ?: ".$host", path, secure, httpOnly,
            now, now, expiresEpochSec != null,
            expiresEpochSec?.let { java.util.Date(it * 1000) } ?: now,
        )
        val ok = runCatching { mgr.setCookie("https://$host/", cookie) }.getOrDefault(false)
        if (ok) log.info { "JCEF[$host]: seeded cookie $name (from FlareSolverr)" }
        return ok
    }

    private val renderHandler = object : CefRenderHandlerAdapter() {
        override fun getViewRect(browser: CefBrowser) = Rectangle(0, 0, 1280, 900)
    }

    /** Fetch [url] (which already includes any vrf/query the extension added) via a real browser. Returns
     *  null on failure so the caller can fall back. Blocks up to ~[timeoutMs] + the one-time clear. */
    fun fetch(url: String, method: String, headers: Map<String, String>, body: String?, timeoutMs: Long = 60_000): Result? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        val host = u.host ?: return null
        val scheme = u.scheme ?: "https"
        val pool = pools.computeIfAbsent(host) { HostPool(host, POOL_SIZE, isCleared = { hasCfClearance(host) }) { newBrowser(scheme, host) } }

        val started = System.currentTimeMillis()
        val browser = pool.borrow(timeoutMs)
        if (browser == null) {
            val w = wedge.computeIfAbsent(host) { AtomicInteger(0) }.incrementAndGet()
            log.info { "JCEF[$host]: no free browser after ${System.currentTimeMillis() - started}ms (${pool.busy}/$POOL_SIZE busy) → falling back" }
            // Auto-recover: a pool that can't lease a browser twice in a row is wedged (browsers stuck on
            // an unclearable challenge). Dispose it so the next call rebuilds fresh instead of every
            // request waiting the full timeout forever.
            if (w >= 2) { log.warn { "JCEF[$host]: pool wedged ($w consecutive failures) — auto-resetting" }; resetPool(host) }
            return null
        }
        wedge.remove(host) // a successful borrow means the pool is healthy again
        try {
            val js = buildFetchJs(url, method, headers, body)
            var res = runFetch(browser, js, host, u.path, timeoutMs)
            // Self-heal: the browser's cf_clearance can expire while it sits idle. When it does, the
            // same-origin fetch comes back as Cloudflare's "Just a moment" challenge (or a 1015 rate
            // limit) instead of data. Reload the root to re-clear, or back off for a rate limit, and
            // retry once — otherwise EVERY call on a stale browser fails and stampedes the fallback path
            // (which is what turned one expired cookie into a whole failed library update).
            val first = res
            if (first != null && isChallenge(first) && !isInteractive(first)) {
                log.info { "JCEF[$host]: ${first.status} challenge on ${u.path} — reloading root to re-clear, retry once" }
                reclear("$scheme://$host/", host, browser)
                res = runFetch(browser, js, host, u.path, timeoutMs)
            } else if (first != null && isInteractive(first)) {
                // A human captcha (e.g. /@waf/challenge "click the shapes") — no reload can auto-solve it, so
                // return immediately (don't burn the 45s reclear). The interceptor flags it for the UI prompt.
                log.info { "JCEF[$host]: interactive human-check on ${u.path} — needs the user (WebView)" }
            } else if (first != null && isRateLimited(first)) {
                log.info { "JCEF[$host]: ${first.status} rate-limited on ${u.path} — backing off ${RATE_LIMIT_BACKOFF_MS}ms, retry once" }
                Thread.sleep(RATE_LIMIT_BACKOFF_MS)
                res = runFetch(browser, js, host, u.path, timeoutMs)
            }
            res?.let { log.info { "JCEF[$host]: ${it.status} ${u.path} in ${System.currentTimeMillis() - started}ms (${it.body.length}B)" } }
            return res
        } finally {
            pool.giveBack(browser)
        }
    }

    /** One same-origin fetch through the browser's message router. Null on eval failure/timeout/error. */
    private fun runFetch(browser: CefBrowser, js: String, host: String, path: String, timeoutMs: Long): Result? {
        val latch = CountDownLatch(1)
        var out: String? = null
        runCatching {
            browser.evaluateJavaScript(js) { r -> out = r; latch.countDown() }
        }.onFailure { log.info { "JCEF[$host]: eval threw for $path: ${it.message}" }; return null }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            log.info { "JCEF[$host]: fetch timed out (${timeoutMs}ms) for $path" }
            return null
        }
        val raw = out ?: return null
        return runCatching {
            val o = json.parseToJsonElement(raw).jsonObject
            val status = o["status"]?.jsonPrimitive?.int ?: 0
            if (status == 0) { log.info { "JCEF[$host]: fetch error for $path: ${o["error"]?.jsonPrimitive?.contentOrNull}" }; return null }
            val bodyStr = o["body"]?.jsonPrimitive?.contentOrNull ?: ""
            val hdrs = o["headers"]?.jsonObject?.mapValues { it.value.jsonPrimitive.contentOrNull ?: "" } ?: emptyMap()
            Result(status, bodyStr, hdrs)
        }.getOrElse { log.info(it) { "JCEF[$host]: bad response for $path" }; null }
    }

    /** A CF interstitial the browser can re-clear by reloading (its clearance went stale) — 403/503 + marker. */
    private fun isChallenge(r: Result): Boolean =
        (r.status == 403 || r.status == 503) && CHALLENGE_MARKERS.any { r.body.contains(it, ignoreCase = true) }

    /** Cloudflare rate limit (429 / "Error 1015") — a reload won't help; only backing off does. */
    private fun isRateLimited(r: Result): Boolean =
        r.status == 429 || r.body.contains("error 1015", ignoreCase = true)

    /** An interactive human challenge (captcha) — a reload can't solve it; only the user in a WebView can. */
    private fun isInteractive(r: Result): Boolean =
        INTERACTIVE_MARKERS.any { r.body.contains(it, ignoreCase = true) }

    /** Reload the site root and wait for a fresh cf_clearance (the browser navigates off the challenge). */
    private fun reclear(root: String, host: String, browser: CefBrowser) {
        runCatching { browser.loadURL(root) }
        waitCleared(host, browser)
    }

    /** Create one cleared browser for a host. Called under the pool's growth lock. Windowed (headed on Xvfb)
     *  when MU_JCEF_HEADED=1 so it passes Cloudflare itself; offscreen otherwise. */
    private fun newBrowser(scheme: String, host: String): CefBrowser? {
        val client = runCatching { runBlocking { CefHelper.createClient() } }.getOrNull() ?: return null
        val root = "$scheme://$host/"
        val browser = if (HEADED) {
            // Real windowed browser hosted in an AWT Frame on the Xvfb display (:99). We never look at the
            // window; Cloudflare does — it sees a genuine window + GL compositing and clears Turnstile the
            // way it did on Windows. Needs java.awt.headless=false (set in CefManager) and DISPLAY=:99.
            val b = client.createBrowser(root, CefRendering.DEFAULT, false)
            runCatching {
                java.awt.Frame().apply {
                    isUndecorated = true
                    setSize(1280, 900)
                    setLocation(0, 0)
                    add(b.uiComponent)
                    isVisible = true
                }.also { headedFrames[b] = it }
            }.onFailure { log.warn { "JCEF[$host]: headed frame failed (${it.message}); browser may not render" } }
            b
        } else {
            client.createBrowser(root, CefRendering.CefRenderingWithHandler(renderHandler, JPanel()), false)
                .apply { createImmediately() }
        }
        waitCleared(host, browser)
        log.info { "JCEF[$host]: opened ${if (HEADED) "windowed" else "offscreen"} browser (cf_clearance=${hasCfClearance(host)})" }
        return browser
    }

    /**
     * A tiny leased pool of browsers for one host. Grows lazily up to [max] under real concurrency; a
     * borrowed browser handles exactly one in-flight eval until [giveBack]. After warm-up the hot path is
     * a lock-free queue poll/offer.
     */
    private class HostPool(val host: String, val max: Int, val isCleared: () -> Boolean, val create: () -> CefBrowser?) {
        private val available = LinkedBlockingQueue<CefBrowser>()
        private val allBrowsers = java.util.concurrent.ConcurrentHashMap.newKeySet<CefBrowser>()
        private val count = AtomicInteger(0)
        val size get() = count.get()
        val free get() = available.size
        val busy get() = (count.get() - available.size).coerceAtLeast(0)

        fun borrow(timeoutMs: Long): CefBrowser? {
            available.poll()?.let { return it }
            // Grow the pool ONLY when we have no browser yet, or the host is already cleared. While a host is
            // still behind an uncleared Cloudflare challenge, extra browsers can't help clear it — each new
            // one independently burns the full clear-timeout (that's how one cold solve turned into 5-6
            // stacked 45-90s solves). So keep exactly ONE browser doing the solve and let concurrent callers
            // queue on it (fast ~200ms cycles through the interactive-captcha path while the autosolver runs
            // in the background) instead of stampeding. Once the host clears, grow to max for throughput.
            if (count.get() < max) {
                synchronized(this) {
                    if (available.isEmpty() && count.get() < max && (count.get() == 0 || isCleared())) {
                        create()?.let { allBrowsers.add(it); count.incrementAndGet(); return it }
                    }
                }
            }
            return available.poll(timeoutMs, TimeUnit.MILLISECONDS)
        }

        fun giveBack(b: CefBrowser) { available.offer(b) }

        /** Force-close every browser and reset the pool — the recovery lever for a wedged pool. A browser
         *  still held by a (stuck) fetch is force-closed too; that fetch was going to time out anyway, and
         *  its late giveBack lands on this now-orphaned instance, not the fresh pool the next fetch creates. */
        fun disposeAll(): Int {
            val n = allBrowsers.size
            allBrowsers.forEach { b ->
                runCatching { b.close(true) }
                runCatching { headedFrames.remove(b)?.dispose() } // headed mode: also drop its AWT window
            }
            allBrowsers.clear(); available.clear(); count.set(0)
            return n
        }
    }

    /** Poll until the site is cleared (cf_clearance present + document loaded), or give up after 45s. */
    private fun waitCleared(host: String, browser: CefBrowser) {
        // 20s, not 45s: a passive JS Turnstile clears in a few seconds; if it hasn't cleared by 20s it's an
        // interactive shapes-captcha that no amount of waiting auto-solves, so stop stalling and let the
        // fetch return the challenge to the autosolver/WebView path sooner.
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(700)
            if (hasCfClearance(host) && documentReady(browser)) return
            // If the page has already landed on the interactive shapes captcha (/@waf/challenge), no amount
            // of passive waiting produces cf_clearance — stop stalling and let the fetch return the challenge
            // so the autosolver fires NOW. This is what makes unattended solving fast (like desktop): don't
            // burn the full 20s on a wall only the YOLO solver can pass.
            if (showsInteractiveChallenge(browser)) {
                log.info { "JCEF: $host hit the interactive captcha — skipping the clear-wait, handing to autosolve" }
                return
            }
        }
        log.warn { "JCEF: $host didn't visibly clear Cloudflare within 20s — trying the fetch anyway" }
    }

    /** True if the browser is currently sitting on MangaFire's interactive shapes captcha (which no passive
     *  reload can clear — only the autosolver/WebView can). Lets [waitCleared] bail immediately. */
    private fun showsInteractiveChallenge(browser: CefBrowser): Boolean {
        val latch = CountDownLatch(1)
        var hit = false
        runCatching {
            browser.evaluateJavaScript(
                "(/@waf\\/challenge/i.test(location.href) || /click the shapes|verify you.?re human/i.test(document.body ? document.body.innerText : ''))",
            ) { r -> hit = r == "true"; latch.countDown() }
        }
        latch.await(1, TimeUnit.SECONDS)
        return hit
    }

    private fun documentReady(browser: CefBrowser): Boolean {
        val latch = CountDownLatch(1)
        var ready = false
        runCatching {
            browser.evaluateJavaScript("(document.readyState==='complete') && !/just a moment|checking your browser/i.test(document.title||'')") { r ->
                ready = r == "true"; latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
        return ready
    }

    private fun hasCfClearance(host: String): Boolean {
        val mgr = runCatching { CefCookieManager.getGlobalManager() }.getOrNull() ?: return false
        val latch = CountDownLatch(1)
        var found = false
        val ok = runCatching {
            mgr.visitAllCookies(object : CefCookieVisitor {
                override fun visit(cookie: CefCookie, curr: Int, total: Int, delete: BoolRef): Boolean {
                    val dom = cookie.domain?.trimStart('.') ?: ""
                    if (cookie.name == "cf_clearance" && (host.endsWith(dom) || dom.endsWith(host))) found = true
                    if (curr + 1 >= total) latch.countDown()
                    return true
                }
            })
        }.getOrDefault(false)
        if (!ok) return false
        latch.await(2, TimeUnit.SECONDS)
        return found
    }

    /**
     * Delete cookies for [host] (e.g. "mangafire.to"), or ALL cookies when null. Returns how many were
     * removed. Live — the pooled browsers read the emptied jar on their next request. Clearing a host's
     * cf_clearance forces a fresh Cloudflare challenge (the recovery lever for a stuck managed challenge);
     * a null/all clear also logs you out of any WebView source you'd signed into.
     */
    fun clearCookies(host: String? = null): Int {
        val mgr = runCatching { CefCookieManager.getGlobalManager() }.getOrNull() ?: return 0
        val target = host?.trimStart('.')
        val latch = CountDownLatch(1)
        var n = 0
        val ok = runCatching {
            mgr.visitAllCookies(object : CefCookieVisitor {
                override fun visit(cookie: CefCookie, curr: Int, total: Int, delete: BoolRef): Boolean {
                    val dom = cookie.domain?.trimStart('.') ?: ""
                    val match = target == null || (dom.isNotEmpty() && (target.endsWith(dom) || dom.endsWith(target)))
                    if (match) { delete.set(true); n++ }
                    if (curr + 1 >= total) latch.countDown()
                    return true
                }
            })
        }.getOrDefault(false)
        if (!ok) return 0
        latch.await(3, TimeUnit.SECONDS)
        runCatching { mgr.flushStore(null) }
        log.info { "JCEF: cleared $n cookie(s)${target?.let { " for $it" } ?: " (all)"}" }
        return n
    }

    /** One cookie host bucket for the dev cookie picker. */
    data class CookieHost(val host: String, val count: Int, val hasClearance: Boolean)

    /**
     * Every host that currently holds cookies in the shared jar, with its cookie count and whether it
     * has a cf_clearance. Groups by cookie domain (leading dot stripped). Backs the dev-menu host picker.
     */
    fun cookieHosts(): List<CookieHost> {
        val mgr = runCatching { CefCookieManager.getGlobalManager() }.getOrNull() ?: return emptyList()
        val latch = CountDownLatch(1)
        val counts = HashMap<String, Int>()
        val clearance = HashSet<String>()
        val ok = runCatching {
            mgr.visitAllCookies(object : CefCookieVisitor {
                override fun visit(cookie: CefCookie, curr: Int, total: Int, delete: BoolRef): Boolean {
                    if (total == 0) { latch.countDown(); return false }
                    val dom = cookie.domain?.trimStart('.') ?: ""
                    if (dom.isNotEmpty()) {
                        counts[dom] = (counts[dom] ?: 0) + 1
                        if (cookie.name == "cf_clearance") clearance.add(dom)
                    }
                    if (curr + 1 >= total) latch.countDown()
                    return true
                }
            })
        }.getOrDefault(false)
        if (!ok) return emptyList()
        latch.await(2, TimeUnit.SECONDS)
        return counts.entries
            .map { CookieHost(it.key, it.value, it.key in clearance) }
            .sortedWith(compareByDescending<CookieHost> { it.hasClearance }.thenByDescending { it.count }.thenBy { it.host })
    }

    private fun buildFetchJs(url: String, method: String, headers: Map<String, String>, body: String?): String {
        // Only forward safe, meaningful headers — the page/browser sets UA, Referer, Cookie, encoding itself.
        val skip = setOf("host", "cookie", "user-agent", "referer", "content-length", "accept-encoding", "connection")
        val hdrObj = headers.filterKeys { it.lowercase() !in skip }
        val jUrl = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(url))
        val jMethod = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(method))
        val jHeaders = json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.JsonObject(hdrObj.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }),
        )
        val jBody = if (body == null) "null" else Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(body))
        // Must `return` the promise: the eval wrapper turns this into a payload() body, and only an
        // explicit return makes payload() return the promise for Promise.resolve(...) to await.
        return """
            var opts = {method:$jMethod, headers:$jHeaders, credentials:'include'};
            if ($jBody !== null) opts.body = $jBody;
            return fetch($jUrl, opts).then(function(r){
              return r.text().then(function(t){
                var h={}; r.headers.forEach(function(v,k){h[k]=v;});
                return JSON.stringify({status:r.status, headers:h, body:t});
              });
            }).catch(function(e){ return JSON.stringify({status:0, error:String(e)}); });
        """.trimIndent()
    }
}
