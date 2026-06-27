/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() =
    application {
        val state = rememberWindowState(width = 1180.dp, height = 800.dp)
        Window(onCloseRequest = ::exitApplication, state = state, title = "manga-utils") {
            val scheme = remember { MuTheme.darkScheme }
            MaterialTheme(colorScheme = scheme) {
                App()
            }
        }
    }
