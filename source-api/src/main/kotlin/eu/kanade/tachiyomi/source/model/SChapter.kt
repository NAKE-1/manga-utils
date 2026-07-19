@file:Suppress("ktlint:standard:property-naming")

package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject
import java.io.Serializable

interface SChapter : Serializable {
    var url: String

    var name: String

    var date_upload: Long

    var chapter_number: Float

    var scanlator: String?

    /**
     * Free-form data a source can attach for the app's own purposes, without affecting the visible
     * chapter data. Apps namespace their own keys (e.g. `"mihon.*"`).
     *
     * @since tachiyomix 1.6
     */
    var memo: JsonObject

    fun copyFrom(other: SChapter) {
        name = other.name
        url = other.url
        date_upload = other.date_upload
        chapter_number = other.chapter_number
        scanlator = other.scanlator
    }

    companion object {
        fun create(): SChapter = SChapterImpl()
    }
}
