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
import mangautils.core.status.JobState
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Web download queue: each task is ONE chapter, run on a small thread pool so several chapters
 * download in parallel. A [DownloadManager] listener feeds live page/byte progress into the task so
 * the Downloads screen can show a per-chapter bar + speed (like a real download manager). Tasks can
 * be stopped (the worker thread is interrupted, which aborts the in-flight network calls).
 */
object DownloadQueue {
    private val log = LoggerFactory.getLogger(javaClass)

    class Task(
        val id: String,
        val sourceId: Long,
        val mangaUrl: String,
        val mangaTitle: String,
        val chapterUrl: String,
        val chapterName: String,
    ) {
        @Volatile var state: String = "queued" // queued | running | done | failed | stopped
        @Volatile var pagesDone: Int = 0
        @Volatile var pagesTotal: Int = 0
        @Volatile var bytesPerSec: Double = 0.0
        @Volatile var error: String = ""
        val active get() = state == "queued" || state == "running"
    }

    private val tasks = ConcurrentHashMap<String, Task>()
    private val futures = ConcurrentHashMap<String, Future<*>>()

    @Volatile private var poolSize = parallelism()
    @Volatile private var pool = newPool(poolSize)
    private var seq = 0L

    private fun parallelism() = runCatching { SettingsStore.get().parallelDownloads }.getOrDefault(3).coerceIn(1, 8)
    private fun newPool(n: Int) = Executors.newFixedThreadPool(n) { r -> Thread(r, "dl-worker").apply { isDaemon = true } }

    /** Re-create the pool if the parallelism setting changed (waiting tasks just re-queue). */
    @Synchronized
    private fun ensurePool() {
        val want = parallelism()
        if (want != poolSize) { poolSize = want; pool = newPool(want) }
    }

    @Synchronized
    fun enqueueChapter(sourceId: Long, mangaUrl: String, mangaTitle: String, chapterUrl: String, chapterName: String) {
        ensurePool()
        val id = "dl-${seq++}-${System.nanoTime().toString(36).takeLast(4)}"
        val task = Task(id, sourceId, mangaUrl, mangaTitle, chapterUrl, chapterName)
        tasks[id] = task
        futures[id] = pool.submit { run(task) }
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
                listener = { p -> task.pagesDone = p.pagesDone; task.pagesTotal = p.pagesTotal; task.bytesPerSec = p.bytesPerSecond },
            )
            val job = dm.download(SourceRef(task.sourceId, task.mangaUrl), select = ChapterSelect.Urls(setOf(task.chapterUrl)))
            if (job.state == JobState.DONE) task.state = "done" else { task.state = "failed"; task.error = job.error }
        }.onFailure {
            if (Thread.currentThread().isInterrupted || it is InterruptedException) task.state = "stopped"
            else { task.state = "failed"; task.error = it.message ?: it::class.simpleName ?: "failed" }
            log.debug("download task {} ended: {}", task.id, task.state)
        }
        futures.remove(task.id)
    }

    fun tasks(): List<Task> = tasks.values.sortedBy { it.id }
    fun queuedCount(): Int = tasks.values.count { it.state == "queued" }
    fun activeCount(): Int = tasks.values.count { it.active }
    fun totalBytesPerSec(): Double = tasks.values.filter { it.state == "running" }.sumOf { it.bytesPerSec }

    fun stop(id: String) {
        futures[id]?.cancel(true)
        tasks[id]?.let { if (it.active) it.state = "stopped" }
    }
    fun stopAll() {
        futures.values.forEach { it.cancel(true) }
        tasks.values.forEach { if (it.active) it.state = "stopped" }
    }
    fun clearFinished() { tasks.entries.removeIf { !it.value.active } }
}
