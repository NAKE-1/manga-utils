/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.server

import eu.kanade.tachiyomi.network.RequestLog
import eu.kanade.tachiyomi.network.interceptor.FlareSolverrConfig
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import mangautils.core.config.AppConfig
import mangautils.core.source.CloudflareState
import mangautils.core.source.SourceManager
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.fileSize

/** Backing logic for the Developer screen's heavier tools (storage walk, diagnostics bundle). */
object DevTools {
    @Serializable
    data class Bucket(val label: String, val bytes: Long, val path: String = "")

    @Serializable
    data class StorageDto(
        val buckets: List<Bucket>,
        val downloadsTop: List<Bucket>,
        val downloadsDir: String,
        val dataDir: String,
    )

    // ponytail: full recursive walk on demand — can take a few seconds on a huge downloads tree; it's a
    // dev tool triggered by a button, so no caching. Add caching only if it's actually annoying.
    fun dirSize(p: Path): Long =
        runCatching {
            when {
                !Files.exists(p) -> 0L
                Files.isRegularFile(p) -> p.fileSize()
                else -> Files.walk(p).use { s ->
                    s.filter { Files.isRegularFile(it) }.mapToLong { runCatching { it.fileSize() }.getOrDefault(0L) }.sum()
                }
            }
        }.getOrDefault(0L)

    fun storage(): StorageDto {
        val dl = AppConfig.downloadsDir
        val buckets = listOf(
            Bucket("Downloads", dirSize(dl), dl.toString()),
            Bucket("Extensions", dirSize(AppConfig.extensionsDir)),
            Bucket("Database", dirSize(AppConfig.databaseFile)),
            Bucket("Logs", dirSize(AppConfig.logsDir)),
            Bucket("Data dir (total, excl. downloads if moved)", dirSize(AppConfig.dataDir), AppConfig.dataDir.toString()),
        )
        val top = runCatching {
            Files.list(dl).use { s ->
                s.filter { Files.isDirectory(it) }
                    .map { Bucket(it.fileName.toString(), dirSize(it)) }
                    .sorted { a, b -> b.bytes.compareTo(a.bytes) }
                    .limit(25).toList()
            }
        }.getOrDefault(emptyList())
        return StorageDto(buckets, top, dl.toString(), AppConfig.dataDir.toString())
    }

    // ---- state inspectors: read-only view of the persisted JSON state files ----

    @Serializable
    data class StateContentDto(val name: String, val content: String)

    /** installed.json + every top-level *.json in the data dir. Auto-discovered so nothing goes stale. */
    private fun stateFiles(): List<Path> {
        val out = mutableListOf<Path>()
        AppConfig.extensionsDir.resolve("installed.json").let { if (Files.exists(it)) out.add(it) }
        runCatching {
            Files.list(AppConfig.dataDir).use { s ->
                s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }.forEach { out.add(it) }
            }
        }
        return out.sortedBy { it.fileName.toString() }
    }

    fun stateList(): List<Bucket> =
        stateFiles().map { Bucket(it.fileName.toString(), runCatching { it.fileSize() }.getOrDefault(0L), it.toString()) }

    /** Content of a named state file — resolved against the discovered list, so no path traversal. */
    fun stateContent(name: String): String? =
        stateFiles().firstOrNull { it.fileName.toString() == name }?.let { runCatching { Files.readString(it) }.getOrNull() }

    // ---- source diagnostics ----

    @Serializable
    data class SourceDiagDto(
        val id: String,
        val name: String,
        val baseUrl: String,
        val host: String,
        val cfBlocked: Boolean,
        val cooldownMs: Long,
        val flareUa: String? = null,
    )

    @Serializable
    data class RawResultDto(val status: Int, val ms: Long, val contentType: String? = null, val snippet: String = "", val error: String? = null)

    fun sourceDiag(id: Long): SourceDiagDto {
        val src = SourceManager.loadSource(id) ?: error("source $id is not installed")
        val baseUrl = (src as? HttpSource)?.baseUrl.orEmpty()
        val host = runCatching { java.net.URI(baseUrl).host ?: "" }.getOrDefault("")
        val cooldown = (DownloadQueue.sourceRestUntil(id) - System.currentTimeMillis()).coerceAtLeast(0)
        return SourceDiagDto(
            id = id.toString(),
            name = src.name,
            baseUrl = baseUrl,
            host = host,
            cfBlocked = CloudflareState.isBlocked(id),
            cooldownMs = cooldown,
            flareUa = FlareSolverrConfig.solvedUserAgents[host],
        )
    }

    /** Fire a raw GET through the source's own client (dev-only; the URL is operator-supplied). */
    fun rawRequest(id: Long, url: String): RawResultDto {
        val src = SourceManager.loadSource(id) as? HttpSource
            ?: return RawResultDto(-1, 0, error = "source $id is not an HttpSource")
        val req = Request.Builder().url(url).headers(src.headers).build()
        val start = System.nanoTime()
        return try {
            src.client.newCall(req).execute().use { resp ->
                RawResultDto(resp.code, (System.nanoTime() - start) / 1_000_000, resp.header("Content-Type"), resp.peekBody(4096).string().take(3000))
            }
        } catch (e: Exception) {
            RawResultDto(-1, (System.nanoTime() - start) / 1_000_000, error = e.message ?: "request failed")
        }
    }

    // ---- diagnostics bundle ----

    fun diagnosticsZip(): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("diagnostics.txt"))
            zip.write(summaryText().toByteArray())
            zip.closeEntry()
            addDir(zip, AppConfig.logsDir, "logs/")
            // The persisted state JSONs — your own data, not secrets. Handy for reproducing an issue.
            stateFiles().forEach { f ->
                runCatching {
                    zip.putNextEntry(ZipEntry("state/${f.fileName}"))
                    Files.copy(f, zip)
                    zip.closeEntry()
                }
            }
        }
        return bos.toByteArray()
    }

    private fun summaryText(): String = buildString {
        val mem = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        appendLine("manga-utils diagnostics")
        appendLine("generated (epoch ms): ${System.currentTimeMillis()}")
        appendLine("uptime ms: ${ManagementFactory.getRuntimeMXBean().uptime}")
        appendLine("threads: ${ManagementFactory.getThreadMXBean().threadCount}")
        appendLine("heap used MB: ${mem.used / 1_048_576}")
        appendLine("jvm: ${System.getProperty("java.version")}")
        appendLine("os: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        appendLine("data dir: ${AppConfig.dataDir}")
        appendLine("downloads dir: ${AppConfig.downloadsDir}")
        appendLine()
        appendLine("== storage ==")
        storage().buckets.forEach { appendLine("${it.label}: ${it.bytes} bytes") }
        appendLine()
        appendLine("== recent requests (last 200) ==")
        RequestLog.snapshot().takeLast(200).forEach { appendLine("${it.time} ${it.code} ${it.method} ${it.host}${it.path} ${it.ms}ms") }
    }

    private fun addDir(zip: ZipOutputStream, dir: Path, prefix: String) {
        if (!Files.exists(dir)) return
        runCatching {
            Files.walk(dir).use { s ->
                s.filter { Files.isRegularFile(it) }.forEach { f ->
                    runCatching {
                        zip.putNextEntry(ZipEntry(prefix + dir.relativize(f).toString().replace('\\', '/')))
                        Files.copy(f, zip)
                        zip.closeEntry()
                    }
                }
            }
        }
    }
}
