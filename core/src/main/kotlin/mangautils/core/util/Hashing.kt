/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.util

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * One place for the content hash used to verify file transfers between the main server and worker nodes.
 *
 * It's SHA-256 (JDK built-in, hardware-accelerated on both our boxes - ARMv8 crypto on the RK3588 Pi,
 * SHA-NI on the x86 main - so it isn't the bottleneck; the network transfer is). We only need to catch
 * accidental corruption/truncation over the tailnet, not defend against a forged collision, so this is
 * deliberately swappable: if hashing ever shows up in a profile, point [hash] at XXH3-128 (one function
 * change) without touching any caller.
 */
object Hashing {
    fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun hashFile(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
