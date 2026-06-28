/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package mangautils.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import mangautils.core.config.AppConfig
import mangautils.core.download.ChapterSelect
import mangautils.core.download.DownloadListener
import mangautils.core.download.DownloadManager
import mangautils.core.download.SourceRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mangautils.core.config.SettingsStore
import mangautils.core.extension.ExtensionInstaller
import mangautils.core.extension.ExtensionRepoClient
import mangautils.core.extension.ExtensionRepoEntry
import mangautils.core.extension.InstalledExtension
import mangautils.core.extension.InstalledStore
import mangautils.core.extension.RepoStore
import mangautils.core.library.HistoryEntry
import mangautils.core.library.HistoryStore
import mangautils.core.library.LibraryEntry
import mangautils.core.library.LibraryService
import mangautils.core.library.UpdateResult
import mangautils.core.library.LibraryStore
import mangautils.core.library.ReadStore
import mangautils.core.source.MangaDetails
import mangautils.core.source.SourceBrowser
import mangautils.core.source.LocalChapter
import mangautils.core.source.LocalChapterReader
import mangautils.core.source.SourceImage
import mangautils.core.source.SourceManager
import mangautils.core.source.SourcePref
import mangautils.core.source.SourcePreferences
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.skia.Image as SkiaImage

// ---- navigation -----------------------------------------------------------------------------

private sealed interface Screen {
    data object Library : Screen

    data object Browse : Screen

    data class SourceBrowse(val sourceId: Long, val name: String, val startLatest: Boolean = false) : Screen

    data class SourceConfig(val sourceId: Long, val name: String) : Screen

    data class Detail(val sourceId: Long, val url: String, val title: String) : Screen

    data class Reader(
        val sourceId: Long,
        val mangaUrl: String,
        val mangaTitle: String,
        val chapterUrl: String,
        val chapterName: String,
    ) : Screen

    data object Settings : Screen

    data object ReaderSettings : Screen

    data object Updates : Screen

    data object History : Screen

    data object Downloads : Screen

    data class Stub(val label: String) : Screen
}

private data class MangaRef(val title: String, val url: String, val thumb: String?)

// ---- Toast notifications --------------------------------------------------------------------

private enum class ToastKind { SUCCESS, ERROR, INFO }

private class ToastMsg(val id: Long, val text: String, val kind: ToastKind) {
    val visible = mutableStateOf(true)
}

/** Transient bottom-left notifications; max 5 shown, the rest queued, each auto-fades after ~5s. */
private object Toasts {
    // Compose Desktop has no Dispatchers.Main; snapshot state is safe to mutate off-thread.
    private val scope = CoroutineScope(Dispatchers.Default)
    val active = mutableStateListOf<ToastMsg>()
    private val pending = ArrayDeque<ToastMsg>()
    private var counter = 0L

    fun success(text: String) = show(text, ToastKind.SUCCESS)

    fun error(text: String) = show(text, ToastKind.ERROR)

    fun info(text: String) = show(text, ToastKind.INFO)

    fun show(text: String, kind: ToastKind = ToastKind.INFO) {
        scope.launch {
            val t = ToastMsg(counter++, text, kind)
            if (active.size < 5) promote(t) else pending.addLast(t)
        }
    }

    private fun promote(t: ToastMsg) {
        active.add(t)
        scope.launch { delay(4700); hide(t) }
    }

    fun hide(t: ToastMsg) {
        scope.launch {
            t.visible.value = false
            delay(280)
            active.remove(t)
            if (pending.isNotEmpty() && active.size < 5) promote(pending.removeFirst())
        }
    }
}

@Composable
private fun ToastHost() {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomStart) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Toasts.active.forEach { t ->
                key(t.id) {
                    AnimatedVisibility(
                        visible = t.visible.value,
                        enter = fadeIn() + slideInHorizontally { -it / 2 },
                        exit = fadeOut() + slideOutHorizontally { -it / 2 },
                    ) { ToastRow(t) }
                }
            }
        }
    }
}

@Composable
private fun ToastRow(t: ToastMsg) {
    val icon = when (t.kind) { ToastKind.SUCCESS -> Icons.Filled.CheckCircle; ToastKind.ERROR -> Icons.Filled.ErrorOutline; ToastKind.INFO -> Icons.Filled.Info }
    val color = when (t.kind) { ToastKind.SUCCESS -> Color(0xFF4CAF50); ToastKind.ERROR -> Red18; ToastKind.INFO -> MuTheme.Vermilion }
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF15171C)).widthIn(min = 230.dp, max = 380.dp).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(t.text, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = { Toasts.hide(t) }, modifier = Modifier.size(30.dp)) { Icon(Icons.Filled.Close, "Dismiss", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp)) }
    }
}

// ---- Connectivity ---------------------------------------------------------------------------

/** Polls connectivity so the app can switch online/offline live + toast the change. */
private object Net {
    var online by mutableStateOf(true)
        private set

    private val scope = CoroutineScope(Dispatchers.IO)
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            online = check()
            while (true) {
                delay(5000)
                val now = check()
                if (now != online) {
                    online = now
                    if (now) Toasts.success("Back online") else Toasts.error("You're offline — check your connection")
                }
            }
        }
    }

    private fun check(): Boolean =
        runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress("1.1.1.1", 443), 2500) }; true }.getOrDefault(false)
}

// ---- Download queue -------------------------------------------------------------------------

private enum class DlState { QUEUED, DOWNLOADING, DONE, ERROR }

private class DlItem(val id: Long, val sourceId: Long, val mangaUrl: String, val title: String, val chapterUrl: String, val chapterName: String) {
    var state by mutableStateOf(DlState.QUEUED)
    var pagesDone by mutableStateOf(0)
    var pagesTotal by mutableStateOf(0)
    var bytesPerSec by mutableStateOf(0.0)
}

private fun fmtSpeed(bps: Double): String = when {
    bps >= 1_000_000 -> "%.1f MB/s".format(bps / 1_000_000)
    bps >= 1_000 -> "%.0f KB/s".format(bps / 1_000)
    bps > 0 -> "${bps.toInt()} B/s"
    else -> "—"
}

/** Global, sequential download queue with live per-chapter progress + speed. */
private object DownloadQueue {
    private val scope = CoroutineScope(Dispatchers.Default)
    val items = mutableStateListOf<DlItem>()
    var completedTick by mutableStateOf(0)
        private set
    private var counter = 0L

    @Volatile private var running = false

    fun enqueue(sourceId: Long, mangaUrl: String, title: String, chapterUrl: String, chapterName: String) {
        if (items.any { it.chapterUrl == chapterUrl && (it.state == DlState.QUEUED || it.state == DlState.DOWNLOADING) }) return
        if (runCatching { DownloadManager.isDownloaded(title, chapterName) }.getOrDefault(false)) return
        items.add(DlItem(counter++, sourceId, mangaUrl, title, chapterUrl, chapterName))
        pump()
    }

    fun enqueueAll(sourceId: Long, mangaUrl: String, title: String, chapters: List<Pair<String, String>>) {
        chapters.forEach { enqueue(sourceId, mangaUrl, title, it.first, it.second) }
    }

    private fun pump() {
        if (running) return
        running = true
        scope.launch {
            while (true) {
                val next = items.firstOrNull { it.state == DlState.QUEUED } ?: break
                next.state = DlState.DOWNLOADING
                val listener = DownloadListener { p -> next.pagesDone = p.pagesDone; next.pagesTotal = p.pagesTotal; next.bytesPerSec = p.bytesPerSecond }
                val ok = runCatching {
                    DownloadManager(listener = listener).download(SourceRef(next.sourceId, next.mangaUrl), select = ChapterSelect.Urls(setOf(next.chapterUrl))).state.toString() == "DONE"
                }.getOrDefault(false)
                next.state = if (ok) DlState.DONE else DlState.ERROR
                completedTick++
            }
            running = false
        }
    }

    fun cancel(item: DlItem) {
        // Queued items can be removed immediately; an active download finishes its current chapter.
        if (item.state == DlState.QUEUED) items.remove(item)
    }

    fun stopAll() {
        items.removeAll { it.state == DlState.QUEUED }
    }

    val activeCount: Int get() = items.count { it.state == DlState.QUEUED || it.state == DlState.DOWNLOADING }

    fun overallProgress(): Float {
        val active = items.filter { it.state == DlState.QUEUED || it.state == DlState.DOWNLOADING }
        val total = active.sumOf { it.pagesTotal }
        return if (total > 0) active.sumOf { it.pagesDone }.toFloat() / total else 0f
    }

    fun speed(): Double = items.filter { it.state == DlState.DOWNLOADING }.sumOf { it.bytesPerSec }

    fun clearFinished() = items.removeAll { it.state == DlState.DONE || it.state == DlState.ERROR }
}

