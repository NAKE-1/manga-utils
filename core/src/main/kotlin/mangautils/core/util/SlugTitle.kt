/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.util

/** Best-effort readable title from a manga url slug, e.g. "/manga/naruto.1205" -> "Naruto". */
object SlugTitle {
    fun fromUrl(url: String): String {
        var slug = url.trimEnd('/').substringAfterLast('/')
        slug = slug.replace(Regex("\\.\\d+$"), "") // strip a trailing numeric id like ".1205"
        return slug
            .replace('-', ' ')
            .replace('_', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
            .ifBlank { url }
    }
}
