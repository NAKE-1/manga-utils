/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Field numbers mirror Suwayomi-Server's NetworkExtensionStore so the same wire format decodes.
 */
package mangautils.core.extension

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * The pointer file (`repo.json`) that a modern repo serves in place of `index.min.json`. Its
 * `index_v2` is the URL of the real index, which is a gzipped protobuf.
 */
@Serializable
data class RepoPointer(
    @SerialName("index_v2") val indexV2: String? = null,
    val meta: Meta? = null,
) {
    @Serializable
    data class Meta(
        val name: String = "",
        val shortName: String? = null,
        val website: String = "",
        /** SHA-256 of the signing certificate every extension in this repo is signed with. */
        val signingKeyFingerprint: String = "",
    )
}

/**
 * The v2 index. Unlike `index.min.json` this carries an absolute [Resources.jarUrl] — a prebuilt jar
 * that needs no DEX translation — plus the repo's signing key and each extension's lib version.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ExtensionStoreV2(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val badgeLabel: String = "",
    @ProtoNumber(3) val signingKey: String = "",
    @ProtoNumber(4) val contact: Contact? = null,
    @ProtoNumber(101) val extensionList: ExtensionList? = null,
    /** Set when the extension list lives in a separate file rather than inline. */
    @ProtoNumber(102) val extensionListUrl: String? = null,
) {
    @Serializable
    data class Contact(
        @ProtoNumber(1) val website: String = "",
        @ProtoNumber(2) val discord: String? = null,
    )

    @Serializable
    data class ExtensionList(
        @ProtoNumber(1) val extensions: List<Extension> = emptyList(),
    )

    @Serializable
    data class Extension(
        @ProtoNumber(1) val name: String = "",
        @ProtoNumber(2) val packageName: String = "",
        @ProtoNumber(3) val resources: Resources = Resources(),
        /** extensions-lib version this was built against, e.g. "1.6". */
        @ProtoNumber(4) val extensionLib: String = "",
        @ProtoNumber(5) val versionCode: Long = 0,
        @ProtoNumber(6) val versionName: String = "",
        @ProtoNumber(7) val contentWarning: Int = 0,
        @ProtoNumber(8) val sources: List<Source> = emptyList(),
    )

    @Serializable
    data class Resources(
        @ProtoNumber(1) val apkUrl: String = "",
        @ProtoNumber(2) val iconUrl: String = "",
        /** Keiyoushi-specific: a prebuilt jar, so the APK never has to be translated. */
        @ProtoNumber(501) val jarUrl: String? = null,
    )

    @Serializable
    data class Source(
        @ProtoNumber(1) val id: Long = 0,
        @ProtoNumber(2) val name: String = "",
        @ProtoNumber(3) val language: String = "",
        @ProtoNumber(4) val homeUrl: String = "",
        @ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
        /** A notice from the repo, e.g. that the site is down. */
        @ProtoNumber(7) val message: String? = null,
    )

    companion object {
        // contentWarning enum: 0 unspecified, 1 safe, 2 mixed, 3 nsfw
        const val WARNING_MIXED = 2
        const val WARNING_NSFW = 3
    }
}

/** Flatten a v2 extension into the entry shape the rest of the installer already works with. */
fun ExtensionStoreV2.Extension.toRepoEntry(): ExtensionRepoEntry =
    ExtensionRepoEntry(
        name = name,
        pkg = packageName,
        // apk/jar are absolute here, so the *Url() helpers pass them through untouched.
        apk = resources.apkUrl,
        jar = resources.jarUrl,
        lang = sources.map { it.language }.distinct().singleOrNull() ?: "all",
        code = versionCode.toInt(),
        version = versionName,
        nsfw = if (contentWarning == ExtensionStoreV2.WARNING_NSFW || contentWarning == ExtensionStoreV2.WARNING_MIXED) 1 else 0,
        extensionLib = extensionLib,
        sources =
            sources.map {
                RepoSource(name = it.name, lang = it.language, id = it.id.toString(), baseUrl = it.homeUrl)
            },
    )
