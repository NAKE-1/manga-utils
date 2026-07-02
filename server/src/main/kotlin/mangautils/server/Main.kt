/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.server

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mangautils.core.config.AppConfig
import mangautils.core.config.SettingsStore
import mangautils.core.download.DownloadManager
import mangautils.core.download.DownloadStore
import mangautils.core.extension.ExtensionIcons
import mangautils.core.extension.ExtensionInstaller
import mangautils.core.extension.ExtensionRepoClient
import mangautils.core.extension.ExtensionRepoEntry
import mangautils.core.extension.ExtensionUpdates
import mangautils.core.extension.InstalledStore
import mangautils.core.extension.RepoStore
import mangautils.core.library.BookmarkStore
import mangautils.core.library.HistoryStore
import mangautils.core.library.LibraryEntry
import mangautils.core.library.LibraryService
import mangautils.core.library.LibraryStore
import mangautils.core.library.MangaBookmarkStore
import mangautils.core.library.ReadStore
import mangautils.core.source.CloudflareState
import mangautils.core.source.Diagnostics
import mangautils.core.source.SourceHealth
import mangautils.core.source.LocalChapterReader
import mangautils.core.source.SourceBrowser
import mangautils.core.source.SourceImage
import mangautils.core.source.SourceManager
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("mangautils.server")

/** Whether a Cloudflare bypass (e.g. FlareSolverr) is wired up. Not yet — sources behind
 *  Cloudflare currently fail with a 403, so the UI marks them black until this is true. */
const val CLOUDFLARE_BYPASS = false

// ---- DTOs (IDs are Strings to survive JS number precision) ----------------------------------

@Serializable
private data class SourceDto(val id: String, val name: String, val lang: String, val nsfw: Boolean, val cfState: String, val down: Boolean)

@Serializable
private data class MangaDto(
    val sourceId: String,
    val url: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genre: String? = null,
    val status: Int = 0,
)

@Serializable
private data class PageResultDto(val mangas: List<MangaDto>, val hasNextPage: Boolean)

@Serializable
private data class ChapterDto(
    val url: String,
    val name: String,
    val scanlator: String? = null,
    val dateUpload: Long = 0,
    val number: Float = -1f,
    val downloaded: Boolean = false,
)

@Serializable
private data class DetailDto(val manga: MangaDto, val chapters: List<ChapterDto>, val newChapters: List<String> = emptyList())

@Serializable
private data class LibraryDto(
    val sourceId: String,
    val url: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val author: String? = null,
    val status: Int = 0,
    val newChapters: Int = 0,
    /** Latest known chapter, for the "Ch N · <date>" line under the cover. */
    val lastNumber: Float = -1f,
    val lastName: String = "",
    val lastDate: Long = 0,
    /** Download-status badge on the cover: downloaded vs total known chapters. */
    val downloadedChapters: Int = 0,
    val totalChapters: Int = 0,
)

@Serializable private data class UpdateSummaryDto(val newChapters: Int, val updatedManga: Int)

@Serializable private data class ExtDto(val pkg: String, val name: String, val version: String, val lang: String, val nsfw: Boolean, val sources: Int, val repo: String = "")
@Serializable private data class AvailDto(val pkg: String, val name: String, val version: String, val lang: String, val nsfw: Boolean, val installed: Boolean, val hasUpdate: Boolean, val repo: String = "")
@Serializable private data class InstallReq(val pkg: String)
@Serializable private data class RepoReq(val url: String)
@Serializable private data class InstallResultDto(val pkg: String, val name: String, val sources: Int)

@Serializable private data class DlChapterReq(val url: String, val name: String = "")
@Serializable private data class DownloadReq(val source: String, val manga: String, val title: String = "", val chapters: List<DlChapterReq> = emptyList())
@Serializable private data class DlTaskDto(
    val id: String, val mangaKey: String, val mangaTitle: String, val state: String,
    val total: Int, val done: Int, val failed: Int,
    val currentChapter: String, val currentChapterUrl: String, val pagesDone: Int, val pagesTotal: Int,
    val kbps: Double, val error: String, val failedChapters: List<DlChapterReq>,
)
@Serializable private data class DownloadsDto(val tasks: List<DlTaskDto>, val active: Int, val queued: Int, val totalKbps: Double)
@Serializable private data class ManagedSeriesDto(val title: String, val chapters: Int, val incomplete: Int, val bytes: Long, val hasCover: Boolean)
@Serializable private data class ManagedChapterDto(val name: String, val pages: Int, val bytes: Long, val cbz: Boolean, val complete: Boolean)

@Serializable
private data class HistoryDto(
    val sourceId: String,
    val mangaUrl: String,
    val mangaTitle: String,
    val thumbnailUrl: String? = null,
    val chapterUrl: String,
    val chapterName: String,
    val readAt: Long,
)

@Serializable
private data class SettingsDto(
    val downloadDir: String?,
    val effectiveDownloadDir: String,
    val dataDir: String,
    val downloadAsCbz: Boolean,
    val downloadConcurrency: Int,
    val parallelDownloads: Int,
    val perSourceParallel: Boolean,
    val visibleLanguages: List<String>,
    val cloudflareBypass: Boolean,
)

