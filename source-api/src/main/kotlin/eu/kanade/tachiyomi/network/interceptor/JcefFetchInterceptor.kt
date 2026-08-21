package eu.kanade.tachiyomi.network.interceptor

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import xyz.nulldev.androidcompat.webkit.JcefFetch
import java.util.concurrent.ConcurrentHashMap

/**
 * A **network** interceptor (so it sees the fully-built request — including the extension's `vrf` query,
 * which app interceptors don't) that, on a Cloudflare challenge, re-runs the exact request through a real
 * Chromium engine (JCEF) instead of OkHttp.
 *
 * This is the actual fix for Cloudflare-gated sources like MangaFire: OkHttp on the desktop JVM can't
 * reproduce Chrome's TLS/JA4 + HTTP/2 fingerprint (see the Conscrypt notes in NetworkHelper), so Cloudflare
 * rejects it no matter what cookies we replay. JCEF *is* Chrome, so its handshake is genuine. If the JCEF
 * fetch fails, we hand the original challenge response back so the FlareSolverr app-interceptor can still
 * try — this only ever *adds* a path, never removes the old one.
 */
class JcefFetchInterceptor : Interceptor {
    private val log = KotlinLogging.logger {}

    // Consecutive un-clearable managed-challenge ("Just a moment…") hits per host. After ESCALATE_AFTER we
    // stop retry-looping (each attempt burns 45s and re-provokes Cloudflare) and escalate. Reset on any 2xx.
    private val managedFails = ConcurrentHashMap<String, Int>()

    // host+section (see [learnKey]) proven to clear only via the browser — okhttp 403s on its TLS/H2
    // fingerprint there, never on a missing cookie, so re-trying okhttp is pure waste. Session-scoped:
    // re-learned after a restart, so a source changing its behavior costs at most one relearn.
    private val jcefFirst: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        // Text/JSON GETs only — images are binary (and usually on an un-gated CDN); let those fall through.
        val textGet = req.method.equals("GET", ignoreCase = true) && !isImageUrl(req.url.encodedPath)

        // Learned fast path: a host+section we've seen clear ONLY via the browser — skip the doomed okhttp
        // round-trip (and its challenge redirect) and go straight to JCEF. If the browser is unavailable,
        // fall back to a single normal okhttp attempt (no second JCEF — we just tried).
        if (textGet && learnKey(req.url) in jcefFirst) {
            viaJcef(req)?.let { return it }
            return chain.proceed(req)
        }

        val resp = chain.proceed(req)
        if (!isCloudflareChallenge(resp) || !textGet) return resp

        // okhttp hit a CF challenge → run the exact request through the real browser. On a genuine 2xx,
        // viaJcef LEARNS this host+section so the next request skips okhttp entirely.
        val jcef = viaJcef(req)
        if (jcef == null) return resp // JCEF unavailable / non-2xx → keep the challenge so FlareSolverr can try
        resp.close()
        return jcef
    }

    /**
     * Run [req] through JCEF (real Chromium). Returns a built [Response] on a genuine 2xx — and records the
     * host+section in [jcefFirst] so future requests short-circuit okhttp — or null to fall back to okhttp,
     * after the human-check / managed-challenge bookkeeping for a non-2xx browser result.
     */
    private fun viaJcef(req: Request): Response? {
        val host = req.url.host
        val headers = req.headers.names().associateWith { req.headers[it] ?: "" }
        val r = runCatching { JcefFetch.fetch(req.url.toString(), req.method, headers, null) }.getOrNull()
        if (r == null) {
            log.info { "JCEF returned null for $host${req.url.encodedPath} (browser unavailable/timeout) → falling back" }
            return null
        }
        if (r.status !in 200..399) {
            // The real browser itself got a non-2xx — tells us it's an app gate (e.g. MangaFire vrf/session),
            // not our okhttp fingerprint. Body snippet makes the cause unambiguous instead of guessed.
            if (HumanCheckState.isHumanChallenge(r.body)) {
                HumanCheckState.needed(host) // an interactive captcha → prompt the user (WebView)
                managedFails.remove(host)
                log.info { "JCEF human-check required for $host${req.url.encodedPath} → flagged for the user" }
            } else {
                // Cloudflare's managed challenge ("Just a moment…") — no shapes, and JCEF's silent re-clear
                // keeps timing out. After a couple tries, dump this host's cf_clearance (so the next attempt
                // re-challenges fresh) and flag a human-check so the queue PAUSES + resumes-on-clear instead
                // of 45s-looping to FAILED; attended, the WebView can clear it. Unattended, the queue cooldown
                // rests the source until Cloudflare's flag decays.
                val fails = managedFails.merge(host, 1, Int::plus) ?: 1
                if (fails >= ESCALATE_AFTER) {
                    JcefFetch.clearCookies(host)
                    HumanCheckState.needed(host)
                    managedFails.remove(host)
                    log.info { "JCEF managed challenge stuck for $host after $fails tries → flushed cf_clearance + flagged human-check" }
                } else {
                    log.info { "JCEF ${r.status} managed challenge for $host${req.url.encodedPath} (try $fails) → falling back" }
                }
            }
            return null
        }
        managedFails.remove(host)
        HumanCheckState.cleared(host) // a real 2xx means the host is clear again
        jcefFirst.add(learnKey(req.url)) // learn: this host+section clears via JCEF → skip okhttp next time

        val contentType = r.headers["content-type"] ?: "application/json; charset=utf-8"
        return Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_2)
            .code(r.status)
            .message("OK (JCEF)")
            .header("content-type", contentType)
            .body(r.body.toResponseBody(contentType.toMediaTypeOrNull()))
            .build()
    }

    /** Learn/lookup key = host + first path segment (e.g. `mangafire.to/api`). Coarse enough that one
     *  learned API path covers every title id; narrow enough that the okhttp-fine warmup paths
     *  (`/` → `mangafire.to/`, `/@waf/challenge` → `mangafire.to/@waf`) are NOT short-circuited. */
    private fun learnKey(url: HttpUrl): String = url.host + "/" + url.pathSegments.firstOrNull().orEmpty()

    private fun isCloudflareChallenge(resp: Response): Boolean {
        if (resp.code != 403 && resp.code != 503) return false
        val server = resp.header("Server")?.lowercase() ?: return false
        return server.contains("cloudflare")
    }

    private fun isImageUrl(path: String): Boolean {
        val p = path.lowercase()
        return IMAGE_EXTS.any { p.endsWith(it) }
    }

    private companion object {
        private val IMAGE_EXTS = listOf(".webp", ".jpg", ".jpeg", ".png", ".gif", ".avif", ".bmp")
        private const val ESCALATE_AFTER = 2
    }
}
