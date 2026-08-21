/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.download

import mangautils.core.config.AppConfig
import mangautils.core.convert.ImageFormat
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

    // ---- Content scan: catch finished-but-corrupt chapters (junk saved as a page image) ----
    data class CorruptChapter(val name: String, val badPages: Int, val pages: Int)
    data class CorruptSeries(val title: String, val chapters: List<CorruptChapter>)
    data class CorruptReport(val series: List<CorruptSeries>, val totalChapters: Int, val totalBadPages: Int)

    /**
     * Validate page CONTENT across the whole library: a page is bad if its bytes aren't a real image
     * (e.g. a Cloudflare "you have been blocked" HTML page that got saved as p.jpg). The [complete]
     * flag can't see this — those chapters finished writing and have a ComicInfo.xml. Uses the exact
     * [ImageFormat.looksLikeImage] rule the downloader accepts by, so a flagged page is one the
     * downloader would now reject. Heavy: reads the head of every page file.
     * ponytail: full walk each call; add a persisted per-chapter marker if it drags on huge libraries.
     */
    fun scanCorrupt(): CorruptReport {
        if (!Files.isDirectory(root)) return CorruptReport(emptyList(), 0, 0)
        val dirs = Files.list(root).use { st -> st.filter { it.isDirectory() }.toList() }
        // Parallel across series (common ForkJoinPool) — on a large library the per-file IO dominates.
        val series = dirs.parallelStream().map { dir ->
            val chs = chapterEntries(dir).mapNotNull { p ->
                val (bad, total) = badPagesIn(p)
                if (bad > 0) CorruptChapter(if (p.name.endsWith(".cbz")) p.name.removeSuffix(".cbz") else p.name, bad, total) else null
            }
            CorruptSeries(dir.name, chs)
        }.filter { it.chapters.isNotEmpty() }.collect(java.util.stream.Collectors.toList()).sortedBy { it.title.lowercase() }
        return CorruptReport(series, series.sumOf { it.chapters.size }, series.sumOf { s -> s.chapters.sumOf { it.badPages } })
    }

    /** Sanitized chapter (folder) names of one series that hold ≥1 non-image page — for targeted repair. */
    fun corruptChapterNames(title: String): Set<String> {
        val dir = root.resolve(DownloadManager.sanitize(title))
        if (!dir.isDirectory()) return emptySet()
        return chapterEntries(dir).mapNotNull { p ->
            val n = if (p.name.endsWith(".cbz")) p.name.removeSuffix(".cbz") else p.name
            n.takeIf { badPagesIn(p).first > 0 }
        }.toSet()
    }

    /**
     * (badPages, totalPages) for one chapter — a page is bad when its head isn't a valid image.
     *
     * Only files under [SCAN_MAX_BYTES] are opened: an error body saved as a page (Cloudflare block
     * page, JSON error) is always small, while a real manga page is large, so anything above the
     * threshold is assumed good without a read. This is what keeps the scan viable on a huge library.
     * ponytail: a non-image body larger than the threshold would slip through — hasn't happened; error
     * responses are always small.
     */
    private fun badPagesIn(p: Path): Pair<Int, Int> = runCatching {
        var bad = 0
        var total = 0
        if (p.name.endsWith(".cbz")) {
            java.util.zip.ZipFile(p.toFile()).use { z ->
                val es = z.entries()
                while (es.hasMoreElements()) {
                    val e = es.nextElement()
                    if (e.isDirectory || e.name.endsWith("ComicInfo.xml")) continue
                    total++
                    if (e.size in 0 until SCAN_MAX_BYTES && !ImageFormat.looksLikeImage(z.getInputStream(e).use { readHead(it) })) bad++
                }
            }
        } else {
            Files.list(p).use { st ->
                st.forEach { child ->
                    if (Files.isDirectory(child) || child.name == "ComicInfo.xml" || child.name.startsWith("cover.")) return@forEach
                    total++
                    if (Files.size(child) < SCAN_MAX_BYTES && !ImageFormat.looksLikeImage(Files.newInputStream(child).use { readHead(it) })) bad++
                }
            }
        }
        bad to total
    }.getOrDefault(0 to 0)

    /** Above this, a page is assumed a real image (error pages are small) — see [badPagesIn]. */
    private const val SCAN_MAX_BYTES = 32L * 1024

    /** First up-to-[HEAD_BYTES] bytes — enough for a magic-byte check without reading whole images. */
    private fun readHead(input: java.io.InputStream): ByteArray {
        val buf = ByteArray(HEAD_BYTES)
        var n = 0
        while (n < HEAD_BYTES) {
            val r = input.read(buf, n, HEAD_BYTES - n)
            if (r < 0) break
            n += r
        }
        return if (n == HEAD_BYTES) buf else buf.copyOf(n)
    }

    private const val HEAD_BYTES = 2048

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
