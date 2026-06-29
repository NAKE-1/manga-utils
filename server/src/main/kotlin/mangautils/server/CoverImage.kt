/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.server

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Server-side cover thumbnailing: covers come from sources at ~300-470 KB; the grid shows them tiny. */
object CoverImage {
    /** Resize [src] to at most [maxW] wide and re-encode as JPEG. Returns the original on any failure. */
    fun thumbnail(src: ByteArray, maxW: Int = 360, quality: Float = 0.82f): ByteArray {
        val img = runCatching { ImageIO.read(ByteArrayInputStream(src)) }.getOrNull() ?: return src
        if (img.width <= maxW) return src // already small — original is likely smaller than a re-encode
        val h = (img.height.toLong() * maxW / img.width).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(maxW, h, BufferedImage.TYPE_INT_RGB)
        scaled.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            color = Color.WHITE
            fillRect(0, 0, maxW, h)
            drawImage(img, 0, 0, maxW, h, null)
            dispose()
        }
        return runCatching { encodeJpeg(scaled, quality) }.getOrDefault(src)
    }

    private fun encodeJpeg(img: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            val param = writer.defaultWriteParam
            if (param.canWriteCompressed()) {
                param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                param.compressionQuality = quality
            }
            writer.write(null, IIOImage(img, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }
}
