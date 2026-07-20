package mangautils.server

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.runBlocking
import mangautils.core.download.ChapterIdentity
import mangautils.core.download.DownloadManager
import mangautils.core.library.BookmarkStore
import mangautils.core.library.HistoryStore
import mangautils.core.library.LibraryService
import mangautils.core.library.LibraryStore
import mangautils.core.library.ReadStore
import mangautils.core.source.SourceBrowser
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Source migration: move a followed series from (fromSource, fromUrl) to (toSource, toUrl), carrying
 * read state, bookmarks and continue-reading by matching chapters on their NUMBER. [compute] is a
 * side-effect-free preview (for the comparison view); [start] runs the real migration on a background
 * thread with a verbose step log the UI polls.
 */
object MigrationJob {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile var running = false; private set
    @Volatile var phase = "idle"; private set
    @Volatile var finished = false; private set
    @Volatile var error = ""; private set
    val steps = CopyOnWriteArrayList<String>()

    data class Plan(
        val toManga: SManga,
        val toChapters: List<SChapter>,
        val toTitle: String,
        val toCover: String?,
        val fromTitle: String,
        val fromTotal: Int,
        val toTotal: Int,
        val readNumbers: Set<Float>,
        val bookmarkNumbers: Set<Float>,
        val bReadUrls: List<String>,
        val bBookmarkUrls: List<String>,
        val matchedRead: Int,
        val unmatchedRead: Int,
        val matchedBookmarks: Int,
        val unmatchedBookmarks: Int,
        val unnumbered: Int,
        val readUpTo: Float,
        val lastReadB: SChapter?,
        val fromDownloaded: Int,
    )

    /** Compute what a migration would carry — no side effects. Throws if the source manga isn't followed. */
    fun compute(fromSource: Long, fromUrl: String, toSource: Long, toUrl: String): Plan {
        val fromEntry = LibraryStore.find(fromSource, fromUrl) ?: error("The source manga isn't in your library.")
        val readSet = ReadStore.readUrls(fromSource, fromUrl)
        val bmSet = BookmarkStore.bookmarks(fromSource, fromUrl)
        // By URL, not name - see the note in Main.kt's library counts.
        val dlUrls = runCatching { ChapterIdentity.downloadedUrls(fromEntry.title) }.getOrDefault(emptySet())

        // Count UNIQUE chapters (grouped by number, else name) — the same grouping the detail page and
        // library badge use — so a multi-scanlator source shows 95 chapters, not 277 raw entries.
        fun key(number: Float, name: String) = if (number > 0) "n$number" else "t${name.trim().lowercase()}"
        val fromGroups = fromEntry.knownChapters.groupBy { key(it.number, it.name) }
        val fromTotal = fromGroups.size
        val fromDownloaded = fromGroups.count { (_, vs) -> vs.any { it.url in dlUrls } }

        val readNumbers = fromEntry.knownChapters.filter { it.url in readSet && it.number >= 0 }.map { it.number }.toSet()
        val bmNumbers = fromEntry.knownChapters.filter { it.url in bmSet && it.number >= 0 }.map { it.number }.toSet()
        val unnumbered = fromEntry.knownChapters.count { it.url in readSet && it.number < 0 }

        val d = runBlocking { SourceBrowser.detailsAsync(toSource, toUrl) }
        val toTotal = d.chapters.groupBy { key(it.chapter_number, it.name) }.size
        val bByNum = d.chapters.filter { it.chapter_number >= 0 }.groupBy { it.chapter_number }
        val bReadUrls = readNumbers.flatMap { bByNum[it].orEmpty() }.map { it.url }
        val bBmUrls = bmNumbers.flatMap { bByNum[it].orEmpty() }.map { it.url }
        val matchedRead = readNumbers.count { bByNum.containsKey(it) }
        val matchedBm = bmNumbers.count { bByNum.containsKey(it) }
        val readUpTo = readNumbers.maxOrNull() ?: -1f
        val lastReadB = readNumbers.filter { bByNum.containsKey(it) }.maxOrNull()?.let { bByNum[it]?.firstOrNull() }

        return Plan(
            d.manga, d.chapters, d.manga.title.ifBlank { fromEntry.title }, runCatching { d.manga.thumbnail_url }.getOrNull(),
            fromEntry.title, fromTotal, toTotal, readNumbers, bmNumbers, bReadUrls, bBmUrls,
            matchedRead, readNumbers.size - matchedRead, matchedBm, bmNumbers.size - matchedBm,
            unnumbered, readUpTo, lastReadB, fromDownloaded,
        )
    }

