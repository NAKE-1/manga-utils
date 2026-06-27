/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.source

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import mangautils.core.extension.InstalledExtension
import mangautils.core.extension.InstalledSource
import mangautils.core.extension.InstalledStore
import mangautils.core.extension.internal.ExtensionLoader
import mangautils.core.runtime.ExtensionRuntime
import org.slf4j.LoggerFactory

/** Registry/loader for the sources provided by installed extensions. */
object SourceManager {
    private val log = LoggerFactory.getLogger(javaClass)

    /** All sources across installed extensions (cheap; reads metadata only). */
    fun listInstalledSources(): List<InstalledSource> =
        InstalledStore.list().flatMap { it.sources }.sortedBy { it.name.lowercase() }

    /** Instantiate and return the live [Source] with [sourceId], or null if not installed. */
    fun loadSource(sourceId: Long): Source? {
        val ext = InstalledStore.findExtensionForSource(sourceId) ?: return null
        return loadSourcesOf(ext).firstOrNull { it.id == sourceId }
    }

    /** Instantiate every [Source] an extension provides (boots the runtime as needed). */
    fun loadSourcesOf(ext: InstalledExtension): List<Source> {
        ExtensionRuntime.ensureStarted()
        return ext.classNames.flatMap { className ->
            val instance = ExtensionLoader.loadExtensionInstance(ext.jarPath, className)
            expand(instance)
        }
    }

    /** A loaded entry class is either a single Source or a factory of several. */
    fun expand(instance: Any): List<Source> =
        when (instance) {
            is SourceFactory -> instance.createSources()
            is Source -> listOf(instance)
            else -> {
                log.warn("Loaded class is neither Source nor SourceFactory: {}", instance::class.java.name)
                emptyList()
            }
        }

    fun langOf(source: Source): String = (source as? CatalogueSource)?.lang ?: ""
}
