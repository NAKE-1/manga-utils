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
 *
 * [onNeeded] fires once when a host first becomes blocked (used for the Discord ping); [onCleared] fires
 * when it's resolved (used to resume downloads that were waiting on it). Both fire only on a real
 * transition, so repeated blocked requests / repeated successes don't spam.
 */
object HumanCheckState {
    private val pending = ConcurrentHashMap<String, Long>() // host -> first-seen epoch ms; any flagged block
    // Hosts that need a HUMAN to solve (auto-solve is off / declined, or it tried and gave up). The in-app
    // "verify" banner reads THIS, not `pending`, so it doesn't nag while the auto-solver is mid-clear.
    private val manual = ConcurrentHashMap<String, Long>()

    @Volatile var onNeeded: ((host: String) -> Unit)? = null
    @Volatile var onCleared: ((host: String) -> Unit)? = null

    fun needed(host: String) {
        if (pending.putIfAbsent(host, System.currentTimeMillis()) == null) {
            runCatching { onNeeded?.invoke(host) }
        }
    }

    fun cleared(host: String) {
        manual.remove(host)
        if (pending.remove(host) != null) {
            runCatching { onCleared?.invoke(host) }
        }
    }

    /** Flag that a host needs a manual solve (surfaces the in-app banner). */
    fun needManual(host: String) { manual.putIfAbsent(host, System.currentTimeMillis()) }

    fun isPending(host: String): Boolean = pending.containsKey(host)

    /** host -> since-ms, oldest first. All flagged blocks (for downloads / health / library). */
    fun snapshot(): List<Pair<String, Long>> = pending.entries.map { it.key to it.value }.sortedBy { it.second }

    /** host -> since-ms, oldest first. Only hosts that need a HUMAN (drives the "verify" banner). */
    fun manualSnapshot(): List<Pair<String, Long>> = manual.entries.map { it.key to it.value }.sortedBy { it.second }

    /** True when a response body is an interactive human challenge (NOT the passive "Just a moment" JS one). */
    fun isHumanChallenge(body: String): Boolean = MARKERS.any { body.contains(it, ignoreCase = true) }

    private val MARKERS = listOf("captcha_required", "@waf/challenge", "verify you're human", "click the shapes")
}