@Serializable
private data class SettingsPatch(
    val downloadDir: String? = null,
    val downloadAsCbz: Boolean? = null,
    val downloadConcurrency: Int? = null,
    val parallelDownloads: Int? = null,
    val perSourceParallel: Boolean? = null,
    val visibleLanguages: List<String>? = null,
)

@Serializable
private data class DiagDto(
    val source: String,
    val baseUrl: String,
    val pingMs: Double,
    val speedMbps: Double,
    val sampleBytes: Long,
    val ok: Boolean,
    val error: String? = null,
)

@Serializable private data class ErrorDto(val error: String)

@Serializable
private data class MangaStateDto(val inLibrary: Boolean, val bookmarked: Boolean, val read: List<String>, val bookmarks: List<String>)

@Serializable
private data class CountDto(val count: Int)

@Serializable
private data class PagesDto(val count: Int)

@Serializable
private data class DevStatsDto(
    val pid: Long,
    val uptimeMs: Long,
    val processRssMb: Long, // real resident memory (Task-Manager working set); -1 if unknown
    val heapUsedMb: Long,
    val heapCommittedMb: Long,
    val heapMaxMb: Long,
    val nonHeapUsedMb: Long,
    val systemRamUsedMb: Long,
    val systemRamTotalMb: Long,
    val processCpuPct: Double,
    val threads: Int,
    val activeDownloads: Int,
    val queuedDownloads: Int,
    val installedSources: Int,
    val jvm: String,
    val os: String,
)

// Resident set size (real process RAM). Cached briefly so 2s polling doesn't spawn tasklist each time.
@Volatile private var rssCacheVal = -1L
@Volatile private var rssCacheAt = 0L

@Synchronized
private fun processRssBytes(): Long {
    val now = System.currentTimeMillis()
    if (rssCacheVal > 0 && now - rssCacheAt < 3000) return rssCacheVal
    val v = readRssBytes()
    if (v > 0) { rssCacheVal = v; rssCacheAt = now }
    return v
}

private fun readRssBytes(): Long {
    // Linux (the deploy target): /proc/self/statm — fields are pages; [1] = resident.
    runCatching {
        val statm = java.nio.file.Path.of("/proc/self/statm")
        if (java.nio.file.Files.exists(statm)) {
            val resident = java.nio.file.Files.readString(statm).trim().split(" ")[1].toLong()
            return resident * 4096L
        }
    }
    // Windows (dev): tasklist working set, e.g. "277,000 K".
    runCatching {
        val pid = ProcessHandle.current().pid()
        val proc = ProcessBuilder("tasklist", "/FI", "PID eq $pid", "/FO", "CSV", "/NH")
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val kb = Regex("\"([\\d.,]+) K\"").findAll(out).lastOrNull()?.groupValues?.get(1)
            ?.replace(",", "")?.replace(".", "")?.toLongOrNull()
        if (kb != null) return kb * 1024L
    }
    return -1L
}

/** Live JVM/host stats for the Developer panel. */
private fun devStats(): DevStatsDto {
    val mb = 1024L * 1024L
    val rt = java.lang.management.ManagementFactory.getRuntimeMXBean()
    val mem = java.lang.management.ManagementFactory.getMemoryMXBean()
    val heap = mem.heapMemoryUsage
    val nonHeap = mem.nonHeapMemoryUsage
    val threads = java.lang.management.ManagementFactory.getThreadMXBean().threadCount
    val sunOs = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
    val sysTotal = sunOs?.totalMemorySize ?: 0L
    val sysFree = sunOs?.freeMemorySize ?: 0L
    fun pct(d: Double?): Double = if (d == null || d < 0) 0.0 else Math.round(d * 1000) / 10.0
    return DevStatsDto(
        pid = ProcessHandle.current().pid(),
        uptimeMs = rt.uptime,
        processRssMb = processRssBytes().let { if (it > 0) it / mb else -1L },
        heapUsedMb = heap.used / mb,
        heapCommittedMb = heap.committed / mb,
        heapMaxMb = (if (heap.max > 0) heap.max else heap.committed) / mb,
        nonHeapUsedMb = nonHeap.used / mb,
        systemRamUsedMb = (sysTotal - sysFree) / mb,
        systemRamTotalMb = sysTotal / mb,
        processCpuPct = pct(sunOs?.processCpuLoad),
        threads = threads,
        activeDownloads = DownloadQueue.activeCount(),
        queuedDownloads = DownloadQueue.queuedCount(),
        installedSources = runCatching { SourceManager.listInstalledSources().size }.getOrDefault(0),
        jvm = "${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}",
        os = "${System.getProperty("os.name")} ${System.getProperty("os.arch")}",
    )
}

// ---- mappers --------------------------------------------------------------------------------

