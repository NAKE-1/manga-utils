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

    /** Every downloaded series (a sub-folder of the downloads dir), newest-modified first. */
    fun listSeries(): List<Series> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { st ->
            st.filter { it.isDirectory() }.map { dir ->
                val chs = chapterEntries(dir)
                val hasCover = runCatching { Files.list(dir).use { s -> s.anyMatch { it.name.startsWith("cover.") } } }.getOrDefault(false)
                val incomplete = chs.count { !hasComicInfo(it, it.name.endsWith(".cbz")) }
                Series(dir.name, chs.size, incomplete, chs.sumOf { sizeOf(it) }, hasCover)
            }.toList()
        }.sortedBy { it.title.lowercase() }
    }

    /** The downloaded chapters of one series (folder name = sanitized title). */
    fun listChapters(title: String): List<Chapter> {
        val dir = root.resolve(DownloadManager.sanitize(title))
        if (!dir.isDirectory()) return emptyList()
        return chapterEntries(dir).map { p ->
            val cbz = p.name.endsWith(".cbz")
            Chapter(
                name = if (cbz) p.name.removeSuffix(".cbz") else p.name,
                pages = pageCount(p, cbz),
                bytes = sizeOf(p),
                cbz = cbz,
                complete = hasComicInfo(p, cbz),
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
        return removed
    }

    private fun chapterEntries(dir: Path): List<Path> =
        runCatching {
            Files.list(dir).use { st -> st.filter { it.isDirectory() || it.name.endsWith(".cbz") }.toList() }
        }.getOrDefault(emptyList())

    /** A finished chapter has ComicInfo.xml (written last). Absent ⇒ the download was interrupted. */
    private fun hasComicInfo(p: Path, cbz: Boolean): Boolean = runCatching {
        if (cbz) {
            java.util.zip.ZipFile(p.toFile()).use { z -> z.getEntry("ComicInfo.xml") != null } && pageCount(p, true) > 0
        } else {
            Files.exists(p.resolve("ComicInfo.xml")) && pageCount(p, false) > 0
        }
    }.getOrDefault(false)

    private fun pageCount(p: Path, cbz: Boolean): Int = runCatching {
        if (cbz) {
            java.util.zip.ZipFile(p.toFile()).use { z -> z.entries().asSequence().count { !it.isDirectory && !it.name.endsWith("ComicInfo.xml") } }
        } else {
            Files.list(p).use { st -> st.filter { !it.isDirectory() && it.name != "ComicInfo.xml" }.count().toInt() }
        }
    }.getOrDefault(0)

    private fun sizeOf(p: Path): Long = runCatching {
        if (p.isDirectory()) Files.walk(p).use { st -> st.filter { !it.isDirectory() }.mapToLong { Files.size(it) }.sum() }
        else Files.size(p)
    }.getOrDefault(0)
}
