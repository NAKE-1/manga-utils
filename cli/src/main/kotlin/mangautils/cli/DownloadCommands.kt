/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.float
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import mangautils.core.config.SettingsStore
import mangautils.core.download.ChapterSelect
import mangautils.core.download.DownloadManager
import mangautils.core.download.ExistingDecision
import mangautils.core.download.ExistingPolicy
import mangautils.core.download.ExistingPrompt
import mangautils.core.download.SourceRef
import mangautils.core.status.JobState

/**
 * `mu download <sourceId> <mangaUrl> [--first N | --latest N | --chapter X ...] [--mirror sid:url ...]`
 * Downloads chapters to CBZ, cascading to mirror sources on failure.
 */
class DownloadCommand : CliktCommand(name = "download") {
    override fun help(context: Context) = "Download chapters to CBZ (with multi-source fallback)"

    private val sourceId by argument(name = "sourceId").long()
    private val mangaUrl by argument(name = "mangaUrl")

    private val first by option("--first", help = "Download the oldest N chapters").int()
    private val latest by option("--latest", help = "Download the newest N chapters").int()
    private val chapters by option("--chapter", help = "Download specific chapter number(s)").float().multiple()
    private val all by option("--all", help = "Download all chapters").flag()
    private val missing by option("--missing", help = "Download only chapters not already on disk").flag()
    private val range by option("--range", help = "Download chapters in number range, e.g. 1-10")
    private val existing by option("--existing", help = "If a chapter exists: skip|replace|ask")
    private val mirrors by option(
        "--mirror",
        help = "Fallback source as sourceId:mangaUrl (repeatable)",
    ).multiple()

    override fun run() {
        val select =
            when {
                chapters.isNotEmpty() -> ChapterSelect.Numbers(chapters.toSet())
                range != null -> parseRange(range!!)
                all -> ChapterSelect.All
                missing -> ChapterSelect.Missing
                latest != null -> ChapterSelect.Latest(latest!!)
                first != null -> ChapterSelect.First(first!!)
                else -> ChapterSelect.First(1)
            }
        val mirrorRefs =
            mirrors.map { spec ->
                val i = spec.indexOf(':')
                require(i > 0) { "Invalid --mirror '$spec' (expected sourceId:mangaUrl)" }
                SourceRef(spec.substring(0, i).trim().toLong(), spec.substring(i + 1).trim())
            }

        val settings = SettingsStore.get()
        val policy = existing?.let { ExistingPolicy.from(it) ?: error("--existing must be skip|replace|ask") } ?: settings.existingBehavior

        echo(Style.dim("Starting download (this boots the source and fetches pages)..."))
        val job =
            DownloadManager(
                concurrency = settings.downloadConcurrency,
                retries = settings.downloadRetries,
                listener = CliProgress.listener,
                existingPolicy = policy,
                existingPrompt = if (policy == ExistingPolicy.ASK) cliExistingPrompt() else null,
            ).download(SourceRef(sourceId, mangaUrl), mirrorRefs, select)

        echo("")
        val ok = job.attempts.count { it.outcome == "ok" }
        val skipped = job.attempts.count { it.outcome == "skipped" }
        val failed = job.attempts.count { it.outcome == "failed" }
        job.attempts.forEach { a ->
            val tag =
                when (a.outcome) {
                    "ok" -> Style.ok("ok  ")
                    "skipped" -> Style.lang("skip")
                    else -> Style.warn("fail")
                }
            echo("  $tag ${Style.dim("src ${a.sourceId}")}  ${Style.title(a.target)}  ${Style.dim(a.message)}")
        }
        echo("")
        val state = if (job.state == JobState.DONE) Style.ok(job.state.name) else Style.warn(job.state.name)
        echo(
            "Job ${Style.id(job.id)} $state  " +
                "(${Style.ok("$ok ok")}, ${Style.lang("$skipped skipped")}, ${Style.warn("$failed failed")})",
        )
        echo(Style.dim("See: mu status ${job.id}"))
        if (job.state != JobState.DONE) throw ProgramResult(1)
    }
}

/** Parse a "A-B" chapter-number range. */
fun parseRange(spec: String): ChapterSelect.Range {
    val parts = spec.split("-", "..").map { it.trim() }
    require(parts.size == 2) { "range must look like 1-10" }
    val from = parts[0].toFloatOrNull() ?: error("invalid range start '${parts[0]}'")
    val to = parts[1].toFloatOrNull() ?: error("invalid range end '${parts[1]}'")
    return ChapterSelect.Range(minOf(from, to), maxOf(from, to))
}

/** A readLine-based prompt for the ASK existing-file policy. */
fun cliExistingPrompt(): ExistingPrompt =
    ExistingPrompt { name, _ ->
        print("\n  '$name' already exists. [s]kip / [r]eplace / [sa] skip-all / [ra] replace-all? ")
        System.out.flush()
        when (readlnOrNull()?.trim()?.lowercase()) {
            "r", "replace" -> ExistingDecision.REPLACE
            "sa", "skipall" -> ExistingDecision.SKIP_ALL
            "ra", "replaceall" -> ExistingDecision.REPLACE_ALL
            else -> ExistingDecision.SKIP
        }
    }
