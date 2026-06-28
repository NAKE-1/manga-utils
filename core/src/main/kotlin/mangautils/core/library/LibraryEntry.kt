/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.library

import kotlinx.serialization.Serializable

/**
 * Reader view mode for a series. NOT a property of the downloaded files (a CBZ is just ordered
 * page images) — it's how a reader should display them. Stored per-series; honored by the
 * future TUI/web reader and optionally written into ComicInfo.xml for external readers.
 */
enum class ReadingMode {
    RTL, // right-to-left paged (typical manga)
    LTR, // left-to-right paged (western comics)
    VERTICAL, // vertical paged
    LONG_STRIP, // continuous vertical (webtoons)
    ;

    companion object {
        fun from(s: String): ReadingMode? =
            when (s.lowercase().replace("-", "").replace("_", "")) {
                "rtl", "righttoleft" -> RTL
                "ltr", "lefttoright" -> LTR
                "vertical", "v" -> VERTICAL
                "longstrip", "webtoon", "strip" -> LONG_STRIP
                else -> null
            }
    }
}

/** A snapshot of a chapter, used to diff for "new chapter" detection + offline display. */
@Serializable
data class ChapterRef(
    val url: String,
    val name: String,
    val number: Float,
    val scanlator: String? = null,
    val dateUpload: Long = 0,
)

/**
 * A followed series. Persisted to `library.json`. `knownChapters` is the snapshot from the last
 * check; comparing a fresh chapter list against it is how the tracker detects new releases.
 */
@Serializable
data class LibraryEntry(
    val sourceId: Long,
    val mangaUrl: String,
    var title: String,
    var author: String? = null,
    var artist: String? = null,
    var description: String? = null,
    var thumbnailUrl: String? = null,
    var genre: String? = null,
    var status: Int = 0,
    var readingMode: ReadingMode = ReadingMode.RTL,
    val addedAt: Long = System.currentTimeMillis(),
    var lastCheckedAt: Long = 0,
    var knownChapters: MutableList<ChapterRef> = mutableListOf(),
) {
    val key: String get() = "$sourceId:$mangaUrl"
}
