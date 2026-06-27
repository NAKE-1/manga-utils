/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.desktop

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * "Ink & Vermilion" — a dark, manga-grounded palette: ink-black surfaces, warm paper text,
 * and a single vermilion (hanko-seal red) accent. Read state dims to muted; unread glows accent.
 */
object MuTheme {
    val Ink = Color(0xFF0E0F13)
    val Panel = Color(0xFF1A1D27)
    val PanelHigh = Color(0xFF222531)
    val Paper = Color(0xFFECEAE3)
    val Muted = Color(0xFF8A8F9C)
    val Vermilion = Color(0xFFE8483D)
    val VermilionDim = Color(0xFF7A2A26)

    val darkScheme =
        darkColorScheme(
            primary = Vermilion,
            onPrimary = Color(0xFF1A0B0A),
            secondary = Paper,
            onSecondary = Ink,
            background = Ink,
            onBackground = Paper,
            surface = Panel,
            onSurface = Paper,
            surfaceVariant = PanelHigh,
            onSurfaceVariant = Muted,
            outline = Color(0xFF333845),
            error = Vermilion,
        )
}
