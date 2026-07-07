/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.server

import mangautils.core.backup.BackupImport
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

/**
 * Runs a backup restore on a background thread and exposes live progress the web UI can poll, so the
 * restore shows a real bar (importing library X/Y, installing extensions) instead of one long spinner.
 * Modeled on [BackupJob]/[DownloadQueue]: a daemon executor + a [Task] with `@Volatile` progress.
 */
object ImportJob {
    private val log = LoggerFactory.getLogger(javaClass)

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "backup-import").apply { isDaemon = true } }
    private var seq = 0L

    class Task(val id: String) {
        @Volatile var state = "running" // running | done | failed
        @Volatile var phase = "Reading backup"
        @Volatile var done = 0
        @Volatile var total = 0
        @Volatile var current = ""
        @Volatile var error = ""
        @Volatile var result: BackupImport.Result? = null
        val active get() = state == "running"
    }

    @Volatile private var current: Task? = null

    /** Start restoring [bytes]. If one is already running, returns it instead of starting another. */
    @Synchronized
    fun start(bytes: ByteArray): Task {
        current?.let { if (it.active) return it }
        val task = Task("imp-${seq++}-${System.nanoTime().toString(36).takeLast(4)}")
        current = task
        exec.submit { run(task, bytes) }
        return task
    }

    fun snapshot(): Task? = current

    private fun run(task: Task, bytes: ByteArray) {
        runCatching {
            BackupImport.import(bytes) { phase, done, total, cur ->
                task.phase = phase; task.done = done; task.total = total; task.current = cur
            }
        }.onSuccess {
            task.result = it; task.phase = "Done"; task.done = it.total; task.total = it.total; task.state = "done"
        }.onFailure {
            task.state = "failed"; task.error = it.message ?: "import failed"
            log.warn("import job {} failed: {}", task.id, task.error)
        }
    }
}
