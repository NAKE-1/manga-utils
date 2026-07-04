package xyz.nulldev.androidcompat.webkit

import com.jetbrains.cef.JCefAppConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.cef.CefApp
import org.cef.CefSettings.LogSeverity
import org.cef.SystemBootstrap
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSameFileAs
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.outputStream
import kotlin.io.path.readLines
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText

/**
 * Brings up the native JetBrains JCEF (Chromium) runtime the first time a source needs a WebView.
 * Ported/adapted from Suwayomi-Server's CEFManager: downloads the platform's jcef native from the
 * JetBrains Runtime GitHub release, extracts it, then initializes CefApp via JCefAppConfig (which
 * computes the correct loader / jcef_helper / resource paths). Unlike KCEF, the JB jcef jar and its
 * native match, so the WebView resource handler (open/read + CefResourceReadCallback) works.
 */
@OptIn(ExperimentalPathApi::class)
object CefManager {
    private val logger = KotlinLogging.logger {}
    private val started = AtomicBoolean(false)

    // Keep these in lockstep. JCEF_VERSION must match the "release" file shipped in JBR_RELEASE.
    private const val JCEF_VERSION = "144.0.15-g72717cf-chromium-144.0.7559.172-api-1.21-262-b37"
    private const val JBR_RELEASE = "jbr-release-25.0.3b508.4"

    private val dataRoot: Path by lazy {
        Path(System.getProperty("suwayomi.tachidesk.config.server.rootDir") ?: System.getProperty("user.home"))
    }
    private val cefDir: Path by lazy { dataRoot / "bin" / "kcef" }
    private val cacheDir: Path by lazy { dataRoot / "cache" / "kcef" }
    private val releaseFile: Path by lazy { cefDir / "release" }

