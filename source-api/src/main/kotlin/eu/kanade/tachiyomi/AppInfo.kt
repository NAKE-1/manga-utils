/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package eu.kanade.tachiyomi

/**
 * Used by extensions to query the host app. Minimal manga-utils implementation
 * (the upstream Suwayomi version reads its server BuildConfig + ImageUtil; we keep
 * the same public surface so extensions compiled against extensions-lib still work).
 *
 * @since extension-lib 1.3
 */
object AppInfo {
    /** A high integer so extensions' "min app version" checks pass. @since extension-lib 1.3 */
    fun getVersionCode(): Int = 9999

    /** A semver-looking string. @since extension-lib 1.3 */
    fun getVersionName(): String = "0.1.0"

    /** Image MIME types the reader supports. @since extension-lib 1.5 */
    fun getSupportedImageMimeTypes(): List<String> =
        listOf(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
            "image/avif",
            "image/heif",
        )
}
