/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.backup

import mangautils.core.config.AppConfig
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.name

/**
 * Rotating local `.mudata` snapshots in `<dataDir>/backups`. A daily one keeps the library/history/
 * settings recoverable from a corruption or mistake without reaching for an off-box copy.
 *
 * Two kinds, by filename prefix: `auto-*` are pruned to the newest N; `manual-*` (from "Back up now")
 * are pinned and only removed when the user deletes them.
 */
object LocalBackups {
    private val log = LoggerFactory.getLogger(javaClass)
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")
    private val NAME = Regex("""^(auto|manual)-[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{4}\.mudata\.zip$""")

    private val dir: Path get() = AppConfig.dataDir.resolve("backups")

    data class Entry(val name: String, val savedAt: Long, val size: Long, val kind: String)

    /** Everything a snapshot should carry: library (+read/bookmarks/history/positions/saved), settings, repos, extensions. */
    private val fullSections = BackupImport.Sections(library = true, settings = true, repos = true, extensions = true)

    /** Write a new snapshot. [kind] is "auto" or "manual"; auto-backups are pruned to [keepAuto] newest. */
    @Synchronized
    fun create(kind: String, keepAuto: Int): Entry {
        require(kind == "auto" || kind == "manual") { "kind must be auto|manual" }
        val bytes = BackupImport.export(fullSections)
        Files.createDirectories(dir)
        val name = "$kind-${LocalDateTime.now().format(stamp)}.mudata.zip"
        val target = dir.resolve(name)
        // Atomic write so a crash mid-write never leaves a truncated backup that looks valid.
        val tmp = dir.resolve("$name.tmp")
        Files.write(tmp, bytes)
        runCatching { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            .onFailure { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING) }
        log.info("wrote {} backup: {} ({} KB)", kind, name, bytes.size / 1024)
        if (kind == "auto") prune(keepAuto)
        return entryOf(target)
    }

    /** Newest first. */
    @Synchronized
    fun list(): List<Entry> {
        if (!dir.exists()) return emptyList()
        return Files.list(dir).use { s ->
            s.filter { NAME.matches(it.name) }.map { entryOf(it) }.toList()
        }.sortedByDescending { it.savedAt }
    }

    @Synchronized
    fun read(name: String): ByteArray {
        val f = safe(name)
        require(f.exists()) { "no such backup: $name" }
        return Files.readAllBytes(f)
    }

    @Synchronized
    fun delete(name: String): Boolean = Files.deleteIfExists(safe(name))

    private fun prune(keepAuto: Int) {
        val autos = list().filter { it.kind == "auto" }
        autos.drop(keepAuto.coerceAtLeast(1)).forEach {
            runCatching { delete(it.name) }.onSuccess { _ -> log.debug("pruned old backup {}", it.name) }
        }
    }

    private fun entryOf(f: Path) = Entry(
        name = f.name,
        savedAt = runCatching { f.getLastModifiedTime().toMillis() }.getOrDefault(0L),
        size = runCatching { f.fileSize() }.getOrDefault(0L),
        kind = f.name.substringBefore('-'),
    )

    /** Resolve [name] inside the backups dir, rejecting any path-traversal or unexpected name. */
    private fun safe(name: String): Path {
        require(NAME.matches(name)) { "invalid backup name" }
        return dir.resolve(name)
    }
}
