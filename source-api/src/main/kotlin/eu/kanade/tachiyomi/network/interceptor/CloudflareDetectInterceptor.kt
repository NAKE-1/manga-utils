package eu.kanade.tachiyomi.network.interceptor

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Detection-only Cloudflare interceptor. Uses Suwayomi's exact challenge condition
 * (HTTP 403/503 with a `Server: cloudflare` header). We don't solve the challenge (no
 * FlareSolverr / browser), so we surface a clear error instead of letting the source parse an
 * empty challenge page as "no results".
 */
class CloudflareDetectInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (isCloudflareChallenge(response)) {
            response.close()
            throw IOException(
                "Cloudflare protection is blocking this source (HTTP ${response.code}). " +
                    "A Cloudflare bypass isn't supported yet.",
            )
        }
        return response
    }

    /**
     * Suwayomi's rule (403/503 + `Server: cloudflare`) catches hard blocks, but Cloudflare also
     * serves interstitial JS challenges with HTTP 200. So additionally, for any cloudflare-served
     * HTML page, peek the body for challenge markers (peekBody does not consume the real stream).
     */
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
        private val ERROR_CODES = listOf(403, 503)
        private val SERVER_CHECK = listOf("cloudflare-nginx", "cloudflare")
        private val CHALLENGE_MARKERS =
            listOf("challenge-platform", "cf-browser-verification", "_cf_chl", "cf_chl_opt", "Just a moment", "cf-mitigated")
    }
}
