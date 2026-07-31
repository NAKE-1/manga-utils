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
import kotlinx.coroutines.delay
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
        if (page.imageUrl.isNullOrBlank()) {
            runCatching { withTimeout(7_000) { page.imageUrl = src.getImageUrl(page) } }
        }
        // Each MangaFire page is pinned to a specific CDN edge (mfcdn1/2/3); when that edge is dead/blocked,
        // ONLY its images fail (the site itself shows a single "page error" while the rest of the chapter
        // loads). MangaFire serves the same /mf/<hash>/… path from sibling hosts, so on failure we retry the
        // image on a sibling edge. Candidates: the extension's own request first (correct for all sources),
        // then host-rotated variants (empty for non-mfcdn sources, so this is a no-op for them).
        val candidates: List<String?> = buildList {
            add(null) // null → use the extension's getImage(page)
            page.imageUrl?.let { addAll(siblingHosts(it)) }
        }
        var gone = false // image genuinely missing (4xx) → mirrors won't help, stop
        for ((i, alt) in candidates.withIndex()) {
            val bytes = try {
                withTimeout(7_000) {
                    val resp = if (alt == null) {
                        src.getImage(page)
                    } else {
                        src.client.newCall(Request.Builder().url(alt).headers(src.headers).build()).execute()
                    }
                    resp.use {
                        if (it.isSuccessful) {
                            it.body?.bytes()
                        } else {
                            if (it.code in 400..499 && it.code != 429) gone = true
                            log.warn("page {} failed: HTTP {} {}", page.index, it.code, alt ?: page.imageUrl)
                            null
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e // navigated away
                val code = Regex("""HTTP error (\d{3})""").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                if (code != null && code in 400..499 && code != 429) gone = true
                log.warn("page {} failed: {} ({})", page.index, e.message ?: e::class.simpleName, alt ?: page.imageUrl)
                null
            }
            if (bytes != null) { SourceCircuits.images.recordSuccess(sourceId); return bytes }
            if (gone) break // permanently missing → don't waste time on mirrors
            if (i < candidates.lastIndex) delay(200)
        }
        if (!gone) SourceCircuits.images.recordFailure(sourceId)
        return null
    }

    /** MangaFire (and similar) serve the same path from mfcdn1/2/3 — swap the edge number for retries. */
    internal fun siblingHosts(url: String): List<String> {
        val m = Regex("""mfcdn(\d)""").find(url) ?: return emptyList()
        val cur = m.groupValues[1]
        return listOf("1", "2", "3").filter { it != cur }.map { url.replaceFirst("mfcdn$cur", "mfcdn$it") }
    }
}
