/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.server

import eu.kanade.tachiyomi.network.interceptor.FlareSolverrConfig
import eu.kanade.tachiyomi.network.interceptor.SolverConfig
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import mangautils.core.source.SourceBrowser
import mangautils.core.source.SourceManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Dev "is MangaFire actually working?" self-test. Pings the solver sidecar's health, then runs a REAL
 * popular-page fetch through the full client → interceptor → solver chain and reports whether data came
 * back. This exercises exactly the path a cold search uses (okhttp 403 → JCEF skipped for the hard host →
 * FlareSolverr interceptor → solver in-page fetch), so a green result means the whole pipeline works.
 */
@Serializable
data class SolverTestDto(
    val solverConfigured: Boolean,
    val solverUrl: String? = null,
    val solverHealthy: Boolean = false,
    val solverOrigin: String? = null,
    val flareReachable: Boolean = true,
    val sourceId: String? = null,
    val sourceName: String? = null,
    val host: String? = null,
    val ok: Boolean = false,
    val results: Int = 0,
    val ms: Long = 0,
    val error: String? = null,
)

object SolverTest {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    /** Ping the solver's /health → (reachable, the origin its tab is parked on). */
    private fun health(): Pair<Boolean, String?> {
        val base = SolverConfig.url?.trimEnd('/') ?: return false to null
        return runCatching {
            http.newCall(Request.Builder().url("$base/health").build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                val origin = Regex("\"origin\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)?.ifBlank { null }
                r.isSuccessful to origin
            }
        }.getOrDefault(false to null)
    }

    /** First installed source whose host routes through the solver (i.e. MangaFire). */
    private fun hardHostSource(): HttpSource? =
        SourceManager.listInstalledSources().asSequence()
            .mapNotNull { SourceManager.loadSource(it.id) as? HttpSource }
            .firstOrNull { src ->
                val host = runCatching { java.net.URI(src.baseUrl).host }.getOrNull() ?: return@firstOrNull false
                FlareSolverrConfig.fetchesThrough(host)
            }

    suspend fun run(explicitId: Long?): SolverTestDto {
        val (healthy, origin) = health()
        val base = SolverConfig.url
        val src = explicitId?.let { SourceManager.loadSource(it) as? HttpSource } ?: hardHostSource()
        if (src == null) {
            return SolverTestDto(
                solverConfigured = SolverConfig.enabled, solverUrl = base, solverHealthy = healthy, solverOrigin = origin,
                flareReachable = FlareSolverrConfig.reachable,
                error = "no installed hard-host source (MangaFire) found — is the extension installed?",
            )
        }
        val host = runCatching { java.net.URI(src.baseUrl).host }.getOrNull()
        val start = System.currentTimeMillis()
        return try {
            val page = SourceBrowser.popularAsync(src.id, 1)
            SolverTestDto(
                solverConfigured = SolverConfig.enabled, solverUrl = base, solverHealthy = healthy, solverOrigin = origin,
                flareReachable = FlareSolverrConfig.reachable,
                sourceId = src.id.toString(), sourceName = src.name, host = host,
                ok = page.mangas.isNotEmpty(), results = page.mangas.size, ms = System.currentTimeMillis() - start,
            )
        } catch (e: Exception) {
            SolverTestDto(
                solverConfigured = SolverConfig.enabled, solverUrl = base, solverHealthy = healthy, solverOrigin = origin,
                flareReachable = FlareSolverrConfig.reachable,
                sourceId = src.id.toString(), sourceName = src.name, host = host,
                ok = false, ms = System.currentTimeMillis() - start, error = e.message ?: e.toString(),
            )
        }
    }
}
