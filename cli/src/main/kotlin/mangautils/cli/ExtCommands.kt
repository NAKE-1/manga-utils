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
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import mangautils.core.config.SettingsStore
import mangautils.core.extension.ExtensionInstaller
import mangautils.core.extension.ExtensionRepoClient
import mangautils.core.extension.ExtensionUpdates
import mangautils.core.extension.InstalledStore
import mangautils.core.extension.RepoStore

/** `mu ext` — manage extension repositories and (later) installed extensions. */
class ExtCommand : CliktCommand(name = "ext") {
    override fun help(context: Context) = "Manage extension repositories and extensions"
    override fun run() = Unit
}

/** `mu ext repo` — manage the list of extension repository index URLs. */
class ExtRepoCommand : CliktCommand(name = "repo") {
    override fun help(context: Context) = "Manage extension repository index URLs"
    override fun run() = Unit
}

class ExtRepoAddCommand : CliktCommand(name = "add") {
    override fun help(context: Context) =
        "Add an extension repository index URL (e.g. .../index.min.json)"

    private val url by argument(name = "url", help = "URL of the repo index.min.json")

    override fun run() {
        val u = url.trim()
        echo(Style.dim("Validating $u ..."))
        val entries =
            try {
                ExtensionRepoClient().fetchIndex(u)
            } catch (e: Exception) {
                echo(Style.warn("Not a valid extension repo: ${e.message}"), err = true)
                throw ProgramResult(1)
            }
        if (entries.isEmpty()) {
            echo(Style.warn("That index has no extensions; not adding."), err = true)
            throw ProgramResult(1)
        }
        if (RepoStore.add(u)) {
            echo("${Style.ok("Added repo")} ${Style.dim("($u, ${entries.size} extensions)")}")
        } else {
            echo(Style.dim("Repo already present: $u"))
        }
    }
}

/** `mu ext repo add-default` — add the well-known Keiyoushi repo in one step. */
class ExtRepoAddDefaultCommand : CliktCommand(name = "add-default") {
    override fun help(context: Context) = "Add the default Keiyoushi extension repository"

    override fun run() {
        val u = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"
        if (RepoStore.add(u)) echo("${Style.ok("Added")} Keiyoushi repo") else echo(Style.dim("Keiyoushi repo already present"))
    }
}

class ExtRepoListCommand : CliktCommand(name = "list") {
    override fun help(context: Context) = "List added extension repositories"

    override fun run() {
        val repos = RepoStore.list()
        if (repos.isEmpty()) {
            echo("No repositories added. Add one with: mu ext repo add <index-url>")
            return
        }
        repos.forEachIndexed { i, url -> echo("${i + 1}. $url") }
    }
}

class ExtRepoRemoveCommand : CliktCommand(name = "remove") {
    override fun help(context: Context) = "Remove an extension repository index URL"

    private val url by argument(name = "url", help = "URL to remove")

    override fun run() {
        if (RepoStore.remove(url.trim())) echo("Removed: $url") else echo("Not found: $url")
    }
}

/** `mu ext list` — list extensions available across the added repositories. */
class ExtListCommand : CliktCommand(name = "list") {
    override fun help(context: Context) = "List available extensions from added repositories"

    private val repoUrl by option("--repo", help = "Use only this repo index URL")
    private val nsfw by option("--nsfw", help = "Include NSFW extensions").flag(default = SettingsStore.get().nsfwVisible)
    private val filter by argument(name = "filter", help = "Optional name/lang substring filter").optional()

    override fun run() {
        val repos = repoUrl?.let { listOf(it) } ?: RepoStore.list()
        if (repos.isEmpty()) {
            echo("No repositories added. Add one with: mu ext repo add <index-url>")
            return
        }
        val client = ExtensionRepoClient()
        var shown = 0
        for (repo in repos) {
            val entries =
                try {
                    client.fetchIndex(repo)
                } catch (e: Exception) {
                    echo("! Failed to fetch $repo: ${e.message}", err = true)
                    continue
                }
            for (e in entries.sortedBy { it.name.lowercase() }) {
                if (!nsfw && e.isNsfw) continue
                val needle = filter?.lowercase()
                if (needle != null &&
                    !e.name.lowercase().contains(needle) &&
                    !e.lang.lowercase().contains(needle)
                ) {
                    continue
                }
                val flag = if (e.isNsfw) " [18+]" else ""
                echo("${e.lang.padEnd(5)} ${e.name}  (v${e.version})  ${e.pkg}$flag")
                shown++
            }
        }
        echo("")
        echo("$shown extension(s).")
    }
}

/** `mu ext install <pkg>` — download, translate and register an extension's sources. */
class ExtInstallCommand : CliktCommand(name = "install") {
    override fun help(context: Context) = "Install an extension by package name (from added repos)"

    private val pkg by argument(name = "pkg", help = "Extension package, e.g. eu.kanade.tachiyomi.extension.en.foo")

    override fun run() {
        echo("Installing $pkg ... (first run boots the extension runtime; this can take a moment)")
        try {
            val ext = ExtensionInstaller().install(pkg.trim())
            echo("Installed: ${ext.name}  v${ext.versionName}  (${ext.pkg})")
            if (ext.sources.isEmpty()) {
                echo("  (no sources enumerated)")
            } else {
                ext.sources.forEach { echo("  source ${it.id}  ${it.name} [${it.lang}]") }
            }
        } catch (e: Exception) {
            echo("Install failed: ${e.message}", err = true)
            throw ProgramResult(1)
        }
    }
}

