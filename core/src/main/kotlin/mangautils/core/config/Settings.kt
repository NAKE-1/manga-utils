/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.config

import kotlinx.serialization.Serializable
import mangautils.core.download.ExistingPolicy

/** Optional image recompression applied when writing chapters. Null = keep original bytes. */
@Serializable
data class ImageQuality(
    /** Downscale images wider than this (px). 0 = no limit. */
    val maxWidth: Int = 0,
    /** JPEG quality 1..100 when re-encoding. */
    val jpegQuality: Int = 90,
    /** Re-encode WEBP/other to JPEG for wider reader compatibility. */
    val convertToJpeg: Boolean = false,
)

/**
 * App-level settings, persisted to `data/settings.json`. Read by the engine and CLI.
 * Per-source preferences (quality/language a *source* exposes) are a separate, later feature.
 */
@Serializable
data class Settings(
    val downloadConcurrency: Int = 4,
    val downloadRetries: Int = 3,
    val existingBehavior: ExistingPolicy = ExistingPolicy.SKIP,
    /** Output format. Only "cbz" is implemented today; here for forward-compat. */
    val defaultFormat: String = "cbz",
    /** Default language filter for `ext list` and the friendly search picker (null = all). */
    val defaultLanguage: String? = null,
    /** Show NSFW-flagged extensions/sources by default. */
    val nsfwVisible: Boolean = false,
    val imageQuality: ImageQuality? = null,
    /** Source/extension language filter for the GUI (empty = show all). */
    val allowedLanguages: List<String> = emptyList(),
    /** Selected GUI theme name + light/dark mode. */
    val themeName: String = "Default",
    val themeDark: Boolean = true,
    /** Use the manga's blurred cover as the detail-page background. */
    val mangaThumbnailBackground: Boolean = true,
    /** Tint the detail page with the cover's dominant color. */
    val dynamicThemeColors: Boolean = true,
)
