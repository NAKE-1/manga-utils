package mangautils.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mangautils.core.worker.WorkerNode
import mangautils.core.worker.WorkerStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    /** Quick blocking liveness check for the dispatcher (reserveWorker). */
    fun onlineNow(node: WorkerNode): Boolean = poll(node).online

    // Exit-node control is proxied straight through: main forwards the worker's own response body to the UI.
    private val exitClient = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()

    fun exitNodes(node: WorkerNode): String? = runCatching {
        val rb = Request.Builder().url("${node.url.trimEnd('/')}/worker/exit-nodes")
        if (node.token.isNotBlank()) rb.header("Authorization", "Bearer ${node.token}")
        exitClient.newCall(rb.build()).execute().use { it.body?.string() }
    }.getOrNull()

    fun setExit(node: WorkerNode, exitNode: String): String? = runCatching {
        val body = Json.encodeToString(WorkerMode.SetExitReq(exitNode)).toRequestBody("application/json".toMediaType())
        val rb = Request.Builder().url("${node.url.trimEnd('/')}/worker/exit-node").post(body)
        if (node.token.isNotBlank()) rb.header("Authorization", "Bearer ${node.token}")
        exitClient.newCall(rb.build()).execute().use { it.body?.string() }
    }.getOrNull()

    @Serializable data class SyncResult(val synced: Int, val failed: Int, val total: Int, val errors: List<String> = emptyList())
    private val jarMedia = "application/java-archive".toMediaType()
    private val syncClient = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()

    /** Push main's EXACT set of installed extension jars to [node] so it can download the same sources,
     *  including sideloaded beta/custom jars that can't be re-fetched from a repo. */
    fun syncExtensions(node: WorkerNode): SyncResult {
        val dir = mangautils.core.config.AppConfig.extensionsDir
        val jars = runCatching {
            java.nio.file.Files.list(dir).use { s -> s.filter { it.toString().endsWith(".jar") }.toList() }
        }.getOrDefault(emptyList())
        // Metadata main already enumerated for each jar, keyed by jar filename, so the worker can install
        // even a jar with no bundled manifest (APK-translated sources: mangahere, mangakatana, natsu, ...).
        val byJar = runCatching { mangautils.core.extension.InstalledStore.list() }.getOrDefault(emptyList())
            .associateBy { java.nio.file.Path.of(it.jarPath).fileName.toString() }
        var ok = 0; var bad = 0; val errs = ArrayList<String>()
        for (jar in jars) {
            // null = installed OK; else the worker's error message (or a transport failure) so the caller
            // sees WHY a jar was rejected, not just its name.
            val err = runCatching {
                val body = java.nio.file.Files.readAllBytes(jar).toRequestBody(jarMedia)
                val rb = Request.Builder().url("${node.url.trimEnd('/')}/worker/extensions").post(body)
                if (node.token.isNotBlank()) rb.header("Authorization", "Bearer ${node.token}")
                byJar[jar.fileName.toString()]?.let { m ->
                    // Header values are ASCII (package/class/version); strip just in case so one odd char
                    // can't throw and fail a jar that would otherwise install fine.
                    fun hdr(k: String, v: String) { rb.header(k, v.filter { it.code in 0x20..0x7e }) }
                    hdr("X-Ext-Pkg", m.pkg)
                    hdr("X-Ext-Nsfw", if (m.nsfw) "1" else "0")
                    hdr("X-Ext-Version", m.versionName)
                    hdr("X-Ext-Code", m.versionCode.toString())
                    hdr("X-Ext-Classes", m.classNames.joinToString(";"))
                }
                syncClient.newCall(rb.build()).execute().use { r ->
                    if (r.isSuccessful) null else (r.body?.string()?.take(200)?.ifBlank { null } ?: "HTTP ${r.code}")
                }
            }.getOrElse { it.message ?: "push failed" }
            if (err == null) ok++ else { bad++; errs.add("${jar.fileName}: $err") }
        }
        return SyncResult(ok, bad, jars.size, errs.take(10))
    }

    /** The extensions installed on a worker (proxied through from its /worker/extensions). */
    fun extensions(node: WorkerNode): String? = runCatching {
        val rb = Request.Builder().url("${node.url.trimEnd('/')}/worker/extensions")
        if (node.token.isNotBlank()) rb.header("Authorization", "Bearer ${node.token}")
        exitClient.newCall(rb.build()).execute().use { it.body?.string() }
    }.getOrNull()

    /** After main installs/updates one extension, push that jar to every enabled worker (background,
     *  best-effort) so the whole pool stays in lockstep with main. */
    fun propagateToWorkers(jarPath: String) {
        val file = java.nio.file.Path.of(jarPath)
        if (!java.nio.file.Files.exists(file)) return
        val workers = runCatching { WorkerStore.list().filter { it.enabled } }.getOrDefault(emptyList())
        if (workers.isEmpty()) return
        Thread {
            val bytes = runCatching { java.nio.file.Files.readAllBytes(file) }.getOrNull() ?: return@Thread
            for (node in workers) runCatching {
                if (!onlineNow(node)) return@runCatching
                val rb = Request.Builder().url("${node.url.trimEnd('/')}/worker/extensions").post(bytes.toRequestBody(jarMedia))
                if (node.token.isNotBlank()) rb.header("Authorization", "Bearer ${node.token}")
                syncClient.newCall(rb.build()).execute().use { it.isSuccessful }
            }
        }.apply { isDaemon = true; name = "ext-propagate" }.start()
    }
}
