package xyz.nulldev.androidcompat.webkit

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.cef.CefApp
import org.cef.CefClient

private val logger = KotlinLogging.logger {}

object CefHelper {
    val cefApp = MutableStateFlow<Result<CefApp?>>(Result.success(null))

    // Set true once CEF reaches INITIALIZED (by CefBootstrap). onInitialization() only fires on state
    // *changes*, so once initialized we must NOT call waitForInit() (it would block forever).
    @Volatile
    var isInitialized = false

    suspend fun createClient(): CefClient {
        // Kick off the (lazy, one-time) native CEF download/init, then WAIT (bounded) for it to be
        // ready instead of failing the first hit — so the first WebView request after startup just
        // works rather than erroring until a manual reload. Cached restarts finish in ~1-2s; a
        // first-ever download can take longer, so the ceiling is generous but still fails cleanly.
        CefManager.ensureStarted()
        val current =
            withTimeoutOrNull(30_000) { cefApp.first { it.isFailure || it.getOrNull() != null } }
                ?: throw CefException(WEBVIEW_INITIALIZING)
        if (current.isFailure) throw CefException("$WEBVIEW_UNAVAILABLE (${current.exceptionOrNull()?.message})")
        val cef = current.getOrNull() ?: throw CefException(WEBVIEW_INITIALIZING)
        // If CEF is already initialized (the common case — bootstrap set cefApp on INITIALIZED), use it
        // directly. onInitialization() only fires on state *changes*, so waitForInit() would block
        // forever on an already-initialized app — deadlocking the main looper the WebView runs on.
        val app = if (isInitialized) cef else waitForInit().first()
        val client = app.createClient()
        JsHandler(client) // This adds itself to a global map
        return client
    }

    const val WEBVIEW_UNAVAILABLE =
        "This source needs an in-app WebView (Chromium), which couldn't be started."
    const val WEBVIEW_INITIALIZING =
        "The in-app WebView is downloading/starting Chromium (first use) — try again in a minute."

    fun waitForInit() =
        callbackFlow {
            val app = cefApp.first { it.isFailure || it.getOrThrow() != null }.getOrThrow()!!
            app.onInitialization {
                logger.debug { "CEF: Initialization state $it" }
                when (it) {
                    CefApp.CefAppState.INITIALIZED -> {
                        trySend(app)
                        close()
                    }

                    CefApp.CefAppState.SHUTTING_DOWN, CefApp.CefAppState.TERMINATED -> {
                        close(CefException("Shutting down"))
                    }

                    else -> {}
                }
            }
            awaitClose {}
        }

    class CefException(
        msg: String,
    ) : Exception(msg)
}
