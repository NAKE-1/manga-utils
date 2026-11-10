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
/**
 * Recent per-worker chapter-shipment events (worker -> main harvests), so the fleet map can fire ONE moving
 * dot per actual chapter shipped instead of a steady loop. Each event has a monotonic seq; the UI spawns a
 * one-shot dot for every seq it hasn't shown yet. Kept small (last 60s / 30 events per worker).
 */
object ShipLog {
    private class Ev(val seq: Long, val sourceId: Long, val at: Long)
    private val byWorker = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<Ev>>()
    private val seq = java.util.concurrent.atomic.AtomicLong(0)

    fun record(worker: String, sourceId: Long) {
        val dq = byWorker.getOrPut(worker) { ArrayDeque() }
        val now = System.currentTimeMillis()
        synchronized(dq) {
            dq.addLast(Ev(seq.incrementAndGet(), sourceId, now))
            while (dq.isNotEmpty() && (dq.first().at < now - 60_000 || dq.size > 30)) dq.removeFirst()
        }
    }
    /** (seq, sourceId) pairs for a worker's recent shipments, oldest first. */
    fun recent(worker: String): List<Pair<Long, Long>> {
        val dq = byWorker[worker] ?: return emptyList()
        return synchronized(dq) { dq.map { it.seq to it.sourceId } }
    }
}

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
        val exitCity: String? = null,  // the worker's current Mullvad egress state code (for the fleet map)
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
                WorkerStatus(node.name, base, node.enabled, true, h.egressIp, h.inflight, h.metrics, h.version, h.uptimeS, exitCity = h.exitCity)
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
    private const val DOWN_ALERT_MS = 45_000L // silent this long before a node counts as "down" (skips a restart)
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
            exitCity = h?.exitCity,
        )
        cache[node.name] = Cached(now, st)
        lastUp[node.name] = now; lastHealth[node.name] = st
        UsageStore.record(node.name, h?.metrics)
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
                        if (st.online) { lastUp[n.name] = t; lastHealth[n.name] = st; UsageStore.record(n.name, st.metrics) }
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

    private val spreadJson = Json { ignoreUnknownKeys = true }

    @Serializable data class SpreadAssign(
        val worker: String, val city: String? = null, val label: String? = null,
        val ping: Int? = null, val applied: Boolean = false, val note: String? = null,
    )
    @Serializable data class SpreadResult(val assignments: List<SpreadAssign>, val message: String = "")

    // ---- Coordinated AUTO egress-spread + collision safety net ----
    @Volatile var egressAlert: String? = null
        private set
    private val collisionDisabled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // Workers whose user-chosen exit SETTING is "Auto" = let main coordinate a distinct state for them. The UI
    // keeps showing "Auto" for these even after main assigns an actual city (see the exit-nodes route override).
    private val autoWorkers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val autoFile get() = mangautils.core.config.AppConfig.dataDir.resolve("worker-auto.txt")
    /** Record the user's per-worker exit SETTING: on=true means "Auto" (let main manage a distinct exit). */
    fun markAuto(name: String, on: Boolean) {
        if (on) autoWorkers += name else autoWorkers -= name
        runCatching { java.nio.file.Files.writeString(autoFile, autoWorkers.joinToString("\n")) }
    }
    fun isAutoManaged(name: String) = name in autoWorkers
    private fun loadAuto() = runCatching { java.nio.file.Files.readAllLines(autoFile).filter { it.isNotBlank() }.forEach { autoWorkers += it } }
    private fun exitKey(w: WorkerStatus): String? = w.exitCity?.ifBlank { null } ?: w.egressIp?.ifBlank { null }

    // ---- Per-source egress restrictions ----
    // A Mullvad state a source blocks (403s its exit IP, proven by the same content downloading from another
    // IP). The coordinated auto-spread never assigns a restricted state, so a worker is never stranded on a
    // state that can't fetch a source. Durable + logged. Seeded with today's confirmed blocks on first run.
    private val restricted = java.util.concurrent.ConcurrentHashMap<Long, MutableSet<String>>() // sourceId -> state codes
    private val restrictedFile get() = mangautils.core.config.AppConfig.dataDir.resolve("worker-egress-restricted.txt")
    fun markRestricted(sourceId: Long, state: String) {
        if (restricted.getOrPut(sourceId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(state)) {
            System.err.println("[workers] egress-restricted: source $sourceId blocked on state '$state'")
            saveRestricted()
        }
    }
    /** Union of states restricted for ANY source - the auto-spread avoids all of these (workers are all-purpose). */
    fun restrictedStates(): Set<String> = restricted.values.flatten().toSet()
    fun restrictionsBySource(): Map<Long, List<String>> = restricted.mapValues { it.value.sorted() }
    private fun saveRestricted() = runCatching {
        java.nio.file.Files.writeString(restrictedFile, restricted.entries.joinToString("\n") { "${it.key}=${it.value.joinToString(",")}" })
    }
    private fun loadRestricted() = runCatching {
        java.nio.file.Files.readAllLines(restrictedFile).forEach { ln ->
            val k = ln.substringBefore('='); val v = ln.substringAfter('=', "")
            k.toLongOrNull()?.let { sid -> v.split(",").filter { it.isNotBlank() }.forEach { markRestricted(sid, it) } }
        }
    }
    private fun seedRestrictionsOnce() {
        // Confirmed blocks (egress-tested 2026-11-10) - always applied; markRestricted dedupes against the
        // durable file, so runtime-learned restrictions are additive, not overwritten.
        // Atsumaru CDN blocks the whole 23.234.x provider range: Atlanta, Raleigh, LA, Salt Lake (+ Kansas
        // City/Phoenix, which also fail MangaFire so they're left out of the pool entirely).
        listOf("atl", "rag", "lax", "slc").forEach { markRestricted(2327480808438768017L, it) }
        // MangaFire blocks Dallas (146.70.x) and Denver (37.19.210.x).
        listOf("dal", "den").forEach { markRestricted(6084907896154116083L, it) }
    }

    /** Coordinated AUTO: a 30s loop keeps every worker whose setting is "Auto" on its OWN distinct exit. If an
     *  auto worker shares a state/IP with any other worker, main re-runs spreadExits over just the auto workers
     *  (reserving the manually-pinned ones' cities) so no two share an egress IP. Too few states -> disable the
     *  extra auto ones + alert. Manually-pinned workers are never touched. */
    fun startAutoSpread() {
        loadAuto(); loadRestricted(); seedRestrictionsOnce()
        Thread {
            while (true) runCatching { Thread.sleep(30_000); kotlinx.coroutines.runBlocking { autoSpreadTick() } }
        }.apply { isDaemon = true; name = "worker-auto-spread" }.start()
    }
    /** Kick an immediate coordinated-auto pass (e.g. right after a worker is switched to Auto). */
    fun nudgeAutoSpread() = Thread { runCatching { kotlinx.coroutines.runBlocking { autoSpreadTick() } } }
        .apply { isDaemon = true; name = "auto-spread-nudge" }.start()
    private fun autoDupes(l: List<WorkerStatus>) = l.filter { it.enabled && it.online }
        .mapNotNull { w -> exitKey(w)?.let { it to w } }
        .groupBy({ it.first }, { it.second }).values.filter { g -> g.size > 1 && g.any { it.name in autoWorkers } }
    private suspend fun autoSpreadTick() {
        if (autoWorkers.isEmpty()) { egressAlert = null; return }
        if (autoDupes(statuses(WorkerStore.list())).isEmpty()) { egressAlert = null; return }
        spreadExits(only = autoWorkers)                      // reassign just the auto workers to distinct exits
        kotlinx.coroutines.delay(6000)                       // let the new exits connect before re-checking
        val still = autoDupes(statuses(WorkerStore.list()))
        if (still.isEmpty()) { egressAlert = null; return }   // healed - every auto worker on a distinct exit
        val extras = still.flatMap { g -> g.filter { it.name in autoWorkers } }.distinctBy { it.name }.drop(1)
        extras.forEach { WorkerStore.setEnabled(it.name, false); collisionDisabled += it.name }
        egressAlert = if (extras.isEmpty()) null
            else "Not enough distinct exits for the Auto workers — disabled ${extras.joinToString(", ") { it.name }} to avoid a shared egress IP. Add more Mullvad states."
    }

    /** Auto-assign a DISTINCT Mullvad exit city to each enabled+online worker so no two share an egress IP
     *  (the whole point of running more than one worker). Greedy: each worker, in order, pins its lowest-ping
     *  city not already claimed by another; a worker with no distinct city left falls back to "auto" (the
     *  worker's own per-node spread). Pins via "city:<code>", which the worker fails over within on its own. */
    /** Auto-assign a DISTINCT Mullvad exit city to each enabled+online worker so no two share an egress IP.
     *  BUSY-AWARE (skipBusy, the default): never flips a worker mid-work - a library scan in progress defers the
     *  whole thing, and a worker with in-flight downloads keeps its current exit (its city is reserved so idle
     *  workers still avoid it). Greedy: each assignable worker pins its lowest-ping free city; one with no
     *  distinct city left falls back to "auto". Called by the Spread-exits button and auto on a worker joining. */
    suspend fun spreadExits(skipBusy: Boolean = true, only: Set<String>? = null): SpreadResult {
        if (skipBusy && ScanDispatch.running)
            return SpreadResult(emptyList(), "Deferred: a library scan is running — exits aren't reassigned mid-scan.")
        // One-tap recovery: re-enable any workers the collision guard auto-disabled, then spread them out.
        if (collisionDisabled.isNotEmpty()) {
            collisionDisabled.forEach { WorkerStore.setEnabled(it, true) }
            collisionDisabled.clear()
        }
        val enabled = WorkerStore.list().filter { it.enabled }
        val stats = statuses(enabled).associateBy { it.name }
        val online = enabled.filter { stats[it.name]?.online == true }
        if (online.isEmpty()) return SpreadResult(emptyList(), "No enabled, online workers.")
        val taken = HashSet<String>()
        val out = ArrayList<SpreadAssign>()
        val assignable = ArrayList<WorkerNode>()
        for (w in online) {
            val inflight = stats[w.name]?.inflight ?: 0
            val city = stats[w.name]?.exitCity.orEmpty()
            // `only` != null (auto loop): leave workers NOT in the set alone - just reserve their exit so the
            // managed ones avoid it. A worker with in-flight downloads also keeps its current exit (reserved).
            if ((only != null && w.name !in only) || (skipBusy && inflight > 0)) {
                if (city.isNotEmpty()) taken += city
                else stats[w.name]?.egressIp?.ifBlank { null }?.let { taken += it }
                if (only == null || w.name in only)
                    out += SpreadAssign(w.name, city.ifEmpty { null }, null, null, false, "busy ($inflight downloading) — kept current exit")
            } else assignable += w
        }
        // Assign each from `cities` = MULLVAD states only. The user's own exit nodes are never auto-assigned.
        for (w in assignable) {
            val restrictedNow = restrictedStates()
            val cities = runCatching { spreadJson.decodeFromString(WorkerMode.ExitNodesDto.serializer(), exitNodes(w) ?: "") }
                .getOrNull()?.cities.orEmpty().filter { it.online > 0 && it.code !in restrictedNow }.sortedBy { it.ping ?: Int.MAX_VALUE }
            val pick = cities.firstOrNull { it.code !in taken }
            if (pick != null) {
                taken += pick.code
                val ok = setExit(w, "city:${pick.code}") != null
                out += SpreadAssign(w.name, pick.code, pick.label, pick.ping, ok, if (ok) null else "set failed")
            } else {
                val ok = setExit(w, "auto") != null
                out += SpreadAssign(w.name, null, "auto", null, ok,
                    if (cities.isEmpty()) "no exits reachable" else "no distinct city left → auto")
            }
        }
        val managed = if (only == null) online.size else online.count { it.name in only }
        val msg = when {
            assignable.isEmpty() -> "All targeted workers busy — exits left unchanged."
            out.none { it.applied && it.city != null } -> "No Mullvad exits available yet — enable them on the tailnet first."
            else -> "$managed workers across ${taken.size} distinct ${if (taken.size == 1) "city" else "cities"}."
        }
        if (out.any { it.applied && it.city != null }) egressAlert = null // cleared the collision
        return SpreadResult(out, msg)
    }

    // ---- Update detection: compare each node's running commit to origin/main on GitHub ----
    @Volatile private var ghCache: List<Pair<String, String>> = emptyList()
    @Volatile private var ghAt = 0L
    private val ghJson = Json { ignoreUnknownKeys = true }
    @Serializable private data class GhCommit(val sha: String = "", val commit: GhMsg = GhMsg())
    @Serializable private data class GhMsg(val message: String = "")
    @Serializable private data class VerDto(val commit: String = "")
    /** Recent commits on origin/main from GitHub (sha7 -> subject), cached 15m. Empty if GitHub is unreachable. */
    fun latestCommits(): List<Pair<String, String>> {
        if (ghCache.isNotEmpty() && System.currentTimeMillis() - ghAt < 900_000) return ghCache
        val got = runCatching {
            val rb = Request.Builder().url("https://api.github.com/repos/NAKE-1/manga-utils/commits?sha=main&per_page=40")
                .header("User-Agent", "manga-utils").header("Accept", "application/vnd.github+json")
            exitClient.newCall(rb.build()).execute().use { r ->
                if (!r.isSuccessful) emptyList() else ghJson.decodeFromString<List<GhCommit>>(r.body?.string() ?: "[]")
                    .map { it.sha.take(7) to it.commit.message.lineSequence().firstOrNull().orEmpty() }
            }
        }.getOrDefault(emptyList())
        if (got.isNotEmpty()) { ghCache = got; ghAt = System.currentTimeMillis() }
        return ghCache
    }
    /** How many commits a node is behind origin/main: 0 = up to date, N>0 = behind by N, -1 = unknown/too far. */
    fun behindOf(commit: String, commits: List<Pair<String, String>>): Int {
        if (commit.isBlank() || commit == "unknown" || commits.isEmpty()) return -1
        return commits.indexOfFirst { it.first.startsWith(commit) || commit.startsWith(it.first) }
    }
    /** A worker's running commit (from its /api/version), or null if unreachable. */
    fun commitOf(node: WorkerNode): String? = runCatching {
        val rb = Request.Builder().url("${node.url.trimEnd('/')}/api/version")
        if (node.token.isNotBlank()) rb.header("Authorization", "Bearer ${node.token}")
        exitClient.newCall(rb.build()).execute().use { r -> if (!r.isSuccessful) null else ghJson.decodeFromString<VerDto>(r.body?.string() ?: "{}").commit.ifBlank { null } }
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

    // ---- Fleet flow map (webui): each worker at its egress city, with the sources it's downloading. ----
    @Serializable data class FleetSource(val id: Long, val name: String)
    @Serializable data class FleetShip(val seq: Long, val source: String) // a chapter shipped home (worker -> main)
    @Serializable data class FleetWorker(
        val name: String, val online: Boolean, val egressIp: String? = null, val exitCity: String = "",
        val lat: Double? = null, val lon: Double? = null, val inflight: Int = 0, val sources: List<FleetSource> = emptyList(),
        val ships: List<FleetShip> = emptyList(),
    )
    @Serializable data class FleetDto(val mainLat: Double, val mainLon: Double, val workers: List<FleetWorker>)

    // Lat/long for each allowed Mullvad state code (mirrors WorkerMode.MULLVAD_CITIES). Used to place a worker
    // at its egress city on the map; a worker with no/unknown exit collapses onto the main marker.
    private val CITY_LL = mapOf(
        "mia" to (25.76 to -80.19), "atl" to (33.75 to -84.39), "bham" to (33.52 to -86.80),
        "cae" to (34.00 to -81.03), "chs" to (32.78 to -79.93), "clt" to (35.23 to -80.84),
        "rag" to (35.78 to -78.64), "rdu" to (35.78 to -78.64), "bna" to (36.16 to -86.78),
        "mem" to (35.15 to -90.05), "msy" to (29.95 to -90.07), "lit" to (34.75 to -92.29),
        "hou" to (29.76 to -95.37), "dal" to (32.78 to -96.80), "was" to (39.04 to -77.49),
        "nyc" to (40.71 to -74.01),
    )

    /** Assemble the fleet view from the status cache + the download queue's per-worker source load. */
    fun fleetFlows(): FleetDto {
        val nodes = runCatching { WorkerStore.list().filter { it.enabled } }.getOrDefault(emptyList())
        val cards = cachedStatuses(nodes).associateBy { it.name }
        val load = runCatching { DownloadQueue.workerSources() }.getOrDefault(emptyMap())
        val workers = nodes.map { n ->
            val c = cards[n.name]
            val city = c?.exitCity.orEmpty()
            val ll = CITY_LL[city]
            val srcs = (load[n.name] ?: emptyList()).map { FleetSource(it, DownloadQueue.sourceName(it)) }
            val ships = runCatching { ShipLog.recent(n.name) }.getOrDefault(emptyList()).map { (sq, sid) -> FleetShip(sq, DownloadQueue.sourceName(sid)) }
            FleetWorker(n.name, c?.online ?: false, c?.egressIp, city, ll?.first, ll?.second, c?.inflight ?: 0, srcs, ships)
        }
        return FleetDto(28.4, -81.3, workers) // main = central FL (direct egress); make configurable later if a box moves
    }
}
