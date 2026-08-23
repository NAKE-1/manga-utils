/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.util

import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Crash-safe flat-file persistence for our small JSON stores.
 *
 * [writeAtomic] never truncates the live file: it writes to `<name>.tmp`, fsyncs it, keeps the previous
 * good copy at `<name>.old`, then atomically renames the temp over the target. A kill at any point leaves
 * either the old file or the new one fully intact - never the half-written file that ate the library once.
 *
 * [read] falls back to `.old` when the live file is missing or fails to parse, so a single corrupt file
 * can't return "empty" and get overwritten with empty on the next save.
 */
object SafeFile {
    private val log = LoggerFactory.getLogger(SafeFile::class.java)

    private fun sib(file: Path, ext: String) = file.resolveSibling(file.fileName.toString() + ext)

    fun writeAtomic(file: Path, text: String) {
        file.createParentDirectories()
        val tmp = sib(file, ".tmp")
        FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
            ch.write(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)))
            ch.force(true) // flush to disk before the rename
        }
        // Rotate the last known-good copy one deep. Best-effort: a partial .old never endangers the live file.
        if (file.exists()) runCatching { Files.copy(file, sib(file, ".old"), StandardCopyOption.REPLACE_EXISTING) }
        runCatching {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure {
            // Rare: a filesystem that won't do an atomic same-dir move. Plain replace is still whole-file.
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Read [file], parsing with [parse] (which must return null on any failure). Tries the live file,
     * then `.old`. Returns null only when neither yields a valid value - logged loudly so corruption
     * is visible instead of silently becoming an empty store.
     */
    fun <T> read(file: Path, parse: (String) -> T?): T? {
        for ((label, p) in listOf("live" to file, "backup(.old)" to sib(file, ".old"))) {
            if (!p.exists()) continue
            val txt = runCatching { p.readText() }.getOrNull() ?: continue
            val v = parse(txt)
            if (v != null) {
                if (label != "live") log.error("{}: live file unusable - recovered from {}", file.fileName, p.fileName)
                return v
            }
            log.error("{}: {} copy failed to parse ({} chars) - not overwriting it", file.fileName, label, txt.length)
        }
        return null
    }
}
