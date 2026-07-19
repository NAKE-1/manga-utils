/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.source

import eu.kanade.tachiyomi.network.interceptor.CloudflareClientMarker
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.online.HttpSource
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

    /** Packages the user manually **unloaded** (to free the .jar so it can be updated on Windows).
     *  In-memory only — a restart loads everything normally. */
    private val unloadedPkgs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun isLoaded(pkg: String): Boolean = pkg !in unloadedPkgs

    /**
     * Unload an extension: close+evict its class loader (releasing the Windows lock on its .jar so an
     * update can overwrite it) and stop instantiating its sources until [load] is called — otherwise the
     * next browse/search would re-open the jar and re-lock it. A GC nudge helps Windows free the handle.
     */
    fun unload(pkg: String) {
        val ext = InstalledStore.list().find { it.pkg == pkg } ?: return
        unloadedPkgs.add(pkg)
        runCatching { ExtensionLoader.releaseJar(ext.jarPath) }
        System.gc() // hint: collect the closed loader so Windows releases the .jar handle for the update
        log.info("unloaded extension {} — jar released for update", pkg)
    }

    /** Re-enable an unloaded extension; its sources instantiate again on next use. */
    fun load(pkg: String) {
        if (unloadedPkgs.remove(pkg)) log.info("loaded extension {}", pkg)
    }

    /** Instantiate and return the live [Source] with [sourceId], or null if not installed / unloaded. */
    fun loadSource(sourceId: Long): Source? {
        val ext = InstalledStore.findExtensionForSource(sourceId) ?: return null
        if (ext.pkg in unloadedPkgs) return null // unloaded for maintenance — don't re-instantiate (would re-lock the jar)
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

    /** True if the extension built this source on the Cloudflare client (its CF-protected flag). */
    fun usesCloudflare(source: Source): Boolean =
        (source as? HttpSource)?.client?.interceptors?.any { it is CloudflareClientMarker } == true

    /**
     * Load every installed source once and record which are Cloudflare-protected. Mirrors how
     * Suwayomi knows (the extension's client choice). Safe to run in the background at startup.
     */
    fun detectCloudflare() {
        InstalledStore.list().forEach { ext ->
            runCatching { loadSourcesOf(ext).forEach { if (usesCloudflare(it)) CloudflareState.mark(it.id) } }
                .onFailure { log.debug("CF detect failed for {}: {}", ext.name, it.message) }
        }
    }
}
