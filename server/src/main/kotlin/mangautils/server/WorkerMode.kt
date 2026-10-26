package mangautils.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mangautils.core.convert.ImageFormat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Worker-node mode (WORKER=1) — the same manga-utils image booted as a headless IP-pool fetcher instead
 * of the web server. It runs ONE ban-prone step for the main server: fetch a chapter's page images from
 * THIS node's IP, verify each is an image, and hold the bytes (with a SHA-256) until the main server pulls
 * and acks them. No library, no extensions, no web UI — just okhttp + ImageFormat, reused from :core.
 *
 * Lifecycle (async, so one dispatch covers a whole chapter and survives a slow page):
 *   POST /worker/jobs            {pages:[{index,url,headers}]}      -> {id}
 *   GET  /worker/jobs/{id}       -> {state, pages:[{index,state,sha,size,status,error}]}
 *   GET  /worker/jobs/{id}/artifact/{index}  -> raw image bytes (+ X-Sha256)   main pulls + re-hashes
 *   POST /worker/jobs/{id}/ack   -> frees the held bytes
 *   GET  /worker/health          -> version, egressIp, inflight/idle, metrics{cpu,mem,temp,disk,net,load}
 *
 * All worker routes require `Authorization: Bearer <WORKER_TOKEN>` when WORKER_TOKEN is set (keeps a
 * stray tailnet host from using this node as an open proxy). Bind is tailnet-only in practice.
 */
object WorkerMode {
    private val log = LoggerFactory.getLogger(javaClass)
    private const val VERSION = "0.1.0"
    private val token = System.getenv("WORKER_TOKEN").orEmpty()
    private val started = System.currentTimeMillis()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    // Bounded fetch pool: a worker is one IP, so a handful of concurrent GETs is plenty and keeps us from
    // hammering a single source edge. ponytail: fixed size; make it an env knob only if a node ever starves.
    private val pool = Executors.newFixedThreadPool(6)

    @Serializable data class PageReq(val index: Int, val url: String, val headers: Map<String, String> = emptyMap())
    @Serializable data class JobReq(val pages: List<PageReq>)
    @Serializable data class JobCreated(val id: String, val pages: Int)
    @Serializable data class PageStatus(
        val index: Int, val state: String, val sha: String? = null, val size: Int = 0,
        val status: Int = 0, val contentType: String? = null, val error: String? = null,
    )
    @Serializable data class JobStatus(val id: String, val state: String, val pages: List<PageStatus>)
    @Serializable data class Metrics(
        val cpuPct: Double? = null, val memPct: Double? = null, val tempC: Double? = null,
        val diskFreeGb: Double? = null, val diskPct: Double? = null,
        val netRxBytes: Long? = null, val netTxBytes: Long? = null, val load1: Double? = null,
    )
    @Serializable data class Health(
        val version: String, val uptimeS: Long, val egressIp: String,
        val inflight: Int, val idle: Boolean, val metrics: Metrics,
    )
    @Serializable data class ErrDto(val error: String)

    private class Page(val req: PageReq) {
        @Volatile var state = "pending" // pending | ok | failed
        @Volatile var sha: String? = null
        @Volatile var bytes: ByteArray? = null
        @Volatile var size = 0
        @Volatile var status = 0
        @Volatile var contentType: String? = null
        @Volatile var error: String? = null
    }
    private class Job(val id: String, pageReqs: List<PageReq>) {
        val pages = pageReqs.map { Page(it) }
        @Volatile var state = "running" // running | done
        val createdAt = System.currentTimeMillis()
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    private var jobSeq = 0L
    @Volatile private var egressIp: String? = null

    fun run() {
        val port = System.getenv("WORKER_PORT")?.toIntOrNull() ?: 8790
        log.info("Starting manga-utils WORKER node on 0.0.0.0:{} (auth={})", port, if (token.isNotEmpty()) "on" else "off")
        Thread { reapOldJobs() }.apply { isDaemon = true; name = "worker-reaper" }.start()
        embeddedServer(Netty, port = port, host = "0.0.0.0") { module() }.start(wait = false)
        log.info("worker ready; egress IP = {}", egress())
        java.util.concurrent.CountDownLatch(1).await()
    }

