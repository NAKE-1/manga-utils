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
}
