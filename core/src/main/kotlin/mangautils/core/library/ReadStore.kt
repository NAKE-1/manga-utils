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

/**
 * Tracks which chapters have been read, per manga, in `data/read.json`. Keyed by "sourceId|mangaUrl"
 * → set of read chapter urls. Used by the reader's chapter list to show read/unread state.
 */
object ReadStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = AppConfig.dataDir.resolve("read.json")

    @Synchronized
    private fun load(): MutableMap<String, MutableSet<String>> {
        if (!file.exists()) return mutableMapOf()
        return runCatching {
            json.decodeFromString<Map<String, Set<String>>>(file.readText())
                .mapValues { it.value.toMutableSet() }
                .toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    @Synchronized
    private fun save(map: Map<String, Set<String>>) {
        file.createParentDirectories()
        file.writeText(json.encodeToString(map))
    }

    private fun key(
        sourceId: Long,
        mangaUrl: String,
    ) = "$sourceId|$mangaUrl"

    fun readUrls(
        sourceId: Long,
        mangaUrl: String,
    ): Set<String> = load()[key(sourceId, mangaUrl)].orEmpty()

    fun isRead(
        sourceId: Long,
        mangaUrl: String,
        chapterUrl: String,
    ): Boolean = chapterUrl in readUrls(sourceId, mangaUrl)

    @Synchronized
    fun setRead(
        sourceId: Long,
        mangaUrl: String,
        chapterUrl: String,
        read: Boolean,
    ) {
        val map = load()
        val set = map.getOrPut(key(sourceId, mangaUrl)) { mutableSetOf() }
        if (read) set.add(chapterUrl) else set.remove(chapterUrl)
        save(map)
    }

    /** Bulk-mark chapters read (one load+save) — used by backup import. */
    @Synchronized
    fun markRead(
        sourceId: Long,
        mangaUrl: String,
        chapterUrls: Collection<String>,
    ) {
        if (chapterUrls.isEmpty()) return
        val map = load()
        map.getOrPut(key(sourceId, mangaUrl)) { mutableSetOf() }.addAll(chapterUrls)
        save(map)
    }

    /** Mark every chapter of a manga unread (drop its read set). */
    @Synchronized
    fun clear(
        sourceId: Long,
        mangaUrl: String,
    ) {
        val map = load()
        if (map.remove(key(sourceId, mangaUrl)) != null) save(map)
    }
}
