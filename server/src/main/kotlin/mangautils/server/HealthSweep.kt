package mangautils.server

import eu.kanade.tachiyomi.network.interceptor.FlareSolverrConfig
import eu.kanade.tachiyomi.network.interceptor.HumanCheckState
import mangautils.core.config.SettingsStore
import mangautils.core.extension.InstalledStore
import mangautils.core.source.Diagnostics
import mangautils.core.source.SourceHealth
import mangautils.core.source.SourceManager
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proactive source health sweep: probes every installed source with a light [Diagnostics] run (one
 * sample — fetches the popular page + a cover, exercising both the API and the image CDN) and updates
 * [SourceHealth], so the dashboard reflects reality without you having to browse each source. Bounded
 * concurrency so it stays gentle. Run manually (dashboard button) or on a schedule.
 */
object HealthSweep {
    private val log = LoggerFactory.getLogger(javaClass)
    private val doneCount = AtomicInteger(0)

    @Volatile var running = false
        private set
    @Volatile var total = 0
        private set
    val done: Int get() = doneCount.get()

    @Synchronized
    fun start() {
        if (running) return
        running = true
        doneCount.set(0)
        total = 0
        Thread({ runSweep() }, "health-sweep").apply { isDaemon = true }.start()
    }

    private fun srcName(id: Long) = runCatching { SourceManager.loadSource(id)?.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: id.toString()

    private const val UP = "up"; private const val DOWN = "down"; private const val GATED = "gated"
    private const val SOLVER_REPROBE_DELAY_MS = 5000L // wait before re-probing a solver-backed host that just failed

    /** Apply a probe result to SourceHealth + fire the transition ping. Returns UP | DOWN | GATED (a
     *  Cloudflare/captcha gate, which leaves the status untouched — not an outage). */
    private fun classify(id: Long, r: Diagnostics.Result, wasDown: Boolean): String {
        if (r.ok) {
            SourceHealth.markUp(id); SourceHealth.markImagesUp(id); SourceHealth.setPing(id, r.pingMs.toLong())
            if (wasDown) Notifier.onSourceTransition(srcName(id), down = false) // recovered
            return UP
        }
        val host = runCatching { java.net.URI(r.baseUrl).host }.getOrNull()
        if (isGatedNotDown(host, r.error)) return GATED // CF/captcha gate — don't flip to down
        SourceHealth.markDown(id)
        if (!wasDown) Notifier.onSourceTransition(srcName(id), down = true) // newly down
        return DOWN
    }

    /** Wait (bounded) for [host]'s human-check to clear (the auto-solver working). */
    private fun waitForClear(host: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!HumanCheckState.isPending(host)) return true
            runCatching { Thread.sleep(1000) }
        }
        return !HumanCheckState.isPending(host)
    }

    private fun runSweep() {
        try {
            val ids = InstalledStore.list().flatMap { it.sources }.map { it.id }.distinct()
            total = ids.size
            val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "health-probe").apply { isDaemon = true } }
            try {
                val futures = ids.map { id ->
                    pool.submit {
                        runCatching {
                            val wasDown = SourceHealth.isDown(id)
                            var r = Diagnostics.run(id, samples = 1)
                            // Solver-backed hosts (MangaFire): a probe can transiently fail while the solver
                            // is re-clearing Cloudflare (e.g. right after an egress reset flushed the session).
                            // That's not an outage — give it a few seconds and re-probe once (the re-probe
                            // itself blocks on the solver, so it waits for the clear) before we'd call it down.
                            val host0 = runCatching { java.net.URI(r.baseUrl).host }.getOrNull()
                            if (!r.ok && host0 != null && !isGatedNotDown(host0, r.error) && FlareSolverrConfig.fetchesThrough(host0)) {
                                log.info("health: {} probe failed — solver may be re-clearing, re-probing in {}s", srcName(id), SOLVER_REPROBE_DELAY_MS / 1000)
                                runCatching { Thread.sleep(SOLVER_REPROBE_DELAY_MS) }
                                r = Diagnostics.run(id, samples = 1)
                            }
                            // Part B: a captcha gate with auto-solve ON — wait for the background solver to
                            // clear it, then re-probe once so the sweep reports the true state (usually up)
                            // instead of leaving it stale. Only for hosts actually pending a human-check.
                            if (classify(id, r, wasDown) == GATED) {
                                val host = runCatching { java.net.URI(r.baseUrl).host }.getOrNull()
                                val autoSolve = runCatching { SettingsStore.get().autoSolveCaptcha }.getOrDefault(false)
                                if (host != null && autoSolve && HumanCheckState.isPending(host) && waitForClear(host, 25_000)) {
                                    classify(id, Diagnostics.run(id, samples = 1), wasDown)
                                }
                            }
                        }.onFailure { log.debug("health probe {} failed: {}", id, it.message) }
                        doneCount.incrementAndGet()
                    }
                }
                futures.forEach { runCatching { it.get(60, TimeUnit.SECONDS) } }
            } finally {
                pool.shutdownNow()
            }
            SourceHealth.flush()
            log.info("health sweep done - {} sources probed", total)
        } catch (e: Exception) {
            log.warn("health sweep failed: {}", e.message)
        } finally {
            running = false
        }
    }
}
