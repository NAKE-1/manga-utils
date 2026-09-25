package mangautils.server

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * HTTP client to the out-of-process browser sidecar (deploy/browser), used when
 * `SettingsStore.webviewEngine == "chrome"`. Mirrors the JcefRemoteView surface (open/frame/input/
 * scroll/close) but every call is a localhost HTTP hop to the sidecar's headed Chromium — so a browser
 * crash is isolated there. Configured by `MU_BROWSER_SIDECAR_URL`; when unset, [configured] is false and
 * callers fall back to JCEF.
 */
object ChromeEngine {
    private val log = LoggerFactory.getLogger(javaClass)

    val url: String? = System.getenv("MU_BROWSER_SIDECAR_URL")?.trim()?.trimEnd('/')?.ifBlank { null }
    val configured: Boolean get() = url != null

    // SHORT timeouts on purpose: /open returns "starting" or "ready" fast, and a frame is a quick JPEG. If
    // the sidecar ever hangs, we must fail in seconds — a long timeout blocks request threads, and with the
    // client retrying open every 2s that exhausts the pool → the health check stalls → autoheal restarts the
    // whole server (the RejectedExecutionException cascade). Fail fast, let the client retry.
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Separate client for autosolve only — a full YOLO solve loop (up to 6 tries, each with clicks + an 8s
    // wait) legitimately runs ~60s, so it needs a long read timeout the fast open/frame client must NOT have.
    private val httpLong = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .build()

    /** POST /webview/open — returns the sidecar's raw JSON body ({status, w, h, url}) or null if unreachable. */
    fun open(pageUrl: String): String? {
        val base = url ?: return null
        val body = """{"url":${jsonStr(pageUrl)}}""".toRequestBody(JSON)
        return runCatching {
            http.newCall(Request.Builder().url("$base/webview/open").post(body).build()).execute().use {
                it.body?.string() // 200 ready / 202 starting|failed both carry a JSON status
            }
        }.onFailure { log.info("chrome: open failed: {}", it.message) }.getOrNull()
    }

    /** GET /webview/frame — the latest JPEG, or null (204/not-open/unreachable). */
    fun frameJpeg(): ByteArray? {
        val base = url ?: return null
        return runCatching {
            http.newCall(Request.Builder().url("$base/webview/frame").build()).execute().use {
                if (it.code == 200) it.body?.bytes() else null
            }
        }.getOrNull()
    }

    fun input(x: Int, y: Int) = post("/webview/input?x=$x&y=$y")
    fun scroll(x: Int, y: Int, dx: Int, dy: Int) = post("/webview/scroll?x=$x&y=$y&dx=$dx&dy=$dy")
    fun touch(phase: String, x: Int, y: Int) = post("/webview/touch?phase=$phase&x=$x&y=$y")
    fun close() = post("/webview/close")

    /** POST /webview/autosolve — runs the YOLO solve loop in the sidecar. Returns the raw JSON body
     *  ({solved, detected, clicked, tries, message}) or null if unreachable. Uses a LONG timeout: a full
     *  6-try solve (clicks + 8s waits each) can take ~60s, far past the short client used for open/frame. */
    fun autosolve(): String? {
        val base = url ?: return null
        return runCatching {
            httpLong.newCall(Request.Builder().url("$base/webview/autosolve").post(EMPTY).build()).execute().use {
                it.body?.string()
            }
        }.onFailure { log.info("chrome: autosolve failed: {}", it.message) }.getOrNull()
    }

    private fun post(path: String) {
        val base = url ?: return
        runCatching {
            http.newCall(Request.Builder().url("$base$path").post(EMPTY).build()).execute().close()
        }.onFailure { log.debug("chrome: {} failed: {}", path, it.message) }
    }

    private fun jsonStr(s: String): String =
        buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> append(c)
            }
            append('"')
        }

    private val JSON = "application/json".toMediaType()
    private val EMPTY = ByteArray(0).toRequestBody(null)
}
