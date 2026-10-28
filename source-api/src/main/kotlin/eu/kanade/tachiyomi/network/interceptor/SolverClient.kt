package eu.kanade.tachiyomi.network.interceptor

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.TimeUnit

/**
 * Client for the anti-detect solver sidecar (Plan #3). Called from the NETWORK interceptor
 * ([JcefFetchInterceptor]) so the request already carries the extension's `vrf` query (app interceptors
 * don't see it). The solver itself gets cf_clearance from FlareSolverr, replays the request with curl_cffi
 * (real Chrome TLS fingerprint) + XHR headers, and solves MangaFire's /@waf shapes captcha in-session — so
 * we just hand it the fully-built URL and get back real JSON.
 */
object SolverClient {
    private val log = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS)
        .build()
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val SKIP = setOf("host", "cookie", "user-agent", "referer", "content-length", "accept-encoding", "connection")
    // Per-host throttle for the re-warm-on-403: at most one reset+retry per host per window, so a burst of
    // failures (e.g. a long MangaFire download whose session got re-challenged) re-warms once, not per chapter.
    private val lastRewarm = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val rewarmLocks = java.util.concurrent.ConcurrentHashMap<String, Any>() // per-host recovery lock
    private const val REWARM_COOLDOWN_MS = 30_000L

    /** Drop the solver's per-host sessions + cached cf_clearance (part of the egress reset — the clearance
     *  is IP-bound, so a new exit node must re-solve). Best-effort. */
    fun reset() {
        val base = SolverConfig.url?.trimEnd('/') ?: return
        val req = Request.Builder().url("$base/reset").post(ByteArray(0).toRequestBody(null)).build()
        runCatching { client.newCall(req).execute().use { } }
            .onSuccess { log.info { "solver: session/clearance reset" } }
            .onFailure { log.info { "solver reset failed: ${it.message}" } }
    }

    /** Run [request] (with its vrf) through the solver. Null on any failure so the caller can fall back. */
    fun fetch(request: Request): Response? = doFetch(request, rewarm = true)

    private fun doFetch(request: Request, rewarm: Boolean): Response? {
        val base = SolverConfig.url?.trimEnd('/') ?: return null
        val hdrs = request.headers.names().filter { it.lowercase() !in SKIP }.associateWith { request.headers[it] ?: "" }
        val payload = json.encodeToString(SolverReq.serializer(), SolverReq(request.url.toString(), hdrs))
        val req = Request.Builder().url("$base/fetch").post(payload.toRequestBody(JSON_MEDIA)).build()
        val text = runCatching { client.newCall(req).execute().use { it.body?.string().orEmpty() } }
            .getOrElse { log.info { "solver unreachable at $base: ${it.message}" }; return null }
        if (text.isBlank()) return null
        val out = runCatching { json.decodeFromString(SolverResp.serializer(), text) }.getOrNull() ?: return null
        if (out.status !in 200..399 || out.body.isNullOrEmpty()) {
            // Log a snippet of the blocked body so the challenge is identifiable (CF "Just a moment", MangaFire
            // /@waf shapes, a rate-limit page, or an app error) instead of just a byte count.
            val snippet = out.body?.take(220)?.replace(Regex("\\s+"), " ")?.trim()
            log.info { "solver ${request.url.host}${request.url.encodedPath}: status ${out.status}, ${out.body?.length ?: 0}B${out.error?.let { " err=$it" } ?: ""}${snippet?.let { " body=[$it]" } ?: ""}" }
            // A 403/503 mid-run is usually a stale cf_clearance / CF re-challenge. Recover per host under a
            // lock so a burst of concurrent 403s (a parallel scan hits many at once) rides ONE re-warm: the
            // first thread resets + re-clears CF while holding the lock, the rest block then retry against the
            // now-warm session instead of failing. The reset itself is still throttled (a persistently-blocked
            // host re-warms at most once per window), so a hard block can't reset in a tight loop.
            if (rewarm && (out.status == 403 || out.status == 503)) {
                val host = request.url.host
                synchronized(rewarmLocks.computeIfAbsent(host) { Any() }) {
                    if (System.currentTimeMillis() - (lastRewarm[host] ?: 0L) > REWARM_COOLDOWN_MS) {
                        lastRewarm[host] = System.currentTimeMillis()
                        log.info { "solver ${out.status} on $host → re-warming session + retrying" }
                        reset()
                    }
                    return doFetch(request, rewarm = false)
                }
            }
            return null
        }
        // Toast ONLY when the solver actually did work — a fresh /@waf captcha solve or a fresh Cloudflare
        // clear. Warm hits (routine traffic reusing the session) stay silent, so "MF solver" isn't pinned
        // "working…" during a whole library check.
        val evHost = out.host ?: request.url.host
        when {
            out.wafSolved -> SolverConfig.record(evHost, "solved")
            out.cfFresh -> SolverConfig.record(evHost, "cleared")
        }
        val ct = if (request.url.encodedPath.contains("/api", true)) "application/json; charset=utf-8" else "text/html; charset=utf-8"
        log.info { "solver ${request.url.host}${request.url.encodedPath}: ${out.body.length}B (status ${out.status})" }
        return Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1)
            .code(out.status).message("OK (solver)")
            .header("Content-Type", ct)
            .body(out.body.toResponseBody(ct.toMediaType()))
            .build()
    }

    @Serializable
    private data class SolverReq(val url: String, val headers: Map<String, String>)

    @Serializable
    private data class SolverResp(
        val status: Int = 0,
        val body: String? = null,
        val error: String? = null,
        @kotlinx.serialization.SerialName("waf_solved") val wafSolved: Boolean = false,
        @kotlinx.serialization.SerialName("cf_fresh") val cfFresh: Boolean = false,
        val host: String? = null,
    )
}
