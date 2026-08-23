/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.download

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Per-series identity written into each download folder as `.series.json`. The folder name is only a
 * sanitized title, which drifts and loses the source; this records the real library key (sourceId +
 * mangaUrl) plus enough detail to rebuild a library entry from disk. Read source-first by the downloads
 * manager, so a folder reports its true source even after an unfollow or a title change.
 *
 * Not run through [mangautils.core.util.SafeFile]: it's regenerable (re-download or backfill rewrites it),
 * so it does a plain atomic write with no `.old` rotation — keeping the downloads tree clean of sidecar
 * clutter. The atomic rename still means a crash never leaves a half-written `.series.json`.
 */
@Serializable
data class SeriesMeta(
    val sourceId: Long,
    val sourceName: String = "",
    val mangaUrl: String,
    val title: String,
    val author: String? = null,
    val status: Int = 0,
    val genre: String? = null,
    val thumbnailUrl: String? = null,
    val savedAt: Long = 0,
) {
    companion object {
        const val FILE = ".series.json"
        private val log = LoggerFactory.getLogger(SeriesMeta::class.java)
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun read(seriesDir: Path): SeriesMeta? {
            val f = seriesDir.resolve(FILE)
            if (!f.exists()) return null
            return runCatching { json.decodeFromString<SeriesMeta>(f.readText()) }.getOrNull()
        }

        fun write(seriesDir: Path, meta: SeriesMeta) {
            runCatching {
                Files.createDirectories(seriesDir)
                val f = seriesDir.resolve(FILE)
                val tmp = seriesDir.resolve("$FILE.tmp")
                Files.write(tmp, json.encodeToString(meta).toByteArray(Charsets.UTF_8))
                runCatching { Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                    .onFailure { Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING) }
            }.onFailure { log.debug("couldn't write {}: {}", FILE, it.message) }
        }
    }
}
