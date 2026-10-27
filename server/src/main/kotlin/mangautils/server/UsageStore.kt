/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mangautils.core.config.AppConfig
import mangautils.core.util.SafeFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Rolling per-node resource usage, for capacity planning ("what should the next node have?"). Fed from each
 * health observation the pool already makes (heartbeat + background poll), so there's no extra polling and no
 * database: a 24h ring of samples per node (compute 1h/24h min/max/avg on demand) plus lifetime high-water
 * marks (so a peak older than 24h isn't lost), persisted to one small JSON.
 */
object UsageStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file get() = AppConfig.dataDir.resolve("worker-usage.json")
    private const val RING_MS = 24 * 3600_000L
    private const val RING_CAP = 5000
    private const val PERSIST_MS = 300_000L

    @Serializable data class Sample(val t: Long, val ramGb: Double, val ramTot: Double, val cpu: Double, val temp: Double, val diskGb: Double, val diskTot: Double)
    @Serializable data class Stat(val min: Double, val max: Double, val avg: Double)
    @Serializable data class Window(val samples: Int, val ram: Stat, val cpu: Stat, val temp: Stat, val disk: Stat)
    @Serializable data class UsageDto(val ramTotalGb: Double, val diskTotalGb: Double, val h1: Window, val h24: Window, val boot: Window, val ramSpark: List<Double>)

    // Lifetime high-water + running average per metric (never reset), so "since boot" survives beyond the ring.
    @Serializable private data class Life(var min: Double = Double.MAX_VALUE, var max: Double = 0.0, var sum: Double = 0.0, var n: Long = 0) {
        fun add(v: Double) { if (v < min) min = v; if (v > max) max = v; sum += v; n++ }
        fun stat(): Stat = if (n == 0L) ZERO else Stat(r1(min), r1(max), r1(sum / n))
    }
    @Serializable private data class NodeUsage(
        val ring: MutableList<Sample> = mutableListOf(),
        val ram: Life = Life(), val cpu: Life = Life(), val temp: Life = Life(), val disk: Life = Life(),
    )

    private val nodes = ConcurrentHashMap<String, NodeUsage>()
    @Volatile private var loaded = false
    @Volatile private var lastPersist = 0L

    @Synchronized private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            SafeFile.read(file) { runCatching { json.decodeFromString<Map<String, NodeUsage>>(it) }.getOrNull() }
                ?.forEach { (k, v) -> nodes[k] = v }
        }
    }

    /** Fold one health reading into [name]'s stats. No-op if the GB figures aren't present (offline poll). */
    fun record(name: String, m: WorkerMode.Metrics?) {
        if (m == null) return
        val ramGb = m.memUsedGb ?: return
        ensureLoaded()
        val diskGb = if (m.diskTotalGb != null && m.diskFreeGb != null) (m.diskTotalGb - m.diskFreeGb) else 0.0
        val now = System.currentTimeMillis()
        val s = Sample(now, ramGb, m.memTotalGb ?: 0.0, m.cpuPct ?: 0.0, m.tempC ?: 0.0, diskGb, m.diskTotalGb ?: 0.0)
        val u = nodes.getOrPut(name) { NodeUsage() }
        synchronized(u) {
            u.ring.add(s)
            while (u.ring.size > RING_CAP || (u.ring.isNotEmpty() && now - u.ring[0].t > RING_MS)) u.ring.removeAt(0)
            u.ram.add(ramGb); u.cpu.add(m.cpuPct ?: 0.0); u.temp.add(m.tempC ?: 0.0); u.disk.add(diskGb)
        }
        maybePersist(now)
    }

    fun usage(name: String): UsageDto? {
        ensureLoaded()
        val u = nodes[name] ?: return null
        val snap = synchronized(u) { u.ring.toList() }
        if (snap.isEmpty()) return null
        val now = System.currentTimeMillis()
        val latest = snap.last()
        val boot = synchronized(u) { Window(u.ram.n.toInt(), u.ram.stat(), u.cpu.stat(), u.temp.stat(), u.disk.stat()) }
        return UsageDto(
            ramTotalGb = r1(latest.ramTot), diskTotalGb = r1(latest.diskTot),
            h1 = window(snap.filter { now - it.t <= 3600_000L }),
            h24 = window(snap.filter { now - it.t <= RING_MS }),
            boot = boot,
            ramSpark = snap.takeLast(60).map { it.ramGb },
        )
    }

    private fun window(s: List<Sample>): Window {
        if (s.isEmpty()) return Window(0, ZERO, ZERO, ZERO, ZERO)
        fun st(sel: (Sample) -> Double) = Stat(r1(s.minOf(sel)), r1(s.maxOf(sel)), r1(s.sumOf(sel) / s.size))
        return Window(s.size, st { it.ramGb }, st { it.cpu }, st { it.temp }, st { it.diskGb })
    }

    private fun maybePersist(now: Long) {
        if (now - lastPersist < PERSIST_MS) return
        lastPersist = now
        runCatching { SafeFile.writeAtomic(file, json.encodeToString(nodes.toMap())) }
    }

    private fun r1(v: Double) = (v * 10).toLong() / 10.0
    private val ZERO = Stat(0.0, 0.0, 0.0)
}
