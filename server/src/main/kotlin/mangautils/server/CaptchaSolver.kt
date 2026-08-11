package mangautils.server

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.FloatBuffer
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * In-JVM port of the MangaFire shape-captcha detector (best.onnx), following docs/captcha-model/MODEL_DEPLOY.md
 * (and onnx_infer.py) EXACTLY — letterbox 640 / gray 114 / RGB / ÷255, decode output0 [1,17,8400], conf≥0.25,
 * class-agnostic NMS IoU 0.45. Then the §7 A→B match: A left-to-right = the click order; for each A shape,
 * consume an unused B instance of the same class → its box centre is the click point; unmatched = MISSING.
 */
object CaptchaSolver {
    private const val SIZE = 640
    private const val CONF = 0.25f
    private const val IOU = 0.45f
    private const val PAD = 114

    data class Det(val name: String, val conf: Float, val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
        val cx get() = (x0 + x1) / 2f
        val cy get() = (y0 + y1) / 2f
    }
    data class Solution(val aOrder: List<Det>, val bDets: List<Det>, val clicks: List<Det>, val missing: List<String>) {
        val solved get() = missing.isEmpty()
    }

    private val names: List<String> by lazy {
        val txt = javaClass.getResourceAsStream("/models/captcha/classes.json")!!.bufferedReader().readText()
        Json.decodeFromString<List<String>>(txt)
    }
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val session: OrtSession by lazy {
        val bytes = javaClass.getResourceAsStream("/models/captcha/best.onnx")!!.readBytes()
        env.createSession(bytes, OrtSession.SessionOptions())
    }
    private val inputName: String by lazy { session.inputNames.first() }

    /** Decode a `data:image/...;base64,xxxx` URI (or bare base64) to an opaque RGB image.
     *  Transparency is flattened onto BLACK: the A/order strip is a PNG with a transparent background, but
     *  the model was trained on it composited on black. Without this, letterboxing turns those transparent
     *  pixels GRAY (the pad colour) and wrecks A detection. B (JPEG) has no alpha, so black never shows. */
    fun decode(dataUri: String): BufferedImage {
        val src = ImageIO.read(Base64.getDecoder().decode(dataUri.substringAfter(",")).inputStream())
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            color = Color.BLACK; fillRect(0, 0, src.width, src.height)
            drawImage(src, 0, 0, null)
            dispose()
        }
        return out
    }

    /** Detect shapes in one image. Returns boxes in the ORIGINAL image's pixel space. Serialized: one
     *  OrtSession isn't guaranteed thread-safe and captcha solves are infrequent. */
    @Synchronized
    fun detect(img: BufferedImage): List<Det> {
        val w0 = img.width; val h0 = img.height
        val r = min(SIZE.toFloat() / w0, SIZE.toFloat() / h0)
        val nw = Math.round(w0 * r); val nh = Math.round(h0 * r)
        val px = (SIZE - nw) / 2; val py = (SIZE - nh) / 2
        // Letterbox onto a gray canvas (bicubic to approximate PIL's resize).
        val canvas = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB)
        canvas.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            color = Color(PAD, PAD, PAD); fillRect(0, 0, SIZE, SIZE)
            drawImage(img, px, py, px + nw, py + nh, 0, 0, w0, h0, null)
            dispose()
        }
        // HWC RGB ÷255 → CHW float32.
        val plane = SIZE * SIZE
        val chw = FloatArray(3 * plane)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val rgb = canvas.getRGB(x, y)
            val idx = y * SIZE + x
            chw[idx] = ((rgb shr 16) and 0xFF) / 255f
            chw[plane + idx] = ((rgb shr 8) and 0xFF) / 255f
            chw[2 * plane + idx] = (rgb and 0xFF) / 255f
        }
        val boxes = ArrayList<FloatArray>(); val confs = ArrayList<Float>(); val clsIds = ArrayList<Int>()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val a = (out[0].value as Array<Array<FloatArray>>)[0] // [17][8400]: rows 0-3 box, 4-16 class scores
                val nc = a.size - 4
                val cand = a[0].size
                for (j in 0 until cand) {
                    var best = 0; var bestScore = a[4][j]
                    for (c in 1 until nc) { val s = a[4 + c][j]; if (s > bestScore) { bestScore = s; best = c } }
                    if (bestScore < CONF) continue
                    val cxv = a[0][j]; val cyv = a[1][j]; val ww = a[2][j]; val hh = a[3][j]
                    // cxcywh → xyxy (letterboxed px) then un-letterbox back to original px.
                    val x0 = ((cxv - ww / 2f) - px) / r; val y0 = ((cyv - hh / 2f) - py) / r
                    val x1 = ((cxv + ww / 2f) - px) / r; val y1 = ((cyv + hh / 2f) - py) / r
                    boxes.add(floatArrayOf(x0, y0, x1, y1)); confs.add(bestScore); clsIds.add(best)
                }
            }
        }
        return nms(boxes, confs, IOU).map { i ->
            Det(names.getOrElse(clsIds[i]) { clsIds[i].toString() }, confs[i], boxes[i][0], boxes[i][1], boxes[i][2], boxes[i][3])
        }
    }

    /** Detect on A and B, then apply the §7 match rule → ordered B detections to click (their box centres). */
    fun solve(a: BufferedImage, b: BufferedImage): Solution {
        val aDets = detect(a).sortedBy { it.cx }                                       // left-to-right = click order
        val bDets = detect(b).sortedWith(compareBy({ (it.cy / 40f).roundToInt() }, { it.cx })) // top-row then left
        val used = BooleanArray(bDets.size)
        val clicks = ArrayList<Det>(); val missing = ArrayList<String>()
        for (aD in aDets) {
            val idx = bDets.indices.firstOrNull { !used[it] && bDets[it].name == aD.name }
            if (idx != null) { used[idx] = true; clicks.add(bDets[idx]) } else missing.add(aD.name)
        }
        return Solution(aDets, bDets, clicks, missing)
    }

    // Class-agnostic NMS (matches onnx_infer.py): drop boxes overlapping a higher-score keeper by ≥ iouThr.
    private fun nms(boxes: List<FloatArray>, scores: List<Float>, iouThr: Float): List<Int> {
        val order = scores.indices.sortedByDescending { scores[it] }.toMutableList()
        val keep = ArrayList<Int>()
        while (order.isNotEmpty()) {
            val i = order.removeAt(0); keep.add(i)
            order.removeAll { j -> iou(boxes[i], boxes[j]) >= iouThr }
        }
        return keep
    }
    private fun iou(a: FloatArray, b: FloatArray): Float {
        val x1 = maxOf(a[0], b[0]); val y1 = maxOf(a[1], b[1]); val x2 = minOf(a[2], b[2]); val y2 = minOf(a[3], b[3])
        val inter = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        return inter / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter + 1e-9f)
    }
}
