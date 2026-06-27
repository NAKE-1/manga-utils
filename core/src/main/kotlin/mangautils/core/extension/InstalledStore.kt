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

/** Flat-file persistence of installed extensions (`extensions/installed.json`). */
object InstalledStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file get() = AppConfig.extensionsDir.resolve("installed.json")

    fun list(): List<InstalledExtension> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<InstalledExtension>>(file.readText()) }
            .getOrDefault(emptyList())
    }

    fun findByPkg(pkg: String): InstalledExtension? = list().firstOrNull { it.pkg == pkg }

    fun findExtensionForSource(sourceId: Long): InstalledExtension? =
        list().firstOrNull { ext -> ext.sources.any { it.id == sourceId } }

    /** Insert or replace the extension with the same pkg. */
    fun upsert(extension: InstalledExtension) {
        val updated = list().filterNot { it.pkg == extension.pkg } + extension
        save(updated)
    }

    fun remove(pkg: String): Boolean {
        val current = list()
        if (current.none { it.pkg == pkg }) return false
        save(current.filterNot { it.pkg == pkg })
        return true
    }

    private fun save(extensions: List<InstalledExtension>) {
        file.createParentDirectories()
        file.writeText(json.encodeToString(extensions.sortedBy { it.name.lowercase() }))
    }
}
