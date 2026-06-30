/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.extension

import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolves real extension icons. Tachiyomi/Mihon repos publish an icon per package at
 * `<repoBase>/icon/<pkg>.png` (alongside `index.min.json`). We index the configured repos once to
 * map `pkg -> iconUrl`, then fetch + cache the bytes. Works for already-installed extensions (no
 * re-install needed) since it keys on the package name.
 */
object ExtensionIcons {
    private val log = LoggerFactory.getLogger(javaClass)
    private val repoClient = ExtensionRepoClient()
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    private val iconUrlByPkg = ConcurrentHashMap<String, String>()
    private val bytesByUrl = ConcurrentHashMap<String, ByteArray>()

    @Volatile
    private var indexed = false

    @Synchronized
    private fun ensureIndexed() {
        if (indexed) return
        RepoStore.list().forEach { indexUrl ->
            runCatching {
                val base = indexUrl.substringBeforeLast('/', missingDelimiterValue = indexUrl)
                repoClient.fetchIndex(indexUrl).forEach { e -> iconUrlByPkg.putIfAbsent(e.pkg, "$base/icon/${e.pkg}.png") }
            }.onFailure { log.debug("icon index {} failed: {}", indexUrl, it.message) }
        }
        indexed = true
    }

    /** PNG bytes for the extension package's icon, or null if it can't be resolved/fetched. */
    fun iconBytes(pkg: String): ByteArray? {
        ensureIndexed()
        val url = iconUrlByPkg[pkg] ?: return null
        bytesByUrl[url]?.let { return it }
        return runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use {
                if (it.isSuccessful) it.body?.bytes()?.also { b -> bytesByUrl[url] = b } else null
            }
        }.getOrNull()
    }
}
