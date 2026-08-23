/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.library

import kotlinx.serialization.json.Json
import mangautils.core.config.AppConfig
import mangautils.core.util.SafeFile
import org.slf4j.LoggerFactory

/** Flat-file persistence of the followed-series library (`data/library.json`). */
object LibraryStore {
    private val log = LoggerFactory.getLogger(LibraryStore::class.java)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file get() = AppConfig.dataDir.resolve("library.json")

    @Synchronized
    fun list(): List<LibraryEntry> =
        SafeFile.read(file) { runCatching { json.decodeFromString<List<LibraryEntry>>(it) }.getOrNull() } ?: emptyList()

    fun find(
        sourceId: Long,
        mangaUrl: String,
    ): LibraryEntry? = list().firstOrNull { it.sourceId == sourceId && it.mangaUrl == mangaUrl }

    @Synchronized
    fun upsert(entry: LibraryEntry) {
        val others = list().filterNot { it.key == entry.key }
        save(others + entry)
    }

    @Synchronized
    fun remove(
        sourceId: Long,
        mangaUrl: String,
    ): Boolean {
        val current = list()
        val filtered = current.filterNot { it.sourceId == sourceId && it.mangaUrl == mangaUrl }
        if (filtered.size == current.size) return false
        save(filtered)
        return true
    }

    private fun save(entries: List<LibraryEntry>) {
        // Guard against the wipe cascade: never replace a populated library with an empty one. A parse
        // failure that slipped past SafeFile, or a caller filtering to nothing, must not blank the file.
        if (entries.isEmpty() && list().size > 1) {
            log.error("refusing to overwrite library ({} entries) with an empty list", list().size)
            return
        }
        SafeFile.writeAtomic(file, json.encodeToString(entries.sortedBy { it.title.lowercase() }))
    }
}