    fun start(fromSource: Long, fromUrl: String, toSource: Long, toUrl: String, deleteOldDownloads: Boolean, reDownload: Boolean) {
        if (running) return
        running = true; finished = false; error = ""; steps.clear(); phase = "starting"
        Thread({ run(fromSource, fromUrl, toSource, toUrl, deleteOldDownloads, reDownload) }, "migrate").apply { isDaemon = true }.start()
    }

    private fun step(s: String) { steps.add(s); log.info("migrate: {}", s) }

    private fun run(fromSource: Long, fromUrl: String, toSource: Long, toUrl: String, deleteOldDownloads: Boolean, reDownload: Boolean) {
        try {
            val fromEntry = LibraryStore.find(fromSource, fromUrl) ?: error("The source manga isn't in your library.")
            phase = "fetching"; step("Fetching chapters from the new source…")
            val plan = compute(fromSource, fromUrl, toSource, toUrl)
            step("New source has ${plan.toTotal} chapters (old had ${plan.fromTotal}).")

            phase = "carrying"; step("Matching chapters by number…")
            step("Carrying ${plan.matchedRead} read chapter(s)" +
                (if (plan.unmatchedRead > 0) " · ${plan.unmatchedRead} couldn't be matched" else "") +
                (if (plan.unnumbered > 0) " · ${plan.unnumbered} unnumbered skipped" else "") + ".")
            ReadStore.markRead(toSource, toUrl, plan.bReadUrls)
            if (plan.matchedBookmarks > 0) step("Carrying ${plan.matchedBookmarks} bookmark(s).")
            plan.bBookmarkUrls.forEach { BookmarkStore.setBookmarked(toSource, toUrl, it, true) }

            plan.lastReadB?.let { ch ->
                HistoryStore.record(toSource, toUrl, plan.toTitle, ch.url, ch.name, plan.toCover)
                step("Continue-reading set to '${ch.name}' (you'd read up to ${fmtNum(plan.readUpTo)}).")
            }

            phase = "library"
            LibraryService.addKnown(toSource, toUrl, plan.toTitle, plan.toManga, plan.toChapters)
            step("Added '${plan.toTitle}' from the new source to your library.")
            LibraryService.remove(fromSource, fromUrl)
            step("Removed the old '${plan.fromTitle}' entry.")

            if (deleteOldDownloads) {
                phase = "downloads"
                // By URL, not name - see the note in Main.kt's library counts.
                val dlUrls = runCatching { ChapterIdentity.downloadedUrls(fromEntry.title) }.getOrDefault(emptySet())
                val n = dlUrls.size
                DownloadManager.deleteDownloads(fromEntry.title)
                step("Deleted $n old downloaded chapter(s).")
                if (reDownload && n > 0) {
                    val dlNumbers = fromEntry.knownChapters.filter { it.url in dlUrls && it.number >= 0 }.map { it.number }.toSet()
                    val bDl = plan.toChapters.filter { it.chapter_number in dlNumbers }.map { DownloadQueue.Chapter(it.url, it.name) }
                    if (bDl.isNotEmpty()) { DownloadQueue.enqueue(toSource, toUrl, plan.toTitle, bDl, tag = "migration"); step("Queued ${bDl.size} chapter(s) to re-download from the new source — see Downloads.") }
                }
            } else {
                step("Kept the old source's downloads on disk (they were downloaded from the old source).")
            }

            phase = "done"; step("Migration complete ✓"); finished = true
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            phase = "error"; step("✕ Migration failed: $error"); finished = true
        } finally {
            running = false
        }
    }

    private fun fmtNum(n: Float) = if (n < 0) "?" else if (n == n.toInt().toFloat()) n.toInt().toString() else n.toString()
}
