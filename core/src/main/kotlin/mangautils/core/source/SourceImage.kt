/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.source

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
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
        val src = SourceManager.loadSource(sourceId) as? HttpSource ?: return null
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

    /** Fetch a single page's image bytes (resolving a lazy image url if needed). */
    fun pageBytes(
        sourceId: Long,
        page: Page,
    ): ByteArray? {
        val src = SourceManager.loadSource(sourceId) as? HttpSource ?: return null
        return try {
            runBlocking {
                // Fail fast if the source stalls (e.g. CDN 5xx/hang) so the reader shows a retry
                // instead of spinning on the client's full ~30s socket timeout.
                withTimeout(12_000) {
                    if (page.imageUrl.isNullOrBlank()) page.imageUrl = src.getImageUrl(page)
                    src.getImage(page).use { resp ->
                        if (resp.isSuccessful) {
                            resp.body?.bytes()
                        } else {
                            log.warn("page {} failed: HTTP {} {}", page.index, resp.code, page.imageUrl)
                            null
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Visible at INFO+ so image failures can actually be troubleshot (timeout, reset, 5xx, …).
            log.warn("page {} failed: {} ({})", page.index, e.message ?: e::class.simpleName, page.imageUrl)
            null
        }
    }
}
