package mangautils.server

import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Tachiyomi/Mihon extensions have no metadata flag declaring "I need a WebView" — they just call
 * android.webkit.WebView at runtime (JS-rendered pages, Cloudflare). We surface that to the UI by
 * statically scanning the translated extension jar's bytecode for a reference to android/webkit/WebView.
 * Result is cached per jar path (jars only change on install/update).
 */
object WebViewDetect {
    private val cache = ConcurrentHashMap<String, Boolean>()
    private const val NEEDLE = "android/webkit/WebView"

    fun usesWebView(jarPath: String): Boolean =
        cache.getOrPut(jarPath) {
            runCatching {
                ZipFile(jarPath).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .filter { it.name.endsWith(".class") }
                        .any { entry ->
                            // class constant pools are ASCII/latin1; a substring check is enough
                            zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.ISO_8859_1).contains(NEEDLE)
                        }
                }
            }.getOrDefault(false)
        }
}
