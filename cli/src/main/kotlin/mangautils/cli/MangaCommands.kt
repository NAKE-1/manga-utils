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
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import mangautils.core.source.SourceBrowser

/** `mu search <sourceId> "<query>"` — search a source over the network. */
class SearchCommand : CliktCommand(name = "search") {
    override fun help(context: Context) = "Search a source for manga"

    private val sourceId by argument(name = "sourceId").long()
    private val query by argument(name = "query")
    private val page by option("--page", help = "Result page (default 1)").int().default(1)

    override fun run() {
        try {
            val result = SourceBrowser.search(sourceId, query, page)
            if (result.mangas.isEmpty()) {
                echo(Style.dim("No results."))
                return
            }
            result.mangas.forEach { m ->
                echo("${Style.title(m.title)}")
                echo("    ${Style.label("url")} ${Style.dim(m.url)}")
            }
            echo("")
            val more = if (result.hasNextPage) Style.dim("  (more pages available, use --page ${page + 1})") else ""
            echo(Style.ok("${result.mangas.size} result(s).") + more)
        } catch (e: Exception) {
            echo(Style.warn("Search failed: ${e.message}"), err = true)
            throw ProgramResult(1)
        }
    }
}

/** `mu manga` — inspect a single manga. */
class MangaCommand : CliktCommand(name = "manga") {
    override fun help(context: Context) = "Inspect manga details and chapters"
    override fun run() = Unit
}

/** `mu manga info <sourceId> <url>` — details + chapter list. */
class MangaInfoCommand : CliktCommand(name = "info") {
    override fun help(context: Context) = "Show manga details and chapter list"

    private val sourceId by argument(name = "sourceId").long()
    private val url by argument(name = "url", help = "Manga url as returned by search")

    override fun run() {
        try {
            val (manga, chapters) = SourceBrowser.details(sourceId, url)
            echo(Style.heading(manga.title))
            manga.author?.takeIf { it.isNotBlank() }?.let { echo("${Style.label("Author:")} $it") }
            manga.artist?.takeIf { it.isNotBlank() }?.let { echo("${Style.label("Artist:")} $it") }
            echo("${Style.label("Status:")} ${SourceBrowser.statusLabel(manga.status)}")
            manga.genre?.takeIf { it.isNotBlank() }?.let { echo("${Style.label("Genres:")} ${Style.dim(it)}") }
            manga.description?.takeIf { it.isNotBlank() }?.let {
                echo("")
                echo(it.trim().take(500))
            }
            echo("")
            echo(Style.heading("Chapters (${chapters.size}):"))
            chapters.forEach { ch ->
                val scan = ch.scanlator?.takeIf { it.isNotBlank() }?.let { Style.dim(" [$it]") } ?: ""
                echo("  ${Style.title(ch.name)}$scan")
                echo("      ${Style.dim(ch.url)}")
            }
        } catch (e: Exception) {
            echo(Style.warn("Failed to load manga: ${e.message}"), err = true)
            throw ProgramResult(1)
        }
    }
}

fun MangaCommand.withSubcommands(): MangaCommand = subcommands(MangaInfoCommand())
