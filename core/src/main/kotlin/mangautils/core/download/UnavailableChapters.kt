/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.download

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mangautils.core.config.AppConfig
import org.slf4j.LoggerFactory
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Chapters the source itself cannot serve — its images 404, or it no longer lists the chapter.
 *
 * These are *permanent* failures: nothing on our side will fix them, and the source has to republish
 * the chapter. Without remembering them, every "download missing" and every library update queues them
 * again, they fail again, and a genuinely-complete series reads as permanently incomplete.
 *
 * Only permanent causes land here. A source being down, rate-limiting, or timing out is temporary and
 * must stay retryable — recording those would quietly abandon chapters that would work tomorrow.
 */
object UnavailableChapters {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Serializable
    data class Entry(
        val url: String,
        val title: String = "",
        val name: String = "",
        val reason: String = "",
        val at: Long = 0,
    )

    private val file get() = AppConfig.dataDir.resolve("unavailable-chapters.json")

    @Volatile private var cache: List<Entry>? = null

    @Synchronized
    fun list(): List<Entry> =
        cache ?: runCatching {
            if (file.exists()) json.decodeFromString<List<Entry>>(file.readText()) else emptyList()
        }.getOrDefault(emptyList()).also { cache = it }

    /** URLs to skip. Checked per chapter, so keep it a set. */
    fun urls(): Set<String> = list().mapTo(HashSet()) { it.url }

    fun isUnavailable(url: String): Boolean = url in urls()

    /** Record a chapter the source can't serve. Re-marking updates the reason rather than duplicating. */
    @Synchronized
    fun mark(
        url: String,
        title: String,
        name: String,
        reason: String,
        at: Long,
    ) {
        if (url.isBlank()) return
        val next = list().filterNot { it.url == url } + Entry(url, title, name, reason, at)
        save(next)
        log.info("marked unavailable: '{}' ({}) - {}", name, title, reason)
    }

    /** Forget one chapter (a Force download), or all of them when [url] is null. */
    @Synchronized
    fun clear(url: String?) {
        if (url == null) {
            save(emptyList())
            log.info("cleared all unavailable-chapter marks")
        } else {
            save(list().filterNot { it.url == url })
            log.info("cleared unavailable mark for {}", url)
        }
    }

    private fun save(entries: List<Entry>) {
        cache = entries
        runCatching {
            file.createParentDirectories()
            file.writeText(json.encodeToString(entries))
        }.onFailure { log.warn("could not save unavailable-chapters: {}", it.message) }
    }
}
