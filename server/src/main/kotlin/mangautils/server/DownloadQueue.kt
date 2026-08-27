/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mangautils.core.config.AppConfig
import mangautils.core.config.SettingsStore
import mangautils.core.download.ChapterSelect
import mangautils.core.download.DownloadManager
import mangautils.core.download.DownloadStore
import mangautils.core.download.ExistingPolicy
import mangautils.core.download.SourceRef
import mangautils.core.source.SourceManager
import eu.kanade.tachiyomi.network.interceptor.HumanCheckState
import eu.kanade.tachiyomi.source.online.HttpSource
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Web download queue. Each task is ONE manga (a set of chapters), downloaded with a single
 * [DownloadManager.download] call: the manga is resolved ONCE and its chapters download
 * sequentially with page-level concurrency — exactly like the desktop app. Different manga run in
 * parallel up to [SettingsStore]'s parallelDownloads. (The old per-chapter-parallel design hammered
 * a source with redundant detail/chapter/page lookups and got rate-limited, failing every chapter.)
 */
object DownloadQueue {
    private val log = LoggerFactory.getLogger(javaClass)

    class Chapter(val url: String, val name: String)

    class Task(
        val id: String,
        val sourceId: Long,
        val mangaUrl: String,
        val mangaTitle: String,
        val chapters: List<Chapter>,
    ) {
        val total = chapters.size
        @Volatile var order = 0 // sort key for the queue; lower runs first (reorderable while queued)
        @Volatile var tag = "" // "" for a normal download, "migration" for one queued by a migration
        @Volatile var state = "queued" // queued | running | done | failed | stopped | interrupted | retrywait | waitvf | offlinewait
        @Volatile var vfHost = "" // when state==waitvf: the host whose human-check must be solved to resume
        @Volatile var doneCount = 0
        @Volatile var failedCount = 0
        @Volatile var currentChapter = ""
        @Volatile var currentChapterUrl = ""
        @Volatile var pagesDone = 0
        @Volatile var pagesTotal = 0
        @Volatile var bytesPerSec = 0.0
        @Volatile var lastLogAt = 0L // throttle for the live progress log line
        @Volatile var error = ""
        @Volatile var autoRetries = 0 // legacy; kept for queue-file compat (inline retry was replaced by re-arm)
        @Volatile var reArms = 0 // how many times a transient failure has parked this task for a later re-run
        @Volatile var retryAt = 0L // epoch ms this parked task re-runs; 0 = not parked
        // What the remaining failures mean, for the card colour: "" none, "transient" source was busy,
        // "alternative" a 404 we hold under another scan, "gone" a 404 with no other copy. Worst wins.
        @Volatile var failClass = ""
        // Chapters that failed (for the Retry button); URLs of finished chapters (live progress).
        // Keyed by URL, not name: several scanlations of one chapter share a name, so a name-keyed
        // set would mark every version done as soon as any one of them finished.
        val failed = CopyOnWriteArrayList<Chapter>()
        // Per failed-chapter meaning (url -> "gone" | "alternative" | "transient"), so the card can show
        // each chapter's own status instead of one worst-wins colour for the whole task.
        val failReason = ConcurrentHashMap<String, String>()
        val finishedUrls = ConcurrentHashMap.newKeySet<String>()
        val active get() = state == "queued" || state == "running"
        fun nameToUrl(name: String) = chapters.firstOrNull { it.name == name }?.url ?: ""
    }

    private val tasks = ConcurrentHashMap<String, Task>()
    private val futures = ConcurrentHashMap<String, Future<*>>()

    // sourceId -> epoch ms until which we won't start a NEW task from that source. Set when a source hands
    // back a transient (rate-limit / busy) failure, so the queue doesn't march the next manga into the same
    // wall. A success clears it. ponytail: flat cooldown; it self-extends since a re-failure just re-sets it.
    private val sourceCooldownUntil = ConcurrentHashMap<Long, Long>()
    private const val SOURCE_COOLDOWN_MS = 3 * 60_000L

    // Fires parked (retrywait) tasks once their cooldown elapses. Cheap 10s tick; a parked task holds no
    // pool slot, it just waits here for the source's rate-limit window to reset.
    private val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dl-retry").apply { isDaemon = true } }
    init { ticker.scheduleWithFixedDelay({ runCatching { sweepRetries() } }, 10, 10, java.util.concurrent.TimeUnit.SECONDS) }

