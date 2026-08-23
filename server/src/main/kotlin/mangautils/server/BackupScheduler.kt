/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.server

import mangautils.core.backup.LocalBackups
import mangautils.core.config.SettingsStore
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Writes a rotating local `.mudata` snapshot once a day at a configured hour when enabled in Settings.
 * Mirrors [HealthScheduler]. A backup can also be triggered manually from Settings.
 */
object BackupScheduler {
    private val log = LoggerFactory.getLogger(javaClass)
    private val exec = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "backup-sched").apply { isDaemon = true }
    }
    @Volatile private var task: ScheduledFuture<*>? = null

    @Synchronized
    fun reschedule() {
        task?.cancel(false)
        task = null
        val s = runCatching { SettingsStore.get() }.getOrNull() ?: return
        if (!s.autoBackupEnabled) { log.debug("scheduled backup disabled"); return }
        val hour = s.autoBackupHour.coerceIn(0, 23)
        val keep = s.autoBackupKeep
        val now = java.time.ZonedDateTime.now()
        var next = now.withHour(hour).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val initialDelayMs = java.time.Duration.between(now, next).toMillis()
        log.info("scheduled backup on: daily at {}:00 (first run in ~{}h, keep {})", String.format("%02d", hour), initialDelayMs / 3_600_000, keep)
        task = exec.scheduleAtFixedRate(
            { runCatching { LocalBackups.create("auto", keep) }.onFailure { log.warn("scheduled backup failed: {}", it.message) } },
            initialDelayMs, TimeUnit.DAYS.toMillis(1), TimeUnit.MILLISECONDS,
        )
    }
}
