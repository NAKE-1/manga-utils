/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.server

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Server-side internet reachability. "Offline" for this app means the SERVER can't reach the outside world
 * (the phone still reaches the server on the LAN), so connectivity is judged here and pushed to clients.
 *
 * Probes a few reliable, always-up hosts — NEVER manga sources, which can be Cloudflare-walled or blocked
 * and would false-flag offline. Online if ANY probe host answers; to avoid flapping on a single blip it only
 * flips to OFFLINE after [FAIL_THRESHOLD] consecutive rounds where every host failed, and back to ONLINE on
 * the first success. Everything else (schedulers, the download queue, the UI) reads [online].
 */
object NetMonitor {
    private val log = LoggerFactory.getLogger(javaClass)

    // Reliable connectivity endpoints. A response of ANY status means we reached the internet; only a
    // DNS/connection/timeout failure counts as unreachable.
    private val probes = listOf(
        "http://www.google.com/generate_204", // Google's connectivity-check endpoint (204, tiny)
        "https://1.1.1.1/cdn-cgi/trace", // Cloudflare
        "https://am.i.mullvad.net/connected", // Mullvad
    )

    private const val FAIL_THRESHOLD = 2 // consecutive all-hosts-fail rounds before declaring offline
    private const val INTERVAL_ONLINE_SEC = 45L // healthy: probe rarely, near-zero overhead
    private const val INTERVAL_OFFLINE_SEC = 8L // down: probe often so coming back online is caught fast
    private const val PROBE_TIMEOUT_SEC = 4L

    private val client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(PROBE_TIMEOUT_SEC))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @Volatile var online: Boolean = true; private set
    @Volatile var lastChecked: Long = 0L; private set
    @Volatile var since: Long = System.currentTimeMillis(); private set // when the current state began

    @Volatile private var consecutiveFails = 0
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "net-monitor").apply { isDaemon = true } }

    fun start() {
        // Probe once SYNCHRONOUSLY before returning so `online` is accurate the moment we start serving —
        // otherwise it defaults to true and a download/search fired right after boot (or the debounce) can
        // misfire as "source busy" until the first async probe lands. No debounce at boot: nothing to flap
        // against yet, so trust the first read.
        online = runCatching { probes.any { reach(it) } }.getOrDefault(true)
        lastChecked = System.currentTimeMillis()
        since = lastChecked
        consecutiveFails = if (online) 0 else FAIL_THRESHOLD // internally "confirmed offline" so later logic is consistent
        if (!online) log.info("network OFFLINE at startup - no egress to any probe host")
        scheduleNext(if (online) INTERVAL_ONLINE_SEC else INTERVAL_OFFLINE_SEC)
        log.info("network monitor started (probing every {}s, {}s while offline)", INTERVAL_ONLINE_SEC, INTERVAL_OFFLINE_SEC)
    }

    // Self-rescheduling so the cadence can differ by state: rare when healthy, frequent while offline so
    // a reconnect is caught within seconds instead of up to a full online-interval.
    private fun scheduleNext(delaySec: Long) {
        exec.schedule({
            runCatching { probeOnce() }
            scheduleNext(if (online) INTERVAL_ONLINE_SEC else INTERVAL_OFFLINE_SEC)
        }, delaySec, TimeUnit.SECONDS)
    }

    /** Register a callback fired whenever the online state flips (true = came online, false = went offline). */
    fun onChange(listener: (Boolean) -> Unit) { listeners.add(listener) }

    /** Force an immediate re-probe (the UI's "Check again" button) and return the fresh state. */
    fun checkNow(): Boolean {
        runCatching { probeOnce() }
        return online
    }

    /** A network op (e.g. a download batch) just failed in a way that could mean the server lost internet.
     *  Probe now; since the failing op is corroborating evidence, flip offline immediately if unreachable
     *  (skip the debounce) so we don't sit in a doomed retry-wait. If the internet IS reachable, no-op — the
     *  source was genuinely busy. Runs on the monitor thread, serialized with the periodic probe. */
    fun reportPossibleOutage() {
        exec.execute {
            val reachable = probes.any { reach(it) }
            lastChecked = System.currentTimeMillis()
            if (reachable) consecutiveFails = 0
            else if (online) flip(false)
        }
    }

    private fun probeOnce() {
        val reachable = probes.any { reach(it) }
        lastChecked = System.currentTimeMillis()
        if (reachable) {
            consecutiveFails = 0
            if (!online) flip(true)
        } else {
            consecutiveFails++
            if (online && consecutiveFails >= FAIL_THRESHOLD) flip(false)
        }
    }

    private fun flip(nowOnline: Boolean) {
        online = nowOnline
        since = System.currentTimeMillis()
        log.info("network {}", if (nowOnline) "ONLINE" else "OFFLINE - no egress to any probe host")
        listeners.forEach { runCatching { it(nowOnline) } }
    }

    /** True if the host answered at all (any HTTP status). Only a DNS/connect/timeout failure is "unreachable". */
    private fun reach(url: String): Boolean = runCatching {
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(PROBE_TIMEOUT_SEC))
            .GET().build()
        client.send(req, HttpResponse.BodyHandlers.discarding())
        true
    }.getOrDefault(false)
}
