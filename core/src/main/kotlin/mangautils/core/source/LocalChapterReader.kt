/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.source

import mangautils.core.config.AppConfig
import mangautils.core.download.ChapterIdentity
import mangautils.core.download.DownloadManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** A downloaded chapter on disk — either a folder of images or a CBZ archive. */
sealed interface LocalChapter {
    val count: Int

    fun bytes(index: Int): ByteArray?

    class Folder(private val files: List<Path>) : LocalChapter {
        override val count = files.size

        override fun bytes(index: Int): ByteArray? =
            files.getOrNull(index)?.let { runCatching { Files.readAllBytes(it) }.getOrNull() }
    }

    class Archive(private val cbz: Path, private val entries: List<String>) : LocalChapter {
        override val count = entries.size

        override fun bytes(index: Int): ByteArray? {
            val name = entries.getOrNull(index) ?: return null
            return runCatching {
                ZipFile(cbz.toFile()).use { zip -> zip.getEntry(name)?.let { zip.getInputStream(it).use { s -> s.readBytes() } } }
            }.getOrNull()
        }
    }
}

/**
 * Resolves a chapter's local download (mirrors Suwayomi's ChapterDownloadHelper.provider): prefers a
 * CBZ, falls back to a folder of images, else null. Entries are sorted by name (page order).
 */
object LocalChapterReader {
    private val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "jxl", "bmp")

    private fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in imageExts

    fun localChapter(title: String, chapterName: String, chapterUrl: String? = null): LocalChapter? {
        val base = AppConfig.downloadsDir.resolve(DownloadManager.sanitize(title))
        // Per-scanlator storage means the folder is "Chapter 89 [Gamma 2]", not "Chapter 89", so resolving
        // by name alone misses the download entirely and we silently refetch from the source. The chapter
        // URL is the actual identity (ComicInfo `Web`), and it also picks the RIGHT scanlation when we hold
        // several of the same number - a name match could hand back a different group's pages.
        if (chapterUrl != null) {
            val folderName = ChapterIdentity.versionsOf(title).firstOrNull { it.url == chapterUrl && it.complete }?.folder
            if (folderName != null) {
                folderPages(base.resolve(folderName))?.let { return it }
                archivePages(base.resolve(folderName + ".cbz"))?.let { return it }
            }
        }
        archivePages(base.resolve(DownloadManager.sanitize(chapterName) + ".cbz"))?.let { return it }
        return folderPages(base.resolve(DownloadManager.sanitize(chapterName)))
    }

    private fun archivePages(cbz: java.nio.file.Path): LocalChapter? {
        if (!Files.exists(cbz)) return null
        val entries = runCatching {
            ZipFile(cbz.toFile()).use { z -> z.entries().toList().map { it.name }.filter { isImage(it) }.sorted() }
        }.getOrDefault(emptyList())
        return if (entries.isEmpty()) null else LocalChapter.Archive(cbz, entries)
    }

    private fun folderPages(folder: java.nio.file.Path): LocalChapter? {
        if (!Files.isDirectory(folder)) return null
        val files = runCatching {
            Files.list(folder).use { s -> s.filter { isImage(it.fileName.toString()) }.toList() }.sortedBy { it.fileName.toString() }
        }.getOrDefault(emptyList())
        return if (files.isEmpty()) null else LocalChapter.Folder(files)
    }
}
