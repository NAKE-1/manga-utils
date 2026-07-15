/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.source

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mangautils.core.config.AppConfig
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Per-source reachability, updated on every browse/search/details (API health) and reader page fetch
 * (image health): a non-Cloudflare failure marks it down, any success marks it up. Now also stamps
 * last-ok / last-fail / last-ping timestamps and persists to `data/sourcehealth.json`, so the health
 * dashboard survives a restart (the proactive sweep refreshes it). Writes are throttled — the hot mark
 * paths update memory instantly and flush to disk at most every few seconds.
 */
object SourceHealth {
    @Serializable
    data class Rec(
        var down: Boolean = false,
        var imagesDown: Boolean = false,
        var lastOkMs: Long = 0,
        var lastFailMs: Long = 0,
        var lastPingMs: Long = 0,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val recs = ConcurrentHashMap<Long, Rec>()
    private val file get() = AppConfig.dataDir.resolve("sourcehealth.json")
    @Volatile private var loaded = false
    @Volatile private var lastSaveMs = 0L

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                if (file.exists()) json.decodeFromString<Map<String, Rec>>(file.readText())
                    .forEach { (k, v) -> k.toLongOrNull()?.let { recs[it] = v } }
            }
            loaded = true
        }
    }

    private fun rec(id: Long): Rec { ensureLoaded(); return recs.getOrPut(id) { Rec() } }

    private fun touchSave(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSaveMs < 3000) return
        lastSaveMs = now
        runCatching {
            file.createParentDirectories()
            file.writeText(json.encodeToString(recs.mapKeys { it.key.toString() }))
        }
    }

    fun isDown(sourceId: Long): Boolean { ensureLoaded(); return recs[sourceId]?.down ?: false }
    fun markDown(sourceId: Long) { val r = rec(sourceId); r.down = true; r.lastFailMs = System.currentTimeMillis(); touchSave() }
    fun markUp(sourceId: Long) { val r = rec(sourceId); r.down = false; r.lastOkMs = System.currentTimeMillis(); touchSave() }

    // Image-serving health, tracked separately from browse/API health: a source's API can be fine while
    // its image CDN 5xx's (the atsu outage). Fed by actual page fetches in the reader.
    fun areImagesDown(sourceId: Long): Boolean { ensureLoaded(); return recs[sourceId]?.imagesDown ?: false }
    fun markImagesDown(sourceId: Long) { val r = rec(sourceId); r.imagesDown = true; r.lastFailMs = System.currentTimeMillis(); touchSave() }
    fun markImagesUp(sourceId: Long) { val r = rec(sourceId); r.imagesDown = false; r.lastOkMs = System.currentTimeMillis(); touchSave() }

    /** Record a probe's round-trip time (from the health sweep / diagnostics). */
    fun setPing(sourceId: Long, ms: Long) { rec(sourceId).lastPingMs = ms; touchSave() }

    /** Read-only snapshot for the dashboard (null = never seen). */
    fun record(sourceId: Long): Rec? { ensureLoaded(); return recs[sourceId] }

    /** Force-flush pending changes to disk (e.g. at the end of a sweep). */
    fun flush() = touchSave(force = true)
}
