/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.library

import kotlinx.serialization.json.Json
import mangautils.core.config.AppConfig
import mangautils.core.util.SafeFile

/**
 * Manga-level bookmarks — a "saved" list that is INDEPENDENT of the library (a manga can be
 * bookmarked without being followed, and vice-versa). Stored in `data/manga_bookmarks.json` as a
 * set of "sourceId|mangaUrl" keys. (Chapter-level bookmarks live in [BookmarkStore].)
 */
object MangaBookmarkStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = AppConfig.dataDir.resolve("manga_bookmarks.json")

    @Synchronized
    private fun load(): MutableSet<String> =
        SafeFile.read(file) { runCatching { json.decodeFromString<Set<String>>(it).toMutableSet() }.getOrNull() }
            ?: mutableSetOf()

    @Synchronized
    private fun save(set: Set<String>) = SafeFile.writeAtomic(file, json.encodeToString(set))

    private fun key(sourceId: Long, mangaUrl: String) = "$sourceId|$mangaUrl"

    fun isBookmarked(sourceId: Long, mangaUrl: String): Boolean = key(sourceId, mangaUrl) in load()

    fun list(): Set<String> = load()

    /** Merge backup keys ("sourceId|mangaUrl") into the saved set, for restore. */
    @Synchronized
    fun restore(keys: Set<String>) {
        if (keys.isEmpty()) return
        val s = load()
        s.addAll(keys)
        save(s)
    }

    @Synchronized
    fun set(sourceId: Long, mangaUrl: String, value: Boolean) {
        val s = load()
        if (value) s.add(key(sourceId, mangaUrl)) else s.remove(key(sourceId, mangaUrl))
        save(s)
    }
}
