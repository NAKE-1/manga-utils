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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
import io.ktor.server.request.receiveStream
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
import mangautils.core.download.ChapterIdentity
import mangautils.core.config.SettingsStore
import mangautils.core.download.DownloadManager
import mangautils.core.download.DownloadStore
import mangautils.core.download.UnavailableChapters
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
import mangautils.core.library.PositionStore
import mangautils.core.library.ReadStore
import mangautils.core.source.CloudflareState
import mangautils.core.source.Diagnostics
import mangautils.core.source.SourceCircuits
import mangautils.core.source.SourceHealth
import mangautils.core.source.LocalChapterReader
import mangautils.core.source.SourceBrowser
import mangautils.core.source.SourceImage
import mangautils.core.source.SourceManager
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("mangautils.server")

/** Whether a Cloudflare bypass (FlareSolverr) is currently enabled — drives the source-health color. */
private fun cloudflareBypassOn(): Boolean = eu.kanade.tachiyomi.network.interceptor.FlareSolverrConfig.enabled

/** Push the app's FlareSolverr settings into the network layer's live config holder. */
private fun applyFlareSolverr(s: mangautils.core.config.Settings) {
    val c = eu.kanade.tachiyomi.network.interceptor.FlareSolverrConfig
    c.enabled = s.flareSolverrEnabled
    c.url = s.flareSolverrUrl
    c.session = s.flareSolverrSession
    c.sessionTtlMinutes = s.flareSolverrSessionTtlMinutes
    c.timeoutMs = s.flareSolverrTimeoutMs.toLong()
    // MU_FLARESOLVERR_URL always wins — set it in compose (http://flaresolverr:8191) and the app is
    // wired to the sibling container no matter what's in Settings or a restored backup. Also flips
    // the bypass on when a URL is provided, so Docker "just works" with zero first-run config.
    System.getenv("MU_FLARESOLVERR_URL")?.trim()?.takeIf { it.isNotBlank() }?.let { c.url = it; c.enabled = true }
}

// Common places a FlareSolverr lives — probed in order when auto-discovering. The docker-internal
// names (flaresolverr, host.docker.internal, 172.17.0.1) are only reachable from the SERVER, which
// is why discovery runs server-side, not in the browser.
private val FLARE_DEFAULTS = listOf(
    "http://localhost:8191",
    "http://127.0.0.1:8191",
    "http://flaresolverr:8191",
    "http://host.docker.internal:8191",
    "http://172.17.0.1:8191",
)

/** Probe one FlareSolverr endpoint (short timeouts so a dead candidate fails fast). */
private fun probeFlareOne(base: String): FlareTestDto = runCatching {
    val u = base.trim().trimEnd('/')
    val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    val body = """{"cmd":"sessions.list"}""".toRequestBody("application/json".toMediaType())
    client.newCall(okhttp3.Request.Builder().url("$u/v1").post(body).build()).execute().use { resp ->
        val txt = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) error("HTTP ${resp.code}")
        val ok = txt.contains("\"status\"") && txt.contains("ok")
        val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(txt)?.groupValues?.get(1)
        FlareTestDto(ok, version, if (ok) null else "Unexpected response from FlareSolverr", if (ok) u else null)
    }
}.getOrElse { FlareTestDto(false, null, it.message ?: "Couldn't reach FlareSolverr") }

/** Test [explicit] if given; otherwise auto-discover: probe the saved URL then the common defaults,
 *  first ok wins. Returns the working url in [FlareTestDto.url] so the caller can save it. */
private fun discoverFlare(explicit: String?): FlareTestDto {
    if (!explicit.isNullOrBlank()) return probeFlareOne(explicit)
    val candidates = buildList {
        val saved = eu.kanade.tachiyomi.network.interceptor.FlareSolverrConfig.url.trim()
        if (saved.isNotBlank()) add(saved)
        addAll(FLARE_DEFAULTS)
    }.distinct()
    var last = FlareTestDto(false, null, "No FlareSolverr found on the usual endpoints")
    for (base in candidates) {
        val r = probeFlareOne(base)
        if (r.ok) return r
        last = r
    }
    return last
}

/** First-ever boot only: if the bypass isn't configured, auto-discover a running FlareSolverr and
 *  wire it up, so a fresh install / container "just works" when a solver is on a default endpoint.
 *  Marked with a file so it never re-probes on later boots (respecting the user's later choices). */
private fun autoDetectFlareOnce() {
    runCatching {
        val marker = mangautils.core.config.AppConfig.dataDir.resolve(".flare-probed")
        if (java.nio.file.Files.exists(marker)) return
        val s = SettingsStore.get()
        if (s.flareSolverrEnabled || !System.getenv("MU_FLARESOLVERR_URL").isNullOrBlank()) {
            java.nio.file.Files.writeString(marker, "1"); return // already set up / env-driven
        }
        java.nio.file.Files.writeString(marker, "1") // once ever, found or not
        val found = discoverFlare(null)
        if (found.ok && found.url != null) {
            SettingsStore.save(s.copy(flareSolverrUrl = found.url, flareSolverrEnabled = true))
            applyFlareSolverr(SettingsStore.get())
            log.info("FlareSolverr auto-detected at {} (first boot) - Cloudflare bypass enabled", found.url)
        }
    }
}

// ---- DTOs (IDs are Strings to survive JS number precision) ----------------------------------

@Serializable
private data class SourceDto(val id: String, val name: String, val lang: String, val nsfw: Boolean, val cfState: String, val down: Boolean, val imagesDown: Boolean, val usesWebView: Boolean)

@Serializable private data class TechDto(val role: String, val tech: String)
@Serializable private data class ChangeDto(val sha: String, val date: String, val subject: String)
@Serializable private data class VersionDto(
    val version: String, val commit: String, val buildTime: String,
    val tech: List<TechDto>, val changelog: List<ChangeDto>,
)

// The app's technical stack, shown on the About screen (our version of Atsumaru's "Technical details").
private val TECH_STACK = listOf(
    TechDto("Language / engine", "Kotlin (JVM)"),
    TechDto("Backend / server", "Ktor (Netty)"),
    TechDto("Manga sources", "Tachiyomi/Mihon extensions + OkHttp"),
    TechDto("Frontend UI", "React + TypeScript"),
    TechDto("Frontend bundler", "Vite (Rollup internally)"),
    TechDto("Styling", "Hand-rolled CSS"),
    TechDto("Routing", "React Router"),
    TechDto("Data store", "Flat JSON files on disk (no DB)"),
    TechDto("Downloads", "Folders / CBZ on disk"),
    TechDto("Remote access", "Tailscale (tailnet-only, no auth)"),
    TechDto("Build", "Gradle"),
)

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
    /** Why the source can't serve this chapter, when it can't. Null means it's fine. */
    val unavailable: String? = null,
)

@Serializable
private data class DetailDto(val manga: MangaDto, val chapters: List<ChapterDto>, val newChapters: List<String> = emptyList(), val newVersions: List<String> = emptyList())

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

@Serializable private data class UpdatedTitleDto(val title: String, val count: Int)
@Serializable private data class UpdateSummaryDto(val newChapters: Int, val updatedManga: Int, val titles: List<UpdatedTitleDto> = emptyList())
@Serializable private data class UpdateProgressDto(val done: Int, val total: Int, val running: Boolean)
@Serializable private data class SimulateDto(val title: String, val newChapters: Int, val autoDownloaded: Boolean)

// Mass-download: a per-series preview (downloaded/total + how many would queue) and the grand total.
@Serializable private data class MassPlanItemDto(
    val sourceId: String,
    val mangaUrl: String,
    val title: String,
    val source: String,
    val total: Int,
    val downloaded: Int,
    val missing: Int,
)
@Serializable private data class MassPlanDto(val items: List<MassPlanItemDto>, val totalMissing: Int, val seriesWithMissing: Int)
@Serializable private data class MassStartItem(val sourceId: String, val mangaUrl: String)
@Serializable private data class MassStartReq(val items: List<MassStartItem>)

// Reading stats page.
@Serializable private data class StatSeriesDto(val title: String, val count: Int, val sourceId: String = "", val mangaUrl: String = "", val thumbnailUrl: String? = null)
@Serializable private data class StatRecentDto(val title: String, val chapter: String, val readAt: Long, val sourceId: String, val mangaUrl: String, val thumbnailUrl: String? = null)
@Serializable private data class StatsDto(
    val chaptersRead: Int,
    val seriesInLibrary: Int,
    val readThisWeek: Int,
    val topSeries: List<StatSeriesDto>,
    val recent: List<StatRecentDto>,
)

// Broken-download detection.
@Serializable private data class BrokenSeriesDto(val title: String, val broken: List<String>, val total: Int)
@Serializable private data class BrokenReportDto(val series: List<BrokenSeriesDto>, val totalBroken: Int)

// In-app error log.
@Serializable private data class LogDto(val ts: Long, val level: String, val logger: String, val msg: String)

// Source health dashboard.
@Serializable private data class HealthSourceDto(
    val id: String,
    val name: String,
    val lang: String,
    val cfState: String,
    val down: Boolean,
    val imagesDown: Boolean,
    val usesWebView: Boolean,
    val circuitApiOpen: Boolean,
    val circuitImagesOpen: Boolean,
    val lastOkMs: Long,
    val lastFailMs: Long,
    val lastPingMs: Long,
)
@Serializable private data class HealthReportDto(val sources: List<HealthSourceDto>, val healthy: Int, val degraded: Int, val down: Int)
@Serializable private data class SweepProgressDto(val done: Int, val total: Int, val running: Boolean)

// Discord webhook tester.
@Serializable private data class WebhookResultDto(val ok: Boolean, val status: Int, val rateLimited: Boolean, val retryAfter: Double? = null, val error: String? = null)
@Serializable private data class WebhookSampleReq(val source: String = "", val mangaUrl: String = "", val kind: String = "newchapters")
@Serializable private data class NotifyStatusDto(val rateLimitedAtMs: Long, val retryAfter: Double)

