/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.source

import kotlinx.serialization.json.Json
import mangautils.core.config.AppConfig
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Remembers which sources have been seen hitting Cloudflare protection (there's no static flag —
 * the engine only finds out at request time). Persisted to `data/cloudflare.json` so the UI can mark
 * a source: green (no Cloudflare seen), else red/orange depending on whether a bypass is installed.
 */
object CloudflareState {
    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = AppConfig.dataDir.resolve("cloudflare.json")
    private val blocked = ConcurrentHashMap.newKeySet<Long>()

    @Volatile
    private var loaded = false

    @Synchronized
    private fun ensure() {
        if (loaded) return
        runCatching { if (file.exists()) blocked.addAll(json.decodeFromString<Set<Long>>(file.readText())) }
        loaded = true
    }

    fun isBlocked(sourceId: Long): Boolean { ensure(); return blocked.contains(sourceId) }

    /** Record that [sourceId] is behind Cloudflare (idempotent; persists on first sight). */
    fun mark(sourceId: Long) {
        ensure()
        if (blocked.add(sourceId)) runCatching { file.createParentDirectories(); file.writeText(json.encodeToString(blocked.toList())) }
    }
}
