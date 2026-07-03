/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.server

import mangautils.core.config.SettingsStore
import mangautils.core.library.LibraryService
import mangautils.core.library.UpdateResult
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Background library-update scheduler. When enabled in Settings, periodically re-checks the whole
 * library for new chapters and — if autoDownloadNew is on — enqueues the new chapters for download.
 */
object UpdateScheduler {
    private val log = LoggerFactory.getLogger(javaClass)
    private val exec = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "lib-update-sched").apply { isDaemon = true }
    }
    @Volatile private var task: ScheduledFuture<*>? = null
    @Volatile var lastRunAt: Long = 0L; private set

    /** (Re)configure the periodic task from the current settings. Safe to call repeatedly. */
    @Synchronized
    fun reschedule() {
        task?.cancel(false)
        task = null
        val s = runCatching { SettingsStore.get() }.getOrNull() ?: return
        if (!s.autoUpdate) { log.debug("scheduled updates disabled"); return }
        val hours = s.autoUpdateHours.coerceIn(1, 168).toLong()
        log.info("scheduled updates on: every {}h (auto-download new: {})", hours, s.autoDownloadNew)
        // First run after one interval, not at boot (avoid an update storm on every restart).
        task = exec.scheduleAtFixedRate(
            { runCatching { runNow() }.onFailure { log.warn("scheduled update failed: {}", it.message) } },
            hours, hours, TimeUnit.HOURS,
        )
    }

    /** Run one update pass over the whole library now (used by the scheduler). */
    fun runNow(): List<UpdateResult> {
        val results = LibraryService.update()
        lastRunAt = System.currentTimeMillis()
        val newCount = results.sumOf { it.newChapters.size }
        log.info("scheduled update: {} new chapter(s) across {} title(s)", newCount, results.count { it.newChapters.isNotEmpty() })
        autoDownloadNew(results)
        return results
    }

    /** If autoDownloadNew is on, enqueue every newly-found chapter for download. */
    fun autoDownloadNew(results: List<UpdateResult>) {
        if (!runCatching { SettingsStore.get().autoDownloadNew }.getOrDefault(false)) return
        results.filter { it.newChapters.isNotEmpty() }.forEach { r ->
            val chapters = r.newChapters.map { DownloadQueue.Chapter(it.url, it.name) }
            if (chapters.isNotEmpty()) {
                log.info("auto-downloading {} new chapter(s) of '{}'", chapters.size, r.entry.title)
                DownloadQueue.enqueue(r.entry.sourceId, r.entry.mangaUrl, r.entry.title, chapters)
            }
        }
    }
}