private fun SManga.toDto(sourceId: Long) = MangaDto(
    sourceId = sourceId.toString(),
    url = runCatching { url }.getOrDefault(""),
    title = runCatching { title }.getOrDefault(""),
    thumbnailUrl = runCatching { thumbnail_url }.getOrNull(),
    author = runCatching { author }.getOrNull(),
    artist = runCatching { artist }.getOrNull(),
    description = runCatching { description }.getOrNull(),
    genre = runCatching { genre }.getOrNull(),
    status = runCatching { status }.getOrDefault(0),
)

// ---- in-memory page-list cache (so /img/page doesn't re-fetch the list each page) ------------

private val pageCache = ConcurrentHashMap<String, List<Page>>()

// Cache fetched details so navigating back to a manga is instant (the source does ~3 slow calls).
private val detailCache = ConcurrentHashMap<String, DetailDto>()

// Cache thumbnailed cover bytes (per cover url) so the grid isn't re-fetching + re-resizing.
private val coverCache = ConcurrentHashMap<String, ByteArray>()

/** Build details from a cached library entry — instant + offline (mirrors the desktop). */
private fun cachedDetail(e: LibraryEntry): DetailDto = DetailDto(
    MangaDto(e.sourceId.toString(), e.mangaUrl, e.title, e.thumbnailUrl, e.author, e.artist, e.description, e.genre, e.status),
    e.knownChapters.map {
        ChapterDto(it.url, it.name, it.scanlator, it.dateUpload, it.number, runCatching { DownloadManager.isDownloaded(e.title, it.name) }.getOrDefault(false))
    },
    e.newChapters.toList(),
)

private fun pagesFor(sourceId: Long, chapterUrl: String): List<Page> =
    pageCache.getOrPut("$sourceId|$chapterUrl") { SourceImage.pageList(sourceId, chapterUrl) }

// Merged (entry, repoUrl) for the "available extensions" browse, cached 5 min (indexes are large).
@Volatile
private var availCache: Pair<Long, List<Pair<ExtensionRepoEntry, String>>>? = null
private fun availableEntries(): List<Pair<ExtensionRepoEntry, String>> {
    availCache?.let { (t, v) -> if (System.currentTimeMillis() - t < 300_000) return v }
    val client = ExtensionRepoClient()
    val seen = HashSet<String>()
    val entries = RepoStore.list().flatMap { repo ->
        runCatching { client.fetchIndex(repo) }.getOrDefault(emptyList()).map { it to repo }
    }.filter { seen.add(it.first.pkg) }
    availCache = System.currentTimeMillis() to entries
    return entries
}

private fun downloadsSnapshot(): DownloadsDto = DownloadsDto(
    DownloadQueue.tasks().map {
        DlTaskDto(
            it.id, "${it.sourceId}|${it.mangaUrl}", it.mangaTitle, it.state,
            it.total, it.doneCount, it.failedCount,
            it.currentChapter, it.currentChapterUrl, it.pagesDone, it.pagesTotal,
            it.bytesPerSec / 1024.0, it.error, it.failed.map { c -> DlChapterReq(c.url, c.name) },
        )
    },
    DownloadQueue.activeCount(),
    DownloadQueue.queuedCount(),
    DownloadQueue.totalBytesPerSec() / 1024.0,
)

/** Short readable name for a repo index URL (the GitHub org for raw URLs, else the host). */
private fun repoLabel(url: String): String = runCatching {
    val u = java.net.URI(url)
    if (u.host == "raw.githubusercontent.com") u.path.split('/').firstOrNull { it.isNotBlank() } ?: u.host else u.host
}.getOrDefault(url)

/** Guess an image content-type from magic bytes (covers + pages are mixed jpg/png/webp/gif). */
private fun sniffImageType(b: ByteArray): ContentType = when {
    b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> ContentType.Image.JPEG
    b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() -> ContentType.Image.PNG
    b.size >= 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() -> ContentType.Image.GIF
    b.size >= 12 && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() -> ContentType("image", "webp")
    else -> ContentType.Image.JPEG
}

fun main() {
    javax.imageio.ImageIO.scanForPlugins() // register twelvemonkeys WebP/JPEG readers+writers
    // Honor a custom downloads directory chosen in Settings.
    SettingsStore.get().downloadDir?.takeIf { it.isNotBlank() }?.let { AppConfig.downloadDirOverride = java.nio.file.Path.of(it) }
    // Detect Cloudflare-protected sources in the background (loads each source once).
    Thread { runCatching { mangautils.core.source.SourceManager.detectCloudflare() } }.apply { isDaemon = true; name = "cf-detect" }.start()
    val port = System.getenv("MANGA_WEB_PORT")?.toIntOrNull() ?: 8080
    log.info("Starting manga-utils web server on 0.0.0.0:{}", port)
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module() }.start(wait = true)
}

