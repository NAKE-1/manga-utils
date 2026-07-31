/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.extension

import mangautils.core.config.AppConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolves and caches real extension icons.
 *
 * The v2 index publishes an absolute [ExtensionRepoEntry.icon] URL per package (currently a jsDelivr
 * mipmap of the extension's `ic_launcher.png`; the old `<repoBase>/icon/<pkg>.png` convention is dead).
 * We index the configured repos once to map `pkg -> iconUrl`, fetch each icon on first use, and cache
 * the PNG **on disk** under `extensions/icons/`. After that, serving is a local file read with no
 * network — the cache is only refreshed when an extension is (re)installed (see [invalidate], called
 * from the installer), since that's the only time the icon can change.
 */
object ExtensionIcons {
    private val log = LoggerFactory.getLogger(javaClass)
    private val repoClient = ExtensionRepoClient()
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    private val iconUrlByPkg = ConcurrentHashMap<String, String>()

    @Volatile private var indexed = false

    private fun cacheFile(pkg: String) = AppConfig.extensionsDir.resolve("icons").resolve("$pkg.png")

    @Synchronized
    private fun ensureIndexed() {
        // Retry if a previous attempt produced nothing (transient repo failure) so it self-heals.
        if (indexed && iconUrlByPkg.isNotEmpty()) return
        var any = false
        RepoStore.list().forEach { indexUrl ->
            runCatching {
                repoClient.fetchIndex(indexUrl).forEach { e ->
                    e.icon?.takeIf { it.isNotBlank() }?.let { iconUrlByPkg.putIfAbsent(e.pkg, it); any = true }
                }
            }.onFailure { log.debug("icon index {} failed: {}", indexUrl, it.message) }
        }
        if (any) indexed = true
    }

    /** PNG bytes for the package's icon — from the on-disk cache, else fetched once and cached. */
    fun iconBytes(pkg: String): ByteArray? {
        val cache = cacheFile(pkg)
        runCatching { if (Files.exists(cache)) return Files.readAllBytes(cache) }.getOrNull()

        ensureIndexed()
        val url = iconUrlByPkg[pkg] ?: return null
        return runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val bytes = r.body?.bytes() ?: return null
                runCatching { Files.createDirectories(cache.parent); Files.write(cache, bytes) }
                bytes
            }
        }.getOrNull()
    }

    /** Drop the cached icon so the next request re-fetches it — call on (re)install/update. */
    fun invalidate(pkg: String) {
        iconUrlByPkg.remove(pkg)
        indexed = false // re-read the index in case the icon URL changed with the update
        runCatching { Files.deleteIfExists(cacheFile(pkg)) }
    }
}