    private fun Application.module() {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
        routing {
            get("/worker/health") {
                if (!call.authed()) return@get call.respond(HttpStatusCode.Unauthorized, ErrDto("unauthorized"))
                val inflight = jobs.values.count { it.state == "running" }
                call.respond(Health(VERSION, (System.currentTimeMillis() - started) / 1000, egress(), inflight, inflight == 0, metrics()))
            }
            post("/worker/jobs") {
                if (!call.authed()) return@post call.respond(HttpStatusCode.Unauthorized, ErrDto("unauthorized"))
                val req = runCatching { call.receive<JobReq>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrDto("bad job body"))
                if (req.pages.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrDto("no pages"))
                val id = synchronized(this@WorkerMode) { "w${++jobSeq}-${System.currentTimeMillis().toString(36)}" }
                val job = Job(id, req.pages)
                jobs[id] = job
                job.pages.forEach { p -> pool.submit { fetch(p) }.also { /* fire-and-track via page.state */ } }
                // Flip to done once every page settled — cheap watcher thread per job (short-lived).
                Thread { awaitDone(job) }.apply { isDaemon = true; name = "worker-job-$id" }.start()
                call.respond(HttpStatusCode.Accepted, JobCreated(id, req.pages.size))
            }
            get("/worker/jobs/{id}") {
                if (!call.authed()) return@get call.respond(HttpStatusCode.Unauthorized, ErrDto("unauthorized"))
                val job = jobs[call.parameters["id"]] ?: return@get call.respond(HttpStatusCode.NotFound, ErrDto("no such job"))
                call.respond(JobStatus(job.id, job.state, job.pages.map {
                    PageStatus(it.req.index, it.state, it.sha, it.size, it.status, it.contentType, it.error)
                }))
            }
            get("/worker/jobs/{id}/artifact/{index}") {
                if (!call.authed()) return@get call.respond(HttpStatusCode.Unauthorized, ErrDto("unauthorized"))
                val job = jobs[call.parameters["id"]] ?: return@get call.respond(HttpStatusCode.NotFound, ErrDto("no such job"))
                val idx = call.parameters["index"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, ErrDto("bad index"))
                val page = job.pages.firstOrNull { it.req.index == idx } ?: return@get call.respond(HttpStatusCode.NotFound, ErrDto("no such page"))
                val data = page.bytes
                if (page.state != "ok" || data == null) return@get call.respond(HttpStatusCode.Conflict, ErrDto("page not ready: ${page.state}"))
                call.respondBytes(data, ContentType.parse(page.contentType?.ifBlank { null } ?: "application/octet-stream"), HttpStatusCode.OK)
            }
            post("/worker/jobs/{id}/ack") {
                if (!call.authed()) return@post call.respond(HttpStatusCode.Unauthorized, ErrDto("unauthorized"))
                val removed = jobs.remove(call.parameters["id"])
                removed?.pages?.forEach { it.bytes = null } // free held image bytes immediately
                if (removed == null) call.respond(HttpStatusCode.NotFound, ErrDto("no such job")) else call.respond(HttpStatusCode.OK)
            }
        }
    }

    private fun ApplicationCall.authed(): Boolean {
        if (token.isEmpty()) return true
        val h = request.headers["Authorization"] ?: return false
        return h.startsWith("Bearer ") && h.removePrefix("Bearer ").trim() == token
    }

    /** Fetch one page from this node's IP, validate it's an image, hold the bytes + SHA-256. */
    private fun fetch(p: Page) {
        try {
            val rb = Request.Builder().url(p.req.url)
            p.req.headers.forEach { (k, v) -> rb.header(k, v) }
            client.newCall(rb.build()).execute().use { resp ->
                p.status = resp.code
                p.contentType = resp.header("Content-Type")
                val data = resp.body?.bytes() ?: ByteArray(0)
                if (!resp.isSuccessful) { p.state = "failed"; p.error = "HTTP ${resp.code}"; p.size = data.size; return }
                if (!ImageFormat.looksLikeImage(data)) { p.state = "failed"; p.error = "not an image (${data.size} bytes)"; p.size = data.size; return }
                p.bytes = data
                p.size = data.size
                p.sha = sha256(data)
                p.state = "ok"
            }
        } catch (e: Exception) {
            p.state = "failed"; p.error = e.message ?: e.toString()
        }
    }