    private val json = Json { ignoreUnknownKeys = true }

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        Thread {
            runCatching { initBlocking() }
                .onFailure {
                    logger.error(it) { "Failed to set up CEF" }
                    CefHelper.cefApp.value = Result.failure(it)
                }
        }.apply { isDaemon = true; name = "cef-bootstrap" }.start()
    }

    private fun initBlocking() {
        System.loadLibrary("jawt")

        if (!isInstallationValid(releaseFile)) {
            logger.info { "Downloading CEF native from JetBrains Runtime ($JBR_RELEASE) — first run, ~100MB…" }
            downloadRelease(cefDir)
            if (!isInstallationValid(releaseFile)) {
                throw CefHelper.CefException("Failed to provide a valid CEF installation")
            }
            logger.info { "Downloaded CEF successfully!" }
        }

        val app =
            if (CefApp.getInstanceIfAny() == null) {
                val config =
                    JCefAppConfig.getInstance(cefDir.toString(), false).apply {
                        appArgsAsList.addAll(
                            listOf(
                                "--disable-gpu",
                                "--off-screen-rendering-enabled",
                                "--disable-dev-shm-usage",
                                "--change-stack-guard-on-fork=disable",
                            ),
                        )
                        cefSettings.apply {
                            windowless_rendering_enabled = true
                            cache_path = cacheDir.absolutePathString()
                            log_severity = LogSeverity.LOGSEVERITY_DEFAULT
                        }
                    }

                // Load the native libs in dependency order (mirrors JetBrains/jcef CefApp bootstrap),
                // because JCEF gives no way to observe an init failure otherwise.
                val os = Platform.current.os
                when {
                    os.isLinux -> config.loader.loadLibrary("cef")
                    os.isWindows -> {
                        config.loader.loadLibrary("chrome_elf")
                        config.loader.loadLibrary("libcef")
                    }
                    else -> {}
                }
                config.loader.loadLibrary("jcef")

                CefApp.setIsRemoteEnabled(config.isRemoteEnabled)
                SystemBootstrap.setLoader(config.loader)
                CefApp.startup(config.appArgs)
                CefApp.getInstance(config.appArgs, config.cefSettings, config.serverExe)
            } else {
                CefApp.getInstance()
            }

        app.onInitialization {
            if (it == CefApp.CefAppState.INITIALIZED) {
                CefHelper.cefApp.value = Result.success(app)
                CefHelper.isInitialized = true
                logger.info { "CEF runtime ready — WebView-based sources are now usable" }
            }
        }
        // If already initialized (e.g. reused instance), onInitialization may not fire again.
        CefHelper.cefApp.value = Result.success(app)

        Runtime.getRuntime().addShutdownHook(
            Thread {
                runCatching { app.dispose() }
            },
        )
    }

    private fun isInstallationValid(releaseFile: Path): Boolean {
        if (!releaseFile.exists() || !releaseFile.isRegularFile()) return false
        return try {
            releaseFile
                .readLines()
                .firstNotNullOfOrNull {
                    if (it.contains("JCEF_VERSION_DETAILED")) it.split("=").getOrNull(1) else null
                }?.let { JCEF_VERSION.split("-chromium")[0] == it.split("-chromium")[0] } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun downloadRelease(installDir: Path) {
        installDir.deleteRecursively()
        installDir.createDirectories()

        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
        val releaseReq =
            HttpRequest
                .newBuilder(URI.create("https://api.github.com/repos/JetBrains/JetBrainsRuntime/releases/tags/$JBR_RELEASE"))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "manga-utils")
                .build()
        val releaseResp = client.send(releaseReq, HttpResponse.BodyHandlers.ofString())
        if (releaseResp.statusCode() !in 200..299) throw IOException("GitHub release lookup failed: ${releaseResp.statusCode()}")
        val downloadUrl = GithubReleaseTransform.transform(releaseResp.body())

        val tmp = createTempDirectory("cef")
        try {
            val downFile = tmp / "download.tar.gz"
            logger.info { "Downloading CEF native from $downloadUrl" }
            val dlReq = HttpRequest.newBuilder(URI.create(downloadUrl)).header("User-Agent", "manga-utils").build()
            val dlResp = client.send(dlReq, HttpResponse.BodyHandlers.ofInputStream())
            if (dlResp.statusCode() !in 200..299) throw IOException("CEF native download failed: ${dlResp.statusCode()}")
            val contentLength = dlResp.headers().firstValueAsLong("content-length").orElse(-1L)
            downFile.outputStream().use { out ->
                dlResp.body().use { input ->
                    val buf = ByteArray(1 shl 16)
                    var read = input.read(buf)
                    var total = 0L
                    var lastPercent = -1L
                    while (read >= 0) {
                        out.write(buf, 0, read)
                        total += read
                        if (contentLength > 0) {
                            val pct = total * 100 / contentLength
                            if (pct != lastPercent) {
                                logger.info { "CEF download: $pct%" }
                                lastPercent = pct
                            }
                        }
                        read = input.read(buf)
                    }
                }
            }
            logger.info { "Extracting CEF native…" }
            TarGzExtractor.extract(installDir, downFile)
            TarGzExtractor.move(installDir)
        } finally {
            tmp.deleteRecursively()
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Below adapted from Suwayomi-Server CEFManager (itself based on KCEF's GithubReleaseTransform /
    // TarGzExtractor). Picks the platform's jcef tar.gz from the JBR GitHub release and lays it out.
    // -----------------------------------------------------------------------------------------------

    private object GithubReleaseTransform {
        private val urlRegex = "(https?://|www.)[-a-zA-Z0-9+&@#/%?=~_|!:.;]*[-a-zA-Z0-9+&@#/%=~_|]".toRegex()

        fun transform(bodyJson: String): String {
            val release = json.decodeFromString<GitHubRelease>(bodyJson)

            val fromBody =
                urlRegex
                    .findAll(release.body)
                    .map { it.value }
                    .filterNot { it.isBlank() || it.endsWith(".checksum", true) }
                    .filter { it.contains("jcef", true) }
                    .toList()

            val platform = Platform.current
            val byOs =
                fromBody
                    .filter { url -> platform.os.values.any { url.contains(it, true) } }
                    .ifEmpty {
                        release.assets
                            .filter { asset ->
                                platform.os.values.any { asset.name.contains(it, true) || asset.downloadUrl.contains(it, true) } &&
                                    asset.downloadUrl.isNotBlank()
                            }.filter { asset ->
                                platform.arch.values.any { asset.name.contains(it, true) || asset.downloadUrl.contains(it, true) } &&
                                    asset.downloadUrl.isNotBlank()
                            }.map { it.downloadUrl }
                    }
            val byArch = byOs.filter { url -> platform.arch.values.any { url.contains(it, true) } }

            if (byArch.isEmpty()) throw CefHelper.CefException("Platform not supported by CEF (${platform.os.name},${platform.arch.name})")

            return byArch
                .sortedWith(
                    compareBy<String> { if (it.contains("sdk", true)) 1 else 0 }
                        .thenBy { if (it.endsWith(".tar.gz", true)) 0 else 1 },
                ).first()
        }

        @Serializable
        private data class GitHubRelease(
            val body: String = "",
            val assets: List<Asset> = emptyList(),
        ) {
            @Serializable
            data class Asset(
                val name: String = "",
                @SerialName("browser_download_url") val downloadUrl: String = "",
            )
        }
    }

    private object TarGzExtractor {
        private fun Path.validate(parent: Path): Boolean = runCatching { normalize().startsWith(parent) }.getOrNull() ?: false

        private fun Path.isSymlink(): Boolean =
            runCatching { isSymbolicLink() }.getOrNull()
                ?: runCatching { !isRegularFile(LinkOption.NOFOLLOW_LINKS) }.getOrNull() ?: false

        private fun Path.getRealFile(): Path = if (isSymlink()) runCatching { readSymbolicLink() }.getOrNull() ?: this else this

        private fun Path.isSame(file: Path?): Boolean {
            var src = getRealFile(); if (!src.exists()) src = this
            var tgt = file?.getRealFile() ?: file; if (tgt?.exists() == false) tgt = file
            return if (tgt == null) {
                false
            } else {
                this == tgt || runCatching { src.absolute() == tgt.absolute() || src.isSameFileAs(tgt) }.getOrNull() ?: false
            }
        }

        fun extract(installDir: Path, downloadedFile: Path) {
            downloadedFile.inputStream().use { fis ->
                GzipCompressorInputStream(fis).use { gz ->
                    TarArchiveInputStream(gz).use { tar ->
                        while (tar.nextEntry != null) {
                            val entry = tar.currentEntry ?: continue
                            val file = installDir / entry.name
                            if (!file.validate(installDir)) throw CefHelper.CefException("bad archive entry")
                            if (entry.isDirectory) {
                                file.createDirectories()
                            } else {
                                file.parent?.createDirectories()
                                BufferedOutputStream(file.outputStream(StandardOpenOption.CREATE, StandardOpenOption.WRITE)).use { dest ->
                                    tar.copyTo(dest)
                                }
                            }
                            runCatching {
                                file.setPosixFilePermissions(
                                    file.getPosixFilePermissions() +
                                        setOf(
                                            PosixFilePermission.OWNER_EXECUTE,
                                            PosixFilePermission.GROUP_EXECUTE,
                                            PosixFilePermission.OTHERS_EXECUTE,
                                        ),
                                )
                            }
                        }
                    }
                }
            }
            downloadedFile.deleteExisting()
        }

        fun move(installDir: Path) {
            val releaseFile =
                Files.walk(installDir).use { s ->
                    s.filter(Files::isRegularFile).filter { it.fileName?.toString() == "release" }.findFirst().orElse(null)
                } ?: (installDir / "release")
            val releaseContents = if (releaseFile.exists()) releaseFile.readText(Charsets.UTF_8) else ""

            when {
                Platform.current.os.isWindows -> winMove(installDir)
                Platform.current.os.isMacOSX -> macMove(installDir)
                else -> linuxMove(installDir)
            }

            (installDir / "release").writeText(releaseContents)
        }

        private fun linuxMove(installDir: Path) {
            var foundDir: Path? = null
            var foundParent: Path? = null
            installDir.listDirectoryEntries().forEach { parent ->
                if ((parent / "lib").exists()) { foundDir = parent / "lib"; foundParent = parent }
            }
            foundDir?.let {
                val target = it.moveTo(installDir / "lib")
                foundParent?.let { p -> p.deleteRecursively(); p.deleteIfExists() }
                installDir.listDirectoryEntries().forEach { d -> if (!d.isSame(target)) d.deleteRecursively() }
                target.listDirectoryEntries().forEach { m -> m.moveTo(installDir / m.fileName) }
                target.deleteExisting()
            }
        }

        private fun macMove(installDir: Path) {
            var foundDir: Path? = null
            var foundParent: Path? = null
            installDir.listDirectoryEntries().forEach { parent ->
                if ((parent / "Contents").exists()) { foundDir = parent / "Contents"; foundParent = parent }
            }
            val target = (installDir / "lib").also { it.createDirectories() }
            foundDir?.let { contents ->
                (contents / "Home" / "lib").listDirectoryEntries().forEach { m -> m.moveTo(target / m.fileName) }
                (contents / "Frameworks").moveTo(target / "Frameworks")
                foundParent?.let { p -> p.deleteRecursively(); p.deleteIfExists() }
                installDir.listDirectoryEntries().forEach { d -> if (!d.isSame(target)) d.deleteRecursively() }
                target.listDirectoryEntries().forEach { m -> m.moveTo(installDir / m.fileName) }
                target.deleteExisting()
            }
        }

        private fun winMove(installDir: Path) {
            var foundDir: Path? = null
            installDir.listDirectoryEntries().forEach { parent -> if ((parent / "lib").exists()) foundDir = parent }
            foundDir?.let {
                val target = (it / "lib").moveTo(installDir / "lib")
                (it / "bin").listDirectoryEntries().forEach { m -> m.moveTo(target / m.fileName) }
                installDir.listDirectoryEntries().forEach { d -> if (!d.isSame(target)) d.deleteRecursively() }
                target.listDirectoryEntries().forEach { m -> m.moveTo(installDir / m.fileName) }
                target.deleteExisting()
            }
        }
    }
}
