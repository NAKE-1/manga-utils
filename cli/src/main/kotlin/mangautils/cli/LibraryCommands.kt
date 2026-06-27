/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import mangautils.core.config.SettingsStore
import mangautils.core.download.ChapterSelect
import mangautils.core.download.DownloadManager
import mangautils.core.download.ExistingPolicy
import mangautils.core.download.SourceRef
import mangautils.core.library.LibraryEntry
import mangautils.core.library.LibraryService
import mangautils.core.library.ReadingMode

/** `mu library` — follow series and track new chapters. */
class LibraryCommand : CliktCommand(name = "library") {
    override fun help(context: Context) = "Follow series and track new chapters"
    override fun run() = Unit
}

/** `mu library add <sourceId> <mangaUrl> [--mode rtl|ltr|vertical|longstrip]` */
class LibraryAddCommand : CliktCommand(name = "add") {
    override fun help(context: Context) = "Follow a series (fetches details + chapter snapshot)"

    private val sourceId by argument(name = "sourceId").long()
    private val mangaUrl by argument(name = "mangaUrl")
    private val mode by option("--mode", help = "Reading mode: rtl, ltr, vertical, longstrip")

    override fun run() {
        val rm = mode?.let { ReadingMode.from(it) ?: throw ProgramResult(1).also { echo(Style.warn("Unknown --mode '$mode'"), err = true) } }
        echo(Style.dim("Following $mangaUrl ..."))
        try {
            val e = LibraryService.add(sourceId, mangaUrl, rm)
            echo("${Style.ok("Following")} ${Style.heading(e.title)} ${Style.dim("(${e.knownChapters.size} chapters, ${e.readingMode})")}")
        } catch (ex: Exception) {
            echo(Style.warn("Failed to follow: ${ex.message}"), err = true)
            throw ProgramResult(1)
        }
    }
}

/** `mu library list` */
class LibraryListCommand : CliktCommand(name = "list") {
    override fun help(context: Context) = "List followed series"

    override fun run() {
        val entries = LibraryService.list()
        if (entries.isEmpty()) {
            echo(Style.dim("Library is empty. Follow one with: mu library add <sourceId> <mangaUrl>"))
            return
        }
        entries.forEach { e ->
            echo("${Style.title(e.title)}  ${Style.dim("${e.knownChapters.size} ch")}  ${Style.lang(e.readingMode.name)}")
            echo("    ${Style.dim("src ${e.sourceId}  ${e.mangaUrl}")}")
        }
        echo("")
        echo(Style.ok("${entries.size} series."))
    }
}

/** `mu library remove <sourceId> <mangaUrl>` */
class LibraryRemoveCommand : CliktCommand(name = "remove") {
    override fun help(context: Context) = "Stop following a series"

    private val sourceId by argument(name = "sourceId").long()
    private val mangaUrl by argument(name = "mangaUrl")

    override fun run() {
        if (LibraryService.remove(sourceId, mangaUrl)) echo("Unfollowed.") else echo("Not in library.")
    }
}

/** `mu library update [--download]` — the tracker: detect new chapters, optionally grab them. */
class LibraryUpdateCommand : CliktCommand(name = "update") {
    override fun help(context: Context) = "Check followed series for new chapters"

    private val download by option("--download", help = "Also download any new chapters").flag()

    override fun run() {
        val entries = LibraryService.list()
        if (entries.isEmpty()) {
            echo(Style.dim("Library is empty."))
            return
        }
        echo(Style.dim("Checking ${entries.size} series for new chapters..."))
        val results = LibraryService.update(entries)
        val withNew = results.filter { it.newChapters.isNotEmpty() }

        if (withNew.isEmpty()) {
            echo(Style.ok("Up to date - no new chapters."))
            return
        }
        echo("")
        withNew.forEach { r ->
            echo("${Style.heading(r.entry.title)}  ${Style.ok("+${r.newChapters.size} new")}")
            r.newChapters.sortedBy { it.number }.forEach { ch -> echo("    ${Style.title(ch.name)}") }
        }
        echo("")
        val total = withNew.sumOf { it.newChapters.size }
        echo(Style.ok("$total new chapter(s) across ${withNew.size} series."))

        if (download) {
            echo("")
            echo(Style.dim("Downloading new chapters..."))
            withNew.forEach { r ->
                val urls = r.newChapters.map { it.url }.toSet()
                DownloadManager(listener = CliProgress.listener)
                    .download(SourceRef(r.entry.sourceId, r.entry.mangaUrl), emptyList(), ChapterSelect.Urls(urls))
            }
        } else {
            echo(Style.dim("Run with --download to grab them."))
        }
    }
}

/** `mu library download <indexOrTitle> [--all|--missing|--range A-B]` */
class LibraryDownloadCommand : CliktCommand(name = "download") {
    override fun help(context: Context) = "Download chapters of a followed series (default: missing only)"

    private val target by argument(name = "indexOrTitle", help = "List number from `library list`, or a title substring")
    private val all by option("--all", help = "All chapters").flag()
    private val missing by option("--missing", help = "Only chapters not on disk (default)").flag()
    private val range by option("--range", help = "Number range A-B")
    private val existing by option("--existing", help = "If a chapter exists: skip|replace|ask")

    override fun run() {
        val entries = LibraryService.list()
        val entry = resolveEntry(entries, target)
        if (entry == null) {
            echo(Style.warn("No library entry matches '$target'"), err = true)
            throw ProgramResult(1)
        }
        val select =
            when {
                all -> ChapterSelect.All
                range != null -> parseRange(range!!)
                else -> ChapterSelect.Missing // default + explicit --missing
            }
        val settings = SettingsStore.get()
        val policy = existing?.let { ExistingPolicy.from(it) ?: error("--existing must be skip|replace|ask") } ?: settings.existingBehavior

        echo(Style.dim("Downloading '${entry.title}'..."))
        val job =
            DownloadManager(
                concurrency = settings.downloadConcurrency,
                retries = settings.downloadRetries,
                listener = CliProgress.listener,
                existingPolicy = policy,
                existingPrompt = if (policy == ExistingPolicy.ASK) cliExistingPrompt() else null,
            ).download(SourceRef(entry.sourceId, entry.mangaUrl), emptyList(), select)

        val ok = job.attempts.count { it.outcome == "ok" }
        val skipped = job.attempts.count { it.outcome == "skipped" }
        val failed = job.attempts.count { it.outcome == "failed" }
        echo("")
        echo("${Style.ok("$ok ok")}, ${Style.lang("$skipped skipped")}, ${Style.warn("$failed failed")}  ${Style.dim("(mu status ${job.id})")}")
    }

    private fun resolveEntry(
        entries: List<LibraryEntry>,
        target: String,
    ): LibraryEntry? {
        target.toIntOrNull()?.let { idx -> return entries.getOrNull(idx - 1) }
        return entries.firstOrNull { it.title.contains(target, ignoreCase = true) }
    }
}

fun LibraryCommand.withSubcommands(): LibraryCommand =
    subcommands(
        LibraryAddCommand(),
        LibraryListCommand(),
        LibraryUpdateCommand(),
        LibraryDownloadCommand(),
        LibraryRemoveCommand(),
    )
