/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.cli

import mangautils.core.download.DownloadListener
import mangautils.core.download.PageProgress

/** A shared, in-place progress bar listener used by `download` and `library update`. */
object CliProgress {
    val listener = DownloadListener { p -> render(p) }

    private fun render(p: PageProgress) {
        val barWidth = 24
        val filled = if (p.pagesTotal > 0) (p.pagesDone * barWidth / p.pagesTotal) else 0
        val bar = Style.ok("#".repeat(filled)) + Style.dim("-".repeat(barWidth - filled))
        val mb = p.bytes / 1_048_576.0
        val mbps = p.bytesPerSecond / 1_048_576.0
        val name = p.chapter.take(26).padEnd(26)
        val eta =
            if (p.pagesDone in 1 until p.pagesTotal && p.bytesPerSecond > 0) {
                val perPageMs = p.elapsedMs.toDouble() / p.pagesDone
                "  ETA ${"%.0f".format(perPageMs * (p.pagesTotal - p.pagesDone) / 1000.0)}s"
            } else {
                ""
            }
        val line =
            "  $name [$bar] ${p.pagesDone}/${p.pagesTotal}  " +
                "${"%.1f".format(mb)}MB  ${Style.lang("%.1f".format(mbps) + " MB/s")}$eta"
        print("\r$line     ")
        System.out.flush()
        if (p.finished) println()
    }
}
