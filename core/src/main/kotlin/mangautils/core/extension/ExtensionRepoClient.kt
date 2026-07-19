/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.extension

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

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

    @OptIn(ExperimentalSerializationApi::class)
    private val protoBuf = ProtoBuf { encodeDefaults = false }

    /**
     * Download and parse the index at [indexUrl]. Throws on HTTP/parse failure.
     *
     * Three formats are accepted, told apart by their first byte rather than by URL:
     *  - `[` a legacy `index.min.json` array. We then try to upgrade to the v2 index, because only
     *        v2 states the prebuilt-jar URL, the signing key and each extension's lib version.
     *  - `{` a `repo.json` pointer, whose `index_v2` names the real index.
     *  - anything else: the v2 index itself, a (usually gzipped) protobuf.
     */
    fun fetchIndex(indexUrl: String): List<ExtensionRepoEntry> {
        log.debug("Fetching extension index: {}", indexUrl)
        val bytes = get(indexUrl)
        val entries =
            when (bytes.firstOrNull()) {
                '['.code.toByte() -> upgradeOrLegacy(indexUrl, bytes)
                '{'.code.toByte() -> followPointer(indexUrl, bytes)
                else -> decodeV2(bytes, indexUrl)
            }
        log.info("Index {} has {} extension(s)", indexUrl, entries.size)
        return entries
    }

    /** The repo's signing-key fingerprint, when it publishes a v2 index. Null for a legacy repo. */
    fun signingKey(indexUrl: String): String? =
        runCatching {
            val bytes = get(indexUrl)
            when (bytes.firstOrNull()) {
                '['.code.toByte() ->
                    json
                        .decodeFromString<RepoPointer>(String(get(pointerUrl(indexUrl))))
                        .meta
                        ?.signingKeyFingerprint
                '{'.code.toByte() -> json.decodeFromString<RepoPointer>(String(bytes)).meta?.signingKeyFingerprint
                else -> decodeStore(bytes).signingKey
            }?.takeIf { it.isNotBlank() }
        }.getOrNull()

    /** A legacy array. Prefer the v2 index if this repo also publishes one; otherwise parse as-is. */
    private fun upgradeOrLegacy(
        indexUrl: String,
        bytes: ByteArray,
    ): List<ExtensionRepoEntry> =
        runCatching { followPointer(pointerUrl(indexUrl), get(pointerUrl(indexUrl))) }
            .getOrElse {
                log.debug("No v2 index for {} ({}); using the legacy index", indexUrl, it.message)
                json.decodeFromString<List<ExtensionRepoEntry>>(String(bytes))
            }

    /** `repo.json` -> follow `index_v2` to the real index. */
    private fun followPointer(
        pointerUrl: String,
        bytes: ByteArray,
    ): List<ExtensionRepoEntry> {
        val pointer = json.decodeFromString<RepoPointer>(String(bytes))
        val next = pointer.indexV2?.takeIf { it.isNotBlank() } ?: error("repo.json at $pointerUrl has no index_v2")
        return decodeV2(get(next), next)
    }

    private fun decodeV2(
        bytes: ByteArray,
        indexUrl: String,
    ): List<ExtensionRepoEntry> {
        val store = decodeStore(bytes)
        // The list is usually inline, but the format allows it to live in its own file.
        val list =
            store.extensionList
                ?: store.extensionListUrl?.let { decodeStore(get(it)).extensionList }
                ?: error("v2 index at $indexUrl has no extension list")
        return list.extensions.map { it.toRepoEntry() }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun decodeStore(bytes: ByteArray): ExtensionStoreV2 =
        if (bytes.firstOrNull() == '{'.code.toByte()) {
            json.decodeFromString<ExtensionStoreV2>(String(bytes))
        } else {
            protoBuf.decodeFromByteArray(ExtensionStoreV2.serializer(), bytes)
        }

    private fun pointerUrl(indexUrl: String): String =
        if (indexUrl.endsWith("/index.min.json")) {
            indexUrl.replace("/index.min.json", "/repo.json")
        } else {
            indexUrl
        }

    /** GET [url], transparently gunzipping — the v2 index is served gzipped. */
    private fun get(url: String): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} fetching $url")
            val raw = response.body?.bytes() ?: error("Empty body for $url")
            val gzipped = raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()
            return if (gzipped) GZIPInputStream(raw.inputStream()).use { it.readBytes() } else raw
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
