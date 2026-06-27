/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.library

import kotlinx.serialization.json.Json
import mangautils.core.config.AppConfig
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Flat-file persistence of the followed-series library (`data/library.json`). */
object LibraryStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file get() = AppConfig.dataDir.resolve("library.json")

    @Synchronized
    fun list(): List<LibraryEntry> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<LibraryEntry>>(file.readText()) }.getOrDefault(emptyList())
    }

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
        file.createParentDirectories()
        file.writeText(json.encodeToString(entries.sortedBy { it.title.lowercase() }))
    }
}
