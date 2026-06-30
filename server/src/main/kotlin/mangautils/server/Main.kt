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
import mangautils.core.extension.InstalledStore
import mangautils.core.library.BookmarkStore
import mangautils.core.library.HistoryStore
import mangautils.core.library.LibraryEntry
import mangautils.core.library.LibraryService
import mangautils.core.library.LibraryStore
import mangautils.core.library.MangaBookmarkStore
import mangautils.core.library.ReadStore
import mangautils.core.source.Diagnostics
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
private data class SourceDto(val id: String, val name: String, val lang: String, val nsfw: Boolean)

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
private data class DetailDto(val manga: MangaDto, val chapters: List<ChapterDto>)

@Serializable
private data class LibraryDto(
    val sourceId: String,
    val url: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val author: String? = null,
    val status: Int = 0,
    val newChapters: Int = 0,
)

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
    val englishSourcesOnly: Boolean,
    val cloudflareBypass: Boolean,
)

@Serializable
private data class SettingsPatch(
    val downloadDir: String? = null,
    val downloadAsCbz: Boolean? = null,
    val downloadConcurrency: Int? = null,
    val englishSourcesOnly: Boolean? = null,
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
)

private fun pagesFor(sourceId: Long, chapterUrl: String): List<Page> =
    pageCache.getOrPut("$sourceId|$chapterUrl") { SourceImage.pageList(sourceId, chapterUrl) }

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
    val port = System.getenv("MANGA_WEB_PORT")?.toIntOrNull() ?: 8080
    log.info("Starting manga-utils web server on 0.0.0.0:{}", port)
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module() }.start(wait = true)
}

fun Application.module() {
    install(DefaultHeaders)
    install(CallLogging)
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    install(CORS) {
        anyHost() // Tailscale-only deployment; no auth.
        allowHeader(io.ktor.http.HttpHeaders.ContentType)
    }

    routing {
        // ---- Sources ----
        get("/api/sources") {
            val enOnly = SettingsStore.get().englishSourcesOnly
            val sources = withContext(Dispatchers.IO) {
                // nsfw lives on the parent extension; flatten so each source carries its 18+ flag.
                InstalledStore.list()
                    .flatMap { ext -> ext.sources.map { SourceDto(it.id.toString(), it.name, it.lang, ext.nsfw) } }
                    .filter { !enOnly || it.lang.isBlank() || it.lang.equals("en", true) || it.lang.equals("all", true) }
                    .sortedBy { it.name.lowercase() }
            }
            call.respond(sources)
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
                    ).also { detailCache[key] = it }
                }
            }
            detail.fold(
                onSuccess = { call.respond(it) },
                onFailure = { call.respond(HttpStatusCode.BadGateway, ErrorDto(sourceErrorMessage(it))) },
            )
        }

        // ---- Library ----
        get("/api/library") {
            val entries = withContext(Dispatchers.IO) {
                LibraryService.list().map {
                    LibraryDto(it.sourceId.toString(), it.mangaUrl, it.title, it.thumbnailUrl, it.author, it.status, it.newChapters.size)
                }
            }
            call.respond(entries)
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
                HistoryStore.list().map {
                    HistoryDto(it.sourceId.toString(), it.mangaUrl, it.mangaTitle, it.thumbnailUrl, it.chapterUrl, it.chapterName, it.readAt)
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
            val thumb = call.queryParam("thumb")
            withContext(Dispatchers.IO) { HistoryStore.record(id, manga, mangaTitle, chapter, name, thumb) }
            call.respond(HttpStatusCode.OK)
        }
        delete("/api/history") {
            val id = call.querySourceId() ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val manga = call.queryParam("manga") ?: return@delete call.respond(HttpStatusCode.BadRequest)
            withContext(Dispatchers.IO) { HistoryStore.remove(id, manga) }
            call.respond(HttpStatusCode.OK)
        }

        // ---- Settings + diagnostics ----
        get("/api/settings") {
            val s = SettingsStore.get()
            call.respond(SettingsDto(s.downloadDir, AppConfig.downloadsDir.toString(), AppConfig.dataDir.toString(), s.downloadAsCbz, s.downloadConcurrency, s.englishSourcesOnly, CLOUDFLARE_BYPASS))
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
            body.englishSourcesOnly?.let { s = s.copy(englishSourcesOnly = it) }
            withContext(Dispatchers.IO) { SettingsStore.save(s) }
            AppConfig.downloadDirOverride = s.downloadDir?.takeIf { it.isNotBlank() }?.let { java.nio.file.Path.of(it) }
            call.respond(SettingsDto(s.downloadDir, AppConfig.downloadsDir.toString(), AppConfig.dataDir.toString(), s.downloadAsCbz, s.downloadConcurrency, s.englishSourcesOnly, CLOUDFLARE_BYPASS))
        }
        get("/api/diag") {
            val id = call.querySourceId() ?: return@get call.respond(HttpStatusCode.BadRequest)
            val r = withContext(Dispatchers.IO) { Diagnostics.run(id) }
            call.respond(DiagDto(r.source, r.baseUrl, r.pingMs, r.speedMbps, r.sampleBytes, r.ok, r.error))
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
        onSuccess = { call.respond(it) },
        onFailure = { call.respond(HttpStatusCode.BadGateway, ErrorDto(sourceErrorMessage(it))) },
    )
}

/** A human-readable reason a source call failed — the engine already gives good messages
 *  (e.g. "Cloudflare protection is blocking this source (HTTP 403)…", "HTTP error 522"). */
private fun sourceErrorMessage(e: Throwable): String =
    e.message?.takeIf { it.isNotBlank() } ?: (e::class.simpleName ?: "The source is unavailable")