/** A persistent download-status box anchored bottom-left: a pill that expands into the live queue. */
@Composable
private fun DownloadWidget(onViewAll: () -> Unit) {
    val live = DownloadQueue.items.filter { it.state == DlState.DOWNLOADING || it.state == DlState.QUEUED }
    if (live.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val active = DownloadQueue.activeCount
    Box(Modifier.fillMaxSize().padding(start = 12.dp, bottom = 12.dp), contentAlignment = Alignment.BottomStart) {
        if (expanded) {
            Column(Modifier.width(330.dp).heightIn(max = 380.dp).clip(RoundedCornerShape(12.dp)).background(MuTheme.Panel).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloading · $active", color = MuTheme.Paper, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { DownloadQueue.stopAll() }) { Text("Stop all", color = MuTheme.Vermilion, fontSize = 12.sp) }
                    IconButton(onClick = { expanded = false }, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.ExpandMore, "Collapse", tint = MuTheme.Muted) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    live.forEach { dl ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(dl.title, color = MuTheme.Paper, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(dl.chapterName, color = MuTheme.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (dl.state == DlState.DOWNLOADING) {
                                    Text(if (dl.pagesTotal > 0) "${dl.pagesDone}/${dl.pagesTotal}" else "…", color = MuTheme.Muted, fontSize = 11.sp)
                                } else {
                                    IconButton(onClick = { DownloadQueue.cancel(dl) }, modifier = Modifier.size(26.dp)) { Icon(Icons.Filled.Close, "Cancel", tint = MuTheme.Muted, modifier = Modifier.size(15.dp)) }
                                }
                            }
                            if (dl.state == DlState.DOWNLOADING) {
                                LinearProgressIndicator(progress = { if (dl.pagesTotal > 0) dl.pagesDone.toFloat() / dl.pagesTotal else 0f }, modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 3.dp), color = MuTheme.Vermilion, trackColor = MuTheme.Ink)
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                TextButton(onClick = onViewAll, modifier = Modifier.fillMaxWidth()) { Text("View all downloads", color = MuTheme.Vermilion, fontSize = 12.sp) }
            }
        } else {
            Row(
                Modifier.clip(RoundedCornerShape(22.dp)).background(MuTheme.Panel).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22.dp)).clickable { expanded = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(28.dp), Alignment.Center) {
                    CircularProgressIndicator(progress = { DownloadQueue.overallProgress() }, modifier = Modifier.size(26.dp), color = MuTheme.Vermilion, strokeWidth = 3.dp, trackColor = MuTheme.Ink)
                    Text("$active", color = MuTheme.Paper, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Column { Text("Downloading", color = MuTheme.Paper, fontSize = 12.sp); Text(fmtSpeed(DownloadQueue.speed()), color = MuTheme.Muted, fontSize = 10.sp) }
            }
        }
    }
}

/** Shared source-browse search state so the top bar (App) and the grid (SourceBrowseScreen) agree. */
private class BrowseSearchState {
    var active by mutableStateOf(false)
    var query by mutableStateOf("")
    var submitTick by mutableStateOf(0)
    fun submit() { submitTick++ }
}

/** Suwayomi-style rounded-rectangle buttons (not Material's default pill shape). */
private val BtnShape = RoundedCornerShape(8.dp)

/** Red used for the 18+/NSFW badge. */
private val Red18 = Color(0xFFFF5252)

/** Many extension names are "Tachiyomi: Foo" — show just "Foo". */
private fun cleanName(s: String): String = s.removePrefix("Tachiyomi: ").trim()

@Composable
fun App() {
    val backStack = remember { mutableStateListOf<Screen>(Screen.Library) }
    val current = backStack.last()
    var sidebarOpen by remember { mutableStateOf(true) }
    var showRepos by remember { mutableStateOf(false) }
    var dataVersion by remember { mutableStateOf(0) }
    val srcSearch = remember(current) { BrowseSearchState() }
    LaunchedEffect(Unit) { Net.start() }
    fun go(s: Screen) = backStack.add(s)
    fun back() { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

    Surface(Modifier.fillMaxSize(), color = MuTheme.Ink) {
      Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(titleFor(current), backStack.size > 1, ::back, onMenu = { sidebarOpen = !sidebarOpen }, search = if (current is Screen.SourceBrowse) srcSearch else null) {
                // Per-screen actions, remastered into the top bar.
                when (current) {
                    is Screen.Browse -> IconButton(onClick = { showRepos = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.Add, "Add repository", tint = MuTheme.Paper) }
                    is Screen.SourceBrowse -> {
                        val sb = current
                        var gridMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { gridMenu = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.GridView, "Layout", tint = MuTheme.Paper) }
                            DropdownMenu(gridMenu, { gridMenu = false }) {
                                DropdownMenuItem(text = { Text("Compact grid") }, onClick = { Display.set(GridMode.COMPACT); gridMenu = false })
                                DropdownMenuItem(text = { Text("Comfortable grid") }, onClick = { Display.set(GridMode.COMFORTABLE); gridMenu = false })
                                DropdownMenuItem(text = { Text("List") }, onClick = { Display.set(GridMode.LIST); gridMenu = false })
                            }
                        }
                        IconButton(onClick = { go(Screen.SourceConfig(sb.sourceId, sb.name)) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.Settings, "Source settings", tint = MuTheme.Paper) }
                    }
                    else -> {}
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(Modifier.fillMaxSize()) {
                if (current !is Screen.Reader && sidebarOpen) {
                    Sidebar(current) { backStack.clear(); backStack.add(it) }
                    Box(Modifier.width(1.dp).fillMaxSize().background(MaterialTheme.colorScheme.outline))
                }
                Box(Modifier.fillMaxSize()) {
                    when (val s = current) {
                        Screen.Library -> LibraryScreen { go(Screen.Detail(it.sourceId, it.mangaUrl, it.title)) }
                        Screen.Browse -> BrowseScreen(dataVersion, onOpenConfig = { id, name -> go(Screen.SourceConfig(id, name)) }) { id, name, latest -> go(Screen.SourceBrowse(id, name, latest)) }
                        is Screen.SourceBrowse -> SourceBrowseScreen(s, srcSearch) { m -> go(Screen.Detail(s.sourceId, m.url, m.title)) }
                        is Screen.SourceConfig -> SourceConfigScreen(s.sourceId)
                        is Screen.Detail -> DetailScreen(s) { chUrl, chName -> go(Screen.Reader(s.sourceId, s.url, s.title, chUrl, chName)) }
                        is Screen.Reader -> ReaderScreen(s, sidebarOpen, onOpenSettings = { go(Screen.ReaderSettings) }) { chUrl, chName ->
                            backStack[backStack.lastIndex] = Screen.Reader(s.sourceId, s.mangaUrl, s.mangaTitle, chUrl, chName)
                        }
                        Screen.Settings -> SettingsScreen { go(Screen.ReaderSettings) }
                        Screen.ReaderSettings -> ReaderSettingsScreen()
                        Screen.Updates -> UpdatesScreen { e -> go(Screen.Detail(e.sourceId, e.mangaUrl, e.title)) }
                        Screen.History -> HistoryScreen { h -> go(Screen.Reader(h.sourceId, h.mangaUrl, h.mangaTitle, h.chapterUrl, h.chapterName)) }
                        Screen.Downloads -> DownloadsScreen()
                        is Screen.Stub -> Empty("${s.label} — coming soon")
                    }
                }
            }
        }
        DownloadWidget { backStack.clear(); backStack.add(Screen.Downloads) }
        ToastHost()
      }
    }
    if (showRepos) RepoDialog { showRepos = false; dataVersion++ }
}

private fun titleFor(s: Screen): String =
    when (s) {
        Screen.Library -> "Library"
        Screen.Browse -> "Browse"
        is Screen.SourceBrowse -> s.name
        is Screen.Detail -> s.title
        is Screen.Reader -> s.mangaTitle
        is Screen.SourceConfig -> "Source Configuration"
        Screen.Settings -> "Settings"
        Screen.ReaderSettings -> "Reader"
        Screen.Updates -> "Updates"
        Screen.History -> "History"
        Screen.Downloads -> "Downloads"
        is Screen.Stub -> s.label
    }

// ---- chrome ---------------------------------------------------------------------------------

@Composable
private fun TopBar(title: String, canGoBack: Boolean, onBack: () -> Unit, onMenu: () -> Unit, search: BrowseSearchState? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(MuTheme.Ink).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenu, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.Menu, "Menu", tint = MuTheme.Paper) }
        if (canGoBack) IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.ArrowBack, "Back", tint = MuTheme.Paper) }
        Spacer(Modifier.width(6.dp))
        if (search != null && search.active) {
            val fr = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
            Spacer(Modifier.weight(1f))
            Column(Modifier.width(340.dp).padding(end = 8.dp)) {
                BasicTextField(
                    value = search.query,
                    onValueChange = { search.query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = MuTheme.Paper, fontSize = 16.sp),
                    cursorBrush = SolidColor(MuTheme.Vermilion),
                    modifier = Modifier.fillMaxWidth().focusRequester(fr)
                        .onPreviewKeyEvent { e -> if (e.key == Key.Enter && e.type == KeyEventType.KeyDown) { search.submit(); true } else false },
                    decorationBox = { inner ->
                        Box(Modifier.padding(vertical = 5.dp)) {
                            if (search.query.isEmpty()) Text("Search this source", color = MuTheme.Muted, fontSize = 16.sp)
                            inner()
                        }
                    },
                )
                HorizontalDivider(color = MuTheme.Vermilion, thickness = 2.dp)
            }
            IconButton(onClick = { search.query = ""; search.active = false; search.submit() }, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.Cancel, "Close search", tint = MuTheme.Paper) }
            actions()
        } else {
            Text(title, color = MuTheme.Paper, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            if (search != null) IconButton(onClick = { search.active = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.Search, "Search", tint = MuTheme.Paper) }
            actions()
        }
    }
}

private data class NavItem(val label: String, val icon: ImageVector, val screen: Screen)

