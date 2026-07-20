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
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

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
        @Volatile var state = "queued" // queued | running | done | failed | stopped | interrupted
        @Volatile var doneCount = 0
        @Volatile var failedCount = 0
        @Volatile var currentChapter = ""
        @Volatile var currentChapterUrl = ""
        @Volatile var pagesDone = 0
        @Volatile var pagesTotal = 0
        @Volatile var bytesPerSec = 0.0
        @Volatile var lastLogAt = 0L // throttle for the live progress log line
        @Volatile var error = ""
        // Chapters that failed (for the Retry button); URLs of finished chapters (live progress).
        // Keyed by URL, not name: several scanlations of one chapter share a name, so a name-keyed
        // set would mark every version done as soon as any one of them finished.
        val failed = CopyOnWriteArrayList<Chapter>()
        val finishedUrls = ConcurrentHashMap.newKeySet<String>()
        val active get() = state == "queued" || state == "running"
        fun nameToUrl(name: String) = chapters.firstOrNull { it.name == name }?.url ?: ""
    }

    private val tasks = ConcurrentHashMap<String, Task>()
    private val futures = ConcurrentHashMap<String, Future<*>>()

    @Volatile private var poolSize = parallelism()
    @Volatile private var pool = newPool(poolSize)
    private var seq = 0L

    private fun parallelism() = runCatching { SettingsStore.get().parallelDownloads }.getOrDefault(3).coerceIn(1, 8)
    private fun perSourceParallel() = runCatching { SettingsStore.get().perSourceParallel }.getOrDefault(false)
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
        val perSource = perSourceParallel()
        val running = tasks.values.filter { it.state == "running" }
        var slots = poolSize - running.size
        if (slots <= 0) return
        val busySources = running.map { it.sourceId }.toMutableSet()
        for (task in tasks.values.sortedBy { it.order }) {
            if (slots <= 0) break
            if (task.state != "queued") continue
            if (!perSource && task.sourceId in busySources) continue
            task.state = "running"
            busySources.add(task.sourceId)
            slots--
            futures[task.id] = pool.submit { run(task) }
        }
    }

    private fun run(task: Task) {
        if (Thread.currentThread().isInterrupted) { task.state = "stopped"; return }
        task.state = "running"
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
            val job = dm.download(SourceRef(task.sourceId, task.mangaUrl), select = ChapterSelect.Urls(task.chapters.map { it.url }.toSet()))
            // Reconcile from the per-chapter attempt trace: a chapter is done if any candidate ok/skipped.
            // Reconcile by URL, not by chapter name: several scanlations of a chapter share a name, so
            // grouping attempts by name merges them and undercounts a versioned download.
            val doneUrls = task.finishedUrls.toMutableSet()
            // A chapter already on disk finishes without emitting progress, so it needs adding by hand.
            // Deliberately narrow: a skip because the source gave up ("source unavailable") is NOT done,
            // it just never ran — treating it as done is what hid 55 chapters from Retry.
            job.attempts
                .filter { it.outcome == "skipped" && it.message.orEmpty().contains("already", ignoreCase = true) }
                .flatMap { a -> task.chapters.filter { it.name == a.target } }
                .forEach { doneUrls.add(it.url) }
            task.doneCount = doneUrls.size
            task.bytesPerSec = 0.0
            if (task.state == "stopped") {
                // Stopped by the user: keep the chapters that finished, drop the rest silently
                // (the in-progress chapter was never written to disk). Don't flag them as "failed".
                task.failed.clear()
                task.failedCount = 0
            } else {
                task.failed.clear()
                // Anything without a successful outcome is retryable — including chapters the job never
                // reached because the source's failure breaker tripped part-way through.
                task.failed.addAll(task.chapters.filter { it.url !in doneUrls })
                task.failedCount = task.failed.size
                task.state = if (task.failedCount == 0) "done" else "failed"
                if (task.failedCount > 0) task.error = explainFailure(task, job.attempts)
            }
        }.onFailure {
            task.bytesPerSec = 0.0
            if (Thread.currentThread().isInterrupted || it is InterruptedException) task.state = "stopped"
            else { task.state = "failed"; task.error = it.message ?: it::class.simpleName ?: "failed" }
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
                Notifier.onDownloadFailed(task.sourceId, task.mangaUrl, task.mangaTitle, task.failedCount, task.error)
            }
            "stopped" -> log.info("DOWNLOAD STOPPED - '{}' ({}/{} chapters kept)", task.mangaTitle, task.doneCount, task.total)
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

    // ---- persistence: survive a restart (Part B) ------------------------------------------------
    @Serializable private data class PChap(val url: String, val name: String)
    @Serializable private data class PTask(
        val id: String, val sourceId: Long, val mangaUrl: String, val title: String, val source: String,
        val order: Int, val tag: String, val state: String,
        val chapters: List<PChap>, val done: List<String>, val failed: List<String>, val error: String,
    )
    @Serializable private data class PFile(val version: Int = 1, val savedAt: Long, val tasks: List<PTask>)

    private val pjson = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val queueFile get() = AppConfig.dataDir.resolve("downloadqueue.json")
    private fun srcName(id: Long) = runCatching { SourceManager.loadSource(id)?.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: id.toString()

    @Synchronized
    private fun persist() {
        runCatching {
            val list = tasks.values.sortedBy { it.order }.map { t ->
                PTask(t.id, t.sourceId, t.mangaUrl, t.mangaTitle, srcName(t.sourceId), t.order, t.tag, t.state,
                    t.chapters.map { PChap(it.url, it.name) }, t.finishedUrls.toList(), t.failed.map { it.url }, t.error)
            }
            queueFile.createParentDirectories()
            queueFile.writeText(pjson.encodeToString(PFile(savedAt = System.currentTimeMillis(), tasks = list)))
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
        val pf = runCatching { if (queueFile.exists()) pjson.decodeFromString<PFile>(queueFile.readText()) else null }.getOrNull() ?: return
        if (pf.tasks.isEmpty()) return
        var interrupted = 0; var kept = 0
        for (pt in pf.tasks) {
            val task = Task(pt.id, pt.sourceId, pt.mangaUrl, pt.title, pt.chapters.map { Chapter(it.url, it.name) }).apply { order = pt.order; tag = pt.tag }
            // Older queue files stored names here; map them back so a restart doesn't lose progress.
            val urlByName = pt.chapters.associateBy({ it.name }, { it.url })
            pt.done.forEach { task.finishedUrls.add(if (it.startsWith("http") || it in urlByName.values) it else urlByName[it] ?: it) }
            task.doneCount = task.finishedUrls.size
            when (pt.state) {
                "queued", "running", "interrupted" -> { task.state = "interrupted"; interrupted++ }
                else -> {
                    task.state = pt.state
                    // Match on URL, falling back to name for queue files written before this change.
                    val byUrl = pt.chapters.associateBy { it.url }
                    val byName = pt.chapters.associateBy { it.name }
                    task.failed.addAll(
                        pt.failed.mapNotNull { k -> (byUrl[k] ?: byName[k])?.let { Chapter(it.url, it.name) } },
                    )
                    task.failedCount = task.failed.size
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

    /** Resume one interrupted task (re-queue after discarding any half-written chapters). */
    @Synchronized
    fun resume(id: String) {
        val t = tasks[id] ?: return
        if (t.state != "interrupted") return
        repairFor(t); t.state = "queued"; pump(); persist()
    }

    /** Resume every interrupted task. */
    @Synchronized
    fun resumeAll() {
        tasks.values.filter { it.state == "interrupted" }.forEach { repairFor(it); it.state = "queued" }
        pump(); persist()
    }

    fun interruptedCount(): Int = tasks.values.count { it.state == "interrupted" }
}
