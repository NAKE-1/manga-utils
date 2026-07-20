/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.download

/** A live snapshot of a chapter download, emitted after each page completes. */
data class PageProgress(
    val chapter: String,
    /**
     * Source URL of the chapter being downloaded. Unique per upload, unlike [chapter] — several
     * scanlations of one chapter share a name, so anything tracking progress must key on this.
     */
    val chapterUrl: String = "",
    val sourceId: Long,
    val pagesDone: Int,
    val pagesTotal: Int,
    val bytes: Long,
    val elapsedMs: Long,
) {
    /** Average download speed in bytes per second so far. */
    val bytesPerSecond: Double
        get() = if (elapsedMs > 0) bytes * 1000.0 / elapsedMs else 0.0

    val finished: Boolean get() = pagesDone >= pagesTotal
}

/** Receives live progress updates during a download (e.g. to render a progress bar). */
fun interface DownloadListener {
    fun onProgress(progress: PageProgress)
}
