/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.worker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mangautils.core.convert.ImageFormat
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Routes a single page's image GET through a worker node (its IP) instead of fetching locally, when a
 * healthy worker is available. Strictly best-effort and side-effect free on failure: it returns null
 * for ANY reason (no workers, all offline, at cap, job failed, SHA mismatch) and the caller fetches
 * locally exactly as before. So the whole feature is inert until a worker is registered and online, and
 * disabling/removing a worker in the UI is the kill switch.
 *
 * Integrity: the worker validates the bytes are an image and returns a SHA-256; we re-hash here and
 * reject a mismatch (fall back to local). Protocol per page: POST /worker/jobs -> poll -> pull artifact
 * -> ack.
 */
object WorkerDispatch {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val JSON = "application/json".toMediaType()

    // Max concurrent dispatches from ONE worker for ONE source (the per-(source,IP) cap): keeps us from
    // throwing a source's whole rate-limit window at a single IP. ponytail: fixed; env-knob only if needed.
    private const val CAP = 3
    private const val HEALTH_TTL_MS = 15_000L
    private const val JOB_BUDGET_MS = 25_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    // name -> epoch-ms until which we treat the node as online (health cached so we don't poll per page).
    private val onlineUntil = ConcurrentHashMap<String, Long>()
    // "sourceId|name" -> in-flight dispatches, for the cap.
    private val inflight = ConcurrentHashMap<String, AtomicInteger>()

    @Serializable private data class PageReqDto(val index: Int, val url: String, val headers: Map<String, String>)
    @Serializable private data class JobReqDto(val pages: List<PageReqDto>)
    @Serializable private data class JobCreatedDto(val id: String = "")
    @Serializable private data class PageStatusDto(val index: Int = 0, val state: String = "", val sha: String? = null)
    @Serializable private data class JobStatusDto(val id: String = "", val state: String = "", val pages: List<PageStatusDto> = emptyList())
    @Serializable private data class HealthDto(val egressIp: String = "")

    /**
     * Fetch [imageUrl] through a healthy worker, or return null to signal "fetch locally". [headers] are
     * the source's request headers (sent so the worker's request looks the same, just from its IP).
     */
    fun fetch(sourceId: Long, imageUrl: String, headers: Headers): ByteArray? {
        val candidates = WorkerStore.list().filter { it.enabled }
        if (candidates.isEmpty()) return null // inert with no workers — the common case, costs nothing
        val hdrMap = headers.toMap()
        for (node in candidates.shuffled()) { // shuffle so load spreads across equally-eligible nodes
            if (!isOnline(node)) continue
            val key = "$sourceId|${node.name}"
            val counter = inflight.getOrPut(key) { AtomicInteger(0) }
            if (counter.get() >= CAP) continue // this IP already busy enough for this source
            counter.incrementAndGet()
            try {
                val bytes = runJob(node, imageUrl, hdrMap)
                if (bytes != null) return bytes
            } catch (e: Exception) {
                log.debug("worker {} dispatch error, falling back: {}", node.name, e.message)
                onlineUntil.remove(node.name) // force a re-check next time; this attempt falls back to local
            } finally {
                counter.decrementAndGet()
            }
        }
        return null
    }

    private fun isOnline(node: WorkerNode): Boolean {
        val now = System.currentTimeMillis()
        onlineUntil[node.name]?.let { if (it > now) return true }
        val ok = runCatching {
            val rb = Request.Builder().url("${node.url.trimEnd('/')}/worker/health")
            if (node.token.isNotBlank()) rb.header("Authorization", "Bearer ${node.token}")
            client.newCall(rb.build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        onlineUntil[node.name] = if (ok) now + HEALTH_TTL_MS else now + 30_000L // back off a dead node 30s
        return ok
    }

    private fun runJob(node: WorkerNode, imageUrl: String, headers: Map<String, String>): ByteArray? {
        val base = node.url.trimEnd('/')
        fun req(b: Request.Builder) = b.apply { if (node.token.isNotBlank()) header("Authorization", "Bearer ${node.token}") }.build()

        // 1. create
        val body = json.encodeToString(JobReqDto(listOf(PageReqDto(0, imageUrl, headers)))).toRequestBody(JSON)
        val id = client.newCall(req(Request.Builder().url("$base/worker/jobs").post(body))).execute().use { r ->
            if (!r.isSuccessful) return null
            json.decodeFromString<JobCreatedDto>(r.body?.string().orEmpty()).id
        }
        if (id.isBlank()) return null

        try {
            // 2. poll until the single page settles
            val deadline = System.currentTimeMillis() + JOB_BUDGET_MS
            var page: PageStatusDto? = null
            while (System.currentTimeMillis() < deadline) {
                val st = client.newCall(req(Request.Builder().url("$base/worker/jobs/$id"))).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    json.decodeFromString<JobStatusDto>(r.body?.string().orEmpty())
                } ?: return null
                if (st.state == "done") { page = st.pages.firstOrNull { it.index == 0 }; break }
                Thread.sleep(100)
            }
            if (page?.state != "ok" || page.sha.isNullOrBlank()) return null

            // 3. pull the bytes
            val bytes = client.newCall(req(Request.Builder().url("$base/worker/jobs/$id/artifact/0"))).execute().use { r ->
                if (!r.isSuccessful) return@use null
                r.body?.bytes()
            } ?: return null

            // 4. verify: it's an image AND the hash matches what the worker reported (end-to-end integrity)
            if (!ImageFormat.looksLikeImage(bytes)) return null
            if (sha256(bytes) != page.sha) { log.warn("worker {} SHA mismatch for page, falling back", node.name); return null }
            return bytes
        } finally {
            // 5. ack (best-effort) so the worker frees the held bytes
            runCatching { client.newCall(req(Request.Builder().url("$base/worker/jobs/$id/ack").post(ByteArray(0).toRequestBody()))).execute().close() }
        }
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
