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
        // CEF is never bootstrapped in this build (cefApp stays success(null)), so waiting for init
        // would block until the network call times out. Fail fast with a clear reason instead — and
        // stay forward-compatible: once something sets cefApp to a real CefApp, this passes through.
        cefApp.value.let { if (it.isSuccess && it.getOrNull() == null) throw CefException(WEBVIEW_UNAVAILABLE) }
        val app = waitForInit().first()
        val client = app.createClient()
        JsHandler(client) // This adds itself to a global map
        return client
    }

    const val WEBVIEW_UNAVAILABLE =
        "This source needs an in-app WebView (Chromium), which isn't enabled in this build yet."

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
