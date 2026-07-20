/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.library

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.runBlocking
import mangautils.core.source.SourceManager
import mangautils.core.util.ChapterNumber
import mangautils.core.util.SlugTitle
import org.slf4j.LoggerFactory

/** Result of checking one followed series for updates. */
data class UpdateResult(
    val entry: LibraryEntry,
    val newChapters: List<ChapterRef>,
    /** New scans of numbers already tracked. Downloaded like the rest, but not counted as new chapters. */
    val newVersions: List<ChapterRef> = emptyList(),
)

/** Follow/unfollow series and detect new chapters (the tracker core). */
object LibraryService {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Follow a series: fetches details + chapters and stores a snapshot. Idempotent per (source,url). */
    fun add(
        sourceId: Long,
        mangaUrl: String,
        mode: ReadingMode? = null,
    ): LibraryEntry {
        val source =
            SourceManager.loadSource(sourceId)
                ?: error("Source $sourceId is not installed")
        val seed = seed(mangaUrl)
        val (details, chapters) =
            runBlocking {
                val d = source.getMangaDetails(seed)
                d to source.getChapterList(seed)
            }

        val existing = LibraryStore.find(sourceId, mangaUrl)
        val entry =
            (existing ?: LibraryEntry(sourceId = sourceId, mangaUrl = mangaUrl, title = mangaUrl)).apply {
                title = runCatching { details.title }.getOrNull()?.takeIf { it.isNotBlank() } ?: SlugTitle.fromUrl(mangaUrl)
                author = details.author ?: author
                description = details.description ?: description
                thumbnailUrl = details.thumbnail_url ?: thumbnailUrl
                genre = details.genre ?: genre
                status = details.status
                if (mode != null) readingMode = mode
                knownChapters = chapters.map { it.toRef() }.toMutableList()
                lastCheckedAt = System.currentTimeMillis()
            }
        LibraryStore.upsert(entry)
        log.debug("Added '{}' to library ({} chapters)", entry.title, entry.knownChapters.size)
        return entry
    }

    /**
     * Follow using details already in hand (no network) — used by UIs that have just loaded the
     * manga. Reliable + instant, unlike [add] which re-fetches. Returns the saved entry.
     */
    fun addKnown(
        sourceId: Long,
        mangaUrl: String,
        title: String,
        manga: SManga,
        chapters: List<SChapter>,
        mode: ReadingMode? = null,
    ): LibraryEntry {
        val existing = LibraryStore.find(sourceId, mangaUrl)
        val entry =
            (existing ?: LibraryEntry(sourceId = sourceId, mangaUrl = mangaUrl, title = title)).apply {
                this.title = title.ifBlank { runCatching { manga.title }.getOrNull()?.takeIf { it.isNotBlank() } ?: SlugTitle.fromUrl(mangaUrl) }
                author = runCatching { manga.author }.getOrNull() ?: author
                artist = runCatching { manga.artist }.getOrNull() ?: artist
                description = runCatching { manga.description }.getOrNull() ?: description
                thumbnailUrl = runCatching { manga.thumbnail_url }.getOrNull() ?: thumbnailUrl
                genre = runCatching { manga.genre }.getOrNull() ?: genre
                status = runCatching { manga.status }.getOrDefault(0)
                if (mode != null) readingMode = mode
                knownChapters = chapters.map { it.toRef() }.toMutableList()
                lastCheckedAt = System.currentTimeMillis()
            }
        LibraryStore.upsert(entry)
        return entry
    }

    fun isFollowed(
        sourceId: Long,
        mangaUrl: String,
    ): Boolean = LibraryStore.find(sourceId, mangaUrl) != null

    fun list(): List<LibraryEntry> = LibraryStore.list()

    fun remove(
        sourceId: Long,
        mangaUrl: String,
    ): Boolean = LibraryStore.remove(sourceId, mangaUrl)

    /**
     * Re-check [entries] (default: the whole library) for new chapters since the last snapshot,
     * updating each entry's snapshot. Returns one [UpdateResult] per entry.
     */
    fun update(
        entries: List<LibraryEntry> = LibraryStore.list(),
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): List<UpdateResult> {
        val total = entries.size
        return entries.mapIndexed { i, entry ->
            val result = updateOne(entry)
            onProgress?.invoke(i + 1, total)
            result
        }
    }

    private fun updateOne(entry: LibraryEntry): UpdateResult {
        val source = SourceManager.loadSource(entry.sourceId) ?: run {
            log.warn("Source {} for '{}' not installed; skipping", entry.sourceId, entry.title)
            return UpdateResult(entry, emptyList())
        }
        val current =
            runCatching { runBlocking { source.getChapterList(seed(entry.mangaUrl)) } }
                .getOrElse {
                    log.warn("Update failed for '{}': {}", entry.title, it.message)
                    return UpdateResult(entry, emptyList())
                }
        val knownUrls = entry.knownChapters.map { it.url }.toSet()
        // Group by chapter number so a new URL can be told apart: a number we've never had (a real new
        // chapter) vs another scan of one we already track. Same number-keying the download counts use.
        val knownNumbers = entry.knownChapters.map { it.number }.toSet()
        val fresh = current.filter { it.url !in knownUrls }.map { it.toRef() }
        val newChapters = fresh.filter { it.number !in knownNumbers } // -> "!" badge + count
        val newVersions = fresh.filter { it.number in knownNumbers }  // another scan; list marker only
        entry.knownChapters = current.map { it.toRef() }.toMutableList()
        // Accumulate unseen urls (only after the first snapshot exists, so a first sync isn't all "new").
        if (knownUrls.isNotEmpty()) {
            newChapters.forEach { if (it.url !in entry.newChapters) entry.newChapters.add(it.url) }
            newVersions.forEach { if (it.url !in entry.newVersions) entry.newVersions.add(it.url) }
        }
        entry.lastCheckedAt = System.currentTimeMillis()
        LibraryStore.upsert(entry)
        // Both are returned so auto-download still pulls every version - the split is for the badge, not
        // for what gets fetched.
        return UpdateResult(entry, newChapters, newVersions)
    }

    /** Clear the "new chapters" flag for a series (called when the user opens it). */
    fun markSeen(sourceId: Long, mangaUrl: String) {
        val entry = LibraryStore.find(sourceId, mangaUrl) ?: return
        if (entry.newChapters.isNotEmpty() || entry.newVersions.isNotEmpty()) {
            entry.newChapters = mutableListOf()
            entry.newVersions = mutableListOf()
            LibraryStore.upsert(entry)
        }
    }

    /** Drop one chapter from the "new" set (called when it's read) — the badge clears once none remain. */
    fun markChapterSeen(sourceId: Long, mangaUrl: String, chapterUrl: String) {
        val entry = LibraryStore.find(sourceId, mangaUrl) ?: return
        // A read chapter clears from whichever list held it - new-number or new-scan.
        if (entry.newChapters.remove(chapterUrl) or entry.newVersions.remove(chapterUrl)) LibraryStore.upsert(entry)
    }

    private fun SChapter.toRef() =
        ChapterRef(
            url = url,
            name = name,
            number = ChapterNumber.of(this),
            scanlator = runCatching { scanlator }.getOrNull(),
            dateUpload = runCatching { date_upload }.getOrDefault(0),
        )

    private fun seed(url: String): SManga = SManga.create().apply { this.url = url; title = url }
}
