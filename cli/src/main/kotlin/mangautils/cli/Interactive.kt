/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.cli

import mangautils.core.config.SettingsStore
import mangautils.core.download.ChapterSelect
import mangautils.core.download.DownloadManager
import mangautils.core.download.ExistingPolicy
import mangautils.core.download.SourceRef
import mangautils.core.extension.ExtensionInstaller
import mangautils.core.extension.ExtensionRepoClient
import mangautils.core.extension.ExtensionUpdates
import mangautils.core.extension.InstalledStore
import mangautils.core.extension.RepoStore
import mangautils.core.library.LibraryService
import mangautils.core.source.SourceBrowser
import mangautils.core.source.SourceManager
import mangautils.core.source.SourcePreferences

/**
 * Lightweight guided, readLine-based menu — the "friendly" front-end for users who don't want
 * to type ids/urls. Launched by `mu` with no args or `mu menu`. (The full gum TUI with images
 * comes later; raw subcommands remain for power users.)
 */
object Interactive {
    fun run() {
        println(Style.heading("manga-utils") + Style.dim("  - interactive mode (raw CLI still available; try `mu --help`)"))
        loop@ while (true) {
            when (
                menu(
                    "Main menu",
                    listOf("Search & download", "Library (followed series)", "Extensions & repos", "Settings"),
                )
            ) {
                1 -> searchFlow()
                2 -> libraryFlow()
                3 -> extensionsFlow()
                4 -> settingsFlow()
                else -> {
                    println(Style.dim("Bye."))
                    break@loop
                }
            }
        }
    }

    // ---- flows -----------------------------------------------------------------------------

    private fun searchFlow() {
        val sources = SourceManager.listInstalledSources()
        if (sources.isEmpty()) {
            println(Style.warn("No sources installed. Use Extensions & repos to install one first."))
            return
        }
        val source = pick("Choose a source", sources) { "${it.name} ${Style.dim("[${it.lang}]")}" } ?: return
        val query = ask("Search ${source.name} for") ?: return
        val results =
            runCatching { SourceBrowser.search(source.id, query).mangas }
                .getOrElse {
                    println(Style.warn("Search failed: ${it.message}"))
                    return
                }
        if (results.isEmpty()) {
            println(Style.dim("No results."))
            return
        }
        val manga = pick("Choose a manga", results) { it.title } ?: return

        val details =
            runCatching { SourceBrowser.details(source.id, manga.url) }
                .getOrElse {
                    println(Style.warn("Failed to load details: ${it.message}"))
                    return
                }
        // Prefer the title from the search result (details often omits it).
        val title = runCatching { manga.title }.getOrNull()?.takeIf { it.isNotBlank() } ?: details.manga.title
        println(Style.heading(title))
        details.manga.author?.takeIf { it.isNotBlank() }?.let { println("${Style.label("Author:")} $it") }
        println("${Style.label("Status:")} ${SourceBrowser.statusLabel(details.manga.status)}  ${Style.dim("${details.chapters.size} chapters")}")

        while (true) {
            when (
                menu("Action", listOf("Download all", "Download a range (A-B)", "Download missing", "Follow this series"))
            ) {
                1 -> { runDownload(source.id, manga.url, ChapterSelect.All); return }
                2 -> {
                    val r = ask("Range, e.g. 1-10") ?: return
                    runCatching { runDownload(source.id, manga.url, parseRange(r)) }
                        .onFailure { println(Style.warn(it.message ?: "bad range")) }
                    return
                }
                3 -> { runDownload(source.id, manga.url, ChapterSelect.Missing); return }
                4 -> {
                    LibraryService.add(source.id, manga.url)
                    println(Style.ok("Now following ${details.manga.title}."))
                    return
                }
                else -> return
            }
        }
    }

