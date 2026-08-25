package xyz.nulldev.androidcompat.webkit

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLifeSpanHandlerAdapter

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
        // BOUNDED: if the app got created but never reaches INITIALIZED (e.g. a stale CEF profile lock
        // after a crash), waitForInit() would otherwise hang forever — and, held under JcefRemoteView's
        // monitor, wedge every WebView open and starve the coroutine pools. Time out and fail cleanly.
        val app = if (isInitialized) {
            cef
        } else {
            withTimeoutOrNull(30_000) { waitForInit().first() } ?: throw CefException(WEBVIEW_INITIALIZING)
        }
        val client = app.createClient()
        JsHandler(client) // This adds itself to a global map
        // Block ALL popups (window.open, target=_blank new-windows). Piracy sources spray ad popups that
        // otherwise spawn stray Chromium windows — including the "install this extension" prompt. Returning
        // true from onBeforePopup cancels the popup; the main frame (captcha, page load) is untouched.
        client.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onBeforePopup(browser: CefBrowser?, frame: CefFrame?, targetUrl: String?, targetFrameName: String?): Boolean {
                logger.debug { "JCEF: blocked popup → $targetUrl" }
                return true
            }
        })
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
