package mangautils.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Discord webhook sender. Builds embeds (one manga per embed, up to 10 per message) and posts them —
 * with the cover uploaded as a multipart file attachment (`attachment://cover.jpg`), since Discord's
 * servers can't fetch a Tailscale cover URL.
 *
 * This first cut exposes [sendNow] for the tester (synchronous, returns the HTTP result incl. 429 +
 * retry_after). The paced batch queue for bulk event notifications (5 req / 2s, 30 msg / min, honoring
 * retry_after) is added when the event triggers are wired.
 */
object Notifier {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** manga-utils violet — the embed's accent strip. */
    const val ACCENT = 0x8a7cf0

    @Serializable data class Footer(val text: String, val icon_url: String? = null)
    @Serializable data class Img(val url: String)
    @Serializable data class Embed(
        val title: String? = null,
        val url: String? = null,
        val description: String? = null,
        val color: Int? = null,
        val footer: Footer? = null,
        val thumbnail: Img? = null,
    )
    @Serializable data class Payload(val content: String? = null, val embeds: List<Embed> = emptyList())

    data class SendResult(val ok: Boolean, val status: Int, val rateLimited: Boolean, val retryAfter: Double?, val error: String?)

    /**
     * Post one message now. [cover] (JPEG/PNG bytes) is attached and referenced by the FIRST embed's
     * thumbnail as `attachment://cover.jpg`. Returns the outcome (never throws).
     */
    fun sendNow(webhookUrl: String, payload: Payload, cover: ByteArray? = null): SendResult {
        if (webhookUrl.isBlank()) return SendResult(false, 0, false, null, "No webhook URL set")
        val payloadJson = json.encodeToString(payload)
        val body = if (cover != null) {
            MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("payload_json", payloadJson)
                .addFormDataPart("files[0]", "cover.jpg", cover.toRequestBody("image/jpeg".toMediaType()))
                .build()
        } else {
            payloadJson.toRequestBody("application/json; charset=utf-8".toMediaType())
        }
        val req = Request.Builder().url(webhookUrl).post(body).build()
        return try {
            client.newCall(req).execute().use { r ->
                when {
                    r.code == 429 -> {
                        val ra = runCatching {
                            json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject["retry_after"]?.jsonPrimitive?.content?.toDoubleOrNull()
                        }.getOrNull() ?: r.header("retry-after")?.toDoubleOrNull()
                        log.warn("Discord webhook rate-limited (retry after {}s)", ra)
                        SendResult(false, 429, true, ra, "Rate limited — retry after ${ra ?: "?"}s")
                    }
                    r.isSuccessful -> SendResult(true, r.code, false, null, null)
                    else -> {
                        val msg = r.body?.string()?.take(300).orEmpty()
                        SendResult(false, r.code, false, null, "HTTP ${r.code}${if (msg.isNotBlank()) ": $msg" else ""}")
                    }
                }
            }
        } catch (e: Exception) {
            SendResult(false, 0, false, null, e.message ?: e.toString())
        }
    }

    /** Build a per-manga embed matching the agreed layout: title / info(description) / source(footer),
     *  cover carried separately as the attachment. */
    fun mangaEmbed(title: String, url: String?, info: String, source: String): Embed = Embed(
        title = title.take(256),
        url = url?.takeIf { it.startsWith("http") },
        description = info.take(4000),
        color = ACCENT,
        footer = Footer(source.take(2048)),
        thumbnail = Img("attachment://cover.jpg"),
    )
}
