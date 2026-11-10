package mangautils.server

import mangautils.core.config.SettingsStore
import mangautils.core.library.ChapterRef
import mangautils.core.library.LibraryEntry
import mangautils.core.library.LibraryService
import mangautils.core.library.LibraryStore
import mangautils.core.library.UpdateResult
import mangautils.core.worker.WorkerNode
import mangautils.core.worker.WorkerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Distributed library update scan. When the "worker update checking" setting is on, the per-series chapter-list
 * fetch (the slow, network-bound part) is spread across main + every online worker node; each worker runs its
 * share in parallel, ships back the refs, and MAIN does all the diffing and owns library state. A worker that
 * fails or can't be reached just means its series fall back to a local fetch - a title is never dropped.
 *
 * Gated behind the setting (default off): scanning hammers sources harder than downloads, so it's opt-in.
 */
object ScanDispatch {
    private val log = LoggerFactory.getLogger("ScanDispatch")
    private val JSON = "application/json".toMediaType()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        // The worker holds ONE request open while it fetches its whole share (minutes, no bytes in between),
        // so the default 10s per-read timeout kills it mid-scan. Disable per-read/write and let callTimeout cap it.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.MINUTES) // overall bound; a worker's share can be a lot of series
        .build()
    private val pollClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).callTimeout(3, TimeUnit.SECONDS).build()
    private val pool = Executors.newCachedThreadPool { Thread(it, "scan-worker").apply { isDaemon = true } }

    // Cost-weight for the progress bar: sources whose per-title scan costs ~an order of magnitude more (a
    // Cloudflare challenge + the captcha solver) are weighted heavier, so the bar tracks TIME, not title count.
    // MangaFire is the dominant slow one; add other heavy sources here, or lower SLOW_WEIGHT, if the mix changes.
    private val SLOW_SOURCES = setOf(6084907896154116083L) // MangaFire
    private const val SLOW_WEIGHT = 8
    private fun weightOf(e: LibraryEntry): Int = if (e.sourceId in SLOW_SOURCES) SLOW_WEIGHT else 1

    /** A library scan (manual or scheduled) is in progress. Workers.spreadExits() won't flip exits mid-scan. */
    @Volatile var running = false
        private set

    fun update(
        entries: List<LibraryEntry> = LibraryStore.list(),
        onProgress: ((done: Int, total: Int, titleDone: Int, titleTotal: Int) -> Unit)? = null,
        isOnline: () -> Boolean = { true },
        onFailure: (() -> Unit)? = null,
    ): List<UpdateResult> {
        running = true
        return try { updateImpl(entries, onProgress, isOnline, onFailure) } finally { running = false }
    }

    private fun updateImpl(
        entries: List<LibraryEntry>,
        onProgress: ((done: Int, total: Int, titleDone: Int, titleTotal: Int) -> Unit)?,
        isOnline: () -> Boolean,
        onFailure: (() -> Unit)?,
    ): List<UpdateResult> {
        val parallel = parallelScanEnabled()
        val nodes = scanNodes()
        // No worker (off / none / all down) or a tiny library: scan it all on main - parallel if enabled
        // (fast even with zero workers), else the old one-at-a-time sequential scan.
        if (nodes.isEmpty() || entries.size < 4) {
            val weights = entries.map(::weightOf).sorted()   // ascending: main clears its fastest titles first
            val total = weights.sum()                         // weighted total (cost-weighted, see weightOf)
            onProgress?.invoke(0, total, 0, entries.size)
            val done = AtomicInteger(0)
            val res = scanLocal(entries, parallel, isOnline, onFailure) {
                val k = done.incrementAndGet(); var s = 0; for (i in 0 until minOf(k, weights.size)) s += weights[i]
                onProgress?.invoke(s.coerceAtMost(total), total, k, entries.size)
            }
            log.info("scan done: {} checked on main{}, {} new, {} failed",
                res.size, if (parallel) " (parallel)" else "", res.sumOf { it.newChapters.size }, res.count { it.failed })
            return res
        }

        // Round-robin into buckets: bucket 0 = main, 1..N = each worker. Interleaving spreads each source
        // evenly across buckets, so parallel hits on one source come from different egress IPs (politer).
        val buckets = Array(nodes.size + 1) { ArrayList<LibraryEntry>() }
        entries.forEachIndexed { i, e -> buckets[i % buckets.size].add(e) }

        // Cost-weight the progress so the bar tracks TIME, not title COUNT. A library is ~half instant JSON-API
        // titles (Atsumaru/MangaDex) and ~half Cloudflare+solver titles (MangaFire) that cost ~10x more each, so
        // a title-linear bar races to ~50% then crawls. Weight each title by its source's rough scan cost.
        val bucketWeights = buckets.map { b -> b.map(::weightOf).sorted() } // per-bucket weights, ascending
        val total = entries.sumOf(::weightOf)                               // weighted total
        onProgress?.invoke(0, total, 0, entries.size) // publish totals up front so the bar shows 0/total, not a stuck 0/0
        // Each node clears its LIGHTEST (fastest) titles first (8-wide parallel), so once it has finished k of
        // its titles the work done is ~the sum of its k smallest title-weights. Track per-node completed counts
        // (main = bucket 0, workers = 1..N), convert to weight at publish time.
        val mainCount = AtomicInteger(0)
        val workerCounts = IntArray(nodes.size)
        fun weightedDone(bucketIdx: Int, k: Int): Int {
            val w = bucketWeights[bucketIdx]; var s = 0
            for (i in 0 until minOf(k, w.size)) s += w[i]; return s
        }
        val publish = {
            val wd = weightedDone(0, mainCount.get()) + nodes.indices.sumOf { weightedDone(it + 1, workerCounts[it]) }
            val td = mainCount.get() + workerCounts.sum() // real title count (for the small xxx/xxx text)
            onProgress?.invoke(wd.coerceAtMost(total), total, td.coerceAtMost(entries.size), entries.size)
        }

        // Kick off every worker's fetch up front so they run in parallel with main's own local scan.
        val futures = nodes.mapIndexed { idx, node ->
            node to pool.submit<Map<String, List<ChapterRef>>> { fetchFromWorker(node, buckets[idx + 1]) }
        }
        // Poll the workers' live scan progress so the bar climbs during their fetch. Also the STALL GUARD: a
        // worker that HANGS on its share (unseeded/cold, bad egress) would otherwise freeze the whole scan -
        // the per-series local fallback only kicks in once its future RETURNS, which for a hung worker is the
        // 15-min callTimeout. So if a worker makes NO progress for STALL_MS, cancel its future - fut.get() then
        // throws and its bucket falls back to a local fetch, and the scan moves on instead of stalling at ~50%.
        val STALL_MS = 45_000L
        val lastAdvance = HashMap<String, Long>().apply { val t0 = System.currentTimeMillis(); nodes.forEach { put(it.name, t0) } }
        val poller = pool.submit {
            runCatching {
                while (futures.any { !it.second.isDone }) {
                    val now = System.currentTimeMillis()
                    futures.forEachIndexed { idx, pair ->
                        val (node, fut) = pair
                        val d = pollScanProgress(node)
                        if (d > workerCounts[idx]) { workerCounts[idx] = d; lastAdvance[node.name] = now } // monotonic
                        else if (!fut.isDone && now - (lastAdvance[node.name] ?: now) > STALL_MS) {
                            log.warn("scan via {} stalled {}s with no progress - giving up, its share falls back local",
                                node.name, STALL_MS / 1000)
                            fut.cancel(true)
                        }
                    }
                    publish()
                    Thread.sleep(700)
                }
            }
        }
        // Scan main's own bucket (parallel when enabled, else sequential) while the workers fetch theirs.
        val tMain = System.currentTimeMillis()
        val mainResults = scanLocal(buckets[0], parallel, isOnline, onFailure) { mainCount.incrementAndGet(); publish() }
        val mainSec = (System.currentTimeMillis() - tMain) / 1000
        // Collect each worker's refs, then diff its bucket: prefetched where the worker succeeded, local fetch
        // for the rest (worker-failed or unreachable). Either way every entry in the bucket is processed.
        var remote = 0
        val workerResults = futures.flatMapIndexed { idx, pair ->
            val (node, fut) = pair
            val bucket = buckets[idx + 1]
            val refs = runCatching { fut.get() }
                .getOrElse { log.warn("scan via {} failed, all {} fall back local: {}", node.name, bucket.size, it.message); emptyMap() }
            if (refs.isNotEmpty()) log.info("scan via {}: {}/{} fetched remotely", node.name, refs.size, bucket.size)
            remote += refs.size
            LibraryService.update(bucket, null, isOnline, onFailure, prefetch = { refs[it.mangaUrl] })
        }
        poller.cancel(true)
        mainCount.set(buckets[0].size); for (i in workerCounts.indices) workerCounts[i] = buckets[i + 1].size
        publish() // finalize to the weighted total
        val all = mainResults + workerResults
        log.info("scan done: {} checked - {} on main ({}s) + {} via {} worker(s) ({} fell back local), {} new, {} failed",
            all.size, buckets[0].size, mainSec, remote, nodes.size, entries.size - buckets[0].size - remote,
            all.sumOf { it.newChapters.size }, all.count { it.failed })
        return all
    }

    private const val LOCAL_CONCURRENCY = 8 // ponytail: fixed; lower if main's IP ever gets rate-limited scanning

    private fun parallelScanEnabled(): Boolean =
        runCatching { SettingsStore.get().parallelUpdateScan }.getOrDefault(true)

    /** Scan a bucket on main, ticking [onTick] per series. Parallel = 8-wide fetch + prefetched (serial,
     *  safe) diff; sequential = the old one-source-at-a-time scan. Either way a title is never dropped. */
    private fun scanLocal(
        bucket: List<LibraryEntry>,
        parallel: Boolean,
        isOnline: () -> Boolean,
        onFailure: (() -> Unit)?,
        onTick: () -> Unit,
    ): List<UpdateResult> {
        if (!parallel) return LibraryService.update(bucket, { _, _ -> onTick() }, isOnline, onFailure)
        val refs = fetchLocalParallel(bucket) { onTick() }
        // localFetch=false: fetchLocalParallel already tried every series here, so a miss is a real failure -
        // don't re-fetch it (would just re-time-out on a dead source and stall at ~100%).
        return LibraryService.update(bucket, null, isOnline, onFailure, prefetch = { refs[it.mangaUrl] }, localFetch = false)
    }

    /** Fetch a bucket's chapter lists locally, 8-wide (mirrors the worker's /worker/scan). Returns the refs
     *  it got; a failed series is simply absent, so update()'s prefetch falls back to a local fetch for it. */
    private fun fetchLocalParallel(bucket: List<LibraryEntry>, onOne: () -> Unit): Map<String, List<ChapterRef>> {
        if (bucket.isEmpty()) return emptyMap()
        return runBlocking(Dispatchers.IO) {
            val sem = Semaphore(LOCAL_CONCURRENCY)
            bucket.map { e ->
                async {
                    sem.withPermit {
                        try {
                            runCatching { LibraryService.fetchRefs(e.sourceId, e.mangaUrl) }.getOrNull()?.let {
                                log.info("SCAN '{}' - {} chapters", e.title, it.size) // live, as each lands (not a diff-time burst)
                                e.mangaUrl to it
                            }
                        } finally { onOne() } // tick progress per fetch (success or not), that's where the time is
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    }

    /** How many series a node has fetched in its in-flight /worker/scan, for live progress. 0 on any error. */
    private fun pollScanProgress(node: WorkerNode): Int = runCatching {
        val rb = Request.Builder().url("${node.url.trimEnd('/')}/worker/scan/progress").get()
        if (node.token.isNotBlank()) rb.header("Authorization", "Bearer ${node.token}")
        pollClient.newCall(rb.build()).execute().use { r ->
            if (!r.isSuccessful) return 0
            json.decodeFromString(WorkerMode.ScanProgressDto.serializer(), r.body?.string() ?: return 0).done
        }
    }.getOrDefault(0)

    /** Enabled + online nodes to offload to, honoring the workerNode pick. Empty unless the setting is on. */
    private fun scanNodes(): List<WorkerNode> {
        val s = runCatching { SettingsStore.get() }.getOrNull() ?: return emptyList()
        if (!s.workerUpdateChecking) return emptyList()
        val pick = s.workerNode // "" = any enabled node, else the chosen one
        val candidates = runCatching {
            WorkerStore.list().filter { it.enabled && (pick.isBlank() || it.name == pick) }
        }.getOrDefault(emptyList())
        return candidates.filter { Workers.onlineNow(it) }
    }

    /** POST a bucket to the node's /worker/scan; return mangaUrl -> refs for the series it fetched OK. */
    private fun fetchFromWorker(node: WorkerNode, bucket: List<LibraryEntry>): Map<String, List<ChapterRef>> {
        if (bucket.isEmpty()) return emptyMap()
        val body = WorkerMode.ScanReqDto(bucket.map { WorkerMode.ScanItem(it.sourceId.toString(), it.mangaUrl, it.title) })
        val rb = Request.Builder().url("${node.url.trimEnd('/')}/worker/scan")
            .post(json.encodeToString(WorkerMode.ScanReqDto.serializer(), body).toRequestBody(JSON))
        if (node.token.isNotBlank()) rb.header("Authorization", "Bearer ${node.token}")
        client.newCall(rb.build()).execute().use { r ->
            if (!r.isSuccessful) { log.warn("scan HTTP {} from {}", r.code, node.name); return emptyMap() }
            val payload = r.body?.string() ?: return emptyMap()
            val res = json.decodeFromString(WorkerMode.ScanResDto.serializer(), payload)
            return res.results.filter { it.chapters != null }.associate { it.manga to it.chapters!! }
        }
    }
}
