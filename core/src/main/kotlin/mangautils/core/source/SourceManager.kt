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
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** Registry/loader for the sources provided by installed extensions. */
object SourceManager {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Loaded source instances, kept alive so we don't re-instantiate the extension (reload its class
     * from the jar) on every browse/detail/image/download — sources are effectively singletons, like
     * Mihon/Suwayomi. Reusing them also reuses each source's OkHttp client (connection pooling).
     * Cleared by [invalidate] when an extension is installed/updated/removed.
     */
    private val sourceCache = ConcurrentHashMap<Long, Source>()

    /**
     * Per-source client for BULK image fetching (cover grids). It reuses the source's client — same
     * interceptors, cookies, Cloudflare setup and connection pool — but with an isolated [Dispatcher]
     * so a wall of cover loads can't consume the per-host request slots that interactive search /
     * detail requests need on the source's main client. The per-host cap is kept MODERATE on purpose:
     * covers are full-size posters (often 1-4 MB), so too many at once saturate bandwidth and stall
     * the small search/detail responses. Cleared with [sourceCache].
     */
    private val imageClients = ConcurrentHashMap<Long, OkHttpClient>()
    private val imageDispatcher = Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 8 }

    /** Drop cached source instances + their image clients (after an extension is installed/updated/removed). */
    fun invalidate() { sourceCache.clear(); imageClients.clear() }

    /** A client for bulk image/cover fetching: the source's client + pool + interceptors, isolated dispatcher. */
    fun imageClient(sourceId: Long): OkHttpClient? {
        imageClients[sourceId]?.let { return it }
        val src = loadSource(sourceId) as? HttpSource ?: return null
        return src.client.newBuilder().dispatcher(imageDispatcher).build().also { imageClients[sourceId] = it }
    }

    /** All sources across installed extensions (cheap; reads metadata only). */
    fun listInstalledSources(): List<InstalledSource> =
        InstalledStore.list().flatMap { it.sources }.sortedBy { it.name.lowercase() }

    /** Instantiate and return the live [Source] with [sourceId], or null if not installed. */
    fun loadSource(sourceId: Long): Source? {
        sourceCache[sourceId]?.let { return it }
        val ext = InstalledStore.findExtensionForSource(sourceId) ?: return null
        return loadSourcesOf(ext).firstOrNull { it.id == sourceId }
    }

    /** Instantiate every [Source] an extension provides (boots the runtime as needed), and cache them. */
    fun loadSourcesOf(ext: InstalledExtension): List<Source> {
        ExtensionRuntime.ensureStarted()
        return ext.classNames.flatMap { className ->
            val instance = ExtensionLoader.loadExtensionInstance(ext.jarPath, className)
            expand(instance)
        }.onEach { sourceCache[it.id] = it }
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
