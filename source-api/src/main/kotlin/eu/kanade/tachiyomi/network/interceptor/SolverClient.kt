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

    /** Run [request] (with its vrf) through the solver. Null on any failure so the caller can fall back. */
    fun fetch(request: Request): Response? {
        val base = SolverConfig.url?.trimEnd('/') ?: return null
        val hdrs = request.headers.names().filter { it.lowercase() !in SKIP }.associateWith { request.headers[it] ?: "" }
        val payload = json.encodeToString(SolverReq.serializer(), SolverReq(request.url.toString(), hdrs))
        val req = Request.Builder().url("$base/fetch").post(payload.toRequestBody(JSON_MEDIA)).build()
        val text = runCatching { client.newCall(req).execute().use { it.body?.string().orEmpty() } }
            .getOrElse { log.info { "solver unreachable at $base: ${it.message}" }; return null }
        if (text.isBlank()) return null
        val out = runCatching { json.decodeFromString(SolverResp.serializer(), text) }.getOrNull() ?: return null
        if (out.status !in 200..399 || out.body.isNullOrEmpty()) {
            log.info { "solver ${request.url.host}${request.url.encodedPath}: status ${out.status}, ${out.body?.length ?: 0}B${out.error?.let { " err=$it" } ?: ""}" }
            return null
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
    private data class SolverResp(val status: Int = 0, val body: String? = null, val error: String? = null)
}
