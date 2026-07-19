/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.extension.internal

import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.util.jar.JarFile

/**
 * Checks that a downloaded extension really was signed by the repository that advertised it.
 *
 * Until now an extension went from an HTTPS download straight into a class loader whose constructor
 * we then invoke, so anything able to serve us a modified index or file got arbitrary code execution.
 * A repo's v2 index publishes the SHA-256 of the certificate every one of its extensions is signed
 * with, which is what makes this checkable at all.
 */
object ExtensionSignature {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Verify every entry's signature and return the SHA-256 of the signing certificate, lowercase
     * hex. Null when the file isn't signed. Throws [SecurityException] if a signature doesn't match
     * its content — i.e. the file was altered after signing.
     */
    fun fingerprintOf(path: String): String? {
        JarFile(File(path), true).use { jar ->
            var certBytes: ByteArray? = null
            jar
                .entries()
                .asSequence()
                .filter { !it.isDirectory && !it.name.startsWith("META-INF/") }
                .forEach { entry ->
                    // Certificates are only populated once an entry has been read to the end, and
                    // reading is also what makes the JVM check that entry's signature.
                    jar.getInputStream(entry).use { it.readBytes() }
                    if (certBytes == null) certBytes = entry.certificates?.firstOrNull()?.encoded
                }
            val bytes = certBytes ?: return null
            return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * Enforce that [path] is signed by [expected]. A repo with no published key can't be checked, so
     * that case passes — refusing every legacy repo would break installs to no benefit.
     *
     * A signature that is present and wrong is always fatal. A *missing* signature is only fatal when
     * [mustBeSigned]: APKs are increasingly signed with APK Signature Scheme v2/v3 only, which the
     * JDK's jar verifier cannot read, so an unsigned-looking APK means "can't tell", not "forged".
     * Prebuilt jars use ordinary jar signing, so there it really does mean unsigned.
     */
    fun require(
        path: String,
        expected: String?,
        label: String,
        mustBeSigned: Boolean,
    ) {
        val want = expected?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        if (want == null) {
            log.debug("{}: repo publishes no signing key, skipping signature check", label)
            return
        }
        val got =
            try {
                fingerprintOf(path)
            } catch (e: SecurityException) {
                error("$label failed its signature check — the file was modified after signing. Not installing.")
            }
        if (got == null) {
            check(!mustBeSigned) { "$label is unsigned, but its repository requires a signature. Not installing." }
            // ponytail: needs apksig to read v2/v3 signatures; until then this path is unverified.
            log.warn("{}: no readable v1 signature (likely APK Scheme v2/v3) — could not verify", label)
            return
        }
        check(got == want) {
            "$label is signed by an unexpected key. Expected ${want.take(16)}…, got ${got.take(16)}…. Not installing."
        }
        log.info("{}: signature verified against the repository key", label)
    }
}
