/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.source

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-source circuit breaker. After [threshold] consecutive failures a source is "opened": its calls
 * fail **instantly** for [cooldownMs] instead of each one hanging on the full timeout (7s images,
 * up to 20s source calls). When the cooldown elapses exactly one call is let through as a probe —
 * success closes the breaker, another failure re-opens it. Turns "a down source is annoying for a
 * minute" into "instant fail, then auto-recover when it's back".
 */
class Circuit(private val threshold: Int, private val cooldownMs: Long) {
    private class State {
        var fails = 0
        var openUntil = 0L
        var probing = false
    }

    private val states = ConcurrentHashMap<Long, State>()

    /** True → skip the call and fail fast. Grants exactly one probe once the cooldown elapses. */
    fun isOpen(id: Long): Boolean {
        val s = states[id] ?: return false
        synchronized(s) {
            if (s.openUntil == 0L) return false // closed
            if (System.currentTimeMillis() < s.openUntil) return true // cooling down → fast-fail
            // cooldown elapsed → half-open: let one probe through, fast-fail everyone else.
            if (s.probing) return true
            s.probing = true
            return false
        }
    }

    fun recordSuccess(id: Long) {
        states[id]?.let { s -> synchronized(s) { s.fails = 0; s.openUntil = 0L; s.probing = false } }
    }

    fun recordFailure(id: Long) {
        val s = states.getOrPut(id) { State() }
        synchronized(s) {
            s.fails++
            s.probing = false
            if (s.fails >= threshold) s.openUntil = System.currentTimeMillis() + cooldownMs
        }
    }
}

object SourceCircuits {
    /** Source API calls (search / browse / details): a down or blocked source (403 / timeout). */
    val api = Circuit(threshold = 3, cooldownMs = 30_000)

    /** Image fetches (pages / covers): a dead CDN like atsu.moe. Higher threshold (chapters have many
     *  pages) and a shorter cooldown so it recovers quickly once the host is back. */
    val images = Circuit(threshold = 5, cooldownMs = 20_000)
}
