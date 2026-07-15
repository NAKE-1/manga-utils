/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.server

import mangautils.core.config.SettingsStore
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Runs [HealthSweep] once a day at a configured hour when enabled in Settings. Mirrors
 * [UpdateScheduler]. The sweep can also be triggered manually from the dashboard.
 */
object HealthScheduler {
    private val log = LoggerFactory.getLogger(javaClass)
    private val exec = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "health-sched").apply { isDaemon = true }
    }
    @Volatile private var task: ScheduledFuture<*>? = null

    @Synchronized
    fun reschedule() {
        task?.cancel(false)
        task = null
        val s = runCatching { SettingsStore.get() }.getOrNull() ?: return
        if (!s.healthCheckEnabled) { log.debug("scheduled health check disabled"); return }
        val hour = s.healthCheckHour.coerceIn(0, 23)
        val now = java.time.ZonedDateTime.now()
        var next = now.withHour(hour).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val initialDelayMs = java.time.Duration.between(now, next).toMillis()
        log.info("scheduled health check on: daily at {}:00 (first run in ~{}h)", String.format("%02d", hour), initialDelayMs / 3_600_000)
        task = exec.scheduleAtFixedRate(
            { runCatching { HealthSweep.start() }.onFailure { log.warn("scheduled health check failed: {}", it.message) } },
            initialDelayMs, TimeUnit.DAYS.toMillis(1), TimeUnit.MILLISECONDS,
        )
    }
}
