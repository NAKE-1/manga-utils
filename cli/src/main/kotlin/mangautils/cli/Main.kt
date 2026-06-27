/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import mangautils.core.BuildInfo

/**
 * Root command. Holds no behaviour itself — it only groups subcommands.
 * Phase 0 ships `version`; later phases add `ext`, `source`, `search`,
 * `manga`, `library`, `mirror`, `download`, `status`, `logs`.
 */
class Mu : CliktCommand(name = "mu") {
    override fun help(context: Context) =
        "manga-utils - headless manga downloader engine (reuses Tachiyomi/Mihon extensions)"

    override fun run() = Unit
}

/** `mu version` — prints engine name + version. */
class VersionCommand : CliktCommand(name = "version") {
    override fun help(context: Context) = "Print version information"

    override fun run() {
        echo("${BuildInfo.NAME} ${BuildInfo.VERSION}")
    }
}

fun main(args: Array<String>) {
    // No args (or `mu menu`) → launch the friendly guided menu instead of just printing help.
    if (args.isEmpty() || (args.size == 1 && args[0] == "menu")) {
        Interactive.run()
        kotlin.system.exitProcess(0)
    }

    Mu()
        .subcommands(
            VersionCommand(),
            ExtCommand().withSubcommands(),
            SourceCommand().withSubcommands(),
            SearchCommand(),
            MangaCommand().withSubcommands(),
            DownloadCommand(),
            StatusCommand(),
            LibraryCommand().withSubcommands(),
            ConfigCommand().withSubcommands(),
        )
        .main(args)
    // The extension runtime (GraalVM/AndroidCompat/Koin) leaves non-daemon threads alive,
    // which would otherwise keep the JVM running after the command finishes. Clikt already
    // exits the process on errors; force a clean exit on the success path too.
    kotlin.system.exitProcess(0)
}