@Composable
private fun Sidebar(current: Screen, onSelect: (Screen) -> Unit) {
    val items =
        listOf(
            NavItem("Library", Icons.Filled.Home, Screen.Library),
            NavItem("Updates", Icons.Filled.Update, Screen.Updates),
            NavItem("History", Icons.Filled.History, Screen.History),
            NavItem("Browse", Icons.Filled.Explore, Screen.Browse),
            NavItem("Downloads", Icons.Filled.Download, Screen.Downloads),
            NavItem("Settings", Icons.Filled.Settings, Screen.Settings),
            NavItem("About", Icons.Filled.Info, Screen.Stub("About")),
        )
    Column(Modifier.width(200.dp).fillMaxHeight().background(MuTheme.Ink).padding(vertical = 8.dp)) {
        items.forEach { item ->
            val selected = current::class == item.screen::class && (item.screen !is Screen.Stub || (current as? Screen.Stub)?.label == (item.screen as Screen.Stub).label) ||
                (item.screen == Screen.Browse && (current is Screen.Browse || current is Screen.SourceBrowse))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) MuTheme.Vermilion.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(item.screen) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Icon(item.icon, item.label, tint = if (selected) MuTheme.Vermilion else MuTheme.Muted, modifier = Modifier.size(20.dp))
                    val dlCount = DownloadQueue.activeCount
                    if (item.screen == Screen.Downloads && dlCount > 0) {
                        Box(Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp).size(16.dp).clip(RoundedCornerShape(8.dp)).background(MuTheme.Vermilion), Alignment.Center) {
                            Text("$dlCount", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.width(14.dp))
                Text(item.label, color = if (selected) MuTheme.Paper else MuTheme.Muted, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

// ---- image loading ---------------------------------------------------------------------------

private object ImageCache {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()
    private val dir by lazy { mangautils.core.config.AppConfig.dataDir.resolve("covers") }

    private fun keyFile(url: String): java.nio.file.Path {
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return dir.resolve(hash)
    }

    /** Bytes from the in-memory→disk cache, else fetched + written through (so covers work offline). */
    private fun cachedBytes(url: String, fetch: () -> ByteArray?): ImageBitmap? {
        cache[url]?.let { return it }
        val file = runCatching { keyFile(url) }.getOrNull()
        val bytes =
            if (file != null && java.nio.file.Files.exists(file)) {
                runCatching { java.nio.file.Files.readAllBytes(file) }.getOrNull()
            } else {
                fetch()?.also { b -> file?.let { runCatching { java.nio.file.Files.createDirectories(it.parent); java.nio.file.Files.write(it, b) } } }
            }
        val img = bytes?.let { decode(it) } ?: return null
        cache[url] = img
        return img
    }

    fun cover(sourceId: Long, url: String): ImageBitmap? = cachedBytes(url) { SourceImage.coverBytes(sourceId, url) }

    /** Plain HTTP image (e.g. extension icons from a repo) — no source headers needed. */
    fun url(u: String): ImageBitmap? = cachedBytes(u) { runCatching { java.net.URI(u).toURL().openStream().use { it.readBytes() } }.getOrNull() }
}

/** Extension icons live next to the index, e.g. <repo>/icon/<pkg>.png. */
private fun extIconUrl(repoUrl: String, pkg: String): String = "${repoUrl.substringBeforeLast('/')}/icon/$pkg.png"

@Composable
private fun IconImage(url: String?, seed: String, modifier: Modifier) {
    var bmp by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) { if (!url.isNullOrBlank()) bmp = withContext(Dispatchers.IO) { ImageCache.url(url) } }
    val b = bmp
    if (b != null) {
        Image(b, null, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(coverColor(seed)), Alignment.Center) {
            Text(seed.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

private fun decode(bytes: ByteArray): ImageBitmap? = runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

@Composable
private fun Cover(sourceId: Long, url: String?, seed: String, modifier: Modifier) {
    var bmp by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) { if (!url.isNullOrBlank()) bmp = withContext(Dispatchers.IO) { ImageCache.cover(sourceId, url) } }
    val b = bmp
    if (b != null) {
        Image(b, null, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(coverColor(seed)), Alignment.Center) {
            Text(seed.take(1).uppercase(), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
        }
    }
}

private fun coverColor(seed: String): Color {
    val hues = listOf(0xFF3A2F4B, 0xFF2F3D4B, 0xFF4B3A2F, 0xFF2F4B3A, 0xFF4B2F3D)
    return Color(hues[(seed.hashCode().ushr(1)) % hues.size])
}

/**
 * The cover's dominant *vibrant* color (Suwayomi-like). Averaging muddies covers, so instead we
 * bucket saturated, mid-brightness pixels into a coarse color histogram and take the heaviest
 * bucket's weighted-average color.
 */
private fun dominantColor(bmp: ImageBitmap): Color {
    val pm = runCatching { bmp.toPixelMap() }.getOrNull() ?: return Color.Gray
    val buckets = HashMap<Int, DoubleArray>() // key -> [weight, r, g, b]
    val stepX = (pm.width / 40).coerceAtLeast(1)
    val stepY = (pm.height / 40).coerceAtLeast(1)
    var x = 0
    while (x < pm.width) {
        var y = 0
        while (y < pm.height) {
            val c = pm[x, y]
            val mx = maxOf(c.red, c.green, c.blue); val mn = minOf(c.red, c.green, c.blue)
            val sat = mx - mn
            if (sat > 0.12f && mx in 0.15f..0.97f) { // ignore near-grey / too dark / blown-out
                val key = ((c.red * 4).toInt() shl 8) or ((c.green * 4).toInt() shl 4) or (c.blue * 4).toInt()
                val w = sat.toDouble()
                val arr = buckets.getOrPut(key) { DoubleArray(4) }
                arr[0] += w; arr[1] += c.red * w; arr[2] += c.green * w; arr[3] += c.blue * w
            }
            y += stepY
        }
        x += stepX
    }
    val best = buckets.values.maxByOrNull { it[0] } ?: return Color(0xFF555560)
    return Color((best[1] / best[0]).toFloat().coerceIn(0f, 1f), (best[2] / best[0]).toFloat().coerceIn(0f, 1f), (best[3] / best[0]).toFloat().coerceIn(0f, 1f))
}

/** A vibrant accent derived from a cover color (boost saturation, fix brightness) — for buttons etc. */
private fun vibrant(c: Color): Color {
    val hsb = java.awt.Color.RGBtoHSB((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt(), null)
    return Color(java.awt.Color.HSBtoRGB(hsb[0], (hsb[1] * 1.35f).coerceIn(0.45f, 1f), 0.78f))
}

/** A whole-app palette derived from a cover color (Suwayomi's dynamic color scheme). */
private fun dynamicPalette(c: Color): MuPalette {
    val acc = vibrant(c)
    return MuPalette(
        name = "dynamic", accentDark = acc, accentLight = acc,
        inkDark = lerp(Color(0xFF0C0C0E), c, 0.13f),
        panelDark = lerp(Color(0xFF1A1A1E), c, 0.17f),
        panelHighDark = lerp(lerp(Color(0xFF1A1A1E), c, 0.17f), Color.White, 0.06f),
        inkLight = Color(0xFFFAFAFA), panelLight = Color(0xFFFFFFFF), panelHighLight = Color(0xFFE7E3DA),
    )
}

// ---- Library --------------------------------------------------------------------------------

@Composable
private fun LibraryScreen(onOpen: (LibraryEntry) -> Unit) {
    var entries by remember { mutableStateOf<List<LibraryEntry>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) { withContext(Dispatchers.IO) { entries = LibraryService.list() } }
    if (entries.isEmpty()) { Empty("Your library is empty.\nBrowse a source and add a series."); return }
    if (Display.grid == GridMode.LIST) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
            items(entries) { e -> MangaRow(e.sourceId, MangaRef(e.title, e.mangaUrl, e.thumbnailUrl), "${e.knownChapters.size} ch") { onOpen(e) } }
        }
    } else {
        LazyVerticalGrid(
            gridCell(),
            Modifier.fillMaxSize().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(entries) { e -> LibraryCard(e, onOpen = { onOpen(e) }) { refresh++ } }
        }
    }
}

@Composable
private fun LibraryCard(e: LibraryEntry, onOpen: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    fun markAll(read: Boolean) {
        scope.launch { withContext(Dispatchers.IO) { e.knownChapters.forEach { ReadStore.setRead(e.sourceId, e.mangaUrl, it.url, read) } } }
    }
    Box {
        MangaCover(e.sourceId, MangaRef(e.title, e.mangaUrl, e.thumbnailUrl), "${e.knownChapters.size} ch") { onOpen() }
        Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            Box(
                Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)).clickable { menuOpen = true }.padding(4.dp),
            ) { Icon(Icons.Filled.MoreVert, "Menu", tint = Color.White, modifier = Modifier.size(20.dp)) }
            DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Mark all read") }, onClick = { menuOpen = false; markAll(true) })
                DropdownMenuItem(text = { Text("Mark all unread") }, onClick = { menuOpen = false; markAll(false) })
                DropdownMenuItem(
                    text = { Text("Remove from library", color = MuTheme.Vermilion) },
                    onClick = {
                        menuOpen = false
                        scope.launch { withContext(Dispatchers.IO) { LibraryService.remove(e.sourceId, e.mangaUrl) }; onChanged(); Toasts.success("Removed manga from the library") }
                    },
                )
            }
        }
    }
}

@Composable
private fun MangaCover(sourceId: Long, m: MangaRef, subtitle: String, allowAdd: Boolean = false, onClick: () -> Unit) {
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var added by remember(m.url) { mutableStateOf(false) }
    if (allowAdd) LaunchedEffect(m.url) { added = withContext(Dispatchers.IO) { runCatching { LibraryService.isFollowed(sourceId, m.url) }.getOrDefault(false) } }
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MuTheme.Panel), modifier = Modifier.hoverable(interaction)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.69f)) {
            Cover(sourceId, m.thumb, m.title, Modifier.fillMaxSize())
            // Bottom gradient scrim so the overlaid title stays readable on any cover.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.92f))))
            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 7.dp)) {
                Text(m.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp)
            }
            if (allowAdd && (hovered || added)) {
                Box(
                    Modifier.align(Alignment.TopStart).clip(RoundedCornerShape(bottomEnd = 8.dp))
                        .background(if (added) MuTheme.Muted else MuTheme.Vermilion)
                        .clickable { if (!added) { scope.launch { withContext(Dispatchers.IO) { runCatching { LibraryService.add(sourceId, m.url) } }; added = true; Toasts.success("Added manga to library!") } } }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) { Text(if (added) "IN LIBRARY" else "ADD TO LIBRARY", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

private enum class GridMode { COMPACT, COMFORTABLE, LIST }

/** Library/browse grid density, persisted to settings. */
private object Display {
    var grid by mutableStateOf(runCatching { GridMode.valueOf(SettingsStore.get().gridMode) }.getOrNull() ?: GridMode.COMFORTABLE)
        private set

    fun set(m: GridMode) {
        grid = m
        runCatching { SettingsStore.save(SettingsStore.get().copy(gridMode = m.name)) }
    }
}

private fun gridCell() = GridCells.Adaptive(if (Display.grid == GridMode.COMPACT) 132.dp else 178.dp)

/** Reader preferences (persisted), readable as Compose state so the reader reacts live. */
private object ReaderPrefs {
    private val s = runCatching { SettingsStore.get() }.getOrNull()
    var scaleType by mutableStateOf(s?.readerScaleType ?: "FIT_WIDTH")
        private set
    var pageGap by mutableStateOf(s?.readerPageGap ?: 0)
        private set
    var background by mutableStateOf(s?.readerBackground ?: "THEME")
        private set
    var skipDuplicates by mutableStateOf(s?.readerSkipDuplicates ?: true)
        private set

    // Session-only auto-scroll (not persisted).
    var autoScroll by mutableStateOf(false)
    var autoScrollSeconds by mutableStateOf(5)

    private fun save() {
        runCatching {
            SettingsStore.save(SettingsStore.get().copy(readerScaleType = scaleType, readerPageGap = pageGap, readerBackground = background, readerSkipDuplicates = skipDuplicates))
        }
    }

    fun applyScale(v: String) { scaleType = v; save() }
    fun applyGap(v: Int) { pageGap = v; save() }
    fun applyBackground(v: String) { background = v; save() }
    fun applySkipDuplicates(v: Boolean) { skipDuplicates = v; save() }

    fun bg(themeInk: Color): Color = when (background) {
        "BLACK" -> Color.Black
        "GRAY" -> Color(0xFF2B2B2D)
        "WHITE" -> Color.White
        else -> themeInk
    }
}

@Composable
private fun MangaRow(sourceId: Long, m: MangaRef, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(sourceId, m.thumb, m.title, Modifier.width(44.dp).aspectRatio(0.7f).clip(RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.title, color = MuTheme.Paper, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = MuTheme.Muted, fontSize = 11.sp)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
}

// ---- Browse (tabs: Source / Extension / Migrate) --------------------------------------------

@Composable
private fun BrowseScreen(dataVersion: Int, onOpenConfig: (Long, String) -> Unit, onOpenSource: (Long, String, Boolean) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = MuTheme.Ink, contentColor = MuTheme.Vermilion) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Source") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Extension") })
            Tab(tab == 2, { tab = 2 }, text = { Text("Migrate") })
        }
        when (tab) {
            0 -> SourceTab(onOpenSource)
            1 -> ExtensionTab(dataVersion, onOpenConfig)
            else -> Empty("Migrate — coming soon")
        }
    }
}

@Composable
private fun SourceTab(onOpen: (Long, String, Boolean) -> Unit) {
    var sources by remember { mutableStateOf<List<Triple<Long, String, String>>>(emptyList()) }
    var nsfwIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var iconBySource by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val installed = InstalledStore.list()
            sources = SourceManager.listInstalledSources().map { Triple(it.id, it.name, it.lang) }
            nsfwIds = installed.filter { it.nsfw }.flatMap { it.sources.map { s -> s.id } }.toSet()
            // Resolve each source's extension icon: sourceId -> pkg -> repo -> <repo>/icon/<pkg>.png
            val pkgBySource = installed.flatMap { ext -> ext.sources.map { it.id to ext.pkg } }.toMap()
            val client = ExtensionRepoClient()
            val repoByPkg = RepoStore.list().flatMap { repo ->
                runCatching { client.fetchIndex(repo) }.getOrDefault(emptyList()).map { it.pkg to repo }
            }.toMap()
            iconBySource = pkgBySource.mapNotNull { (sid, pkg) -> repoByPkg[pkg]?.let { sid to extIconUrl(it, pkg) } }.toMap()
        }
    }
    if (sources.isEmpty()) { Empty("No sources installed.\nInstall an extension from the Extension tab."); return }
    var prefsRev by remember { mutableStateOf(0) }
    val settings = remember(prefsRev) { runCatching { SettingsStore.get() }.getOrNull() }
    val pinned = settings?.pinnedSources?.toSet() ?: emptySet()
    val lastUsed = settings?.lastUsedSourceId ?: 0L
    fun togglePin(id: Long) {
        runCatching {
            val cur = SettingsStore.get().pinnedSources.toMutableList()
            if (id in cur) cur.remove(id) else cur.add(id)
            SettingsStore.save(SettingsStore.get().copy(pinnedSources = cur))
        }
        prefsRev++
    }
    fun open(id: Long, name: String, latest: Boolean) {
        runCatching { SettingsStore.save(SettingsStore.get().copy(lastUsedSourceId = id)) }
        onOpen(id, name, latest)
    }

    @Composable
    fun row(s: Triple<Long, String, String>) = SourceRow(iconBySource[s.first], s.first, s.second, s.third, s.first in nsfwIds, s.first in pinned, ::togglePin, ::open)

    val byLang = sources.groupBy { it.third.ifBlank { "other" } }.toSortedMap()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        sources.firstOrNull { it.first == lastUsed }?.let { last ->
            item { SectionHeader("Last used") }
            item { row(last) }
        }
        val pins = sources.filter { it.first in pinned }
        if (pins.isNotEmpty()) {
            item { SectionHeader("Pinned") }
            items(pins) { s -> row(s) }
        }
        byLang.forEach { (lang, group) ->
            item { SectionHeader(lang.uppercase()) }
            items(group) { s -> row(s) }
        }
    }
}

