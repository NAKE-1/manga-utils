package xyz.nulldev.androidcompat.webkit

import dev.datlag.kcef.KCEF
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.cef.CefApp
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lazily brings up the native CEF (Chromium) runtime the first time a source actually needs a
 * WebView. KCEF is the installer/launcher around JetBrains JCEF: it downloads the native bundle
 * (~150 MB) into the data dir on first run, then hands the initialized [CefApp] to [CefHelper].
 * Runs on a daemon thread so it never blocks a request — until it's ready, [CefHelper.createClient]
 * fails fast with a clear message.
 */
object CefBootstrap {
    private val logger = KotlinLogging.logger {}
    private val started = AtomicBoolean(false)

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        Thread {
            val root = System.getProperty("suwayomi.tachidesk.config.server.rootDir") ?: System.getProperty("user.home")
            val dir = File(root, "bin/kcef")
            logger.info { "Bootstrapping CEF via KCEF (install dir: $dir; first run downloads Chromium, this can take a while)…" }
            runBlocking {
                KCEF.init(
                builder = {
                    installDir(dir)
                    progress {
                        onDownloading { logger.info { "CEF download: ${it.toInt()}%" } }
                        onInitialized {
                            runCatching {
                                CefHelper.cefApp.value = Result.success(CefApp.getInstance())
                                CefHelper.isInitialized = true
                            }.onFailure { CefHelper.cefApp.value = Result.failure(it) }
                            logger.info { "CEF runtime ready — WebView-based sources are now usable" }
                        }
                    }
                    settings {
                        windowlessRenderingEnabled = true
                        // KCEF downloads the native bundle here but doesn't point CEF at it — without
                        // these, CEF looks for jcef_helper.exe + resources inside the JDK (which lacks
                        // them) and every helper subprocess fails to launch.
                        browserSubProcessPath = File(dir, "jcef_helper.exe").absolutePath
                        resourcesDirPath = dir.absolutePath
                        localesDirPath = File(dir, "locales").absolutePath
                    }
                    // Headless server: disable the GPU entirely, or CEF launches a GPU process,
                    // fails ("GPU process isn't usable"), and its FATAL handler kills the JVM.
                    addArgs(
                        "--disable-gpu",
                        "--disable-gpu-compositing",
                        "--disable-software-rasterizer",
                        "--disable-gpu-process-crash-limit",
                        "--no-sandbox",
                    )
                },
                onError = {
                    logger.error(it) { "CEF init error" }
                    CefHelper.cefApp.value = Result.failure(it ?: RuntimeException("CEF init failed"))
                },
                    onRestartRequired = {
                        logger.warn { "CEF requires an app restart to finish installing" }
                    },
                )
            }
        }.apply { isDaemon = true; name = "cef-bootstrap" }.start()
    }
}
