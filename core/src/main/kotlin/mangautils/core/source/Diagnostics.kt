/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.source

import eu.kanade.tachiyomi.source.online.HttpSource
import org.slf4j.LoggerFactory

/** Connection diagnostics against a source: latency + throughput, using the proven browse/image paths. */
object Diagnostics {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Result(
        val source: String,
        val baseUrl: String,
        val pingMs: Double,
        val speedMbps: Double,
        val sampleBytes: Long,
        val ok: Boolean,
        val error: String? = null,
    )

    /** Resolve a real cover, then fetch it [samples] times: best time = latency, bytes/time = speed. */
    fun run(sourceId: Long, samples: Int = 3): Result {
        val name = SourceManager.listInstalledSources().firstOrNull { it.id == sourceId }?.name ?: ""
        val base = (SourceManager.loadSource(sourceId) as? HttpSource)?.baseUrl ?: ""

        val popular = runCatching { SourceBrowser.popular(sourceId, 1) }
        popular.exceptionOrNull()?.let {
            return Result(name, base, 0.0, 0.0, 0, false, "Couldn't reach the source: ${it.message ?: it::class.simpleName}")
        }
        val thumb = popular.getOrNull()?.mangas?.firstNotNullOfOrNull { it.thumbnail_url?.takeIf(String::isNotBlank) }
            ?: return Result(name, base, 0.0, 0.0, 0, false, "The source returned no cover to test against")

        val times = ArrayList<Long>()
        var bytes = 0L
        repeat(samples) {
            val t0 = System.nanoTime()
            val b = runCatching { SourceImage.coverBytes(sourceId, thumb) }.getOrNull()
            if (b != null && b.isNotEmpty()) { times += (System.nanoTime() - t0) / 1_000_000; bytes = b.size.toLong() }
        }
        if (times.isEmpty()) return Result(name, base, 0.0, 0.0, 0, false, "Couldn't download an image from the source")

        val bestMs = times.min()
        val mbps = if (bestMs > 0 && bytes > 0) bytes * 8 / 1e6 / (bestMs / 1000.0) else 0.0
        log.debug("diag {}: best={}ms bytes={} mbps={}", name, bestMs, bytes, mbps)
        return Result(name, base, bestMs.toDouble(), mbps, bytes, true)
    }
}
