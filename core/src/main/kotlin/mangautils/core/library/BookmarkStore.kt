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

/** Bookmarked chapters, per manga, in `data/bookmarks.json` ("sourceId|mangaUrl" -> set of urls). */
object BookmarkStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = AppConfig.dataDir.resolve("bookmarks.json")

    @Synchronized
    private fun load(): MutableMap<String, MutableSet<String>> {
        if (!file.exists()) return mutableMapOf()
        return runCatching {
            json.decodeFromString<Map<String, Set<String>>>(file.readText())
                .mapValues { it.value.toMutableSet() }.toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    @Synchronized
    private fun save(map: Map<String, Set<String>>) {
        file.createParentDirectories()
        file.writeText(json.encodeToString(map))
    }

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