fun Application.module() {
    install(DefaultHeaders)
    install(CallLogging) {
        // Don't log the high-frequency poll + static-asset traffic (the Downloads screen hits
        // /api/downloads every second, images stream constantly) — it floods the console.
        filter { call ->
            val p = call.request.path()
            !(p == "/api/downloads" || p.startsWith("/img/") || p.startsWith("/assets/") || p == "/api/history" || p == "/api/dev/stats")
        }
    }
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    install(CORS) {
        anyHost() // Tailscale-only deployment; no auth.
        allowHeader(io.ktor.http.HttpHeaders.ContentType)
    }

    routing {
        // ---- Sources ----
        get("/api/sources") {
            val visible = SettingsStore.get().visibleLanguages
            val sources = withContext(Dispatchers.IO) {
                // nsfw lives on the parent extension; flatten so each source carries its 18+ flag.
                InstalledStore.list()
                    .flatMap { ext -> ext.sources.map { SourceDto(it.id.toString(), it.name, it.lang, ext.nsfw, cfState(it.id), SourceHealth.isDown(it.id)) } }
                    .filter { langVisible(it.lang, visible) }
                    .sortedBy { it.name.lowercase() }
            }
            call.respond(sources)
        }

        // Distinct languages across all installed sources (for the visibility picker).
        get("/api/languages") {
            val langs = withContext(Dispatchers.IO) {
                InstalledStore.list().flatMap { it.sources }.map { it.lang }.filter { it.isNotBlank() }.distinct().sorted()
            }
            call.respond(langs)
        }

        get("/api/sources/{id}/popular") { browse(call) { id, page -> SourceBrowser.popular(id, page) } }
        get("/api/sources/{id}/latest") { browse(call) { id, page -> SourceBrowser.latest(id, page) } }
        get("/api/sources/{id}/search") {
            val q = call.queryParam("q") ?: ""
            browse(call) { id, page -> SourceBrowser.search(id, q, page) }
        }

        get("/api/sources/{id}/manga") {
            val id = call.sourceId() ?: return@get call.respond(HttpStatusCode.BadRequest, "bad source id")
            val url = call.queryParam("url") ?: return@get call.respond(HttpStatusCode.BadRequest, "missing url")
            val refresh = call.queryParam("refresh")?.toBoolean() == true
            val key = "$id|$url"
            val detail = withContext(Dispatchers.IO) {
                if (!refresh) {
                    // Library entries serve from cache instantly (no network); else reuse a recent fetch.
                    LibraryStore.find(id, url)?.takeIf { it.knownChapters.isNotEmpty() }?.let { return@withContext Result.success(cachedDetail(it)) }
                    detailCache[key]?.let { return@withContext Result.success(it) }
                }
                runCatching {
                    val d = SourceBrowser.details(id, url)
                    val mangaTitle = runCatching { d.manga.title }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
                    // A manual refresh of a followed manga updates its library snapshot (detects new chapters).
                    if (refresh && LibraryService.isFollowed(id, url)) {
                        runCatching { LibraryService.addKnown(id, url, mangaTitle, d.manga, d.chapters) }
                    }
                    DetailDto(
                        d.manga.toDto(id),
                        d.chapters.map { ch ->
                            val chName = runCatching { ch.name }.getOrDefault("")
                            ChapterDto(
                                url = runCatching { ch.url }.getOrDefault(""),
                                name = chName,
                                scanlator = runCatching { ch.scanlator }.getOrNull(),
                                dateUpload = runCatching { ch.date_upload }.getOrDefault(0),
                                number = runCatching { ch.chapter_number }.getOrDefault(-1f),
                                downloaded = runCatching { DownloadManager.isDownloaded(mangaTitle, chName) }.getOrDefault(false),
                            )
                        },
                        LibraryStore.find(id, url)?.newChapters?.toList() ?: emptyList(),
                    ).also { detailCache[key] = it }
                }
            }
            detail.fold(
                onSuccess = { SourceHealth.markUp(id); call.respond(it) },
                onFailure = { markFailure(id, it); call.respond(HttpStatusCode.BadGateway, ErrorDto(sourceErrorMessage(it))) },
            )
        }

        // ---- Library ----
        get("/api/library") {
            val entries = withContext(Dispatchers.IO) {
                LibraryService.list().map {
                    val last = it.knownChapters.maxByOrNull { c -> c.number }
                    val downloaded = runCatching { DownloadManager.downloadCount(it.title) }.getOrDefault(0)
                    LibraryDto(it.sourceId.toString(), it.mangaUrl, it.title, it.thumbnailUrl, it.author, it.status, it.newChapters.size,
                        last?.number ?: -1f, last?.name ?: "", last?.dateUpload ?: 0, downloaded, it.knownChapters.size)
                }
            }
            call.respond(entries)
        }

        // Scan the whole library for new chapters (like the desktop). Updates per-manga newChapters.
        post("/api/library/update") {
            val results = withContext(Dispatchers.IO) { LibraryService.update() }
            call.respond(UpdateSummaryDto(results.sumOf { it.newChapters.size }, results.count { it.newChapters.isNotEmpty() }))
        }

        // ---- Per-manga state for the detail page (in-library + read + bookmarked) ----
        get("/api/manga/state") {
            val id = call.querySourceId() ?: return@get call.respond(HttpStatusCode.BadRequest)
            val url = call.queryParam("url") ?: return@get call.respond(HttpStatusCode.BadRequest)
            val st = withContext(Dispatchers.IO) {
                MangaStateDto(
                    LibraryService.isFollowed(id, url),
                    MangaBookmarkStore.isBookmarked(id, url),
                    ReadStore.readUrls(id, url).toList(),
                    BookmarkStore.bookmarks(id, url).toList(),
                )
            }
            call.respond(st)
        }

        post("/api/manga/bookmark") {
            val id = call.querySourceId() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val url = call.queryParam("url") ?: return@post call.respond(HttpStatusCode.BadRequest)
            val on = call.queryParam("on")?.toBoolean() ?: true
            withContext(Dispatchers.IO) { MangaBookmarkStore.set(id, url, on) }
            call.respond(HttpStatusCode.OK)
        }

        post("/api/library") {
            val id = call.querySourceId() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val url = call.queryParam("url") ?: return@post call.respond(HttpStatusCode.BadRequest)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val d = SourceBrowser.details(id, url)
                    val title = runCatching { d.manga.title }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
                    LibraryService.addKnown(id, url, title, d.manga, d.chapters)
                    true
                }.getOrDefault(false)
            }
            call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.BadGateway)
        }

        delete("/api/library") {
            val id = call.querySourceId() ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val url = call.queryParam("url") ?: return@delete call.respond(HttpStatusCode.BadRequest)
            withContext(Dispatchers.IO) { LibraryService.remove(id, url) }
            call.respond(HttpStatusCode.OK)
        }

        // ---- Download queue ----
        get("/api/downloads") { call.respond(downloadsSnapshot()) }
        post("/api/downloads") {
            val body = call.receive<DownloadReq>()
            val id = body.source.toLongOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("bad source id"))
            if (body.manga.isBlank() || body.chapters.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("no chapters to download"))
            val title = body.title.ifBlank { LibraryStore.find(id, body.manga)?.title ?: body.manga }
            DownloadQueue.enqueue(id, body.manga, title, body.chapters.map { DownloadQueue.Chapter(it.url, it.name) })
            call.respond(HttpStatusCode.Accepted, downloadsSnapshot())
        }
        post("/api/downloads/stop") {
            val id = call.queryParam("id") ?: return@post call.respond(HttpStatusCode.BadRequest)
            DownloadQueue.stop(id); call.respond(downloadsSnapshot())
        }
        post("/api/downloads/stop-all") { DownloadQueue.stopAll(); call.respond(downloadsSnapshot()) }
        post("/api/downloads/clear") { DownloadQueue.clearFinished(); call.respond(downloadsSnapshot()) }

        // ---- Download manager (browse / delete on-disk content) ----
        get("/api/downloads/manage") {
            val list = withContext(Dispatchers.IO) {
                DownloadStore.listSeries().map { ManagedSeriesDto(it.title, it.chapters, it.incomplete, it.bytes, it.hasCover) }
            }
            call.respond(list)
        }
        get("/api/downloads/manage/chapters") {
            val title = call.queryParam("title") ?: return@get call.respond(HttpStatusCode.BadRequest)
            val list = withContext(Dispatchers.IO) {
                DownloadStore.listChapters(title).map { ManagedChapterDto(it.name, it.pages, it.bytes, it.cbz, it.complete) }
            }
            call.respond(list)
        }
        // Delete every incomplete (interrupted) chapter of a series so they can be re-downloaded.
        post("/api/downloads/manage/delete-incomplete") {
            val title = call.queryParam("title") ?: return@post call.respond(HttpStatusCode.BadRequest)
            val n = withContext(Dispatchers.IO) {
                DownloadStore.listChapters(title).filterNot { it.complete }.onEach { DownloadStore.deleteChapter(title, it.name) }.size
            }
            call.respond(CountDto(n))
        }
        delete("/api/downloads/chapter") {
            val title = call.queryParam("title") ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val chapter = call.queryParam("chapter") ?: return@delete call.respond(HttpStatusCode.BadRequest)
            withContext(Dispatchers.IO) { DownloadStore.deleteChapter(title, chapter) }
            call.respond(HttpStatusCode.OK)
        }
        // Mark a downloaded series unread — resolves the manga via the library by (sanitized) title.
        post("/api/downloads/manage/mark-unread") {
            val title = call.queryParam("title") ?: return@post call.respond(HttpStatusCode.BadRequest)
            val matched = withContext(Dispatchers.IO) {
                LibraryStore.list().filter { DownloadManager.sanitize(it.title) == title }
                    .onEach { ReadStore.clear(it.sourceId, it.mangaUrl) }.size
            }
            call.respond(CountDto(matched))
        }

        // Downloaded-files management for a series (used by the remove-from-library confirm flow).
        get("/api/downloads/count") {
            val title = call.queryParam("title") ?: return@get call.respond(HttpStatusCode.BadRequest)
            val n = withContext(Dispatchers.IO) { DownloadManager.downloadCount(title) }
            call.respond(CountDto(n))
        }

        delete("/api/downloads") {
            val title = call.queryParam("title") ?: return@delete call.respond(HttpStatusCode.BadRequest)
            withContext(Dispatchers.IO) { DownloadManager.deleteDownloads(title) }
            call.respond(HttpStatusCode.OK)
        }

        post("/api/read") {
            val id = call.querySourceId() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val manga = call.queryParam("manga") ?: return@post call.respond(HttpStatusCode.BadRequest)
            val chapter = call.queryParam("chapter") ?: return@post call.respond(HttpStatusCode.BadRequest)
            val read = call.queryParam("read")?.toBoolean() ?: true
            withContext(Dispatchers.IO) { ReadStore.setRead(id, manga, chapter, read) }
            call.respond(HttpStatusCode.OK)
        }

        post("/api/bookmarks") {
            val id = call.querySourceId() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val manga = call.queryParam("manga") ?: return@post call.respond(HttpStatusCode.BadRequest)
            val chapter = call.queryParam("chapter") ?: return@post call.respond(HttpStatusCode.BadRequest)
            val on = call.queryParam("on")?.toBoolean() ?: true
            withContext(Dispatchers.IO) { BookmarkStore.setBookmarked(id, manga, chapter, on) }
            call.respond(HttpStatusCode.OK)
        }

        get("/api/history") {
            val items = withContext(Dispatchers.IO) {
                // Backfill a missing cover from the library so Continue-reading cards keep their art
                // even for older entries (recorded before covers were stored / from sources that
                // omit the cover in details).
                val lib = LibraryStore.list().associateBy { it.sourceId to it.mangaUrl }
                HistoryStore.list().map {
                    val thumb = it.thumbnailUrl?.takeIf { t -> t.isNotBlank() } ?: lib[it.sourceId to it.mangaUrl]?.thumbnailUrl
                    HistoryDto(it.sourceId.toString(), it.mangaUrl, it.mangaTitle, thumb, it.chapterUrl, it.chapterName, it.readAt)
                }
            }
            call.respond(items)
        }

        post("/api/history") {
            val id = call.querySourceId() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val manga = call.queryParam("manga") ?: return@post call.respond(HttpStatusCode.BadRequest)
            val chapter = call.queryParam("chapter") ?: return@post call.respond(HttpStatusCode.BadRequest)
            val mangaTitle = call.queryParam("title") ?: manga
            val name = call.queryParam("name") ?: ""
            // Bake the cover into the history entry so it survives later library removal — prefer the
            // client-supplied thumb, else fall back to the library entry's cover (recorded while it's
            // still in the library).
            val thumb = withContext(Dispatchers.IO) {
                call.queryParam("thumb")?.takeIf { it.isNotBlank() } ?: LibraryStore.find(id, manga)?.thumbnailUrl
            }
            withContext(Dispatchers.IO) { HistoryStore.record(id, manga, mangaTitle, chapter, name, thumb) }
            call.respond(HttpStatusCode.OK)
        }
        delete("/api/history") {
            val id = call.querySourceId() ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val manga = call.queryParam("manga") ?: return@delete call.respond(HttpStatusCode.BadRequest)
            withContext(Dispatchers.IO) { HistoryStore.remove(id, manga) }
            call.respond(HttpStatusCode.OK)
        }
        post("/api/history/clear") {
            withContext(Dispatchers.IO) { HistoryStore.clear() }
            call.respond(HttpStatusCode.OK)
        }

        // ---- Settings + diagnostics ----
        get("/api/settings") {
            val s = SettingsStore.get()
            call.respond(SettingsDto(s.downloadDir, AppConfig.downloadsDir.toString(), AppConfig.dataDir.toString(), s.downloadAsCbz, s.downloadConcurrency, s.parallelDownloads, s.perSourceParallel, s.visibleLanguages, CLOUDFLARE_BYPASS))
        }
        post("/api/settings") {
            val body = call.receive<SettingsPatch>()
            var s = SettingsStore.get()
            if (body.downloadDir != null) {
                val dir = body.downloadDir.trim()
                if (dir.isEmpty()) {
                    s = s.copy(downloadDir = null)
                } else {
                    val p = runCatching { java.nio.file.Path.of(dir).toAbsolutePath().normalize() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("That doesn't look like a valid path"))
                    val ok = withContext(Dispatchers.IO) { runCatching { java.nio.file.Files.createDirectories(p) }.isSuccess }
                    if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("Couldn't create that directory"))
                    s = s.copy(downloadDir = p.toString())
                }
            }
            body.downloadAsCbz?.let { s = s.copy(downloadAsCbz = it) }
            body.downloadConcurrency?.let { s = s.copy(downloadConcurrency = it.coerceIn(1, 32)) }
            body.parallelDownloads?.let { s = s.copy(parallelDownloads = it.coerceIn(1, 8)) }
            body.perSourceParallel?.let { s = s.copy(perSourceParallel = it) }
            body.visibleLanguages?.let { s = s.copy(visibleLanguages = it.map { l -> l.lowercase() }.distinct()) }
            withContext(Dispatchers.IO) { SettingsStore.save(s) }
            AppConfig.downloadDirOverride = s.downloadDir?.takeIf { it.isNotBlank() }?.let { java.nio.file.Path.of(it) }
            call.respond(SettingsDto(s.downloadDir, AppConfig.downloadsDir.toString(), AppConfig.dataDir.toString(), s.downloadAsCbz, s.downloadConcurrency, s.parallelDownloads, s.perSourceParallel, s.visibleLanguages, CLOUDFLARE_BYPASS))
        }
        get("/api/diag") {
            val id = call.querySourceId() ?: return@get call.respond(HttpStatusCode.BadRequest)
            val r = withContext(Dispatchers.IO) { Diagnostics.run(id) }
            call.respond(DiagDto(r.source, r.baseUrl, r.pingMs, r.speedMbps, r.sampleBytes, r.ok, r.error))
        }
        get("/api/dev/stats") { call.respond(devStats()) }

        // ---- Extensions + repositories ----
        get("/api/extensions") {
            val list = withContext(Dispatchers.IO) {
                val repoOf = runCatching { availableEntries().associate { it.first.pkg to repoLabel(it.second) } }.getOrDefault(emptyMap())
                InstalledStore.list().map { ExtDto(it.pkg, it.name, it.versionName, it.lang, it.nsfw, it.sources.size, repoOf[it.pkg] ?: "") }
            }
            call.respond(list)
        }
        post("/api/extensions/check-updates") {
            val pkgs = withContext(Dispatchers.IO) { runCatching { ExtensionUpdates.check().map { it.installed.pkg } }.getOrDefault(emptyList()) }
            call.respond(pkgs)
        }
        post("/api/extensions/install") {
            val pkg = call.receive<InstallReq>().pkg
            availCache = null // a fresh install/update should re-read repos next browse
            val r = withContext(Dispatchers.IO) { runCatching { ExtensionInstaller().install(pkg) } }
            r.fold(
                onSuccess = { call.respond(InstallResultDto(it.pkg, it.name, it.sources.size)) },
                onFailure = { call.respond(HttpStatusCode.BadGateway, ErrorDto(it.message ?: "Install failed")) },
            )
        }
        delete("/api/extensions") {
            val pkg = call.queryParam("pkg") ?: return@delete call.respond(HttpStatusCode.BadRequest)
            withContext(Dispatchers.IO) {
                InstalledStore.remove(pkg)
                runCatching {
                    java.nio.file.Files.deleteIfExists(AppConfig.extensionsDir.resolve("$pkg.jar"))
                    java.nio.file.Files.deleteIfExists(AppConfig.extensionsDir.resolve("$pkg.apk"))
                }
            }
            call.respond(HttpStatusCode.OK)
        }
        get("/api/extensions/available") {
            val q = call.queryParam("q")?.trim()?.lowercase().orEmpty()
            val visible = SettingsStore.get().visibleLanguages
            val result = withContext(Dispatchers.IO) {
                val installed = InstalledStore.list().associateBy { it.pkg }
                availableEntries()
                    .filter { langVisible(it.first.lang, visible) }
                    .filter { q.isEmpty() || it.first.name.lowercase().contains(q) }
                    .sortedBy { it.first.name.lowercase() }
                    .take(300)
                    .map { (e, repo) ->
                        val inst = installed[e.pkg]
                        AvailDto(e.pkg, e.name, e.version, e.lang, e.isNsfw, inst != null, inst != null && e.code > inst.versionCode, repoLabel(repo))
                    }
            }
            call.respond(result)
        }

        get("/api/repos") { call.respond(RepoStore.list()) }
        post("/api/repos") {
            val url = call.receive<RepoReq>().url.trim()
            if (url.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("Enter a repo URL"))
            withContext(Dispatchers.IO) { RepoStore.add(url) }
            availCache = null
            call.respond(RepoStore.list())
        }
        delete("/api/repos") {
            val url = call.queryParam("url") ?: return@delete call.respond(HttpStatusCode.BadRequest)
            withContext(Dispatchers.IO) { RepoStore.remove(url) }
            availCache = null
            call.respond(RepoStore.list())
        }

        // ---- Chapter pages ----
        get("/api/chapter/pages") {
            val id = call.querySourceId() ?: return@get call.respond(HttpStatusCode.BadRequest, "bad source id")
            val chapter = call.queryParam("chapter") ?: return@get call.respond(HttpStatusCode.BadRequest, "missing chapter")
            val title = call.queryParam("title")
            val name = call.queryParam("name")
            val count = withContext(Dispatchers.IO) {
                // Offline-first: a local download knows its own page count without the network.
                val local = if (title != null && name != null) runCatching { LocalChapterReader.localChapter(title, name) }.getOrNull() else null
                local?.count ?: pagesFor(id, chapter).size
            }
            call.respond(PagesDto(count))
        }

        // Real extension icon (from the repo), keyed by source id.
        get("/img/source-icon") {
            val id = call.querySourceId() ?: return@get call.respond(HttpStatusCode.BadRequest)
            val pkg = withContext(Dispatchers.IO) { InstalledStore.findExtensionForSource(id)?.pkg }
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val bytes = withContext(Dispatchers.IO) { ExtensionIcons.iconBytes(pkg) }
            if (bytes == null) call.respond(HttpStatusCode.NotFound) else {
                call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=604800, immutable")
                call.respondBytes(bytes, sniffImageType(bytes))
            }
        }

        // Extension icon by package (for the extensions manager).
        get("/img/ext-icon") {
            val pkg = call.queryParam("pkg") ?: return@get call.respond(HttpStatusCode.BadRequest)
            val bytes = withContext(Dispatchers.IO) { ExtensionIcons.iconBytes(pkg) }
            if (bytes == null) call.respond(HttpStatusCode.NotFound) else {
                call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=604800, immutable")
                call.respondBytes(bytes, sniffImageType(bytes))
            }
        }

        // ---- Images (binary) ----
        get("/img/cover") {
            val id = call.querySourceId() ?: return@get call.respond(HttpStatusCode.BadRequest)
            val url = call.queryParam("url") ?: return@get call.respond(HttpStatusCode.BadRequest)
            val title = call.queryParam("title")
            val bytes = coverCache[url] ?: withContext(Dispatchers.IO) {
                // Offline-first: a downloaded series' cover on disk wins over the source.
                val raw = title?.let { t -> DownloadManager.localCover(t)?.let { p -> runCatching { java.nio.file.Files.readAllBytes(p) }.getOrNull() } }
                    ?: SourceImage.coverBytes(id, url)
                raw?.let { CoverImage.thumbnail(it) }?.also { coverCache[url] = it }
            }
            if (bytes == null) call.respond(HttpStatusCode.NotFound) else {
                call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=86400")
                call.respondBytes(bytes, sniffImageType(bytes))
            }
        }

        get("/img/page") {
            val id = call.querySourceId() ?: return@get call.respond(HttpStatusCode.BadRequest)
            val chapter = call.queryParam("chapter") ?: return@get call.respond(HttpStatusCode.BadRequest)
            val index = call.queryParam("index")?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
            val title = call.queryParam("title")
            val name = call.queryParam("name")
            val bytes = withContext(Dispatchers.IO) {
                val local = if (title != null && name != null) runCatching { LocalChapterReader.localChapter(title, name) }.getOrNull() else null
                if (local != null) {
                    local.bytes(index)
                } else {
                    pagesFor(id, chapter).getOrNull(index)?.let { SourceImage.pageBytes(id, it) }
                }
            }
            if (bytes == null) call.respond(HttpStatusCode.NotFound) else {
                call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=604800, immutable")
                call.respondBytes(bytes, sniffImageType(bytes))
            }
        }

        // ---- Static frontend (built React SPA): serves real files, falls back to index.html ----
        singlePageApplication {
            useResources = true
            filesPath = "web"
            defaultPage = "index.html"
        }
    }
}

