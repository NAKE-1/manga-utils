/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import mangautils.core.config.SettingsStore

/** Force the native Windows title bar into dark mode (DWMWA_USE_IMMERSIVE_DARK_MODE). */
private fun applyDarkTitleBar(window: java.awt.Window) {
    if (!System.getProperty("os.name").orEmpty().lowercase().contains("win")) return
    runCatching {
        val hwnd = WinDef.HWND(Native.getWindowPointer(window))
        val enabled = IntByReference(1)
        // 20 on Windows 10 2004+/11; 19 on older builds — set both, the wrong one is a no-op.
        DwmApi.INSTANCE.DwmSetWindowAttribute(hwnd, 19, enabled.pointer, 4)
        DwmApi.INSTANCE.DwmSetWindowAttribute(hwnd, 20, enabled.pointer, 4)
    }
}

private interface DwmApi : com.sun.jna.Library {
    fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attr: Int, value: com.sun.jna.Pointer, size: Int): Int

    companion object {
        val INSTANCE: DwmApi = Native.load("dwmapi", DwmApi::class.java)
    }
}

fun main() {
    runCatching { SettingsStore.get() }.getOrNull()?.let { MuTheme.apply(it.themeName, it.themeDark) }
    application {
        val state = rememberWindowState(width = 1180.dp, height = 800.dp)
        Window(onCloseRequest = ::exitApplication, state = state, title = "manga-utils") {
            LaunchedEffect(Unit) { applyDarkTitleBar(window) }
            MaterialTheme(colorScheme = MuTheme.scheme) {
                App()
            }
        }
    }
}
