/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.cli

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles

/**
 * Centralised colour scheme for CLI output (rendered via Mordant). Colours degrade
 * automatically to plain text when output is piped or the terminal lacks colour support.
 */
object Style {
    fun heading(s: String) = (TextColors.brightWhite + TextStyles.bold)(s)

    /** numeric ids (source ids, indexes) */
    fun id(s: String) = TextColors.brightYellow(s)

    /** language codes */
    fun lang(s: String) = TextColors.brightCyan(s)

    /** primary text, e.g. titles */
    fun title(s: String) = TextColors.white(s)

    /** secondary/meta text */
    fun dim(s: String) = TextColors.gray(s)

    /** field labels in detail views */
    fun label(s: String) = TextColors.cyan(s)

    fun ok(s: String) = TextColors.brightGreen(s)

    fun warn(s: String) = TextColors.brightRed(s)

    fun nsfw(s: String) = TextColors.brightMagenta(s)
}
