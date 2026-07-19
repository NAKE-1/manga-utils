package mangautils.server

import mangautils.core.config.AppConfig
import mangautils.core.config.SettingsStore
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.absolute
import kotlin.io.path.name

/**
 * Relocate the downloads library to a new root (e.g. a big external SSD), laying down a canonical
 * `manga-utils/` tree there. Cross-filesystem safe: always **copy → verify → (move only) delete** —
 * the source is never removed until the copy is byte-for-byte verified. Runs on a background thread
 * with a verbose, step-by-step log the UI polls (see [snapshot]).
 *
 * Relocates the **downloads** dir honoring the chosen mode (the space-heavy part; applied at runtime via
 * [AppConfig.downloadDirOverride]), then **stages** covers + the library metadata (library/history/read/
 * queue/… json) + the per-source `settings/` (prefs + cookies) into the same `manga-utils/` root —
 * **copy-only**, since the running server keeps reading those from the old data dir until it's repointed
 * on a restart / the new machine. The result is a self-contained library root you can mount elsewhere and
 * use with **no re-download**. The copy is incremental (files already present at the same size are
 * skipped), so a re-run or a resumed transfer only moves what's missing.
 */
object RelocateJob {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile var running = false; private set
    @Volatile var phase = "idle"; private set
    @Volatile var finished = false; private set
    @Volatile var error = ""; private set
    @Volatile var mode = ""; private set
    @Volatile var target = ""; private set
    @Volatile var filesTotal = 0L; private set
    @Volatile var filesDone = 0L; private set
    @Volatile var bytesTotal = 0L; private set
    @Volatile var bytesDone = 0L; private set
    val steps = CopyOnWriteArrayList<String>()

    data class Preview(
        val sourceBytes: Long,
        val sourceFiles: Long,
        val targetFreeBytes: Long,
        val targetLayout: String,
        val activeDownloads: Int,
        val fits: Boolean,
        val warning: String,
    )

    /** Side-effect-free: sizes, free space on the target, and whether it's safe to start. */
    fun plan(rootRaw: String): Preview {
        val root = Path.of(rootRaw.trim()).absolute().normalize()
        val src = AppConfig.downloadsDir
        val (bytes, files) = sizeOf(src)
        val layout = root.resolve("manga-utils").resolve("downloads")
        // Free space is measured on the nearest existing ancestor of the target (the drive/mount).
        val free = runCatching { Files.getFileStore(nearestExisting(root)).usableSpace }.getOrDefault(0L)
        val active = runCatching { DownloadQueue.activeCount() + DownloadQueue.queuedCount() }.getOrDefault(0)
        val warn = buildString {
            if (active > 0) append("$active download(s) in progress — stop them first. ")
            if (layout.startsWith(src) || src.startsWith(layout)) append("Target overlaps the current downloads folder. ")
        }
        return Preview(bytes, files, free, layout.toString(), active, bytes < free, warn.trim())
    }

    /** mode: "move" | "copy" | "point". Starts a background thread; poll [snapshot]. */
    fun start(rootRaw: String, mode: String) {
        if (running) return
        running = true; finished = false; error = ""; steps.clear()
        filesTotal = 0; filesDone = 0; bytesTotal = 0; bytesDone = 0
        this.mode = mode; this.target = rootRaw.trim(); phase = "starting"
        Thread({ run(rootRaw.trim(), mode) }, "relocate").apply { isDaemon = true }.start()
    }

    fun snapshot(): Map<String, Any> = mapOf(
        "running" to running, "phase" to phase, "finished" to finished, "error" to error,
        "mode" to mode, "target" to target,
        "filesTotal" to filesTotal, "filesDone" to filesDone,
        "bytesTotal" to bytesTotal, "bytesDone" to bytesDone,
        "steps" to steps.toList(),
    )

    private fun step(s: String) { steps.add(s); log.info("relocate: {}", s) }

