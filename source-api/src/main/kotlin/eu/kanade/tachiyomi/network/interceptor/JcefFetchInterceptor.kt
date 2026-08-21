package eu.kanade.tachiyomi.network.interceptor

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
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

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val resp = chain.proceed(req)
        if (!isCloudflareChallenge(resp)) return resp
        // Text/JSON GETs only — images are binary (and usually on an un-gated CDN); let those fall through.
        if (!req.method.equals("GET", ignoreCase = true) || isImageUrl(req.url.encodedPath)) return resp

        val headers = req.headers.names().associateWith { req.headers[it] ?: "" }
        val r = runCatching { JcefFetch.fetch(req.url.toString(), req.method, headers, null) }.getOrNull()
        if (r == null) {
            log.info { "JCEF returned null for ${req.url.host}${req.url.encodedPath} (browser unavailable/timeout) → falling back" }
            return resp // JCEF unavailable / failed → keep the challenge so FlareSolverr can try
        }
        if (r.status !in 200..399) {
            // The real browser itself got a non-2xx — tells us it's an app gate (e.g. MangaFire vrf/session),
            // not our okhttp fingerprint. Body snippet makes the cause unambiguous instead of guessed.
            val host = req.url.host
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
            return resp
        }
        managedFails.remove(req.url.host)
        HumanCheckState.cleared(req.url.host) // a real 2xx means the host is clear again

        resp.close()
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

    private fun isCloudflareChallenge(resp: Response): Boolean {
        if (resp.code != 403 && resp.code != 503) return false
        val server = resp.header("Server")?.lowercase() ?: return false
        return server.contains("cloudflare")
    }

    private fun isImageUrl(path: String): Boolean {
        val p = path.lowercase()
        return IMAGE_EXTS.any { p.endsWith(it) }
    }

    companion object {
        private val IMAGE_EXTS = listOf(".webp", ".jpg", ".jpeg", ".png", ".gif", ".avif", ".bmp")
        private const val ESCALATE_AFTER = 2

        // Consecutive un-clearable managed-challenge ("Just a moment…") hits per host. After ESCALATE_AFTER we
        // stop retry-looping (each attempt burns 45s and re-provokes Cloudflare) and escalate. Reset on any 2xx.
        // Process-wide (companion) so an egress reset can wipe stuck counters after a VPN switch.
        private val managedFails = ConcurrentHashMap<String, Int>()

        /** Clear stuck managed-challenge counters — part of the egress reset (VPN/exit-node switch). */
        fun resetManagedFails() = managedFails.clear()
    }
}
