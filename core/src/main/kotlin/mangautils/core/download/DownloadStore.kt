/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.download

import mangautils.core.config.AppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Read/manage what's actually on disk in the downloads folder, for the web "Download manager".
 * Downloads live at `<downloadsDir>/<title>/<chapter>/…` (folder of pages) or `<chapter>.cbz`.
 */
object DownloadStore {
    data class Series(val title: String, val chapters: Int, val incomplete: Int, val bytes: Long, val hasCover: Boolean)
    /** [complete] = the chapter finished writing (has ComicInfo.xml). Missing it ⇒ interrupted/partial. */
    data class Chapter(val name: String, val pages: Int, val bytes: Long, val cbz: Boolean, val complete: Boolean)

    private val root: Path get() = AppConfig.downloadsDir

    // Building the series list stats every page file on disk, so it's cached. Every change the app makes
    // (download finished, chapter deleted) calls invalidate() for instant correctness; the TTL is only a
    // safety net for files changed OUTSIDE the app (e.g. deleted in a file explorer).
    @Volatile private var seriesCache: List<Series>? = null
    @Volatile private var cachedAt = 0L
    private val cacheLock = Any()
    private const val CACHE_TTL_MS = 60_000L

    /** Drop the cached series list so the next [listSeries] re-scans disk. */
    fun invalidate() {
        seriesCache = null
    }

    /** Every downloaded series (a sub-folder of the downloads dir), title-sorted. Cached (see above). */
    fun listSeries(): List<Series> {
        val hit = seriesCache
        if (hit != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) return hit
        // Only one thread re-walks at a time; the rest wait and reuse its result (no thundering herd).
        return synchronized(cacheLock) {
            val again = seriesCache
            if (again != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
                again
            } else {
                computeSeries().also { seriesCache = it; cachedAt = System.currentTimeMillis() }
            }
        }
    }

    private fun computeSeries(): List<Series> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { st ->
            st.filter { it.isDirectory() }.map { dir ->
                val stats = chapterEntries(dir).map { statChapter(it) }
                val hasCover = runCatching { Files.list(dir).use { s -> s.anyMatch { it.name.startsWith("cover.") } } }.getOrDefault(false)
                Series(dir.name, stats.size, stats.count { !it.complete }, stats.sumOf { it.bytes }, hasCover)
            }.toList()
        }.sortedBy { it.title.lowercase() }
    }

    /** The downloaded chapters of one series (folder name = sanitized title). */
    fun listChapters(title: String): List<Chapter> {
        val dir = root.resolve(DownloadManager.sanitize(title))
        if (!dir.isDirectory()) return emptyList()
        return chapterEntries(dir).map { p ->
            val cbz = p.name.endsWith(".cbz")
            val s = statChapter(p)
            Chapter(
                name = if (cbz) p.name.removeSuffix(".cbz") else p.name,
                pages = s.pages,
                bytes = s.bytes,
                cbz = cbz,
                complete = s.complete,
            )
        }.sortedBy { it.name.lowercase() }
    }

    /** Delete one downloaded chapter (its folder or .cbz). Returns true if anything was removed. */
    fun deleteChapter(title: String, chapterName: String): Boolean {
        val base = root.resolve(DownloadManager.sanitize(title))
        val cbz = base.resolve(DownloadManager.sanitize(chapterName) + ".cbz")
        val folder = base.resolve(DownloadManager.sanitize(chapterName))
        var removed = false
        if (Files.exists(cbz)) removed = Files.deleteIfExists(cbz) || removed
        if (folder.isDirectory()) {
            runCatching { Files.walk(folder).use { st -> st.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
            removed = true
        }
        if (removed) invalidate()
        return removed
    }

    private fun chapterEntries(dir: Path): List<Path> =
        runCatching {
            Files.list(dir).use { st -> st.filter { it.isDirectory() || it.name.endsWith(".cbz") }.toList() }
        }.getOrDefault(emptyList())

    private data class ChapterStat(val pages: Int, val bytes: Long, val complete: Boolean)

    /**
     * Scan a chapter (page folder or .cbz) in ONE pass → page count, total bytes, and whether it
     * finished. A finished chapter has ComicInfo.xml (written last) and at least one page; missing
     * ComicInfo ⇒ the download was interrupted.
     */
    private fun statChapter(p: Path): ChapterStat = runCatching {
        if (p.name.endsWith(".cbz")) {
            var pages = 0
            var hasInfo = false
            java.util.zip.ZipFile(p.toFile()).use { z ->
                val entries = z.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    if (entry.name.endsWith("ComicInfo.xml")) hasInfo = true else pages++
                }
            }
            ChapterStat(pages, runCatching { Files.size(p) }.getOrDefault(0L), hasInfo && pages > 0)
        } else {
            var pages = 0
            var bytes = 0L
            var hasInfo = false
            Files.list(p).use { st ->
                st.forEach { child ->
                    if (Files.isDirectory(child)) return@forEach
                    bytes += runCatching { Files.size(child) }.getOrDefault(0L)
                    if (child.name == "ComicInfo.xml") hasInfo = true else pages++
                }
            }
            ChapterStat(pages, bytes, hasInfo && pages > 0)
        }
    }.getOrDefault(ChapterStat(0, 0, false))
}
