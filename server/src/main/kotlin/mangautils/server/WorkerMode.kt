package mangautils.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import mangautils.core.config.SettingsStore
import mangautils.core.config.AppConfig
import mangautils.core.download.ChapterIdentity
import mangautils.core.download.ChapterSelect
import mangautils.core.download.DownloadManager
import mangautils.core.download.ExistingPolicy
import mangautils.core.download.SourceRef
import mangautils.core.util.Hashing
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Worker-node role. A worker is a FULL manga-utils image (extensions + solver + flaresolverr + its own
 * cf_clearance) that the main server delegates whole chapters to; it downloads them with its own session
 * and the main pulls the finished files back. Set with WORKER_TOKEN (enables these routes) + WORKER=1
 * (disables autonomous schedulers in main(), so the node only does delegated work).
 *
 * Routes (all require `Authorization: Bearer <WORKER_TOKEN>`):
 *   GET  /worker/health                               version, egressIp, metrics{cpu,mem,temp,disk,net,load}
 *   POST /worker/download  {source,manga,title,chapters,clientTaskId}  -> {id}
 *   GET  /worker/download/{id}                        per-chapter state (done chapters carry their folder)
 *   GET  /worker/download/{id}/manifest?chapter=F     files of one done chapter: [{path,size,sha}]
 *   GET  /worker/download/{id}/file?chapter=F&path=P  raw bytes of one file (path-sanitized)
 *   POST /worker/download/{id}/ack?chapter=F          main committed F -> delete it on the worker
 *   POST /worker/download/{id}/done                   drop the job record
 *   GET  /worker/jobs                                 [{id,clientTaskId,state}] for re-adoption after a main restart
 */
object WorkerMode {
    private val log = LoggerFactory.getLogger(javaClass)
    private const val VERSION = "0.2.0"
    private val token = System.getenv("WORKER_TOKEN").orEmpty()
    private val started = System.currentTimeMillis()

    /** Worker routes are live only when a token is configured (so a plain main server never exposes them). */
    fun enabled(): Boolean = token.isNotEmpty()

    // One download job at a time is plenty for a single node; a small pool lets a couple overlap if asked.
    private val pool = Executors.newFixedThreadPool(2)
    private val jobs = ConcurrentHashMap<String, DlJob>()
    private var seq = 0L
    @Volatile private var egressIp: String? = null
    private val httpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()

    // ---- DTOs ----
    @Serializable data class ChReq(val url: String, val name: String)
    @Serializable data class DownloadReqDto(val source: String, val manga: String, val title: String, val chapters: List<ChReq>, val clientTaskId: String = "")
    @Serializable data class ChapterStatusDto(val url: String, val name: String, val state: String, val folder: String? = null, val files: Int = 0, val error: String? = null)
    @Serializable data class JobStatusDto(val id: String, val clientTaskId: String, val state: String, val title: String, val chapters: List<ChapterStatusDto>)
    @Serializable data class FileEntry(val path: String, val size: Long, val sha: String)
    @Serializable data class ManifestDto(val chapter: String, val files: List<FileEntry>)
    @Serializable data class JobRow(val id: String, val clientTaskId: String, val state: String)
    @Serializable data class Created(val id: String)
    @Serializable data class Err(val error: String)
    @Serializable data class Metrics(
        val cpuPct: Double? = null, val memPct: Double? = null, val tempC: Double? = null,
        val diskFreeGb: Double? = null, val diskPct: Double? = null,
        val netRxBytes: Long? = null, val netTxBytes: Long? = null, val load1: Double? = null,
    )
    @Serializable data class Health(val version: String, val uptimeS: Long, val egressIp: String, val inflight: Int, val idle: Boolean, val metrics: Metrics)

    // ---- job model ----
    private class ChapterRec(val url: String, val name: String) {
        @Volatile var folder: String? = null
        @Volatile var error: String? = null
    }
    private class DlJob(val id: String, val clientTaskId: String, val sourceId: Long, val mangaUrl: String, val title: String, reqs: List<ChReq>) {
        val chapters = reqs.map { ChapterRec(it.url, it.name) }
        @Volatile var state = "running" // running | done | failed
        val createdAt = System.currentTimeMillis()
    }

