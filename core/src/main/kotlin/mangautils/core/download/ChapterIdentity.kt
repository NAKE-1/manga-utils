/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.download

import mangautils.core.config.AppConfig
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Answers "which upload is in this folder?" by reading each downloaded chapter's ComicInfo.xml.
 *
 * Once folder names carry a sanitized, length-capped scanlator they can no longer be trusted as
 * identity — two groups can sanitize to the same string, long names get trimmed, and folders you rename
 * by hand would break everything. So identity is read from inside the folder instead: `Web` (the chapter
 * URL, unique per upload) and `Translator` (the scanlator, written since P1).
 *
 * Folders downloaded before P1 have no `Translator`. They still have `Web`, so the caller can map the URL
 * back to a scanlator via the source's chapter list — which is what makes "never rename existing folders"
 * workable.
 */
object ChapterIdentity {
    private val log = LoggerFactory.getLogger(javaClass)

    /** One downloaded chapter folder (or .cbz) and what it says about itself. */
    data class Version(
        /** Folder or file name on disk — display only, never used as identity. */
        val folder: String,
        /** Source chapter URL from ComicInfo `Web`. Null for very old or hand-made folders. */
        val url: String?,
        /** Scanlation group from ComicInfo `Translator`. Null for anything downloaded before P1. */
        val scanlator: String?,
        val number: Float?,
        val title: String?,
        val pageCount: Int,
        /** False when the folder holds no images — an interrupted download. */
        val complete: Boolean,
    ) {
        /** True when we know nothing about which upload this is. */
        val unidentified: Boolean get() = url == null && scanlator == null
    }

    // A manga's folder listing rarely changes, and the detail page asks per chapter row. Cache on the
    // directory's mtime so an external change (a manual delete, a restore) still invalidates.
    // ponytail: in-memory only, rebuilt on restart. A persisted index is the upgrade if this measures slow.
    private val cache = ConcurrentHashMap<String, Pair<Long, List<Version>>>()

    /** Every downloaded version of every chapter for [title], read from disk. */
    fun versionsOf(title: String): List<Version> {
        val dir = AppConfig.downloadsDir.resolve(DownloadManager.sanitize(title))
        if (!dir.isDirectory()) return emptyList()
        val stamp = runCatching { Files.getLastModifiedTime(dir).toMillis() }.getOrDefault(0L)
        cache[dir.toString()]?.let { (cachedStamp, cached) -> if (cachedStamp == stamp) return cached }
        val scanned = scan(dir)
        cache[dir.toString()] = stamp to scanned
        return scanned
    }

    /** Is this exact upload on disk? */
    fun hasVersion(
        title: String,
        chapterUrl: String,
    ): Boolean = versionsOf(title).any { it.complete && it.url == chapterUrl }

    /** Do we have any version of this chapter number? */
    fun hasAnyVersion(
        title: String,
        number: Float,
    ): Boolean = versionsOf(title).any { it.complete && it.number == number }

    /** Chapter URLs we already hold, for computing what's missing. */
    fun downloadedUrls(title: String): Set<String> =
        versionsOf(title).filter { it.complete }.mapNotNull { it.url }.toSet()

    /**
     * Chapter numbers we hold at least one version of.
     *
     * URL matching alone is too strict: a source that re-uploads a chapter gives it a new URL, so the
     * folder we already have stops matching and the chapter reads as missing — and gets fetched again.
     * Falling back to the number keeps "I have a copy of this chapter" true across a re-upload.
     */
    fun downloadedNumbers(title: String): Set<Float> =
        versionsOf(title).filter { it.complete }.mapNotNull { it.number }.toSet()

    /**
     * Identify a single chapter folder or .cbz, without scanning the series. Used on the write path,
     * where scanning every sibling for each chapter would be quadratic.
     */
    fun identify(path: Path): Version? = runCatching { readOne(path) }.getOrNull()

    /** Drop cached listings — call after a download or delete changes a series. */
    fun invalidate(title: String? = null) {
        if (title == null) {
            cache.clear()
        } else {
            cache.remove(AppConfig.downloadsDir.resolve(DownloadManager.sanitize(title)).toString())
        }
    }

    private fun scan(dir: Path): List<Version> =
        runCatching {
            Files.list(dir).use { stream ->
                stream
                    .filter { it.isDirectory() || it.toString().endsWith(".cbz") }
                    .map(::readOne)
                    .toList()
            }
        }.getOrElse {
            log.warn("Could not scan {}: {}", dir, it.message)
            emptyList()
        }

    private fun readOne(entry: Path): Version {
        val isCbz = !entry.isDirectory()
        val xml = runCatching { if (isCbz) xmlFromCbz(entry) else xmlFromFolder(entry) }.getOrNull()
        val pages =
            runCatching {
                if (isCbz) {
                    ZipFile(entry.toFile()).use { z -> z.entries().asSequence().count { !it.isDirectory && !it.name.endsWith(".xml") } }
                } else {
                    Files.list(entry).use { s -> s.filter { !it.name.endsWith(".xml") }.count().toInt() }
                }
            }.getOrDefault(0)
        return Version(
            folder = entry.name,
            url = xml?.let { tag(it, "Web") },
            scanlator = xml?.let { tag(it, "Translator") },
            number = xml?.let { tag(it, "Number") }?.toFloatOrNull(),
            title = xml?.let { tag(it, "Title") },
            pageCount = xml?.let { tag(it, "PageCount") }?.toIntOrNull() ?: pages,
            complete = pages > 0,
        )
    }

    private fun xmlFromFolder(dir: Path): String? =
        dir.resolve("ComicInfo.xml").takeIf { Files.exists(it) }?.let { Files.readString(it) }

    private fun xmlFromCbz(file: Path): String? =
        ZipFile(file.toFile()).use { zip ->
            zip.getEntry("ComicInfo.xml")?.let { zip.getInputStream(it).use { s -> s.readBytes().decodeToString() } }
        }

    /** Pull one tag's text. The file is ours and shallow, so a full XML parse buys nothing here. */
    private fun tag(
        xml: String,
        name: String,
    ): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)
            ?.groupValues
            ?.get(1)
            ?.replace("&lt;", "<")
            ?.replace("&gt;", ">")
            ?.replace("&quot;", "\"")
            ?.replace("&amp;", "&")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
}
