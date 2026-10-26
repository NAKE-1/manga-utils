package mangautils.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mangautils.core.extension.ExtensionInstaller
import mangautils.core.extension.InstalledStore
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import mangautils.core.config.SettingsStore
import mangautils.core.config.AppConfig
import mangautils.core.download.ChapterIdentity
import mangautils.core.download.ChapterSelect
import mangautils.core.download.DownloadListener
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
    @Volatile private var exitActive = false // an exit node we set is live — the watchdog guards it
    private val httpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()

    // ---- DTOs ----
    @Serializable data class ChReq(val url: String, val name: String)
    @Serializable data class DownloadReqDto(val source: String, val manga: String, val title: String, val chapters: List<ChReq>, val clientTaskId: String = "")
    @Serializable data class ChapterStatusDto(val url: String, val name: String, val state: String, val folder: String? = null, val files: Int = 0, val error: String? = null, val pagesDone: Int = 0, val pagesTotal: Int = 0)
    @Serializable data class JobStatusDto(val id: String, val clientTaskId: String, val state: String, val title: String, val chapters: List<ChapterStatusDto>, val curChapter: String = "", val curChapterUrl: String = "", val curPagesDone: Int = 0, val curPagesTotal: Int = 0, val bytesPerSec: Double = 0.0)
    @Serializable data class FileEntry(val path: String, val size: Long, val sha: String)
    @Serializable data class ManifestDto(val chapter: String, val files: List<FileEntry>)
    @Serializable data class JobRow(val id: String, val clientTaskId: String, val state: String)
    @Serializable data class Created(val id: String)
    @Serializable data class Err(val error: String)
    @Serializable data class Metrics(
        val cpuPct: Double? = null, val memPct: Double? = null, val tempC: Double? = null,
        val diskFreeGb: Double? = null, val diskTotalGb: Double? = null, val diskPct: Double? = null,
        val netRxBytes: Long? = null, val netTxBytes: Long? = null, val load1: Double? = null,
    )
    @Serializable data class Health(val version: String, val uptimeS: Long, val egressIp: String, val inflight: Int, val idle: Boolean, val metrics: Metrics)
    @Serializable data class ExitNode(val name: String, val ip: String, val online: Boolean, val current: Boolean)
    @Serializable data class ExitNodesDto(val available: Boolean, val current: String, val nodes: List<ExitNode>, val error: String? = null)
    @Serializable data class SetExitReq(val node: String = "") // ip or MagicDNS name; "" = none (normal egress)
    @Serializable data class OkDto(val ok: Boolean, val error: String? = null)
    @Serializable data class ExtInfo(val pkg: String, val name: String, val version: String, val beta: Boolean, val sources: Int)

    // ---- job model ----
    private class ChapterRec(val url: String, val name: String) {
        @Volatile var folder: String? = null
        @Volatile var error: String? = null
        @Volatile var pagesDone: Int = 0
        @Volatile var pagesTotal: Int = 0
    }
    private class DlJob(val id: String, val clientTaskId: String, val sourceId: Long, val mangaUrl: String, val title: String, reqs: List<ChReq>) {
        val chapters = reqs.map { ChapterRec(it.url, it.name) }
        @Volatile var state = "running" // running | done | failed
        @Volatile var cancelled = false // main abandoned/stopped this job -> stop downloading (don't orphan-run)
        val createdAt = System.currentTimeMillis()
        // Job-level live progress (the chapter the worker is pulling right now + its speed). Main surfaces
        // these onto the task, exactly like a local download, so the card shows pages + KB/s.
        @Volatile var curChapter = ""
        @Volatile var curChapterUrl = ""
        @Volatile var curPagesDone = 0
        @Volatile var curPagesTotal = 0
        @Volatile var curBytesPerSec = 0.0
        @Volatile var lastLogAt = 0L
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
        // Resolve finished chapters' folders on a side thread (~every 1.5s) so main harvests incrementally
        // and the done-count climbs live. Kept OFF the download hot path on purpose: resolveFolders scans
        // every finished chapter via ChapterIdentity, and running that inside the progress listener (which
        // fires under the DownloadManager lock) serialized every concurrent page write behind a growing
        // disk scan - a worker-specific drag that got worse with each chapter landed.
        val resolver = Thread {
            try { while (job.state == "running") { Thread.sleep(1500); synchronized(job) { runCatching { resolveFolders(job) } } } }
            catch (_: InterruptedException) { /* job ended */ }
        }.apply { isDaemon = true; name = "wjob-resolve-${job.id}" }
        resolver.start()
        try {
            val s = SettingsStore.get()
            val urls = job.chapters.map { it.url }.toSet()
            // Numbers let DownloadManager recover a chapter the source re-slugged (url changed since enqueue).
            val nums = job.chapters.mapNotNull { numFromName(it.name) }.toSet()
            val byUrl = job.chapters.associateBy { it.url }
            // Live progress: record the active chapter's pages + speed on the job (main surfaces these onto
            // the task) and log a clean, throttled line prefixed with the job id - so the worker console
            // reads like main ("[d2-xxx] DOWNLOAD Chapter 20 - 5/39 pages - 480 KB/s") instead of being
            // silent behind Ktor's per-request access log. The listener is cheap: no disk I/O, no lock.
            val listener = DownloadListener { p ->
                job.curChapter = p.chapter; job.curChapterUrl = p.chapterUrl
                job.curPagesDone = p.pagesDone; job.curPagesTotal = p.pagesTotal; job.curBytesPerSec = p.bytesPerSecond
                byUrl[p.chapterUrl]?.let { it.pagesDone = p.pagesDone; it.pagesTotal = p.pagesTotal }
                val now = System.currentTimeMillis()
                if (p.finished || p.pagesDone <= 1 || now - job.lastLogAt > 800) {
                    job.lastLogAt = now
                    val sp = if (p.bytesPerSecond > 0) " - ${(p.bytesPerSecond / 1024).toInt()} KB/s" else ""
                    log.info("[{}] DOWNLOAD {} - {}/{} pages{}", job.id, p.chapter, p.pagesDone, p.pagesTotal, sp)
                }
            }
            val dm = DownloadManager(
                concurrency = s.downloadConcurrency,
                retries = s.downloadRetries,
                listener = listener,
                existingPolicy = ExistingPolicy.SKIP,
                downloadAsCbz = false, // folders, so the main harvests per page file + ComicInfo
                cancelled = { job.cancelled }, // main dropped the job (/done) -> abort, don't orphan-run it
            )
            log.info("worker job {} START: {} chapter(s) of '{}'", job.id, job.chapters.size, job.title)
            dm.download(SourceRef(job.sourceId, job.mangaUrl), select = ChapterSelect.Urls(urls, nums), titleOverride = job.title)
            // Final resolve: catch the last chapter(s) and any re-slugged one (unique-number fallback).
            synchronized(job) { resolveFolders(job) }
            job.state = "done"
            val ready = job.chapters.count { it.folder != null }
            log.info("worker job {} DONE: {}/{} chapter(s) ready for main to pull", job.id, ready, job.chapters.size)
        } catch (e: Exception) {
            if (job.cancelled) log.info("worker job {} cancelled by main - stopping", job.id)
            else { log.warn("worker job {} FAILED: {}", job.id, e.message); runCatching { resolveFolders(job) } }
            job.state = "failed"
        } finally {
            resolver.interrupt()
            // Main dropped this job (Stop/Remove/abandon): discard its leftover download data so partial
            // chapters don't pile up on the node. On a clean finish everything was acked + deleted already.
            if (job.cancelled) runCatching { purgeJob(job) }
        }
    }

    private fun resolveFolders(job: DlJob) {
        val have = runCatching { ChapterIdentity.versionsOf(job.title).filter { it.complete } }.getOrDefault(emptyList())
        // Folders already resolved to a chapter in this job, so NO two chapters can ever map to the same one.
        // An aggregated source (Atsumaru) lists many same-number scanlation versions; the unique-number
        // fallback used to assign the one complete "Chapter 1" folder to ALL unresolved number-1 chapters,
        // and main then harvested that single folder over and over - it deletes on ack, so every repeat
        // 404'd and main logged "failed to harvest; will retry locally" in a tight loop.
        val claimed = job.chapters.mapNotNullTo(HashSet()) { it.folder }
        for (c in job.chapters) {
            if (c.folder != null) continue
            val v = have.firstOrNull { it.url == c.url && it.folder != null && it.folder !in claimed }
                ?: numFromName(c.name)?.let { n ->
                    have.filter { it.number == n && it.folder != null && it.folder !in claimed }.takeIf { it.size == 1 }?.first()
                }
            if (v?.folder != null) { c.folder = v.folder; claimed.add(v.folder!!) } else c.error = c.error ?: "not downloaded"
        }
    }

    private fun deleteDir(dir: Path) {
        if (!Files.exists(dir)) return
        runCatching { Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach { p -> Files.deleteIfExists(p) } } }
    }

    /** Discard a dropped job's leftover download data (un-acked + partial chapters). Acked chapters were
     *  already deleted on ship-back. Wipes the whole series dir when no other running job shares the title
     *  (a node holds only transient download data, never a library); otherwise just this job's own folders. */
    private fun purgeJob(job: DlJob) {
        val base = seriesDir(job.title)
        val sharedByOther = jobs.values.any { it !== job && it.title == job.title && it.state == "running" }
        if (!sharedByOther) deleteDir(base)
        else job.chapters.mapNotNull { it.folder }.distinct().forEach { f ->
            val d = runCatching { base.resolve(f).normalize() }.getOrNull()
            if (d != null && d.startsWith(base)) deleteDir(d)
        }
    }

    /** A worker holds only transient delegated-download data (no library). After a crash/restart its
     *  in-memory jobs are gone, so any files left under the downloads dir are orphaned partials: wipe them
     *  at boot so a crashed job can't leak disk or feed a stale half-chapter into a later harvest. */
    fun cleanDownloadsAtBoot() {
        val root = AppConfig.downloadsDir
        runCatching { if (Files.isDirectory(root)) Files.list(root).use { s -> s.forEach { deleteDir(it) } } }
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
            ChapterStatusDto(c.url, c.name, state, c.folder, 0, c.error, c.pagesDone, c.pagesTotal)
        },
        job.curChapter, job.curChapterUrl, job.curPagesDone, job.curPagesTotal, job.curBytesPerSec,
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
            if (ackChapter(job, folder)) {
                // Not silent any more: the ack means main verified + committed this chapter, so we free it here.
                log.info("worker job {} -> shipped '{}' to main (committed, freed locally)", job.id, folder)
                call.respond(HttpStatusCode.OK)
            } else call.respond(HttpStatusCode.NotFound, Err("no such chapter"))
        }
        post("/worker/download/{id}/done") {
            if (!call.authed()) return@post call.respond(HttpStatusCode.Unauthorized, Err("unauthorized"))
            // Cancel the download too (not just drop the record), so a job main abandoned stops instead of
            // orphan-running the whole series on this node.
            call.parameters["id"]?.let { id -> jobs[id]?.let { it.cancelled = true }; jobs.remove(id) }
            call.respond(HttpStatusCode.OK)
        }
        // List the extensions installed on this node (pkg, name, version, beta) so main can show them.
        get("/worker/extensions") {
            if (!call.authed()) return@get call.respond(HttpStatusCode.Unauthorized, Err("unauthorized"))
            val list = runCatching { InstalledStore.list() }.getOrDefault(emptyList())
                .map { ExtInfo(it.pkg, it.name, it.versionName, it.beta, it.sources.size) }.sortedBy { it.name.lowercase() }
            call.respond(list)
        }
        // Receive and install one extension jar pushed from main (so a worker gets main's EXACT set,
        // including sideloaded beta/custom jars that aren't in any public repo). Reuses installLocalJar.
        post("/worker/extensions") {
            if (!call.authed()) return@post call.respond(HttpStatusCode.Unauthorized, Err("unauthorized"))
            val bytes = withContext(Dispatchers.IO) { call.receiveStream().readBytes() }
            if (bytes.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, Err("empty jar"))
            // Optional metadata main sends with the jar so a worker can install even an APK-translated jar
            // that has no bundled manifest. Absent (older main) -> null -> install derives from the jar.
            val provided = call.request.headers["X-Ext-Pkg"]?.let { pkg ->
                val classes = call.request.headers["X-Ext-Classes"]?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                if (classes.isEmpty()) null
                else ExtensionInstaller.ProvidedMeta(
                    pkg = pkg,
                    nsfw = call.request.headers["X-Ext-Nsfw"] == "1",
                    beta = call.request.headers["X-Ext-Beta"] == "1",
                    versionName = call.request.headers["X-Ext-Version"].orEmpty(),
                    versionCode = call.request.headers["X-Ext-Code"]?.toLongOrNull() ?: 0L,
                    classNames = classes,
                )
            }
            val r = withContext(Dispatchers.IO) {
                val tmp = Files.createTempFile("wsync-", ".jar")
                try { Files.write(tmp, bytes); runCatching { ExtensionInstaller().installLocalJar(tmp, provided) } }
                finally { Files.deleteIfExists(tmp) }
            }
            r.fold(onSuccess = { call.respond(OkDto(true)) }, onFailure = { call.respond(HttpStatusCode.InternalServerError, Err(it.message ?: "install failed")) })
        }
        // Tailscale exit node: switch this node between normal egress and a chosen exit (or none).
        get("/worker/exit-nodes") {
            if (!call.authed()) return@get call.respond(HttpStatusCode.Unauthorized, Err("unauthorized"))
            call.respond(exitNodes())
        }
        post("/worker/exit-node") {
            if (!call.authed()) return@post call.respond(HttpStatusCode.Unauthorized, Err("unauthorized"))
            val req = runCatching { call.receive<SetExitReq>() }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, Err("bad body"))
            val err = setExitNode(req.node)
            egressIp = null // force a re-probe so /health reflects the new egress
            call.respond(OkDto(err == null, err))
        }
    }

    // ---- Tailscale exit-node control (shells the `tailscale` CLI on this box) ----
    @Serializable private class TsPeer(val HostName: String = "", val DNSName: String = "", val TailscaleIPs: List<String> = emptyList(), val ExitNodeOption: Boolean = false, val ExitNode: Boolean = false, val Online: Boolean = false)
    @Serializable private class TsStatus(val Peer: Map<String, TsPeer> = emptyMap())
    private val tsJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    // Mullvad exit nodes we allow: Florida + surrounding southeast states + New York. Keyed by Mullvad's
    // city code (from hostnames like "us-mia-wg-001") -> friendly label. Only codes Mullvad actually offers
    // appear (verified present on-tailnet: mia, atl, rag, nyc); the rest are future-proofing (shown if added).
    private val MULLVAD_CITIES = linkedMapOf(
        "mia" to "Miami, FL",
        "atl" to "Atlanta, GA",
        "bham" to "Birmingham, AL",
        "cae" to "Columbia, SC", "chs" to "Charleston, SC",
        "rag" to "Raleigh, NC", "clt" to "Charlotte, NC", "rdu" to "Raleigh, NC",
        "bna" to "Nashville, TN", "mem" to "Memphis, TN",
        "nyc" to "New York, NY",
    )
    private val mullvadCodeRe = Regex("""-([a-z]+)-wg""")

    private fun runCmd(vararg cmd: String): Pair<Int, String> = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 to "timed out" }
        p.exitValue() to out
    }.getOrElse { -1 to (it.message ?: "tailscale not available") }

    private fun exitNodes(): ExitNodesDto {
        val (code, out) = runCmd("tailscale", "status", "--json")
        if (code != 0) return ExitNodesDto(available = false, current = "", nodes = emptyList(), error = out.trim().take(200).ifBlank { "tailscale unavailable" })
        val st = runCatching { tsJson.decodeFromString<TsStatus>(out) }.getOrNull()
            ?: return ExitNodesDto(false, "", emptyList(), "could not parse tailscale status")
        val peers = st.Peer.values.filter { it.ExitNodeOption }
        val result = ArrayList<ExitNode>()
        // Your own (non-Mullvad) exit nodes: shown as-is, never filtered.
        peers.filter { "mullvad" !in (it.HostName + it.DNSName).lowercase() }.forEach { p ->
            val name = p.HostName.ifBlank { p.DNSName.substringBefore('.') }.ifBlank { p.TailscaleIPs.firstOrNull() ?: "?" }
            result.add(ExitNode(name, p.TailscaleIPs.firstOrNull().orEmpty(), p.Online, p.ExitNode))
        }
        // Mullvad: keep only allowed cities, and collapse each city's many servers to ONE pickable entry.
        val byCity = LinkedHashMap<String, MutableList<TsPeer>>()
        for (p in peers) {
            if ("mullvad" !in (p.HostName + p.DNSName).lowercase()) continue
            val c = mullvadCodeRe.find(p.HostName.lowercase())?.groupValues?.getOrNull(1) ?: continue
            if (c in MULLVAD_CITIES) byCity.getOrPut(c) { mutableListOf() }.add(p)
        }
        for ((c, servers) in byCity) {
            val rep = servers.firstOrNull { it.Online } ?: servers.first()
            result.add(ExitNode(MULLVAD_CITIES.getValue(c), rep.TailscaleIPs.firstOrNull().orEmpty(), servers.any { it.Online }, servers.any { it.ExitNode }))
        }
        val nodes = result.distinctBy { it.name }.sortedBy { it.name.lowercase() }
        return ExitNodesDto(available = true, current = nodes.firstOrNull { it.current }?.name.orEmpty(), nodes = nodes)
    }

    private fun applyExit(node: String): Pair<Int, String> =
        if (node.isBlank()) runCmd("tailscale", "set", "--exit-node=")
        else runCmd("tailscale", "set", "--exit-node=$node", "--exit-node-allow-lan-access")

    /**
     * Set the exit node by ip/name, or "" to clear (normal egress). SELF-REVERTING: after selecting an
     * exit node it verifies egress actually works, and if not, drops back to normal egress on its own - so
     * a dead/broken exit node can never strand the worker without internet. (Clearing is always safe.)
     */
    private fun setExitNode(node: String): String? {
        val (code, out) = applyExit(node)
        if (code != 0) return out.trim().take(200).ifBlank { "tailscale set failed" }
        if (node.isBlank()) { exitActive = false; return null } // back to normal egress - nothing to verify
        if (verifyEgress()) { exitActive = true; return null }
        // The exit node didn't give us a working path out. Revert to normal egress so downloads keep working.
        log.warn("exit node '{}' failed connectivity check - reverting to normal egress", node)
        applyExit(""); exitActive = false; egressIp = null
        return "exit node didn't connect - reverted to normal egress"
    }

    /** Can we reach the internet right now? Retries over ~15s (a fresh exit node takes a moment to come up). */
    private fun verifyEgress(): Boolean {
        val probe = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
        repeat(5) {
            val ok = runCatching {
                probe.newCall(Request.Builder().url("https://api.ipify.org").build()).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (ok) return true
            Thread.sleep(3000)
        }
        return false
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
        val n = net(); val d = disk()
        return Metrics(
            cpuPct = cpuPct(), memPct = memPct(), tempC = tempC(),
            diskFreeGb = d.first, diskTotalGb = d.second, diskPct = d.third,
            netRxBytes = n?.first, netTxBytes = n?.second, load1 = load1(),
        )
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
    /** Disk free/total/used% for the filesystem that actually holds downloads (/data), not "/" - that's the
     *  one that fills up, and once a worker's /data sits on an SSD (2230/2280) this follows it. Falls back to
     *  "/" if the downloads dir can't be resolved yet. (free, totalGb, usedPct), all rounded to 0.1 GB/%. */
    private fun disk(): Triple<Double?, Double?, Double?> {
        val s = runCatching { Files.getFileStore(AppConfig.downloadsDir) }
            .recoverCatching { Files.getFileStore(java.nio.file.FileSystems.getDefault().getPath("/")) }
            .getOrNull() ?: return Triple(null, null, null)
        val free = (s.usableSpace / 1e8).toLong() / 10.0
        val total = (s.totalSpace / 1e8).toLong() / 10.0
        val pct = if (s.totalSpace > 0) ((s.totalSpace - s.usableSpace) * 1000 / s.totalSpace) / 10.0 else null
        return Triple(free, total, pct)
    }
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
        // Exit-node watchdog: if an exit node we set loses internet for a sustained window, revert to normal
        // egress so the node can never get stuck with no working path out. Conservative (3 strikes / ~3 min)
        // so a transient blip doesn't drop a working exit. Tailnet comms with main bypass the exit anyway.
        Thread {
            var strikes = 0
            while (true) {
                Thread.sleep(60_000)
                if (!exitActive) { strikes = 0; continue }
                val ok = runCatching {
                    OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
                        .newCall(Request.Builder().url("https://api.ipify.org").build()).execute().use { it.isSuccessful }
                }.getOrDefault(false)
                if (ok) { strikes = 0; continue }
                if (++strikes >= 3) {
                    log.warn("exit node egress dead {} checks - reverting to normal egress", strikes)
                    applyExit(""); exitActive = false; egressIp = null; strikes = 0
                }
            }
        }.apply { isDaemon = true; name = "worker-exit-watchdog" }.start()
    }
}
