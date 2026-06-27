/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.source

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/** Details of a manga plus its chapter list. */
data class MangaDetails(
    val manga: SManga,
    val chapters: List<SChapter>,
)

/**
 * Drives a loaded source's network operations (search / popular / details / chapters).
 * Bridges the source's suspend API to the synchronous CLI via [runBlocking].
 */
object SourceBrowser {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun catalogue(sourceId: Long): CatalogueSource =
        SourceManager.loadSource(sourceId) as? CatalogueSource
            ?: error("Source $sourceId is not installed, or does not support browsing")

    fun search(
        sourceId: Long,
        query: String,
        page: Int = 1,
    ): MangasPage {
        val source = catalogue(sourceId)
        log.debug("search source={} query='{}' page={}", sourceId, query, page)
        return runBlocking { source.getSearchManga(page, query, source.getFilterList()) }
    }

    fun popular(
        sourceId: Long,
        page: Int = 1,
    ): MangasPage {
        val source = catalogue(sourceId)
        log.debug("popular source={} page={}", sourceId, page)
        return runBlocking { source.getPopularManga(page) }
    }

    fun latest(
        sourceId: Long,
        page: Int = 1,
    ): MangasPage {
        val source = catalogue(sourceId)
        log.debug("latest source={} page={}", sourceId, page)
        return runBlocking { source.getLatestUpdates(page) }
    }

    /** Fetch full details + chapter list for the manga at [url] on [sourceId]. */
    fun details(
        sourceId: Long,
        url: String,
    ): MangaDetails {
        val source =
            SourceManager.loadSource(sourceId)
                ?: error("Source $sourceId is not installed")
        val seed = SManga.create().apply { this.url = url; title = url }
        log.debug("details source={} url={}", sourceId, url)
        return runBlocking {
            // getMangaDetails returns a (possibly fresh) SManga that often omits url/title since
            // they were the inputs; those fields are lateinit, so guard access and backfill.
            val details = source.getMangaDetails(seed)
            if (runCatching { details.url }.getOrNull().isNullOrBlank()) details.url = url
            if (runCatching { details.title }.getOrNull().isNullOrBlank()) details.title = seed.title
            // Pass the seed (guaranteed url) to chapter listing.
            val chapters = source.getChapterList(seed)
            MangaDetails(details, chapters)
        }
    }

    /** Human-readable status label for an [SManga.status] code. */
    fun statusLabel(status: Int): String =
        when (status) {
            SManga.ONGOING -> "Ongoing"
            SManga.COMPLETED -> "Completed"
            SManga.LICENSED -> "Licensed"
            SManga.PUBLISHING_FINISHED -> "Publishing finished"
            SManga.CANCELLED -> "Cancelled"
            SManga.ON_HIATUS -> "On hiatus"
            else -> "Unknown"
        }
}
