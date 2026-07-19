package mangautils.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mangautils.core.config.SettingsStore
import mangautils.core.library.LibraryStore
import mangautils.core.library.UpdateResult
import mangautils.core.source.SourceImage
import mangautils.core.source.SourceManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Discord webhook notifications. Events are built off the caller's thread (a small executor fetches
 * covers + assembles messages) and handed to a single sender thread that PACES them to respect
 * Discord's limits (≤10 embeds/message, ~1 message / 2.1s ⇒ under 30/min per channel and 5/2s per
 * webhook) and, on a 429, sleeps `retry_after` and retries — so nothing is dropped. The last
 * rate-limit is exposed for an in-app toast.
 */
object Notifier {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    const val ACCENT = 0x8a7cf0
    const val RED = 0xe86e8f
    const val GREEN = 0x5fce8f
    const val AMBER = 0xe0a458
    private const val MIN_GAP_MS = 2100L // pace: keeps us under 30 msg/min per channel

    // ---- embed model ----
    @Serializable data class Footer(val text: String, val icon_url: String? = null)
    @Serializable data class Author(val name: String, val url: String? = null, val icon_url: String? = null)
    @Serializable data class Img(val url: String)
    @Serializable data class Embed(
        val author: Author? = null,
        val title: String? = null,
        val url: String? = null,
        val description: String? = null,
        val color: Int? = null,
        val footer: Footer? = null,
        val thumbnail: Img? = null,
        val image: Img? = null,
    )
    @Serializable data class Payload(val content: String? = null, val embeds: List<Embed> = emptyList())
    data class Attachment(val filename: String, val bytes: ByteArray)
    data class SendResult(val ok: Boolean, val status: Int, val rateLimited: Boolean, val retryAfter: Double?, val error: String?)

    // ---- rate-limit status (for the in-app toast) ----
    @Volatile var rateLimitedAtMs = 0L; private set
    @Volatile var rateLimitRetryAfter = 0.0; private set

    // ---- config gates ----
    private fun cfg() = runCatching { SettingsStore.get().notify }.getOrNull()
    private fun webhook() = runCatching { SettingsStore.get().discordWebhookUrl }.getOrDefault("")
    private fun active() = cfg()?.enabled == true && webhook().isNotBlank()

    // ================================ public API used by the tester ================================
    /** Single-message send (used by the tester); [cover] is attached as `cover.jpg`. Synchronous. */
    fun sendNow(webhookUrl: String, payload: Payload, cover: ByteArray? = null): SendResult =
        sendMulti(webhookUrl, payload, if (cover != null) listOf(Attachment("cover.jpg", cover)) else emptyList())

    /** Tester embed: title (clickable) / info / source(footer) + violet accent, cover as ~80px thumbnail. */
    fun mangaEmbed(title: String, url: String?, info: String, source: String, withCover: Boolean = true): Embed = Embed(
        title = title.take(256),
        url = url?.takeIf { it.startsWith("http") },
        description = info.take(4000),
        color = ACCENT,
        footer = Footer(source.take(2048)),
        thumbnail = if (withCover) Img("attachment://cover.jpg") else null,
    )

    // ================================ event triggers ================================
    fun onLibraryChecked(results: List<UpdateResult>, scheduled: Boolean) {
        val c = cfg() ?: return
        if (!active() || (!c.libraryCheck && !c.newChapters)) return
        bg.submit {
            runCatching {
                val withNew = results.filter { it.newChapters.isNotEmpty() }
                val totalNew = withNew.sumOf { it.newChapters.size }
                val summary = if (c.libraryCheck)
                    "🔄 **Library check** (${if (scheduled) "scheduled" else "manual"}) · ${results.size} series · " +
                        (if (totalNew > 0) "**$totalNew** new across ${withNew.size}" else "no new chapters")
                else null

                if (!c.newChapters || withNew.isEmpty()) {
                    if (summary != null) enqueue(Payload(content = summary))
                    return@runCatching
                }
                // Per-manga embeds, batched 10/message; the first message carries the summary as content.
                val items = withNew.map { r ->
                    val names = r.newChapters.map { it.name }
                    MangaItem(r.entry.title, r.entry.mangaUrl, chapterList(names), sourceName(r.entry.sourceId), coverFor(r.entry.sourceId, r.entry.mangaUrl))
                }
                items.chunked(10).forEachIndexed { idx, chunk -> enqueue(buildMangaMessage(if (idx == 0) summary else null, chunk)) }
            }.onFailure { log.warn("notify(libraryChecked) failed: {}", it.message) }
        }
    }