    private fun libraryFlow() {
        val entries = LibraryService.list()
        if (entries.isEmpty()) {
            println(Style.dim("Library is empty. Follow a series from Search & download."))
            return
        }
        val entry = pick("Choose a series", entries) { "${it.title} ${Style.dim("(${it.knownChapters.size} ch)")}" } ?: return
        when (menu("Action for ${entry.title}", listOf("Check for new chapters", "Download missing", "Unfollow"))) {
            1 -> {
                val res = LibraryService.update(listOf(entry)).firstOrNull()
                val n = res?.newChapters?.size ?: 0
                if (n == 0) {
                    println(Style.ok("Up to date."))
                } else {
                    println(Style.ok("$n new chapter(s):"))
                    res!!.newChapters.sortedBy { it.number }.forEach { println("    ${it.name}") }
                    if (confirm("Download them now?")) {
                        runDownload(entry.sourceId, entry.mangaUrl, ChapterSelect.Urls(res.newChapters.map { it.url }.toSet()))
                    }
                }
            }
            2 -> runDownload(entry.sourceId, entry.mangaUrl, ChapterSelect.Missing)
            3 -> {
                LibraryService.remove(entry.sourceId, entry.mangaUrl)
                println(Style.ok("Unfollowed ${entry.title}."))
            }
            else -> {}
        }
    }

    private fun extensionsFlow() {
        when (
            menu(
                "Extensions & repos",
                listOf(
                    "List installed",
                    "Search & install",
                    "Check for updates",
                    "Add a repo",
                    "Add default (Keiyoushi) repo",
                    "Configure a source (quality/language/...)",
                ),
            )
        ) {
            1 -> {
                val installed = InstalledStore.list()
                if (installed.isEmpty()) println(Style.dim("None installed.")) else
                    installed.forEach { println("  ${Style.title(it.name)}  ${Style.dim("v${it.versionName}  ${it.sources.size} src")}") }
            }
            2 -> installFlow()
            3 -> {
                println(Style.dim("Checking..."))
                val updates = ExtensionUpdates.check()
                if (updates.isEmpty()) {
                    println(Style.ok("All extensions up to date."))
                } else {
                    updates.forEach { println("  ${Style.title(it.entry.name)}  ${Style.warn("v${it.installed.versionName} -> v${it.entry.version}")}") }
                    println(Style.dim("Out-of-date extensions can break when their site changes."))
                    if (confirm("Update all ${updates.size} now?")) {
                        val installer = ExtensionInstaller()
                        updates.forEach {
                            runCatching { installer.install(it.entry, it.indexUrl) }
                                .onSuccess { _ -> println(Style.ok("updated ${it.entry.name}")) }
                                .onFailure { e -> println(Style.warn("failed ${it.entry.name}: ${e.message}")) }
                        }
                    }
                }
            }
            4 -> {
                val url = ask("Repo index URL") ?: return
                val entries = runCatching { ExtensionRepoClient().fetchIndex(url.trim()) }.getOrNull()
                if (entries.isNullOrEmpty()) {
                    println(Style.warn("Not a valid repo (no extensions found)."))
                } else {
                    RepoStore.add(url.trim())
                    println(Style.ok("Added (${entries.size} extensions)."))
                }
            }
            5 -> {
                RepoStore.add("https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json")
                println(Style.ok("Added Keiyoushi repo."))
            }
            6 -> configureSourceFlow()
            else -> {}
        }
    }

    private fun configureSourceFlow() {
        val sources = SourceManager.listInstalledSources()
        if (sources.isEmpty()) {
            println(Style.dim("No sources installed."))
            return
        }
        val src = pick("Choose a source to configure", sources) { "${it.name} ${Style.dim("[${it.lang}]")}" } ?: return
        while (true) {
            val prefs =
                try {
                    SourcePreferences.list(src.id)
                } catch (e: Exception) {
                    println(Style.warn("Could not read settings: ${e.message}"))
                    return
                }
            if (prefs.isNullOrEmpty()) {
                println(Style.dim("This source has no configurable settings."))
                return
            }
            println()
            println(Style.heading("${src.name} settings:"))
            prefs.forEach { p ->
                println("  ${Style.id(p.index.toString())}. ${Style.title(p.title)}  ${Style.lang(p.value.ifBlank { "(default)" })}  ${Style.dim("[${p.type}]")}")
                val ev = p.entryValues
                val en = p.entries
                if (ev != null && en != null && ev.size == en.size) {
                    println("       ${Style.dim(ev.indices.joinToString("  ") { "${ev[it]}=${en[it]}" })}")
                }
            }
            print(Style.label("Pick a number to change (0 to go back) > "))
            System.out.flush()
            val n = readlnOrNull()?.trim()?.toIntOrNull() ?: 0
            if (n <= 0 || n > prefs.size) return
            val newVal = ask("New value (true|false, a list value, or comma-separated for sets)") ?: continue
            val err = SourcePreferences.set(src.id, prefs[n - 1].index, newVal)
            println(if (err == null) Style.ok("Saved.") else Style.warn(err))
        }
    }