// ---- route helpers --------------------------------------------------------------------------

private fun io.ktor.server.application.ApplicationCall.queryParam(name: String): String? = request.queryParameters[name]

private fun io.ktor.server.application.ApplicationCall.sourceId(): Long? = parameters["id"]?.toLongOrNull()

private fun io.ktor.server.application.ApplicationCall.querySourceId(): Long? = request.queryParameters["source"]?.toLongOrNull()

private suspend fun browse(
    call: io.ktor.server.application.ApplicationCall,
    op: (Long, Int) -> eu.kanade.tachiyomi.source.model.MangasPage,
) {
    val id = call.sourceId() ?: return call.respond(HttpStatusCode.BadRequest, "bad source id")
    val page = call.queryParam("page")?.toIntOrNull() ?: 1
    val result = withContext(Dispatchers.IO) {
        runCatching {
            val mp = op(id, page)
            PageResultDto(mp.mangas.map { it.toDto(id) }, mp.hasNextPage)
        }
    }
    result.fold(
        onSuccess = { SourceHealth.markUp(id); call.respond(it) },
        onFailure = { markFailure(id, it); call.respond(HttpStatusCode.BadGateway, ErrorDto(sourceErrorMessage(it))) },
    )
}

/** A human-readable reason a source call failed — the engine already gives good messages
 *  (e.g. "Cloudflare protection is blocking this source (HTTP 403)…", "HTTP error 522"). */
private fun sourceErrorMessage(e: Throwable): String =
    e.message?.takeIf { it.isNotBlank() } ?: (e::class.simpleName ?: "The source is unavailable")

/** Record a failure: Cloudflare blocks stay "up but blocked"; anything else marks the source down. */
private fun markFailure(sourceId: Long, e: Throwable) {
    if (e.message?.contains("Cloudflare", ignoreCase = true) == true) {
        CloudflareState.mark(sourceId)
        SourceHealth.markUp(sourceId)
    } else {
        SourceHealth.markDown(sourceId)
    }
}

/** green = no Cloudflare seen; red = behind Cloudflare, no bypass; orange = behind CF + bypass. */
private fun cfState(sourceId: Long): String =
    if (!CloudflareState.isBlocked(sourceId)) "green" else if (CLOUDFLARE_BYPASS) "orange" else "red"

/** A source's language is visible if no filter is set, it's language-agnostic, or it's selected. */
private fun langVisible(lang: String, visible: List<String>): Boolean =
    visible.isEmpty() || lang.isBlank() || lang.equals("all", true) || visible.any { it.equals(lang, true) }
