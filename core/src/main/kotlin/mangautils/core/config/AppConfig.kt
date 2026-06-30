/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.config

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * Central location for all on-disk state. Everything lives under one **fixed, absolute** data dir
 * so every front-end (CLI, Swing, Compose, packaged) always agrees regardless of the launch
 * working directory. Override with the `MU_DATA_DIR` system property / env var.
 */
object AppConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    val dataDir: Path by lazy {
        val configured = System.getProperty("MU_DATA_DIR") ?: System.getenv("MU_DATA_DIR")
        val resolved = (if (configured.isNullOrBlank()) defaultDataDir() else Path.of(configured)).absolute().normalize()
        runCatching {
            if (!resolved.exists()) resolved.createDirectories()
            migrateLegacyInto(resolved)
        }.onFailure { log.warn("data dir init: {}", it.message) }
        resolved
    }

    val extensionsDir: Path get() = sub("extensions")

    /** Optional runtime override for the downloads location (set from Settings). */
    @Volatile
    var downloadDirOverride: Path? = null
    val downloadsDir: Path get() = downloadDirOverride ?: sub("downloads")
    val logsDir: Path get() = sub("logs")
    val databaseFile: Path get() = dataDir.resolve("library.db")
    val reposFile: Path get() = dataDir.resolve("repos.json")

    private fun sub(name: String): Path = dataDir.resolve(name)

    fun ensureLayout() {
        listOf(dataDir, extensionsDir, downloadsDir, logsDir).forEach {
            if (!Files.exists(it)) it.createDirectories()
        }
    }

    /** Platform-appropriate fixed location, e.g. %LOCALAPPDATA%\manga-utils on Windows. */
    private fun defaultDataDir(): Path {
        val os = System.getProperty("os.name").lowercase()
        val home = System.getProperty("user.home")
        return when {
            os.contains("win") -> Path.of(System.getenv("LOCALAPPDATA") ?: home, "manga-utils")
            os.contains("mac") -> Path.of(home, "Library", "Application Support", "manga-utils")
            else -> Path.of(home, ".local", "share", "manga-utils")
        }
    }

    /**
     * One-time migration: if [target] has no installed-extensions marker, copy a legacy cwd-relative
     * `data` dir (from before the fixed-path move) into it so users keep their extensions/library.
     */
    private fun migrateLegacyInto(target: Path) {
        if (Files.exists(target.resolve("extensions").resolve("installed.json"))) return
        val candidates =
            listOfNotNull(
                System.getProperty("MU_LEGACY_DATA_DIR")?.let { Path.of(it) },
                Path.of("data"),
                Path.of("..", "data"),
            ).map { it.absolute().normalize() }.distinct()
        val legacy =
            candidates.firstOrNull { it != target && Files.exists(it.resolve("extensions").resolve("installed.json")) }
                ?: return
        log.info("Migrating legacy data {} -> {}", legacy, target)
        Files.walk(legacy).use { stream ->
            stream.forEach { src ->
                val rel = legacy.relativize(src)
                val dst = target.resolve(rel.toString())
                runCatching {
                    if (Files.isDirectory(src)) {
                        if (!Files.exists(dst)) Files.createDirectories(dst)
                    } else if (!Files.exists(dst)) {
                        dst.parent?.let { if (!Files.exists(it)) Files.createDirectories(it) }
                        Files.copy(src, dst)
                    }
                }
            }
        }
    }
}
