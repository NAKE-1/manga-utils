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
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import mangautils.core.source.SourceManager
import mangautils.core.source.SourcePreferences

/** `mu source` — inspect the sources provided by installed extensions. */
class SourceCommand : CliktCommand(name = "source") {
    override fun help(context: Context) = "Inspect sources from installed extensions"
    override fun run() = Unit
}

class SourceListCommand : CliktCommand(name = "list") {
    override fun help(context: Context) = "List loaded sources (id, lang, name)"

    override fun run() {
        val sources = SourceManager.listInstalledSources()
        if (sources.isEmpty()) {
            echo("No sources. Install an extension first: mu ext install <pkg>")
            return
        }
        sources.forEach { echo("${it.id}  ${it.lang.padEnd(5)} ${it.name}") }
        echo("")
        echo("${sources.size} source(s).")
    }
}

/**
 * `mu source config <id>` lists a source's own preferences (quality, language, …).
 * `mu source config <id> <index> <value>` changes one. Values persist for that source.
 */
class SourceConfigCommand : CliktCommand(name = "config") {
    override fun help(context: Context) = "View or change a source's own settings (quality, language, ...)"

    private val sourceId by argument(name = "sourceId").long()
    private val index by argument(name = "index", help = "Preference number to change").int().optional()
    private val value by argument(name = "value", help = "New value (true|false, a list value, or text)").optional()

    override fun run() {
        // Set mode
        if (index != null) {
            val v = value ?: run { echo(Style.warn("Provide a value to set."), err = true); throw ProgramResult(1) }
            val err = SourcePreferences.set(sourceId, index!!, v)
            if (err != null) {
                echo(Style.warn("Cannot set: $err"), err = true)
                throw ProgramResult(1)
            }
            echo(Style.ok("Saved. Source $sourceId preference #$index = $v"))
            return
        }

        // List mode
        val prefs =
            try {
                SourcePreferences.list(sourceId)
            } catch (e: Exception) {
                echo(Style.warn("Could not read settings: ${e.message}"), err = true)
                throw ProgramResult(1)
            }
        when {
            prefs == null -> {
                echo(Style.warn("Source $sourceId is not installed."), err = true)
                throw ProgramResult(1)
            }
            prefs.isEmpty() -> echo(Style.dim("This source has no configurable settings."))
            else -> {
                prefs.forEach { p ->
                    val current = displayValue(p)
                    echo("${Style.id(p.index.toString())}. ${Style.title(p.title)}  ${Style.dim("[${p.type}]")} ${if (!p.enabled) Style.dim("(disabled)") else ""}")
                    p.summary?.let { echo("    ${Style.dim(it)}") }
                    echo("    ${Style.label("current:")} ${Style.lang(current)}")
                    val entries = p.entries
                    val entryValues = p.entryValues
                    if (entries != null && entryValues != null && entries.size == entryValues.size) {
                        val opts = entries.indices.joinToString("  ") { "${entryValues[it]}=${entries[it]}" }
                        echo("    ${Style.label("options:")} ${Style.dim(opts)}")
                    }
                }
                echo("")
                echo(Style.dim("Change with: mu source config $sourceId <number> <value>"))
            }
        }
    }

    private fun displayValue(p: mangautils.core.source.SourcePref): String {
        val entries = p.entries
        val entryValues = p.entryValues
        if (entries != null && entryValues != null) {
            val i = entryValues.indexOf(p.value)
            if (i >= 0) return "${p.value} (${entries[i]})"
        }
        return p.value.ifBlank { "(default)" }
    }
}

fun SourceCommand.withSubcommands(): SourceCommand = subcommands(SourceListCommand(), SourceConfigCommand())
