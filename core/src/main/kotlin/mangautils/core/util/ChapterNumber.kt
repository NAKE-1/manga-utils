/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.util

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.util.chapter.ChapterRecognition

/**
 * The chapter's number, parsed from the name when the source leaves `chapter_number` unset (many
 * scanlator sources — aquamanga, asura, arean, kagane, allmanga — do). Delegates to Tachiyomi's
 * [ChapterRecognition] (what Suwayomi uses): it strips the manga title + volume/version tags and
 * handles "Vol.1 Ch.4", "Bleach 567", "Chapter 12.5", etc.
 */
object ChapterNumber {
    fun of(ch: SChapter, mangaTitle: String = ""): Float =
        ChapterRecognition.parseChapterNumber(mangaTitle, ch.name, ch.chapter_number.toDouble()).toFloat()
}
