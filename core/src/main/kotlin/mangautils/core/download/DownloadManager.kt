/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.download

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mangautils.core.config.AppConfig
import mangautils.core.config.SettingsStore
import mangautils.core.convert.CbzWriter
import mangautils.core.convert.ComicInfoData
import mangautils.core.convert.FolderWriter
import mangautils.core.convert.ImageFormat
import mangautils.core.convert.PageImage
import mangautils.core.source.SourceManager
import mangautils.core.status.Job
import mangautils.core.status.JobAttempt
import mangautils.core.status.JobState
import mangautils.core.status.StatusStore
import org.slf4j.LoggerFactory
import java.nio.file.Path

/** Points at a manga on a particular source. The primary plus any mirrors form a fallback chain. */
data class SourceRef(
    val sourceId: Long,
    val mangaUrl: String,
)

/** Which chapters of the manga to download. */
sealed interface ChapterSelect {
    data class First(val n: Int) : ChapterSelect

    data class Latest(val n: Int) : ChapterSelect

    data class Numbers(val numbers: Set<Float>) : ChapterSelect

    /** Exact chapters by their url (used by the library tracker to grab specific new chapters). */
    data class Urls(val urls: Set<String>) : ChapterSelect

    /** Every chapter. */
    data object All : ChapterSelect

    /** Only chapters whose output file is not already on disk. */
    data object Missing : ChapterSelect

    /** Chapters whose number is within [from]..[to] inclusive. */
    data class Range(val from: Float, val to: Float) : ChapterSelect
}

/**
 * Downloads chapters to CBZ, with a per-chapter **multi-source fallback chain**: it tries the
 * primary source, and on any hard failure cascades to each mirror (matched by chapter number),
 * recording every attempt to the [StatusStore] for a verbose trace.
 */
