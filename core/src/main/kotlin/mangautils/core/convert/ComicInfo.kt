/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.convert

/** Metadata for a single chapter, written as ComicInfo.xml (Komga/Kavita/CBR compatible). */
data class ComicInfoData(
    val series: String,
    val title: String,
    val number: String,
    val writer: String? = null,
    val penciller: String? = null,
    val genre: String? = null,
    val summary: String? = null,
    val pageCount: Int = 0,
    val web: String? = null,
)

object ComicInfo {
    fun toXml(data: ComicInfoData): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """)
            .append("""xmlns:xsd="http://www.w3.org/2001/XMLSchema">""").append('\n')
        fun tag(name: String, value: String?) {
            if (!value.isNullOrBlank()) sb.append("  <").append(name).append('>')
                .append(escape(value)).append("</").append(name).append('>').append('\n')
        }
        tag("Series", data.series)
        tag("Title", data.title)
        tag("Number", data.number)
        tag("Writer", data.writer)
        tag("Penciller", data.penciller)
        tag("Genre", data.genre)
        tag("Summary", data.summary)
        if (data.pageCount > 0) tag("PageCount", data.pageCount.toString())
        tag("Web", data.web)
        tag("Manga", "Yes")
        sb.append("</ComicInfo>").append('\n')
        return sb.toString()
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
