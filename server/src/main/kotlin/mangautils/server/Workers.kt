package mangautils.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mangautils.core.worker.WorkerNode
import mangautils.core.worker.WorkerStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Main-server view of the worker pool: poll each registered node's /worker/health for a live status
 * card (online, egress IP, metrics, in-flight jobs). Config lives in WorkerStore; this only reads.
 * The dispatch client (routing downloadPage's image GET through a node) is the next step on top of this.
 */
object Workers {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    @Serializable
    data class WorkerStatus(
        val name: String,
        val url: String,
        val enabled: Boolean,
        val online: Boolean,
        val egressIp: String? = null,
        val inflight: Int? = null,
        val metrics: WorkerMode.Metrics? = null,
        val version: String? = null,
        val uptimeS: Long? = null,
        val error: String? = null,
        val checking: Boolean = false, // never heard from yet (grey dot) - distinct from confirmed offline
    )

    @Serializable data class AddReq(val name: String, val url: String, val token: String = "")
    @Serializable data class TestReq(val url: String, val token: String = "")

    /** GET {url}/worker/health with the node's bearer token. Null url/token errors become an offline card. */
    private fun poll(node: WorkerNode): WorkerStatus {
        val base = node.url.trimEnd('/')
        return try {
            val rb = Request.Builder().url("$base/worker/health")
            if (node.token.isNotBlank()) rb.header("Authorization", "Bearer ${node.token}")
            client.newCall(rb.build()).execute().use { resp ->
                val bodyText = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return WorkerStatus(node.name, base, node.enabled, false,
                        error = if (resp.code == 401) "unauthorized (check token)" else "HTTP ${resp.code}")
                }
                val h = json.decodeFromString<WorkerMode.Health>(bodyText)
                WorkerStatus(node.name, base, node.enabled, true, h.egressIp, h.inflight, h.metrics, h.version, h.uptimeS)
            }
        } catch (e: Exception) {
            WorkerStatus(node.name, base, node.enabled, false, error = e.message ?: "unreachable")
        }
    }

    /** Poll every registered node in parallel (used by Add/Test, where an immediate live probe is wanted). */
    suspend fun statuses(nodes: List<WorkerNode>): List<WorkerStatus> = withContext(Dispatchers.IO) {
        coroutineScope { nodes.map { async { poll(it) } }.awaitAll() }
    }

    // ---- Status cache: the Worker nodes screen reads THIS (instant, no live probe), so an offline node never
    // costs a connection timeout on the request path. Filled two ways: worker heartbeats (/checkin, fast) and
    // a background poll (fallback for a node that isn't heartbeating). ----
    private class Cached(val at: Long, val status: WorkerStatus)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()
    private const val FRESH_MS = 50_000L // online if we heard from the node within this window (heartbeat ~20s)

    /** Build the UI cards from the cache only - instant. Fresh entry -> its status; stale -> offline; never
     *  heard from -> "checking" (grey dot) until the first heartbeat/poll lands. */
    fun cachedStatuses(nodes: List<WorkerNode>): List<WorkerStatus> {
        val now = System.currentTimeMillis()
        return nodes.map { n ->
            val c = cache[n.name]
            when {
                c == null -> WorkerStatus(n.name, n.url.trimEnd('/'), n.enabled, online = false, checking = true)
                now - c.at <= FRESH_MS -> c.status.copy(name = n.name, url = n.url.trimEnd('/'), enabled = n.enabled)
                else -> WorkerStatus(n.name, n.url.trimEnd('/'), n.enabled, online = false, egressIp = c.status.egressIp, error = "no response")
            }
        }
    }

    /** Seed a node's cached status (right after Add, so the new card isn't "checking" for a tick). */
    fun seed(status: WorkerStatus) { cache[status.name] = Cached(System.currentTimeMillis(), status) }

    // ---- Up/down alerter: fire a Discord message on each node's online/down/recovered edge (gated in Notifier
    // by the webhook + "worker health" toggle). State is tracked ALWAYS, so enabling alerts never dumps a backlog.
    private const val DOWN_ALERT_MS = 90_000L // silent this long before a node counts as "down" (skips a restart)
    private val lastUp = java.util.concurrent.ConcurrentHashMap<String, Long>()               // last time online
    private val lastHealth = java.util.concurrent.ConcurrentHashMap<String, WorkerStatus>()   // last ONLINE status (for the msg)
    private class AlertState(var up: Boolean, var downSince: Long, var downAlerted: Boolean)
    private val alertState = java.util.concurrent.ConcurrentHashMap<String, AlertState>()

    /** Compare each node's current up/down (by whether we heard from it within DOWN_ALERT_MS) to its last state
     *  and fire one alert per edge. online on first-seen-up; down on up→silent; recovered on down→up (w/ downtime). */
    private fun evaluateAlerts() {
        val now = System.currentTimeMillis()
        val names = HashSet<String>()
        for (n in runCatching { WorkerStore.list() }.getOrDefault(emptyList())) {
            if (!n.enabled) { alertState.remove(n.name); continue }
            names.add(n.name)
            val up = lastUp[n.name]?.let { now - it <= DOWN_ALERT_MS } ?: false
            val prev = alertState[n.name]
            when {
                prev == null -> { alertState[n.name] = AlertState(up, now, false); if (up) fireWorker(n, "online", null) }
                prev.up && !up -> { prev.up = false; prev.downSince = lastUp[n.name] ?: now; prev.downAlerted = true; fireWorker(n, "down", null) }
                !prev.up && up -> { prev.up = true; if (prev.downAlerted) fireWorker(n, "recovered", now - prev.downSince) else fireWorker(n, "online", null); prev.downAlerted = false }
                else -> {}
            }
        }
        alertState.keys.retainAll(names) // forget removed nodes
    }

    private fun fireWorker(n: WorkerNode, kind: String, downMs: Long?) {
        val h = lastHealth[n.name]
        val bits = buildList {
            h?.egressIp?.takeIf { it.isNotBlank() }?.let { add("egress $it") }
            h?.version?.let { add("v$it") }
            val m = h?.metrics
            if (m?.memUsedGb != null && m.memTotalGb != null) add("RAM ${m.memUsedGb}/${m.memTotalGb} GB")
            if (kind == "down" && (h?.inflight ?: 0) > 0) add("${h?.inflight} download(s) were in flight - main re-runs them")
        }
        val tail = bits.joinToString(" · ")
        val detail = if (downMs != null) "Down for ${humanDur(downMs)}." + (if (tail.isNotEmpty()) " $tail" else "") else tail
        Notifier.onWorkerTransition(n.name, kind, detail)
    }

    private fun humanDur(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m" else "${s}s"
    }

    @Serializable data class CheckinDto(val token: String = "", val health: WorkerMode.Health? = null)

    /** A worker pushed a heartbeat. Match it to a registered node by the request's source IP (its tailscale
     *  address = the host in its registered URL) + token, then cache it online with the pushed health. */
    fun recordCheckin(remoteHost: String, dto: CheckinDto): Boolean {
        // Match by TOKEN (what the worker reliably knows + sends). Source IP is unreliable here: main runs
        // behind Docker's published-port NAT, so the container sees the docker gateway, not the worker's
        // tailscale IP. IP is only a tiebreaker when several nodes share a token, and the fallback for a
        // token-less node.
        val nodes = WorkerStore.list()
        val node = if (dto.token.isNotBlank()) {
            val byToken = nodes.filter { it.token == dto.token }
            byToken.firstOrNull { nodeHost(it.url) == remoteHost } ?: byToken.firstOrNull()
        } else {
            nodes.firstOrNull { it.token.isBlank() && nodeHost(it.url) == remoteHost }
        } ?: return false
        val h = dto.health
        val now = System.currentTimeMillis()
        val st = WorkerStatus(
            node.name, node.url.trimEnd('/'), node.enabled, online = true,
            egressIp = h?.egressIp, inflight = h?.inflight, metrics = h?.metrics, version = h?.version, uptimeS = h?.uptimeS,
        )
        cache[node.name] = Cached(now, st)
        lastUp[node.name] = now; lastHealth[node.name] = st
        return true
    }

    private fun nodeHost(url: String): String? = runCatching { java.net.URI(url.trim().trimEnd('/')).host }.getOrNull()

    /** Background refresher: poll nodes that AREN'T heartbeating and cache the result, off the request path so
     *  an offline node's timeout never blocks the UI. A node with a fresh heartbeat is skipped (push wins). */
    fun startPoller() {
        Thread {
            while (true) {
                runCatching {
                    val now = System.currentTimeMillis()
                    for (n in WorkerStore.list()) {
                        val c = cache[n.name]
                        if (c != null && c.status.online && now - c.at < 25_000L) continue // fresh heartbeat/poll
                        val st = poll(n); val t = System.currentTimeMillis()
                        cache[n.name] = Cached(t, st)
                        if (st.online) { lastUp[n.name] = t; lastHealth[n.name] = st }
                    }
                    evaluateAlerts()
                }
                Thread.sleep(15_000)
            }
        }.apply { isDaemon = true; name = "worker-poller" }.start()
    }

    /** Probe an unsaved node (the "Test" button when adding one). */
    suspend fun test(url: String, token: String): WorkerStatus = withContext(Dispatchers.IO) {
        poll(WorkerNode(name = "(test)", url = url, token = token))
    }

    /** Quick blocking liveness check for the dispatcher (reserveWorker). */
    fun onlineNow(node: WorkerNode): Boolean = poll(node).online

    // Exit-node control is proxied straight through: main forwards the worker's own response body to the UI.
    private val exitClient = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()

    fun exitNodes(node: WorkerNode): String? = runCatching {
        val rb = Request.Builder().url("${node.url.trimEnd('/')}/worker/exit-nodes")
        if (node.token.isNotBlank()) rb.header("Authorization", "Bearer ${node.token}")
        exitClient.newCall(rb.build()).execute().use { it.body?.string() }
    }.getOrNull()

    fun setExit(node: WorkerNode, exitNode: String): String? = runCatching {
        val body = Json.encodeToString(WorkerMode.SetExitReq(exitNode)).toRequestBody("application/json".toMediaType())
        val rb = Request.Builder().url("${node.url.trimEnd('/')}/worker/exit-node").post(body)
        if (node.token.isNotBlank()) rb.header("Authorization", "Bearer ${node.token}")
        exitClient.newCall(rb.build()).execute().use { it.body?.string() }
    }.getOrNull()

    @Serializable data class SyncResult(val synced: Int, val failed: Int, val total: Int, val errors: List<String> = emptyList())
    private val jarMedia = "application/java-archive".toMediaType()
    private val syncClient = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()

    /** Push main's real INSTALLED extensions to [node] so it can download the same sources, including
     *  sideloaded beta/custom jars that can't be re-fetched from a repo. Driven by InstalledStore (not a
     *  raw dir listing) so orphan jars left in the dir - ones not actually installed as a source on main,
     *  and which main never dispatches anyway - aren't pushed and don't show up as spurious failures. Each
     *  push carries the class metadata main enumerated, so even an APK-translated jar (no bundled manifest)
     *  installs on the worker. */
    fun syncExtensions(node: WorkerNode): SyncResult {
        val installed = runCatching { mangautils.core.extension.InstalledStore.list() }.getOrDefault(emptyList())
        var ok = 0; var bad = 0; val errs = ArrayList<String>()
        for (m in installed) {
            val jar = java.nio.file.Path.of(m.jarPath)
            if (!java.nio.file.Files.exists(jar)) { bad++; errs.add("${m.name}: jar missing on main (${jar.fileName})"); continue }
            // null = installed OK; else the worker's error message (or a transport failure) so the caller
            // sees WHY a jar was rejected, not just its name.
            val err = runCatching {
                val body = java.nio.file.Files.readAllBytes(jar).toRequestBody(jarMedia)
                val rb = Request.Builder().url("${node.url.trimEnd('/')}/worker/extensions").post(body)
                if (node.token.isNotBlank()) rb.header("Authorization", "Bearer ${node.token}")
                // Header values are ASCII (package/class/version); strip just in case so one odd char can't
                // throw and fail a jar that would otherwise install fine.
                fun hdr(k: String, v: String) { rb.header(k, v.filter { it.code in 0x20..0x7e }) }
                hdr("X-Ext-Pkg", m.pkg)
                hdr("X-Ext-Nsfw", if (m.nsfw) "1" else "0")
                hdr("X-Ext-Beta", if (m.beta) "1" else "0")
                hdr("X-Ext-Version", m.versionName)
                hdr("X-Ext-Code", m.versionCode.toString())
                hdr("X-Ext-Classes", m.classNames.joinToString(";"))
                syncClient.newCall(rb.build()).execute().use { r ->
                    if (r.isSuccessful) null else (r.body?.string()?.take(200)?.ifBlank { null } ?: "HTTP ${r.code}")
                }
            }.getOrElse { it.message ?: "push failed" }
            if (err == null) ok++ else { bad++; errs.add("${m.name}: $err") }
        }
        return SyncResult(ok, bad, installed.size, errs.take(10))
    }

    /** The extensions installed on a worker (proxied through from its /worker/extensions). */
    fun extensions(node: WorkerNode): String? = runCatching {
        val rb = Request.Builder().url("${node.url.trimEnd('/')}/worker/extensions")
        if (node.token.isNotBlank()) rb.header("Authorization", "Bearer ${node.token}")
        exitClient.newCall(rb.build()).execute().use { it.body?.string() }
    }.getOrNull()

    /** After main installs/updates one extension, push that jar to every enabled worker (background,
     *  best-effort) so the whole pool stays in lockstep with main. */
    fun propagateToWorkers(jarPath: String) {
        val file = java.nio.file.Path.of(jarPath)
        if (!java.nio.file.Files.exists(file)) return
        val workers = runCatching { WorkerStore.list().filter { it.enabled } }.getOrDefault(emptyList())
        if (workers.isEmpty()) return
        Thread {
            val bytes = runCatching { java.nio.file.Files.readAllBytes(file) }.getOrNull() ?: return@Thread
            for (node in workers) runCatching {
                if (!onlineNow(node)) return@runCatching
                val rb = Request.Builder().url("${node.url.trimEnd('/')}/worker/extensions").post(bytes.toRequestBody(jarMedia))
                if (node.token.isNotBlank()) rb.header("Authorization", "Bearer ${node.token}")
                syncClient.newCall(rb.build()).execute().use { it.isSuccessful }
            }
        }.apply { isDaemon = true; name = "ext-propagate" }.start()
    }
}