    @Volatile private var poolSize = parallelism()
    @Volatile private var pool = newPool(poolSize)
    private var seq = 0L

    private fun parallelism() = runCatching { SettingsStore.get().parallelDownloads }.getOrDefault(3).coerceIn(1, 8)
    private fun perSourceParallel() = runCatching { SettingsStore.get().perSourceParallel }.getOrDefault(false)
    private fun perSourceLimit() = runCatching { SettingsStore.get().perSourceLimit }.getOrDefault(2).coerceIn(1, 8)
    private fun newPool(n: Int) = Executors.newFixedThreadPool(n) { r -> Thread(r, "dl-worker").apply { isDaemon = true } }

    @Synchronized
    private fun ensurePool() {
        val want = parallelism()
        if (want != poolSize) { poolSize = want; pool = newPool(want) }
    }

    /** Enqueue a manga's chapters as a single task (one resolve, sequential chapters). */
    @Synchronized
    fun enqueue(sourceId: Long, mangaUrl: String, mangaTitle: String, chapters: List<Chapter>, tag: String = "") {
        if (chapters.isEmpty()) return
        val n = seq++
        val id = "dl-$n-${System.nanoTime().toString(36).takeLast(4)}"
        tasks[id] = Task(id, sourceId, mangaUrl, mangaTitle, chapters).apply { order = n.toInt(); this.tag = tag }
        pump()
        persist()
    }

    /**
     * Start as many queued tasks as the limits allow. By default only ONE manga per source runs at a
     * time (different sources run in parallel up to parallelDownloads) — gentle on each source, like
     * Suwayomi. perSourceParallel lifts the per-source cap.
     */
    @Synchronized
    private fun pump() {
        ensurePool()
        // How many manga may run from ONE source at once: 1 (gentle, default) or perSourceLimit when the
        // same-source-parallel toggle is on.
        val perSourceCap = if (perSourceParallel()) perSourceLimit() else 1
        val running = tasks.values.filter { it.state == "running" }
        var slots = poolSize - running.size
        if (slots <= 0) return
        val perSourceCount = running.groupingBy { it.sourceId }.eachCount().toMutableMap()
        val now = System.currentTimeMillis()
        for (task in tasks.values.sortedBy { it.order }) {
            if (slots <= 0) break
            if (task.state != "queued") continue
            if ((perSourceCount[task.sourceId] ?: 0) >= perSourceCap) continue
            // A source that just rate-limited us is resting: don't start the next manga from it and cascade
            // the same failure down the whole queue. Other sources keep running; this one waits out its cooldown.
            if ((sourceCooldownUntil[task.sourceId] ?: 0) > now) continue
            task.state = "running"
            perSourceCount[task.sourceId] = (perSourceCount[task.sourceId] ?: 0) + 1
            slots--
            futures[task.id] = pool.submit { run(task) }
        }
    }

