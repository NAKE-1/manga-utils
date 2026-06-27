/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.extension

import kotlinx.serialization.Serializable

/** A source advertised by an installed extension (enumerated at install time). */
@Serializable
data class InstalledSource(
    val id: Long,
    val name: String,
    val lang: String,
)

/**
 * Metadata for an extension that has been installed (APK downloaded + translated to a jar).
 * Persisted to `extensions/installed.json` so a fresh `mu` process can list and re-load
 * sources without re-installing.
 */
@Serializable
data class InstalledExtension(
    val pkg: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val lang: String,
    val nsfw: Boolean,
    /** absolute path to the translated jar under the data dir */
    val jarPath: String,
    /** fully-qualified entry classes (Source or SourceFactory) */
    val classNames: List<String>,
    val sources: List<InstalledSource>,
)
