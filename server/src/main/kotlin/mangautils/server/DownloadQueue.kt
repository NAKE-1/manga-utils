/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.server

import mangautils.core.config.SettingsStore
import mangautils.core.download.ChapterSelect
import mangautils.core.download.DownloadManager
import mangautils.core.download.SourceRef
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs web-triggered downloads off the request thread, one at a time. Each task calls
 * [DownloadManager.download] (which records its own Job in StatusStore as RUNNING -> DONE/FAILED),
 * so the Downloads screen just reads StatusStore plus this queue's pending count.
 */
object DownloadQueue {
    private val log = LoggerFactory.getLogger(javaClass)
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "dl-queue").apply { isDaemon = true } }
    private val queued = AtomicInteger(0)

    fun queuedCount(): Int = queued.get()

    fun enqueue(ref: SourceRef, select: ChapterSelect) {
        queued.incrementAndGet()
        exec.submit {
            queued.decrementAndGet()
            runCatching {
                val s = SettingsStore.get()
                DownloadManager(concurrency = s.downloadConcurrency, retries = s.downloadRetries, existingPolicy = s.existingBehavior)
                    .download(ref, emptyList(), select)
            }.onFailure { log.warn("download task failed: {}", it.message) }
        }
    }
}
