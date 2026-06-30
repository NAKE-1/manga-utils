/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.server

import mangautils.core.config.SettingsStore
import mangautils.core.download.ChapterSelect
import mangautils.core.download.DownloadManager
import mangautils.core.download.SourceRef
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
        @Volatile var state = "queued" // queued | running | done | failed | stopped
        @Volatile var doneCount = 0
        @Volatile var failedCount = 0
        @Volatile var currentChapter = ""
        @Volatile var currentChapterUrl = ""
        @Volatile var pagesDone = 0
        @Volatile var pagesTotal = 0
        @Volatile var bytesPerSec = 0.0
        @Volatile var error = ""
        // Chapters that failed (for the Retry button); names of finished chapters (live progress).
        val failed = CopyOnWriteArrayList<Chapter>()
        val finishedNames = ConcurrentHashMap.newKeySet<String>()
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
    fun enqueue(sourceId: Long, mangaUrl: String, mangaTitle: String, chapters: List<Chapter>) {
        if (chapters.isEmpty()) return
        val id = "dl-${seq++}-${System.nanoTime().toString(36).takeLast(4)}"
        tasks[id] = Task(id, sourceId, mangaUrl, mangaTitle, chapters)
        pump()
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
        for (task in tasks.values.sortedBy { it.id }) {
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
        runCatching {
            val s = SettingsStore.get()
            val dm = DownloadManager(
                concurrency = s.downloadConcurrency,
                retries = s.downloadRetries,
                existingPolicy = s.existingBehavior,
                listener = { p ->
                    task.currentChapter = p.chapter
                    task.currentChapterUrl = task.nameToUrl(p.chapter)
                    task.pagesDone = p.pagesDone
                    task.pagesTotal = p.pagesTotal
                    task.bytesPerSec = p.bytesPerSecond
                    if (p.finished && p.pagesTotal > 0) task.finishedNames.add(p.chapter)
                    task.doneCount = task.finishedNames.size
                },
            )
            val job = dm.download(SourceRef(task.sourceId, task.mangaUrl), select = ChapterSelect.Urls(task.chapters.map { it.url }.toSet()))
            // Reconcile from the per-chapter attempt trace: a chapter is done if any candidate ok/skipped.
            val byTarget = job.attempts.groupBy { it.target }
            val doneNames = byTarget.filterValues { atts -> atts.any { it.outcome == "ok" || it.outcome == "skipped" } }.keys
            task.doneCount = doneNames.size
            task.failed.clear()
            task.failed.addAll(task.chapters.filter { it.name !in doneNames })
            task.failedCount = task.failed.size
            task.bytesPerSec = 0.0
            task.state = if (task.failedCount == 0) "done" else "failed"
            if (task.failedCount > 0) task.error = job.error.ifBlank { "${task.failedCount} chapter(s) failed" }
        }.onFailure {
            task.bytesPerSec = 0.0
            if (Thread.currentThread().isInterrupted || it is InterruptedException) task.state = "stopped"
            else { task.state = "failed"; task.error = it.message ?: it::class.simpleName ?: "failed" }
            log.debug("download task {} ended: {}", task.id, task.state)
        }
        futures.remove(task.id)
        pump() // a slot (and this source) just freed — start the next eligible manga
    }

    fun tasks(): List<Task> = tasks.values.sortedBy { it.id }
    fun queuedCount(): Int = tasks.values.count { it.state == "queued" }
    fun activeCount(): Int = tasks.values.count { it.active }
    fun totalBytesPerSec(): Double = tasks.values.filter { it.state == "running" }.sumOf { it.bytesPerSec }

    fun stop(id: String) {
        futures[id]?.cancel(true)
        tasks[id]?.let { if (it.active) it.state = "stopped" }
        pump()
    }
    fun stopAll() {
        futures.values.forEach { it.cancel(true) }
        tasks.values.forEach { if (it.active) it.state = "stopped" }
    }
    fun clearFinished() { tasks.entries.removeIf { !it.value.active } }
}