    private fun start(req: DownloadReqDto): String {
        val id = synchronized(this) { "d${++seq}-${System.currentTimeMillis().toString(36)}" }
        val job = DlJob(id, req.clientTaskId, req.source.toLongOrNull() ?: 0L, req.manga, req.title, req.chapters)
        jobs[id] = job
        pool.submit { runJob(job) }
        return id
    }

    /** Download the job's chapters to this node's own disk via the normal DownloadManager (own clearance). */
    private fun runJob(job: DlJob) {
        try {
            val s = SettingsStore.get()
            val urls = job.chapters.map { it.url }.toSet()
            // Numbers let DownloadManager recover a chapter the source re-slugged (url changed since enqueue).
            val nums = job.chapters.mapNotNull { numFromName(it.name) }.toSet()
            val dm = DownloadManager(
                concurrency = s.downloadConcurrency,
                retries = s.downloadRetries,
                existingPolicy = ExistingPolicy.SKIP,
                downloadAsCbz = false, // folders, so the main harvests per page file + ComicInfo
            )
            dm.download(SourceRef(job.sourceId, job.mangaUrl), select = ChapterSelect.Urls(urls, nums), titleOverride = job.title)
            // Resolve each requested chapter to its on-disk folder (or mark failed). This also catches a
            // re-slugged chapter by its unique number when the url no longer matches.
            resolveFolders(job)
            job.state = "done"
        } catch (e: Exception) {
            log.warn("worker download job {} failed: {}", job.id, e.message)
            runCatching { resolveFolders(job) }
            job.state = "failed"
        }
    }

    private fun resolveFolders(job: DlJob) {
        val have = runCatching { ChapterIdentity.versionsOf(job.title).filter { it.complete } }.getOrDefault(emptyList())
        for (c in job.chapters) {
            if (c.folder != null) continue
            val v = have.firstOrNull { it.url == c.url }
                ?: numFromName(c.name)?.let { n ->
                    have.filter { it.number == n }.takeIf { it.size == 1 }?.first() // unique-number fallback
                }
            if (v?.folder != null) c.folder = v.folder else c.error = c.error ?: "not downloaded"
        }
    }

    // ---- disk access (title + folder resolved to a safe absolute path under the downloads dir) ----
    private fun seriesDir(title: String): Path = AppConfig.downloadsDir.resolve(DownloadManager.sanitize(title))

    private fun chapterDir(job: DlJob, folder: String): Path? {
        // Only a folder this job actually produced is reachable, and it must be a single path segment.
        if (folder.isBlank() || folder.contains('/') || folder.contains('\\') || folder.contains("..")) return null
        if (job.chapters.none { it.folder == folder }) return null
        val dir = seriesDir(job.title).resolve(folder).normalize()
        if (!dir.startsWith(seriesDir(job.title)) || !Files.isDirectory(dir)) return null
        return dir
    }

    private fun manifest(job: DlJob, folder: String): ManifestDto? {
        val dir = chapterDir(job, folder) ?: return null
        val files = Files.list(dir).use { st ->
            st.filter { Files.isRegularFile(it) }.toList().map { p ->
                FileEntry(p.fileName.toString(), runCatching { Files.size(p) }.getOrDefault(0L), Hashing.hashFile(p))
            }
        }
        return ManifestDto(folder, files.sortedBy { it.path })
    }

    private fun fileBytes(job: DlJob, folder: String, name: String): ByteArray? {
        val dir = chapterDir(job, folder) ?: return null
        if (name.isBlank() || name.contains('/') || name.contains('\\') || name.contains("..")) return null
        val f = dir.resolve(name).normalize()
        if (!f.startsWith(dir) || !Files.isRegularFile(f)) return null
        return Files.readAllBytes(f)
    }

