/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.core.convert

/** Detects a sensible file extension for an image from its magic bytes. */
object ImageFormat {
    fun extensionFor(bytes: ByteArray): String {
        if (bytes.size < 12) return "jpg"
        fun b(i: Int) = bytes[i].toInt() and 0xFF
        return when {
            b(0) == 0xFF && b(1) == 0xD8 -> "jpg"
            b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47 -> "png"
            b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46 -> "gif"
            // RIFF....WEBP
            b(0) == 0x52 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x46 &&
                b(8) == 0x57 && b(9) == 0x45 && b(10) == 0x42 && b(11) == 0x50 -> "webp"
            // ftyp....avif / heic
            b(4) == 0x66 && b(5) == 0x74 && b(6) == 0x79 && b(7) == 0x70 -> "avif"
            else -> "jpg"
        }
    }

    /**
     * Sanity check that downloaded bytes are an image, not an error page. Validates the magic bytes
     * so a *valid but tiny* image (some CDNs serve ~80-byte spacer/near-blank WEBP pages) is kept,
     * while an HTML/JSON error blob is rejected. A large unknown body is accepted as a fallback.
     */
    fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        fun b(i: Int) = bytes[i].toInt() and 0xFF
        return when {
            b(0) == 0xFF && b(1) == 0xD8 -> true // jpeg
            b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47 -> true // png
            b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46 -> true // gif
            b(0) == 0x52 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x46 &&
                b(8) == 0x57 && b(9) == 0x45 && b(10) == 0x42 && b(11) == 0x50 -> true // RIFF…WEBP
            b(4) == 0x66 && b(5) == 0x74 && b(6) == 0x79 && b(7) == 0x70 -> true // ftyp… avif/heic
            bytes.size >= 1024 -> true // unknown format but substantial — accept rather than fail
            else -> false
        }
    }
}
