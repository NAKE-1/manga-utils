/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.server

import mangautils.core.backup.UsbBackup
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * DYNO Phase 0 — server-side wrapper that runs a single [UsbBackup] on a background thread and exposes
 * live progress the web UI can poll. Modeled on [DownloadQueue]: a daemon executor + a [Task] holding
 * `@Volatile` progress fields. Only one backup runs at a time (starting again returns the active one).
 */
object BackupJob {
    private val log = LoggerFactory.getLogger(javaClass)

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "usb-backup").apply { isDaemon = true } }
    private var seq = 0L

    class Task(val id: String, val target: String) {
        @Volatile var state = "queued" // queued | running | done | failed
        @Volatile var phase = UsbBackup.Phase.PREPARING.name
        @Volatile var filesDone = 0
        @Volatile var filesTotal = 0
        @Volatile var bytesCopied = 0L
        @Volatile var blobName = ""
        @Volatile var filesSkipped = 0
        @Volatile var error = ""
        @Volatile var startedAt = System.currentTimeMillis()
        @Volatile var finishedAt = 0L
        val active get() = state == "queued" || state == "running"
    }

    @Volatile private var current: Task? = null

    /** Start a backup to [target]. If one is already running, returns it instead of starting another. */
    @Synchronized
    fun start(target: Path): Task {
        current?.let { if (it.active) return it }
        val task = Task("usb-${seq++}-${System.nanoTime().toString(36).takeLast(4)}", target.toString())
        current = task
        exec.submit { run(task, target) }
        return task
    }

    fun snapshot(): Task? = current

    private fun run(task: Task, target: Path) {
        task.state = "running"
        val result = UsbBackup.run(target) { phase, done, total, bytes ->
            task.phase = phase.name
            task.filesDone = done
            task.filesTotal = total
            task.bytesCopied = bytes
        }
        task.blobName = result.blobName ?: ""
        task.filesSkipped = result.filesSkipped
        task.finishedAt = System.currentTimeMillis()
        if (result.ok) {
            task.state = "done"
        } else {
            task.state = "failed"
            task.error = result.error ?: "backup failed"
            log.warn("USB backup job {} failed: {}", task.id, task.error)
        }
    }
}
