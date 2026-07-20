package mangautils.server

import kotlinx.serialization.Serializable
import mangautils.core.config.AppConfig
import mangautils.core.download.ChapterIdentity
import mangautils.core.download.DownloadManager
import mangautils.core.download.UnavailableChapters
import mangautils.core.library.LibraryEntry
import mangautils.core.library.LibraryStore
import org.slf4j.LoggerFactory
import java.nio.file.Files

/**
 * Works out which scanlations of a chapter you *don't* have yet, and can queue them.
 *
 * The point is the dry run. Keeping every version of every chapter is roughly a 3x storage increase on
 * a source like atsu, so this reports exactly what it would fetch — per chapter, with a size estimate —
 * and downloads nothing until asked. Verify on one series, read the log, then decide.
 *
 * Execution deliberately reuses the normal download queue rather than adding a second progress system:
 * fewer moving parts, and Stop/Resume/Retry already work.
 */
object ScanVersions {
    private val log = LoggerFactory.getLogger(javaClass)

    /** One chapter number and what we hold of it versus what the source offers. */
    @Serializable
    data class ChapterPlan(
        val number: Float,
        val name: String,
        val have: List<String>,
        val missing: List<String>,
        val missingUrls: List<String>,
    )

    @Serializable
    data class SeriesPlan(
        val sourceId: Long,
        val mangaUrl: String,
        val title: String,
        val numbers: Int,
        val versionsOnDisk: Int,
        val versionsAtSource: Int,
        val missing: Int,
        val estBytes: Long,
        val chapters: List<ChapterPlan>,
    )

    @Serializable
    data class Plan(
        val series: List<SeriesPlan>,
        val totalMissing: Int,
        val totalEstBytes: Long,
        val scope: String,
    )

    /**
     * Dry run. [title] limits it to one series — always do that first; the whole-library number is
     * there to be looked at, not acted on blind.
     *
     * Uses the chapter list already cached on the library entry, so this costs no network and can be
     * re-run freely.
     */
    fun plan(title: String? = null): Plan {
        val entries = LibraryStore.list().filter { title == null || it.title == title }
        val series = entries.map { planFor(it, detail = title != null) }.filter { it.missing > 0 || title != null }
        return Plan(
            series = series.sortedByDescending { it.missing },
            totalMissing = series.sumOf { it.missing },
            totalEstBytes = series.sumOf { it.estBytes },
            scope = title ?: "whole library",
        )
    }

    private fun planFor(
        entry: LibraryEntry,
        detail: Boolean,
    ): SeriesPlan {
        val onDisk = ChapterIdentity.versionsOf(entry.title).filter { it.complete }
        // Treat unavailable chapters as accounted for: they are not fetchable, so listing them as
        // missing would overstate the plan and queue guaranteed failures.
        val haveUrls = onDisk.mapNotNull { it.url }.toSet() + UnavailableChapters.urls()
        // Group the source's chapters by number so the report reads per chapter, not per URL.
        val byNumber = entry.knownChapters.groupBy { it.number }
        val chapters =
            byNumber.entries.sortedBy { it.key }.map { (number, versions) ->
                val missing = versions.filterNot { it.url in haveUrls }
                ChapterPlan(
                    number = number,
                    name = versions.firstOrNull()?.name.orEmpty(),
                    have = versions.filter { it.url in haveUrls }.map { it.scanlator ?: "?" },
                    missing = missing.map { it.scanlator ?: "?" },
                    missingUrls = missing.map { it.url },
                )
            }
        val missingCount = chapters.sumOf { it.missing.size }
        return SeriesPlan(
            sourceId = entry.sourceId,
            mangaUrl = entry.mangaUrl,
            title = entry.title,
            numbers = byNumber.size,
            versionsOnDisk = onDisk.size,
            versionsAtSource = entry.knownChapters.size,
            missing = missingCount,
            estBytes = averageChapterBytes(entry.title) * missingCount,
            // The per-chapter breakdown is only useful for a single series; skip it library-wide.
            chapters = if (detail) chapters.filter { it.missing.isNotEmpty() } else emptyList(),
        )
    }

    /**
     * Rough size of one chapter, from what's already on disk. Sampled rather than measured in full —
     * walking every page of a 70 GB library to produce an estimate would cost more than the estimate
     * is worth. ponytail: sample of 5; widen it if the numbers ever look silly.
     */
    private fun averageChapterBytes(title: String): Long =
        runCatching {
            val dir = AppConfig.downloadsDir.resolve(DownloadManager.sanitize(title))
            val sample =
                Files.list(dir).use { s ->
                    s.filter { Files.isDirectory(it) }.limit(5).toList()
                }
            if (sample.isEmpty()) return 0
            val total =
                sample.sumOf { d ->
                    Files.list(d).use { f -> f.mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum() }
                }
            total / sample.size
        }.getOrDefault(0L)

    /**
     * Queue everything the plan says is missing, for one series. Returns how many were queued.
     * Deliberately single-series: the whole-library version of this is a several-hundred-GB action and
     * should be a series at a time until you've watched it work.
     */
    fun start(title: String): Int {
        val entry = LibraryStore.list().firstOrNull { it.title == title } ?: error("'$title' is not in the library")
        val p = planFor(entry, detail = true)
        val chapters =
            p.chapters.flatMap { cp ->
                cp.missingUrls.map { url ->
                    DownloadQueue.Chapter(url, entry.knownChapters.first { it.url == url }.name)
                }
            }
        if (chapters.isEmpty()) {
            log.info("scan-versions: nothing missing for '{}'", title)
            return 0
        }
        log.info("scan-versions: queuing {} missing version(s) for '{}'", chapters.size, title)
        DownloadQueue.enqueue(entry.sourceId, entry.mangaUrl, entry.title, chapters)
        return chapters.size
    }
}
