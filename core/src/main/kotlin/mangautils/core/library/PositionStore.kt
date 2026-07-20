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
 * How far through each chapter you got, per manga, in `data/positions.json`. Keyed "sourceId|mangaUrl"
 * → chapterUrl → fraction (0..1).
 *
 * Server-side rather than in the browser so picking a chapter up on your phone lands where you stopped
 * on your PC. It sits beside [ReadStore] rather than inside it because the shape differs (a fraction per
 * chapter, not a set) and folding it in would change read.json's schema under existing data.
 *
 * Only mid-chapter positions are worth keeping: the very start and the very end both mean "open at the
 * top", so those are dropped instead of stored.
 */
object PositionStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = AppConfig.dataDir.resolve("positions.json")

    @Synchronized
    private fun load(): MutableMap<String, MutableMap<String, Float>> {
        if (!file.exists()) return mutableMapOf()
        return runCatching {
            json.decodeFromString<Map<String, Map<String, Float>>>(file.readText())
                .mapValues { it.value.toMutableMap() }
                .toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    @Synchronized
    private fun save(map: Map<String, Map<String, Float>>) {
        file.createParentDirectories()
        file.writeText(json.encodeToString(map))
    }

    private fun key(
        sourceId: Long,
        mangaUrl: String,
    ) = "$sourceId|$mangaUrl"

    fun positions(
        sourceId: Long,
        mangaUrl: String,
    ): Map<String, Float> = load()[key(sourceId, mangaUrl)].orEmpty()

    @Synchronized
    fun set(
        sourceId: Long,
        mangaUrl: String,
        chapterUrl: String,
        fraction: Float,
    ) {
        val map = load()
        val forManga = map.getOrPut(key(sourceId, mangaUrl)) { mutableMapOf() }
        // <=2% is "hasn't started", >=98% is "finished" - both should open at the top next time.
        if (fraction <= 0.02f || fraction >= 0.98f) forManga.remove(chapterUrl) else forManga[chapterUrl] = fraction
        if (forManga.isEmpty()) map.remove(key(sourceId, mangaUrl))
        save(map)
    }

    /**
     * Forget where you were. Marking a chapter unread means you intend to read it again from the start,
     * so the resume point has to go with the read flag - otherwise reopening drops you back into the
     * read you just discarded.
     */
    @Synchronized
    fun clear(
        sourceId: Long,
        mangaUrl: String,
        chapterUrl: String,
    ) {
        val map = load()
        val forManga = map[key(sourceId, mangaUrl)] ?: return
        if (forManga.remove(chapterUrl) == null) return
        if (forManga.isEmpty()) map.remove(key(sourceId, mangaUrl))
        save(map)
    }
}