    private fun ackChapter(job: DlJob, folder: String): Boolean {
        val dir = chapterDir(job, folder) ?: return false
        return runCatching { Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach { p -> Files.deleteIfExists(p) } }; true }.getOrDefault(false)
    }

    private fun status(job: DlJob): JobStatusDto = JobStatusDto(
        job.id, job.clientTaskId, job.state, job.title,
        job.chapters.map { c ->
            val state = when {
                c.folder != null -> "done"
                job.state == "running" -> "pending"
                else -> "failed"
            }
            ChapterStatusDto(c.url, c.name, state, c.folder, 0, c.error)
        },
    )

    // ---- routes (mounted by Application.module when enabled()) ----
    fun Route.workerRoutes() {
        get("/worker/health") {
            if (!call.authed()) return@get call.respond(HttpStatusCode.Unauthorized, Err("unauthorized"))
            val inflight = jobs.values.count { it.state == "running" }
            call.respond(Health(VERSION, (System.currentTimeMillis() - started) / 1000, egress(), inflight, inflight == 0, metrics()))
        }
        get("/worker/jobs") {
            if (!call.authed()) return@get call.respond(HttpStatusCode.Unauthorized, Err("unauthorized"))
            call.respond(jobs.values.map { JobRow(it.id, it.clientTaskId, it.state) })
        }
        post("/worker/download") {
            if (!call.authed()) return@post call.respond(HttpStatusCode.Unauthorized, Err("unauthorized"))
            val req = runCatching { call.receive<DownloadReqDto>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, Err("bad body"))
            if (req.chapters.isEmpty() || req.manga.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, Err("no chapters"))
            call.respond(HttpStatusCode.Accepted, Created(start(req)))
        }
        get("/worker/download/{id}") {
            if (!call.authed()) return@get call.respond(HttpStatusCode.Unauthorized, Err("unauthorized"))
            val job = jobs[call.parameters["id"]] ?: return@get call.respond(HttpStatusCode.NotFound, Err("no such job"))
            call.respond(status(job))
        }
        get("/worker/download/{id}/manifest") {
            if (!call.authed()) return@get call.respond(HttpStatusCode.Unauthorized, Err("unauthorized"))
            val job = jobs[call.parameters["id"]] ?: return@get call.respond(HttpStatusCode.NotFound, Err("no such job"))
            val folder = call.request.queryParameters["chapter"] ?: return@get call.respond(HttpStatusCode.BadRequest, Err("missing chapter"))
            val m = manifest(job, folder) ?: return@get call.respond(HttpStatusCode.NotFound, Err("no such chapter"))
            call.respond(m)
        }
        get("/worker/download/{id}/file") {
            if (!call.authed()) return@get call.respond(HttpStatusCode.Unauthorized, Err("unauthorized"))
            val job = jobs[call.parameters["id"]] ?: return@get call.respond(HttpStatusCode.NotFound, Err("no such job"))
            val folder = call.request.queryParameters["chapter"] ?: return@get call.respond(HttpStatusCode.BadRequest, Err("missing chapter"))
            val path = call.request.queryParameters["path"] ?: return@get call.respond(HttpStatusCode.BadRequest, Err("missing path"))
            val bytes = fileBytes(job, folder, path) ?: return@get call.respond(HttpStatusCode.NotFound, Err("no such file"))
            call.respondBytes(bytes, ContentType.Application.OctetStream, HttpStatusCode.OK)
        }
        post("/worker/download/{id}/ack") {
            if (!call.authed()) return@post call.respond(HttpStatusCode.Unauthorized, Err("unauthorized"))
            val job = jobs[call.parameters["id"]] ?: return@post call.respond(HttpStatusCode.NotFound, Err("no such job"))
            val folder = call.request.queryParameters["chapter"] ?: return@post call.respond(HttpStatusCode.BadRequest, Err("missing chapter"))
            if (ackChapter(job, folder)) call.respond(HttpStatusCode.OK) else call.respond(HttpStatusCode.NotFound, Err("no such chapter"))
        }
        post("/worker/download/{id}/done") {
            if (!call.authed()) return@post call.respond(HttpStatusCode.Unauthorized, Err("unauthorized"))
            jobs.remove(call.parameters["id"])
            call.respond(HttpStatusCode.OK)
        }
    }

    private fun ApplicationCall.authed(): Boolean {
        if (token.isEmpty()) return false // routes shouldn't even mount, but never serve without a token
        val h = request.headers["Authorization"] ?: return false
        return h.startsWith("Bearer ") && h.removePrefix("Bearer ").trim() == token
    }

    private fun numFromName(name: String): Float? {
        val m = Regex("(?i)chapter\\s*([0-9]+(?:\\.[0-9]+)?)").find(name) ?: Regex("([0-9]+(?:\\.[0-9]+)?)").find(name)
        return m?.groupValues?.getOrNull(1)?.toFloatOrNull()
    }

    // ---- health metrics: Linux pseudo-files; each probe guarded so a non-Linux box reports null ----
    private fun read(path: String): String? = runCatching { java.io.File(path).readText() }.getOrNull()

    private fun metrics(): Metrics {
        val n = net()
        return Metrics(cpuPct(), memPct(), tempC(), diskFreeGb(), diskPct(), n?.first, n?.second, load1())
    }
    private fun cpuSnap(): Pair<Long, Long>? {
        val first = read("/proc/stat")?.lineSequence()?.firstOrNull { it.startsWith("cpu ") } ?: return null
        val v = first.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (v.size < 4) return null
        return v.sum() to (v[3] + v.getOrElse(4) { 0 })
    }
    private fun cpuPct(): Double? {
        val a = cpuSnap() ?: return null; Thread.sleep(200); val b = cpuSnap() ?: return null
        val dt = b.first - a.first; val di = b.second - a.second
        return if (dt > 0) ((dt - di) * 1000 / dt) / 10.0 else null
    }
    private fun memPct(): Double? {
        val kv = (read("/proc/meminfo") ?: return null).lineSequence().mapNotNull {
            val p = it.split(Regex("\\s+")); if (p.size >= 2) p[0].removeSuffix(":") to (p[1].toLongOrNull() ?: 0) else null
        }.toMap()
        val total = kv["MemTotal"] ?: return null; val avail = kv["MemAvailable"] ?: return null
        return if (total > 0) ((total - avail) * 1000 / total) / 10.0 else null
    }
    private fun tempC(): Double? {
        var best: Double? = null
        for (i in 0 until 12) {
            val v = read("/sys/class/thermal/thermal_zone$i/temp")?.trim()?.toLongOrNull() ?: continue
            val c = v / 1000.0; best = if (best == null) c else maxOf(best!!, c)
        }
        return best?.let { (it * 10).toLong() / 10.0 }
    }
    private fun diskFreeGb(): Double? = runCatching {
        (Files.getFileStore(java.nio.file.FileSystems.getDefault().getPath("/")).usableSpace / 1e8).toLong() / 10.0
    }.getOrNull()
    private fun diskPct(): Double? = runCatching {
        val s = Files.getFileStore(java.nio.file.FileSystems.getDefault().getPath("/"))
        if (s.totalSpace > 0) ((s.totalSpace - s.usableSpace) * 1000 / s.totalSpace) / 10.0 else null
    }.getOrNull()
    private fun net(): Pair<Long, Long>? {
        val txt = read("/proc/net/dev") ?: return null
        var rx = 0L; var tx = 0L
        for (ln in txt.lineSequence()) {
            val i = ln.indexOf(':'); if (i < 0) continue
            val name = ln.substring(0, i).trim(); if (name == "lo") continue
            val f = ln.substring(i + 1).trim().split(Regex("\\s+"))
            if (f.size >= 9) { rx += f[0].toLongOrNull() ?: 0; tx += f[8].toLongOrNull() ?: 0 }
        }
        return rx to tx
    }
    private fun load1(): Double? = read("/proc/loadavg")?.trim()?.split(" ")?.firstOrNull()?.toDoubleOrNull()

    private fun egress(): String {
        egressIp?.let { return it }
        for (u in listOf("https://api.ipify.org", "https://ifconfig.me/ip")) {
            runCatching {
                httpClient.newCall(Request.Builder().url(u).build()).execute().use { r ->
                    val t = r.body?.string()?.trim().orEmpty()
                    if (r.isSuccessful && t.isNotEmpty()) { egressIp = t; return t }
                }
            }
        }
        egressIp = ""; return ""
    }

    /** Drop jobs the main never finished pulling (main crashed) so held files don't pile up forever. */
    fun startReaper() {
        Thread {
            while (true) {
                Thread.sleep(300_000)
                val cutoff = System.currentTimeMillis() - 6 * 3600_000 // 6h: generous, main re-adopts by clientTaskId
                jobs.entries.removeIf { (_, j) -> j.state != "running" && j.createdAt < cutoff }
            }
        }.apply { isDaemon = true; name = "worker-job-reaper" }.start()
    }
}
