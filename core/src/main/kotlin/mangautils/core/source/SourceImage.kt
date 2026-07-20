/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.source

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.slf4j.LoggerFactory

/**
 * Streams images straight from a source (covers + reader pages) using that source's own OkHttp
 * client and headers — so reading works without downloading, exactly like Suwayomi.
 */
object SourceImage {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Fetch an arbitrary image (e.g. a cover thumbnail) with the source's headers. */
    fun coverBytes(
        sourceId: Long,
        url: String,
    ): ByteArray? {
        if (url.isBlank()) return null
        // Covers deliberately do NOT use SourceCircuits.images. That breaker protects the READER from
        // hammering a dead CDN mid-chapter — but a grid loads ~40 covers at once, and a source that 404s
        // a few genuinely-missing posters (normal for atsu.moe etc.) would trip the threshold and then
        // blackhole EVERY cover for that source for the whole cooldown → "No Poster" across the grid.
        // A cover miss must fail only that one card, never cascade; so each fetch stands alone here.
        val src = SourceManager.loadSource(sourceId) as? HttpSource ?: return null
        var gone = false // image is permanently missing rather than the source being in trouble
        return try {
            val request = Request.Builder().url(url).headers(src.headers).build()
            src.client.newCall(request).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
        } catch (e: Exception) {
            log.debug("cover fetch failed {}: {}", url, e.message)
            null
        }
    }

    /** The page list for a chapter (no download — just the page descriptors). */
    fun pageList(
        sourceId: Long,
        chapterUrl: String,
    ): List<Page> {
        val src = SourceManager.loadSource(sourceId) ?: return emptyList()
        val chapter = SChapter.create().apply { url = chapterUrl; name = "" }
        return try {
            runBlocking { src.getPageList(chapter) }
        } catch (e: Exception) {
            log.warn("page list failed for {}: {}", chapterUrl, e.message)
            emptyList()
        }
    }

    /** Blocking page fetch for the CLI/downloader. The server uses [pageBytesAsync] (cancellable). */
    fun pageBytes(
        sourceId: Long,
        page: Page,
    ): ByteArray? = runBlocking { pageBytesAsync(sourceId, page) }

    /**
     * Cancellable page fetch for the web reader. Called directly (no runBlocking) on the image pool,
     * so when you leave a stuck reader the request is cancelled — the OkHttp call and the browser
     * connection free immediately instead of holding one of the browser's ~6 connections for the
     * full timeout (which otherwise blocks library/search/covers to the same host).
     *
     * Timeout is short: a down CDN (e.g. atsu.moe) should surface a retry fast, not saturate the
     * connection pool for 12s per page.
     */
    suspend fun pageBytesAsync(
        sourceId: Long,
        page: Page,
    ): ByteArray? {
        if (SourceCircuits.images.isOpen(sourceId)) return null // breaker open (dead CDN) → instant fail
        val src = SourceManager.loadSource(sourceId) as? HttpSource ?: return null
        var gone = false // image is permanently missing rather than the source being in trouble
        return try {
            val bytes = withTimeout(7_000) {
                if (page.imageUrl.isNullOrBlank()) page.imageUrl = src.getImageUrl(page)
                src.getImage(page).use { resp ->
                    if (resp.isSuccessful) {
                        resp.body?.bytes()
                    } else {
                        log.warn("page {} failed: HTTP {} {}", page.index, resp.code, page.imageUrl)
                        // A 404/410 means THIS image is gone, not that the source is unhealthy. The breaker
                        // is source-wide, so counting these lets one dead chapter fast-fail every image from
                        // the source for 20s - including the chapter you switch to when the dead one fails.
                        // 429 and 5xx are genuine source trouble and still count.
                        if (resp.code in 400..499 && resp.code != 429) gone = true
                        null
                    }
                }
            }
            if (bytes != null) {
                SourceCircuits.images.recordSuccess(sourceId)
            } else if (!gone) {
                SourceCircuits.images.recordFailure(sourceId)
            }
            bytes
        } catch (e: Exception) {
            // A plain cancellation (you navigated away) isn't the source's fault — don't trip the breaker.
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            SourceCircuits.images.recordFailure(sourceId)
            log.warn("page {} failed: {} ({})", page.index, e.message ?: e::class.simpleName, page.imageUrl)
            null
        }
    }
}
