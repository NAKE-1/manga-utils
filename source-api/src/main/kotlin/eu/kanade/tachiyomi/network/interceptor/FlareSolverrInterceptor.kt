package eu.kanade.tachiyomi.network.interceptor

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.network.PersistentCookieStore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cloudflare handling. Detects a challenge (Suwayomi's rule: 403/503 + `Server: cloudflare`, plus
 * a body-marker peek for 200 interstitials) and, when [FlareSolverrConfig.enabled], asks a running
 * FlareSolverr instance to solve it — storing the returned `cf_clearance` cookie and pinning the
 * solved User-Agent (the clearance cookie is bound to it) — then retries the request once. When the
 * bypass is off (or a solve fails) it surfaces the same clear error as before instead of letting the
 * source parse a challenge page as "no results".
 *
 * Modeled on Suwayomi's CloudflareInterceptor / CFClearance.
 */
class FlareSolverrInterceptor(
    private val cookieStore: PersistentCookieStore,
    private val setUserAgent: (String) -> Unit,
) : Interceptor {
    private val log = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val solvedUa = FlareSolverrConfig.solvedUserAgents // shared with the UA network interceptor

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        solvedUa[request.url.host]?.let { ua ->
            if (request.header("User-Agent") != ua) request = request.newBuilder().header("User-Agent", ua).build()
        }

        val response = chain.proceed(request)
        if (!isCloudflareChallenge(response)) return response

        // Snapshot the code + CF-diagnostic headers BEFORE closing — this is what tells us WHY atsu is
        // blocking (managed challenge vs 503 overload vs rate-limit), instead of guessing.
        val initialCode = response.code
        val initialDiag = cfDiag(response)

        if (!FlareSolverrConfig.enabled) {
            response.close()
            throw IOException(
                "Cloudflare protection is blocking this source (HTTP $initialCode [$initialDiag]). " +
                    "A Cloudflare bypass isn't supported yet.",
            )
        }
        response.close()

        val host = request.url.host
        FlareSolverrConfig.recordSolveStart(host) // so the UI can toast "solving…" during the pause

        // FIRST warm a full session via the site ROOT. A per-URL solve of an /api endpoint only yields
        // cf_clearance; a real page load also sets the SESSION cookie some sites (MangaFire) now require
        // ("Missing token" without it). Load the root, keep its cookies + UA, and retry the original — if
        // that clears it we skip the per-URL loop entirely. Rate-limited so it can't thrash.
        if (shouldWarm(host)) {
            val warmed = runCatching { warmSessionViaRoot(chain, request) }.getOrNull()
            if (warmed != null) {
                if (!isCloudflareChallenge(warmed) && warmed.code != 403) {
                    FlareSolverrConfig.recordSolveDone(host, 0)
                    return warmed
                }
                warmed.close()
            }
        }

        // Try up to twice: on a cold start FlareSolverr's browser sometimes comes back with only
        // __cf_bm (no cf_clearance) or a still-challenged retry — a second solve usually clears it,
        // so smooth over the flaky first attempt instead of hard-failing (comix's cold start).
        val maxAttempts = 2
        for (attempt in 1..maxAttempts) {
            val last = attempt == maxAttempts
            val solution =
                try {
                    solve(request)
                } catch (e: Exception) {
                    if (last) { FlareSolverrConfig.recordSolveFail(host); throw IOException("Cloudflare bypass (FlareSolverr) failed for $host: ${e.message}") }
                    continue
                }
            if (solution == null) {
                if (last) { FlareSolverrConfig.recordSolveFail(host); throw IOException("Cloudflare bypass (FlareSolverr) returned no solution for $host.") }
                continue
            }

            val cookies = solution.cookies.mapNotNull { it.toOkHttp() }
            if (cookies.isNotEmpty()) cookieStore.addAll(request.url, cookies)
            val ua = solution.userAgent?.takeIf { it.isNotBlank() } // clearance is UA-bound
            if (ua != null) {
                setUserAgent(ua)
                solvedUa[host] = ua
            }
            log.info { "FlareSolverr solved $host (try $attempt): stored ${cookies.size} cookie(s) [${cookies.joinToString(", ") { it.name }}]" }

            // The only cookie that clears Cloudflare is cf_clearance. If it's missing and we still have
            // an attempt left, re-solve instead of retrying a request that will just 403 again.
            if (cookies.none { it.name == "cf_clearance" } && !last) continue

            FlareSolverrConfig.recordSolveDone(host, cookies.size)
            // Retry with the solved UA forced (overriding any UA the extension set) + the stored cookies
            // (added automatically by the cookie jar backing cookieStore).
            val retry = request.newBuilder()
            if (ua != null) retry.header("User-Agent", ua)
            val retried = chain.proceed(retry.build())
            if (!isCloudflareChallenge(retried)) { HumanCheckState.cleared(host); return retried } // cleared
            val retriedCode = retried.code
            val retriedDiag = cfDiag(retried)
            retried.close()
            if (last) {
                // A managed/Turnstile challenge (cf-mitigated) can't be cleared by cookie replay — a human
                // must solve it in a WebView. Flag it (any source, not just MangaFire) so the UI prompts.
                if (retriedDiag.contains("cf-mitigated", ignoreCase = true) || initialDiag.contains("cf-mitigated", ignoreCase = true)) {
                    HumanCheckState.needed(host)
                }
                // Suwayomi PR #990: a managed/Turnstile challenge can't be cleared by cookie replay
                // (the clearance is bound to the browser's fingerprint), but FlareSolverr's real browser
                // DID fetch the page — so for TEXT requests (search/browse/details) hand its rendered
                // body back to the extension. Not usable for images (a browser returns HTML, not binary).
                if (request.method.equals("GET", true) && !isImageUrl(request.url)) {
                    val rendered = runCatching { solve(request, returnOnlyCookies = false) }.getOrNull()
                    if (!rendered?.response.isNullOrBlank()) {
                        FlareSolverrConfig.recordSolveDone(host, cookies.size)
                        log.info { "FlareSolverr: cookie replay still blocked on $host — returning its rendered response body instead" }
                        return flareResponse(request, rendered!!)
                    }
                }
                FlareSolverrConfig.recordSolveFail(host)
                throw IOException(
                    "Cloudflare still blocking $host after $attempt FlareSolverr solve(s) - " +
                        "initial HTTP $initialCode [$initialDiag], post-solve HTTP $retriedCode [$retriedDiag]. " +
                        "(cf-mitigated=challenge => managed/Turnstile, cookie can't clear; " +
                        "503 + Retry-After => rate-limit/overload; plain 503 => atsu origin busy.)",
                )
            }
            // not the last attempt → loop and solve once more
        }
        FlareSolverrConfig.recordSolveFail(host)
        throw IOException("Cloudflare bypass failed for $host.")
    }

    /** At most one warm attempt per host per [WARM_COOLDOWN_MS], so a persistently-403 host can't thrash. */
    private fun shouldWarm(host: String): Boolean =
        System.currentTimeMillis() - (warmedAt[host] ?: 0L) > WARM_COOLDOWN_MS

    /**
     * Load the site root through FlareSolverr (a real browser that clears Cloudflare), keep its
     * cf_clearance/session cookies + User-Agent, then retry the original request once. If the warm
     * fails, we just replay the original — no worse than before.
     */
    private fun warmSessionViaRoot(chain: Interceptor.Chain, request: Request): Response {
        val host = request.url.host
        warmedAt[host] = System.currentTimeMillis()
        val root = Request.Builder().url("${request.url.scheme}://$host/").build()
        val sol = runCatching { solve(root) }.getOrNull()
        if (sol != null) {
            val cookies = sol.cookies.mapNotNull { it.toOkHttp() }
            if (cookies.isNotEmpty()) cookieStore.addAll(root.url, cookies)
            sol.userAgent?.takeIf { it.isNotBlank() }?.let { setUserAgent(it); solvedUa[host] = it }
            log.info { "Warmed $host session via root: ${cookies.size} cookie(s) [${cookies.joinToString(", ") { it.name }}]" }
        } else {
            log.info { "Session warm for $host failed — FlareSolverr couldn't clear the root" }
        }
        val retry = request.newBuilder()
        solvedUa[host]?.let { retry.header("User-Agent", it) }
        return chain.proceed(retry.build())
    }

    private fun solve(request: Request, returnOnlyCookies: Boolean = true): FsSolution? {
        val cfg = FlareSolverrConfig
        val isPost = request.method.equals("POST", ignoreCase = true)
        val postData =
            if (isPost) {
                (request.body as? FormBody)?.let { fb ->
                    buildString {
                        for (i in 0 until fb.size) {
                            if (i > 0) append('&')
                            append(fb.encodedName(i)); append('='); append(fb.encodedValue(i))
                        }
                    }
                }
            } else {
                null
            }

        // FlareSolverr requires postData for request.post; if we couldn't extract a form body (JSON
        // body, empty, etc.) fall back to request.get — we only need the cf_clearance cookie, not the
        // POST's actual response.
        val usePost = isPost && !postData.isNullOrEmpty()
        val payload =
            FsReq(
                cmd = if (usePost) "request.post" else "request.get",
                url = request.url.toString(),
                maxTimeout = cfg.timeoutMs,
                session = cfg.session.ifBlank { null },
                sessionTtlMinutes = cfg.sessionTtlMinutes.takeIf { it > 0 },
                returnOnlyCookies = returnOnlyCookies,
                postData = if (usePost) postData else null,
            )

        val client =
            OkHttpClient.Builder()
                .callTimeout(cfg.timeoutMs + 15_000, TimeUnit.MILLISECONDS)
                .readTimeout(cfg.timeoutMs + 10_000, TimeUnit.MILLISECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .build()

        val req =
            Request.Builder()
                .url(cfg.url.trimEnd('/') + "/v1")
                .post(json.encodeToString(FsReq.serializer(), payload).toRequestBody(JSON_MEDIA))
                .build()

        val resp =
            try {
                client.newCall(req).execute()
            } catch (e: IOException) {
                // Couldn't reach FlareSolverr at all — it crashed, was stopped, or the URL is wrong.
                // This is the only signal we get that it died, since nothing polls it between solves.
                FlareSolverrConfig.reportReachable(false, e.message)
                throw e
            }
        resp.use {
            val text = it.body?.string().orEmpty()
            if (text.isBlank()) {
                FlareSolverrConfig.reportReachable(false, "empty response (HTTP ${it.code})")
                throw IOException("empty response from FlareSolverr (HTTP ${it.code})")
            }
            // It answered, so it's alive. A challenge it couldn't solve is the site's doing, not a
            // FlareSolverr outage, so that stays "reachable" and only throws below.
            FlareSolverrConfig.reportReachable(true)
            val parsed = json.decodeFromString(FsResp.serializer(), text)
            if (!parsed.status.equals("ok", ignoreCase = true)) {
                throw IOException(parsed.message.ifBlank { "status=${parsed.status}" })
            }
            return parsed.solution
        }
    }

    private fun isCloudflareChallenge(response: Response): Boolean {
        val cfServed = response.header("Server") in SERVER_CHECK
        if (!cfServed) return false
        if (response.header("cf-mitigated") != null) return true // managed/Turnstile — always a real challenge
        // Peek only when there's a reason (error code or HTML); a JSON 200 stays on the fast path.
        val isError = response.code in ERROR_CODES
        val isHtml = response.header("Content-Type")?.contains("text/html", ignoreCase = true) == true
        if (!isError && !isHtml) return false
        // A real challenge carries a marker. MangaFire's origin answers its vrf/API with a plain 403 +
        // JSON "Missing token" body behind Cloudflare — an app error no cookie/solve can fix — so let it
        // fall through and fail fast instead of running warm + 2 solves + rendered-body per title
        // (that was the ~3.5-min "check for updates" hang across a MangaFire-heavy library).
        // ponytail: marker sniff; a CF challenge shipping none of these markers would slip past — none of ours do.
        return runCatching {
            val body = response.peekBody(256 * 1024).string()
            CHALLENGE_MARKERS.any { body.contains(it, ignoreCase = true) }
        }.getOrDefault(false)
    }

    /**
     * Snapshot of the CF-relevant response headers so the failure log says WHY, not just "blocking":
     * `cf-mitigated: challenge` = managed/Turnstile (a cookie can't clear it); `Retry-After` present =
     * rate-limit/backoff; a plain 503 with neither usually = origin overload (atsu busy under load).
     */
    private fun cfDiag(r: Response): String =
        buildString {
            append("Server=").append(r.header("Server") ?: "?")
            r.header("cf-mitigated")?.let { append(", cf-mitigated=").append(it) }
            r.header("Retry-After")?.let { append(", Retry-After=").append(it) }
            r.header("cf-ray")?.let { append(", cf-ray=").append(it) }
        }

    /** True for page-image requests — the FlareSolverr rendered-body fallback can't serve binary. */
    private fun isImageUrl(url: HttpUrl): Boolean {
        val name = url.encodedPath.substringAfterLast('/').lowercase()
        return IMAGE_EXTS.any { name.endsWith(it) }
    }

    /** Wrap FlareSolverr's rendered page body as an OkHttp response the extension can parse. */
    private fun flareResponse(request: Request, sol: FsSolution): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (sol.status in 100..599) sol.status else 200)
            .message("OK (FlareSolverr)")
            .header("Content-Type", "text/html; charset=utf-8")
            .body((sol.response ?: "").toResponseBody("text/html; charset=utf-8".toMediaType()))
            .build()

    companion object {
        // host -> last session-warm attempt. Process-wide so an egress reset can wipe it after a VPN switch
        // (a warm session is bound to the old exit IP).
        private val warmedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

        /** Clear FlareSolverr warm-session bookkeeping — part of the egress reset. */
        fun resetWarmSessions() = warmedAt.clear()

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val ERROR_CODES = listOf(403, 503)
        private const val WARM_COOLDOWN_MS = 2 * 60_000L // min gap between session-warm attempts per host
        private val SERVER_CHECK = listOf("cloudflare-nginx", "cloudflare")
        private val CHALLENGE_MARKERS =
            listOf("challenge-platform", "cf-browser-verification", "_cf_chl", "cf_chl_opt", "Just a moment", "cf-mitigated")
        private val IMAGE_EXTS = listOf(".webp", ".jpg", ".jpeg", ".png", ".gif", ".avif", ".bmp")
    }
}

