/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.runtime

import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.createAppModule
import mangautils.core.config.AppConfig
import org.slf4j.LoggerFactory
import xyz.nulldev.androidcompat.AndroidCompat
import xyz.nulldev.androidcompat.AndroidCompatInitializer
import xyz.nulldev.androidcompat.androidCompatModule

/**
 * Brings up the headless Android/Injekt runtime that loaded Tachiyomi extensions expect:
 * a Koin graph providing the `Application`, `NetworkHelper`, `JavaScriptEngine`, etc., plus
 * the AndroidCompat context. Idempotent and thread-safe; call before loading any extension.
 *
 * Mirrors the relevant part of Suwayomi-Server's `ServerSetup.applicationSetup()`.
 */
object ExtensionRuntime {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var started = false

    @Synchronized
    fun ensureStarted() {
        if (started) return

        // Point the AndroidCompat config (filesDir, cacheDir, server.conf) at our data dir
        // before anything reads ApplicationRootDir.
        AppConfig.ensureLayout()
        System.setProperty(
            "suwayomi.tachidesk.config.server.rootDir",
            AppConfig.dataDir.toString(),
        )

        log.debug("Starting extension runtime (data dir: {})", AppConfig.dataDir)

        startMainLooper()

        val app = App()

        // injekt-koin bridges Injekt.get<T>() to this Koin container.
        org.koin.core.context.startKoin {
            modules(
                createAppModule(app),
                androidCompatModule(),
                xyz.nulldev.ts.config.configManagerModule(),
            )
        }

        AndroidCompatInitializer().init()
        AndroidCompat().startApp(app)

        started = true
        log.debug("Extension runtime started")
    }

    /**
     * Prepare an Android "main" looper on a daemon thread. Some extensions build a
     * `Handler(Looper.getMainLooper())` (e.g. rate-limit / WebView-based Cloudflare interceptors);
     * without a prepared main looper `getMainLooper()` is null and the Handler constructor NPEs
     * ("Cannot read field mQueue because looper is null"). The queue is backed by our JVM
     * MessageQueue impl, so posted work actually runs.
     */
    private fun startMainLooper() {
        if (android.os.Looper.getMainLooper() != null) return
        val ready = java.util.concurrent.CountDownLatch(1)
        Thread {
            try {
                android.os.Looper.prepareMainLooper()
                ready.countDown()
                android.os.Looper.loop()
            } catch (e: Throwable) {
                ready.countDown()
                log.warn("android main looper stopped: {}", e.message)
            }
        }.apply { isDaemon = true; name = "android-main-looper" }.start()
        ready.await()
        // Diagnostic: confirm the main looper actually DISPATCHES posted work (not merely exists).
        // WebView-based extensions post a runnable here to create/load the browser; if this never
        // logs, that's why they fail with "Failed to start WebView".
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            log.info("main-looper self-test: posted runnable executed OK")
        }
    }
}
