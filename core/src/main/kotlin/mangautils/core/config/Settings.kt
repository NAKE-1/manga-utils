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
    /** Save downloads as a CBZ archive; off (default) = a folder of page images (like Suwayomi). */
    val downloadAsCbz: Boolean = false,
    /** How many chapters to download at once (chapter-level parallelism in the web queue). */
    val parallelDownloads: Int = 3,
    /** Override where downloads are saved (absolute path). Null = default `<dataDir>/downloads`. */
    val downloadDir: String? = null,
    /** Languages whose sources are shown in the web UI (codes like "en", "fr"). Empty = all;
     *  language-agnostic sources ("all"/blank) are always shown. Defaults to English only. */
    val visibleLanguages: List<String> = listOf("en"),
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
    /** Library/browse grid density: COMPACT | COMFORTABLE | LIST. */
    val gridMode: String = "COMFORTABLE",
    /** Most recently opened source (shown in the "Last used" row on Browse). */
    val lastUsedSourceId: Long = 0,
    /** Pinned sources, shown at the top of the source list. */
    val pinnedSources: List<Long> = emptyList(),
    // ---- Reader ----
    // Reading mode is always long-strip (webtoon); no per-mode setting.
    /** Page scale in the reader: FIT_WIDTH | ORIGINAL. */
    val readerScaleType: String = "FIT_WIDTH",
    /** Gap between pages (px). */
    val readerPageGap: Int = 0,
    /** Reader background: THEME | BLACK | GRAY | WHITE. */
    val readerBackground: String = "THEME",
    /** Skip duplicate chapters (scanlator de-dup) when navigating in the reader. */
    val readerSkipDuplicates: Boolean = true,
)
