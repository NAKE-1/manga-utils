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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import mangautils.core.config.Settings
import mangautils.core.config.SettingsStore
import mangautils.core.extension.ExtensionInstaller
import mangautils.core.extension.InstalledStore
import mangautils.core.extension.RepoStore
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

    /** Which parts of a backup to include on export / restore on import. */
    data class Sections(
        val library: Boolean = true,
        val settings: Boolean = false,
        val repos: Boolean = false,
        val extensions: Boolean = false,
    ) {
        companion object {
            fun of(names: Set<String>) = Sections(
                library = "library" in names, settings = "settings" in names,
                repos = "repos" in names, extensions = "extensions" in names,
            )
        }
    }

    data class Result(
        val imported: Int,
        val skipped: Int,
        val total: Int,
        val settingsRestored: Boolean = false,
        val reposAdded: Int = 0,
        val extensionsInstalled: Int = 0,
        val extensionsFailed: Int = 0,
    )

    data class PreviewItem(val title: String, val source: Long, val chapters: Int, val read: Int, val inLibrary: Boolean)
    data class Preview(
        val total: Int,
        val manga: List<PreviewItem>,
        val hasSettings: Boolean = false,
        val repos: Int = 0,
        val extensions: Int = 0,
    )

    private val json = Json { ignoreUnknownKeys = true }

    @OptIn(ExperimentalSerializationApi::class)
    private fun decode(gzBytes: ByteArray): Backup {
        val raw = GZIPInputStream(gzBytes.inputStream()).use { it.readBytes() }
        return ProtoBuf.decodeFromByteArray<Backup>(raw)
    }

    /** Dry run: what a backup would import, without touching the library. */
    fun preview(gzBytes: ByteArray): Preview {
        val backup = decode(gzBytes)
        val favorites = backup.backupManga.filter { it.favorite }
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
            hasSettings = !backup.settingsJson.isNullOrBlank(),
            repos = backup.repoUrls.size,
            extensions = backup.extensionPkgs.size,
        )
    }

    /** Export the chosen [sections] as a gzipped protobuf. The library part is Mihon/Tachiyomi
     *  compatible; settings/repos/extensions live in manga-utils-native fields. */
    @OptIn(ExperimentalSerializationApi::class)
    fun export(sections: Sections = Sections()): ByteArray {
        val manga = if (!sections.library) emptyList() else
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
        val backup = Backup(
            backupManga = manga,
            // Export preferences, but blank the machine-specific bits so a backup restored on another
            // box (or in Docker) doesn't drag over this host's FlareSolverr URL or download path.
            settingsJson = if (sections.settings)
                json.encodeToString(SettingsStore.get().copy(flareSolverrUrl = "http://localhost:8191", downloadDir = null))
            else null,
            repoUrls = if (sections.repos) RepoStore.list() else emptyList(),
            extensionPkgs = if (sections.extensions) InstalledStore.list().map { it.pkg } else emptyList(),
        )
        val raw = ProtoBuf.encodeToByteArray(backup)
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
        // Restore any manga-utils-native sections present in the file.
        var settingsRestored = false
        if (!backup.settingsJson.isNullOrBlank()) {
            runCatching { SettingsStore.save(json.decodeFromString<Settings>(backup.settingsJson)) }
                .onSuccess { settingsRestored = true }
                .onFailure { log.warn("backup: failed to restore settings: {}", it.message) }
        }
        val reposAdded = backup.repoUrls.count { runCatching { RepoStore.add(it) }.getOrDefault(false) }

        var extInstalled = 0
        var extFailed = 0
        if (backup.extensionPkgs.isNotEmpty()) {
            val installer = ExtensionInstaller()
            val already = InstalledStore.list().map { it.pkg }.toSet()
            for (pkg in backup.extensionPkgs.filter { it !in already }) {
                runCatching { installer.install(pkg) }
                    .onSuccess { extInstalled++ }
                    .onFailure { log.warn("backup: failed to reinstall '{}': {}", pkg, it.message); extFailed++ }
            }
        }

        log.info(
            "backup import: {} manga imported, {} skipped; settings={}, repos+{}, extensions +{}/{}",
            imported, skipped, settingsRestored, reposAdded, extInstalled, extFailed,
        )
        return Result(imported, skipped, favorites.size, settingsRestored, reposAdded, extInstalled, extFailed)
    }

    // ---- Minimal protobuf schema (Mihon field numbers) --------------------------------------------

    @Serializable
    private data class Backup(
        @ProtoNumber(1) val backupManga: List<BackupManga> = emptyList(),
        // manga-utils-native sections (high field numbers → ignored by Mihon and by Mihon files here)
        @ProtoNumber(900) val settingsJson: String? = null,
        @ProtoNumber(901) val repoUrls: List<String> = emptyList(),
        @ProtoNumber(902) val extensionPkgs: List<String> = emptyList(),
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
