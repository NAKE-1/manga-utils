package xyz.nulldev.androidcompat.webkit

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefRendering
import org.cef.handler.CefRenderHandlerAdapter
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import javax.swing.JPanel

/**
 * One interactive, offscreen (OSR) JCEF browser whose rendered frames are exposed as JPEG snapshots —
 * the server-side half of "Open in WebView". This is STAGE 1: the video feed only (open a URL, pull
 * frames). Input forwarding (taps/keys) is Stage 2.
 *
 * The point: a human solves a Cloudflare *interactive* challenge (e.g. mangafire's `/@waf/challenge`
 * "click the shapes in order") in the streamed view. Because JCEF cookies live in the GLOBAL
 * CefCookieManager — the same store JcefFetch's pool uses — the resulting `cf_clearance` is picked up
 * by the normal fetch path automatically, with no extra bridging.
 *
 * One session at a time (you solve one challenge at a time). No window — same OSR the extension WebViews use.
 *
 * ponytail: single global session + poll-per-frame (client GETs /frame). Fine for a one-off challenge;
 * swap to a push stream (WebSocket/MJPEG) only if the poll latency proves too laggy in practice.
 */
object JcefRemoteView {
    private val log = KotlinLogging.logger {}

    // OSR viewport. Kept close to a challenge card's size so a centered card fills most of the frame and
    // reads large on a phone (a big viewport makes the card a tiny island of empty dark space).
    const val WIDTH = 440
    const val HEIGHT = 780

    private var client: CefClient? = null
    @Volatile private var browser: CefBrowser? = null
    @Volatile private var currentUrl = ""
    private val panel = JPanel() // MouseEvent source for input forwarding

    private val lock = Any()
    private var raw: ByteArray? = null // latest BGRA frame from onPaint
    private var rw = 0
    private var rh = 0

    private val renderHandler = object : CefRenderHandlerAdapter() {
        override fun getViewRect(browser: CefBrowser) = Rectangle(0, 0, WIDTH, HEIGHT)

        override fun onPaint(b: CefBrowser, popup: Boolean, dirtyRects: Array<Rectangle>, buffer: ByteBuffer, width: Int, height: Int) {
            // Drop popups and — crucially — paints from any browser that isn't the current one. When open()
            // replaces a session, the old browser keeps painting for a moment while it disposes; without this
            // guard both write into `raw` and you see two challenges flashing back and forth.
            if (popup || b !== browser) return
            synchronized(lock) {
                val need = width * height * 4
                if (raw?.size != need) raw = ByteArray(need)
                val b = buffer.duplicate(); b.rewind()
                b.get(raw!!, 0, minOf(need, b.remaining()))
                rw = width; rh = height
            }
        }
    }

    /** (Re)open the offscreen browser at [url]. Disposes any previous session. */
    @Synchronized
    fun open(url: String) {
        // Idempotent: a client re-mount / re-render must NOT spawn a second browser (that's what caused the
        // two-challenges-flashing + endless re-init). Same URL + a live session → keep it.
        if (browser != null && url.trimEnd('/') == currentUrl.trimEnd('/')) return
        if (client == null) client = runCatching { runBlocking { CefHelper.createClient() } }.getOrNull()
        val c = client ?: run { log.warn { "JcefRemoteView: CEF client unavailable" }; return }
        closeBrowser()
        synchronized(lock) { raw = null; rw = 0; rh = 0 }
        currentUrl = url
        browser = c.createBrowser(url, CefRendering.CefRenderingWithHandler(renderHandler, panel), false)
            .apply { createImmediately() }
        log.info { "JcefRemoteView: opened $url" }
    }

    /** Forward a tap at OSR-pixel ([x],[y]) as a left click (move → press → release). */
    fun click(x: Int, y: Int) {
        val b = browser ?: return
        val now = System.currentTimeMillis()
        fun ev(id: Int, mods: Int, clicks: Int, button: Int) =
            java.awt.event.MouseEvent(panel, id, now, mods, x, y, clicks, false, button)
        runCatching {
            b.setFocus(true)
            b.sendMouseEvent(ev(java.awt.event.MouseEvent.MOUSE_MOVED, 0, 0, 0))
            b.sendMouseEvent(ev(java.awt.event.MouseEvent.MOUSE_PRESSED, java.awt.event.InputEvent.BUTTON1_DOWN_MASK, 1, java.awt.event.MouseEvent.BUTTON1))
            b.sendMouseEvent(ev(java.awt.event.MouseEvent.MOUSE_RELEASED, 0, 1, java.awt.event.MouseEvent.BUTTON1))
        }
        runCatching { b.invalidate() }
    }

    /** The latest frame as a JPEG, or null if nothing has painted yet. Also nudges the next repaint. */
    fun frameJpeg(): ByteArray? {
        val bytes: ByteArray; val w: Int; val h: Int
        synchronized(lock) {
            val r = raw ?: return null
            if (rw == 0 || rh == 0) return null
            bytes = r.copyOf(); w = rw; h = rh
        }
        // OSR only repaints on change; poke it so the stream keeps flowing while the client polls.
        runCatching { browser?.invalidate() }
        return runCatching {
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val px = IntArray(w * h)
            var i = 0; var j = 0
            while (i < px.size) {
                val bch = bytes[j].toInt() and 0xFF
                val g = bytes[j + 1].toInt() and 0xFF
                val rr = bytes[j + 2].toInt() and 0xFF
                px[i] = (rr shl 16) or (g shl 8) or bch
                i++; j += 4
            }
            img.setRGB(0, 0, w, h, px, 0, w)
            val baos = ByteArrayOutputStream()
            ImageIO.write(img, "jpg", baos)
            baos.toByteArray()
        }.getOrNull()
    }

    val isOpen: Boolean get() = browser != null

    /** Cookies currently stored for the open page's host — a visual "cookies captured" indicator for the
     *  WebView top bar. Bounded blocking (visitor is async); returns 0 if nothing's open. */
    fun cookieCount(): Int {
        val host = runCatching { java.net.URI(currentUrl).host }.getOrNull()?.trimStart('.')
        if (host.isNullOrBlank()) return 0
        val mgr = runCatching { org.cef.network.CefCookieManager.getGlobalManager() }.getOrNull() ?: return 0
        val latch = java.util.concurrent.CountDownLatch(1)
        var n = 0
        val ok = runCatching {
            mgr.visitAllCookies(object : org.cef.callback.CefCookieVisitor {
                override fun visit(cookie: org.cef.network.CefCookie, curr: Int, total: Int, delete: org.cef.misc.BoolRef): Boolean {
                    val dom = cookie.domain?.trimStart('.') ?: ""
                    if (dom.isNotEmpty() && (host.endsWith(dom) || dom.endsWith(host))) n++
                    if (curr + 1 >= total) latch.countDown()
                    return true
                }
            })
        }.getOrDefault(false)
        if (!ok) return 0
        latch.await(1, java.util.concurrent.TimeUnit.SECONDS)
        return n
    }

    @Synchronized
    fun close() {
        closeBrowser()
        currentUrl = ""
        synchronized(lock) { raw = null; rw = 0; rh = 0 }
        log.info { "JcefRemoteView: closed" }
    }

    private fun closeBrowser() {
        browser?.let { runCatching { it.close(true) } }
        browser = null
    }
}
