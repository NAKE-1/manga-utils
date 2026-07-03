/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.extension

import mangautils.core.config.AppConfig
import mangautils.core.extension.internal.ExtensionLoader
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

        val apkPath = AppConfig.extensionsDir.resolve("${entry.pkg}.apk")
        val jarPath = AppConfig.extensionsDir.resolve("${entry.pkg}.jar")

        // 1. download the APK
        val apkUrl = entry.apkUrl(indexUrl)
        log.info("Downloading {} -> {}", apkUrl, apkPath)
        download(apkUrl, apkPath.toString())

        // 2. read manifest metadata to find the entry class(es)
        val packageInfo = ExtensionLoader.getPackageInfo(apkPath.toString())
        val metaData = packageInfo.applicationInfo.metaData
        val pkgName = packageInfo.packageName ?: entry.pkg
        val classMeta =
            metaData?.getString(ExtensionLoader.METADATA_SOURCE_CLASS)
                ?: error("APK has no ${ExtensionLoader.METADATA_SOURCE_CLASS} metadata; not a Tachiyomi extension?")
        val classNames = classMeta.split(";").map { resolveClassName(pkgName, it.trim()) }
        val nsfw = metaData.getString(ExtensionLoader.METADATA_NSFW) == "1" || entry.isNsfw

        // 3. translate DEX -> jar, then fold in the APK's bundled assets. Release any loaded class loader
        // for this jar first, or Windows keeps the old .jar locked and the overwrite fails (update case).
        log.info("Translating {} -> {}", apkPath, jarPath)
        ExtensionLoader.releaseJar(jarPath.toString())
        ExtensionLoader.dex2jar(apkPath.toString(), jarPath.toString())
        ExtensionLoader.mergeApkAssetsIntoJar(apkPath.toString(), jarPath.toString())

        // 4. instantiate to enumerate sources
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
                versionName = packageInfo.versionName ?: entry.version,
                versionCode = packageInfo.versionCode.toLong().takeIf { it > 0 } ?: entry.code.toLong(),
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
