/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.extension.internal

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URL
import java.util.zip.ZipFile

/**
 * Loads every class in a translated extension jar and reports, per class, whether the JVM accepts it.
 *
 * This exists because a broken translation used to surface only as an opaque `VerifyError` thrown from
 * `newInstance()` deep inside an install, with no indication of which class was at fault or why. Here each
 * class is loaded in isolation so one bad class can't hide the rest, and the failure kinds are kept apart:
 * a [VerifyError] means the bytecode itself is malformed, whereas a [NoClassDefFoundError] means the
 * bytecode is fine and we're simply missing a class the extension expects the host to provide.
 */
object ExtensionVerifier {
    private val log = LoggerFactory.getLogger(javaClass)

    /** How a single class fared. [error] is null when the JVM accepted it. */
    @Serializable
    data class ClassResult(
        val name: String,
        val classVersion: Int,
        val kind: String,
        val error: String? = null,
    )

    @Serializable
    data class Report(
        val jarPath: String,
        val dexVersion: String? = null,
        val total: Int,
        val verified: Int,
        val results: List<ClassResult>,
    ) {
        val failed: List<ClassResult> get() = results.filter { it.error != null }
        val ok: Boolean get() = failed.isEmpty()

        /** Distinct classes the extension referenced that we don't provide — the ext-lib gap, if any. */
        val missingClasses: List<String>
            get() =
                results
                    .filter { it.kind == KIND_MISSING }
                    .mapNotNull { it.error }
                    .distinct()
                    .sorted()

        fun summary(): String =
            "$verified/$total classes verified" +
                (dexVersion?.let { ", DEX $it" } ?: "") +
                if (ok) "" else " — ${failed.size} failed"
    }

    const val KIND_OK = "ok"
    const val KIND_VERIFY = "verify-error"
    const val KIND_MISSING = "missing-class"
    const val KIND_FORMAT = "bad-class-file"

    // A class whose <clinit> throws still *verified* fine — that's a runtime problem, not a translation
    // one, so it is not counted as a failure.
    private const val KIND_INIT = "clinit-threw"

    /**
     * Verify every class in [jarPath]. [apkPath], when given, is only read for its DEX format version,
     * which is the single most useful correlate when a whole batch of extensions starts failing at once.
     */
    fun verify(
        jarPath: String,
        apkPath: String? = null,
    ): Report {
        val classes = mutableMapOf<String, Int>() // binary name -> class file major version
        ZipFile(File(jarPath)).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") }.forEach { entry ->
                val header = zip.getInputStream(entry).use { it.readNBytes(8) }
                if (header.size == 8 && header[0] == 0xCA.toByte() && header[1] == 0xFE.toByte()) {
                    val major = ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
                    classes[entry.name.removeSuffix(".class").replace('/', '.')] = major
                }
            }
        }

        // A loader per verification run: we want a cold load, not whatever an earlier install cached.
        val loader = ChildFirstURLClassLoader(arrayOf<URL>(File(jarPath).toURI().toURL()))
        val results =
            loader.use { cl ->
                classes.entries.sortedBy { it.key }.map { (name, version) ->
                    try {
                        // initialize = true: verification is what we're testing, and the JVM defers it far
                        // enough that a load without initialization can report a broken class as fine.
                        Class.forName(name, true, cl)
                        ClassResult(name, version, KIND_OK)
                    } catch (e: VerifyError) {
                        ClassResult(name, version, KIND_VERIFY, e.message ?: "VerifyError")
                    } catch (e: NoClassDefFoundError) {
                        ClassResult(name, version, KIND_MISSING, e.message?.replace('/', '.') ?: "?")
                    } catch (e: ClassFormatError) {
                        ClassResult(name, version, KIND_FORMAT, e.message ?: "ClassFormatError")
                    } catch (e: ExceptionInInitializerError) {
                        // Verified fine; its static init blew up. Report it, don't fail the extension.
                        ClassResult(name, version, KIND_INIT, null).also {
                            log.debug("{} verified but <clinit> threw: {}", name, e.cause?.toString())
                        }
                    } catch (e: LinkageError) {
                        ClassResult(name, version, KIND_VERIFY, e.toString())
                    } catch (e: Throwable) {
                        // Anything thrown from a static initializer that isn't wrapped — same reasoning.
                        ClassResult(name, version, KIND_INIT, null).also {
                            log.debug("{} verified but threw during init: {}", name, e.toString())
                        }
                    }
                }
            }

        return Report(
            jarPath = jarPath,
            dexVersion = apkPath?.let(::dexVersionOf),
            total = results.size,
            verified = results.count { it.error == null },
            results = results,
        )
    }

    /** The DEX format version ("035", "038", …) of the first classes*.dex in an APK, or null. */
    fun dexVersionOf(apkPath: String): String? =
        runCatching {
            ZipFile(File(apkPath)).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .firstOrNull { it.name.startsWith("classes") && it.name.endsWith(".dex") }
                    ?.let { zip.getInputStream(it).use { s -> s.readNBytes(8) } }
                    ?.takeIf { it.size == 8 }
                    ?.let { String(it, 4, 3, Charsets.US_ASCII) }
            }
        }.getOrNull()

    /** Log a report at a level matching how bad it is. */
    fun log(report: Report) {
        if (report.ok) {
            log.info("verify {} — {}", File(report.jarPath).name, report.summary())
            return
        }
        log.error("verify {} — {}", File(report.jarPath).name, report.summary())
        report.failed.take(20).forEach { log.error("  {} [{}] {}", it.name, it.kind, it.error) }
        if (report.missingClasses.isNotEmpty()) {
            log.error("  missing from host: {}", report.missingClasses.joinToString())
        }
    }
}