@Composable
private fun SourceRow(
    iconUrl: String?, id: Long, name: String, lang: String, nsfw: Boolean, pinned: Boolean,
    onTogglePin: (Long) -> Unit, onOpen: (Long, String, Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(id, name, false) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconImage(iconUrl, name, Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(cleanName(name), color = MuTheme.Paper, fontWeight = FontWeight.SemiBold)
            Row {
                Text(lang.uppercase(), color = MuTheme.Muted, fontSize = 11.sp)
                if (nsfw) { Spacer(Modifier.width(6.dp)); Text("18+", color = Red18, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
        }
        OutlinedButton(onClick = { onOpen(id, name, true) }, shape = BtnShape) { Text("LATEST", color = MuTheme.Vermilion) }
        IconButton(onClick = { onTogglePin(id) }) { Icon(Icons.Filled.PushPin, if (pinned) "Unpin" else "Pin", tint = if (pinned) MuTheme.Vermilion else MuTheme.Muted) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
}

@Composable
private fun ExtensionTab(dataVersion: Int, onOpenConfig: (Long, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf<List<InstalledExtension>>(emptyList()) }
    var allEntries by remember { mutableStateOf<List<Pair<ExtensionRepoEntry, String>>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var selectedRepo by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refresh, dataVersion) {
        loading = true
        withContext(Dispatchers.IO) {
            installed = InstalledStore.list()
            val client = ExtensionRepoClient()
            allEntries = RepoStore.list().flatMap { repo ->
                runCatching { client.fetchIndex(repo) }.getOrDefault(emptyList()).map { it to repo }
            }
        }
        loading = false
    }

    val repos = remember(allEntries) { allEntries.map { it.second }.distinct() }
    val byPkg = remember(allEntries) { allEntries.associateBy { it.first.pkg } }
    val have = installed.map { it.pkg }.toSet()
    fun inRepo(repo: String?) = selectedRepo == null || repo == selectedRepo
    val available = allEntries.filter { it.first.pkg !in have && inRepo(it.second) && it.first.name.contains(query, ignoreCase = true) }
    val installedShown = installed.filter { inRepo(byPkg[it.pkg]?.second) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), singleLine = true,
            placeholder = { Text("Search extensions") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
        )
        // Per-repo tabs (only when more than one repo is configured).
        if (repos.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RepoChip("All", selectedRepo == null) { selectedRepo = null }
                repos.forEach { r -> RepoChip(repoShortName(r), selectedRepo == r) { selectedRepo = r } }
            }
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = MuTheme.Vermilion) }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                item { SectionHeader("Installed") }
                if (installedShown.isEmpty()) item { Text("None installed.", color = MuTheme.Muted, modifier = Modifier.padding(vertical = 8.dp)) }
                items(installedShown) { ext ->
                    val repo = byPkg[ext.pkg]?.second
                    ExtRow(
                        repo?.let { extIconUrl(it, ext.pkg) }, ext.name, ext.lang, ext.versionName, ext.nsfw, repo?.let { repoShortName(it) },
                        installed = true,
                        onSettings = { ext.sources.firstOrNull()?.let { onOpenConfig(it.id, ext.name) } },
                    ) { removeExtension(ext); refresh++ }
                }
                item { SectionHeader("Available (${available.size})") }
                items(available) { (entry, repo) ->
                    ExtRow(extIconUrl(repo, entry.pkg), entry.name, entry.lang, entry.version, entry.isNsfw, repoShortName(repo), installed = false) {
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { ExtensionInstaller().install(entry, repo) } }
                            refresh++
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(BtnShape).background(if (selected) MuTheme.Vermilion else MuTheme.Panel).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(label, color = if (selected) Color.White else MuTheme.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
}

/** Short label for a repo index URL, e.g. ".../keiyoushi/extensions/repo/index.min.json" -> "keiyoushi". */
private fun repoShortName(url: String): String =
    runCatching { url.substringAfter("://").substringAfter("/").substringBefore("/").ifBlank { url } }.getOrDefault(url)

@Composable
private fun ExtRow(iconUrl: String?, name: String, lang: String, version: String, nsfw: Boolean, repoShort: String?, installed: Boolean, onSettings: (() -> Unit)? = null, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        IconImage(iconUrl, cleanName(name), Modifier.size(40.dp).clip(BtnShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(cleanName(name), color = MuTheme.Paper, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${lang.uppercase()} · v$version", color = MuTheme.Muted, fontSize = 11.sp)
                if (nsfw) {
                    Spacer(Modifier.width(6.dp))
                    Text("18+", color = Red18, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                repoShort?.let {
                    Spacer(Modifier.width(6.dp))
                    Text("· $it", color = MuTheme.Muted.copy(alpha = 0.85f), fontSize = 11.sp)
                }
            }
        }
        if (installed) {
            IconButton(onClick = { onSettings?.invoke() }) { Icon(Icons.Filled.Settings, "Settings", tint = MuTheme.Muted) }
            Spacer(Modifier.width(4.dp))
            OutlinedButton(onClick = onAction, shape = BtnShape) { Text("UNINSTALL", color = MuTheme.Vermilion) }
        } else {
            Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = MuTheme.Vermilion), shape = BtnShape) { Text("INSTALL") }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
}

@Composable
private fun RepoDialog(onClose: () -> Unit) {
    var repos by remember { mutableStateOf(RepoStore.list()) }
    var newUrl by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = MuTheme.Panel,
        title = { Text("Extension repositories", color = MuTheme.Paper) },
        text = {
            Column {
                Text("Add repositories from which extensions can be installed. Only add repos you trust.", color = MuTheme.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                repos.forEach { url ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(url, color = MuTheme.Paper, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { RepoStore.remove(url); repos = RepoStore.list() }) { Text("Delete", color = MuTheme.Vermilion) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(newUrl, { newUrl = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("index.min.json URL") })
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("OK", color = MuTheme.Vermilion) } },
        dismissButton = {
            TextButton(onClick = {
                val u = newUrl.trim()
                if (u.isNotBlank()) { RepoStore.add(u); repos = RepoStore.list(); newUrl = "" }
            }) { Text("Add repository", color = MuTheme.Vermilion) }
        },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, color = MuTheme.Paper, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

// ---- Updates / History / Downloads ----------------------------------------------------------

private fun timeAgo(ts: Long): String {
    val m = (System.currentTimeMillis() - ts) / 60000
    return when {
        m < 1 -> "just now"
        m < 60 -> "${m}m ago"
        m < 1440 -> "${m / 60}h ago"
        else -> "${m / 1440}d ago"
    }
}

@Composable
private fun UpdatesScreen(onOpenManga: (LibraryEntry) -> Unit) {
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<List<UpdateResult>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    fun refresh() {
        if (running) return
        running = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { LibraryService.update() }.getOrDefault(emptyList()) }
            results = r.filter { it.newChapters.isNotEmpty() }
            running = false; checked = true
            val n = results.sumOf { it.newChapters.size }
            Toasts.success(if (n > 0) "$n new chapter(s) across ${results.size} series" else "No new chapters")
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { refresh() }, enabled = !running, shape = BtnShape, colors = ButtonDefaults.buttonColors(containerColor = MuTheme.Vermilion)) {
                Text(if (running) "Checking…" else "Check for updates")
            }
            Spacer(Modifier.width(12.dp))
            if (running) CircularProgressIndicator(color = MuTheme.Vermilion, modifier = Modifier.size(22.dp))
        }
        if (results.isEmpty()) {
            Empty(if (checked) "No new chapters." else "Check your library for new chapters.")
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(results) { res ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenManga(res.entry) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(res.entry.sourceId, res.entry.thumbnailUrl, res.entry.title, Modifier.width(40.dp).aspectRatio(0.7f).clip(RoundedCornerShape(6.dp)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(res.entry.title, color = MuTheme.Paper, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${res.newChapters.size} new chapter${if (res.newChapters.size == 1) "" else "s"}", color = MuTheme.Vermilion, fontSize = 12.sp)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(onOpen: (HistoryEntry) -> Unit) {
    var items by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var rev by remember { mutableStateOf(0) }
    LaunchedEffect(rev) { withContext(Dispatchers.IO) { items = HistoryStore.list() } }
    if (items.isEmpty()) { Empty("No reading history yet."); return }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${items.size} entries", color = MuTheme.Muted, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { HistoryStore.clear(); rev++ }, shape = BtnShape) { Text("Clear", color = MuTheme.Vermilion) }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(items) { h ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(h) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(h.sourceId, h.thumbnailUrl, h.mangaTitle, Modifier.width(40.dp).aspectRatio(0.7f).clip(RoundedCornerShape(6.dp)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.mangaTitle, color = MuTheme.Paper, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(h.chapterName, color = MuTheme.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(timeAgo(h.readAt), color = MuTheme.Muted, fontSize = 11.sp)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            }
        }
    }
}

@Composable
private fun DownloadsScreen() {
    // Queue-only. Managing already-downloaded chapters (by series) will live in Settings later.
    val queue = DownloadQueue.items
    if (queue.isEmpty()) { Empty("No downloads in the queue.\nDownloaded-chapter management will live in Settings."); return }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Queue · ${DownloadQueue.activeCount} active", color = MuTheme.Paper, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { DownloadQueue.stopAll() }) { Text("Stop all", color = MuTheme.Vermilion) }
                TextButton(onClick = { DownloadQueue.clearFinished() }) { Text("Clear finished", color = MuTheme.Vermilion) }
            }
        }
        items(queue, key = { it.id }) { dl -> QueueRow(dl) }
    }
}

@Composable
private fun QueueRow(dl: DlItem) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(dl.title, color = MuTheme.Paper, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(dl.chapterName, color = MuTheme.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val label = when (dl.state) {
                DlState.DOWNLOADING -> if (dl.pagesTotal > 0) "${dl.pagesDone}/${dl.pagesTotal}" else "starting…"
                DlState.QUEUED -> "Queued"
                DlState.DONE -> "Done"
                DlState.ERROR -> "Failed"
            }
            Text(label, color = when (dl.state) { DlState.ERROR -> Red18; DlState.DONE -> Color(0xFF4CAF50); else -> MuTheme.Muted }, fontSize = 11.sp)
        }
        if (dl.state == DlState.DOWNLOADING) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { if (dl.pagesTotal > 0) dl.pagesDone.toFloat() / dl.pagesTotal else 0f }, modifier = Modifier.fillMaxWidth().height(4.dp), color = MuTheme.Vermilion, trackColor = MuTheme.Panel)
            Text(fmtSpeed(dl.bytesPerSec), color = MuTheme.Muted, fontSize = 10.sp)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
}

// ---- Settings (appearance / themes) ---------------------------------------------------------

@Composable
private fun SettingsScreen(onOpenReader: () -> Unit) {
    var themeName by remember { mutableStateOf(MuTheme.palette.name) }
    var dark by remember { mutableStateOf(MuTheme.dark) }
    fun apply() {
        MuTheme.apply(themeName, dark)
        runCatching { SettingsStore.save(SettingsStore.get().copy(themeName = themeName, themeDark = dark)) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Appearance", color = MuTheme.Paper, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Theme mode", color = MuTheme.Paper, modifier = Modifier.weight(1f))
            RepoChip("Dark", dark) { dark = true; apply() }
            Spacer(Modifier.width(8.dp))
            RepoChip("Light", !dark) { dark = false; apply() }
        }
        Spacer(Modifier.height(20.dp))
        Text("Theme", color = MuTheme.Muted, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MuTheme.presets.forEach { p -> ThemeSwatch(p, selected = p.name == themeName) { themeName = p.name; apply() } }
        }
        Spacer(Modifier.height(28.dp))
        Text("Display", color = MuTheme.Muted, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        var bg by remember { mutableStateOf(runCatching { SettingsStore.get().mangaThumbnailBackground }.getOrDefault(true)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Manga thumbnail as background", color = MuTheme.Paper)
                Text("Use the blurred cover as the detail-page background", color = MuTheme.Muted, fontSize = 12.sp)
            }
            Switch(checked = bg, onCheckedChange = { bg = it; runCatching { SettingsStore.save(SettingsStore.get().copy(mangaThumbnailBackground = it)) } })
        }
        Spacer(Modifier.height(8.dp))
        var dyn by remember { mutableStateOf(runCatching { SettingsStore.get().dynamicThemeColors }.getOrDefault(true)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Dynamic theme colors on manga page", color = MuTheme.Paper)
                Text("Tint the detail page with the cover's dominant color", color = MuTheme.Muted, fontSize = 12.sp)
            }
            Switch(checked = dyn, onCheckedChange = { dyn = it; runCatching { SettingsStore.save(SettingsStore.get().copy(dynamicThemeColors = it)) } })
        }
        Spacer(Modifier.height(28.dp))
        Text("Downloads", color = MuTheme.Muted, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        var cbz by remember { mutableStateOf(runCatching { SettingsStore.get().downloadAsCbz }.getOrDefault(false)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Save as CBZ archive", color = MuTheme.Paper)
                Text("Off = a folder of page images (like Suwayomi)", color = MuTheme.Muted, fontSize = 12.sp)
            }
            Switch(checked = cbz, onCheckedChange = { cbz = it; runCatching { SettingsStore.save(SettingsStore.get().copy(downloadAsCbz = it)) } })
        }
        Spacer(Modifier.height(28.dp))
        Text("Reader", color = MuTheme.Muted, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpenReader).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PlayArrow, null, tint = MuTheme.Vermilion, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Reader settings", color = MuTheme.Paper)
                Text("Scale, page gap, background, behaviour", color = MuTheme.Muted, fontSize = 12.sp)
            }
            Icon(Icons.Filled.ArrowForward, null, tint = MuTheme.Muted)
        }
    }
}

@Composable
private fun ThemeSwatch(p: MuPalette, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(120.dp).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(10.dp)).background(p.inkDark)) {
            Column(Modifier.fillMaxSize().padding(10.dp)) {
                Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(4.dp)).background(p.accentDark))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(p.panelDark))
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(4.dp)).background(p.accentDark.copy(alpha = 0.55f)))
            }
            if (selected) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(12.dp).clip(RoundedCornerShape(6.dp)).background(p.accentDark))
        }
        Spacer(Modifier.height(6.dp))
        Text(p.name, color = if (selected) MuTheme.Paper else MuTheme.Muted, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- Source browse (popular / latest / search grid) -----------------------------------------

@Composable
private fun SourceBrowseScreen(s: Screen.SourceBrowse, search: BrowseSearchState, onOpen: (MangaRef) -> Unit) {
    val scope = rememberCoroutineScope()
    var mode by remember(s) { mutableStateOf(if (s.startLatest) "latest" else "popular") }
    var query by remember(s) { mutableStateOf("") }
    val items = remember(s) { mutableStateListOf<MangaRef>() }
    var page by remember(s) { mutableStateOf(0) }
    var hasNext by remember(s) { mutableStateOf(false) }
    var loading by remember(s) { mutableStateOf(false) }
    var error by remember(s) { mutableStateOf<String?>(null) }
    var filters by remember(s) { mutableStateOf<FilterList?>(null) }
    var showFilter by remember(s) { mutableStateOf(false) }

    fun loadNext(reset: Boolean) {
        if (loading) return
        loading = true
        error = null
        val next = if (reset) 1 else page + 1
        scope.launch {
            val outcome =
                withContext(Dispatchers.IO) {
                    runCatching {
                        when (mode) {
                            "latest" -> SourceBrowser.latest(s.sourceId, next)
                            "search" -> SourceBrowser.search(s.sourceId, query, next)
                            "filter" -> SourceBrowser.searchWithFilters(s.sourceId, query, filters ?: FilterList(), next)
                            else -> SourceBrowser.popular(s.sourceId, next)
                        }
                    }
                }
            outcome.onSuccess { result ->
                if (reset) items.clear()
                result.mangas.forEach { m -> items.add(MangaRef(runCatching { m.title }.getOrDefault(m.url), m.url, m.thumbnail_url)) }
                hasNext = result.hasNextPage
            }.onFailure {
                if (reset) items.clear()
                error = it.message ?: it.javaClass.simpleName
            }
            page = next
            loading = false
        }
    }

    LaunchedEffect(s, mode) { if (mode != "search") loadNext(true) }
    // Search is driven from the top bar; run when the user submits.
    LaunchedEffect(search.submitTick) {
        if (search.submitTick == 0) return@LaunchedEffect
        if (search.active && search.query.isNotBlank()) {
            mode = "search"; query = search.query; loadNext(true)
        } else {
            mode = "popular"
        }
    }

    Box(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip("Popular", Icons.Filled.Favorite, mode == "popular") { mode = "popular" }
            ModeChip("Latest", Icons.Filled.Bolt, mode == "latest") { mode = "latest" }
            ModeChip("Filter", Icons.Filled.FilterList, mode == "filter") {
                scope.launch {
                    if (filters == null) filters = withContext(Dispatchers.IO) { runCatching { SourceBrowser.filterList(s.sourceId) }.getOrNull() }
                    showFilter = true
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f)) {
            if (Display.grid == GridMode.LIST) {
                LazyColumn(Modifier.fillMaxSize()) { items(items) { m -> MangaRow(s.sourceId, m, "") { onOpen(m) } } }
            } else {
                LazyVerticalGrid(
                    gridCell(),
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(items) { m -> MangaCover(s.sourceId, m, "", allowAdd = true) { onOpen(m) } }
                }
            }
            when {
                loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = MuTheme.Vermilion) }
                items.isEmpty() && error != null -> {
                    val cf = error.orEmpty().contains("cloudflare", ignoreCase = true)
                    Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                        Text(
                            when {
                                !Net.online -> "📡  You're offline.\n\nConnect to the internet to browse this source."
                                cf -> "🛡  This source is protected by Cloudflare.\n\nA Cloudflare bypass isn't supported yet — try another source, or open a series and use the open-in-browser button to read it on the site."
                                else -> "Couldn't load this source.\n\n$error"
                            },
                            color = MuTheme.Muted, textAlign = TextAlign.Center,
                        )
                    }
                }
                items.isEmpty() && !loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No results.", color = MuTheme.Muted) }
            }
        }
        if (hasNext) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { loadNext(false) }, enabled = !loading, modifier = Modifier.fillMaxWidth(), shape = BtnShape) {
                Text(if (loading) "Loading..." else "Load more")
            }
        }
      }
      if (showFilter) {
          FilterSheet(
              filters = filters,
              onApply = { showFilter = false; mode = "filter"; loadNext(true) },
              onReset = { filters = runCatching { SourceBrowser.filterList(s.sourceId) }.getOrNull() },
              onDismiss = { showFilter = false },
          )
      }
    }
}

