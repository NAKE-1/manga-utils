package mangautils.server

import kotlinx.serialization.json.Json
import mangautils.core.config.AppConfig
import mangautils.core.convert.ImageFormat
import mangautils.core.download.ChapterIdentity
import mangautils.core.download.DownloadManager
import mangautils.core.util.Hashing
import mangautils.core.worker.WorkerNode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * Main-server side of worker delegation: hand a download task's chapters to a worker node, then harvest
 * the finished chapters back into main's downloads. The worker downloads with its OWN cf_clearance; main
 * pulls each completed chapter and commits it atomically.
 *
 * Robustness (per chapter): declare (manifest) -> stage (pull into a temp folder) -> verify (file count +
 * SHA-256 + every page is a real image) -> commit (atomic rename into place) -> ack (worker frees it).
 * A crash or dropped transfer on either side leaves at most an incomplete staging folder, which is
 * discarded; nothing partial ever enters the library, and the affected chapters just retry. The task's
 * (assignedWorker, remoteJobId) are persisted by the caller so a main restart can re-adopt or reclaim.
 */
object WorkerDelegate {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val JSON = "application/json".toMediaType()

    private const val POLL_MS = 1500L
    private const val STALL_MS = 180_000L // give up on a worker that makes no progress for 3 min
    private const val FILE_RETRIES = 3

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS) // a chapter's files can be a few MB each
        .build()

    /** Outcome: the chapter URLs successfully committed to main's disk. The caller treats the rest as failed. */
    data class Result(val harvested: Set<String>, val workerGone: Boolean)

    private fun req(node: WorkerNode, b: Request.Builder) =
        b.apply { if (node.token.isNotBlank()) header("Authorization", "Bearer ${node.token}") }.build()

    private fun base(node: WorkerNode) = node.url.trimEnd('/')

    /**
     * Delegate [task]'s chapters to [node] and harvest them. Updates task progress as chapters land.
     * [persistJob] is called once the worker accepts the job (so a main restart can find it), [isStopped]
     * lets the Stop button abort, [onChange] asks the caller to persist/refresh after each committed chapter.
     */
    fun run(task: DownloadQueue.Task, node: WorkerNode, persistJob: (String) -> Unit, isStopped: () -> Boolean, onChange: () -> Unit): Result {
        val title = task.mangaTitle
        // Re-adopt an existing job after a main restart; otherwise start a fresh one.
        var jobId = task.remoteJobId?.takeIf { task.assignedWorker == node.name && jobAlive(node, it) }
        if (jobId == null) {
            jobId = startJob(node, task) ?: return Result(emptySet(), workerGone = true)
            persistJob(jobId)
        }

        val harvested = HashSet<String>()
        var lastProgress = System.currentTimeMillis()
        try {
        while (true) {
            if (isStopped()) break
            val st = pollJob(node, jobId) ?: run {
                // Worker unreachable: one transient miss is fine, a sustained one trips the stall timeout.
                if (System.currentTimeMillis() - lastProgress > STALL_MS) return Result(harvested, workerGone = true)
                Thread.sleep(POLL_MS); null
            } ?: continue

            // Live progress: surface the worker's active chapter + pages + speed onto the task, the same
            // shape a local download shows. These come from the job itself (not a per-chapter scan), so they
            // stay correct even when the worker finishes chapters faster than we poll - the old per-chapter
            // pick showed a stale/wrong chapter and never carried a download speed at all.
            if (st.curChapter.isNotBlank() || st.curPagesTotal > 0) {
                task.currentChapter = st.curChapter
                task.currentChapterUrl = st.curChapterUrl
                task.pagesDone = st.curPagesDone
                task.pagesTotal = st.curPagesTotal
                task.bytesPerSec = st.bytesPerSec
            }
            for (ch in st.chapters) {
                if (isStopped()) break
                if (ch.state != "done" || ch.folder == null) continue
                if (ch.url in harvested) continue
                // Main may already hold this chapter (idempotent resume) — ack and skip.
                if (runCatching { ChapterIdentity.hasVersion(title, ch.url) }.getOrDefault(false)) {
                    harvested.add(ch.url); ackChapter(node, jobId, ch.folder); continue
                }
                task.currentChapter = ch.name
                if (harvestChapter(node, jobId, title, ch.folder)) {
                    harvested.add(ch.url)
                    task.finishedUrls.add(ch.url)
                    task.doneCount = task.finishedUrls.size
                    ackChapter(node, jobId, ch.folder)
                    lastProgress = System.currentTimeMillis()
                    onChange()
                } else {
                    log.warn("worker {} chapter '{}' failed to harvest; will retry locally", node.name, ch.name)
                }
            }

            val allDone = st.chapters.all { it.state != "pending" }
            val nothingLeft = st.chapters.none { it.state == "done" && it.url !in harvested }
            if (st.state != "running" && allDone && nothingLeft) break
            if (System.currentTimeMillis() - lastProgress > STALL_MS) { log.warn("worker {} stalled on job {}", node.name, jobId); return Result(harvested, workerGone = true) }
            Thread.sleep(POLL_MS)
        }
        return Result(harvested, workerGone = false)
        } finally {
            // Always tell the worker to drop AND stop this job - even on a stall/abandon, not just a clean
            // finish - so it never orphan-runs the whole series after main has moved on (double work, and
            // it'd keep shipping chapters main no longer tracks).
            runCatching { client.newCall(req(node, Request.Builder().url("${base(node)}/worker/download/$jobId/done").post(ByteArray(0).toRequestBody()))).execute().close() }
        }
    }

    private fun startJob(node: WorkerNode, task: DownloadQueue.Task): String? {
        val body = WorkerMode.DownloadReqDto(
            source = task.sourceId.toString(), manga = task.mangaUrl, title = task.mangaTitle,
            chapters = task.chapters.map { WorkerMode.ChReq(it.url, it.name) }, clientTaskId = task.id,
        )
        return runCatching {
            client.newCall(req(node, Request.Builder().url("${base(node)}/worker/download")
                .post(json.encodeToString(body).toRequestBody(JSON)))).execute().use { r ->
                if (!r.isSuccessful) return@use null
                json.decodeFromString<WorkerMode.Created>(r.body?.string().orEmpty()).id.ifBlank { null }
            }
        }.getOrNull()
    }

    private fun jobAlive(node: WorkerNode, jobId: String): Boolean = pollJob(node, jobId) != null

    private fun pollJob(node: WorkerNode, jobId: String): WorkerMode.JobStatusDto? = runCatching {
        client.newCall(req(node, Request.Builder().url("${base(node)}/worker/download/$jobId"))).execute().use { r ->
            if (!r.isSuccessful) return@use null
            json.decodeFromString<WorkerMode.JobStatusDto>(r.body?.string().orEmpty())
        }
    }.getOrNull()

    private fun ackChapter(node: WorkerNode, jobId: String, folder: String) {
        val url = "${base(node)}/worker/download/$jobId/ack?chapter=${enc(folder)}"
        runCatching { client.newCall(req(node, Request.Builder().url(url).post(ByteArray(0).toRequestBody()))).execute().close() }
    }

    /** Pull one chapter into a staging folder, verify it fully, then atomically commit it. Returns success. */
    private fun harvestChapter(node: WorkerNode, jobId: String, title: String, folder: String): Boolean {
        val manifest = runCatching {
            client.newCall(req(node, Request.Builder().url("${base(node)}/worker/download/$jobId/manifest?chapter=${enc(folder)}"))).execute().use { r ->
                if (!r.isSuccessful) return@use null
                json.decodeFromString<WorkerMode.ManifestDto>(r.body?.string().orEmpty())
            }
        }.getOrNull() ?: return false
        if (manifest.files.isEmpty()) return false

        val seriesDir = AppConfig.downloadsDir.resolve(DownloadManager.sanitize(title))
        val staging = seriesDir.resolve(".staging-$jobId-$folder")
        try {
            if (Files.exists(staging)) deleteRecursively(staging)
            Files.createDirectories(staging)
            for (fe in manifest.files) {
                val bytes = pullFileVerified(node, jobId, folder, fe) ?: return false
                // Content gate: a page must be a real image (not 0 bytes / an error page). ComicInfo.xml is XML, skip it.
                if (!fe.path.equals("ComicInfo.xml", ignoreCase = true)) {
                    if (bytes.isEmpty() || !ImageFormat.looksLikeImage(bytes)) {
                        log.warn("worker harvest: '{}/{}' is not a valid image ({} bytes) - rejecting chapter", folder, fe.path, bytes.size)
                        return false
                    }
                }
                Files.write(staging.resolve(fe.path), bytes)
            }
            // All files present and valid? (count gate)
            val got = Files.list(staging).use { it.filter { p -> Files.isRegularFile(p) }.count() }
            if (got.toInt() != manifest.files.size) return false

            // Commit atomically: rename staging into the real chapter folder.
            val finalDir = seriesDir.resolve(folder)
            if (Files.exists(finalDir)) deleteRecursively(finalDir) // replace a prior partial
            Files.createDirectories(seriesDir)
            runCatching { Files.move(staging, finalDir, StandardCopyOption.ATOMIC_MOVE) }
                .recoverCatching { Files.move(staging, finalDir) } // same-store fallback if ATOMIC unsupported
                .getOrThrow()
            return true
        } catch (e: Exception) {
            log.warn("worker harvest of '{}' failed: {}", folder, e.message)
            return false
        } finally {
            runCatching { if (Files.exists(staging)) deleteRecursively(staging) }
        }
    }

    private fun pullFileVerified(node: WorkerNode, jobId: String, folder: String, fe: WorkerMode.FileEntry): ByteArray? {
        val url = "${base(node)}/worker/download/$jobId/file?chapter=${enc(folder)}&path=${enc(fe.path)}"
        repeat(FILE_RETRIES) {
            val bytes = runCatching {
                client.newCall(req(node, Request.Builder().url(url))).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
            }.getOrNull()
            if (bytes != null && Hashing.hash(bytes) == fe.sha) return bytes // transfer-integrity gate
            if (bytes != null) log.warn("worker harvest: SHA mismatch on '{}/{}', retrying", folder, fe.path)
        }
        return null
    }

    private fun deleteRecursively(dir: Path) {
        Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach { p -> runCatching { Files.deleteIfExists(p) } } }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8)

    /** Clean up any leftover staging folders across the whole downloads dir (abandoned transfers). Call at boot. */
    fun cleanStaging() {
        val root = AppConfig.downloadsDir
        if (!Files.isDirectory(root)) return
        runCatching {
            Files.list(root).use { series ->
                series.filter { Files.isDirectory(it) }.forEach { s ->
                    Files.list(s).use { st -> st.filter { Files.isDirectory(it) && it.fileName.toString().startsWith(".staging-") }.forEach { deleteRecursively(it) } }
                }
            }
        }
    }
}
