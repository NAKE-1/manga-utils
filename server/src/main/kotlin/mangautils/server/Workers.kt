package mangautils.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mangautils.core.worker.WorkerNode
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Main-server view of the worker pool: poll each registered node's /worker/health for a live status
 * card (online, egress IP, metrics, in-flight jobs). Config lives in WorkerStore; this only reads.
 * The dispatch client (routing downloadPage's image GET through a node) is the next step on top of this.
 */
object Workers {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    @Serializable
    data class WorkerStatus(
        val name: String,
        val url: String,
        val enabled: Boolean,
        val online: Boolean,
        val egressIp: String? = null,
        val inflight: Int? = null,
        val metrics: WorkerMode.Metrics? = null,
        val version: String? = null,
        val uptimeS: Long? = null,
        val error: String? = null,
    )

    @Serializable data class AddReq(val name: String, val url: String, val token: String = "")
    @Serializable data class TestReq(val url: String, val token: String = "")

    /** GET {url}/worker/health with the node's bearer token. Null url/token errors become an offline card. */
    private fun poll(node: WorkerNode): WorkerStatus {
        val base = node.url.trimEnd('/')
        return try {
            val rb = Request.Builder().url("$base/worker/health")
            if (node.token.isNotBlank()) rb.header("Authorization", "Bearer ${node.token}")
            client.newCall(rb.build()).execute().use { resp ->
                val bodyText = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return WorkerStatus(node.name, base, node.enabled, false,
                        error = if (resp.code == 401) "unauthorized (check token)" else "HTTP ${resp.code}")
                }
                val h = json.decodeFromString<WorkerMode.Health>(bodyText)
                WorkerStatus(node.name, base, node.enabled, true, h.egressIp, h.inflight, h.metrics, h.version, h.uptimeS)
            }
        } catch (e: Exception) {
            WorkerStatus(node.name, base, node.enabled, false, error = e.message ?: "unreachable")
        }
    }

    /** Poll every registered node in parallel for the list screen. */
    suspend fun statuses(nodes: List<WorkerNode>): List<WorkerStatus> = withContext(Dispatchers.IO) {
        coroutineScope { nodes.map { async { poll(it) } }.awaitAll() }
    }

    /** Probe an unsaved node (the "Test" button when adding one). */
    suspend fun test(url: String, token: String): WorkerStatus = withContext(Dispatchers.IO) {
        poll(WorkerNode(name = "(test)", url = url, token = token))
    }
}