/** `mu ext installed` — list installed extensions. */
class ExtInstalledCommand : CliktCommand(name = "installed") {
    override fun help(context: Context) = "List installed extensions"

    override fun run() {
        val installed = InstalledStore.list()
        if (installed.isEmpty()) {
            echo("No extensions installed. Install one with: mu ext install <pkg>")
            return
        }
        installed.forEach { e ->
            val flag = if (e.nsfw) " [18+]" else ""
            echo("${e.name}  v${e.versionName}  (${e.pkg})  ${e.sources.size} source(s)$flag")
        }
    }
}

/** `mu ext uninstall <pkg>` — remove an installed extension. */
class ExtUninstallCommand : CliktCommand(name = "uninstall") {
    override fun help(context: Context) = "Remove an installed extension"

    private val pkg by argument(name = "pkg")

    override fun run() {
        if (InstalledStore.remove(pkg.trim())) echo("Uninstalled: $pkg") else echo("Not installed: $pkg")
    }
}

/** `mu ext search <name>` — find available extensions by name (alias over the filtered index). */
class ExtSearchCommand : CliktCommand(name = "search") {
    override fun help(context: Context) = "Search available extensions by name"

    private val nsfw by option("--nsfw", help = "Include NSFW extensions").flag(default = SettingsStore.get().nsfwVisible)
    private val query by argument(name = "name")

    override fun run() {
        val repos = RepoStore.list()
        if (repos.isEmpty()) {
            echo(Style.dim("No repositories. Add one: mu ext repo add-default"))
            return
        }
        val client = ExtensionRepoClient()
        val needle = query.lowercase()
        var shown = 0
        repos.forEach { repo ->
            runCatching { client.fetchIndex(repo) }.getOrDefault(emptyList())
                .filter { (nsfw || !it.isNsfw) && it.name.lowercase().contains(needle) }
                .sortedBy { it.name.lowercase() }
                .forEach { e ->
                    echo("${Style.lang(e.lang.padEnd(5))} ${Style.title(e.name)}  ${Style.dim("v${e.version}  ${e.pkg}")}")
                    shown++
                }
        }
        echo("")
        echo(Style.ok("$shown match(es)."))
        if (shown > 0) echo(Style.dim("Install with: mu ext install <pkg>"))
    }
}

/** `mu ext outdated` — list installed extensions with a newer version in a repo. */
class ExtOutdatedCommand : CliktCommand(name = "outdated") {
    override fun help(context: Context) = "List installed extensions that have updates available"

    override fun run() {
        echo(Style.dim("Checking for extension updates..."))
        val updates = ExtensionUpdates.check()
        if (updates.isEmpty()) {
            echo(Style.ok("All extensions are up to date."))
            return
        }
        updates.forEach { u ->
            echo("${Style.title(u.entry.name)}  ${Style.warn("v${u.installed.versionName} -> v${u.entry.version}")}  ${Style.dim(u.entry.pkg)}")
        }
        echo("")
        echo(Style.warn("${updates.size} update(s) available.") + Style.dim("  Update with: mu ext update --all"))
    }
}

/** `mu ext update [pkg] [--all]` — re-install outdated extensions (with confirmation). */
class ExtUpdateCommand : CliktCommand(name = "update") {
    override fun help(context: Context) = "Update installed extensions to the latest version"

    private val pkg by argument(name = "pkg", help = "Specific package to update").optional()
    private val all by option("--all", help = "Update every outdated extension").flag()
    private val yes by option("--yes", "-y", help = "Skip the confirmation prompt").flag()

    override fun run() {
        val updates = ExtensionUpdates.check()
        val targets =
            when {
                pkg != null -> updates.filter { it.entry.pkg == pkg }
                all -> updates
                else -> updates
            }
        if (targets.isEmpty()) {
            echo(Style.ok("Nothing to update."))
            return
        }
        echo(Style.heading("Updates available:"))
        targets.forEach { u -> echo("  ${Style.title(u.entry.name)}  ${Style.warn("v${u.installed.versionName} -> v${u.entry.version}")}") }
        echo("")
        echo(Style.dim("An out-of-date extension can stop working when its site changes; updating is recommended."))
        if (!yes) {
            print(Style.label("Update ${targets.size} extension(s) now? [y/N] "))
            System.out.flush()
            if (readlnOrNull()?.trim()?.lowercase() !in listOf("y", "yes")) {
                echo("Cancelled.")
                return
            }
        }
        val installer = ExtensionInstaller()
        var ok = 0
        targets.forEach { u ->
            try {
                installer.install(u.entry, u.indexUrl)
                echo("${Style.ok("updated")} ${u.entry.name} -> v${u.entry.version}")
                ok++
            } catch (e: Exception) {
                echo(Style.warn("failed: ${u.entry.name}: ${e.message}"), err = true)
            }
        }
        echo("")
        echo(Style.ok("$ok of ${targets.size} updated."))
    }
}

fun ExtCommand.withSubcommands(): ExtCommand =
    subcommands(
        ExtRepoCommand().subcommands(
            ExtRepoAddCommand(),
            ExtRepoAddDefaultCommand(),
            ExtRepoListCommand(),
            ExtRepoRemoveCommand(),
        ),
        ExtListCommand(),
        ExtSearchCommand(),
        ExtInstallCommand(),
        ExtInstalledCommand(),
        ExtOutdatedCommand(),
        ExtUpdateCommand(),
        ExtUninstallCommand(),
    )
