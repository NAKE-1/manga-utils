/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.backup

import eu.kanade.tachiyomi.source.model.SChapterImpl
import eu.kanade.tachiyomi.source.model.SMangaImpl
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import mangautils.core.library.BookmarkStore
import mangautils.core.library.LibraryService
import mangautils.core.library.LibraryStore
import mangautils.core.library.ReadStore
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Imports a Mihon / Tachiyomi / Suwayomi backup (.tachibk / .proto.gz — a gzipped protobuf). Only the
 * fields we use are declared; kotlinx-protobuf skips the rest (tracking, history, categories, …), so
 * this stays compatible with the full schema. Field numbers match Mihon's BackupManga/BackupChapter.
 */
object BackupImport {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Result(val imported: Int, val skipped: Int, val total: Int)
    data class PreviewItem(val title: String, val source: Long, val chapters: Int, val read: Int, val inLibrary: Boolean)
    data class Preview(val total: Int, val manga: List<PreviewItem>)

    @OptIn(ExperimentalSerializationApi::class)
    private fun decode(gzBytes: ByteArray): Backup {
        val raw = GZIPInputStream(gzBytes.inputStream()).use { it.readBytes() }
        return ProtoBuf.decodeFromByteArray<Backup>(raw)
    }

    /** Dry run: what a backup would import, without touching the library. */
    fun preview(gzBytes: ByteArray): Preview {
        val favorites = decode(gzBytes).backupManga.filter { it.favorite }
        return Preview(
            favorites.size,
            favorites.map {
                PreviewItem(
                    title = it.title.ifBlank { it.url },
                    source = it.source,
                    chapters = it.chapters.size,
                    read = it.chapters.count { c -> c.read },
                    inLibrary = LibraryStore.find(it.source, it.url) != null,
                )
            },
        )
    }

    /** Export the current library as a Mihon/Tachiyomi-compatible backup (gzipped protobuf). */
    @OptIn(ExperimentalSerializationApi::class)
    fun export(): ByteArray {
        val manga =
            LibraryStore.list().map { e ->
                val read = ReadStore.readUrls(e.sourceId, e.mangaUrl)
                val marks = BookmarkStore.bookmarks(e.sourceId, e.mangaUrl)
                BackupManga(
                    source = e.sourceId,
                    url = e.mangaUrl,
                    title = e.title,
                    author = e.author,
                    description = e.description,
                    genre = e.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                    status = e.status,
                    thumbnailUrl = e.thumbnailUrl,
                    chapters = e.knownChapters.map { c ->
                        BackupChapter(c.url, c.name, c.scanlator, c.url in read, c.url in marks, c.dateUpload, c.number)
                    },
                    favorite = true,
                )
            }
        val raw = ProtoBuf.encodeToByteArray(Backup(manga))
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun import(gzBytes: ByteArray): Result {
        val backup = decode(gzBytes)
        val favorites = backup.backupManga.filter { it.favorite }
        var imported = 0
        var skipped = 0
        for (m in favorites) {
            runCatching {
                val title = m.title.ifBlank { m.url }
                val sManga =
                    SMangaImpl().apply {
                        url = m.url
                        this.title = title
                        artist = m.artist
                        author = m.author
                        description = m.description
                        genre = m.genre.joinToString(", ").ifBlank { null }
                        status = m.status
                        thumbnail_url = m.thumbnailUrl
                    }
                val chapters =
                    m.chapters.map { c ->
                        SChapterImpl().apply {
                            url = c.url
                            name = c.name
                            scanlator = c.scanlator
                            date_upload = c.dateUpload
                            chapter_number = c.chapterNumber
                        }
                    }
                LibraryService.addKnown(m.source, m.url, title, sManga, chapters)
                ReadStore.markRead(m.source, m.url, m.chapters.filter { it.read }.map { it.url })
                m.chapters.filter { it.bookmark }.forEach { BookmarkStore.setBookmarked(m.source, m.url, it.url, true) }
                imported++
            }.onFailure {
                log.warn("backup: failed to import '{}': {}", m.title, it.message)
                skipped++
            }
        }
        log.info("backup import: {} imported, {} skipped of {} favorites", imported, skipped, favorites.size)
        return Result(imported, skipped, favorites.size)
    }

    // ---- Minimal protobuf schema (Mihon field numbers) --------------------------------------------

    @Serializable
    private data class Backup(
        @ProtoNumber(1) val backupManga: List<BackupManga> = emptyList(),
    )

    @Serializable
    private data class BackupManga(
        @ProtoNumber(1) val source: Long = 0,
        @ProtoNumber(2) val url: String = "",
        @ProtoNumber(3) val title: String = "",
        @ProtoNumber(4) val artist: String? = null,
        @ProtoNumber(5) val author: String? = null,
        @ProtoNumber(6) val description: String? = null,
        @ProtoNumber(7) val genre: List<String> = emptyList(),
        @ProtoNumber(8) val status: Int = 0,
        @ProtoNumber(9) val thumbnailUrl: String? = null,
        @ProtoNumber(16) val chapters: List<BackupChapter> = emptyList(),
        @ProtoNumber(100) val favorite: Boolean = true,
    )

    @Serializable
    private data class BackupChapter(
        @ProtoNumber(1) val url: String = "",
        @ProtoNumber(2) val name: String = "",
        @ProtoNumber(3) val scanlator: String? = null,
        @ProtoNumber(4) val read: Boolean = false,
        @ProtoNumber(5) val bookmark: Boolean = false,
        @ProtoNumber(8) val dateUpload: Long = 0,
        @ProtoNumber(9) val chapterNumber: Float = 0f,
    )
}
