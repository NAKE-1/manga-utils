/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.library

import kotlinx.serialization.json.Json
import mangautils.core.config.AppConfig
import mangautils.core.util.SafeFile

/** Bookmarked chapters, per manga, in `data/bookmarks.json` ("sourceId|mangaUrl" -> set of urls). */
object BookmarkStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = AppConfig.dataDir.resolve("bookmarks.json")

    @Synchronized
    private fun load(): MutableMap<String, MutableSet<String>> =
        SafeFile.read(file) {
            runCatching {
                json.decodeFromString<Map<String, Set<String>>>(it)
                    .mapValues { e -> e.value.toMutableSet() }.toMutableMap()
            }.getOrNull()
        } ?: mutableMapOf()

    @Synchronized
    private fun save(map: Map<String, Set<String>>) = SafeFile.writeAtomic(file, json.encodeToString(map))

    private fun key(sourceId: Long, mangaUrl: String) = "$sourceId|$mangaUrl"

    fun bookmarks(sourceId: Long, mangaUrl: String): Set<String> = load()[key(sourceId, mangaUrl)].orEmpty()

    @Synchronized
    fun setBookmarked(sourceId: Long, mangaUrl: String, chapterUrl: String, value: Boolean) {
        val map = load()
        val set = map.getOrPut(key(sourceId, mangaUrl)) { mutableSetOf() }
        if (value) set.add(chapterUrl) else set.remove(chapterUrl)
        save(map)
    }
}
