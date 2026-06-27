/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.util

import eu.kanade.tachiyomi.source.model.SChapter

/** The chapter's number, parsed from the name when the source leaves `chapter_number` unset. */
object ChapterNumber {
    fun of(ch: SChapter): Float {
        if (ch.chapter_number >= 0f) return ch.chapter_number
        val byKeyword = Regex("(?i)chapter\\s*([0-9]+(?:\\.[0-9]+)?)").find(ch.name)
        val any = byKeyword ?: Regex("([0-9]+(?:\\.[0-9]+)?)").find(ch.name)
        return any?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: -1f
    }
}
