package xyz.nulldev.androidcompat.webkit

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import org.cef.CefApp
import org.cef.CefClient

private val logger = KotlinLogging.logger {}

object CefHelper {
    val cefApp = MutableStateFlow<Result<CefApp?>>(Result.success(null))

    suspend fun createClient(): CefClient {
        // Kick off the (lazy, one-time) native CEF download/init. Until it's ready, fail fast with a
        // clear reason instead of blocking until the network call times out.
        CefBootstrap.ensureStarted()
        val current = cefApp.value
        if (current.isFailure) throw CefException("$WEBVIEW_UNAVAILABLE (${current.exceptionOrNull()?.message})")
        if (current.getOrNull() == null) throw CefException(WEBVIEW_INITIALIZING)
        val app = waitForInit().first()
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