// ---- FlareSolverr /v1 protocol ----------------------------------------------------------------

@Serializable
private data class FsReq(
    val cmd: String,
    val url: String,
    val maxTimeout: Long,
    val session: String? = null,
    @SerialName("session_ttl_minutes") val sessionTtlMinutes: Int? = null,
    val returnOnlyCookies: Boolean = true,
    val postData: String? = null,
)

@Serializable
private data class FsResp(
    val status: String = "",
    val message: String = "",
    val solution: FsSolution? = null,
)

@Serializable
data class FsSolution(
    val url: String = "",
    val status: Int = 0,
    val userAgent: String? = null,
    val cookies: List<FsCookie> = emptyList(),
    val response: String? = null, // rendered page body (only when solved with returnOnlyCookies=false)
)

@Serializable
data class FsCookie(
    val name: String,
    val value: String,
    val domain: String = "",
    val path: String = "/",
    val expires: Double = -1.0,
    val httpOnly: Boolean = false,
    val secure: Boolean = false,
) {
    fun toOkHttp(): Cookie? =
        runCatching {
            val host = domain.removePrefix(".").ifBlank { return null }
            val b = Cookie.Builder().name(name).value(value).domain(host).path(path.ifBlank { "/" })
            if (expires > 0) b.expiresAt((expires * 1000).toLong())
            if (secure) b.secure()
            if (httpOnly) b.httpOnly()
            b.build()
        }.getOrNull()
}