class DownloadManager(
    private val concurrency: Int = 4,
    private val retries: Int = 3,
    private val backoffMs: Long = 500,
    private val listener: DownloadListener? = null,
    private val existingPolicy: ExistingPolicy = ExistingPolicy.SKIP,
    private val existingPrompt: ExistingPrompt? = null,
    private val downloadAsCbz: Boolean = runCatching { SettingsStore.get().downloadAsCbz }.getOrDefault(false),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** When the user picks a *_ALL decision under ASK, it latches for the rest of the run. */
    @Volatile
    private var latchedReplace: Boolean? = null

    private data class Candidate(
        val sourceId: Long,
        val source: HttpSource,
        val chapter: SChapter,
    )

    fun download(
        primary: SourceRef,
        mirrors: List<SourceRef> = emptyList(),
        select: ChapterSelect,
    ): Job {
        val job =
            Job(
                id = StatusStore.newId(),
                type = "download",
                state = JobState.RUNNING,
                target = "${primary.sourceId}:${primary.mangaUrl}",
            )
        StatusStore.save(job)

        try {
            val primarySource = httpSource(primary.sourceId)
            val seed = seed(primary.mangaUrl)
            val (title, details, chapters) =
                runBlocking {
                    val d = primarySource.getMangaDetails(seed)
                    if (runCatching { d.url }.getOrNull().isNullOrBlank()) d.url = primary.mangaUrl
                    val t = runCatching { d.title }.getOrNull()?.takeIf { it.isNotBlank() } ?: titleFromUrl(primary.mangaUrl)
                    Triple(t, d, primarySource.getChapterList(seed))
                }

            val targets = resolveSelection(chapters, select, title)
            if (targets.isEmpty()) error("No chapters matched the selection")
            log.debug("Job {}: {} chapter(s) selected for '{}'", job.id, targets.size, title)

            // Save the cover into the series folder so it's available offline (once per series).
            runCatching { saveCover(primary.sourceId, title, details) }

            // Pre-fetch mirror chapter lists so we can match by chapter number on fallback.
            val mirrorChapters =
                mirrors.mapNotNull { ref ->
                    val src = runCatching { httpSource(ref.sourceId) }.getOrNull() ?: return@mapNotNull null
                    val chs =
                        runCatching { runBlocking { src.getChapterList(seed(ref.mangaUrl)) } }
                            .getOrDefault(emptyList())
                    Triple(ref, src, chs)
                }

            var anySuccess = false
            for (chapter in targets) {
                val candidates =
                    buildList {
                        add(Candidate(primary.sourceId, primarySource, chapter))
                        for ((ref, src, chs) in mirrorChapters) {
                            val target = chapterNumberOf(chapter)
                            val match = chs.firstOrNull { chapterNumberOf(it) == target }
                            if (match != null) add(Candidate(ref.sourceId, src, match))
                        }
                    }
                if (downloadChapter(job, title, details, chapter, candidates)) anySuccess = true
            }

            job.state = if (anySuccess) JobState.DONE else JobState.FAILED
            if (!anySuccess) job.error = "All chapters failed across all candidate sources"
        } catch (e: Exception) {
            job.state = JobState.FAILED
            job.error = e.message ?: e.toString()
            log.error("Job ${job.id} failed", e)
        }
        StatusStore.save(job)
        return job
    }

    /** Try each candidate source in order; first success wins. Returns true if the chapter saved. */
    private fun downloadChapter(
        job: Job,
        title: String,
        details: SManga,
        chapter: SChapter,
        candidates: List<Candidate>,
    ): Boolean {
        val dest = destFor(title, chapter)
        if (java.nio.file.Files.exists(dest) && !shouldReplaceExisting(chapter.name, dest)) {
            job.attempts.add(
                JobAttempt(sourceId = 0, target = chapter.name, outcome = "skipped", message = "already exists: $dest"),
            )
            StatusStore.save(job)
            log.debug("Skipping existing '{}'", chapter.name)
            return true
        }
        for (cand in candidates) {
            val start = System.currentTimeMillis()
            try {
                val images =
                    runBlocking {
                        val pages = cand.source.getPageList(cand.chapter)
                        if (pages.isEmpty()) error("source returned 0 pages")
                        downloadPages(cand.source, pages, chapter.name, cand.sourceId)
                    }
                val dest = destFor(title, chapter)
                if (downloadAsCbz) {
                    CbzWriter.write(dest, images, comicInfo(title, details, chapter, images.size))
                } else {
                    FolderWriter.write(dest, images, comicInfo(title, details, chapter, images.size))
                }
                DownloadStore.invalidate() // a chapter just landed on disk — refresh the manager's cached list

                job.attempts.add(
                    JobAttempt(
                        sourceId = cand.sourceId,
                        target = chapter.name,
                        outcome = "ok",
                        message = "${images.size} pages -> $dest",
                        durationMs = System.currentTimeMillis() - start,
                    ),
                )
                StatusStore.save(job)
                log.debug("Saved '{}' from source {} ({} pages)", chapter.name, cand.sourceId, images.size)
                return true
            } catch (e: Exception) {
                job.attempts.add(
                    JobAttempt(
                        sourceId = cand.sourceId,
                        target = chapter.name,
                        outcome = "failed",
                        message = e.message ?: e.toString(),
                        durationMs = System.currentTimeMillis() - start,
                    ),
                )
                StatusStore.save(job)
                log.warn("Candidate source {} failed for '{}': {} (trying next mirror)", cand.sourceId, chapter.name, e.message)
            }
        }
        return false
    }

    /** Download every page with bounded concurrency + retry/backoff + a basic integrity check. */
    private suspend fun downloadPages(
        source: HttpSource,
        pages: List<Page>,
        chapterName: String,
        sourceId: Long,
    ): List<PageImage> =
        coroutineScope {
            val sem = Semaphore(concurrency)
            val total = pages.size
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val bytes = java.util.concurrent.atomic.AtomicLong(0)
            val startNanos = System.nanoTime()
            // Emit an initial 0/total so the UI can show the bar immediately.
            listener?.onProgress(PageProgress(chapterName, sourceId, 0, total, 0, 0))
            pages
                .map { page ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            val image = downloadPage(source, page)
                            val totalBytes = bytes.addAndGet(image.bytes.size.toLong())
                            val d = done.incrementAndGet()
                            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
                            synchronized(this@DownloadManager) {
                                listener?.onProgress(PageProgress(chapterName, sourceId, d, total, totalBytes, elapsedMs))
                            }
                            image
                        }
                    }
                }.awaitAll()
                .sortedBy { it.index }
        }

    private suspend fun downloadPage(
        source: HttpSource,
        page: Page,
    ): PageImage {
        var lastError: Exception? = null
        repeat(retries) { attempt ->
            try {
                if (page.imageUrl.isNullOrBlank()) {
                    page.imageUrl = source.getImageUrl(page)
                }
                source.getImage(page).use { response ->
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    if (!ImageFormat.looksLikeImage(bytes)) {
                        error("page ${page.index} returned ${bytes.size} bytes (not an image)")
                    }
                    return PageImage(page.index, bytes, ImageFormat.extensionFor(bytes))
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt < retries - 1) delay(backoffMs * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("page ${page.index} failed")
    }

    private fun resolveSelection(
        chapters: List<SChapter>,
        select: ChapterSelect,
        title: String,
    ): List<SChapter> {
        // Source chapter lists are newest-first; "first" = oldest, "latest" = newest.
        val oldestFirst = chapters.reversed()
        return when (select) {
            is ChapterSelect.First -> oldestFirst.take(select.n)
            is ChapterSelect.Latest -> chapters.take(select.n)
            is ChapterSelect.Numbers -> oldestFirst.filter { chapterNumberOf(it) in select.numbers }
            is ChapterSelect.Urls -> oldestFirst.filter { it.url in select.urls }
            is ChapterSelect.All -> oldestFirst
            is ChapterSelect.Missing -> oldestFirst.filterNot { java.nio.file.Files.exists(destFor(title, it)) }
            is ChapterSelect.Range -> oldestFirst.filter { chapterNumberOf(it) in select.from..select.to }
        }
    }

    /**
     * The chapter's number. Falls back to parsing it out of the chapter name when the source
     * doesn't populate `chapter_number` (common for some sites, e.g. MangaKatana).
     */
    private fun chapterNumberOf(ch: SChapter): Float {
        if (ch.chapter_number >= 0f) return ch.chapter_number
        val byKeyword = Regex("(?i)chapter\\s*([0-9]+(?:\\.[0-9]+)?)").find(ch.name)
        val any = byKeyword ?: Regex("([0-9]+(?:\\.[0-9]+)?)").find(ch.name)
        return any?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: -1f
    }

    private fun comicInfo(
        title: String,
        details: SManga,
        chapter: SChapter,
        pageCount: Int,
    ): ComicInfoData =
        ComicInfoData(
            series = title,
            title = chapter.name,
            number = if (chapter.chapter_number >= 0) chapter.chapter_number.toString() else "",
            writer = details.author,
            penciller = details.artist,
            genre = details.genre,
            summary = details.description,
            pageCount = pageCount,
            web = chapter.url,
        )

    /** Decide whether to overwrite an existing chapter file (true) or skip it (false). */
    private fun shouldReplaceExisting(
        chapterName: String,
        dest: Path,
    ): Boolean {
        latchedReplace?.let { return it }
        return when (existingPolicy) {
            ExistingPolicy.SKIP -> false
            ExistingPolicy.REPLACE -> true
            ExistingPolicy.ASK ->
                when (existingPrompt?.decide(chapterName, dest) ?: ExistingDecision.SKIP) {
                    ExistingDecision.SKIP -> false
                    ExistingDecision.REPLACE -> true
                    ExistingDecision.SKIP_ALL -> { latchedReplace = false; false }
                    ExistingDecision.REPLACE_ALL -> { latchedReplace = true; true }
                }
        }
    }

    /** Fetch + write the manga cover into the series folder as cover.<ext>, once (offline covers). */
    private fun saveCover(sourceId: Long, title: String, details: SManga) {
        val url = runCatching { details.thumbnail_url }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        if (localCover(title) != null) return
        val bytes = runCatching { mangautils.core.source.SourceImage.coverBytes(sourceId, url) }.getOrNull() ?: return
        val dir = AppConfig.downloadsDir.resolve(sanitize(title))
        runCatching {
            java.nio.file.Files.createDirectories(dir)
            java.nio.file.Files.write(dir.resolve("cover." + coverExt(bytes)), bytes)
        }
    }

    private fun coverExt(b: ByteArray): String = when {
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpg"
        b.size >= 4 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() -> "png"
        b.size >= 12 && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() -> "webp"
        b.size >= 3 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() -> "gif"
        else -> "jpg"
    }

    private fun destFor(
        title: String,
        chapter: SChapter,
    ): Path {
        val base = AppConfig.downloadsDir.resolve(sanitize(title))
        return if (downloadAsCbz) base.resolve(sanitize(chapter.name) + ".cbz") else base.resolve(sanitize(chapter.name))
    }

    private fun seed(url: String): SManga = SManga.create().apply { this.url = url; title = url }

    /** Best-effort readable title from a manga url slug, e.g. "/manga/naruto.1205" -> "Naruto". */
    private fun titleFromUrl(url: String): String {
        var slug = url.trimEnd('/').substringAfterLast('/')
        slug = slug.replace(Regex("\\.\\d+$"), "") // strip a trailing numeric id like ".1205"
        return slug
            .replace('-', ' ')
            .replace('_', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
            .ifBlank { url }
    }

    private fun httpSource(sourceId: Long): HttpSource =
        SourceManager.loadSource(sourceId) as? HttpSource
            ?: error("Source $sourceId is not installed, or is not an HTTP source")

    companion object {
        fun sanitize(name: String): String =
            name
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(150)
                .ifBlank { "untitled" }

        /** True if this chapter is downloaded — as a CBZ file or a (non-empty) image folder. */
        fun isDownloaded(
            title: String,
            chapterName: String,
        ): Boolean {
            val base = AppConfig.downloadsDir.resolve(sanitize(title))
            if (java.nio.file.Files.exists(base.resolve(sanitize(chapterName) + ".cbz"))) return true
            val folder = base.resolve(sanitize(chapterName))
            return java.nio.file.Files.isDirectory(folder) &&
                runCatching { java.nio.file.Files.list(folder).use { it.findFirst().isPresent } }.getOrDefault(false)
        }

        /** Number of downloaded chapters (CBZ files + image folders) for a series. */
        fun downloadCount(title: String): Int {
            val dir = AppConfig.downloadsDir.resolve(sanitize(title))
            if (!java.nio.file.Files.isDirectory(dir)) return 0
            return runCatching {
                java.nio.file.Files.list(dir).use { st ->
                    st.filter { java.nio.file.Files.isDirectory(it) || it.toString().endsWith(".cbz") }.count().toInt()
                }
            }.getOrDefault(0)
        }

        /** The on-disk cover for a downloaded series (cover.jpg/png/webp/…), or null. */
        fun localCover(title: String): java.nio.file.Path? {
            val dir = AppConfig.downloadsDir.resolve(sanitize(title))
            if (!java.nio.file.Files.isDirectory(dir)) return null
            return runCatching {
                java.nio.file.Files.list(dir).use { s -> s.filter { it.fileName.toString().startsWith("cover.") }.findFirst().orElse(null) }
            }.getOrNull()
        }

        /** Delete all downloaded files for a series. Returns true if anything was removed. */
        fun deleteDownloads(title: String): Boolean {
            val dir = AppConfig.downloadsDir.resolve(sanitize(title))
            if (!java.nio.file.Files.exists(dir)) return false
            return runCatching {
                java.nio.file.Files.walk(dir).use { st -> st.sorted(Comparator.reverseOrder()).forEach { java.nio.file.Files.deleteIfExists(it) } }
                true
            }.getOrDefault(false)
        }
    }
}
