/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.desktop

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** A named theme — surfaces (ink/panel), text (paper/muted) and a single accent, in dark + light. */
data class MuPalette(
    val name: String,
    val accent: Color,
    val accentDim: Color,
    // dark surfaces
    val ink: Color,
    val panel: Color,
    val panelHigh: Color,
    // light surfaces (used when themeDark = false)
    val inkLight: Color = Color(0xFFEDEAE3),
    val panelLight: Color = Color(0xFFFFFFFF),
    val panelHighLight: Color = Color(0xFFE7E3DA),
)

/**
 * Theme registry. Property names match the original "Ink & Vermilion" palette so the UI code is
 * unchanged; the values are now backed by the active [palette] (a snapshot state), so switching
 * themes recomposes the whole app. Preset colors adapt Suwayomi's theme set.
 */
object MuTheme {
    val presets =
        listOf(
            MuPalette("Default", Color(0xFFE8483D), Color(0xFF7A2A26), Color(0xFF0E0F13), Color(0xFF1A1D27), Color(0xFF222531)),
            MuPalette("Lavender", Color(0xFFA076FD), Color(0xFF5A3FA0), Color(0xFF111129), Color(0xFF1D193B), Color(0xFF2A2450)),
            MuPalette("Crimson", Color(0xFFE53935), Color(0xFF7A2226), Color(0xFF140A0C), Color(0xFF241015), Color(0xFF321820)),
            MuPalette("Forest Dew", Color(0xFF43C463), Color(0xFF226A36), Color(0xFF0A140D), Color(0xFF102418), Color(0xFF183222)),
            MuPalette("Rosegold", Color(0xFFE89AA0), Color(0xFFA06868), Color(0xFF1A1012), Color(0xFF2A1C1F), Color(0xFF382428)),
            MuPalette("Mountain Sunset", Color(0xFFC4436F), Color(0xFF7A2A48), Color(0xFF160D12), Color(0xFF261520), Color(0xFF34202C)),
            MuPalette("Minty Miracles", Color(0xFF43C4A0), Color(0xFF226A58), Color(0xFF0A1410), Color(0xFF102420), Color(0xFF18322A)),
            MuPalette("Orange Juice", Color(0xFFE8A043), Color(0xFFA06822), Color(0xFF14100A), Color(0xFF241C10), Color(0xFF322818)),
        )

    var palette by mutableStateOf(presets.first())
        private set

    var dark by mutableStateOf(true)
        private set

    fun apply(name: String, isDark: Boolean) {
        palette = presets.firstOrNull { it.name == name } ?: presets.first()
        dark = isDark
    }

    val Ink get() = if (dark) palette.ink else palette.inkLight
    val Panel get() = if (dark) palette.panel else palette.panelLight
    val PanelHigh get() = if (dark) palette.panelHigh else palette.panelHighLight
    val Paper get() = if (dark) Color(0xFFECEAE3) else Color(0xFF1A1A1F)
    val Muted get() = if (dark) Color(0xFF8A8F9C) else Color(0xFF6A6F7C)
    val Vermilion get() = palette.accent
    val VermilionDim get() = palette.accentDim

    val scheme: ColorScheme
        get() =
            if (dark) {
                darkColorScheme(
                    primary = Vermilion, onPrimary = Color(0xFF120606), secondary = Paper, onSecondary = Ink,
                    background = Ink, onBackground = Paper, surface = Panel, onSurface = Paper,
                    surfaceVariant = PanelHigh, onSurfaceVariant = Muted, outline = Color(0xFF333845), error = Vermilion,
                )
            } else {
                lightColorScheme(
                    primary = Vermilion, onPrimary = Color(0xFFFFFFFF), secondary = Paper, onSecondary = Ink,
                    background = Ink, onBackground = Paper, surface = Panel, onSurface = Paper,
                    surfaceVariant = PanelHigh, onSurfaceVariant = Muted, outline = Color(0xFFCFCAC0), error = Vermilion,
                )
            }
}
