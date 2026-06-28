/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.convert

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * Writes a chapter as a folder of page images (001.jpg, 002.jpg, …) plus ComicInfo.xml — the
 * default download format (matches Suwayomi's FolderProvider). Pages stay as the source's bytes.
 */
object FolderWriter {
    private val log = LoggerFactory.getLogger(javaClass)

    fun write(
        destDir: Path,
        pages: List<PageImage>,
        comicInfo: ComicInfoData,
    ) {
        Files.createDirectories(destDir)
        pages.sortedBy { it.index }.forEachIndexed { i, page ->
            destDir.resolve("%03d.%s".format(i + 1, page.extension)).writeBytes(page.bytes)
        }
        destDir.resolve("ComicInfo.xml").writeText(ComicInfo.toXml(comicInfo.copy(pageCount = pages.size)))
        log.debug("Wrote folder {} ({} pages)", destDir, pages.size)
    }
}
