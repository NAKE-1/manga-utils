/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.download

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mangautils.core.config.AppConfig
import mangautils.core.convert.ImageFormat
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.atomic.AtomicInteger
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

    // Building the series list stats every page file on disk (heavy on a big library), so it's cached and
    // served STALE-WHILE-REVALIDATE: a request gets the last snapshot instantly while a background pass
    // recomputes when it's gone dirty (a download landed / a chapter was deleted) or past the TTL. Only a
    // cold start (no snapshot yet) blocks — the server warms it at boot. This is what stops a mass-download
    // (which marks the list dirty on every finished chapter) from forcing a ~20s full re-walk on every
    // /downloads request → phone/Tailscale timeouts that showed as "nothing downloaded".
    @Volatile private var seriesCache: List<Series>? = null
    @Volatile private var cachedAt = 0L
    @Volatile private var seriesDirty = false
    private val cacheLock = Any()
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val refreshExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "dl-series-refresh").apply { isDaemon = true }
    }
    private const val CACHE_TTL_MS = 60_000L

    /** Mark the cached series list stale — the next [listSeries] serves the old snapshot and refreshes in
     *  the background. (Marking, not dropping, so a burst of finished downloads can't force blocking walks.) */
    fun invalidate() {
        seriesDirty = true
    }

    /** Every downloaded series (a sub-folder of the downloads dir), title-sorted. Cached (see above). */
    fun listSeries(): List<Series> {
        val hit = seriesCache
        if (hit != null) {
            if (seriesDirty || System.currentTimeMillis() - cachedAt >= CACHE_TTL_MS) refreshSeriesAsync()
            return hit // instant — never block a request on the full walk once we have any snapshot
        }
        // Cold: nothing cached yet (first call / boot warmer) — compute once, guarded so only one thread walks.
        return synchronized(cacheLock) {
            seriesCache ?: computeSeries().also { seriesCache = it; cachedAt = System.currentTimeMillis(); seriesDirty = false }
        }
    }

    /** Single-flight background recompute that swaps in a fresh snapshot without blocking callers. */
    private fun refreshSeriesAsync() {
        if (!refreshing.compareAndSet(false, true)) return
        refreshExec.submit {
            seriesDirty = false // clear first — a change during the walk re-marks it → another refresh follows
            try { computeSeries().also { seriesCache = it; cachedAt = System.currentTimeMillis() } }
            catch (_: Throwable) { /* keep serving the previous snapshot */ }
            finally { refreshing.set(false) }
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
    fun scanCorrupt(onProgress: ((Int, Int) -> Unit)? = null): CorruptReport {
        if (!Files.isDirectory(root)) return CorruptReport(emptyList(), 0, 0)
        val dirs = Files.list(root).use { st -> st.filter { it.isDirectory() }.toList() }
        val totalDirs = dirs.size
        val done = AtomicInteger(0)
        // Parallel across series (common ForkJoinPool) — on a large library the per-file IO dominates.
        val series = dirs.parallelStream().map { dir ->
            val chs = chapterEntries(dir).mapNotNull { p ->
                val (bad, total) = badPagesIn(p)
                if (bad > 0) CorruptChapter(if (p.name.endsWith(".cbz")) p.name.removeSuffix(".cbz") else p.name, bad, total) else null
            }
            CorruptSeries(dir.name, chs).also { onProgress?.invoke(done.incrementAndGet(), totalDirs) }
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

    // ---- Integrity manifest: prove the downloads survived a disk move / copy intact ----------------
    // Generate a fingerprint on the OLD box, then verify against it on the NEW box. FAST (default) reads
    // only file names + sizes (minutes); DEEP also SHA-256s every file's content (hours, catches bit-rot).
    // Per-CHAPTER granularity keeps the manifest small (~thousands of rows) yet pinpoints the exact chapter
    // that changed. Digests are OS-independent: names+sizes only (no mtime), JVM string sort, junk excluded.

    @Serializable data class ChapterFp(val name: String, val files: Int, val bytes: Long, val digest: String)
    @Serializable data class SeriesFp(val series: String, val files: Int, val bytes: Long, val digest: String, val chapters: List<ChapterFp>)
    @Serializable data class DownloadsManifest(val version: Int, val deep: Boolean, val generatedAt: Long, val totalFiles: Int, val totalBytes: Long, val series: List<SeriesFp>)

    @Serializable data class VerifyChanged(val series: String, val savedFiles: Int, val curFiles: Int, val savedBytes: Long, val curBytes: Long, val chapters: List<String>)
    @Serializable data class VerifyReport(val ok: Boolean, val deep: Boolean, val seriesTotal: Int, val seriesMatched: Int, val missing: List<String>, val extra: List<String>, val changed: List<VerifyChanged>)

    private val manifestJson = Json { ignoreUnknownKeys = true }
    private val manifestFile: Path get() = AppConfig.dataDir.resolve("downloads-manifest.json")

    /** Walk the library and fingerprint every series/chapter. [onProgress] fires per series (done,total). */
    fun buildManifest(deep: Boolean, onProgress: ((Int, Int) -> Unit)? = null): DownloadsManifest {
        if (!Files.isDirectory(root)) return DownloadsManifest(1, deep, System.currentTimeMillis(), 0, 0, emptyList())
        val dirs = Files.list(root).use { st -> st.filter { it.isDirectory() }.toList() }
        val total = dirs.size
        val done = AtomicInteger(0)
        val series = dirs.parallelStream().map { dir ->
            fingerprintSeries(dir, deep).also { onProgress?.invoke(done.incrementAndGet(), total) }
        }.collect(java.util.stream.Collectors.toList()).sortedBy { it.series }
        return DownloadsManifest(1, deep, System.currentTimeMillis(), series.sumOf { it.files }, series.sumOf { it.bytes }, series)
    }

    /** Generate + persist a manifest to the data dir (rides the config backup to the new box). */
    fun generateManifest(deep: Boolean, onProgress: ((Int, Int) -> Unit)? = null): DownloadsManifest {
        val m = buildManifest(deep, onProgress)
        runCatching { Files.writeString(manifestFile, manifestJson.encodeToString(DownloadsManifest.serializer(), m)) }
        return m
    }

    /** The saved manifest (from a prior generate), or null if none exists / it won't parse. */
    fun savedManifest(): DownloadsManifest? = runCatching {
        if (!Files.exists(manifestFile)) return null
        manifestJson.decodeFromString(DownloadsManifest.serializer(), Files.readString(manifestFile))
    }.getOrNull()

    /** Re-fingerprint at the saved manifest's level and diff against it — the post-move integrity check. */
    fun verifyManifest(onProgress: ((Int, Int) -> Unit)? = null): VerifyReport {
        val saved = savedManifest() ?: return VerifyReport(false, false, 0, 0, emptyList(), emptyList(), emptyList())
        val current = buildManifest(saved.deep, onProgress)
        val savedMap = saved.series.associateBy { it.series }
        val curMap = current.series.associateBy { it.series }
        val missing = savedMap.keys.filter { it !in curMap }.sorted()
        val extra = curMap.keys.filter { it !in savedMap }.sorted()
        val changed = savedMap.values.mapNotNull { s ->
            val c = curMap[s.series] ?: return@mapNotNull null
            if (s.digest == c.digest) return@mapNotNull null
            val sc = s.chapters.associateBy { it.name }; val cc = c.chapters.associateBy { it.name }
            val chDiffs = (sc.keys + cc.keys).filter { sc[it]?.digest != cc[it]?.digest }.sorted()
            VerifyChanged(s.series, s.files, c.files, s.bytes, c.bytes, chDiffs.take(50))
        }.sortedBy { it.series }
        val matched = savedMap.count { (n, s) -> curMap[n]?.digest == s.digest }
        return VerifyReport(missing.isEmpty() && extra.isEmpty() && changed.isEmpty(), saved.deep, saved.series.size, matched, missing, extra, changed)
    }

    private fun fingerprintSeries(dir: Path, deep: Boolean): SeriesFp {
        val chapters = chapterEntries(dir).map { fingerprintChapter(it, deep) }.sortedBy { it.name }
        val md = MessageDigest.getInstance("SHA-256")
        for (c in chapters) { md.update(c.name.toByteArray()); md.update(0); md.update(c.digest.toByteArray()); md.update('\n'.code.toByte()) }
        return SeriesFp(dir.name, chapters.sumOf { it.files }, chapters.sumOf { it.bytes }, hex(md.digest()), chapters)
    }

    private fun fingerprintChapter(p: Path, deep: Boolean): ChapterFp {
        val name = if (p.name.endsWith(".cbz")) p.name.removeSuffix(".cbz") else p.name
        // (fileName, size, contentHash?) for every real file in the chapter (the .cbz itself, or its pages).
        val entries = ArrayList<Triple<String, Long, String?>>()
        if (p.name.endsWith(".cbz")) {
            entries.add(Triple(p.name, runCatching { Files.size(p) }.getOrDefault(0L), if (deep) sha256File(p) else null))
        } else runCatching {
            Files.list(p).use { st -> st.forEach { child ->
                if (Files.isDirectory(child) || isJunk(child.name)) return@forEach
                entries.add(Triple(child.name, runCatching { Files.size(child) }.getOrDefault(0L), if (deep) sha256File(child) else null))
            } }
        }
        entries.sortBy { it.first }
        val md = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        for ((rel, size, ch) in entries) {
            md.update(rel.toByteArray()); md.update(0); md.update(size.toString().toByteArray())
            if (ch != null) { md.update(0); md.update(ch.toByteArray()) }
            md.update('\n'.code.toByte()); bytes += size
        }
        return ChapterFp(name, entries.size, bytes, hex(md.digest()))
    }

    /** OS/Explorer junk that appears on one box but not the other — excluded so it can't cause false diffs. */
    private fun isJunk(name: String): Boolean {
        val n = name.lowercase()
        return n == "thumbs.db" || n == "desktop.ini" || n == ".ds_store" || name.contains(':') // ':' = NTFS ADS
    }

    private fun sha256File(p: Path): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(p).use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) { val r = ins.read(buf); if (r < 0) break; md.update(buf, 0, r) }
        }
        hex(md.digest())
    }.getOrDefault("")

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
