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
    /**
     * Absolute URL of a prebuilt jar, when the repo publishes one (v2 index only). Present means we
     * can install without translating any DEX.
     */
    val jar: String? = null,
    /** extensions-lib version this was built against, e.g. "1.6". Empty on a legacy index. */
    val extensionLib: String = "",
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
fun ExtensionRepoEntry.apkUrl(indexUrl: String): String = resolve(apk, indexUrl, "apk")

/**
 * The prebuilt jar a repo publishes alongside the apk. Installing that directly skips DEX
 * translation, which is what makes an extension immune to dex2jar's mistranslation of newer
 * (DEX 038 / minSdk >= 26) builds.
 *
 * A v2 index states the URL outright. A legacy `index.min.json` doesn't mention jars at all, so it's
 * derived from the apk's name — verified against all 1367 entries of the Keiyoushi index, but still
 * a convention we don't control, so callers must treat a failed download as "no prebuilt jar here"
 * and fall back to the apk.
 */
fun ExtensionRepoEntry.jarUrl(indexUrl: String): String =
    jar?.takeIf { it.isNotBlank() }
        ?: resolve("${apk.substringAfterLast('/').removeSuffix(".apk")}.jar", indexUrl, "jar")

/** v2 entries carry absolute URLs; legacy ones are a bare filename under `<base>/<dir>/`. */
private fun resolve(
    value: String,
    indexUrl: String,
    dir: String,
): String =
    if (value.startsWith("http://") || value.startsWith("https://")) {
        value
    } else {
        "${indexUrl.substringBeforeLast('/', missingDelimiterValue = indexUrl)}/$dir/$value"
    }
