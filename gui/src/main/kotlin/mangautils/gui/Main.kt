/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package mangautils.gui

import mangautils.core.config.SettingsStore
import mangautils.core.download.ChapterSelect
import mangautils.core.download.DownloadListener
import mangautils.core.download.DownloadManager
import mangautils.core.download.ExistingDecision
import mangautils.core.download.ExistingPolicy
import mangautils.core.download.ExistingPrompt
import mangautils.core.download.SourceRef
import mangautils.core.extension.ExtensionInstaller
import mangautils.core.extension.ExtensionRepoClient
import mangautils.core.extension.ExtensionUpdates
import mangautils.core.extension.InstalledStore
import mangautils.core.extension.RepoStore
import mangautils.core.library.LibraryEntry
import mangautils.core.library.LibraryService
import mangautils.core.source.SourceBrowser
import mangautils.core.source.SourceManager
import mangautils.core.source.SourcePreferences
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.border.EmptyBorder

fun main() {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    SwingUtilities.invokeLater { MainWindow().show() }
}

/** A basic Swing test GUI over the engine. Not the eventual polished desktop app — a dev tool. */
class MainWindow {
    private val frame = JFrame("manga-utils — test GUI")
    private val status = JLabel("Idle")
    private val progress = JProgressBar()
    private val logArea = JTextArea(8, 80).apply { isEditable = false }

    // Search-tab selection state
    private var selSourceId: Long = -1
    private var selUrl: String = ""

    fun show() {
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.layout = BorderLayout()

        val tabs = JTabbedPane()
        tabs.addTab("Search & download", searchTab())
        tabs.addTab("Library", libraryTab())
        tabs.addTab("Extensions", extensionsTab())
        tabs.addTab("Settings", settingsTab())
        frame.add(tabs, BorderLayout.CENTER)

        val south = JPanel(BorderLayout())
        val bar = JPanel(BorderLayout()).apply {
            border = EmptyBorder(4, 8, 4, 8)
            add(status, BorderLayout.WEST)
            add(progress, BorderLayout.CENTER)
        }
        south.add(bar, BorderLayout.NORTH)
        south.add(JScrollPane(logArea), BorderLayout.CENTER)
        frame.add(south, BorderLayout.SOUTH)

        frame.preferredSize = Dimension(1000, 720)
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        log("Ready. Tip: install an extension under the Extensions tab, then search.")
    }

    // ---- Search & download tab -------------------------------------------------------------