    private fun awaitDone(job: Job) {
        while (job.pages.any { it.state == "pending" }) Thread.sleep(50)
        job.state = "done"
    }

    /** Drop jobs the main server never acked (crashed mid-pull) so held image bytes can't leak. */
    private fun reapOldJobs() {
        while (true) {
            Thread.sleep(60_000)
            val cutoff = System.currentTimeMillis() - 10 * 60_000
            jobs.entries.removeIf { (_, j) -> j.createdAt < cutoff }
        }
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun egress(): String {
        egressIp?.let { return it }
        for (u in listOf("https://api.ipify.org", "https://ifconfig.me/ip")) {
            runCatching {
                client.newCall(Request.Builder().url(u).build()).execute().use { r ->
                    val t = r.body?.string()?.trim().orEmpty()
                    if (r.isSuccessful && t.isNotEmpty()) { egressIp = t; return t }
                }
            }
        }
        egressIp = "" // cache the miss; don't re-probe every health poll
        return ""
    }

    // ---- metrics: Linux pseudo-files; each probe guarded so a non-Linux box just reports null ----------
    private fun read(path: String): String? = runCatching { java.io.File(path).readText() }.getOrNull()

    private fun metrics(): Metrics = Metrics(
        cpuPct = cpuPct(), memPct = memPct(), tempC = tempC(),
        diskFreeGb = diskFreeGb(), diskPct = diskPct(),
        netRxBytes = net()?.first, netTxBytes = net()?.second, load1 = load1(),
    )

    private fun cpuSnap(): Pair<Long, Long>? {
        val first = read("/proc/stat")?.lineSequence()?.firstOrNull { it.startsWith("cpu ") } ?: return null
        val v = first.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (v.size < 4) return null
        val idle = v[3] + (v.getOrElse(4) { 0 })
        return v.sum() to idle
    }

    private fun cpuPct(): Double? {
        val a = cpuSnap() ?: return null
        Thread.sleep(200)
        val b = cpuSnap() ?: return null
        val dt = b.first - a.first; val di = b.second - a.second
        return if (dt > 0) ((dt - di) * 1000 / dt) / 10.0 else null
    }

    private fun memPct(): Double? {
        val txt = read("/proc/meminfo") ?: return null
        val kv = txt.lineSequence().mapNotNull {
            val p = it.split(Regex("\\s+")); if (p.size >= 2) p[0].removeSuffix(":") to (p[1].toLongOrNull() ?: 0) else null
        }.toMap()
        val total = kv["MemTotal"] ?: return null
        val avail = kv["MemAvailable"] ?: return null
        return if (total > 0) ((total - avail) * 1000 / total) / 10.0 else null
    }

    private fun tempC(): Double? {
        var best: Double? = null
        for (i in 0 until 12) {
            val v = read("/sys/class/thermal/thermal_zone$i/temp")?.trim()?.toLongOrNull() ?: continue
            val c = v / 1000.0
            best = if (best == null) c else maxOf(best!!, c)
        }
        return best?.let { (it * 10).toLong() / 10.0 }
    }

    private fun diskFreeGb(): Double? = runCatching {
        val s = java.nio.file.FileSystems.getDefault().getPath("/").let { java.nio.file.Files.getFileStore(it) }
        (s.usableSpace / 1e8).toLong() / 10.0
    }.getOrNull()

    private fun diskPct(): Double? = runCatching {
        val store = java.nio.file.Files.getFileStore(java.nio.file.FileSystems.getDefault().getPath("/"))
        val total = store.totalSpace; val free = store.usableSpace
        if (total > 0) ((total - free) * 1000 / total) / 10.0 else null
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
}
