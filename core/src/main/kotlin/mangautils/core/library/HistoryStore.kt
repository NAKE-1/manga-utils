/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mangautils.core.config.AppConfig
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class HistoryEntry(
    val sourceId: Long,
    val mangaUrl: String,
    val mangaTitle: String,
    val thumbnailUrl: String?,
    val chapterUrl: String,
    val chapterName: String,
    val readAt: Long,
)

/** Recently-read chapters, newest first, in `data/history.json`. */
object HistoryStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = AppConfig.dataDir.resolve("history.json")
    private const val MAX = 500

    @Synchronized
    private fun load(): MutableList<HistoryEntry> {
        if (!file.exists()) return mutableListOf()
        return runCatching { json.decodeFromString<List<HistoryEntry>>(file.readText()).toMutableList() }.getOrDefault(mutableListOf())
    }

    @Synchronized
    private fun save(list: List<HistoryEntry>) {
        file.createParentDirectories()
        file.writeText(json.encodeToString(list))
    }

    @Synchronized
    fun record(
        sourceId: Long,
        mangaUrl: String,
        mangaTitle: String,
        chapterUrl: String,
        chapterName: String,
        thumbnailUrl: String? = null,
    ) {
        val list = load()
        list.removeAll { it.chapterUrl == chapterUrl && it.mangaUrl == mangaUrl }
        list.add(0, HistoryEntry(sourceId, mangaUrl, mangaTitle, thumbnailUrl, chapterUrl, chapterName, System.currentTimeMillis()))
        save(if (list.size > MAX) list.take(MAX) else list)
    }

    fun list(): List<HistoryEntry> = load()

    /** Remove every history entry for one manga (e.g. "remove from Continue reading"). */
    @Synchronized
    fun remove(sourceId: Long, mangaUrl: String) {
        val list = load()
        if (list.removeAll { it.sourceId == sourceId && it.mangaUrl == mangaUrl }) save(list)
    }

    @Synchronized
    fun clear() = save(emptyList())
}
