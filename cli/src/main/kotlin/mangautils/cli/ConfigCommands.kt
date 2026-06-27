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
import mangautils.core.config.SettingsStore

/** `mu config` — view and change app settings (data/settings.json). */
class ConfigCommand : CliktCommand(name = "config") {
    override fun help(context: Context) = "View and change app settings"
    override fun run() = Unit
}

class ConfigListCommand : CliktCommand(name = "list") {
    override fun help(context: Context) = "Show all settings and their values"

    override fun run() {
        val pad = SettingsStore.describe().maxOf { it.first.length }
        SettingsStore.describe().forEach { (k, v) ->
            echo("${Style.label(k.padEnd(pad))}  ${Style.title(v.ifBlank { "(unset)" })}")
        }
        echo("")
        echo(Style.dim("Change with: mu config set <key> <value>   (valid: skip|replace|ask, true|false, ints)"))
    }
}

class ConfigGetCommand : CliktCommand(name = "get") {
    override fun help(context: Context) = "Print one setting's value"

    private val key by argument(name = "key")

    override fun run() {
        val v = SettingsStore.getByKey(key)
        if (v == null) {
            echo(Style.warn("Unknown setting '$key'"), err = true)
            throw ProgramResult(1)
        }
        echo(v)
    }
}

class ConfigSetCommand : CliktCommand(name = "set") {
    override fun help(context: Context) = "Change a setting"

    private val key by argument(name = "key")
    private val value by argument(name = "value")

    override fun run() {
        val err = SettingsStore.setByKey(key, value)
        if (err != null) {
            echo(Style.warn("Cannot set $key: $err"), err = true)
            throw ProgramResult(1)
        }
        echo("${Style.ok("set")} ${Style.label(key)} = ${Style.title(value)}")
    }
}

class ConfigResetCommand : CliktCommand(name = "reset") {
    override fun help(context: Context) = "Reset all settings to defaults"

    override fun run() {
        SettingsStore.reset()
        echo(Style.ok("Settings reset to defaults."))
    }
}

fun ConfigCommand.withSubcommands(): ConfigCommand =
    subcommands(ConfigListCommand(), ConfigGetCommand(), ConfigSetCommand(), ConfigResetCommand())
