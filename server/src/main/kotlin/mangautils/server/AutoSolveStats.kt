package mangautils.server

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory telemetry for the MangaFire captcha auto-solver: a ring buffer of phase events (polled by the
 * client for the live "MF" toast, same shape as the FlareSolverr event feed) plus running counters and the
 * last-N attempts for the dev stats panel. Cheap, best-effort, reset on restart.
 */
object AutoSolveStats {
    data class Event(val id: Long, val phase: String, val detail: String) // phase: solving|retrying|solved|failed
    data class Attempt(val at: Long, val result: String, val clicks: Int, val tries: Int, val ms: Long)

    private val events = ConcurrentLinkedDeque<Event>()
    private val seq = AtomicLong(0)
    private val times = ConcurrentLinkedDeque<Long>() // solve durations (ms), for the average
    private val recent = ConcurrentLinkedDeque<Attempt>()

    @Volatile var solved = 0; private set
    @Volatile var failed = 0; private set
    @Volatile var reloads = 0; private set

    fun lastEventId(): Long = seq.get()
    fun eventsSince(id: Long): List<Event> = events.filter { it.id > id }

    @Synchronized
    private fun emit(phase: String, detail: String = "") {
        events.addLast(Event(seq.incrementAndGet(), phase, detail))
        while (events.size > 100) events.pollFirst()
    }

    fun solving() = emit("solving")
    fun retrying(reason: String) { reloads++; emit("retrying", reason) }
    fun solvedNow(clicks: Int, tries: Int, ms: Long, at: Long) {
        solved++
        times.addLast(ms); while (times.size > 50) times.pollFirst()
        recent.addLast(Attempt(at, "solved", clicks, tries, ms)); trimRecent()
        emit("solved", "$clicks clicks")
    }
    fun failedNow(tries: Int, ms: Long, at: Long) {
        failed++
        recent.addLast(Attempt(at, "failed", 0, tries, ms)); trimRecent()
        emit("failed", "$tries tries")
    }
    private fun trimRecent() { while (recent.size > 12) recent.pollFirst() }

    fun avgMs(): Long = times.toList().let { if (it.isEmpty()) 0L else it.sum() / it.size }
    fun recent(): List<Attempt> = recent.toList().reversed() // newest first
}
