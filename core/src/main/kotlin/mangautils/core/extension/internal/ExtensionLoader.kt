/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Adapted from Suwayomi-Server's PackageTools (MPL-2.0).
 */
package mangautils.core.extension.internal

import android.content.pm.PackageInfo
import android.os.Bundle
import com.googlecode.d2j.dex.Dex2jar
import com.googlecode.d2j.reader.MultiDexFileReader
import com.googlecode.dex2jar.tools.BaksmaliBaseDexExceptionHandler
import net.dongliu.apk.parser.ApkFile
import net.dongliu.apk.parser.ApkParsers
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import xyz.nulldev.androidcompat.pm.InstalledPackage.Companion.toList
import xyz.nulldev.androidcompat.pm.toPackageInfo
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.Path

/** Low-level helpers to turn an extension APK into runnable Source classes on the JVM. */
object ExtensionLoader {
    private val log = LoggerFactory.getLogger(javaClass)

    const val EXTENSION_FEATURE = "tachiyomi.extension"
    const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
    const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
    const val METADATA_NSFW = "tachiyomi.extension.nsfw"
    const val LIB_VERSION_MIN = 1.3
    const val LIB_VERSION_MAX = 1.5

    /** Translate the DEX bytecode inside [apkOrDexFile] into a standard JVM jar at [jarFile]. */
    fun dex2jar(
        apkOrDexFile: String,
        jarFile: String,
    ) {
        val jarFilePath = File(jarFile).toPath()
        val reader = MultiDexFileReader.open(File(apkOrDexFile).readBytes())
        val handler = BaksmaliBaseDexExceptionHandler()
        Dex2jar
            .from(reader)
            .withExceptionHandler(handler)
            .reUseReg(false)
            .topoLogicalSort()
            .skipDebug(true)
            .optimizeSynchronized(false)
            .printIR(false)
            .noCode(false)
            .skipExceptions(false)
            .dontSanitizeNames(true)
            .to(jarFilePath)
        if (handler.hasException()) {
            val errorFile = jarFilePath.resolveSibling("${jarFilePath.fileName}-error.txt")
            log.error("dex2jar reported issues; details written to {}", errorFile)
            handler.dump(errorFile, emptyArray())
        } else {
            BytecodeEditor.fixAndroidClasses(jarFilePath)
        }
    }

    /** Read the APK manifest into a [PackageInfo] whose applicationInfo.metaData has the tags. */
    fun getPackageInfo(apkFilePath: String): PackageInfo {
        val apk = File(apkFilePath)
        return ApkParsers.getMetaInfo(apk).toPackageInfo(apk).apply {
            val parsed = ApkFile(apk)
            val doc =
                parsed.manifestXml.byteInputStream().use {
                    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
                }
            applicationInfo.metaData =
                Bundle().apply {
                    val appTag = doc.getElementsByTagName("application").item(0)
                    appTag
                        ?.childNodes
                        ?.toList()
                        .orEmpty()
                        .asSequence()
                        .filter { it.nodeType == Node.ELEMENT_NODE }
                        .map { it as Element }
                        .filter { it.tagName == "meta-data" }
                        .forEach {
                            putString(
                                it.attributes.getNamedItem("android:name").nodeValue,
                                it.attributes.getNamedItem("android:value").nodeValue,
                            )
                        }
                }
        }
    }

    /**
     * Copies the APK's `assets/` entries into the translated jar (at their `assets/...` path), so
     * extensions that read bundled files via the classpath (e.g. MangaDex's language list) work.
     * Mirrors Suwayomi's extractAssetsFromApk. No-op if the APK has no assets.
     */
    fun mergeApkAssetsIntoJar(
        apkPath: String,
        jarPath: String,
    ) {
        val assets = LinkedHashMap<String, ByteArray>()
        ZipInputStream(File(apkPath).inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.startsWith("assets/")) {
                    assets[entry.name] = zin.readBytes()
                }
                entry = zin.nextEntry
            }
        }
        if (assets.isEmpty()) return

        val jar = File(jarPath)
        val tmp = File("$jarPath.tmp")
        val seen = HashSet<String>()
        ZipInputStream(jar.inputStream().buffered()).use { jin ->
            ZipOutputStream(tmp.outputStream().buffered()).use { jout ->
                var entry = jin.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!name.startsWith("META-INF/") && name !in assets && seen.add(name)) {
                        jout.putNextEntry(ZipEntry(name))
                        jin.copyTo(jout)
                        jout.closeEntry()
                    }
                    entry = jin.nextEntry
                }
                assets.forEach { (name, bytes) ->
                    if (seen.add(name)) {
                        jout.putNextEntry(ZipEntry(name))
                        jout.write(bytes)
                        jout.closeEntry()
                    }
                }
            }
        }
        Files.move(tmp.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING)
        log.debug("Merged {} asset(s) into {}", assets.size, jarPath)
    }

    private val jarLoaderMap = mutableMapOf<String, URLClassLoader>()

    /** Instantiate the extension entry [className] from [jarPath] (a Source or SourceFactory). */
    fun loadExtensionInstance(
        jarPath: String,
        className: String,
    ): Any {
        log.debug("Loading class {} from {}", className, jarPath)
        val classLoader =
            jarLoaderMap.getOrPut(jarPath) {
                ChildFirstURLClassLoader(arrayOf<URL>(Path(jarPath).toUri().toURL()))
            }
        val classToLoad = Class.forName(className, false, classLoader)
        return classToLoad.getDeclaredConstructor().newInstance()
    }
}