    fun onDownloadStart(sourceId: Long, mangaUrl: String, title: String, chapters: Int) {
        if (!active() || cfg()?.downloadStart != true) return
        bg.submit { runCatching { enqueueManga("📥 **Now downloading** — $chapters chapter${plural(chapters)}", sourceId, mangaUrl, title) } }
    }

    fun onDownloadComplete(sourceId: Long, mangaUrl: String, title: String, done: Int) {
        if (!active()) return
        session.add(SessionEntry(title, sourceName(sourceId), done))
        if (cfg()?.downloadComplete == true) bg.submit { runCatching { enqueueManga("✅ **Downloaded** $done chapter${plural(done)}", sourceId, mangaUrl, title) } }
    }

    fun onDownloadFailed(sourceId: Long, mangaUrl: String, title: String, failed: Int, reason: String) {
        if (!active() || cfg()?.downloadFailed != true) return
        bg.submit { runCatching { enqueueManga("⚠️ **Download failed** — $failed chapter${plural(failed)}\n${reason.take(500)}", sourceId, mangaUrl, title, color = RED) } }
    }

    /** Called when the download queue goes idle — flush a session summary of everything downloaded. */
    fun flushDownloadSession() {
        val entries = session.toList(); session.clear()
        if (entries.isEmpty() || !active() || cfg()?.downloadComplete != true) return
        bg.submit {
            runCatching {
                val totalCh = entries.sumOf { it.chapters }
                val list = entries.joinToString("\n") { "• ${it.title} — ${it.chapters}" }.take(3800)
                enqueue(Payload(embeds = listOf(Embed(
                    title = "📥 Downloads complete",
                    description = "**$totalCh** chapter${plural(totalCh)} across **${entries.size}** series\n$list",
                    color = ACCENT,
                ))))
            }.onFailure { log.warn("notify(downloadSession) failed: {}", it.message) }
        }
    }

    fun onSourceTransition(name: String, down: Boolean) {
        if (!active() || cfg()?.sourceHealth != true) return
        bg.submit {
            runCatching {
                enqueue(Payload(embeds = listOf(Embed(
                    title = if (down) "🔴 $name is unreachable" else "🟢 $name recovered",
                    description = if (down) "The source stopped responding during a health check." else "The source is responding again.",
                    color = if (down) RED else GREEN,
                ))))
            }
        }
    }

    /**
     * The server finished binding. [flare] describes the Cloudflare bypass at boot: null when it's
     * switched off, otherwise whether it answered — so a FlareSolverr that died while the server was
     * down is visible immediately rather than at the next solve.
     */
    fun onServerOnline(
        port: Int,
        flare: Pair<Boolean, String?>?,
    ) {
        if (!active() || cfg()?.serviceHealth != true) return
        bg.submit {
            runCatching {
                val bypass =
                    when {
                        flare == null -> "Cloudflare bypass: off"
                        flare.first -> "Cloudflare bypass: online"
                        else -> "Cloudflare bypass: **unreachable**${flare.second?.let { " — $it" } ?: ""}"
                    }
                enqueue(
                    Payload(
                        embeds =
                            listOf(
                                Embed(
                                    title = "🟢 Server online",
                                    description = "Listening on port $port.\n$bypass",
                                    color = if (flare?.first == false) AMBER else GREEN,
                                ),
                            ),
                    ),
                )
            }
        }
    }

    /**
     * A background service changed state. Called only on a transition, so a crash produces one
     * message rather than one per failed request.
     */
    fun onServiceTransition(
        name: String,
        down: Boolean,
        detail: String? = null,
    ) {
        if (!active() || cfg()?.serviceHealth != true) return
        bg.submit {
            runCatching {
                enqueue(
                    Payload(
                        embeds =
                            listOf(
                                Embed(
                                    title = if (down) "🔴 $name is unreachable" else "🟢 $name recovered",
                                    description =
                                        if (down) {
                                            "Requests needing it will fail until it's back." +
                                                (detail?.let { "\n`$it`" } ?: "")
                                        } else {
                                            "It's responding again."
                                        },
                                    color = if (down) RED else GREEN,
                                ),
                            ),
                    ),
                )
            }
        }
    }

    // ================================ building helpers ================================
    private data class MangaItem(val title: String, val url: String, val info: String, val source: String, val cover: ByteArray?)
    private data class SessionEntry(val title: String, val source: String, val chapters: Int)
    private val session = CopyOnWriteArrayList<SessionEntry>()

    private fun enqueueManga(info: String, sourceId: Long, mangaUrl: String, title: String, color: Int = ACCENT) {
        val src = sourceName(sourceId)
        val cover = coverFor(sourceId, mangaUrl)
        enqueue(buildMangaMessage(null, listOf(MangaItem(title, mangaUrl, info, src, cover)), color))
    }

