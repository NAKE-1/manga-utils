/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.worker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mangautils.core.config.AppConfig
import mangautils.core.util.SafeFile

/** A registered worker node: a headless manga-utils image (WORKER=1) reachable on the tailnet. */
@Serializable
data class WorkerNode(
    val name: String,
    val url: String,
    val token: String = "",
    val enabled: Boolean = true,
)

/**
 * The set of worker nodes the main server dispatches image fetches to. Small flat JSON file (atomic
 * write, like SettingsStore), keyed by [WorkerNode.name]. Holds config only — live health is polled
 * on demand, never persisted.
 */
object WorkerStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file get() = AppConfig.dataDir.resolve("workers.json")

    fun list(): List<WorkerNode> =
        SafeFile.read(file) { runCatching { json.decodeFromString<List<WorkerNode>>(it) }.getOrNull() } ?: emptyList()

    fun find(name: String): WorkerNode? = list().firstOrNull { it.name == name }

    /** Add (or replace by name). URL is normalized without a trailing slash. Returns the saved node. */
    fun add(node: WorkerNode): WorkerNode {
        val clean = node.copy(name = node.name.trim(), url = node.url.trim().trimEnd('/'))
        save(list().filter { it.name != clean.name } + clean)
        return clean
    }

    fun remove(name: String): Boolean {
        val cur = list()
        val next = cur.filter { it.name != name }
        if (next.size == cur.size) return false
        save(next)
        return true
    }

    fun setEnabled(name: String, enabled: Boolean): Boolean {
        val cur = list()
        if (cur.none { it.name == name }) return false
        save(cur.map { if (it.name == name) it.copy(enabled = enabled) else it })
        return true
    }

    private fun save(nodes: List<WorkerNode>) = SafeFile.writeAtomic(file, json.encodeToString(nodes))
}
