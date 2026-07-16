package mangautils.server

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
                            val r = Diagnostics.run(id, samples = 1)
                            if (r.ok) {
                                SourceHealth.markUp(id)
                                SourceHealth.markImagesUp(id)
                                SourceHealth.setPing(id, r.pingMs.toLong())
                                if (wasDown) Notifier.onSourceTransition(srcName(id), down = false) // recovered
                            } else if (r.error?.contains("Cloudflare", ignoreCase = true) != true) {
                                // A Cloudflare block shows via cfState, not "down"; a real failure marks down.
                                SourceHealth.markDown(id)
                                if (!wasDown) Notifier.onSourceTransition(srcName(id), down = true) // newly down
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
