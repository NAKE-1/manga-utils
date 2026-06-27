/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.extension

import kotlinx.serialization.json.Json
import mangautils.core.config.AppConfig
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Persists the set of extension repository index URLs the user has added, as a small
 * JSON file under the data dir. This is the simple pre-database store; once the SQLite
 * schema lands (Phase 3) repos can move there, but a flat file is fine for now.
 */
object RepoStore {
    private val json = Json { prettyPrint = true }

    fun list(): List<String> {
        val file = AppConfig.reposFile
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<String>>(file.readText()) }.getOrDefault(emptyList())
    }

    /** Adds [url] if not already present. Returns true if it was newly added. */
    fun add(url: String): Boolean {
        val current = list()
        if (url in current) return false
        save(current + url)
        return true
    }

    /** Removes [url]. Returns true if it was present. */
    fun remove(url: String): Boolean {
        val current = list()
        if (url !in current) return false
        save(current - url)
        return true
    }

    private fun save(urls: List<String>) {
        val file = AppConfig.reposFile
        file.createParentDirectories()
        file.writeText(json.encodeToString(urls))
    }
}
