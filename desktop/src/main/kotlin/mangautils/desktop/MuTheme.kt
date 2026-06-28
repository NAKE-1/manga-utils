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
import androidx.compose.ui.graphics.lerp

/** A resolved theme: accent + surfaces for both dark and light. */
data class MuPalette(
    val name: String,
    val accentDark: Color,
    val accentLight: Color,
    val inkDark: Color,
    val panelDark: Color,
    val panelHighDark: Color,
    val inkLight: Color,
    val panelLight: Color,
    val panelHighLight: Color,
)

/**
 * Theme spec mirroring Suwayomi's `Themes.ts` (primary per mode + optional explicit backgrounds).
 * Where a dark background isn't given, MUI/Suwayomi derive one from the primary — we do the same.
 */
private data class ThemeSpec(
    val name: String,
    val darkPrimary: Long,
    val lightPrimary: Long,
    val darkPaper: Long? = null,
    val darkDefault: Long? = null,
    val lightPaper: Long? = null,
    val lightDefault: Long? = null,
)

object MuTheme {
    private val specs =
        listOf(
            ThemeSpec("Default", 0xFF5B74EF, 0xFF5B74EF),
            ThemeSpec("Lavender", 0xFFA076FD, 0xFF6D41C8, darkPaper = 0xFF1D193B, darkDefault = 0xFF111129, lightPaper = 0xFFE4D5F8, lightDefault = 0xFFEDE2FF),
            ThemeSpec("Dune", 0xFF897869, 0xFF897869),
            ThemeSpec("Rosegold", 0xFFE9A7A1, 0xFFC07F7A, lightPaper = 0xFFEAE1E0, lightDefault = 0xFFF3EFEE),
            ThemeSpec("Forest Dew", 0xFF53A584, 0xFF53A584),
            ThemeSpec("Mountain Sunset", 0xFFC55A77, 0xFF974258, lightPaper = 0xFFE5D6DA, lightDefault = 0xFFF7F3F4),
            ThemeSpec("Crimson", 0xFFDC143C, 0xFFDC143C),
            ThemeSpec("Minty Miracles", 0xFF5CE6A1, 0xFF00C56A, lightPaper = 0xFFD6EAE0, lightDefault = 0xFFE9F3EE),
            ThemeSpec("Orange Juice", 0xFFFFB546, 0xFFE74C00, lightPaper = 0xFFEDE3D3, lightDefault = 0xFFF5F0E8),
            ThemeSpec("Bright Pink", 0xFFFF007F, 0xFFFF007F),
            ThemeSpec("Veronica", 0xFFA020F0, 0xFFA020F0),
            ThemeSpec("Tree Frog Green", 0xFF8ACE31, 0xFF4F9513, lightPaper = 0xFFDDE6D0, lightDefault = 0xFFEDF1E6),
            ThemeSpec("Ying & Yang", 0xFFFFFFFF, 0xFF000000, lightPaper = 0xFFEFEFEF, lightDefault = 0xFFFFFFFF),
        )

    val presets: List<MuPalette> = specs.map(::build)

    private fun build(s: ThemeSpec): MuPalette {
        val pDark = Color(s.darkPrimary)
        val pLight = Color(s.lightPrimary)
        // Derive a dark surface tinted by the primary when Suwayomi doesn't specify one.
        val inkD = s.darkDefault?.let(::Color) ?: lerp(Color(0xFF0D0D0F), pDark, 0.05f)
        val panelD = s.darkPaper?.let(::Color) ?: lerp(Color(0xFF18181C), pDark, 0.07f)
        val panelHiD = lerp(panelD, Color.White, 0.06f)
        val inkL = s.lightDefault?.let(::Color) ?: Color(0xFFFAFAFA)
        val panelL = s.lightPaper?.let(::Color) ?: Color(0xFFFFFFFF)
        val panelHiL = lerp(panelL, Color.Black, 0.05f)
        return MuPalette(s.name, pDark, pLight, inkD, panelD, panelHiD, inkL, panelL, panelHiL)
    }

    var palette by mutableStateOf(presets.first())
        private set

    var dark by mutableStateOf(true)
        private set

    /** When set (e.g. on a manga page with dynamic colors), the whole app recolors to this. */
    var dynamicOverride: MuPalette? by mutableStateOf(null)

    private val active get() = dynamicOverride ?: palette

    fun apply(name: String, isDark: Boolean) {
        palette = presets.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: presets.first()
        dark = isDark
    }

    val Vermilion get() = if (dark) active.accentDark else active.accentLight
    val VermilionDim get() = lerp(Vermilion, Color.Black, 0.5f)
    val Ink get() = if (dark) active.inkDark else active.inkLight
    val Panel get() = if (dark) active.panelDark else active.panelLight
    val PanelHigh get() = if (dark) active.panelHighDark else active.panelHighLight
    val Paper get() = if (dark) Color(0xFFECEAE3) else Color(0xFF1A1A1F)
    val Muted get() = if (dark) Color(0xFF8A8F9C) else Color(0xFF6A6F7C)

    val scheme: ColorScheme
        get() =
            if (dark) {
                darkColorScheme(
                    primary = Vermilion, onPrimary = Color(0xFF120606), secondary = Paper, onSecondary = Ink,
                    background = Ink, onBackground = Paper, surface = Panel, onSurface = Paper,
                    surfaceVariant = PanelHigh, onSurfaceVariant = Muted, outline = lerp(Panel, Color.White, 0.18f), error = Vermilion,
                )
            } else {
                lightColorScheme(
                    primary = Vermilion, onPrimary = Color(0xFFFFFFFF), secondary = Paper, onSecondary = Ink,
                    background = Ink, onBackground = Paper, surface = Panel, onSurface = Paper,
                    surfaceVariant = PanelHigh, onSurfaceVariant = Muted, outline = lerp(Panel, Color.Black, 0.18f), error = Vermilion,
                )
            }
}
