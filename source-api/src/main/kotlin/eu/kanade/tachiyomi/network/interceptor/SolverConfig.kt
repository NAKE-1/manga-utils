package eu.kanade.tachiyomi.network.interceptor

/**
 * Anti-detect solver sidecar (Plan #3). When `MU_SOLVER_URL` is set, requests to hard hosts (see
 * [FlareSolverrConfig.fetchThroughHosts]) are run as a same-origin IN-PAGE fetch inside the sidecar's real
 * browser — which both passes Cloudflare AND returns the XHR-only data MangaFire's `/api` serves only to a
 * same-origin request (a plain FlareSolverr navigation gets an empty stub). Falls back to FlareSolverr when
 * unset or unreachable. Env-only (no Settings UI) — it's an infrastructure endpoint like the FS URL.
 */
object SolverConfig {
    @Volatile
    var url: String? = System.getenv("MU_SOLVER_URL")?.trim()?.ifBlank { null }

    val enabled: Boolean get() = !url.isNullOrBlank()

    // A small ring of recent solver events so the web UI can toast "solver cleared / captcha solved" —
    // mirrors the FlareSolverr feed. Fed by SolverClient off the sidecar's response.
    data class Event(val id: Long, val host: String, val phase: String, val at: Long)

    private val seq = java.util.concurrent.atomic.AtomicLong()
    private val events = java.util.concurrent.ConcurrentLinkedDeque<Event>()

    fun record(host: String, phase: String) {
        events.addLast(Event(seq.incrementAndGet(), host, phase, System.currentTimeMillis()))
        while (events.size > 30) events.pollFirst()
    }

    fun lastEventId(): Long = seq.get()
    fun eventsSince(id: Long): List<Event> = events.filter { it.id > id }
}
