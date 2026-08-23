/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mangautils.core.config.AppConfig
import mangautils.core.download.SeriesMeta
import mangautils.core.library.HistoryStore
import mangautils.core.library.LibraryStore
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * One-shot backfill of `.series.json` for download folders that don't have one yet, using local data
 * only (library → download queue → history). SAFE BY CONSTRUCTION: it only ever writes the sidecar, and
 * only into a folder that lacks one — never overwrites, renames, deletes, or touches chapter files. A
 * folder it can't confidently resolve is left exactly as it was. Supports a dry run that writes nothing.
 */
object SeriesBackfill {
    private val log = LoggerFactory.getLogger(javaClass)

    @Serializable
    data class Result(
        val total: Int,
        val alreadyHad: Int,
        val written: Int,
        val unresolved: List<String>,
        val dryRun: Boolean,
    )

    // Minimal view of downloadqueue.json (it carries source name + key per task).
    @Serializable private data class QTask(val sourceId: Long = 0, val mangaUrl: String = "", val title: String = "", val source: String = "")
    @Serializable private data class QFile(val tasks: List<QTask> = emptyList())
    private val qjson = Json { ignoreUnknownKeys = true }

    private fun sani(s: String) = mangautils.core.download.DownloadManager.sanitize(s)

    fun run(dryRun: Boolean): Result {
        val root = AppConfig.downloadsDir
        if (!root.isDirectory()) return Result(0, 0, 0, emptyList(), dryRun)

        // Resolvers keyed by sanitized title. singleOrNull → an ambiguous title (two sources / urls) is
        // left unresolved rather than guessed, so we never write a wrong identity.
        val libByTitle = LibraryStore.list().groupBy { sani(it.title) }
        val queueByTitle = readQueue().groupBy { sani(it.title) }
        val histByTitle = HistoryStore.list().groupBy { sani(it.mangaTitle) }

        var total = 0; var alreadyHad = 0; var written = 0
        val unresolved = mutableListOf<String>()

        Files.list(root).use { stream ->
            stream.filter { it.isDirectory() }.forEach { dir ->
                total++
                if (dir.resolve(SeriesMeta.FILE).exists()) { alreadyHad++; return@forEach }
                val meta = resolve(dir.name, libByTitle, queueByTitle, histByTitle)
                if (meta == null) { unresolved += dir.name; return@forEach }
                if (dryRun) { written++; return@forEach }
                runCatching { SeriesMeta.write(dir, meta) }
                    .onSuccess { written++ }
                    .onFailure { log.debug("backfill: couldn't write for {}: {}", dir.name, it.message); unresolved += dir.name }
            }
        }
        log.info("series backfill{}: {} folders, {} already had, {} {}, {} unresolved",
            if (dryRun) " (dry run)" else "", total, alreadyHad, written, if (dryRun) "would write" else "written", unresolved.size)
        return Result(total, alreadyHad, written, unresolved, dryRun)
    }

    private fun resolve(
        folder: String,
        lib: Map<String, List<mangautils.core.library.LibraryEntry>>,
        queue: Map<String, List<QTask>>,
        hist: Map<String, List<mangautils.core.library.HistoryEntry>>,
    ): SeriesMeta? {
        val now = System.currentTimeMillis()
        // 1) Library entry (richest — full metadata for a future rebuild).
        lib[folder]?.distinctBy { it.sourceId to it.mangaUrl }?.singleOrNull()?.let { e ->
            return SeriesMeta(
                sourceId = e.sourceId, sourceName = DownloadQueue.sourceName(e.sourceId), mangaUrl = e.mangaUrl,
                title = e.title, author = e.author, status = e.status, genre = e.genre, thumbnailUrl = e.thumbnailUrl, savedAt = now,
            )
        }
        // 2) Download-queue task (carries the source name directly).
        queue[folder]?.distinctBy { it.sourceId to it.mangaUrl }?.singleOrNull()?.let { t ->
            return SeriesMeta(sourceId = t.sourceId, sourceName = t.source, mangaUrl = t.mangaUrl, title = t.title.ifBlank { folder }, savedAt = now)
        }
        // 3) History (last resort — has the key + title + thumbnail).
        hist[folder]?.distinctBy { it.sourceId to it.mangaUrl }?.singleOrNull()?.let { h ->
            return SeriesMeta(
                sourceId = h.sourceId, sourceName = DownloadQueue.sourceName(h.sourceId), mangaUrl = h.mangaUrl,
                title = h.mangaTitle.ifBlank { folder }, thumbnailUrl = h.thumbnailUrl, savedAt = now,
            )
        }
        return null
    }

    private fun readQueue(): List<QTask> {
        val f = AppConfig.dataDir.resolve("downloadqueue.json")
        if (!f.exists()) return emptyList()
        return runCatching { qjson.decodeFromString<QFile>(f.readText()).tasks }.getOrDefault(emptyList())
    }
}