// ---- Source filter sheet (the source's own getFilterList) -----------------------------------

@Composable
private fun FilterSheet(filters: FilterList?, onApply: () -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onDismiss))
        Column(Modifier.align(Alignment.CenterEnd).width(420.dp).fillMaxHeight().background(MuTheme.Panel)) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onReset) { Text("RESET", color = MuTheme.Vermilion) }
                Spacer(Modifier.weight(1f))
                Button(onClick = onApply, shape = BtnShape, colors = ButtonDefaults.buttonColors(containerColor = MuTheme.Vermilion)) { Text("FILTER") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            val list = filters?.list ?: emptyList()
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { Text("This source has no filters.", color = MuTheme.Muted) }
            } else {
                var rev by remember { mutableStateOf(0) }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    // Passing `rev` as a param recomposes each control when state changes, while the
                    // controls' own `remember` (expanded sections) survives.
                    list.forEach { f -> FilterControl(f, rev) { rev++ } }
                }
            }
        }
    }
}

/** A collapsible filter section (header + chevron), expanded on tap — like Suwayomi's filter sheet. */
@Composable
private fun CollapsibleFilter(name: String, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = MuTheme.Paper, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MuTheme.Muted)
        }
        if (expanded) Column(Modifier.padding(start = 4.dp, bottom = 8.dp)) { content() }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    }
}

@Composable
private fun FilterControl(f: Filter<*>, rev: Int, bump: () -> Unit) {
    when (f) {
        is Filter.Header -> Text(f.name, color = MuTheme.Paper, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
        is Filter.Separator -> HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 6.dp))
        is Filter.CheckBox ->
            Row(Modifier.fillMaxWidth().clickable { f.state = !f.state; bump() }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = f.state, onCheckedChange = { f.state = it; bump() })
                Text(f.name, color = MuTheme.Paper)
            }
        is Filter.TriState ->
            Row(Modifier.fillMaxWidth().clickable { f.state = (f.state + 1) % 3; bump() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val (sym, col) = when (f.state) { 1 -> "✓" to Color(0xFF4CAF50); 2 -> "✕" to Red18; else -> "○" to MuTheme.Muted }
                Text(sym, color = col, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                Spacer(Modifier.width(4.dp))
                Text(f.name, color = MuTheme.Paper)
            }
        is Filter.Text ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(f.name, color = MuTheme.Muted, fontSize = 12.sp)
                OutlinedTextField(f.state, { f.state = it; bump() }, Modifier.fillMaxWidth(), singleLine = true)
            }
        is Filter.Select<*> -> {
            var open by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text(f.name, color = MuTheme.Paper, modifier = Modifier.weight(1f))
                Box {
                    OutlinedButton(onClick = { open = true }, shape = BtnShape) { Text(f.values.getOrNull(f.state)?.toString() ?: "—", color = MuTheme.Paper) }
                    DropdownMenu(open, { open = false }) {
                        f.values.forEachIndexed { i, v -> DropdownMenuItem(text = { Text(v.toString()) }, onClick = { f.state = i; open = false; bump() }) }
                    }
                }
            }
        }
        is Filter.Sort ->
            CollapsibleFilter(f.name) {
                f.values.forEachIndexed { i, v ->
                    val sel = f.state
                    val isSel = sel?.index == i
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            f.state = if (isSel) Filter.Sort.Selection(i, !(sel?.ascending ?: false)) else Filter.Sort.Selection(i, false)
                            bump()
                        }.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (isSel) (if (sel?.ascending == true) "↑" else "↓") else " ", color = MuTheme.Vermilion, modifier = Modifier.width(18.dp))
                        Text(v, color = if (isSel) MuTheme.Vermilion else MuTheme.Paper)
                    }
                }
            }
        is Filter.Group<*> ->
            CollapsibleFilter(f.name) {
                f.state.filterIsInstance<Filter<*>>().forEach { sub -> FilterControl(sub, rev, bump) }
            }
        else -> {}
    }
}

// ---- Source configuration (the source's own getSourcePreferences) ---------------------------

