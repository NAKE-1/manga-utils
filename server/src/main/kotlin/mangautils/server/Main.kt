/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.server

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import io.ktor.http.ContentType
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
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mangautils.core.library.HistoryStore
import mangautils.core.library.LibraryService
import mangautils.core.source.LocalChapterReader
import mangautils.core.source.SourceBrowser
import mangautils.core.source.SourceImage
import mangautils.core.source.SourceManager
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("mangautils.server")

// ---- DTOs (IDs are Strings to survive JS number precision) ----------------------------------

@Serializable
private data class SourceDto(val id: String, val name: String, val lang: String)

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
            val sources = withContext(Dispatchers.IO) {
                SourceManager.listInstalledSources().map { SourceDto(it.id.toString(), it.name, it.lang) }
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
            val detail = withContext(Dispatchers.IO) {
                runCatching {
                    val d = SourceBrowser.details(id, url)
                    DetailDto(
                        d.manga.toDto(id),
                        d.chapters.map { ch ->
                            ChapterDto(
                                url = runCatching { ch.url }.getOrDefault(""),
                                name = runCatching { ch.name }.getOrDefault(""),
                                scanlator = runCatching { ch.scanlator }.getOrNull(),
                                dateUpload = runCatching { ch.date_upload }.getOrDefault(0),
                                number = runCatching { ch.chapter_number }.getOrDefault(-1f),
                            )
                        },
                    )
                }.getOrNull()
            }
            if (detail == null) call.respond(HttpStatusCode.BadGateway, "couldn't load manga")
            else call.respond(detail)
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

        get("/api/history") {
            val items = withContext(Dispatchers.IO) {
                HistoryStore.list().map {
                    HistoryDto(it.sourceId.toString(), it.mangaUrl, it.mangaTitle, it.thumbnailUrl, it.chapterUrl, it.chapterName, it.readAt)
                }
            }
            call.respond(items)
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
            val bytes = withContext(Dispatchers.IO) { SourceImage.coverBytes(id, url) }
            if (bytes == null) call.respond(HttpStatusCode.NotFound) else call.respondBytes(bytes, sniffImageType(bytes))
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
            if (bytes == null) call.respond(HttpStatusCode.NotFound) else call.respondBytes(bytes, sniffImageType(bytes))
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
        }.getOrNull()
    }
    if (result == null) call.respond(HttpStatusCode.BadGateway, "source error") else call.respond(result)
}
