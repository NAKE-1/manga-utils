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
        val hour = s.autoUpdateHour.coerceIn(0, 23)
        // Run once a day at the chosen local hour: compute the delay to the next HH:00, then repeat daily.
        val now = java.time.ZonedDateTime.now()
        var next = now.withHour(hour).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val initialDelayMs = java.time.Duration.between(now, next).toMillis()
        log.info(
            "scheduled updates on: daily at {}:00 (first run in ~{}h, auto-download new: {})",
            String.format("%02d", hour), initialDelayMs / 3_600_000, s.autoDownloadNew,
        )
        task = exec.scheduleAtFixedRate(
            { runCatching { runNow() }.onFailure { log.warn("scheduled update failed: {}", it.message) } },
            initialDelayMs, TimeUnit.DAYS.toMillis(1), TimeUnit.MILLISECONDS,
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