@Composable
private fun SourceConfigScreen(sourceId: Long) {
    var prefs by remember(sourceId) { mutableStateOf<List<SourcePref>?>(null) }
    var error by remember(sourceId) { mutableStateOf<String?>(null) }
    var rev by remember(sourceId) { mutableStateOf(0) }
    LaunchedEffect(sourceId, rev) {
        withContext(Dispatchers.IO) {
            runCatching { SourcePreferences.list(sourceId) }.onSuccess { prefs = it; error = null }.onFailure { error = it.message }
        }
    }
    val list = prefs
    when {
        error != null -> Empty("Couldn't load source settings:\n$error")
        list == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = MuTheme.Vermilion) }
        list.isEmpty() -> Empty("This source has no settings.")
        else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
            items(list) { p ->
                SourcePrefRow(sourceId, p) { rev++ }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            }
        }
    }
}

@Composable
private fun PrefTitle(title: String, summary: String?) {
    Text(title, color = MuTheme.Paper)
    summary?.takeIf { it.isNotBlank() }?.let { Text(it, color = MuTheme.Muted, fontSize = 12.sp) }
}

@Composable
private fun SourcePrefRow(sourceId: Long, p: SourcePref, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val entries = p.entries
    val entryValues = p.entryValues
    fun save(v: String) { scope.launch { withContext(Dispatchers.IO) { SourcePreferences.set(sourceId, p.index, v) }; onChanged() } }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        when {
            p.type == "Boolean" ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { PrefTitle(p.title, p.summary) }
                    Switch(checked = p.value.toBooleanStrictOrNull() ?: false, enabled = p.enabled, onCheckedChange = { save(it.toString()) })
                }
            !entries.isNullOrEmpty() && p.type != "Set<String>" -> {
                var open by remember { mutableStateOf(false) }
                val curIdx = entryValues?.indexOf(p.value) ?: -1
                val curLabel = entries.getOrNull(curIdx) ?: p.value.ifBlank { "—" }
                PrefTitle(p.title, p.summary)
                Spacer(Modifier.height(4.dp))
                Box {
                    OutlinedButton(onClick = { open = true }, enabled = p.enabled, shape = BtnShape) { Text(curLabel, color = MuTheme.Paper) }
                    DropdownMenu(open, { open = false }) {
                        entries.forEachIndexed { i, label ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { open = false; save(entryValues?.getOrNull(i) ?: label) })
                        }
                    }
                }
            }
            !entries.isNullOrEmpty() && p.type == "Set<String>" -> {
                PrefTitle(p.title, p.summary)
                entries.forEachIndexed { i, label ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = p.value.contains(entryValues?.getOrNull(i) ?: label), enabled = p.enabled, onCheckedChange = { on ->
                            val sel = entryValues?.filterIndexed { j, v -> if (j == i) on else p.value.contains(v) } ?: emptyList()
                            save(sel.joinToString(","))
                        })
                        Text(label, color = MuTheme.Paper)
                    }
                }
            }
            else -> {
                PrefTitle(p.title, p.summary)
                var text by remember(p.value) { mutableStateOf(p.value) }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    text, { text = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = p.enabled,
                    trailingIcon = { TextButton(onClick = { save(text) }) { Text("Save", color = MuTheme.Vermilion) } },
                )
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MuTheme.Vermilion else MuTheme.Panel
    Row(
        Modifier.clip(BtnShape).background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) Color.White else MuTheme.Muted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (selected) Color.White else MuTheme.Muted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

// ---- Detail (two-column) --------------------------------------------------------------------

/** Build full details from the cached library entry (offline / instant). */
private fun cachedDetails(e: LibraryEntry): MangaDetails {
    val m = SManga.create().apply {
        url = e.mangaUrl; title = e.title; author = e.author; artist = e.artist
        description = e.description; thumbnail_url = e.thumbnailUrl; genre = e.genre; status = e.status
    }
    val chs = e.knownChapters.map { cr ->
        SChapter.create().apply { url = cr.url; name = cr.name; chapter_number = cr.number; scanlator = cr.scanlator; date_upload = cr.dateUpload }
    }
    return MangaDetails(m, chs)
}

private fun computeBrowseUrl(src: HttpSource?, url: String, title: String, m: SManga): String? =
    src?.let { hs ->
        runCatching {
            hs.getMangaUrl(
                SManga.create().apply {
                    this.url = url
                    this.title = runCatching { m.title }.getOrNull()?.takeIf { it.isNotBlank() } ?: title
                    thumbnail_url = m.thumbnail_url; artist = m.artist; author = m.author; description = m.description; genre = m.genre; status = m.status
                },
            )
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: if (url.startsWith("http")) url else hs.baseUrl.trimEnd('/') + "/" + url.trimStart('/')
    } ?: url.takeIf { it.startsWith("http") }

@Composable
private fun DetailScreen(s: Screen.Detail, onReadChapter: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var details by remember(s) { mutableStateOf<MangaDetails?>(null) }
    var error by remember(s) { mutableStateOf<String?>(null) }
    var inLibrary by remember(s) { mutableStateOf(false) }
    var sourceLabel by remember(s) { mutableStateOf("") }
    var browseUrl by remember(s) { mutableStateOf<String?>(null) }
    var bgBmp by remember(s) { mutableStateOf<ImageBitmap?>(null) }
    var coverTint by remember(s) { mutableStateOf<Color?>(null) }
    var readUrls by remember(s) { mutableStateOf(ReadStore.readUrls(s.sourceId, s.url)) }
    var dlVersion by remember(s) { mutableStateOf(0) }
    val settings = remember { runCatching { SettingsStore.get() }.getOrNull() }
    val bgEnabled = settings?.mangaThumbnailBackground ?: true
    val dynColors = settings?.dynamicThemeColors ?: true
    val dateFmt = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }

    var refreshTick by remember(s) { mutableStateOf(0) }
    var refreshing by remember(s) { mutableStateOf(false) }
    LaunchedEffect(s, refreshTick) {
        withContext(Dispatchers.IO) {
            val cached = LibraryStore.find(s.sourceId, s.url)
            inLibrary = cached != null
            sourceLabel = SourceManager.listInstalledSources().firstOrNull { it.id == s.sourceId }?.let { "${cleanName(it.name)} (${it.lang.uppercase()})" } ?: ""
            val src = runCatching { SourceManager.loadSource(s.sourceId) }.getOrNull() as? HttpSource
            val forceNetwork = refreshTick > 0
            // Library entries read from the cache (instant + offline); refresh re-fetches + updates it.
            if (cached != null && cached.knownChapters.isNotEmpty() && !forceNetwork) {
                val dd = cachedDetails(cached)
                details = dd; error = null
                browseUrl = computeBrowseUrl(src, s.url, s.title, dd.manga)
            } else if (!Net.online) {
                // Offline: use the cache if we have it, else a clear offline error (no hanging fetch).
                if (cached != null) { details = cachedDetails(cached); error = null; browseUrl = computeBrowseUrl(src, s.url, s.title, cachedDetails(cached).manga) } else error = "offline"
            } else {
                val dd = runCatching { withTimeoutOrNull(15_000) { SourceBrowser.details(s.sourceId, s.url) } }.getOrNull()
                if (dd != null) {
                    details = dd; error = null
                    browseUrl = computeBrowseUrl(src, s.url, s.title, dd.manga)
                    if (cached != null) runCatching { LibraryService.addKnown(s.sourceId, s.url, s.title, dd.manga, dd.chapters) }
                } else if (cached != null) {
                    details = cachedDetails(cached); browseUrl = computeBrowseUrl(src, s.url, s.title, cachedDetails(cached).manga)
                } else {
                    error = "Couldn't reach the source."
                }
            }
            refreshing = false
        }
    }
    fun refresh() { if (!refreshing) { refreshing = true; refreshTick++ } }
    val d = details
    LaunchedEffect(d?.manga?.thumbnail_url) {
        val url = d?.manga?.thumbnail_url
        if ((bgEnabled || dynColors) && !url.isNullOrBlank()) {
            val bmp = withContext(Dispatchers.IO) { ImageCache.cover(s.sourceId, url) }
            bgBmp = bmp
            if (bmp != null && dynColors) coverTint = withContext(Dispatchers.Default) { dominantColor(bmp) }
        }
    }
    // Recolor the WHOLE app from the cover while on this page (Suwayomi behaviour); revert on leave.
    LaunchedEffect(coverTint, dynColors) {
        MuTheme.dynamicOverride = if (dynColors && coverTint != null) dynamicPalette(coverTint!!) else null
    }
    DisposableEffect(s) { onDispose { MuTheme.dynamicOverride = null } }

    fun toggleLibrary() {
        val dd = details ?: return
        scope.launch {
            inLibrary = withContext(Dispatchers.IO) {
                if (LibraryService.isFollowed(s.sourceId, s.url)) {
                    LibraryService.remove(s.sourceId, s.url); false
                } else {
                    LibraryService.addKnown(s.sourceId, s.url, s.title, dd.manga, dd.chapters); true
                }
            }
            Toasts.success(if (inLibrary) "Added manga to library!" else "Removed manga from the library")
        }
    }
    fun setChapterRead(url: String, read: Boolean) {
        scope.launch { withContext(Dispatchers.IO) { ReadStore.setRead(s.sourceId, s.url, url, read) }; readUrls = ReadStore.readUrls(s.sourceId, s.url) }
    }
    fun downloadChapter(url: String, name: String) {
        DownloadQueue.enqueue(s.sourceId, s.url, s.title, url, name)
        Toasts.info("Queued download")
    }
    fun openInBrowser() { browseUrl?.let { u -> runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(u)) } } }
    fun openFolder() {
        runCatching {
            val dir = AppConfig.downloadsDir.resolve(DownloadManager.sanitize(s.title))
            val target = if (java.nio.file.Files.exists(dir)) dir else AppConfig.downloadsDir
            java.awt.Desktop.getDesktop().open(target.toFile())
        }
    }

    when {
        error != null -> Empty(if (!Net.online) "📡  You're offline.\n\nThis title isn't in your library, so it can't be loaded right now." else "Couldn't load: $error")
        d == null ->
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MuTheme.Vermilion)
                    Spacer(Modifier.height(16.dp))
                    Text(s.title, color = MuTheme.Paper, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Loading details & chapters…", color = MuTheme.Muted, fontSize = 12.sp)
                }
            }
        else -> {
            val continueCh = d.chapters.reversed().firstOrNull { it.url !in readUrls } ?: d.chapters.firstOrNull()
            // The whole MuTheme is already recolored from the cover (dynamicOverride), so just use it.
            val pageBg = MuTheme.Ink
            val acc = MuTheme.Vermilion
            Box(Modifier.fillMaxSize().background(pageBg)) {
              Row(Modifier.fillMaxSize()) {
                // LEFT: cover + info + library + description + tags
                Column(Modifier.weight(0.46f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp)) {
                  // Header zone with the Suwayomi-style blurred-cover backdrop (confined to here, not
                  // the chapter list): blurred cover dimmed ~75%, faded into the page bg on all edges.
                  Box {
                    if (bgEnabled) bgBmp?.let { bmp ->
                        Image(bmp, null, Modifier.matchParentSize().blur(18.dp), contentScale = ContentScale.Crop)
                        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.25f)))
                        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Transparent, 1f to pageBg)))
                        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to pageBg, 0.5f to Color.Transparent)))
                        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to pageBg, 0.5f to Color.Transparent)))
                        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0.5f to Color.Transparent, 1f to pageBg)))
                    }
                    Column {
                        Row {
                            // Fixed-size cover (don't let it scale to the column width).
                            Cover(s.sourceId, d.manga.thumbnail_url, s.title, Modifier.width(210.dp).aspectRatio(0.7f).clip(RoundedCornerShape(12.dp)))
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.title, color = MuTheme.Paper, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                d.manga.author?.takeIf { it.isNotBlank() }?.let { InfoRow("Author", it) }
                                d.manga.artist?.takeIf { it.isNotBlank() }?.let { InfoRow("Artist", it) }
                                InfoRow("Status", SourceBrowser.statusLabel(d.manga.status))
                                if (sourceLabel.isNotBlank()) InfoRow("Source", sourceLabel)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { toggleLibrary() }, shape = BtnShape,
                            border = BorderStroke(1.dp, acc),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = if (inLibrary) acc.copy(alpha = 0.16f) else Color.Transparent),
                        ) {
                            Icon(Icons.Filled.Favorite, null, tint = acc, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (inLibrary) "IN LIBRARY" else "ADD TO LIBRARY", color = acc, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        if (browseUrl != null) {
                            OutlinedButton(onClick = { openInBrowser() }, shape = BtnShape, border = BorderStroke(1.dp, acc), contentPadding = PaddingValues(horizontal = 12.dp)) {
                                Icon(Icons.Filled.OpenInNew, "Open in browser", tint = acc, modifier = Modifier.size(18.dp))
                            }
                        }
                        OutlinedButton(onClick = { openFolder() }, shape = BtnShape, border = BorderStroke(1.dp, acc), contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Icon(Icons.Filled.FolderOpen, "Open download folder", tint = acc, modifier = Modifier.size(18.dp))
                        }
                        }
                    }
                    }
                    Spacer(Modifier.height(14.dp))
                    var descExpanded by remember(s) { mutableStateOf(false) }
                    d.manga.description?.takeIf { it.isNotBlank() }?.let { desc ->
                        Box {
                            Text(
                                desc.trim(), color = MuTheme.Paper.copy(alpha = 0.85f), fontSize = 13.sp,
                                maxLines = if (descExpanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                            )
                            if (!descExpanded) {
                                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to pageBg)))
                            }
                        }
                        Box(Modifier.fillMaxWidth(), Alignment.Center) {
                            IconButton(onClick = { descExpanded = !descExpanded }, modifier = Modifier.size(32.dp)) {
                                Icon(if (descExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, "Toggle description", tint = MuTheme.Muted)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val genres = d.manga.genre?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                    if (genres.isNotEmpty()) {
                        val tagScroll = rememberScrollState()
                        Row(Modifier.fillMaxWidth().horizontalScroll(tagScroll), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            genres.forEach { g ->
                                Box(
                                    Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, MuTheme.Muted.copy(alpha = 0.4f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                                ) { Text(g, color = MuTheme.Paper, fontSize = 12.sp, maxLines = 1) }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        HorizontalScrollbar(
                            rememberScrollbarAdapter(tagScroll),
                            Modifier.fillMaxWidth(),
                            style = ScrollbarStyle(
                                minimalHeight = 16.dp, thickness = 4.dp, shape = RoundedCornerShape(2.dp),
                                hoverDurationMillis = 300, unhoverColor = MuTheme.Vermilion.copy(alpha = 0.5f), hoverColor = MuTheme.Vermilion,
                            ),
                        )
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
                // RIGHT: chapter list + RESUME
                Box(Modifier.weight(0.58f).fillMaxHeight()) {
                    val chapterListState = rememberLazyListState()
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${d.chapters.size} chapters", color = MuTheme.Paper, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(onClick = { refresh() }, enabled = !refreshing) {
                                if (refreshing) CircularProgressIndicator(color = MuTheme.Muted, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                else Icon(Icons.Filled.Refresh, "Refresh from source", tint = MuTheme.Muted)
                            }
                            IconButton(onClick = {
                                scope.launch { withContext(Dispatchers.IO) { d.chapters.forEach { ReadStore.setRead(s.sourceId, s.url, it.url, true) } }; readUrls = ReadStore.readUrls(s.sourceId, s.url) }
                            }) { Icon(Icons.Filled.DoneAll, "Mark all read", tint = MuTheme.Muted) }
                            IconButton(onClick = {
                                DownloadQueue.enqueueAll(s.sourceId, s.url, s.title, d.chapters.map { it.url to it.name })
                                Toasts.info("Queued ${d.chapters.size} chapters")
                            }) { Icon(Icons.Filled.Download, "Download all", tint = MuTheme.Muted) }
                        }
                      Box(Modifier.weight(1f)) {
                        LazyColumn(state = chapterListState, modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 18.dp)) {
                            items(d.chapters) { ch ->
                                val read = ch.url in readUrls
                                val downloaded = remember(ch.url, DownloadQueue.completedTick) { DownloadManager.isDownloaded(s.title, ch.name) }
                                val dl = DownloadQueue.items.firstOrNull { it.chapterUrl == ch.url }
                                var chMenu by remember(ch.url) { mutableStateOf(false) }
                                Card(
                                    onClick = { onReadChapter(ch.url, ch.name) },
                                    colors = CardDefaults.cardColors(containerColor = if (read) MuTheme.Ink else MuTheme.Panel),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f).padding(14.dp)) {
                                            Text(ch.name, color = if (read) MuTheme.Muted else MuTheme.Paper, fontWeight = if (read) FontWeight.Normal else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Row {
                                                val meta = listOfNotNull(
                                                    ch.scanlator?.takeIf { it.isNotBlank() },
                                                    ch.date_upload.takeIf { it > 0 }?.let { dateFmt.format(Date(it)) },
                                                ).joinToString("  ·  ")
                                                if (meta.isNotBlank()) Text(meta, color = MuTheme.Muted, fontSize = 11.sp)
                                                if (downloaded) Text("  ·  Downloaded", color = acc, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                        // Live download status from the queue.
                                        when (dl?.state) {
                                            DlState.DOWNLOADING -> {
                                                val pct = if (dl.pagesTotal > 0) dl.pagesDone.toFloat() / dl.pagesTotal else 0f
                                                Box(Modifier.size(30.dp), Alignment.Center) { CircularProgressIndicator(progress = { pct }, modifier = Modifier.size(22.dp), color = acc, strokeWidth = 2.5.dp) }
                                                Text("${(pct * 100).toInt()}%", color = MuTheme.Muted, fontSize = 10.sp)
                                            }
                                            DlState.QUEUED -> Text("Queued", color = MuTheme.Muted, fontSize = 11.sp)
                                            DlState.ERROR -> Text("Failed", color = Red18, fontSize = 11.sp)
                                            else -> {}
                                        }
                                        Box {
                                            IconButton(onClick = { chMenu = true }) { Icon(Icons.Filled.MoreVert, "Chapter menu", tint = MuTheme.Muted) }
                                            DropdownMenu(chMenu, onDismissRequest = { chMenu = false }) {
                                                DropdownMenuItem(text = { Text(if (downloaded) "Downloaded" else "Download") }, enabled = !downloaded && dl == null, onClick = { chMenu = false; downloadChapter(ch.url, ch.name) })
                                                DropdownMenuItem(text = { Text(if (read) "Mark as unread" else "Mark as read") }, onClick = { chMenu = false; setChapterRead(ch.url, !read) })
                                            }
                                        }
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(72.dp)) }
                        }
                        VerticalScrollbar(
                            rememberScrollbarAdapter(chapterListState),
                            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
                            style = ScrollbarStyle(
                                minimalHeight = 48.dp, thickness = 8.dp, shape = RoundedCornerShape(4.dp),
                                hoverDurationMillis = 300, unhoverColor = acc.copy(alpha = 0.45f), hoverColor = acc,
                            ),
                        )
                      }
                    }
                    if (continueCh != null) {
                        ExtendedFloatingActionButton(
                            onClick = { onReadChapter(continueCh.url, continueCh.name) },
                            containerColor = acc,
                            contentColor = Color.White,
                            icon = { Icon(Icons.Filled.PlayArrow, null) },
                            text = { Text(if (readUrls.isEmpty()) "Start" else "Resume") },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                        )
                    }
                }
              }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 1.dp)) {
        Text("$label  ", color = MuTheme.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = MuTheme.Paper, fontSize = 12.sp)
    }
}

// ---- Reader settings ------------------------------------------------------------------------

@Composable
private fun PrefHeader(text: String) {
    Text(text, color = MuTheme.Paper, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun SelChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, shape = BtnShape, colors = ButtonDefaults.buttonColors(containerColor = MuTheme.Vermilion)) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
    } else {
        OutlinedButton(onClick = onClick, shape = BtnShape, border = BorderStroke(1.dp, MuTheme.Vermilion)) { Text(label, color = MuTheme.Vermilion, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun ReaderSettingsScreen() {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = MuTheme.Ink, contentColor = MuTheme.Vermilion) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Layout") })
            Tab(tab == 1, { tab = 1 }, text = { Text("General") })
            Tab(tab == 2, { tab = 2 }, text = { Text("Behaviour") })
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            when (tab) {
                0 -> {
                    PrefHeader("Reading mode")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { SelChip("Continuous vertical", true) {} }
                    Text("Paged / double-page / horizontal modes coming.", color = MuTheme.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(20.dp))
                    PrefHeader("Scale type")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelChip("FIT WIDTH", ReaderPrefs.scaleType == "FIT_WIDTH") { ReaderPrefs.applyScale("FIT_WIDTH") }
                        SelChip("ORIGINAL SIZE", ReaderPrefs.scaleType == "ORIGINAL") { ReaderPrefs.applyScale("ORIGINAL") }
                    }
                    Spacer(Modifier.height(20.dp))
                    PrefHeader("Page gap")
                    Text("${ReaderPrefs.pageGap}px", color = MuTheme.Muted, fontSize = 12.sp)
                    Slider(value = ReaderPrefs.pageGap.toFloat(), onValueChange = { ReaderPrefs.applyGap(it.toInt()) }, valueRange = 0f..40f)
                }
                1 -> {
                    PrefHeader("Background color")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("THEME", "BLACK", "GRAY", "WHITE").forEach { bg ->
                            SelChip(bg, ReaderPrefs.background == bg) { ReaderPrefs.applyBackground(bg) }
                        }
                    }
                }
                else -> {
                    Row(Modifier.fillMaxWidth().clickable { ReaderPrefs.applySkipDuplicates(!ReaderPrefs.skipDuplicates) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = ReaderPrefs.skipDuplicates, onCheckedChange = { ReaderPrefs.applySkipDuplicates(it) })
                        Column {
                            Text("Skip duplicate chapters", color = MuTheme.Paper)
                            Text("Stay on the current scanlator; don't re-read the same chapter number", color = MuTheme.Muted, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("More behaviour options (scroll amount, auto-scroll, preload) coming.", color = MuTheme.Muted, fontSize = 11.sp)
                }
            }
        }
    }
}

// ---- Reader (streaming long-strip, scale by width) ------------------------------------------

private data class ReaderChapter(val url: String, val name: String, val number: Float, val scanlator: String?)

/** A reader page resolved either to a local download or a streamed source page. */
private sealed interface ReaderPageRef {
    class Streamed(val sourceId: Long, val page: Page) : ReaderPageRef
    class Local(val local: LocalChapter, val index: Int) : ReaderPageRef
}

/**
 * Suwayomi's removeDuplicates: keep one chapter per number, preferring the one the reader is on,
 * then the last one from the current scanlator, then the last available. So forward/back navigation
 * stays on your provider and only switches scanlators when yours is missing — no duplicate reads.
 */
private fun dedupChapters(current: ReaderChapter?, all: List<ReaderChapter>): List<ReaderChapter> {
    if (current == null) return all
    val keep =
        all.groupBy { it.number }.flatMap { (num, g) ->
            if (num < 0f) g // unnumbered chapters aren't collapsed
            else listOf(g.firstOrNull { it.url == current.url } ?: g.lastOrNull { it.scanlator == current.scanlator } ?: g.last())
        }.map { it.url }.toSet()
    return all.filter { it.url in keep }
}

@Composable
private fun ReaderScreen(s: Screen.Reader, panelOpen: Boolean, onOpenSettings: () -> Unit, onOpenChapter: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var pageRefs by remember(s.chapterUrl) { mutableStateOf<List<ReaderPageRef>>(emptyList()) }
    var chapters by remember(s.mangaUrl) { mutableStateOf<List<ReaderChapter>>(emptyList()) }
    var loading by remember(s.chapterUrl) { mutableStateOf(true) }

    LaunchedEffect(s.chapterUrl) {
        loading = true
        withContext(Dispatchers.IO) {
            ReadStore.setRead(s.sourceId, s.mangaUrl, s.chapterUrl, true)
            runCatching { HistoryStore.record(s.sourceId, s.mangaUrl, s.mangaTitle, s.chapterUrl, s.chapterName) }
            // Pages: the local download (folder/CBZ) FIRST — instant + offline. Only stream when online.
            val local = runCatching { LocalChapterReader.localChapter(s.mangaTitle, s.chapterName) }.getOrNull()
            pageRefs = when {
                local != null -> (0 until local.count).map { ReaderPageRef.Local(local, it) }
                Net.online -> runCatching { withTimeoutOrNull(25_000) { SourceImage.pageList(s.sourceId, s.chapterUrl).map { ReaderPageRef.Streamed(s.sourceId, it) } } }.getOrNull() ?: emptyList()
                else -> emptyList()
            }
            // Chapter list for prev/next nav: cached when offline; fetch (with timeout) + cache fallback when online.
            if (chapters.isEmpty()) {
                val cachedChs = LibraryStore.find(s.sourceId, s.mangaUrl)?.knownChapters?.map { ReaderChapter(it.url, it.name, it.number, it.scanlator) }
                chapters = if (Net.online) {
                    runCatching {
                        withTimeoutOrNull(12_000) {
                            SourceBrowser.details(s.sourceId, s.mangaUrl).chapters.map { ReaderChapter(it.url, it.name, runCatching { it.chapter_number }.getOrDefault(-1f), runCatching { it.scanlator }.getOrNull()) }
                        }
                    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: cachedChs ?: emptyList()
                } else {
                    cachedChs ?: emptyList()
                }
            }
        }
        loading = false
        listState.scrollToItem(0)
    }

    // Scanlator-aware prev/next: navigate the de-duplicated list, preferring the current scanlator.
    val current = chapters.firstOrNull { it.url == s.chapterUrl }
    val nav = if (ReaderPrefs.skipDuplicates) dedupChapters(current, chapters) else chapters
    val idx = nav.indexOfFirst { it.url == s.chapterUrl }
    val prevChapter = nav.getOrNull(idx + 1)
    val nextChapter = nav.getOrNull(idx - 1)
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val showTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 1 } }

    // Auto-scroll: continuously scroll while enabled; faster as the seconds value lowers.
    LaunchedEffect(ReaderPrefs.autoScroll, ReaderPrefs.autoScrollSeconds) {
        if (ReaderPrefs.autoScroll) {
            val px = 180f / ReaderPrefs.autoScrollSeconds.coerceAtLeast(1)
            while (true) { listState.scrollBy(px); delay(33) }
        }
    }

    Row(Modifier.fillMaxSize()) {
        if (panelOpen) {
            ReaderSidebar(
                title = s.mangaTitle, chapterName = s.chapterName, chapters = chapters, currentChapterUrl = s.chapterUrl,
                sourceId = s.sourceId, mangaUrl = s.mangaUrl, pageCount = pageRefs.size, currentPage = currentPage,
                prevChapter = prevChapter, nextChapter = nextChapter, onOpenChapter = onOpenChapter, onOpenSettings = onOpenSettings,
                onJumpPage = { i -> scope.launch { listState.animateScrollToItem(i.coerceIn(0, (pageRefs.size - 1).coerceAtLeast(0))) } },
            )
            Box(Modifier.width(1.dp).fillMaxSize().background(MaterialTheme.colorScheme.outline))
        }
        Box(Modifier.fillMaxSize().background(ReaderPrefs.bg(MuTheme.Ink))) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(ReaderPrefs.pageGap.dp)) { items(pageRefs) { ref -> ReaderPage(ref) } }
            if (loading) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = MuTheme.Vermilion) }
            if (!loading && pageRefs.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                    Text(
                        if (!Net.online) "📡  You're offline.\n\nThis chapter isn't downloaded, so it can't be read right now." else "Couldn't load this chapter's pages.",
                        color = MuTheme.Muted, textAlign = TextAlign.Center,
                    )
                }
            }
            if (showTop) {
                FloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    containerColor = MuTheme.Vermilion, contentColor = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                ) { Icon(Icons.Filled.ArrowUpward, "Scroll to top") }
            }
        }
    }
}

