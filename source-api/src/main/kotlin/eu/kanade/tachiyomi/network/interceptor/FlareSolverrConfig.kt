package eu.kanade.tachiyomi.network.interceptor

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * Live FlareSolverr settings, pushed in from the app's SettingsStore. Read per-request by
 * [FlareSolverrInterceptor] so toggling it in the UI takes effect without rebuilding the shared
 * OkHttp client (which is a process-wide singleton).
 */
object FlareSolverrConfig {
    @Volatile var enabled: Boolean = false
    @Volatile var url: String = "http://localhost:8191"
    @Volatile var session: String = "mangautils"
    @Volatile var sessionTtlMinutes: Int = 15
    @Volatile var timeoutMs: Long = 60_000

    /**
     * Hosts we've cleared → the User-Agent the cf_clearance is bound to. Read by a *network*
     * interceptor (so it runs after the extension's own interceptors and wins) to force that UA on
     * every request to the host — otherwise an extension that sets its own UA invalidates the cookie.
     */
    val solvedUserAgents = java.util.concurrent.ConcurrentHashMap<String, String>()

    // A small ring of recent solve events so the web UI can toast "solving / solved" — the frontend
    // can't see server logs, and a solve blocks for a few seconds, so this explains the pause.
    data class SolveEvent(val id: Long, val host: String, val phase: String, val cookies: Int, val at: Long)

    private val seq = java.util.concurrent.atomic.AtomicLong()
    private val events = java.util.concurrent.ConcurrentLinkedDeque<SolveEvent>()

    fun recordSolveStart(host: String) = push(host, "solving", 0)
    fun recordSolveDone(host: String, cookies: Int) = push(host, "solved", cookies)
    private fun push(host: String, phase: String, cookies: Int) {
        events.addLast(SolveEvent(seq.incrementAndGet(), host, phase, cookies, System.currentTimeMillis()))
        while (events.size > 30) events.pollFirst()
    }

    fun lastEventId(): Long = seq.get()
    fun eventsSince(sinceId: Long): List<SolveEvent> = events.filter { it.id > sinceId }
}
