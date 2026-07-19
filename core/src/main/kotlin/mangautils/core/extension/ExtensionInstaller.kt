/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.extension

import mangautils.core.config.AppConfig
import mangautils.core.extension.internal.ExtensionLoader
import mangautils.core.extension.internal.ExtensionSignature
import mangautils.core.extension.internal.ExtensionVerifier
import mangautils.core.runtime.ExtensionRuntime
import mangautils.core.source.SourceManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.io.path.createParentDirectories
import kotlin.io.path.outputStream

/**
 * Installs an extension by package name: finds it in the added repositories, downloads the
 * APK, translates it to a JVM jar, instantiates its sources to enumerate them, and records
 * the result in [InstalledStore].
 */
class ExtensionInstaller(
    private val repoClient: ExtensionRepoClient = ExtensionRepoClient(),
    private val http: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Located(val entry: ExtensionRepoEntry, val indexUrl: String)

    /** Find an extension entry by package across all added repositories. */
    fun locate(pkg: String): Located? {
        for (repo in RepoStore.list()) {
            val entry =
                runCatching { repoClient.fetchIndex(repo) }
                    .getOrElse {
                        log.warn("Failed to fetch {}: {}", repo, it.message)
                        emptyList()
                    }.firstOrNull { it.pkg == pkg }
            if (entry != null) return Located(entry, repo)
        }
        return null
    }

    fun install(pkg: String): InstalledExtension {
        val located = locate(pkg) ?: error("Extension '$pkg' not found in any added repository")
        return install(located.entry, located.indexUrl)
    }

    fun install(
        entry: ExtensionRepoEntry,
        indexUrl: String,
    ): InstalledExtension {
        ExtensionRuntime.ensureStarted()
        AppConfig.ensureLayout()

        // A v2 index states which extensions-lib an extension was built against. Rejecting an
        // unsupported one here gives a clear reason, instead of it installing fine and then dying on
        // a missing method the first time you use it — which is how ext-lib 1.6 first showed up.
        entry.extensionLib.toDoubleOrNull()?.let { lib ->
            check(lib <= ExtensionLoader.LIB_VERSION_MAX) {
                "${entry.name} needs extensions-lib $lib, but this build supports up to " +
                    "${ExtensionLoader.LIB_VERSION_MAX}. Update manga-utils."
            }
            if (lib < ExtensionLoader.LIB_VERSION_MIN) {
                log.warn("{} targets extensions-lib {} (older than {})", entry.pkg, lib, ExtensionLoader.LIB_VERSION_MIN)
            }
        }

        val apkPath = AppConfig.extensionsDir.resolve("${entry.pkg}.apk")
        val jarPath = AppConfig.extensionsDir.resolve("${entry.pkg}.jar")

        // Release any loaded class loader for this jar first, or Windows keeps the old .jar locked and
        // the overwrite fails (update case).
        ExtensionLoader.releaseJar(jarPath.toString())
        val before = stampOf(jarPath)

        // Published by a v2 index; null for a legacy repo, which simply can't be checked.
        val signingKey = runCatching { repoClient.signingKey(indexUrl) }.getOrNull()

        // Prefer the repo's prebuilt jar. It needs no DEX translation, which sidesteps a dex2jar bug
        // that turns DEX 038 (minSdk >= 26) builds into bytecode the JVM verifier rejects.
        val pkg =
            fetchPrebuiltJar(entry, indexUrl, jarPath.toString(), signingKey)
                ?: translateApk(entry, indexUrl, apkPath.toString(), jarPath.toString(), signingKey)

        // An "update" that silently left the old jar in place is the worst outcome, because the
        // version bump gets recorded and the extension keeps running the code it always did. One
        // extension really did sit on a three-week-old jar this way. Refuse to record that.
        check(stampOf(jarPath) != before) {
            "The extension file was not replaced — ${jarPath.fileName} is unchanged, so the update did not apply."
        }

        val classMeta =
            pkg.sourceClass
                ?: error("No ${ExtensionLoader.METADATA_SOURCE_CLASS} metadata; not a Tachiyomi extension?")
        val classNames = classMeta.split(";").map { resolveClassName(pkg.packageName, it.trim()) }
        val nsfw = pkg.nsfwMeta || entry.isNsfw

        // Check the JVM accepts the code before we record the extension as installed. A failure here
        // used to surface much later as an opaque error from whatever first touched the source.
        val report = runCatching { ExtensionVerifier.verify(jarPath.toString()) }.getOrNull()
        if (report != null) {
            ExtensionVerifier.log(report)
            // Fatal: an entry class that won't load (nothing can start), or malformed bytecode
            // anywhere (a healthy jar never has any). A missing class elsewhere is tolerated — it's
            // usually an optional dependency the extension only touches on some code paths.
            val broken =
                report.failed.filter {
                    it.name in classNames || it.kind == ExtensionVerifier.KIND_VERIFY || it.kind == ExtensionVerifier.KIND_FORMAT
                }
            check(broken.isEmpty()) {
                val f = broken.first()
                "${entry.name} can't run on this build: ${f.name} failed to load (${f.kind}) — ${f.error}"
            }
        }

        // instantiate to enumerate sources
        val sources =
            classNames.flatMap { cn ->
                val instance = ExtensionLoader.loadExtensionInstance(jarPath.toString(), cn)
                SourceManager.expand(instance)
            }.map { src ->
                InstalledSource(id = src.id, name = src.name, lang = SourceManager.langOf(src))
            }

        val installed =
            InstalledExtension(
                pkg = entry.pkg,
                name = entry.name,
                versionName = pkg.versionName ?: entry.version,
                versionCode = pkg.versionCode.takeIf { it > 0 } ?: entry.code.toLong(),
                lang = entry.lang,
                nsfw = nsfw,
                jarPath = jarPath.toString(),
                classNames = classNames,
                sources = sources,
            )
        InstalledStore.upsert(installed)
        log.info("Installed {} ({} source(s))", entry.pkg, sources.size)
        return installed
    }

    /** Identity of the jar on disk, so we can tell whether an install actually replaced it. */
    private fun stampOf(path: java.nio.file.Path): Pair<Long, Long>? =
        runCatching {
            java.nio.file.Files.getLastModifiedTime(path).toMillis() to java.nio.file.Files.size(path)
        }.getOrNull()

    /** The bits of an extension's manifest we need, however we obtained the jar. */
    private data class Pkg(
        val packageName: String,
        val versionName: String?,
        val versionCode: Long,
        val sourceClass: String?,
        val nsfwMeta: Boolean,
    )

    /**
     * Download the repo's prebuilt jar straight into place, or return null if it doesn't publish one
     * (404, or the file isn't a jar with a bundled manifest) so the caller falls back to the APK.
     */
    private fun fetchPrebuiltJar(
        entry: ExtensionRepoEntry,
        indexUrl: String,
        jarPath: String,
        signingKey: String?,
    ): Pkg? {
        val url = entry.jarUrl(indexUrl)
        val manifest =
            runCatching {
                download(url, jarPath)
                ExtensionLoader.readJarManifest(jarPath)
            }.getOrElse {
                log.debug("No prebuilt jar at {}: {}", url, it.message)
                null
            }
        if (manifest == null) {
            log.info("{} has no prebuilt jar in this repo; translating the APK instead", entry.pkg)
            return null
        }
        // Prove it came from the repo that advertised it before anything gets loaded. Prebuilt jars
        // use ordinary jar signing, so a missing signature here is a real problem.
        ExtensionSignature.require(jarPath, signingKey, entry.name, mustBeSigned = true)
        log.info("Installed prebuilt jar {} -> {} (no DEX translation needed)", url, jarPath)
        return Pkg(
            packageName = manifest.packageName.ifEmpty { entry.pkg },
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            sourceClass = manifest.meta[ExtensionLoader.METADATA_SOURCE_CLASS],
            nsfwMeta = manifest.meta[ExtensionLoader.METADATA_NSFW] == "1",
        )
    }

    /** The original path: download the APK, translate its DEX to a jar, fold in its assets. */
    private fun translateApk(
        entry: ExtensionRepoEntry,
        indexUrl: String,
        apkPath: String,
        jarPath: String,
        signingKey: String?,
    ): Pkg {
        val apkUrl = entry.apkUrl(indexUrl)
        log.info("Downloading {} -> {}", apkUrl, apkPath)
        download(apkUrl, apkPath)

        // Verify before translating, so we never feed dex2jar bytes we don't trust.
        ExtensionSignature.require(apkPath, signingKey, entry.name, mustBeSigned = false)

        val info = ExtensionLoader.getPackageInfo(apkPath)
        val meta = info.applicationInfo.metaData

        log.info("Translating {} -> {}", apkPath, jarPath)
        ExtensionLoader.dex2jar(apkPath, jarPath)
        ExtensionLoader.mergeApkAssetsIntoJar(apkPath, jarPath)

        return Pkg(
            packageName = info.packageName ?: entry.pkg,
            versionName = info.versionName,
            versionCode = info.versionCode.toLong(),
            sourceClass = meta?.getString(ExtensionLoader.METADATA_SOURCE_CLASS),
            nsfwMeta = meta?.getString(ExtensionLoader.METADATA_NSFW) == "1",
        )
    }

    private fun resolveClassName(
        pkg: String,
        raw: String,
    ): String = if (raw.startsWith(".")) pkg + raw else raw

    private fun download(
        url: String,
        destPath: String,
    ) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} downloading $url")
            val body = response.body ?: error("Empty body for $url")
            val dest = java.nio.file.Path.of(destPath)
            dest.createParentDirectories()
            body.byteStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
