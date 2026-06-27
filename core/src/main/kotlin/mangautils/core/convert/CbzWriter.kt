/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.convert

import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createParentDirectories
import kotlin.io.path.outputStream

/** An ordered page image: its raw bytes and detected extension. */
data class PageImage(
    val index: Int,
    val bytes: ByteArray,
    val extension: String,
)

/** Writes a CBZ (zip of images, named 001.jpg.., plus ComicInfo.xml). */
object CbzWriter {
    private val log = LoggerFactory.getLogger(javaClass)

    fun write(
        dest: Path,
        pages: List<PageImage>,
        comicInfo: ComicInfoData,
    ) {
        dest.createParentDirectories()
        // Images are already compressed; store the zip without re-deflating for speed.
        ZipOutputStream(dest.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            pages.sortedBy { it.index }.forEachIndexed { i, page ->
                val name = "%03d.%s".format(i + 1, page.extension)
                zip.putNextEntry(ZipEntry(name))
                zip.write(page.bytes)
                zip.closeEntry()
            }
            val xml = ComicInfo.toXml(comicInfo.copy(pageCount = pages.size)).toByteArray()
            zip.putNextEntry(ZipEntry("ComicInfo.xml"))
            zip.write(xml)
            zip.closeEntry()
        }
        log.debug("Wrote {} ({} pages)", dest, pages.size)
    }
}