    private fun run(task: Task) {
        if (Thread.currentThread().isInterrupted) { task.state = "stopped"; return }
        task.state = "running"
        task.failClass = "" // recomputed at the end; clear stale colour from a prior run
        Notifier.onDownloadStart(task.sourceId, task.mangaUrl, task.mangaTitle, task.total)
        runCatching {
            val s = SettingsStore.get()
            // The web queue is headless — there's no prompt, so ASK must fall back to SKIP
            // (the ASK path needs an interactive ExistingPrompt and otherwise errors).
            val policy = if (s.existingBehavior == ExistingPolicy.ASK) ExistingPolicy.SKIP else s.existingBehavior
            val dm = DownloadManager(
                concurrency = s.downloadConcurrency,
                retries = s.downloadRetries,
                existingPolicy = policy,
                cancelled = { task.state == "stopped" }, // Stop button flips state → download aborts cooperatively
                listener = { p ->
                    task.currentChapter = p.chapter
                    // Fall back to the name lookup only for a downloader that predates chapterUrl.
                    task.currentChapterUrl = p.chapterUrl.ifBlank { task.nameToUrl(p.chapter) }
                    task.pagesDone = p.pagesDone
                    task.pagesTotal = p.pagesTotal
                    task.bytesPerSec = p.bytesPerSecond
                    if (p.finished && p.pagesTotal > 0) {
                        task.finishedUrls.add(p.chapterUrl.ifBlank { task.nameToUrl(p.chapter) })
                    }
                    task.doneCount = task.finishedUrls.size
                    // Live progress in the server log so you can watch a download happen (matches the
                    // READ/PRELOAD semantic lines). Throttled to ~0.8s so a fast chapter isn't a line
                    // per page; always logs the first page and the finished line.
                    val now = System.currentTimeMillis()
                    if (p.finished || p.pagesDone <= 1 || now - task.lastLogAt > 800) {
                        task.lastLogAt = now
                        val speed = if (p.bytesPerSecond > 0) " - ${(p.bytesPerSecond / 1024).toInt()} KB/s" else ""
                        log.info("DOWNLOAD {} - {}/{} pages{}", p.chapter, p.pagesDone, p.pagesTotal, speed)
                    }
                },
            )
            val allUrls = task.chapters.map { it.url }.toSet()
            val job = dm.download(SourceRef(task.sourceId, task.mangaUrl), select = ChapterSelect.Urls(allUrls))
            // Reconcile from the per-chapter attempt trace: a chapter is done if any candidate ok/skipped.
            // Reconcile by URL, not by chapter name: several scanlations of a chapter share a name, so
            // grouping attempts by name merges them and undercounts a versioned download.
            val doneUrls = task.finishedUrls.toMutableSet()
            val attempts = job.attempts.toMutableList() // accumulates across auto-retry passes
            // A chapter already on disk finishes without emitting progress, so it needs adding by hand.
            // Deliberately narrow: a skip because the source gave up ("source unavailable") is NOT done,
            // it just never ran — treating it as done is what hid 55 chapters from Retry.
            fun foldSkips() = attempts
                .filter { it.outcome == "skipped" && it.message.orEmpty().contains("already", ignoreCase = true) }
                .flatMap { a -> task.chapters.filter { it.name == a.target } }
                .forEach { doneUrls.add(it.url) }
            foldSkips()

            task.doneCount = doneUrls.size
            task.bytesPerSec = 0.0
            if (task.state == "stopped") {
                // Stopped by the user: keep the chapters that finished, drop the rest silently
                // (the in-progress chapter was never written to disk). Don't flag them as "failed".
                task.failed.clear()
                task.failReason.clear()
                task.failedCount = 0
            } else {
                task.failed.clear()
                task.failReason.clear()
                // Anything without a successful outcome is retryable — including chapters the job never
                // reached because the source's failure breaker tripped part-way through.
                task.failed.addAll(task.chapters.filter { it.url !in doneUrls })
                task.failedCount = task.failed.size
                if (task.failedCount == 0) {
                    task.state = "done"
                    sourceCooldownUntil.remove(task.sourceId) // source is clearly healthy again
                } else {
                    task.error = explainFailure(task, attempts)
                    task.failClass = classifyFailures(task, attempts)
                    // Blocked on an interactive human-check (MangaFire's "click the shapes"): don't fail and
                    // don't burn retry passes on a timer — no amount of waiting clears a captcha. Park it as
                    // "waitvf" and resume the moment the user solves it in the WebView (HumanCheckState.onCleared
                    // → resumeWaitingFor). A Discord ping already fired when the host first got flagged.
                    val vfHost = sourceHost(task.sourceId)?.takeIf { HumanCheckState.isPending(it) }
                    if (vfHost != null) {
                        task.vfHost = vfHost
                        task.state = "waitvf"
                    } else if (task.failClass == "transient" && !NetMonitor.online) {
                    // Server is already offline: this "busy source" is really no-internet (started a download
                    // while offline). Park it as offlinewait so it shows "Paused" and auto-resumes on reconnect,
                    // instead of a bogus source-rest timer. No cooldown - the source isn't the problem.
                    task.state = "offlinewait"
                    task.error = ""
                    } else {
                    // A rate limit / busy source is source-wide, not manga-specific: rest the whole source so
                    // pump() doesn't immediately throw the next queued manga at it and cascade the failure.
                    if (task.failClass == "transient") {
                        sourceCooldownUntil[task.sourceId] = System.currentTimeMillis() + SOURCE_COOLDOWN_MS
                        // A whole batch failing as "busy" can actually be the SERVER being offline (every source
                        // call fails the same way). Probe connectivity now instead of sitting in a pointless
                        // retry-wait; if offline, the monitor flips and the queue parks as offlinewait (auto-resume).
                        NetMonitor.reportPossibleOutage()
                    }
                    // A rate limit / busy source mid-run strands a batch of chapters, all retryable. A quick
                    // inline retry is the wrong move — it just keeps the source's window hot — and burying it
                    // as a dead "failed" row forces a manual click. Park it and re-run after a real quiet gap,
                    // once the window has reset (exactly what a manual Retry does, but on a timer). Permanent
                    // (404 / missing-images) failures aren't parked — retrying those never helps.
                    if (task.failClass == "transient" && task.reArms < RE_ARM_CAP) parkForRetry(task)
                    else task.state = "failed"
                    }
                }
            }
        }.onFailure {
            task.bytesPerSec = 0.0
            // download() can throw (vs. return a job with failed chapters) when the human-check trips on the
            // chapter-LIST / details fetch, which runs outside the per-chapter loop. Park that the same way
            // the mid-page path does (see ~line 230) so ONE captcha solve resumes it with its siblings,
            // instead of stranding it as a hard "failed" row the batch never comes back to.
            val vfHost = sourceHost(task.sourceId)?.takeIf { HumanCheckState.isPending(it) }
            when {
                // pauseForOffline() marks the task offlinewait BEFORE interrupting it, so preserve that
                // (auto-resume on reconnect) rather than clobbering it to a manual "stopped".
                Thread.currentThread().isInterrupted || it is InterruptedException -> { if (task.state != "offlinewait") task.state = "stopped" }
                vfHost != null -> {
                    task.failed.clear(); task.failed.addAll(task.chapters.filter { c -> c.url !in task.finishedUrls })
                    task.failedCount = task.failed.size
                    task.vfHost = vfHost
                    task.state = "waitvf"
                }
                // Threw because the server is offline (e.g. host-unknown on the details fetch): park as
                // offlinewait ("Paused", auto-resumes on reconnect) instead of a dead "failed" row.
                !NetMonitor.online -> {
                    task.failed.clear(); task.failed.addAll(task.chapters.filter { c -> c.url !in task.finishedUrls })
                    task.failedCount = task.failed.size
                    task.state = "offlinewait"; task.error = ""
                }
                else -> { task.state = "failed"; task.error = it.message ?: it::class.simpleName ?: "failed" }
            }
            log.debug("download task {} ended: {}", task.id, task.state)
        }
        when (task.state) {
            "done" -> {
                log.info("DOWNLOAD COMPLETE - '{}' ({}/{} chapters)", task.mangaTitle, task.doneCount, task.total)
                Notifier.onDownloadComplete(task.sourceId, task.mangaUrl, task.mangaTitle, task.doneCount)
            }
            "failed" -> {
                log.info(
                    "DOWNLOAD FAILED - '{}' ({}/{} done, {} failed) - {}",
                    task.mangaTitle, task.doneCount, task.total, task.failedCount, task.error,
                )
                Notifier.onDownloadFailed(task.sourceId, task.mangaUrl, task.mangaTitle, task.failedCount, task.error, task.failClass)
            }
            "stopped" -> log.info("DOWNLOAD STOPPED - '{}' ({}/{} chapters kept)", task.mangaTitle, task.doneCount, task.total)
            "retrywait" -> log.info(
                "DOWNLOAD will retry - '{}' ({} chapter(s) the source was too busy for) in ~{}min",
                task.mangaTitle, task.failedCount, ((task.retryAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(1),
            )
            "waitvf" -> log.info(
                "DOWNLOAD waiting for verification - '{}' ({} chapter(s)) - solve the human-check for {} to resume",
                task.mangaTitle, task.failedCount, task.vfHost,
            )
        }
        futures.remove(task.id)
        pump() // a slot (and this source) just freed — start the next eligible manga
        persist() // capture the finished state + any newly-running task
        if (activeCount() == 0 && queuedCount() == 0) Notifier.flushDownloadSession() // queue drained → session summary
    }

    fun tasks(): List<Task> = tasks.values.sortedBy { it.order }

    /** Move a QUEUED task one place earlier/later by swapping its order with the adjacent queued task.
     *  No-op on running/finished tasks (their position is fixed). */
    @Synchronized
    fun move(id: String, up: Boolean) {
        val t = tasks[id] ?: return
        if (t.state != "queued") return
        val queued = tasks.values.filter { it.state == "queued" }.sortedBy { it.order }
        val idx = queued.indexOfFirst { it.id == id }
        val swapIdx = if (up) idx - 1 else idx + 1
        if (idx < 0 || swapIdx !in queued.indices) return
        val other = queued[swapIdx]
        val tmp = t.order; t.order = other.order; other.order = tmp
        persist()
    }
    fun queuedCount(): Int = tasks.values.count { it.state == "queued" }
    fun activeCount(): Int = tasks.values.count { it.active }
    fun totalBytesPerSec(): Double = tasks.values.filter { it.state == "running" }.sumOf { it.bytesPerSec }

    fun stop(id: String) {
        tasks[id]?.let { if (it.active) it.state = "stopped" } // set the flag FIRST so the running loop aborts
        futures[id]?.cancel(true)
        pump()
        persist()
    }
    fun stopAll() {
        tasks.values.forEach { if (it.active) it.state = "stopped" }
        futures.values.forEach { it.cancel(true) }
        persist()
    }
    // Clears done/failed/stopped rows — but NOT interrupted ones (those wait for a manual Resume).
    fun clearFinished() { tasks.entries.removeIf { it.value.state == "done" || it.value.state == "failed" || it.value.state == "stopped" }; persist() }

    /** Remove ONE finished/failed/stopped task row (no-op while it's still active). */
    fun remove(id: String) { tasks[id]?.let { if (!it.active) tasks.remove(id) }; persist() }

    /** Host of a source's base URL (e.g. "mangafire.to"), for matching against HumanCheckState. */
    private fun sourceHost(sourceId: Long): String? = runCatching {
        (SourceManager.loadSource(sourceId) as? HttpSource)?.baseUrl?.let { java.net.URI(it).host }
    }.getOrNull()

    /** The user just solved [host]'s human-check (HumanCheckState.onCleared) — requeue every download that
     *  was parked waiting on it so it starts right away. */
    @Synchronized
    fun resumeWaitingFor(host: String) {
        var any = false
        tasks.values.filter { it.state == "waitvf" && it.vfHost == host }.forEach {
            it.state = "queued"; it.error = ""; it.vfHost = ""; any = true
            log.info("DOWNLOAD resuming after verification - '{}'", it.mangaTitle)
        }
        if (any) { pump(); persist() }
    }

    /** Server lost internet: park active/queued/retry-waiting downloads so they stop hammering doomed
     *  fetches. Distinct from a user Stop and from waitvf; [resumeFromOffline] brings them back automatically. */
    @Synchronized
    fun pauseForOffline() {
        var any = false
        tasks.values.forEach {
            if (it.state == "running" || it.state == "queued" || it.state == "retrywait") {
                it.state = "offlinewait" // set FIRST so the interrupt below preserves it (see run() onFailure)
                it.bytesPerSec = 0.0
                any = true
            }
        }
        futures.values.forEach { it.cancel(true) } // interrupt any in-flight download
        if (any) { log.info("DOWNLOAD paused - server went offline"); persist() }
    }

    /** Connectivity returned: requeue everything parked by [pauseForOffline]. */
    @Synchronized
    fun resumeFromOffline() {
        // Clear source cooldowns first: while offline every source "failed", which set a bogus rest timer.
        // Those aren't real rate-limits, so drop them or the resumed downloads would sit in "source resting".
        sourceCooldownUntil.clear()
        var any = false
        tasks.values.filter { it.state == "offlinewait" }.forEach { it.state = "queued"; it.error = ""; it.failClass = ""; any = true }
        if (any) { log.info("DOWNLOAD resuming - server back online"); pump(); persist() }
    }

    // ---- persistence: survive a restart (Part B) ------------------------------------------------
    @Serializable private data class PChap(val url: String, val name: String)
    @Serializable private data class PTask(
        val id: String, val sourceId: Long, val mangaUrl: String, val title: String, val source: String,
        val order: Int, val tag: String, val state: String,
        val chapters: List<PChap>, val done: List<String>, val failed: List<String>, val error: String,
        val autoRetries: Int = 0, val failClass: String = "",
        val reArms: Int = 0, val retryAt: Long = 0,
        val failReason: Map<String, String> = emptyMap(),
    )
    @Serializable private data class PFile(val version: Int = 1, val savedAt: Long, val tasks: List<PTask>)

    private val pjson = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val queueFile get() = AppConfig.dataDir.resolve("downloadqueue.json")
    private fun srcName(id: Long) = runCatching { SourceManager.loadSource(id)?.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: id.toString()

    /** Human source name for a task (for the Downloads per-source tabs). */
    fun sourceName(id: Long): String = srcName(id)

    /** Epoch ms this source is resting until after a rate-limit (0 = not resting) — for the queued-row label. */
    fun sourceRestUntil(id: Long): Long = sourceCooldownUntil[id] ?: 0

    @Synchronized
    private fun persist() {
        runCatching {
            val list = tasks.values.sortedBy { it.order }.map { t ->
                PTask(t.id, t.sourceId, t.mangaUrl, t.mangaTitle, srcName(t.sourceId), t.order, t.tag, t.state,
                    t.chapters.map { PChap(it.url, it.name) }, t.finishedUrls.toList(), t.failed.map { it.url }, t.error,
                    t.autoRetries, t.failClass, t.reArms, t.retryAt, t.failReason.toMap())
            }
            mangautils.core.util.SafeFile.writeAtomic(queueFile, pjson.encodeToString(PFile(savedAt = System.currentTimeMillis(), tasks = list)))
        }.onFailure { log.debug("queue persist failed: {}", it.message) }
    }

    /** Restore the queue from disk on startup. Active tasks become "interrupted" and wait for a manual
     *  Resume (they do NOT auto-start); finished ones are kept as history. */

    /**
     * Turn a download failure into something worth reading. "1 chapter(s) failed" says nothing you can
     * act on; whether the source is down, the chapter's images are gone, or Cloudflare is in the way
     * are three different problems with three different responses - and only one of them is worth
     * retrying.
     */
    private fun explainFailure(task: Task, attempts: List<mangautils.core.status.JobAttempt>): String {
        val failedNames = task.failed.map { it.name }.toSet()
        val reasons =
            attempts
                .filter { it.outcome == "failed" && it.target in failedNames }
                .associate { it.target to reasonFor(it.message.orEmpty()) }
        // Remember the ones the source simply cannot serve, so nothing queues them again on its own.
        val now = System.currentTimeMillis()
        attempts.filter { it.outcome == "failed" && it.target in failedNames && isPermanent(it.message.orEmpty()) }
            .forEach { a ->
                task.chapters.filter { it.name == a.target }.forEach { ch ->
                    mangautils.core.download.UnavailableChapters.mark(ch.url, task.mangaTitle, ch.name, reasonFor(a.message.orEmpty()), now)
                }
            }
        if (reasons.isEmpty()) return "${task.failedCount} chapter(s) couldn't be downloaded"

        // One reason for everything is the common case - say it once rather than per chapter.
        val distinct = reasons.values.distinct()
        val chapters = reasons.keys.sorted()
        val which =
            when {
                chapters.size == 1 -> chapters.first()
                chapters.size <= 3 -> chapters.joinToString(", ")
                else -> "${chapters.take(2).joinToString(", ")} and ${chapters.size - 2} more"
            }
        return if (distinct.size == 1) "$which: ${distinct.first()}" else "$which: ${reasons.values.first()} (and other errors)"
    }

    /** How many times a transient failure re-arms itself before giving up (then it's a plain "failed" row). */
    private const val RE_ARM_CAP = 4

    /** Quiet gap before a parked task re-runs, times the attempt number: 5, 10, 15, 20 min. A single quiet
     *  gap usually clears a rate limit; escalating covers a source that's genuinely struggling — never a
     *  tight loop that keeps the limit warm. */
    private const val RE_ARM_COOLDOWN_MS = 5 * 60_000L

    /** Park a task whose remaining failures are transient: re-run after a real cooldown, no pool slot held. */
    private fun parkForRetry(task: Task) {
        task.retryAt = System.currentTimeMillis() + RE_ARM_COOLDOWN_MS * (task.reArms + 1)
        task.state = "retrywait"
    }

    /** Re-run a parked task now: back to the queue, re-attempting everything. Already-downloaded chapters
     *  skip on disk, so only the stranded ones actually re-fetch.
     *  ponytail: re-lists all chapters (2 requests) and disk-skips the done ones rather than tracking a
     *  failed-only sub-selection — fine at these sizes; narrow to task.failed if the re-list ever drags. */
    @Synchronized
    private fun reArm(task: Task) {
        task.reArms++
        task.retryAt = 0
        task.error = ""
        task.failClass = ""
        task.failedCount = 0
        task.failed.clear()
        task.failReason.clear()
        task.state = "queued"
        pump()
    }

    /** Timer tick: fire every parked task whose cooldown has elapsed. */
    @Synchronized
    private fun sweepRetries() {
        val now = System.currentTimeMillis()
        val due = tasks.values.filter { it.state == "retrywait" && it.retryAt in 1..now }
        if (due.isEmpty()) {
            // Nothing to re-arm, but a source cooldown may have just elapsed — let its queued tasks start.
            if (tasks.values.any { it.state == "queued" }) pump()
            return
        }
        due.forEach { reArm(it) }
        persist()
    }

    /** "Retry now" button on a parked task — skip the remaining cooldown. */
    @Synchronized
    fun forceRetry(id: String) {
        val t = tasks[id] ?: return
        if (t.state == "retrywait") { reArm(t); persist() }
    }

    /**
     * Is this failure the source's fault permanently? Only a missing/delisted chapter qualifies —
     * everything else (down, rate-limited, timed out, Cloudflare) can succeed on a later attempt and
     * must not be written off.
     */
    private fun isPermanent(message: String): Boolean =
        message.contains("404") || message.contains("No chapters matched")

    /** URLs of chapters that failed permanently (404/delisted) — the ones auto-retry must skip. Name-keyed
     *  like explainFailure, since attempts carry the chapter name, not its URL. */
    private fun permanentFailedUrls(task: Task, attempts: List<mangautils.core.status.JobAttempt>): Set<String> {
        val permanentNames = attempts
            .filter { it.outcome == "failed" && isPermanent(it.message.orEmpty()) }
            .map { it.target }.toSet()
        return task.chapters.filter { it.name in permanentNames }.map { it.url }.toSet()
    }

    /**
     * Classify what the remaining failures mean, for the card colour. Worst wins, because the bar is one
     * colour: a genuinely-gone chapter (red) outranks a busy source (amber) outranks a covered 404 (green).
     * A transient failure only lands here after auto-retry has given up on it.
     */
    private fun classifyFailures(task: Task, attempts: List<mangautils.core.status.JobAttempt>): String {
        val permanentUrls = permanentFailedUrls(task, attempts)
        task.failReason.clear()
        for (ch in task.failed) {
            task.failReason[ch.url] = when {
                ch.url !in permanentUrls -> "transient" // source was busy, not missing
                hasAlternative(task.sourceId, task.mangaUrl, task.mangaTitle, ch.url) -> "alternative" // covered
                else -> "gone" // genuinely missing, no other copy
            }
        }
        // Worst-wins for the single bar colour; the per-chapter reasons carry the honest detail.
        val kinds = task.failReason.values.toSet()
        return when {
            "gone" in kinds -> "gone"
            "transient" in kinds -> "transient"
            "alternative" in kinds -> "alternative"
            else -> ""
        }
    }

    /** Do we have — or could we still get — this chapter's number from a different scanlation? A 404 that's
     *  covered elsewhere isn't a hole in the library, so it reads green rather than red. */
    private fun hasAlternative(sourceId: Long, mangaUrl: String, mangaTitle: String, chapterUrl: String): Boolean {
        val entry = mangautils.core.library.LibraryStore.find(sourceId, mangaUrl) ?: return false
        val number = entry.knownChapters.firstOrNull { it.url == chapterUrl }?.number ?: return false
        if (number < 0) return false // unnumbered can't be matched across scans
        if (runCatching { mangautils.core.download.ChapterIdentity.hasAnyVersion(mangaTitle, number) }.getOrDefault(false)) return true
        val unavailable = mangautils.core.download.UnavailableChapters.urls()
        return entry.knownChapters.any { it.number == number && it.url != chapterUrl && it.url !in unavailable }
    }

    /** Map a raw error to a plain explanation. Unrecognised messages pass through unchanged. */
    private fun reasonFor(message: String): String =
        when {
            message.contains("404") ->
                "the source is missing these images - the chapter is broken on their end, so retrying won't help"
            message.contains("521") || message.contains("522") || message.contains("523") ->
                "the source's server is unreachable - usually temporary, worth retrying later"
            message.contains("503") || message.contains("502") ->
                "the source is overloaded or down - worth retrying later"
            message.contains("429") ->
                "the source is rate-limiting us - wait a while before retrying"
            message.contains("403") || message.contains("Cloudflare", ignoreCase = true) ->
                "blocked by Cloudflare - a bypass may be needed for this source"
            message.contains("timed out", ignoreCase = true) || message.contains("timeout", ignoreCase = true) ->
                "the source timed out - worth retrying"
            message.contains("No chapters matched") -> "the chapter is no longer listed on the source"
            message.isBlank() -> "download failed for an unknown reason"
            else -> message
        }

    @Synchronized
    fun loadAndResume() {
        val pf = mangautils.core.util.SafeFile.read(queueFile) { runCatching { pjson.decodeFromString<PFile>(it) }.getOrNull() } ?: return
        if (pf.tasks.isEmpty()) return
        var interrupted = 0; var kept = 0
        for (pt in pf.tasks) {
            val task = Task(pt.id, pt.sourceId, pt.mangaUrl, pt.title, pt.chapters.map { Chapter(it.url, it.name) }).apply { order = pt.order; tag = pt.tag; autoRetries = pt.autoRetries; failClass = pt.failClass; reArms = pt.reArms; retryAt = pt.retryAt }
            // Older queue files stored names here; map them back so a restart doesn't lose progress.
            val urlByName = pt.chapters.associateBy({ it.name }, { it.url })
            pt.done.forEach { task.finishedUrls.add(if (it.startsWith("http") || it in urlByName.values) it else urlByName[it] ?: it) }
            task.doneCount = task.finishedUrls.size
            when (pt.state) {
                // waitvf too: the in-memory human-check flag is gone after a restart, so there's nothing to
                // auto-resume against — fall back to a manual Resume like any interrupted task.
                "queued", "running", "interrupted", "waitvf" -> { task.state = "interrupted"; interrupted++ }
                else -> {
                    task.state = pt.state
                    // Match on URL, falling back to name for queue files written before this change.
                    val byUrl = pt.chapters.associateBy { it.url }
                    val byName = pt.chapters.associateBy { it.name }
                    task.failed.addAll(
                        pt.failed.mapNotNull { k -> (byUrl[k] ?: byName[k])?.let { Chapter(it.url, it.name) } },
                    )
                    task.failedCount = task.failed.size
                    task.failReason.putAll(pt.failReason)
                    task.error = pt.error
                    kept++
                }
            }
            tasks[task.id] = task
        }
        seq = (pf.tasks.maxOfOrNull { it.order.toLong() } ?: -1L) + 1 // don't collide new ids/order with restored
        log.info("download queue restored: {} interrupted (tap Resume), {} finished kept", interrupted, kept)
        persist()
    }

    private fun repairFor(t: Task) {
        // Discard any incomplete (no-ComicInfo) chapters so a half-written one re-downloads fresh.
        runCatching { DownloadStore.listChapters(t.mangaTitle).filterNot { it.complete }.forEach { DownloadStore.deleteChapter(t.mangaTitle, it.name) } }
    }

    /** Resume one paused task — either "interrupted" (post-restart) or "offlinewait" (paused on an offline
     *  blip that can strand it if the server is already back online). Re-queue after discarding half-written
     *  chapters + dropping any bogus source cooldown so it doesn't sit "resting". */
    @Synchronized
    fun resume(id: String) {
        val t = tasks[id] ?: return
        if (t.state != "interrupted" && t.state != "offlinewait") return
        sourceCooldownUntil.remove(t.sourceId)
        repairFor(t); t.state = "queued"; t.error = ""; t.failClass = ""; pump(); persist()
    }

    /** Resume every paused task (interrupted OR offlinewait). offlinewait tasks are included because a manual
     *  "Resume all" must un-strand them — they otherwise only auto-resume on an offline→online transition,
     *  which never comes if the server is already back online. */
    @Synchronized
    fun resumeAll() {
        sourceCooldownUntil.clear() // a manual resume-all also drops resting cooldowns (mirrors resumeFromOffline)
        tasks.values.filter { it.state == "interrupted" || it.state == "offlinewait" }.forEach {
            repairFor(it); it.state = "queued"; it.error = ""; it.failClass = ""
        }
        pump(); persist()
    }

    fun interruptedCount(): Int = tasks.values.count { it.state == "interrupted" || it.state == "offlinewait" }
}