    private fun installFlow() {
        if (RepoStore.list().isEmpty()) {
            println(Style.warn("No repos. Add one first (option 4 or 5)."))
            return
        }
        val name = ask("Extension name to search") ?: return
        val nsfw = SettingsStore.get().nsfwVisible
        val client = ExtensionRepoClient()
        val matches =
            RepoStore.list().flatMap { repo ->
                runCatching { client.fetchIndex(repo) }.getOrDefault(emptyList())
                    .filter { (nsfw || !it.isNsfw) && it.name.contains(name, ignoreCase = true) }
                    .map { it to repo }
            }.sortedBy { it.first.name.lowercase() }
        if (matches.isEmpty()) {
            println(Style.dim("No matches. (Tip: enable NSFW in Settings if you expect adult sources.)"))
            return
        }
        val chosen = pick("Choose an extension", matches) { "${it.first.name} ${Style.dim("[${it.first.lang}] v${it.first.version}")}" } ?: return
        println(Style.dim("Installing ${chosen.first.name}..."))
        runCatching { ExtensionInstaller().install(chosen.first, chosen.second) }
            .onSuccess { ext -> println(Style.ok("Installed ${ext.name} (${ext.sources.size} source(s)).")) }
            .onFailure { println(Style.warn("Install failed: ${it.message}")) }
    }

    private fun settingsFlow() {
        while (true) {
            val rows = SettingsStore.describe()
            println(Style.heading("Settings:"))
            rows.forEachIndexed { i, (k, v) -> println("  ${Style.id("${i + 1}")}. ${Style.label(k)} = ${Style.title(v.ifBlank { "(unset)" })}") }
            print(Style.label("Pick a number to change (0 to go back) > "))
            System.out.flush()
            val n = readlnOrNull()?.trim()?.toIntOrNull() ?: 0
            if (n <= 0 || n > rows.size) return
            val key = rows[n - 1].first
            val value = ask("New value for $key") ?: continue
            val err = SettingsStore.setByKey(key, value)
            println(if (err == null) Style.ok("Saved.") else Style.warn(err))
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun runDownload(
        sourceId: Long,
        url: String,
        select: ChapterSelect,
    ) {
        val s = SettingsStore.get()
        val job =
            DownloadManager(
                concurrency = s.downloadConcurrency,
                retries = s.downloadRetries,
                listener = CliProgress.listener,
                existingPolicy = s.existingBehavior,
                existingPrompt = if (s.existingBehavior == ExistingPolicy.ASK) cliExistingPrompt() else null,
            ).download(SourceRef(sourceId, url), emptyList(), select)
        val ok = job.attempts.count { it.outcome == "ok" }
        val skipped = job.attempts.count { it.outcome == "skipped" }
        val failed = job.attempts.count { it.outcome == "failed" }
        println("${Style.ok("$ok ok")}, ${Style.lang("$skipped skipped")}, ${Style.warn("$failed failed")}")
    }

    private fun menu(
        title: String,
        options: List<String>,
    ): Int {
        println()
        println(Style.heading(title))
        options.forEachIndexed { i, o -> println("  ${Style.id("${i + 1}")}. $o") }
        println("  ${Style.id("0")}. Back / Quit")
        print(Style.label("> "))
        System.out.flush()
        return readlnOrNull()?.trim()?.toIntOrNull() ?: 0
    }

    private fun <T> pick(
        title: String,
        items: List<T>,
        render: (T) -> String,
    ): T? {
        println()
        println(Style.heading(title))
        items.take(40).forEachIndexed { i, it -> println("  ${Style.id("${i + 1}")}. ${render(it)}") }
        print(Style.label("Pick a number (0 to cancel) > "))
        System.out.flush()
        val n = readlnOrNull()?.trim()?.toIntOrNull() ?: 0
        return items.getOrNull(n - 1)
    }

    private fun ask(label: String): String? {
        print(Style.label("$label: "))
        System.out.flush()
        return readlnOrNull()?.trim()?.ifBlank { null }
    }

    private fun confirm(label: String): Boolean {
        print(Style.label("$label [y/N] "))
        System.out.flush()
        return readlnOrNull()?.trim()?.lowercase() in listOf("y", "yes")
    }
}