    private fun buildMangaMessage(content: String?, items: List<MangaItem>, color: Int = ACCENT): Msg {
        val style = cfg()?.coverStyle ?: "thumbnail"
        val embeds = ArrayList<Embed>(items.size)
        val atts = ArrayList<Attachment>()
        items.forEachIndexed { i, it ->
            val ref = if (it.cover != null) "cover$i.jpg" else null
            embeds.add(Embed(
                title = it.title.take(256),
                url = it.url.takeIf { u -> u.startsWith("http") },
                description = it.info.take(4000),
                color = color,
                footer = Footer(it.source.take(2048)),
                thumbnail = if (ref != null && style != "poster") Img("attachment://$ref") else null,
                image = if (ref != null && style == "poster") Img("attachment://$ref") else null,
            ))
            if (ref != null) atts.add(Attachment(ref, it.cover!!))
        }
        return Msg(webhook(), Payload(content, embeds), atts)
    }

    /** Full chapter list, truncated with an `(embed-limit)` marker if it would blow the description cap. */
    private fun chapterList(names: List<String>): String {
        val header = "🆕 ${names.size} new chapter${plural(names.size)}\n"
        val sb = StringBuilder(header)
        for ((i, n) in names.withIndex()) {
            val line = "• $n\n"
            if (sb.length + line.length > 3900) { sb.append("• …(embed-limit — ${names.size - i} more)"); break }
            sb.append(line)
        }
        return sb.toString().trimEnd()
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"
    private fun sourceName(id: Long) = runCatching { SourceManager.loadSource(id)?.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: id.toString()
    private fun coverFor(sourceId: Long, mangaUrl: String): ByteArray? {
        val thumb = runCatching { LibraryStore.find(sourceId, mangaUrl)?.thumbnailUrl }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { SourceImage.coverBytes(sourceId, thumb) }.getOrNull()
    }

    // ================================ the paced send queue ================================
    private data class Msg(val url: String, val payload: Payload, val attachments: List<Attachment>)
    private val queue = LinkedBlockingQueue<Msg>()
    private val bg = Executors.newSingleThreadExecutor { r -> Thread(r, "notify-build").apply { isDaemon = true } }
    @Volatile private var senderStarted = false
    @Volatile private var lastSendMs = 0L

    private fun enqueue(payload: Payload) = enqueue(Msg(webhook(), payload, emptyList()))
    private fun enqueue(msg: Msg) {
        if (msg.url.isBlank()) return
        ensureSender()
        queue.offer(msg)
    }

    @Synchronized
    private fun ensureSender() {
        if (senderStarted) return
        senderStarted = true
        Thread({ senderLoop() }, "notify-sender").apply { isDaemon = true }.start()
    }

    private fun senderLoop() {
        while (true) {
            val msg = queue.take()
            val gap = System.currentTimeMillis() - lastSendMs
            if (gap < MIN_GAP_MS) Thread.sleep(MIN_GAP_MS - gap)
            var attempt = 0
            while (attempt < 6) {
                val r = sendMulti(msg.url, msg.payload, msg.attachments)
                lastSendMs = System.currentTimeMillis()
                if (r.rateLimited) {
                    rateLimitedAtMs = lastSendMs
                    rateLimitRetryAfter = r.retryAfter ?: 1.0
                    val waitMs = ((r.retryAfter ?: 1.0) * 1000).toLong() + 300
                    log.warn("Discord rate-limited — retrying in {}ms", waitMs)
                    Thread.sleep(waitMs)
                    attempt++
                    continue
                }
                if (!r.ok) log.warn("Discord webhook send failed: {}", r.error)
                break
            }
        }
    }

    private fun sendMulti(webhookUrl: String, payload: Payload, attachments: List<Attachment>): SendResult {
        if (webhookUrl.isBlank()) return SendResult(false, 0, false, null, "No webhook URL set")
        val payloadJson = json.encodeToString(payload)
        val body = if (attachments.isEmpty()) {
            payloadJson.toRequestBody("application/json; charset=utf-8".toMediaType())
        } else {
            val b = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("payload_json", payloadJson)
            attachments.forEachIndexed { i, a -> b.addFormDataPart("files[$i]", a.filename, a.bytes.toRequestBody("image/jpeg".toMediaType())) }
            b.build()
        }
        val req = Request.Builder().url(webhookUrl).post(body).build()
        return try {
            client.newCall(req).execute().use { r ->
                when {
                    r.code == 429 -> {
                        val ra = runCatching {
                            json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject["retry_after"]?.jsonPrimitive?.content?.toDoubleOrNull()
                        }.getOrNull() ?: r.header("retry-after")?.toDoubleOrNull()
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
}
