/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories

/**
 * Central location for all on-disk state. Everything lives under one data dir so a
 * future web layer can open the exact same store (e.g. shared over Tailscale).
 *
 * The data dir is, in order of precedence:
 *   1. the `MU_DATA_DIR` system property (`-DMU_DATA_DIR=...`)
 *   2. the `MU_DATA_DIR` environment variable
 *   3. `./data` relative to the working directory
 */
object AppConfig {
    val dataDir: Path by lazy {
        val configured = System.getProperty("MU_DATA_DIR") ?: System.getenv("MU_DATA_DIR")
        val base = if (configured.isNullOrBlank()) Path.of("data") else Path.of(configured)
        base.absolute().normalize()
    }

    /** Cached extension repository indexes + downloaded/translated extension jars. */
    val extensionsDir: Path get() = sub("extensions")

    /** Per-chapter image downloads and exported CBZ/etc. files. */
    val downloadsDir: Path get() = sub("downloads")

    /** Rolling log files (see logback.xml). */
    val logsDir: Path get() = sub("logs")

    /** The SQLite database file (used from Phase 3 onward). */
    val databaseFile: Path get() = dataDir.resolve("library.db")

    /** repos.json — the list of extension repository index URLs the user has added. */
    val reposFile: Path get() = dataDir.resolve("repos.json")

    private fun sub(name: String): Path = dataDir.resolve(name)

    /** Ensure the standard directory layout exists; safe to call repeatedly. */
    fun ensureLayout() {
        listOf(dataDir, extensionsDir, downloadsDir, logsDir).forEach {
            if (!Files.exists(it)) it.createDirectories()
        }
    }
}
