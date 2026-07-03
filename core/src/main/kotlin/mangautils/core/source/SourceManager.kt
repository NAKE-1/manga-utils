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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** Registry/loader for the sources provided by installed extensions. */
object SourceManager {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Learned origin->CDN host per source. Some sources (e.g. Atsumaru) serve every page image from
     * a slow origin that 302-redirects to a fast CDN. Once we've seen that redirect for a source, we
     * can fetch later page images STRAIGHT from the CDN and skip the ~135ms origin bounce. Falls back
     * to the source's own getImage on any miss/failure, so it can only speed things up, not break them.
     */
    private val cdnHosts = ConcurrentHashMap<Long, Pair<String, String>>() // sourceId -> (originHost, cdnHost)

    /** Record the origin->CDN mapping from a followed redirect (requested host != final host). */
    fun learnCdn(sourceId: Long, requestedUrl: String, finalUrl: String) {
        if (cdnHosts.containsKey(sourceId)) return
        val reqHost = requestedUrl.toHttpUrlOrNull()?.host ?: return
        val finHost = finalUrl.toHttpUrlOrNull()?.host ?: return
        if (finHost != reqHost) {
            cdnHosts[sourceId] = reqHost to finHost
            log.info("learned CDN for source {}: {} -> {} (reader images now fetch direct)", sourceId, reqHost, finHost)
        }
    }

    /** Rewrite an image URL to the learned CDN host (skips the origin 302). Unchanged if none learned. */
    fun cdnRewrite(sourceId: Long, url: String): String {
        val (origin, cdn) = cdnHosts[sourceId] ?: return url
        val u = url.toHttpUrlOrNull() ?: return url
        return if (u.host == origin) u.newBuilder().host(cdn).build().toString() else url
    }

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