// Source migration.
@Serializable private data class MigrateSideDto(val sourceName: String, val title: String, val cover: String? = null, val total: Int, val readCount: Int, val readUpTo: String, val bookmarks: Int, val downloaded: Int)
@Serializable private data class MigratePreviewDto(
    val from: MigrateSideDto,
    val to: MigrateSideDto,
    val willCarryRead: Int,
    val unmatchedRead: Int,
    val willCarryBookmarks: Int,
    val unmatchedBookmarks: Int,
    val chapterDiff: Int,
    val unnumbered: Int,
)
@Serializable private data class MigrateReq(val fromSource: String, val fromUrl: String, val toSource: String, val toUrl: String, val deleteOldDownloads: Boolean = false, val reDownload: Boolean = false)
@Serializable private data class MigrateProgressDto(val running: Boolean, val finished: Boolean, val phase: String, val error: String, val steps: List<String>)

private fun migFmt(n: Float) = if (n < 0) "—" else if (n == n.toInt().toFloat()) n.toInt().toString() else n.toString()
private fun sourceDisplayName(id: Long) = runCatching { mangautils.core.source.SourceManager.loadSource(id)?.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: id.toString()

// Live progress of a running library-update scan, for the "Check updates" percentage.
@Volatile private var libUpdateDone = 0
@Volatile private var libUpdateTotal = 0
@Volatile private var libUpdateRunning = false

@Serializable private data class ExtDto(val pkg: String, val name: String, val version: String, val lang: String, val nsfw: Boolean, val sources: Int, val repo: String = "", val usesWebView: Boolean = false)

@Serializable private data class AvailDto(val pkg: String, val name: String, val version: String, val lang: String, val nsfw: Boolean, val installed: Boolean, val hasUpdate: Boolean, val repo: String = "")
@Serializable private data class InstallReq(val pkg: String)
@Serializable private data class IdentSrcDto(val url: String, val name: String, val scanlator: String? = null, val number: Float = -1f)
@Serializable private data class IdentDiskDto(
    val folder: String, val url: String? = null, val scanlator: String? = null, val resolvedScanlator: String? = null,
    val number: Float? = null, val pageCount: Int = 0, val complete: Boolean = true, val unidentified: Boolean = false,
)
@Serializable private data class IdentityDto(
    val title: String, val onDisk: Int, val identified: Int, val sourceVersions: Int, val sourceNumbers: Int,
    val missing: Int, val chapters: List<IdentDiskDto>, val source: List<IdentSrcDto>,
)

@Serializable private data class RepoReq(val url: String)

/** A repo plus what it offers: [extensions] packages advertising [sources] sources between them. */
@Serializable private data class RepoStatDto(val url: String, val extensions: Int, val sources: Int)
@Serializable private data class InstallResultDto(val pkg: String, val name: String, val sources: Int)

@Serializable private data class DlChapterReq(val url: String, val name: String = "")
@Serializable private data class DownloadReq(val source: String, val manga: String, val title: String = "", val chapters: List<DlChapterReq> = emptyList())
@Serializable private data class DlTaskDto(
    val id: String, val mangaKey: String, val mangaTitle: String, val state: String,
    val total: Int, val done: Int, val failed: Int,
    val currentChapter: String, val currentChapterUrl: String, val pagesDone: Int, val pagesTotal: Int,
    val kbps: Double, val error: String, val failedChapters: List<DlChapterReq>, val tag: String = "",
    /** What the failures mean, for the card colour: "" | "transient" | "alternative" | "gone". */
    val failClass: String = "", val autoRetries: Int = 0,
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
    val autoUpdate: Boolean,
    val autoUpdateHours: Int,
    val autoUpdateHour: Int,
    val autoDownloadNew: Boolean,
    val healthCheckEnabled: Boolean,
    val healthCheckHour: Int,
    val flareSolverrEnabled: Boolean,
    val flareSolverrUrl: String,
    val flareSolverrSession: String,
    val flareSolverrSessionTtlMinutes: Int,
    val flareSolverrTimeoutMs: Int,
    val usbBackupDir: String,
    val discordWebhookUrl: String,
    val notify: mangautils.core.config.NotifyConfig,
)

@Serializable
private data class RelocatePlanReq(val root: String)

@Serializable
private data class RelocateStartReq(val root: String, val mode: String)

@Serializable
private data class RelocatePreviewDto(
    val sourceBytes: Long,
    val sourceFiles: Long,
    val targetFreeBytes: Long,
    val targetLayout: String,
    val activeDownloads: Int,
    val fits: Boolean,
    val warning: String,
)

@Serializable
private data class RelocateProgressDto(
    val running: Boolean,
    val phase: String,
    val finished: Boolean,
    val error: String,
    val mode: String,
    val target: String,
    val filesTotal: Long,
    val filesDone: Long,
    val bytesTotal: Long,
    val bytesDone: Long,
    val steps: List<String>,
)

@Serializable
private data class SettingsPatch(
    val downloadDir: String? = null,
    val downloadAsCbz: Boolean? = null,
    val downloadConcurrency: Int? = null,
    val parallelDownloads: Int? = null,
    val perSourceParallel: Boolean? = null,
    val visibleLanguages: List<String>? = null,
    val autoUpdate: Boolean? = null,
    val autoUpdateHours: Int? = null,
    val autoUpdateHour: Int? = null,
    val autoDownloadNew: Boolean? = null,
    val healthCheckEnabled: Boolean? = null,
    val healthCheckHour: Int? = null,
    val flareSolverrEnabled: Boolean? = null,
    val flareSolverrUrl: String? = null,
    val flareSolverrSession: String? = null,
    val flareSolverrSessionTtlMinutes: Int? = null,
    val flareSolverrTimeoutMs: Int? = null,
    val usbBackupDir: String? = null,
    val discordWebhookUrl: String? = null,
    val notify: mangautils.core.config.NotifyConfig? = null,
)

@Serializable
private data class SourcePrefDto(
    val index: Int, val key: String?, val title: String, val summary: String?, val type: String,
    val value: String, val entries: List<String>?, val entryValues: List<String>?, val enabled: Boolean,
)

@Serializable
private data class SetPrefReq(val index: Int, val value: String)

private fun mangautils.core.source.SourcePref.toDto() =
    SourcePrefDto(index, key, title, summary, type, value, entries, entryValues, enabled)

@Serializable
private data class FlareTestDto(val ok: Boolean, val version: String? = null, val error: String? = null, val url: String? = null)

@Serializable
private data class FlareEventDto(val id: Long, val host: String, val phase: String, val cookies: Int)

@Serializable
private data class FlareEventsDto(val lastId: Long, val events: List<FlareEventDto>)

@Serializable
private data class BackupResultDto(val imported: Int, val skipped: Int, val total: Int, val settingsRestored: Boolean = false, val reposAdded: Int = 0, val extensionsInstalled: Int = 0, val extensionsFailed: Int = 0, val historyRestored: Int = 0, val clientPrefsJson: String? = null)

@Serializable
private data class ImportJobDto(val state: String, val phase: String, val done: Int, val total: Int, val current: String, val error: String = "", val result: BackupResultDto? = null)

@Serializable
private data class ExportReqDto(val include: List<String> = emptyList(), val clientPrefs: String? = null)

private fun importJobDto(t: ImportJob.Task) = ImportJobDto(
    t.state, t.phase, t.done, t.total, t.current, t.error,
    t.result?.let { BackupResultDto(it.imported, it.skipped, it.total, it.settingsRestored, it.reposAdded, it.extensionsInstalled, it.extensionsFailed, it.historyRestored, it.clientPrefsJson) },
)

@Serializable
private data class BackupPreviewItemDto(val title: String, val source: String, val chapters: Int, val read: Int, val inLibrary: Boolean)

@Serializable
private data class BackupPreviewDto(val total: Int, val manga: List<BackupPreviewItemDto>, val hasSettings: Boolean = false, val repos: Int = 0, val extensions: Int = 0)

@Serializable
private data class BackupJobDto(
    val running: Boolean, val state: String, val phase: String,
    val filesDone: Int, val filesTotal: Int, val bytesCopied: Long,
    val blobName: String, val filesSkipped: Int, val error: String, val target: String,
)

/** Where the "Back up to USB" action writes: Settings.usbBackupDir → MU_DYNO_DIR env → /dyno default. */
private fun dynoTarget(): java.nio.file.Path {
    val configured = SettingsStore.get().usbBackupDir.ifBlank { System.getenv("MU_DYNO_DIR").orEmpty() }
    return java.nio.file.Path.of(configured.ifBlank { "/dyno" })
}

private fun backupJobDto(t: BackupJob.Task?) =
    if (t == null) BackupJobDto(false, "idle", "", 0, 0, 0, "", 0, "", dynoTarget().toString())
    else BackupJobDto(t.active, t.state, t.phase, t.filesDone, t.filesTotal, t.bytesCopied, t.blobName, t.filesSkipped, t.error, t.target)

private fun settingsDto(s: mangautils.core.config.Settings) = SettingsDto(
    s.downloadDir, AppConfig.downloadsDir.toString(), AppConfig.dataDir.toString(),
    s.downloadAsCbz, s.downloadConcurrency, s.parallelDownloads, s.perSourceParallel,
    s.visibleLanguages, s.flareSolverrEnabled, s.autoUpdate, s.autoUpdateHours, s.autoUpdateHour, s.autoDownloadNew,
    s.healthCheckEnabled, s.healthCheckHour,
    s.flareSolverrEnabled, s.flareSolverrUrl, s.flareSolverrSession, s.flareSolverrSessionTtlMinutes, s.flareSolverrTimeoutMs,
    s.usbBackupDir, s.discordWebhookUrl, s.notify,
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
@Serializable private data class ResolveDto(val sourceId: String, val mangaUrl: String, val title: String, val cover: String? = null)

@Serializable
private data class MangaStateDto(
    val inLibrary: Boolean,
    val bookmarked: Boolean,
    val read: List<String>,
    val bookmarks: List<String>,
    /** chapterUrl -> how far through you got (0..1), so any device resumes where the last one stopped. */
    val positions: Map<String, Float> = emptyMap(),
)

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

// Short-TTL cache for browse pages (popular/latest/search) so re-opening a source or hitting Back
// doesn't re-pay the source's latency (Madara/WordPress sources are ~1s per page).
private const val BROWSE_TTL_MS = 300_000L
private val browseCache = ConcurrentHashMap<String, Pair<Long, PageResultDto>>()

// Cache thumbnailed cover bytes (per cover url) so the grid isn't re-fetching + re-resizing.
private val coverCache = ConcurrentHashMap<String, ByteArray>()

/**
 * The source sometimes lists the same chapter twice under one group name — e.g. two distinct "Gamma 3"
 * uploads of ch 82. We keep both (they may differ), but two identical "Gamma 3" rows are confusing, so
 * the duplicates are numbered Windows-style: the first stays "Gamma 3", the next become "Gamma 3 (2)",
 * "Gamma 3 (3)". Display only — the chapter URL, the on-disk folder, and ComicInfo are all untouched.
 */
private fun disambiguateScanlators(chapters: List<ChapterDto>): List<ChapterDto> {
    val seen = HashMap<String, Int>()
    return chapters.map { c ->
        val scan = c.scanlator?.takeIf { it.isNotBlank() } ?: return@map c
        val n = (seen["${c.number} $scan"]?.plus(1) ?: 1).also { seen["${c.number} $scan"] = it }
        if (n == 1) c else c.copy(scanlator = "$scan ($n)")
    }
}

/** Build details from a cached library entry — instant + offline (mirrors the desktop). */
private fun cachedDetail(e: LibraryEntry): DetailDto {
    // Looked up once for the whole list, not per chapter.
    val unavailableBy = runCatching { UnavailableChapters.list().associate { u -> u.url to u.reason } }.getOrDefault(emptyMap())
    return DetailDto(
    MangaDto(e.sourceId.toString(), e.mangaUrl, e.title, e.thumbnailUrl, e.author, e.artist, e.description, e.genre, e.status),
    disambiguateScanlators(e.knownChapters.map {
        // Per *version*, not per name: several scanlations share a chapter name, so a name check marks
        // all of them downloaded as soon as any one is, which greys out the button for the very
        // alternates you're trying to fetch. Matches legacy name-only folders too, via their ComicInfo.
        ChapterDto(
            it.url, it.name, it.scanlator, it.dateUpload, it.number,
            runCatching { ChapterIdentity.hasVersion(e.title, it.url) }.getOrDefault(false),
            unavailableBy[it.url],
        )
    }),
        e.newChapters.toList(),
        e.newVersions.toList(),
    )
}

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
            it.bytesPerSec / 1024.0, it.error, it.failed.map { c -> DlChapterReq(c.url, c.name) }, it.tag,
            it.failClass, it.autoRetries,
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

/**
 * Announce the server on Discord once it's bound, including whether the Cloudflare bypass answered,
 * and arm the crash notifier. Runs off-thread so a slow probe can't delay startup, and every failure
 * is swallowed — a webhook problem must never take the server down.
 */
private fun announceOnline(port: Int) {
    Thread({
        runCatching {
            val cfg = eu.kanade.tachiyomi.network.interceptor.FlareSolverrConfig
            val flare: Pair<Boolean, String?>? =
                if (!cfg.enabled) {
                    null
                } else {
                    val probe = probeFlareOne(cfg.url)
                    // Seed the reachability state so the first real solve doesn't re-announce what we
                    // already reported here.
                    cfg.reportReachable(probe.ok, probe.error)
                    probe.ok to probe.error
                }
            // Only fires on a change, so a crash mid-session reports once, not once per request.
            cfg.onReachabilityChange = { ok, err -> Notifier.onServiceTransition("FlareSolverr", down = !ok, detail = err) }
            Notifier.onServerOnline(port, flare)
        }
    }, "startup-announce").apply { isDaemon = true }.start()
}

/**
 * ASCII startup banner printed once the server is live. Pure ASCII on purpose — the Windows console
 * isn't UTF-8, so box-drawing/Unicode glyphs mojibake (same reason the log separators are ASCII).
 * Printed via println (not the logger) so the art isn't prefixed with a timestamp on every line.
 */
private fun printStartupBanner(port: Int) {
    // "manga-utils" in the figlet "slant" font. Raw string so the backslashes stay literal; ASCII-only
    // (Windows console isn't UTF-8 — box/block glyphs mojibake, hence no Unicode here).
    val logo =
        """
                                                  __  _ __
   ____ ___  ____ _____  ____ _____ _      __  __/ /_(_) /____
  / __ `__ \/ __ `/ __ \/ __ `/ __ `/_____/ / / / __/ / / ___/
 / / / / / / /_/ / / / / /_/ / /_/ /_____/ /_/ / /_/ / (__  )
/_/ /_/ /_/\__,_/_/ /_/\__, /\__,_/      \__,_/\__/_/_/____/
                      /____/
"""
    val bar = "=".repeat(64)
    val sb = StringBuilder("\n\n")
    logo.trim('\n').lines().forEach { sb.append("   ").append(it).append('\n') }
    sb.append('\n')
    sb.append("   ").append(bar).append('\n')
    sb.append("     [*]  SERVER ONLINE   .   phone-first manga reader\n")
    sb.append("     [>]  http://0.0.0.0:$port      (or http://<tailscale-ip>:$port)\n")
    sb.append("   ").append(bar).append("\n\n")
    println(sb)
}

fun main() {
    // Disable the JVM's JAR URL cache. Without this, closing an extension's URLClassLoader still leaves a
    // cached JarFile open, which keeps the .jar LOCKED on Windows — so Unload → Update fails. Must run
    // before any extension jar is opened. (No-op-ish on Linux, which never had the lock.)
    runCatching { java.net.URLConnection.setDefaultUseCaches("jar", false) }
    LogBuffer.install() // capture WARN/ERROR into a ring buffer for the in-app log viewer
    javax.imageio.ImageIO.scanForPlugins() // register twelvemonkeys WebP/JPEG readers+writers
    // Honor a custom downloads directory chosen in Settings.
    SettingsStore.get().downloadDir?.takeIf { it.isNotBlank() }?.let { AppConfig.downloadDirOverride = java.nio.file.Path.of(it) }
    applyFlareSolverr(SettingsStore.get()) // push the Cloudflare-bypass config into the network layer
    autoDetectFlareOnce() // first-ever boot: find a running FlareSolverr on a default endpoint and wire it up
    // Detect Cloudflare-protected sources in the background (loads each source once).
    Thread { runCatching { mangautils.core.source.SourceManager.detectCloudflare() } }.apply { isDaemon = true; name = "cf-detect" }.start()
    // Warm the download-manager series cache in the background so its first open is instant, not a ~3s scan.
    Thread { runCatching { mangautils.core.download.DownloadStore.listSeries() } }.apply { isDaemon = true; name = "dl-warm" }.start()
    // Restore + resume the download queue from disk (survives a crash/restart).
    Thread { runCatching { DownloadQueue.loadAndResume() } }.apply { isDaemon = true; name = "dl-resume" }.start()
    UpdateScheduler.reschedule() // start background library updates if enabled in settings
    HealthScheduler.reschedule() // start the daily health sweep if enabled in settings
    val port = System.getenv("MANGA_WEB_PORT")?.toIntOrNull() ?: 8080
    log.info("Starting manga-utils web server on 0.0.0.0:{}", port)
    try {
        // start(wait=false) returns once Netty is bound & listening — THEN print the "online" banner so
        // it reflects a truly-up server, then block the main thread forever on a latch.
        embeddedServer(Netty, port = port, host = "0.0.0.0") { module() }.start(wait = false)
        printStartupBanner(port)
        announceOnline(port)
        java.util.concurrent.CountDownLatch(1).await() // park main thread; the server runs on Netty's threads
    } catch (e: java.net.BindException) {
        // A stale server (or something else) is already on the port — say so plainly instead of
        // dumping a BindException stack trace.
        System.err.println()
        System.err.println("  ✗ Port $port is already in use — is another manga-utils server still running?")
        System.err.println("    Close the other window, or stop the process holding port $port, then try again.")
        System.err.println("    (Or set MANGA_WEB_PORT to a free port.)")
        System.err.println()
        kotlin.system.exitProcess(1)
    }
}

fun Application.module() {
    install(DefaultHeaders)
    install(CallLogging) {
        // Don't log the high-frequency poll + static-asset traffic (the Downloads screen hits
        // /api/downloads every second, images stream constantly) — it floods the console.
        filter { call ->
            val p = call.request.path()
            // Reader triad (/api/chapter/pages, /api/read) is replaced by the semantic READ/PRELOAD lines.
            // NB: p == "/api/sources" is the EXACT source-health poll list only — the meaningful
            // sub-paths (/api/sources/{id}/search, /popular, /manga, …) still log.
            !(p == "/api/downloads" || p == "/api/sources" || p == "/api/logs" || p == "/api/notify/status" || p.startsWith("/img/") || p.startsWith("/assets/") || p == "/api/history" || p == "/api/dev/stats" || p == "/api/library/update/progress" || p == "/api/dyno/backup/progress" || p.startsWith("/api/net") || p == "/api/chapter/pages" || p == "/api/read" || p == "/api/flaresolverr/events")
        }
    }
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    install(CORS) {
        anyHost() // Tailscale-only deployment; no auth.
        allowHeader(io.ktor.http.HttpHeaders.ContentType)
    }

    // Never cache index.html / SPA routes, so a browser reload always picks up the latest built
    // bundle (the hashed /assets/* files stay immutable-cached). Without this, phones keep running an
    // old JS bundle after a rebuild until the tab is fully closed.
    // Never let the browser reuse a stale index.html — otherwise a new build's hashed JS/CSS are
    // never picked up (the classic "I can't see the change on my phone"). The HTML doc is tiny and
    // points at content-hashed /assets/ which stay cached forever, so no-store here is free.
    sendPipeline.intercept(io.ktor.server.response.ApplicationSendPipeline.Before) {
        val p = context.request.path()
        val isApiOrAsset = p.startsWith("/api") || p.startsWith("/img") || p.startsWith("/assets")
        val isHtmlDoc = p == "/" || p.endsWith(".html") || !p.substringAfterLast('/').contains('.') // SPA routes → index.html
        if (!isApiOrAsset && isHtmlDoc) {
            context.response.headers.append(io.ktor.http.HttpHeaders.CacheControl, "no-store, must-revalidate")
        }
    }

    routing {
        // ---- Sources ----
        get("/api/sources") {
            val visible = SettingsStore.get().visibleLanguages
            val sources = withContext(Dispatchers.IO) {
                // nsfw lives on the parent extension; flatten so each source carries its 18+ flag.
                InstalledStore.list()
                    .flatMap { ext ->
                        val webview = WebViewDetect.usesWebView(ext.jarPath)
                        ext.sources.map { SourceDto(it.id.toString(), it.name, it.lang, ext.nsfw, cfState(it.id), SourceHealth.isDown(it.id), SourceHealth.areImagesDown(it.id), webview) }
                    }
                    .filter { langVisible(it.lang, visible) }
                    .sortedBy { it.name.lowercase() }
            }
            call.respond(sources)
        }

        // Source health dashboard: per-source status (cf/down/images/circuit) + last-ok/fail/ping times,
        // sorted worst-first, plus a healthy/degraded/down rollup.
        get("/api/health/sources") {
            val visible = SettingsStore.get().visibleLanguages
            val report = withContext(Dispatchers.IO) {
                val rows = InstalledStore.list().flatMap { ext ->
                    val webview = WebViewDetect.usesWebView(ext.jarPath)
                    ext.sources.map { s ->
                        val rec = SourceHealth.record(s.id)
                        HealthSourceDto(
                            s.id.toString(), s.name, s.lang, cfState(s.id),
                            SourceHealth.isDown(s.id), SourceHealth.areImagesDown(s.id), webview,
                            SourceCircuits.api.isOpen(s.id), SourceCircuits.images.isOpen(s.id),
                            rec?.lastOkMs ?: 0, rec?.lastFailMs ?: 0, rec?.lastPingMs ?: 0,
                        )
                    }
                }.filter { langVisible(it.lang, visible) }
                fun rank(h: HealthSourceDto) = if (h.down || h.cfState == "red") 2 else if (h.imagesDown || h.cfState == "orange") 1 else 0
                val sorted = rows.sortedWith(compareByDescending<HealthSourceDto> { rank(it) }.thenBy { it.name.lowercase() })
                val down = sorted.count { rank(it) == 2 }
                val degraded = sorted.count { rank(it) == 1 }
                HealthReportDto(sorted, sorted.size - down - degraded, degraded, down)
            }
            call.respond(report)
        }
        post("/api/health/sweep") { HealthSweep.start(); call.respond(SweepProgressDto(HealthSweep.done, HealthSweep.total, HealthSweep.running)) }
        get("/api/health/sweep/progress") { call.respond(SweepProgressDto(HealthSweep.done, HealthSweep.total, HealthSweep.running)) }

        // ---- Discord webhook tester (no event wiring yet — just iterate on the embed format) ----
        post("/api/webhooks/test/ping") {
            val url = SettingsStore.get().discordWebhookUrl
            val r = withContext(Dispatchers.IO) { Notifier.sendNow(url, Notifier.Payload(content = "✅ **manga-utils** connected to this channel.")) }
            call.respond(WebhookResultDto(r.ok, r.status, r.rateLimited, r.retryAfter, r.error))
        }
        get("/api/notify/status") { call.respond(NotifyStatusDto(Notifier.rateLimitedAtMs, Notifier.rateLimitRetryAfter)) }

        // ---- Source migration ----
        get("/api/migrate/preview") {
            val fromSource = call.queryParam("fromSource")?.toLongOrNull()
            val fromUrl = call.queryParam("fromUrl")
            val toSource = call.queryParam("toSource")?.toLongOrNull()
            val toUrl = call.queryParam("toUrl")
            if (fromSource == null || fromUrl == null || toSource == null || toUrl == null) return@get call.respond(HttpStatusCode.BadRequest)
            if (fromSource == toSource && fromUrl == toUrl) return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("That's the same manga on the same source."))
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val fromEntry = LibraryStore.find(fromSource, fromUrl) ?: error("The source manga isn't in your library.")
                    val p = MigrationJob.compute(fromSource, fromUrl, toSource, toUrl)
                    MigratePreviewDto(
                        from = MigrateSideDto(sourceDisplayName(fromSource), p.fromTitle, fromEntry.thumbnailUrl, p.fromTotal, p.readNumbers.size, migFmt(p.readUpTo), p.bookmarkNumbers.size, p.fromDownloaded),
                        to = MigrateSideDto(sourceDisplayName(toSource), p.toTitle, p.toCover, p.toTotal, p.matchedRead, migFmt(p.lastReadB?.chapter_number ?: -1f), p.matchedBookmarks, 0),
                        willCarryRead = p.matchedRead, unmatchedRead = p.unmatchedRead,
                        willCarryBookmarks = p.matchedBookmarks, unmatchedBookmarks = p.unmatchedBookmarks,
                        chapterDiff = p.toTotal - p.fromTotal, unnumbered = p.unnumbered,
                    )
                }
            }
            result.fold(
                onSuccess = { call.respond(it) },
                onFailure = { call.respond(HttpStatusCode.BadGateway, ErrorDto(sourceErrorMessage(it))) },
            )
        }
        post("/api/migrate") {
            val b = call.receive<MigrateReq>()
            val fs = b.fromSource.toLongOrNull(); val ts = b.toSource.toLongOrNull()
            if (fs == null || ts == null) return@post call.respond(HttpStatusCode.BadRequest)
            MigrationJob.start(fs, b.fromUrl, ts, b.toUrl, b.deleteOldDownloads, b.reDownload)
            call.respond(MigrateProgressDto(MigrationJob.running, MigrationJob.finished, MigrationJob.phase, MigrationJob.error, MigrationJob.steps.toList()))
        }
        get("/api/migrate/progress") {
            call.respond(MigrateProgressDto(MigrationJob.running, MigrationJob.finished, MigrationJob.phase, MigrationJob.error, MigrationJob.steps.toList()))
        }
        post("/api/webhooks/test/sample") {
            val body = call.receive<WebhookSampleReq>()
            val url = SettingsStore.get().discordWebhookUrl
            val r = withContext(Dispatchers.IO) {
                val sid = body.source.toLongOrNull()
                val lib = sid?.let { LibraryStore.find(it, body.mangaUrl) }
                val srcName = sid?.let { runCatching { SourceManager.loadSource(it)?.name }.getOrNull() } ?: "Some Source"
                val title = lib?.title ?: "Sample Manga"
                val cover = lib?.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { t -> sid?.let { runCatching { SourceImage.coverBytes(it, t) }.getOrNull() } }
                fun mangaEmbed(info: String) = Notifier.mangaEmbed(title, lib?.mangaUrl, info, srcName, cover != null)
                when (body.kind) {
                    "sourcedown" -> Notifier.sendNow(url, Notifier.Payload(embeds = listOf(
                        Notifier.Embed(title = "⚠ $srcName is unreachable", description = "The source stopped responding during a health check.", color = 0xe86e8f, footer = Notifier.Footer(srcName)),
                    )))
                    "download" -> Notifier.sendNow(url, Notifier.Payload(embeds = listOf(mangaEmbed("📥 Downloaded 3 chapters\n• Chapter 138\n• Chapter 139\n• Chapter 140"))), cover)
                    "poster" -> {
                        // Full-width "poster" image instead of the small thumbnail, to compare the look.
                        val e = Notifier.mangaEmbed(title, lib?.mangaUrl, "🆕 3 new chapters\n• Chapter 138\n• Chapter 139\n• Chapter 140", srcName, withCover = false)
                            .copy(image = if (cover != null) Notifier.Img("attachment://cover.jpg") else null)
                        Notifier.sendNow(url, Notifier.Payload(embeds = listOf(e)), cover)
                    }
                    else -> Notifier.sendNow(url, Notifier.Payload(embeds = listOf(mangaEmbed("🆕 3 new chapters\n• Chapter 138\n• Chapter 139\n• Chapter 140"))), cover)
                }
            }
            call.respond(WebhookResultDto(r.ok, r.status, r.rateLimited, r.retryAfter, r.error))
        }

        // Distinct languages across all installed sources (for the visibility picker).
        get("/api/languages") {
            val langs = withContext(Dispatchers.IO) {
                InstalledStore.list().flatMap { it.sources }.map { it.lang }.filter { it.isNotBlank() }.distinct().sorted()
            }
            call.respond(langs)
        }

        get("/api/sources/{id}/popular") { browse(call, "popular") { id, page -> SourceBrowser.popularAsync(id, page) } }
        get("/api/sources/{id}/latest") { browse(call, "latest") { id, page -> SourceBrowser.latestAsync(id, page) } }
        get("/api/sources/{id}/search") {
            val q = call.queryParam("q") ?: ""
            browse(call, "search|$q") { id, page -> SourceBrowser.searchAsync(id, q, page) }
        }

        // Paste a source URL (e.g. https://atsu.moe/manga/-tya) → match the installed source by host and
        // resolve the manga directly, bypassing search (some sites' search APIs omit titles that exist).
        get("/api/resolve") {
            val raw = call.queryParam("url")?.trim()
            if (raw.isNullOrBlank()) return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("no url"))
            val normalized = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
            val uri = runCatching { java.net.URI(normalized) }.getOrNull()
            val host = uri?.host?.removePrefix("www.")?.lowercase()
            if (host.isNullOrBlank()) return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("Not a URL"))
            val path = (uri.path ?: "").trimEnd('/')
            val lastSeg = path.substringAfterLast('/')
            // A site path can map to the extension's manga-url in a few ways (full path, bare slug, …) — try each.
            val candidates = listOf(path, path.removePrefix("/"), "/$lastSeg", lastSeg)
                .map { it.trim() }.filter { it.isNotBlank() }.distinct()
            val result = withContext(mangautils.core.async.Pools.source) {
                for (s in InstalledStore.list().flatMap { it.sources }) {
                    val http = runCatching { SourceManager.loadSource(s.id) as? eu.kanade.tachiyomi.source.online.HttpSource }.getOrNull() ?: continue
                    val srcHost = runCatching { java.net.URI(http.baseUrl).host?.removePrefix("www.")?.lowercase() }.getOrNull() ?: continue
                    if (srcHost != host) continue
                    for (cand in candidates) {
                        val d = runCatching { SourceBrowser.detailsAsync(s.id, cand) }.getOrNull()
                        if (d != null && (d.chapters.isNotEmpty() || d.manga.title.isNotBlank())) {
                            return@withContext ResolveDto(s.id.toString(), cand, d.manga.title, d.manga.thumbnail_url)
                        }
                    }
                }
                null
            }
            if (result == null) call.respond(HttpStatusCode.NotFound, ErrorDto("Couldn't open that URL — is its source installed?"))
            else call.respond(result)
        }

        get("/api/sources/{id}/manga") {
            val id = call.sourceId() ?: return@get call.respond(HttpStatusCode.BadRequest, "bad source id")
            val url = call.queryParam("url") ?: return@get call.respond(HttpStatusCode.BadRequest, "missing url")
            val refresh = call.queryParam("refresh")?.toBoolean() == true
            val key = "$id|$url"
            val detail = withContext(mangautils.core.async.Pools.source) {
                if (!refresh) {
                    // Library entries serve from cache instantly (no network); else reuse a recent fetch.
                    LibraryStore.find(id, url)?.takeIf { it.knownChapters.isNotEmpty() }?.let { return@withContext Result.success(cachedDetail(it)) }
                    detailCache[key]?.let { return@withContext Result.success(it) }
                }
                runCatching {
                    val d = SourceBrowser.detailsAsync(id, url)
                    val mangaTitle = runCatching { d.manga.title }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
                    // A manual refresh of a followed manga updates its library snapshot (detects new chapters).
                    if (refresh && LibraryService.isFollowed(id, url)) {
                        runCatching { LibraryService.addKnown(id, url, mangaTitle, d.manga, d.chapters) }
                    }
                    DetailDto(
                        d.manga.toDto(id),
                        disambiguateScanlators(d.chapters.map { ch ->
                            val chName = runCatching { ch.name }.getOrDefault("")
                            ChapterDto(
                                url = runCatching { ch.url }.getOrDefault(""),
                                name = chName,
                                scanlator = runCatching { ch.scanlator }.getOrNull(),
                                dateUpload = runCatching { ch.date_upload }.getOrDefault(0),
                                number = runCatching { mangautils.core.util.ChapterNumber.of(ch, mangaTitle) }.getOrDefault(-1f),
                                downloaded = runCatching { DownloadManager.isDownloaded(mangaTitle, chName) }.getOrDefault(false),
                            )
                        }),
                        LibraryStore.find(id, url)?.newChapters?.toList() ?: emptyList(),
                    ).also { detailCache[key] = it }
                }
            }
            detail.fold(
                onSuccess = { SourceHealth.markUp(id); call.respond(it) },
                onFailure = { markFailure(id, it); call.respond(HttpStatusCode.BadGateway, ErrorDto(sourceErrorMessage(it))) },
            )
        }

        // ---- Source's own preferences (login / mirror / quality / language, per ConfigurableSource) ----
        get("/api/sources/{id}/preferences") {
            val id = call.sourceId() ?: return@get call.respond(HttpStatusCode.BadRequest, "bad source id")
            val r = withContext(Dispatchers.IO) { runCatching { mangautils.core.source.SourcePreferences.list(id) } }
            r.fold(
                onSuccess = { prefs ->
                    if (prefs == null) call.respond(HttpStatusCode.NotFound, ErrorDto("source not installed"))
                    else call.respond(prefs.map { it.toDto() })
                },
                onFailure = { call.respond(HttpStatusCode.BadGateway, ErrorDto(it.message ?: "couldn't load the source's settings")) },
            )
        }
        post("/api/sources/{id}/preferences") {
            val id = call.sourceId() ?: return@post call.respond(HttpStatusCode.BadRequest, "bad source id")
            val body = call.receive<SetPrefReq>()
            val err = withContext(Dispatchers.IO) { mangautils.core.source.SourcePreferences.set(id, body.index, body.value) }
            if (err == null) call.respond(HttpStatusCode.OK) else call.respond(HttpStatusCode.BadRequest, ErrorDto(err))
        }

        // ---- Library ----
        get("/api/library") {
            val entries = withContext(Dispatchers.IO) {
                LibraryService.list().map {
                    val last = it.knownChapters.maxByOrNull { c -> c.number }
                    // Group known chapters by number (dedup multi-scanlator variants); a chapter counts as
                    // downloaded if ANY of its variants has a file on disk. Badge = unique chapters, not the
                    // scanlator-inflated total, so a fully-downloaded series reads green (not perpetual yellow).
                    // Match on chapter URL, not folder name. Every version of a chapter shares a name, and
                    // versioned folders are "Chapter 4 [Gamma 2]", so a name match now fails for anything
                    // downloaded since versioning - reporting chapters we hold as missing and re-fetching
                    // them. A number counts as downloaded when ANY of its versions is on disk; grabbing the
                    // remaining versions is a separate, opt-in action.
                    val have = runCatching { ChapterIdentity.downloadedUrls(it.title) }.getOrDefault(emptySet())
                    val haveNums = runCatching { ChapterIdentity.downloadedNumbers(it.title) }.getOrDefault(emptySet())
                    val groups = it.knownChapters.groupBy { c -> if (c.number > 0) "n${c.number}" else "t${c.name.trim().lowercase()}" }
                    val total = groups.size
                    val downloaded = groups.count { (_, vs) -> vs.any { c -> c.url in have || c.number in haveNums } }
                    LibraryDto(it.sourceId.toString(), it.mangaUrl, it.title, it.thumbnailUrl, it.author, it.status, it.newChapters.size,
                        last?.number ?: -1f, last?.name ?: "", last?.dateUpload ?: 0, downloaded, total)
                }
            }
            call.respond(entries)
        }

        // Scan the whole library for new chapters (like the desktop). Updates per-manga newChapters.
        post("/api/library/update") {
            val results = withContext(Dispatchers.IO) {
                libUpdateRunning = true; libUpdateDone = 0; libUpdateTotal = 0
                try {
                    LibraryService.update(onProgress = { done, total -> libUpdateDone = done; libUpdateTotal = total })
                } finally { libUpdateRunning = false }
            }
            Notifier.onLibraryChecked(results, scheduled = false) // notify on manual checks too
            UpdateScheduler.autoDownloadNew(results) // honor the auto-download setting for manual checks too
            val titles = results.filter { it.newChapters.isNotEmpty() }
                .sortedByDescending { it.newChapters.size }
                .map { UpdatedTitleDto(it.entry.title, it.newChapters.size) }
            call.respond(UpdateSummaryDto(titles.sumOf { it.count }, titles.size, titles))
        }
        get("/api/library/update/progress") { call.respond(UpdateProgressDto(libUpdateDone, libUpdateTotal, libUpdateRunning)) }
        // Clear every series' "new chapters" flag (the ! badges) at once.
        post("/api/library/clear-new") {
            val n = withContext(Dispatchers.IO) {
                LibraryStore.list().filter { it.newChapters.isNotEmpty() }
                    .onEach { LibraryService.markSeen(it.sourceId, it.mangaUrl) }.size
            }
            call.respond(CountDto(n))
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
                    PositionStore.positions(id, url),
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
        post("/api/downloads/remove") {
            val id = call.queryParam("id") ?: return@post call.respond(HttpStatusCode.BadRequest)
            DownloadQueue.remove(id); call.respond(downloadsSnapshot())
        }
        post("/api/downloads/move") {
            val id = call.queryParam("id") ?: return@post call.respond(HttpStatusCode.BadRequest)
            val up = call.queryParam("dir") != "down"
            DownloadQueue.move(id, up); call.respond(downloadsSnapshot())
        }
        post("/api/downloads/resume") {
            val id = call.queryParam("id") ?: return@post call.respond(HttpStatusCode.BadRequest)
            DownloadQueue.resume(id); call.respond(downloadsSnapshot())
        }
        post("/api/downloads/resume-all") { DownloadQueue.resumeAll(); call.respond(downloadsSnapshot()) }

        // ---- Download manager (browse / delete on-disk content) ----
        // Broken-download detection: every series with interrupted/incomplete chapters (missing the
        // ComicInfo.xml written last), for a one-glance "N broken" report + repair.
        get("/api/downloads/broken") {
            val rep = withContext(Dispatchers.IO) {
                val series = DownloadStore.listSeries().filter { it.incomplete > 0 }.map { s ->
                    val names = DownloadStore.listChapters(s.title).filterNot { it.complete }.map { it.name }
                    BrokenSeriesDto(s.title, names, s.chapters)
                }.filter { it.broken.isNotEmpty() }
                BrokenReportDto(series, series.sumOf { it.broken.size })
            }
            call.respond(rep)
        }

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
        // Repair: re-download the incomplete/interrupted chapters of a series. Maps their (sanitized)
        // folder names back to source chapter URLs via the library's known chapters, deletes only the
        // ones we can re-fetch, then enqueues them. Returns the count queued, or -1 if not in library.
        post("/api/downloads/manage/repair") {
            val title = call.queryParam("title") ?: return@post call.respond(HttpStatusCode.BadRequest)
            val n = withContext(Dispatchers.IO) {
                val incompleteNames = DownloadStore.listChapters(title).filterNot { it.complete }.map { it.name }.toSet()
                if (incompleteNames.isEmpty()) return@withContext 0
                val entries = LibraryStore.list().filter { DownloadManager.sanitize(it.title) == title }
                if (entries.isEmpty()) return@withContext -1
                val plans = entries.map { entry ->
                    entry to entry.knownChapters
                        .filter { DownloadManager.sanitize(it.name) in incompleteNames }
                        .map { DownloadQueue.Chapter(it.url, it.name) }
                }.filter { it.second.isNotEmpty() }
                // Delete only the incomplete chapters we can actually re-download (else they'd be skipped
                // as "existing"); never delete one we can't re-fetch.
                val reDownloadable = plans.flatMap { it.second }.map { DownloadManager.sanitize(it.name) }.toSet()
                incompleteNames.filter { it in reDownloadable }.forEach { DownloadStore.deleteChapter(title, it) }
                plans.forEach { (entry, chapters) -> DownloadQueue.enqueue(entry.sourceId, entry.mangaUrl, entry.title, chapters) }
                plans.sumOf { it.second.size }
            }
            call.respond(CountDto(n))
        }

        // Mass download — plan: per-series downloaded/total (unique chapters, mirrors the library badge)
        // + the grand total that would queue. Uses cached knownChapters (no network); the UI can run
        // /api/library/update first to refresh. Sorted most-missing first.
        get("/api/downloads/mass/plan") {
            val plan = withContext(Dispatchers.IO) {
                val items = LibraryStore.list().map { e ->
                    val have = runCatching { ChapterIdentity.downloadedUrls(e.title) }.getOrDefault(emptySet())
                    val haveNums = runCatching { ChapterIdentity.downloadedNumbers(e.title) }.getOrDefault(emptySet())
                    val groups = e.knownChapters.groupBy { c -> if (c.number > 0) "n${c.number}" else "t${c.name.trim().lowercase()}" }
                    val total = groups.size
                    val downloaded = groups.count { (_, vs) -> vs.any { it.url in have || it.number in haveNums } }
                    val src = runCatching { SourceManager.loadSource(e.sourceId)?.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: e.sourceId.toString()
                    MassPlanItemDto(e.sourceId.toString(), e.mangaUrl, e.title, src, total, downloaded, total - downloaded)
                }.sortedWith(compareByDescending<MassPlanItemDto> { it.missing }.thenBy { it.title.lowercase() })
                MassPlanDto(items, items.sumOf { it.missing }, items.count { it.missing > 0 })
            }
            call.respond(plan)
        }

        // Mass download — start: enqueue every missing chapter (one variant per unique number) for the
        // chosen series. Returns the count queued.
        post("/api/downloads/mass/start") {
            val body = call.receive<MassStartReq>()
            val wanted = body.items.mapNotNull { i -> i.sourceId.toLongOrNull()?.let { it to i.mangaUrl } }.toSet()
            val queued = withContext(Dispatchers.IO) {
                var n = 0
                LibraryStore.list().filter { (it.sourceId to it.mangaUrl) in wanted }.forEach { e ->
                    val have = runCatching { ChapterIdentity.downloadedUrls(e.title) }.getOrDefault(emptySet())
                    val haveNums = runCatching { ChapterIdentity.downloadedNumbers(e.title) }.getOrDefault(emptySet())
                    val groups = e.knownChapters.groupBy { c -> if (c.number > 0) "n${c.number}" else "t${c.name.trim().lowercase()}" }
                    // Chapters the source can't serve are not "missing" in any actionable sense -
                    // queueing them just fails again. Force-download from the chapter row to override.
                    val unavailable = runCatching { UnavailableChapters.urls() }.getOrDefault(emptySet())
                    val missing = groups
                        .filterNot { (_, vs) -> vs.any { it.url in have || it.number in haveNums } }
                        .filterNot { (_, vs) -> vs.all { it.url in unavailable } }
                        .map { (_, vs) -> vs.first() }
                        .map { DownloadQueue.Chapter(it.url, it.name) }
                    if (missing.isNotEmpty()) { DownloadQueue.enqueue(e.sourceId, e.mangaUrl, e.title, missing); n += missing.size }
                }
                n
            }
            call.respond(CountDto(queued))
        }

        // Reading stats — aggregates ReadStore + HistoryStore + library + downloads for the stats page.
        get("/api/stats") {
            val dto = withContext(Dispatchers.IO) {
                val lib = LibraryStore.list()
                val libByKey = lib.associateBy { "${it.sourceId}|${it.mangaUrl}" }
                val readCounts = ReadStore.allReadCounts()
                val history = HistoryStore.list()
                val histByKey = history.associateBy { "${it.sourceId}|${it.mangaUrl}" }
                val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                val topSeries = readCounts.entries.filter { it.value > 0 }
                    .mapNotNull { (k, c) ->
                        val parts = k.split("|", limit = 2)
                        val le = libByKey[k]
                        val h = histByKey[k]
                        // Resolve a real title from the library or reading history. If the series was removed
                        // and isn't in history either, skip it — never surface a raw url slug like "LJyx5".
                        val title = le?.title ?: h?.mangaTitle
                        if (title.isNullOrBlank()) null
                        else StatSeriesDto(title, c, parts.getOrNull(0) ?: "", parts.getOrNull(1) ?: "", le?.thumbnailUrl ?: h?.thumbnailUrl)
                    }
                    .sortedByDescending { it.count }
                    .take(10)
                // Recently read: ONE row per manga (its latest chapter), not every chapter of the same series.
                // History is newest-first, so the first time we see a manga key is its most recent read.
                val seenManga = HashSet<String>()
                val recent = history
                    .filter { seenManga.add("${it.sourceId}|${it.mangaUrl}") }
                    .take(15)
                    .map { StatRecentDto(it.mangaTitle, it.chapterName, it.readAt, it.sourceId.toString(), it.mangaUrl, it.thumbnailUrl) }
                StatsDto(
                    chaptersRead = readCounts.values.sum(),
                    seriesInLibrary = lib.size,
                    readThisWeek = history.count { it.readAt >= weekAgo },
                    topSeries = topSeries,
                    recent = recent,
                )
            }
            call.respond(dto)
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

        /** Where you stopped in a chapter. Sent when leaving it, so it survives to your other devices. */
        post("/api/position") {
            val id = call.querySourceId() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val manga = call.queryParam("manga") ?: return@post call.respond(HttpStatusCode.BadRequest)
            val chapter = call.queryParam("chapter") ?: return@post call.respond(HttpStatusCode.BadRequest)
            val frac = call.queryParam("frac")?.toFloatOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            withContext(Dispatchers.IO) { PositionStore.set(id, manga, chapter, frac) }
            call.respond(HttpStatusCode.OK)
        }

        post("/api/read") {
            val id = call.querySourceId() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val manga = call.queryParam("manga") ?: return@post call.respond(HttpStatusCode.BadRequest)
            val chapter = call.queryParam("chapter") ?: return@post call.respond(HttpStatusCode.BadRequest)
            val read = call.queryParam("read")?.toBoolean() ?: true
            withContext(Dispatchers.IO) {
                ReadStore.setRead(id, manga, chapter, read)
                // Unread means "I want to read this again", so the resume point goes with the flag. Done
                // here rather than in each client so every caller - web, bulk mark-unread, a future app -
                // gets it without having to remember.
                if (!read) PositionStore.clear(id, manga, chapter)
                // Reading a new chapter clears its "new" flag; the "!" badge disappears once none remain.
                if (read) LibraryService.markChapterSeen(id, manga, chapter)
            }
            if (read) log.info("READ     chapter {}", chapter) // one clean line per chapter opened
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
        get("/api/settings") { call.respond(settingsDto(SettingsStore.get())) }
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
            body.autoUpdate?.let { s = s.copy(autoUpdate = it) }
            body.autoUpdateHours?.let { s = s.copy(autoUpdateHours = it.coerceIn(1, 168)) }
            body.autoUpdateHour?.let { s = s.copy(autoUpdateHour = it.coerceIn(0, 23)) }
            body.autoDownloadNew?.let { s = s.copy(autoDownloadNew = it) }
            body.healthCheckEnabled?.let { s = s.copy(healthCheckEnabled = it) }
            body.healthCheckHour?.let { s = s.copy(healthCheckHour = it.coerceIn(0, 23)) }
            body.flareSolverrEnabled?.let { s = s.copy(flareSolverrEnabled = it) }
            body.flareSolverrUrl?.let { s = s.copy(flareSolverrUrl = it.trim()) }
            body.flareSolverrSession?.let { s = s.copy(flareSolverrSession = it.trim()) }
            body.flareSolverrSessionTtlMinutes?.let { s = s.copy(flareSolverrSessionTtlMinutes = it.coerceIn(1, 1440)) }
            body.flareSolverrTimeoutMs?.let { s = s.copy(flareSolverrTimeoutMs = it.coerceIn(10000, 180000)) }
            body.usbBackupDir?.let { s = s.copy(usbBackupDir = it.trim()) }
            body.discordWebhookUrl?.let { s = s.copy(discordWebhookUrl = it.trim()) }
            body.notify?.let { s = s.copy(notify = it) }
            withContext(Dispatchers.IO) { SettingsStore.save(s) }
            AppConfig.downloadDirOverride = s.downloadDir?.takeIf { it.isNotBlank() }?.let { java.nio.file.Path.of(it) }
            applyFlareSolverr(s) // live-apply the Cloudflare-bypass config
            UpdateScheduler.reschedule() // apply any change to the auto-update interval/toggle
            HealthScheduler.reschedule() // apply any change to the health-check schedule
            call.respond(settingsDto(s))
        }

        // ---- Relocate the downloads library to a new root (e.g. an external SSD) ----
        post("/api/relocate/plan") {
            val req = call.receive<RelocatePlanReq>()
            if (req.root.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("Pick a target folder"))
            val p = withContext(Dispatchers.IO) { runCatching { RelocateJob.plan(req.root) }.getOrNull() }
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("Couldn't read that path"))
            call.respond(RelocatePreviewDto(p.sourceBytes, p.sourceFiles, p.targetFreeBytes, p.targetLayout, p.activeDownloads, p.fits, p.warning))
        }
        post("/api/relocate/start") {
            val req = call.receive<RelocateStartReq>()
            if (req.mode !in setOf("move", "copy", "point")) return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("bad mode"))
            if (req.root.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("Pick a target folder"))
            if (RelocateJob.running) return@post call.respond(HttpStatusCode.Conflict, ErrorDto("A relocate is already running"))
            RelocateJob.start(req.root, req.mode)
            call.respond(HttpStatusCode.Accepted)
        }
        get("/api/relocate/progress") {
            call.respond(
                RelocateProgressDto(
                    RelocateJob.running, RelocateJob.phase, RelocateJob.finished, RelocateJob.error,
                    RelocateJob.mode, RelocateJob.target,
                    RelocateJob.filesTotal, RelocateJob.filesDone, RelocateJob.bytesTotal, RelocateJob.bytesDone,
                    RelocateJob.steps.toList(),
                ),
            )
        }
        // Verify a FlareSolverr instance is reachable. With a url it tests that one; blank auto-discovers
        // across the common local/docker endpoints and returns the working one (FlareTestDto.url).
        get("/api/flaresolverr/test") {
            val explicit = call.queryParam("url")?.takeIf { it.isNotBlank() }
            val r = withContext(Dispatchers.IO) { discoverFlare(explicit) }
            call.respond(r)
        }
        // Import a Mihon / Tachiyomi / Suwayomi backup (.tachibk / .proto.gz) — raw gzipped bytes in the body.
        post("/api/backup/import") {
            val bytes = withContext(Dispatchers.IO) { call.receiveStream().readBytes() }
            // Restore runs as a background job so the UI can show a live progress bar; poll /progress.
            val t = withContext(Dispatchers.IO) { runCatching { ImportJob.start(bytes) } }
            t.fold(
                onSuccess = { call.respond(importJobDto(it)) },
                onFailure = { call.respond(HttpStatusCode.BadRequest, ErrorDto("Couldn't start import (${it.message})")) },
            )
        }
        // Live progress for the running restore (null until one is started).
        get("/api/backup/import/progress") {
            val t = ImportJob.snapshot()
            if (t == null) call.respond(HttpStatusCode.NoContent) else call.respond(importJobDto(t))
        }
        // Dry run: what a backup would import, without changing the library.
        post("/api/backup/preview") {
            val bytes = withContext(Dispatchers.IO) { call.receiveStream().readBytes() }
            val r = withContext(Dispatchers.IO) { runCatching { mangautils.core.backup.BackupImport.preview(bytes) } }
            r.fold(
                onSuccess = { p -> call.respond(BackupPreviewDto(p.total, p.manga.map { BackupPreviewItemDto(it.title, it.source.toString(), it.chapters, it.read, it.inLibrary) }, p.hasSettings, p.repos, p.extensions)) },
                onFailure = { call.respond(HttpStatusCode.BadRequest, ErrorDto("Couldn't read that backup — is it a Mihon/Tachiyomi .tachibk? (${it.message})")) },
            )
        }
        // Export chosen sections. ?include=library,settings,repos,extensions (default: library only).
        get("/api/backup/export") {
            val include = call.request.queryParameters["include"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            val sections = if (include.isNullOrEmpty()) mangautils.core.backup.BackupImport.Sections() else mangautils.core.backup.BackupImport.Sections.of(include)
            val bytes = withContext(Dispatchers.IO) { mangautils.core.backup.BackupImport.export(sections) }
            call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"manga-utils.tachibk\"")
            call.respondBytes(bytes, ContentType.parse("application/gzip"))
        }
        // POST variant so the frontend can also fold in browser reader/display prefs (localStorage).
        post("/api/backup/export") {
            val req = call.receive<ExportReqDto>()
            val sections = if (req.include.isEmpty()) mangautils.core.backup.BackupImport.Sections() else mangautils.core.backup.BackupImport.Sections.of(req.include.toSet())
            val bytes = withContext(Dispatchers.IO) { mangautils.core.backup.BackupImport.export(sections, req.clientPrefs) }
            call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"manga-utils.tachibk\"")
            call.respondBytes(bytes, ContentType.parse("application/gzip"))
        }
        // ---- DYNO Phase 0: back up (metadata blob + downloaded chapters) to a mounted USB drive ----
        post("/api/dyno/backup-now") {
            val target = dynoTarget()
            if (!java.nio.file.Files.isDirectory(target)) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("USB drive not mounted at $target"))
            }
            val t = BackupJob.start(target)
            call.respond(HttpStatusCode.Accepted, backupJobDto(t))
        }
        get("/api/dyno/backup/progress") { call.respond(backupJobDto(BackupJob.snapshot())) }
        // Recent FlareSolverr solve events, so the web UI can toast "solving / solved".
        get("/api/flaresolverr/events") {
            val cfg = eu.kanade.tachiyomi.network.interceptor.FlareSolverrConfig
            val since = call.queryParam("since")?.toLongOrNull() ?: cfg.lastEventId()
            val evs = cfg.eventsSince(since).map { FlareEventDto(it.id, it.host, it.phase, it.cookies) }
            call.respond(FlareEventsDto(cfg.lastEventId(), evs))
        }
        get("/api/diag") {
            val id = call.querySourceId() ?: return@get call.respond(HttpStatusCode.BadRequest)
            val r = withContext(Dispatchers.IO) { Diagnostics.run(id) }
            call.respond(DiagDto(r.source, r.baseUrl, r.pingMs, r.speedMbps, r.sampleBytes, r.ok, r.error))
        }
        get("/api/dev/stats") { call.respond(devStats()) }

        /** Chapters the source can't serve, which automatic downloads skip. */
        get("/api/downloads/unavailable") { call.respond(UnavailableChapters.list()) }

        /**
         * Forget a chapter is unavailable so it can be tried again - the Force path. Without ?url= it
         * clears every mark, e.g. after a source has republished.
         */
        delete("/api/downloads/unavailable") {
            UnavailableChapters.clear(call.queryParam("url"))
            call.respond(UnavailableChapters.list())
        }

        /**
         * Dry run: which scanlations are missing, and roughly what fetching them would cost. Downloads
         * nothing. Pass ?title= for the per-chapter breakdown of one series; omit it for the
         * library-wide totals, which are there to be read rather than acted on.
         */
        get("/api/scanver/plan") {
            val title = call.queryParam("title")
            call.respond(withContext(Dispatchers.IO) { ScanVersions.plan(title) })
        }

        /** Queue the missing versions for ONE series. Requires an explicit title - never library-wide. */
        post("/api/scanver/start") {
            val title = call.queryParam("title") ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("missing title"))
            val queued = runCatching { withContext(Dispatchers.IO) { ScanVersions.start(title) } }
            queued.fold(
                onSuccess = { call.respond(mapOf("queued" to it)) },
                onFailure = { call.respond(HttpStatusCode.InternalServerError, ErrorDto(it.message ?: "failed")) },
            )
        }

        /**
         * Dev: what the app thinks each downloaded folder of a series actually is, plus what the source
         * currently offers. This is how the per-scanlator work gets checked by eye before anything
         * depends on it — a wrong identity shows up here rather than as odd behaviour weeks later.
         */
        get("/api/dev/identity") {
            val title = call.queryParam("title") ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("missing title"))
            val sourceId = call.queryParam("source")?.toLongOrNull()
            val mangaUrl = call.queryParam("url")
            val dto =
                withContext(Dispatchers.IO) {
                    val onDisk = ChapterIdentity.versionsOf(title)
                    // Map url -> scanlator from the source, so pre-P1 folders (no Translator tag) can
                    // still be identified, and so we can list which versions exist but aren't downloaded.
                    val fromSource =
                        if (sourceId != null && mangaUrl != null) {
                            runCatching {
                                val src = SourceManager.loadSource(sourceId) ?: error("source not loaded")
                                val seed = eu.kanade.tachiyomi.source.model.SManga.create().apply { url = mangaUrl }
                                src.getChapterList(seed).map { c ->
                                    IdentSrcDto(c.url, c.name, c.scanlator, c.chapter_number)
                                }
                            }.getOrElse { emptyList<IdentSrcDto>() }
                        } else {
                            emptyList()
                        }
                    val byUrl = fromSource.associateBy { it.url }
                    val disk =
                        onDisk.map { v ->
                            IdentDiskDto(
                                folder = v.folder,
                                url = v.url,
                                scanlator = v.scanlator,
                                // Where the folder predates P1, recover the group from the source list.
                                resolvedScanlator = v.scanlator ?: v.url?.let { byUrl[it]?.scanlator },
                                number = v.number,
                                pageCount = v.pageCount,
                                complete = v.complete,
                                unidentified = v.unidentified,
                            )
                        }.sortedWith(compareBy({ it.number ?: Float.MAX_VALUE }, { it.folder }))
                    val haveUrls = disk.mapNotNull { it.url }.toSet()
                    IdentityDto(
                        title = title,
                        onDisk = disk.size,
                        identified = disk.count { !it.unidentified },
                        sourceVersions = fromSource.size,
                        sourceNumbers = fromSource.map { it.number }.distinct().size,
                        missing = fromSource.filterNot { it.url in haveUrls }.size,
                        chapters = disk,
                        source = fromSource.sortedWith(compareBy({ it.number }, { it.scanlator ?: "" })),
                    )
                }
            call.respond(dto)
        }
        get("/api/logs") {
            val level = call.queryParam("level") ?: "warn"
            val limit = call.queryParam("limit")?.toIntOrNull() ?: 200
            call.respond(LogBuffer.recent(level, limit).map { LogDto(it.ts, it.level, it.logger, it.msg) })
        }

        // ---- Client<->host speed test (phone <-> server over Tailscale). Timed on the client. ----
        get("/api/net/ping") { call.respondBytes("pong".toByteArray()) }
        get("/api/net/down") {
            val n = (call.queryParam("bytes")?.toIntOrNull() ?: 8_000_000).coerceIn(1, 64_000_000)
            call.response.headers.append(io.ktor.http.HttpHeaders.CacheControl, "no-store")
            call.respondBytes(ByteArray(n), io.ktor.http.ContentType.Application.OctetStream)
        }
        post("/api/net/up") {
            val n = call.receive<ByteArray>().size
            call.respondBytes(n.toString().toByteArray())
        }

        // Build info + tech stack + recent changelog (baked into resources at build time). Lets each
        // device show exactly which build it's running.
        get("/api/version") {
            val cl = object {}.javaClass
            val props = java.util.Properties().apply { cl.getResourceAsStream("/build-info.properties")?.use { load(it) } }
            val changelog = cl.getResourceAsStream("/changelog.tsv")?.bufferedReader()?.use { r -> r.readLines() }
                ?.mapNotNull { line -> line.split("\t").takeIf { it.size >= 3 }?.let { ChangeDto(it[0], it[1], it.drop(2).joinToString("\t")) } }
                ?: emptyList()
            call.respond(
                VersionDto(
                    version = props.getProperty("version", "dev"),
                    commit = props.getProperty("commit", "unknown"),
                    buildTime = props.getProperty("buildTime", ""),
                    tech = TECH_STACK,
                    changelog = changelog,
                ),
            )
        }
        // TEST: make the app think a library manga got a new chapter — forgets its latest known
        // chapter so the next update re-detects it as new (setting the "!" badge, and auto-downloading
        // it if that setting is on). Reversible: the update re-adds it to knownChapters.
        post("/api/dev/simulate-update") {
            val id = call.querySourceId() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val manga = call.queryParam("manga") ?: return@post call.respond(HttpStatusCode.BadRequest)
            val result = withContext(Dispatchers.IO) {
                val entry = LibraryStore.find(id, manga) ?: return@withContext null
                if (entry.knownChapters.isEmpty()) return@withContext SimulateDto(entry.title, -1, false)
                val latest = entry.knownChapters.maxByOrNull { it.number } ?: entry.knownChapters.last()
                entry.knownChapters = entry.knownChapters.filterNot { it.url == latest.url }.toMutableList()
                LibraryStore.upsert(entry)
                val results = LibraryService.update(listOf(LibraryStore.find(id, manga)!!))
                UpdateScheduler.autoDownloadNew(results)
                val n = results.sumOf { it.newChapters.size }
                SimulateDto(entry.title, n, SettingsStore.get().autoDownloadNew && n > 0)
            }
            if (result == null) call.respond(HttpStatusCode.NotFound, ErrorDto("Not in your library"))
            else call.respond(result)
        }

        // ---- Extensions + repositories ----
        get("/api/extensions") {
            val list = withContext(Dispatchers.IO) {
                val repoOf = runCatching { availableEntries().associate { it.first.pkg to repoLabel(it.second) } }.getOrDefault(emptyMap())
                InstalledStore.list().map { ExtDto(it.pkg, it.name, it.versionName, it.lang, it.nsfw, it.sources.size, repoOf[it.pkg] ?: "", WebViewDetect.usesWebView(it.jarPath)) }
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
                onFailure = {
                    log.warn("extension install/update failed for {}: {}", pkg, it.message, it) // log the real cause
                    // Truthful status: the old .jar being locked by the running JVM is a local conflict
                    // (Windows-only), not an upstream gateway failure; everything else is an internal error.
                    val locked = it is java.nio.file.FileSystemException ||
                        it.message?.contains("used by another process", ignoreCase = true) == true
                    if (locked) {
                        call.respond(HttpStatusCode.Conflict, ErrorDto("That extension is loaded and its file is locked — a Windows-only limitation. In-place updates work on the Linux deploy."))
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, ErrorDto(it.message ?: "Install failed"))
                    }
                },
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
        // What each repo actually offers. A repo that fails to fetch reports zeroes rather than
        // failing the whole call, so one dead repo can't blank the others' counts.
        get("/api/repos/stats") {
            val stats =
                withContext(Dispatchers.IO) {
                    RepoStore.list().map { url ->
                        val entries = runCatching { ExtensionRepoClient().fetchIndex(url) }.getOrDefault(emptyList())
                        RepoStatDto(url = url, extensions = entries.size, sources = entries.sumOf { it.sources.size })
                    }
                }
            call.respond(stats)
        }
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
                val local = if (title != null && name != null) runCatching { LocalChapterReader.localChapter(title, name, chapter) }.getOrNull() else null
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
            val bytes = coverCache[url] ?: withContext(mangautils.core.async.Pools.cover) {
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
            // Next-chapter preload requests are marked so they're visible in the log even for
            // cached/downloaded pages (normal /img/page logging stays filtered to keep the console clean).
            // One line per preloaded chapter (on its first page) instead of one per page.
            // The reader refuses to warm a chapter it knows is broken, and says so here rather than only
            // in the browser console - this log is where you actually watch downloads and reads happen.
            call.request.headers["X-Preload-Skip"]?.let { why ->
                log.info("PRELOAD  SKIPPED {} ({}) - {}", name ?: chapter, call.queryParam("scan") ?: "unknown scan", why)
                return@get call.respond(HttpStatusCode.NoContent)
            }
            // Refuse to warm a chapter we know is broken. The reader also skips these, but that depends on
            // the browser running current JS - a cached bundle would still preload it, and this endpoint is
            // the only place that always knows. A preload is never something you asked for directly, so
            // dropping it costs nothing; an explicit read still goes through (that is the Force path).
            val isPreload = call.request.headers["X-Preload"] != null || call.queryParam("pre") == "1"
            if (isPreload && UnavailableChapters.isUnavailable(chapter)) {
                if (index == 0) {
                    log.info("PRELOAD  SKIPPED {} ({}) - {}", name ?: chapter, call.queryParam("scan") ?: "unknown scan",
                        UnavailableChapters.list().firstOrNull { it.url == chapter }?.reason ?: "known broken")
                }
                return@get call.respond(HttpStatusCode.NoContent)
            }
            if (call.request.headers["X-Preload"] != null && index == 0) log.info("PRELOAD  next chapter {}", chapter)
            var fromSource = false
            val bytes = withContext(mangautils.core.async.Pools.image) {
                val local = if (title != null && name != null) runCatching { LocalChapterReader.localChapter(title, name, chapter) }.getOrNull() else null
                if (local != null) {
                    local.bytes(index)
                } else {
                    fromSource = true
                    pagesFor(id, chapter).getOrNull(index)?.let { SourceImage.pageBytesAsync(id, it) }
                }
            }
            // Track image-serving health from real fetches (not local reads) so a source can show
            // "images down" even when its API is fine (the atsu case).
            if (fromSource) { if (bytes == null) SourceHealth.markImagesDown(id) else SourceHealth.markImagesUp(id) }
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
    kind: String,
    op: suspend (Long, Int) -> eu.kanade.tachiyomi.source.model.MangasPage,
) {
    val id = call.sourceId() ?: return call.respond(HttpStatusCode.BadRequest, "bad source id")
    val page = call.queryParam("page")?.toIntOrNull() ?: 1
    val key = "$id|$kind|$page"
    val now = System.currentTimeMillis()
    browseCache[key]?.let { (t, v) -> if (now - t < BROWSE_TTL_MS) return call.respond(v) }
    // Circuit breaker: a source that just failed repeatedly fails INSTANTLY for a cooldown instead of
    // hanging on its 20s timeout again (global search especially — skip known-down sources fast).
    if (mangautils.core.source.SourceCircuits.api.isOpen(id)) {
        return call.respond(HttpStatusCode.BadGateway, ErrorDto("This source is temporarily unavailable (recent failures) — retrying shortly."))
    }
    // Isolated source pool + cancellable op: if the client navigates away, this coroutine is
    // cancelled and the extension's OkHttp call is cancelled with it (no 20s zombie thread).
    val result = withContext(mangautils.core.async.Pools.source) {
        runCatching {
            val mp = op(id, page)
            PageResultDto(mp.mangas.map { it.toDto(id) }, mp.hasNextPage)
        }
    }
    result.fold(
        onSuccess = {
            mangautils.core.source.SourceCircuits.api.recordSuccess(id)
            SourceHealth.markUp(id)
            browseCache[key] = now to it
            if (browseCache.size > 256) browseCache.entries.removeIf { (System.currentTimeMillis() - it.value.first) > BROWSE_TTL_MS }
            call.respond(it)
        },
        onFailure = {
            // A timeout IS a source failure (must trip the breaker so flaky/slow sources fast-fail);
            // only a PLAIN cancellation (client navigated away) shouldn't count.
            if (it !is kotlinx.coroutines.CancellationException || it is kotlinx.coroutines.TimeoutCancellationException) {
                mangautils.core.source.SourceCircuits.api.recordFailure(id)
            }
            markFailure(id, it)
            call.respond(HttpStatusCode.BadGateway, ErrorDto(sourceErrorMessage(it)))
        },
    )
}

/** A human-readable reason a source call failed — the engine already gives good messages
 *  (e.g. "Cloudflare protection is blocking this source (HTTP 403)…", "HTTP error 522"). */
private fun sourceErrorMessage(e: Throwable): String =
    e.message?.takeIf { it.isNotBlank() } ?: (e::class.simpleName ?: "The source is unavailable")

/** Record a failure: Cloudflare blocks stay "up but blocked"; anything else marks the source down. */
private fun markFailure(sourceId: Long, e: Throwable) {
    log.warn("source {} call failed: {}", sourceId, e.message ?: e::class.simpleName) // visible so 5xx can be troubleshot
    if (e.message?.contains("Cloudflare", ignoreCase = true) == true) {
        CloudflareState.mark(sourceId)
        SourceHealth.markUp(sourceId)
    } else {
        SourceHealth.markDown(sourceId)
    }
}

/** green = no Cloudflare seen; red = behind Cloudflare, no bypass; orange = behind CF + bypass. */
private fun cfState(sourceId: Long): String =
    if (!CloudflareState.isBlocked(sourceId)) "green" else if (cloudflareBypassOn()) "orange" else "red"

/** A source's language is visible if no filter is set, it's language-agnostic, or it's selected. */
private fun langVisible(lang: String, visible: List<String>): Boolean =
    visible.isEmpty() || lang.isBlank() || lang.equals("all", true) || visible.any { it.equals(lang, true) }