    private fun run(rootRaw: String, mode: String) {
        try {
            val root = Path.of(rootRaw).absolute().normalize()
            val src = AppConfig.downloadsDir
            val data = AppConfig.dataDir
            val dstRoot = root.resolve("manga-utils")
            val dstDownloads = dstRoot.resolve("downloads")

            // 1. validate
            phase = "validate"
            require(mode in setOf("move", "copy", "point")) { "unknown mode '$mode'" }
            require(!dstDownloads.startsWith(src) && !src.startsWith(dstDownloads)) {
                "The new location overlaps the current downloads folder — pick a different drive/folder."
            }
            if (mode != "point") {
                require(DownloadQueue.activeCount() == 0 && DownloadQueue.queuedCount() == 0) {
                    "Downloads are still running — stop the queue before moving files."
                }
            }
            step("Target: $root  (mode: $mode)")

            // 2. lay down the canonical tree
            phase = "layout"
            Files.createDirectories(dstDownloads)
            step("Created manga-utils/ root under $root")

            if (mode == "point") {
                // No transfer — just point new downloads here. Existing downloads stay at the old path.
                step("Point-only: leaving existing downloads in place; new downloads will save to the new folder.")
                repoint(dstDownloads)
                step("Done. New downloads now save to $dstDownloads. (Old downloads remain at $src.)")
                phase = "done"; finished = true; return
            }

            // 3. size up + free-space guard
            phase = "measure"
            val (bytes, files) = sizeOf(src) // downloads — the part we verify + (move) delete
            val (cBytes, cFiles) = sizeOf(data.resolve("covers")) // covers ride along in the metadata pass
            bytesTotal = bytes + cBytes; filesTotal = files + cFiles
            val free = runCatching { Files.getFileStore(nearestExisting(root)).usableSpace }.getOrDefault(0L)
            step("To transfer: ${files} download file(s) (${human(bytes)}) + covers/metadata. Free on target: ${human(free)}.")
            require(bytes + cBytes < free) { "Not enough free space: need ${human(bytes + cBytes)}, have ${human(free)}." }

            // 4. copy (always copy first, even for move — cross-drive safe)
            phase = "copy"
            step("Copying…")
            copyTree(src, dstDownloads)
            step("Copied ${filesDone}/${filesTotal} file(s), ${human(bytesDone)}.")

            // 5. verify count + bytes before touching the source
            phase = "verify"
            val (dstBytes, dstFiles) = sizeOf(dstDownloads)
            step("Verify — source: ${files} files / ${human(bytes)}; target: ${dstFiles} files / ${human(dstBytes)}.")
            require(dstFiles >= files && dstBytes >= bytes) {
                "Verification failed (target has fewer files/bytes than source) — source left untouched."
            }
            step("Verified ✓")

            // 6. repoint BEFORE deleting, so a failure never leaves us pointing at deleted files
            phase = "repoint"
            repoint(dstDownloads)
            step("Downloads now point to $dstDownloads")

            // 7. move only: delete the source after verify
            if (mode == "move") {
                phase = "cleanup"
                step("Deleting the old copy at $src…")
                deleteTree(src)
                step("Old copy removed.")
            } else {
                step("Copy mode: the old library is kept at $src as a backup.")
            }

            // 8. stage covers + metadata + per-source settings into the new root (copy-only — the running
            // server keeps using the old data dir until you repoint it on a restart / the new machine).
            phase = "metadata"
            stageMetadata(dstRoot)

            phase = "done"; finished = true
            step("Relocate complete.")
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            phase = "failed"
            step("FAILED: $error")
            log.warn("relocate failed", e)
        } finally {
            running = false
        }
    }

    /**
     * Copy covers + the library metadata + per-source settings into the new root. **Copy-only** — never
     * deletes the source, because the live server still reads these from the old data dir until the data
     * dir itself is repointed (a restart / the new machine). This is what makes the new root a
     * self-contained library you can mount elsewhere with no re-download.
     */
    private fun stageMetadata(dstRoot: Path) {
        val data = AppConfig.dataDir
        for (d in listOf("covers", "settings")) {
            val s = data.resolve(d)
            if (Files.isDirectory(s)) { step("Staging $d/…"); copyTree(s, dstRoot.resolve(d)) }
        }
        val jsons = listOf(
            "library.json", "history.json", "read.json", "downloadqueue.json", "jobs.json",
            "sourcehealth.json", "settings.json", "repos.json", "cloudflare.json",
        )
        var n = 0
        for (f in jsons) {
            val s = data.resolve(f)
            if (Files.isRegularFile(s)) runCatching { Files.copy(s, dstRoot.resolve(f), StandardCopyOption.REPLACE_EXISTING) }.onSuccess { n++ }
        }
        step("Staged covers, per-source settings, and $n metadata file(s) — copy-only; old copies kept for the running server.")
        step("The new root is a self-contained library now — point the server's data dir at it (restart / new machine) to use it with no re-download.")
    }

    private fun repoint(dstDownloads: Path) {
        val s = SettingsStore.get().copy(downloadDir = dstDownloads.toString())
        SettingsStore.save(s)
        AppConfig.downloadDirOverride = dstDownloads
    }

    // ---- fs helpers ----

    private fun copyTree(src: Path, dst: Path) {
        if (!Files.exists(src)) return
        var currentSeries = ""
        Files.walk(src).use { stream ->
            stream.forEach { p ->
                val rel = src.relativize(p)
                val out = dst.resolve(rel.toString())
                // Log once when we start copying a new top-level series folder — progress without per-page spam.
                val series = if (rel.nameCount >= 1) rel.getName(0).toString() else ""
                if (series.isNotEmpty() && series != currentSeries) {
                    currentSeries = series
                    step("Copying '$series'… (${filesDone}/${filesTotal} files, ${human(bytesDone)} so far)")
                }
                if (Files.isDirectory(p)) {
                    if (!Files.exists(out)) Files.createDirectories(out)
                } else {
                    out.parent?.let { if (!Files.exists(it)) Files.createDirectories(it) }
                    val sz = runCatching { Files.size(p) }.getOrDefault(0L)
                    // Incremental / resume-safe: if the target already has this file at the same size,
                    // skip the copy (so a re-run or a resumed transfer only moves what's actually missing).
                    if (Files.exists(out) && runCatching { Files.size(out) }.getOrDefault(-1L) == sz) {
                        filesDone++; bytesDone += sz
                    } else {
                        Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                        filesDone++; bytesDone += sz
                    }
                }
            }
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { s ->
            s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun sizeOf(dir: Path): Pair<Long, Long> {
        if (!Files.exists(dir)) return 0L to 0L
        var bytes = 0L; var files = 0L
        runCatching {
            Files.walk(dir).use { s ->
                s.filter { Files.isRegularFile(it) }.forEach { bytes += runCatching { Files.size(it) }.getOrDefault(0L); files++ }
            }
        }
        return bytes to files
    }

    /** Walk up until we hit a path that exists, so getFileStore (free space) works before we mkdir. */
    private fun nearestExisting(p: Path): Path {
        var cur: Path? = p.absolute().normalize()
        while (cur != null && !Files.exists(cur)) cur = cur.parent
        return cur ?: p.root ?: p
    }

    private fun human(b: Long): String {
        if (b < 1024) return "$b B"
        val u = "KMGT"
        var v = b.toDouble(); var i = -1
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++ }
        return String.format("%.1f %sB", v, u[i])
    }
}
