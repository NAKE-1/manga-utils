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
import java.time.format.DateTimeFormatter

/**
 * DYNO Phase 0 — dump the library to a mounted USB drive.
 *
 * Writes two things to [run]'s target directory (a host-mounted, bind-mounted USB path):
 *  1. `Backups/backup-<ts>.tachibk.gz` — the full gz-protobuf metadata backup ([BackupImport.export]),
 *     carrying library entries + read marks + bookmarks (NOT history/resume — no such model yet).
 *  2. `Library/<series>/<chapter>.cbz` + `cover.<ext>` — a mirror of [AppConfig.downloadsDir].
 *
 * Design (per DYNO-IMPLEMENTATION-SPEC §9/§13):
 *  - **Additive only.** Never deletes anything on the drive.
 *  - **Idempotent/incremental.** A file already present with an identical size is skipped, so a second
 *    run only copies what changed instead of recopying the whole library.
 *  - **Atomic writes.** Every file is written to a sibling under `Temp/` (or a `.part` sibling),
 *    fsync'd, then renamed into place — a yanked USB never leaves a half-written blob/CBZ.
 *
 * Pure disk logic with no server dependencies, so the CLI/desktop can reuse it. The server wraps it in
 * a progress-tracking job (`BackupJob`).
 */
object UsbBackup {
    private val log = LoggerFactory.getLogger(javaClass)

    enum class Phase { PREPARING, EXPORTING, COPYING, DONE, FAILED }

    /** Live progress callback: current phase, files done / total (copy phase), bytes copied so far. */
    fun interface Progress {
        fun update(phase: Phase, filesDone: Int, filesTotal: Int, bytesCopied: Long)
    }

    data class Result(
        val ok: Boolean,
        val error: String? = null,
        val blobName: String? = null,
        val filesCopied: Int = 0,
        val filesSkipped: Int = 0,
        val bytesCopied: Long = 0,
    )

    private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /**
     * Run a backup into [target] (the mounted drive root). Returns a [Result]; never throws — failures
     * (drive not mounted, not writable, IO error) come back as `ok=false` with an [Result.error].
     */
    fun run(target: Path, progress: Progress = Progress { _, _, _, _ -> }): Result {
        progress.update(Phase.PREPARING, 0, 0, 0)
        val backups: Path
        val temp: Path
        val library: Path
        try {
            // Precheck: target must exist and be a directory. Creating the subdirs doubles as the real
            // "is the USB actually mounted and writable?" test — a read-only or absent mount throws here.
            if (!Files.isDirectory(target)) {
                return fail(target, "drive not mounted / not a directory: $target")
            }
            backups = Files.createDirectories(target.resolve("Backups"))
            temp = Files.createDirectories(target.resolve("Temp"))
            library = Files.createDirectories(target.resolve("Library"))
        } catch (e: Exception) {
            return fail(target, "drive not writable at $target: ${e.message}")
        }

        return try {
            // 1. Export the metadata blob (full sections) and write it atomically.
            progress.update(Phase.EXPORTING, 0, 0, 0)
            val bytes = BackupImport.export(
                BackupImport.Sections(library = true, settings = true, repos = true, extensions = true),
            )
            val blobName = "backup-${LocalDateTime.now().format(TS)}.tachibk.gz"
            writeAtomic(temp, backups.resolve(blobName), bytes)

            // 2. Mirror the downloads dir into Library/ (additive, incremental, atomic per file).
            var copied = 0
            var skipped = 0
            var bytesCopied = 0L
            val src = AppConfig.downloadsDir
            if (Files.isDirectory(src)) {
                val files = Files.walk(src).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
                val total = files.size
                progress.update(Phase.COPYING, 0, total, 0)
                for (file in files) {
                    val rel = src.relativize(file).toString()
                    val dest = library.resolve(rel)
                    val size = runCatching { Files.size(file) }.getOrDefault(0L)
                    if (Files.exists(dest) && runCatching { Files.size(dest) }.getOrDefault(-1L) == size) {
                        skipped++
                    } else {
                        copyAtomic(temp, file, dest)
                        copied++
                        bytesCopied += size
                    }
                    progress.update(Phase.COPYING, copied + skipped, total, bytesCopied)
                }
            }

            // 3. Finalize: clear scratch, log the run.
            cleanTemp(temp)
            appendLog(target, blobName, copied, skipped, bytesCopied, true, null)
            progress.update(Phase.DONE, copied + skipped, copied + skipped, bytesCopied)
            log.info("USB backup complete → {} (blob {}, {} copied, {} skipped, {} bytes)", target, blobName, copied, skipped, bytesCopied)
            Result(ok = true, blobName = blobName, filesCopied = copied, filesSkipped = skipped, bytesCopied = bytesCopied)
        } catch (e: Exception) {
            log.warn("USB backup failed: {}", e.message)
            appendLog(target, null, 0, 0, 0, false, e.message)
            progress.update(Phase.FAILED, 0, 0, 0)
            Result(ok = false, error = e.message ?: e.toString())
        }
    }

    private fun fail(target: Path, msg: String): Result {
        log.warn("USB backup precheck failed: {}", msg)
        return Result(ok = false, error = msg)
    }

    /** Write [bytes] to [dest] via a fsync'd temp file + atomic rename. */
    private fun writeAtomic(tempDir: Path, dest: Path, bytes: ByteArray) {
        dest.parent?.let { Files.createDirectories(it) }
        val part = tempDir.resolve("${dest.fileName}.${System.nanoTime()}.part")
        Files.newOutputStream(part).use { it.write(bytes) }
        fsync(part)
        moveInto(part, dest)
    }

    /** Copy [src] to [dest] via a fsync'd temp file + atomic rename. */
    private fun copyAtomic(tempDir: Path, src: Path, dest: Path) {
        dest.parent?.let { Files.createDirectories(it) }
        val part = tempDir.resolve("${dest.fileName}.${System.nanoTime()}.part")
        Files.copy(src, part, StandardCopyOption.REPLACE_EXISTING)
        fsync(part)
        moveInto(part, dest)
    }

    /** Rename [part] over [dest], preferring an atomic move; fall back to a plain replace across quirky FSes. */
    private fun moveInto(part: Path, dest: Path) {
        try {
            Files.move(part, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun fsync(path: Path) {
        runCatching {
            java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
        }
    }

    private fun cleanTemp(temp: Path) {
        runCatching {
            Files.list(temp).use { s -> s.forEach { runCatching { Files.deleteIfExists(it) } } }
        }
    }

    /** Append one newline-JSON record to `.dyno-backup.log` on the drive. Best-effort. */
    private fun appendLog(target: Path, blob: String?, copied: Int, skipped: Int, bytes: Long, ok: Boolean, err: String?) {
        runCatching {
            val line = buildString {
                append('{')
                append("\"ts\":").append(System.currentTimeMillis())
                append(",\"ok\":").append(ok)
                append(",\"blob\":").append(if (blob == null) "null" else "\"${blob}\"")
                append(",\"copied\":").append(copied)
                append(",\"skipped\":").append(skipped)
                append(",\"bytes\":").append(bytes)
                if (err != null) append(",\"error\":\"").append(err.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
                append("}\n")
            }
            Files.write(
                target.resolve(".dyno-backup.log"),
                line.toByteArray(),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
            )
        }
    }
}
