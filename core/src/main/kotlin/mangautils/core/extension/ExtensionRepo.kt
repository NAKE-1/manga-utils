/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.extension

import kotlinx.serialization.Serializable

/**
 * One entry in a Tachiyomi/Mihon extension repository `index.min.json`.
 * Unknown fields are ignored when parsing (the real format has more).
 */
@Serializable
data class ExtensionRepoEntry(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    /** version code (monotonic, used to detect updates) */
    val code: Int = 0,
    val version: String = "",
    /** 1 = contains NSFW sources */
    val nsfw: Int = 0,
    val sources: List<RepoSource> = emptyList(),
) {
    val isNsfw: Boolean get() = nsfw == 1
}

/** A single source advertised by an extension. */
@Serializable
data class RepoSource(
    val name: String = "",
    val lang: String = "",
    /** stable numeric source id (as a string in the index) */
    val id: String = "",
    val baseUrl: String = "",
)

/**
 * Resolves the absolute download URL of an extension's APK. In a Tachiyomi repo the
 * index sits at `<base>/index.min.json` and apks live under `<base>/apk/<apk>`.
 */
fun ExtensionRepoEntry.apkUrl(indexUrl: String): String {
    val base = indexUrl.substringBeforeLast('/', missingDelimiterValue = indexUrl)
    return "$base/apk/$apk"
}
