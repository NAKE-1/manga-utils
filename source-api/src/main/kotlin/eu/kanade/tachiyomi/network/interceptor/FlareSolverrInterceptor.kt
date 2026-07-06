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
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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

        if (!FlareSolverrConfig.enabled) {
            response.close()
            throw IOException(
                "Cloudflare protection is blocking this source (HTTP ${response.code}). " +
                    "A Cloudflare bypass isn't supported yet.",
            )
        }
        response.close()

        val host = request.url.host
        FlareSolverrConfig.recordSolveStart(host) // so the UI can toast "solving…" during the pause

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
            if (!isCloudflareChallenge(retried)) return retried // cleared
            retried.close()
            if (last) {
                FlareSolverrConfig.recordSolveFail(host)
                throw IOException(
                    "Cloudflare is still blocking $host after $attempt FlareSolverr solve(s) — this " +
                        "site uses a managed/Turnstile challenge that a cookie bypass can't clear.",
                )
            }
            // not the last attempt → loop and solve once more
        }
        FlareSolverrConfig.recordSolveFail(host)
        throw IOException("Cloudflare bypass failed for $host.")
    }

    private fun solve(request: Request): FsSolution? {
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

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (text.isBlank()) throw IOException("empty response from FlareSolverr (HTTP ${resp.code})")
            val parsed = json.decodeFromString(FsResp.serializer(), text)
            if (!parsed.status.equals("ok", ignoreCase = true)) {
                throw IOException(parsed.message.ifBlank { "status=${parsed.status}" })
            }
            return parsed.solution
        }
    }

    private fun isCloudflareChallenge(response: Response): Boolean {
        val cfServed = response.header("Server") in SERVER_CHECK
        if (response.code in ERROR_CODES && cfServed) return true
        if (!cfServed) return false
        if (response.header("Content-Type")?.contains("text/html", ignoreCase = true) != true) return false
        return runCatching {
            val body = response.peekBody(256 * 1024).string()
            CHALLENGE_MARKERS.any { body.contains(it, ignoreCase = true) }
        }.getOrDefault(false)
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val ERROR_CODES = listOf(403, 503)
        private val SERVER_CHECK = listOf("cloudflare-nginx", "cloudflare")
        private val CHALLENGE_MARKERS =
            listOf("challenge-platform", "cf-browser-verification", "_cf_chl", "cf_chl_opt", "Just a moment", "cf-mitigated")
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
