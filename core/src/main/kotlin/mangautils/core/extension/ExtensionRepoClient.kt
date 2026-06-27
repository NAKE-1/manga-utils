/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.extension

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Fetches and parses extension repository indexes (`index.min.json`).
 * Network only — installation/loading of the APKs is a separate concern.
 */
class ExtensionRepoClient(
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true }

    /** Download and parse the index at [indexUrl]. Throws on HTTP/parse failure. */
    fun fetchIndex(indexUrl: String): List<ExtensionRepoEntry> {
        log.debug("Fetching extension index: {}", indexUrl)
        val request = Request.Builder().url(indexUrl).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code} fetching index $indexUrl")
            }
            val body = response.body?.string().orEmpty()
            val entries = json.decodeFromString<List<ExtensionRepoEntry>>(body)
            log.info("Index {} has {} extension(s)", indexUrl, entries.size)
            return entries
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