@Composable
private fun ReaderSidebar(
    title: String, chapterName: String, chapters: List<ReaderChapter>, currentChapterUrl: String,
    sourceId: Long, mangaUrl: String, pageCount: Int, currentPage: Int,
    prevChapter: ReaderChapter?, nextChapter: ReaderChapter?, onOpenSettings: () -> Unit = {},
    onOpenChapter: (String, String) -> Unit, onJumpPage: (Int) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.width(300.dp).fillMaxSize().background(MuTheme.Panel).padding(16.dp)) {
        Text(title, color = MuTheme.Paper, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val curScan = chapters.firstOrNull { it.url == currentChapterUrl }?.scanlator?.takeIf { it.isNotBlank() }
        Text(if (curScan != null) "$chapterName  ·  $curScan" else chapterName, color = MuTheme.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(16.dp))
        Text("PAGE", color = MuTheme.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onJumpPage(currentPage - 1) }, enabled = currentPage > 0) { Text("<") }
            Text("${(currentPage + 1).coerceAtMost(pageCount.coerceAtLeast(1))} / $pageCount", color = MuTheme.Paper, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            OutlinedButton(onClick = { onJumpPage(currentPage + 1) }, enabled = currentPage < pageCount - 1) { Text(">") }
        }
        Spacer(Modifier.height(14.dp))
        Text("CHAPTER", color = MuTheme.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { prevChapter?.let { onOpenChapter(it.url, it.name) } }, enabled = prevChapter != null) { Text("<") }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) { Text(chapterName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                    // Full list (every scanlator) so you can still pick any version manually,
                    // opened scrolled to — and highlighting — the chapter you're currently on.
                    val scroll = rememberScrollState()
                    val density = LocalDensity.current
                    val currentIdx = chapters.indexOfFirst { it.url == currentChapterUrl }
                    LaunchedEffect(menuOpen) {
                        if (menuOpen && currentIdx > 0) scroll.scrollTo((currentIdx * with(density) { 52.dp.toPx() }).toInt())
                    }
                    Column(Modifier.heightIn(max = 460.dp).verticalScroll(scroll)) {
                        chapters.forEach { ch ->
                            val isCurrent = ch.url == currentChapterUrl
                            val read = ReadStore.isRead(sourceId, mangaUrl, ch.url) || isCurrent
                            DropdownMenuItem(
                                modifier = if (isCurrent) Modifier.background(MuTheme.Vermilion.copy(alpha = 0.15f)) else Modifier,
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(if (read) MuTheme.VermilionDim else MuTheme.Vermilion))
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(ch.name, color = if (isCurrent) MuTheme.Vermilion else if (read) MuTheme.Muted else MuTheme.Paper, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal)
                                            ch.scanlator?.takeIf { it.isNotBlank() }?.let { Text(it, color = MuTheme.Muted, fontSize = 10.sp) }
                                        }
                                    }
                                },
                                onClick = { menuOpen = false; onOpenChapter(ch.url, ch.name) },
                            )
                        }
                    }
                }
            }
            OutlinedButton(onClick = { nextChapter?.let { onOpenChapter(it.url, it.name) } }, enabled = nextChapter != null) { Text(">") }
        }
        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        // Inline quick controls (Suwayomi-style).
        Text("SCALE", color = MuTheme.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelChip("Fit width", ReaderPrefs.scaleType == "FIT_WIDTH") { ReaderPrefs.applyScale("FIT_WIDTH") }
            SelChip("Original", ReaderPrefs.scaleType == "ORIGINAL") { ReaderPrefs.applyScale("ORIGINAL") }
        }
        Spacer(Modifier.height(12.dp))
        Text("AUTO SCROLL", color = MuTheme.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelChip(if (ReaderPrefs.autoScroll) "On" else "Off", ReaderPrefs.autoScroll) { ReaderPrefs.autoScroll = !ReaderPrefs.autoScroll }
            OutlinedButton(onClick = { ReaderPrefs.autoScrollSeconds = (ReaderPrefs.autoScrollSeconds - 1).coerceAtLeast(1) }, shape = BtnShape, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("−", color = MuTheme.Vermilion) }
            Text("${ReaderPrefs.autoScrollSeconds}s", color = MuTheme.Paper)
            OutlinedButton(onClick = { ReaderPrefs.autoScrollSeconds += 1 }, shape = BtnShape, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("+", color = MuTheme.Vermilion) }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth(), shape = BtnShape, border = BorderStroke(1.dp, MuTheme.Vermilion)) {
            Icon(Icons.Filled.Settings, null, tint = MuTheme.Vermilion, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("All reader settings", color = MuTheme.Vermilion)
        }
    }
}

