/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * "What's new" for an extension: the recent upstream commit messages from keiyoushi/extensions-source
 * for that extension's source path (`src/<lang>/<name>`). The repo index carries no changelog, so this
 * is the only place the text lives.
 *
 * Lazy (fetched only when the user expands a row) + cached per pkg, because GitHub's unauthenticated API
 * is 60 req/hr. Always returns a [ChangelogDto] — on a path we can't map or a failed/rate-limited fetch,
 * `commits` is empty and the UI degrades to the `githubUrl` "view on GitHub" link.
 */
object ExtensionChangelog {
    private val log = LoggerFactory.getLogger(javaClass)
    private const val REPO = "keiyoushi/extensions-source"
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6h — a version's changelog doesn't change

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Pair<Long, ChangelogDto>>()

    @Serializable data class Commit(val title: String, val body: String, val url: String, val date: String)
    @Serializable data class ChangelogDto(val commits: List<Commit>, val githubUrl: String)

    /** `eu.kanade.tachiyomi.extension.<lang>.<name>` -> `src/<lang>/<name>`, or null if it doesn't fit the
     *  standard single-source layout (multisrc/themed families live under generated paths we can't map). */
    private fun sourcePath(pkg: String): String? {
        val rest = pkg.removePrefix("eu.kanade.tachiyomi.extension.")
        if (rest == pkg) return null
        val dot = rest.indexOf('.')
        if (dot <= 0 || dot == rest.length - 1) return null
        val name = rest.substring(dot + 1)
        if ('.' in name) return null // nested/odd layout — can't map cleanly
        return "src/${rest.substring(0, dot)}/$name"
    }

    fun forPackage(pkg: String): ChangelogDto {
        cache[pkg]?.let { (at, dto) -> if (System.currentTimeMillis() - at < CACHE_TTL_MS) return dto }
        val path = sourcePath(pkg)
        val githubUrl =
            if (path != null) "https://github.com/$REPO/commits/main/$path"
            else "https://github.com/$REPO/commits/main?q=${pkg.substringAfterLast('.')}"
        val commits = if (path == null) emptyList() else runCatching { fetch(path) }.getOrElse {
            log.info("changelog fetch failed for {}: {}", pkg, it.message)
            emptyList()
        }
        val dto = ChangelogDto(commits, githubUrl)
        cache[pkg] = System.currentTimeMillis() to dto
        return dto
    }

    private fun fetch(path: String): List<Commit> {
        val url = "https://api.github.com/repos/$REPO/commits?path=$path&per_page=3"
        val req = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "manga-utils")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val arr = json.parseToJsonElement(resp.body!!.string()).jsonArray
            return arr.mapNotNull { el ->
                val o = el.jsonObject
                val commit = o["commit"]?.jsonObject ?: return@mapNotNull null
                val msg = commit["message"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val lines = msg.split("\n")
                // Drop the noisy tail Keiyoushi appends: the "---------" separator and Co-authored-by trailers.
                val body = lines.drop(1)
                    .takeWhile { !it.startsWith("Co-authored-by:") }
                    .filterNot { it.trim().matches(Regex("^-{3,}$")) }
                    .joinToString("\n").trim()
                Commit(
                    title = lines.first().trim(),
                    body = body,
                    url = o["html_url"]?.jsonPrimitive?.contentOrNull ?: "https://github.com/$REPO",
                    date = commit["committer"]?.jsonObject?.get("date")?.jsonPrimitive?.contentOrNull ?: "",
                )
            }
        }
    }
}
