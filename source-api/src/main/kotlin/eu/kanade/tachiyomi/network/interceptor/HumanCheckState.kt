package eu.kanade.tachiyomi.network.interceptor

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks hosts that hit an INTERACTIVE "verify you're human" challenge (e.g. MangaFire's
 * `/@waf/challenge` "click the shapes in order") — the kind no auto-solver (JCEF self-heal, FlareSolverr)
 * can pass. The UI polls this and prompts the user to solve it once in the WebView; a later success (or an
 * explicit clear) removes the flag.
 *
 * It's flagged from the shared network interceptor, so EVERY path routes through it — search, browse,
 * chapter lists, a manual/overnight library update, and downloads all raise the same single prompt.
 */
object HumanCheckState {
    private val pending = ConcurrentHashMap<String, Long>() // host -> first-seen epoch ms

    fun needed(host: String) { pending.putIfAbsent(host, System.currentTimeMillis()) }

    fun cleared(host: String) { pending.remove(host) }

    /** host -> since-ms, oldest first. */
    fun snapshot(): List<Pair<String, Long>> = pending.entries.map { it.key to it.value }.sortedBy { it.second }

    /** True when a response body is an interactive human challenge (NOT the passive "Just a moment" JS one). */
    fun isHumanChallenge(body: String): Boolean = MARKERS.any { body.contains(it, ignoreCase = true) }

    private val MARKERS = listOf("captcha_required", "@waf/challenge", "verify you're human", "click the shapes")
}