    private val sourceCombo = JComboBox<SourceItem>()
    private val resultsModel = DefaultListModel<MangaItem>()
    private val resultsList = JList(resultsModel)
    private val detailsArea = JTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }

    private fun searchTab(): JPanel {
        val panel = JPanel(BorderLayout(6, 6)).apply { border = EmptyBorder(8, 8, 8, 8) }

        val queryField = JTextField(28)
        val north = JPanel(FlowLayout(FlowLayout.LEFT))
        north.add(JLabel("Source:"))
        north.add(sourceCombo)
        north.add(button("Reload") { reloadSources() })
        north.add(JLabel("  Query:"))
        north.add(queryField)
        north.add(button("Search") { doSearch(queryField.text) })
        panel.add(north, BorderLayout.NORTH)

        resultsList.addListSelectionListener {
            if (!it.valueIsAdjusting) resultsList.selectedValue?.let { m -> showDetails(m) }
        }
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JScrollPane(resultsList), JScrollPane(detailsArea))
        split.dividerLocation = 360
        panel.add(split, BorderLayout.CENTER)

        val actions = JPanel(FlowLayout(FlowLayout.LEFT))
        actions.add(button("Download all") { download(ChapterSelect.All) })
        actions.add(button("Download missing") { download(ChapterSelect.Missing) })
        actions.add(button("Download range...") {
            val r = JOptionPane.showInputDialog(frame, "Chapter range (e.g. 1-10):") ?: return@button
            runCatching { parseRange(r) }.onSuccess { download(it) }.onFailure { log("Bad range: ${it.message}") }
        })
        actions.add(button("Follow series") { followSelected() })
        panel.add(actions, BorderLayout.SOUTH)

        reloadSources()
        return panel
    }

    private fun reloadSources() {
        bg {
            val sources = SourceManager.listInstalledSources().map { SourceItem(it.id, it.name, it.lang) }
            ui {
                sourceCombo.removeAllItems()
                sources.forEach { sourceCombo.addItem(it) }
                status.text = "${sources.size} source(s) loaded"
            }
        }
    }

    private fun doSearch(query: String) {
        val src = sourceCombo.selectedItem as? SourceItem ?: run { log("Pick a source first."); return }
        if (query.isBlank()) return
        status.text = "Searching..."
        bg {
            val mangas = SourceBrowser.search(src.id, query).mangas.map { MangaItem(it.title, it.url) }
            ui {
                resultsModel.clear()
                mangas.forEach { resultsModel.addElement(it) }
                status.text = "${mangas.size} result(s)"
            }
        }
    }

    private fun showDetails(m: MangaItem) {
        val src = sourceCombo.selectedItem as? SourceItem ?: return
        selSourceId = src.id
        selUrl = m.url
        detailsArea.text = "Loading ${m.title}..."
        bg {
            val d = SourceBrowser.details(src.id, m.url)
            val text = buildString {
                appendLine(m.title)
                d.manga.author?.takeIf { it.isNotBlank() }?.let { appendLine("Author: $it") }
                appendLine("Status: ${SourceBrowser.statusLabel(d.manga.status)}")
                d.manga.genre?.takeIf { it.isNotBlank() }?.let { appendLine("Genres: $it") }
                appendLine("Chapters: ${d.chapters.size}")
                appendLine()
                d.manga.description?.let { appendLine(it.trim()) }
            }
            ui { detailsArea.text = text; detailsArea.caretPosition = 0 }
        }
    }

    private fun followSelected() {
        if (selSourceId < 0) { log("Select a manga first."); return }
        val id = selSourceId; val url = selUrl
        bg {
            val e = LibraryService.add(id, url)
            ui { log("Now following ${e.title} (${e.knownChapters.size} chapters).") }
        }
    }

    private fun download(select: ChapterSelect) {
        if (selSourceId < 0) { log("Select a manga first."); return }
        startDownload(selSourceId, selUrl, select)
    }

    // ---- Library tab -----------------------------------------------------------------------

    private val libModel = DefaultListModel<LibraryEntry>()
    private val libList = JList(libModel)

    private fun libraryTab(): JPanel {
        val panel = JPanel(BorderLayout(6, 6)).apply { border = EmptyBorder(8, 8, 8, 8) }
        libList.cellRenderer = simpleRenderer { "${it.title}   (${it.knownChapters.size} ch, ${it.readingMode})" }
        panel.add(JScrollPane(libList), BorderLayout.CENTER)
        val actions = JPanel(FlowLayout(FlowLayout.LEFT))
        actions.add(button("Refresh") { reloadLibrary() })
        actions.add(button("Check for new chapters") { checkUpdates() })
        actions.add(button("Download missing") {
            libList.selectedValue?.let { startDownload(it.sourceId, it.mangaUrl, ChapterSelect.Missing) } ?: log("Select a series.")
        })
        actions.add(button("Unfollow") {
            libList.selectedValue?.let { LibraryService.remove(it.sourceId, it.mangaUrl); reloadLibrary(); log("Unfollowed ${it.title}.") }
        })
        panel.add(actions, BorderLayout.SOUTH)
        reloadLibrary()
        return panel
    }

    private fun reloadLibrary() {
        bg {
            val entries = LibraryService.list()
            ui { libModel.clear(); entries.forEach { libModel.addElement(it) } }
        }
    }

    private fun checkUpdates() {
        val e = libList.selectedValue ?: run { log("Select a series."); return }
        status.text = "Checking ${e.title}..."
        bg {
            val r = LibraryService.update(listOf(e)).firstOrNull()
            val n = r?.newChapters?.size ?: 0
            ui {
                status.text = "Idle"
                if (n == 0) log("${e.title}: up to date.")
                else {
                    log("${e.title}: $n new chapter(s).")
                    if (JOptionPane.showConfirmDialog(frame, "$n new chapter(s). Download now?", "New chapters", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                        startDownload(e.sourceId, e.mangaUrl, ChapterSelect.Urls(r!!.newChapters.map { it.url }.toSet()))
                    }
                }
                reloadLibrary()
            }
        }
    }

    // ---- Extensions tab --------------------------------------------------------------------

    private val extModel = DefaultListModel<String>()
    private val extList = JList(extModel)

    private fun extensionsTab(): JPanel {
        val panel = JPanel(BorderLayout(6, 6)).apply { border = EmptyBorder(8, 8, 8, 8) }
        panel.add(JScrollPane(extList), BorderLayout.CENTER)
        val actions = JPanel(FlowLayout(FlowLayout.LEFT))
        actions.add(button("Refresh") { reloadExtensions() })
        actions.add(button("Add default repo") { RepoStore.add(KEIYOUSHI); log("Added Keiyoushi repo.") })
        actions.add(button("Add repo...") { addRepo() })
        actions.add(button("Search & install...") { installDialog() })
        actions.add(button("Check updates") { checkExtUpdates() })
        actions.add(button("Configure source...") { configureSourceDialog() })
        panel.add(actions, BorderLayout.SOUTH)
        reloadExtensions()
        return panel
    }

    private fun reloadExtensions() {
        bg {
            val items = InstalledStore.list().map { "${it.name}   v${it.versionName}   (${it.sources.size} src)   ${it.pkg}" }
            ui { extModel.clear(); items.forEach { extModel.addElement(it) } }
        }
    }

    private fun addRepo() {
        val url = JOptionPane.showInputDialog(frame, "Repo index.min.json URL:") ?: return
        status.text = "Validating..."
        bg {
            val entries = runCatching { ExtensionRepoClient().fetchIndex(url.trim()) }.getOrNull()
            ui {
                status.text = "Idle"
                if (entries.isNullOrEmpty()) log("Not a valid repo: $url")
                else { RepoStore.add(url.trim()); log("Added repo (${entries.size} extensions).") }
            }
        }
    }

    private fun installDialog() {
        if (RepoStore.list().isEmpty()) { log("Add a repo first."); return }
        val name = JOptionPane.showInputDialog(frame, "Extension name to search:") ?: return
        status.text = "Searching extensions..."
        bg {
            val nsfw = SettingsStore.get().nsfwVisible
            val client = ExtensionRepoClient()
            val matches = RepoStore.list().flatMap { repo ->
                runCatching { client.fetchIndex(repo) }.getOrDefault(emptyList())
                    .filter { (nsfw || !it.isNsfw) && it.name.contains(name, ignoreCase = true) }
                    .map { it to repo }
            }.sortedBy { it.first.name.lowercase() }
            ui {
                status.text = "Idle"
                if (matches.isEmpty()) { log("No matches (enable NSFW in Settings for adult sources)."); return@ui }
                val labels = matches.map { "${it.first.name} [${it.first.lang}] v${it.first.version}" }.toTypedArray()
                val choice = JOptionPane.showInputDialog(frame, "Install which?", "Install", JOptionPane.PLAIN_MESSAGE, null, labels, labels[0]) as? String ?: return@ui
                val picked = matches[labels.indexOf(choice)]
                status.text = "Installing ${picked.first.name}..."
                bg {
                    runCatching { ExtensionInstaller().install(picked.first, picked.second) }
                        .onSuccess { ext -> ui { log("Installed ${ext.name} (${ext.sources.size} src)."); status.text = "Idle"; reloadExtensions(); reloadSources() } }
                        .onFailure { ui { log("Install failed: ${it.message}"); status.text = "Idle" } }
                }
            }
        }
    }

    private fun checkExtUpdates() {
        status.text = "Checking updates..."
        bg {
            val updates = ExtensionUpdates.check()
            ui {
                status.text = "Idle"
                if (updates.isEmpty()) { log("All extensions up to date."); return@ui }
                val msg = updates.joinToString("\n") { "${it.entry.name}: v${it.installed.versionName} -> v${it.entry.version}" }
                if (JOptionPane.showConfirmDialog(frame, "$msg\n\nOut-of-date extensions can break. Update all?", "Updates", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    bg {
                        val installer = ExtensionInstaller()
                        updates.forEach { u -> runCatching { installer.install(u.entry, u.indexUrl) }.onSuccess { ui { log("updated ${u.entry.name}") } }.onFailure { ui { log("failed ${u.entry.name}: ${it.message}") } } }
                        ui { reloadExtensions() }
                    }
                }
            }
        }
    }

    private fun configureSourceDialog() {
        val sources = SourceManager.listInstalledSources()
        if (sources.isEmpty()) { log("No sources installed."); return }
        val labels = sources.map { "${it.name} [${it.lang}]" }.toTypedArray()
        val choice = JOptionPane.showInputDialog(frame, "Configure which source?", "Source settings", JOptionPane.PLAIN_MESSAGE, null, labels, labels[0]) as? String ?: return
        val src = sources[labels.indexOf(choice)]
        bg {
            val prefs = runCatching { SourcePreferences.list(src.id) }.getOrElse { ui { log("Could not read settings: ${it.message}") }; return@bg }
            ui {
                if (prefs.isNullOrEmpty()) { log("${src.name} has no configurable settings."); return@ui }
                val dlg = JDialog(frame, "${src.name} settings", true)
                val box = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = EmptyBorder(8, 8, 8, 8) }
                prefs.forEach { p ->
                    val row = JPanel(FlowLayout(FlowLayout.LEFT))
                    row.add(JLabel("${p.title} [${p.type}] = ${p.value.ifBlank { "(default)" }}"))
                    val opts = p.entryValues
                    row.add(button("Change") {
                        val hint = if (opts != null) "Options: ${opts.joinToString(", ")}" else "Value (true/false, text, or comma-separated set)"
                        val v = JOptionPane.showInputDialog(dlg, "${p.title}\n$hint", p.value) ?: return@button
                        val err = SourcePreferences.set(src.id, p.index, v)
                        log(if (err == null) "Saved ${p.title} = $v" else "Error: $err")
                        dlg.dispose()
                    })
                    box.add(row)
                }
                dlg.add(JScrollPane(box))
                dlg.setSize(560, 480)
                dlg.setLocationRelativeTo(frame)
                dlg.isVisible = true
            }
        }
    }

    // ---- Settings tab ----------------------------------------------------------------------

    private fun settingsTab(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = EmptyBorder(12, 12, 12, 12)
        val fields = HashMap<String, JTextField>()
        SettingsStore.describe().forEach { (k, v) ->
            val row = JPanel(FlowLayout(FlowLayout.LEFT))
            row.add(JLabel(k).apply { preferredSize = Dimension(160, 24) })
            val field = JTextField(v, 24)
            fields[k] = field
            row.add(field)
            panel.add(row)
        }
        val actions = JPanel(FlowLayout(FlowLayout.LEFT))
        actions.add(button("Save") {
            var okAll = true
            fields.forEach { (k, f) ->
                val err = SettingsStore.setByKey(k, f.text)
                if (err != null) { okAll = false; log("$k: $err") }
            }
            log(if (okAll) "Settings saved." else "Some settings failed (see above).")
        })
        actions.add(button("Reset") { SettingsStore.reset(); log("Settings reset."); refreshSettings(panel, fields) })
        panel.add(actions)
        return panel
    }

    private fun refreshSettings(panel: JPanel, fields: Map<String, JTextField>) {
        SettingsStore.describe().forEach { (k, v) -> fields[k]?.text = v }
    }

    // ---- shared download + helpers ---------------------------------------------------------

    private fun startDownload(sourceId: Long, url: String, select: ChapterSelect) {
        val s = SettingsStore.get()
        status.text = "Starting download..."
        progress.isIndeterminate = true
        bg {
            val job = DownloadManager(
                concurrency = s.downloadConcurrency,
                retries = s.downloadRetries,
                existingPolicy = s.existingBehavior,
                existingPrompt = if (s.existingBehavior == ExistingPolicy.ASK) guiExistingPrompt() else null,
                listener = DownloadListener { p ->
                    ui {
                        progress.isIndeterminate = false
                        progress.maximum = p.pagesTotal.coerceAtLeast(1)
                        progress.value = p.pagesDone
                        val mb = p.bytes / 1_048_576.0
                        val mbps = p.bytesPerSecond / 1_048_576.0
                        status.text = "${p.chapter}: ${p.pagesDone}/${p.pagesTotal}  %.1fMB  %.1f MB/s".format(mb, mbps)
                    }
                },
            ).download(SourceRef(sourceId, url), emptyList(), select)
            val ok = job.attempts.count { it.outcome == "ok" }
            val skipped = job.attempts.count { it.outcome == "skipped" }
            val failed = job.attempts.count { it.outcome == "failed" }
            ui {
                progress.isIndeterminate = false; progress.value = 0
                status.text = "Idle"
                log("Download ${job.state}: $ok ok, $skipped skipped, $failed failed.")
            }
        }
    }

    private fun guiExistingPrompt() = ExistingPrompt { name, _ ->
        var decision = ExistingDecision.SKIP
        SwingUtilities.invokeAndWait {
            val opts = arrayOf<Any>("Skip", "Replace", "Skip all", "Replace all")
            val r = JOptionPane.showOptionDialog(
                frame, "'$name' already exists.", "File exists",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0],
            )
            decision = when (r) {
                1 -> ExistingDecision.REPLACE
                2 -> ExistingDecision.SKIP_ALL
                3 -> ExistingDecision.REPLACE_ALL
                else -> ExistingDecision.SKIP
            }
        }
        decision
    }

    private fun parseRange(spec: String): ChapterSelect.Range {
        val p = spec.split("-", "..").map { it.trim() }
        require(p.size == 2)
        val from = p[0].toFloat(); val to = p[1].toFloat()
        return ChapterSelect.Range(minOf(from, to), maxOf(from, to))
    }

    private fun button(text: String, action: () -> Unit) = JButton(text).apply { addActionListener { runCatching(action).onFailure { log("Error: ${it.message}") } } }

    private fun <T> simpleRenderer(render: (T) -> String) =
        javax.swing.ListCellRenderer<T> { list, value, index, selected, focus ->
            JLabel(value?.let(render) ?: "").apply {
                isOpaque = true
                if (selected) { background = list.selectionBackground; foreground = list.selectionForeground }
                border = EmptyBorder(2, 6, 2, 6)
            }
        }

    private fun bg(block: () -> Unit) {
        Thread {
            try { block() } catch (e: Exception) { ui { log("Error: ${e.message ?: e.toString()}") } }
        }.apply { isDaemon = true }.start()
    }

    private fun ui(block: () -> Unit) = SwingUtilities.invokeLater(block)

    private fun log(msg: String) = ui {
        logArea.append(msg + "\n")
        logArea.caretPosition = logArea.document.length
    }

    private companion object {
        const val KEIYOUSHI = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"
    }
}

/** Simple display item for search results. */
private data class MangaItem(val title: String, val url: String) {
    override fun toString() = title
}

/** A source shown in the combo box. */
private data class SourceItem(val id: Long, val name: String, val lang: String) {
    override fun toString() = "$name [$lang]"
}
