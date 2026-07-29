/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import mangautils.core.config.AppConfig
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Whole-instance data migration: zip up EVERYTHING under the data dir except the downloads (which move
 * separately as a mounted drive) and the regenerable tooling/caches (rebuilt on first launch). Purpose:
 * move one instance to another — e.g. onto Linux/Proxmox/Docker — by mounting the downloads drive and
 * restoring this blob, instead of hand-copying a dozen json files.
 *
 * Unlike the .tachibk backup (library/read/settings only) this carries the FULL state: resume positions,
 * history, unavailable marks, download queue, extension prefs, installed extensions, covers — the lot.
 * Both export and import surface a [Manifest] first, so you see exactly what will move before it moves.
 */
object DataMigration {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true }

    /** Top-level names never migrated: downloads (moved as a drive) + purely-regenerable dirs/logs. */
    private val EXCLUDE = setOf(
        "downloads",                                   // the whole point — moved separately as a mount
        "bin", "android-compat", "build", "cache",     // tooling/stubs, rebuilt on first run
        "logs", ".flare-probed", "jobs.json",          // logs + a big diagnostic trace + a probe marker
    )

    /** One category on the "what will move" screen. */
    @Serializable
    data class Item(val key: String, val label: String, val files: Int, val bytes: Long, val detail: String)

    /** The full "what will move" summary, for either the export or the import-preview screen. */
    @Serializable
    data class Manifest(val files: Int, val bytes: Long, val items: List<Item>)

    // ---- export / import --------------------------------------------------------------------------

    /** Zip the migratable slice of the data dir into memory. */
    fun export(): ByteArray {
        val root = AppConfig.dataDir
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) && !excluded(root, it) }.forEach { f ->
                    zip.putNextEntry(ZipEntry(root.relativize(f).toString().replace('\\', '/')))
                    Files.newInputStream(f).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        val bytes = out.toByteArray()
        log.info("data migration export: {} KB", bytes.size / 1024)
        return bytes
    }

    /**
     * Extract a migration zip over the data dir (overwriting). Skips the excluded set and any path that
     * tries to escape the data dir (zip-slip guard). Files already open in memory won't refresh — a
     * restart is required after import, which suits the intended flow (import into a fresh instance).
     * Returns how many files were written.
     */
    fun import(bytes: ByteArray): Int {
        val root = AppConfig.dataDir
        var n = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e: ZipEntry? = zip.nextEntry
            while (e != null) {
                val name = e.name.replace('\\', '/')
                if (!e.isDirectory && name.substringBefore('/') !in EXCLUDE && ".." !in name) {
                    val dest = root.resolve(name).normalize()
                    if (dest.startsWith(root)) {
                        dest.parent?.let { Files.createDirectories(it) }
                        Files.newOutputStream(dest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { zip.copyTo(it) }
                        n++
                    }
                }
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        log.info("data migration import: {} file(s) restored (restart to apply)", n)
        return n
    }

    // ---- manifests (the "what will move" screens) -------------------------------------------------

    /** What an export from THIS instance would contain (walks the live data dir). */
    fun manifest(): Manifest {
        val root = AppConfig.dataDir
        val agg = Agg()
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && !excluded(root, it) }.forEach { f ->
                val name = root.relativize(f).toString().replace('\\', '/')
                val size = runCatching { Files.size(f) }.getOrDefault(0L)
                val count = if (name in COUNTABLE) runCatching { countOf(Files.readAllBytes(f)) }.getOrNull() else null
                agg.add(name, size, count)
            }
        }
        return agg.build()
    }

    /** What restoring an uploaded migration zip WOULD do — read-only, changes nothing. */
    fun preview(bytes: ByteArray): Manifest {
        val agg = Agg()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e: ZipEntry? = zip.nextEntry
            while (e != null) {
                val name = e.name.replace('\\', '/')
                if (!e.isDirectory && name.substringBefore('/') !in EXCLUDE && ".." !in name) {
                    val data = zip.readBytes()
                    agg.add(name, data.size.toLong(), if (name in COUNTABLE) runCatching { countOf(data) }.getOrNull() else null)
                }
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        if (agg.total == 0) error("empty or unrecognised migration package")
        return agg.build()
    }

    // ---- categorisation ---------------------------------------------------------------------------

    /** JSONs worth parsing for a "N series / N marks" detail instead of a raw byte size. */
    private val COUNTABLE = setOf("library.json", "read.json", "positions.json", "history.json", "unavailable-chapters.json", "repos.json")

    private fun categorize(name: String): Pair<String, String> = when {
        name.startsWith("extensions/") -> "extensions" to "Installed extensions"
        name.startsWith("covers/") -> "covers" to "Cover cache"
        name.startsWith("settings/") -> "extprefs" to "Extension preferences"
        name == "library.json" -> "library" to "Library (followed series)"
        name == "read.json" -> "read" to "Read / unread marks"
        name == "positions.json" -> "positions" to "Reader resume points"
        name == "history.json" -> "history" to "Reading history"
        name == "settings.json" -> "settings" to "App settings"
        name == "repos.json" -> "repos" to "Extension repos"
        name == "unavailable-chapters.json" -> "unavailable" to "Unavailable-chapter marks"
        name == "downloadqueue.json" -> "queue" to "Download queue"
        name == "sourcehealth.json" -> "health" to "Source health"
        name == "cloudflare.json" -> "cloudflare" to "Cloudflare detection"
        else -> "other" to "Other data"
    }

    /** Best-effort element count for a state JSON — array size, else the first inner array, else keys. */
    private fun countOf(data: ByteArray): Int? = when (val el = json.parseToJsonElement(data.decodeToString())) {
        is JsonArray -> el.size
        is JsonObject -> (el.values.firstOrNull { it is JsonArray } as? JsonArray)?.size ?: el.size
        else -> null
    }

    private fun excluded(root: Path, file: Path): Boolean =
        root.relativize(file).getName(0).toString() in EXCLUDE

    /** Accumulates files into ordered categories and formats each one's human detail line. */
    private class Agg {
        private data class Cat(val label: String, var files: Int = 0, var bytes: Long = 0, var count: Int? = null)
        private val cats = LinkedHashMap<String, Cat>()
        var total = 0; private set
        private var totalBytes = 0L

        fun add(name: String, size: Long, count: Int?) {
            val (key, label) = categorize(name)
            val c = cats.getOrPut(key) { Cat(label) }
            c.files++; c.bytes += size; if (count != null) c.count = count
            total++; totalBytes += size
        }

        fun build(): Manifest {
            // Stable, sensible order: the things people care about first, "other" last.
            val order = listOf("library", "read", "positions", "history", "unavailable", "queue", "settings", "repos", "extprefs", "extensions", "covers", "health", "cloudflare", "other")
            val items = cats.entries.sortedBy { order.indexOf(it.key).let { i -> if (i < 0) 99 else i } }.map { (key, c) ->
                Item(key, c.label, c.files, c.bytes, detailFor(key, c))
            }
            return Manifest(total, totalBytes, items)
        }

        private fun detailFor(key: String, c: Cat): String = when {
            c.count != null -> when (key) {
                "library" -> "${c.count} series"
                "read" -> "${c.count} entries"
                "positions" -> "${c.count} resume points"
                "history" -> "${c.count} entries"
                "unavailable" -> "${c.count} chapters"
                "repos" -> "${c.count} repos"
                else -> "${c.count} items"
            }
            key == "extensions" -> "${c.files} extension${if (c.files == 1) "" else "s"}"
            key == "covers" -> "${c.files} cover${if (c.files == 1) "" else "s"}"
            key == "extprefs" -> "${c.files} file${if (c.files == 1) "" else "s"}"
            else -> humanBytes(c.bytes)
        }

        private fun humanBytes(b: Long): String {
            if (b < 1024) return "$b B"
            val kb = b / 1024.0
            if (kb < 1024) return "${kb.toInt()} KB"
            return "%.1f MB".format(kb / 1024)
        }
    }
}