@Composable
private fun ReaderPage(ref: ReaderPageRef) {
    var bmp by remember(ref) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(ref) { mutableStateOf(false) }
    LaunchedEffect(ref) {
        val bytes = withContext(Dispatchers.IO) {
            when (ref) {
                is ReaderPageRef.Streamed -> SourceImage.pageBytes(ref.sourceId, ref.page)
                is ReaderPageRef.Local -> ref.local.bytes(ref.index)
            }
        }
        val b = bytes?.let { decode(it) }
        if (b == null) failed = true else bmp = b
    }
    val b = bmp
    when {
        b != null -> {
            val ar = b.width.toFloat() / b.height.toFloat()
            if (ReaderPrefs.scaleType == "ORIGINAL") {
                // Natural size, centred; never wider than the column (then it's effectively fit-width).
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(b, null, Modifier.widthIn(max = with(LocalDensity.current) { b.width.toDp() }).fillMaxWidth().aspectRatio(ar), contentScale = ContentScale.Fit)
                }
            } else {
                Image(b, null, Modifier.fillMaxWidth().aspectRatio(ar), contentScale = ContentScale.FillWidth)
            }
        }
        failed -> Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) { Text("page failed", color = MuTheme.Muted) }
        else -> Box(Modifier.fillMaxWidth().height(420.dp), Alignment.Center) { CircularProgressIndicator(color = MuTheme.Vermilion) }
    }
}

private fun removeExtension(ext: InstalledExtension) {
    runCatching {
        InstalledStore.remove(ext.pkg)
        runCatching { Files.deleteIfExists(java.nio.file.Path.of(ext.jarPath)) }
        runCatching { Files.deleteIfExists(java.nio.file.Path.of(ext.jarPath.removeSuffix(".jar") + ".apk")) }
    }
}

@Composable
private fun Empty(message: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) { Text(message, color = MuTheme.Muted, textAlign = TextAlign.Center) }
}
