/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package mangautils.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import mangautils.core.config.AppConfig
import mangautils.core.download.ChapterSelect
import mangautils.core.download.DownloadManager
import mangautils.core.download.SourceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mangautils.core.config.SettingsStore
import mangautils.core.extension.ExtensionInstaller
import mangautils.core.extension.ExtensionRepoClient
import mangautils.core.extension.ExtensionRepoEntry
import mangautils.core.extension.InstalledExtension
import mangautils.core.extension.InstalledStore
import mangautils.core.extension.RepoStore
import mangautils.core.library.LibraryEntry
import mangautils.core.library.LibraryService
import mangautils.core.library.LibraryStore
import mangautils.core.library.ReadStore
import mangautils.core.source.MangaDetails
import mangautils.core.source.SourceBrowser
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

    data class Stub(val label: String) : Screen
}

private data class MangaRef(val title: String, val url: String, val thumb: String?)

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
    fun go(s: Screen) = backStack.add(s)
    fun back() { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

    Surface(Modifier.fillMaxSize(), color = MuTheme.Ink) {
        Column(Modifier.fillMaxSize()) {
            TopBar(titleFor(current), backStack.size > 1, ::back, onMenu = { sidebarOpen = !sidebarOpen }, search = if (current is Screen.SourceBrowse) srcSearch else null) {
                // Per-screen actions, remastered into the top bar.
                when (current) {
                    is Screen.Browse -> IconButton(onClick = { showRepos = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.Add, "Add repository", tint = MuTheme.Paper) }
                    is Screen.SourceBrowse -> {
                        val sb = current
                        IconButton(onClick = {}, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.GridView, "Layout", tint = MuTheme.Paper) }
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
                        Screen.Browse -> BrowseScreen(dataVersion) { id, name, latest -> go(Screen.SourceBrowse(id, name, latest)) }
                        is Screen.SourceBrowse -> SourceBrowseScreen(s, srcSearch) { m -> go(Screen.Detail(s.sourceId, m.url, m.title)) }
                        is Screen.SourceConfig -> SourceConfigScreen(s.sourceId)
                        is Screen.Detail -> DetailScreen(s) { chUrl, chName -> go(Screen.Reader(s.sourceId, s.url, s.title, chUrl, chName)) }
                        is Screen.Reader -> ReaderScreen(s, sidebarOpen) { chUrl, chName ->
                            backStack[backStack.lastIndex] = Screen.Reader(s.sourceId, s.mangaUrl, s.mangaTitle, chUrl, chName)
                        }
                        Screen.Settings -> SettingsScreen()
                        is Screen.Stub -> Empty("${s.label} — coming soon")
                    }
                }
            }
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
            NavItem("Updates", Icons.Filled.Update, Screen.Stub("Updates")),
            NavItem("History", Icons.Filled.History, Screen.Stub("History")),
            NavItem("Browse", Icons.Filled.Explore, Screen.Browse),
            NavItem("Downloads", Icons.Filled.Download, Screen.Stub("Downloads")),
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
                Icon(item.icon, item.label, tint = if (selected) MuTheme.Vermilion else MuTheme.Muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(14.dp))
                Text(item.label, color = if (selected) MuTheme.Paper else MuTheme.Muted, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

// ---- image loading ---------------------------------------------------------------------------

private object ImageCache {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    fun cover(sourceId: Long, url: String): ImageBitmap? {
        cache[url]?.let { return it }
        val img = SourceImage.coverBytes(sourceId, url)?.let { decode(it) } ?: return null
        cache[url] = img
        return img
    }

    /** Plain HTTP image (e.g. extension icons from a repo) — no source headers needed. */
    fun url(u: String): ImageBitmap? {
        cache[u]?.let { return it }
        val bytes = runCatching { java.net.URI(u).toURL().openStream().use { it.readBytes() } }.getOrNull() ?: return null
        val img = decode(bytes) ?: return null
        cache[u] = img
        return img
    }
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
    LazyVerticalGrid(
        GridCells.Adaptive(168.dp),
        Modifier.fillMaxSize().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(entries) { e -> LibraryCard(e, onOpen = { onOpen(e) }) { refresh++ } }
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
                        scope.launch { withContext(Dispatchers.IO) { LibraryService.remove(e.sourceId, e.mangaUrl) }; onChanged() }
                    },
                )
            }
        }
    }
}

@Composable
private fun MangaCover(sourceId: Long, m: MangaRef, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MuTheme.Panel)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.69f)) {
            Cover(sourceId, m.thumb, m.title, Modifier.fillMaxSize())
            // Bottom gradient scrim so the overlaid title stays readable on any cover.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.92f))))
            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 7.dp)) {
                Text(m.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp)
            }
        }
    }
}

// ---- Browse (tabs: Source / Extension / Migrate) --------------------------------------------

@Composable
private fun BrowseScreen(dataVersion: Int, onOpenSource: (Long, String, Boolean) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = MuTheme.Ink, contentColor = MuTheme.Vermilion) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Source") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Extension") })
            Tab(tab == 2, { tab = 2 }, text = { Text("Migrate") })
        }
        when (tab) {
            0 -> SourceTab(onOpenSource)
            1 -> ExtensionTab(dataVersion)
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
    val byLang = sources.groupBy { it.third.ifBlank { "other" } }.toSortedMap()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        byLang.forEach { (lang, group) ->
            item { SectionHeader(lang.uppercase()) }
            items(group) { s -> SourceRow(iconBySource[s.first], s.first, s.second, s.third, s.first in nsfwIds, onOpen) }
        }
    }
}

@Composable
private fun SourceRow(iconUrl: String?, id: Long, name: String, lang: String, nsfw: Boolean, onOpen: (Long, String, Boolean) -> Unit) {
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
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
}

@Composable
private fun ExtensionTab(dataVersion: Int) {
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
                    ExtRow(repo?.let { extIconUrl(it, ext.pkg) }, ext.name, ext.lang, ext.versionName, ext.nsfw, repo?.let { repoShortName(it) }, installed = true) {
                        removeExtension(ext); refresh++
                    }
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
private fun ExtRow(iconUrl: String?, name: String, lang: String, version: String, nsfw: Boolean, repoShort: String?, installed: Boolean, onAction: () -> Unit) {
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
            IconButton(onClick = {}) { Icon(Icons.Filled.Settings, "Settings", tint = MuTheme.Muted) }
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

// ---- Settings (appearance / themes) ---------------------------------------------------------

@Composable
private fun SettingsScreen() {
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
            LazyVerticalGrid(
                GridCells.Adaptive(168.dp),
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(items) { m -> MangaCover(s.sourceId, m, "") { onOpen(m) } }
            }
            when {
                loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = MuTheme.Vermilion) }
                items.isEmpty() && error != null ->
                    Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                        Text(
                            "Couldn't load this source.\n\n$error\n\nSome sources (e.g. Aqua Manga, Asura Scans) are behind Cloudflare, which isn't supported yet. Try another source.",
                            color = MuTheme.Muted, textAlign = TextAlign.Center,
                        )
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
                    key(rev) { list.forEach { f -> FilterControl(f) { rev++ } } }
                }
            }
        }
    }
}

@Composable
private fun FilterControl(f: Filter<*>, bump: () -> Unit) {
    when (f) {
        is Filter.Header -> Text(f.name, color = MuTheme.Paper, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
        is Filter.Separator -> HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 6.dp))
        is Filter.CheckBox ->
            Row(Modifier.fillMaxWidth().clickable { f.state = !f.state; bump() }, verticalAlignment = Alignment.CenterVertically) {
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
        is Filter.Sort -> {
            Text(f.name, color = MuTheme.Paper, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
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
        is Filter.Group<*> -> {
            Text(f.name, color = MuTheme.Paper, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            Column(Modifier.padding(start = 8.dp)) {
                f.state.filterIsInstance<Filter<*>>().forEach { sub -> FilterControl(sub, bump) }
            }
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

    LaunchedEffect(s) {
        withContext(Dispatchers.IO) {
            inLibrary = LibraryService.isFollowed(s.sourceId, s.url)
            sourceLabel = SourceManager.listInstalledSources().firstOrNull { it.id == s.sourceId }?.let { "${cleanName(it.name)} (${it.lang.uppercase()})" } ?: ""
            val src = runCatching { SourceManager.loadSource(s.sourceId) }.getOrNull() as? HttpSource
            runCatching { SourceBrowser.details(s.sourceId, s.url) }
                .onSuccess { dd ->
                    details = dd
                    // Suwayomi's "realUrl": call getMangaUrl() with a fully-populated SManga (some
                    // sources need more than the url to build it), wrapped in runCatching.
                    browseUrl = src?.let { hs ->
                        runCatching {
                            hs.getMangaUrl(
                                SManga.create().apply {
                                    url = s.url
                                    title = runCatching { dd.manga.title }.getOrNull() ?: s.title
                                    thumbnail_url = dd.manga.thumbnail_url
                                    artist = dd.manga.artist
                                    author = dd.manga.author
                                    description = dd.manga.description
                                    genre = dd.manga.genre
                                    status = dd.manga.status
                                },
                            )
                        }.getOrNull()?.takeIf { it.isNotBlank() }
                    } ?: if (s.url.startsWith("http")) s.url else src?.let { it.baseUrl.trimEnd('/') + "/" + s.url.trimStart('/') }
                }
                .onFailure { error = it.message }
        }
    }
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
        }
    }
    fun setChapterRead(url: String, read: Boolean) {
        scope.launch { withContext(Dispatchers.IO) { ReadStore.setRead(s.sourceId, s.url, url, read) }; readUrls = ReadStore.readUrls(s.sourceId, s.url) }
    }
    fun downloadChapter(url: String) {
        scope.launch { withContext(Dispatchers.IO) { runCatching { DownloadManager().download(SourceRef(s.sourceId, s.url), select = ChapterSelect.Urls(setOf(url))) } }; dlVersion++ }
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
        error != null -> Empty("Couldn't load: $error")
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
                        Text("${d.chapters.size} chapters", color = MuTheme.Paper, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
                      Box(Modifier.weight(1f)) {
                        LazyColumn(state = chapterListState, modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 18.dp)) {
                            items(d.chapters) { ch ->
                                val read = ch.url in readUrls
                                val downloaded = remember(ch.url, dlVersion) { DownloadManager.isDownloaded(s.title, ch.name) }
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
                                        Box {
                                            IconButton(onClick = { chMenu = true }) { Icon(Icons.Filled.MoreVert, "Chapter menu", tint = MuTheme.Muted) }
                                            DropdownMenu(chMenu, onDismissRequest = { chMenu = false }) {
                                                DropdownMenuItem(text = { Text(if (downloaded) "Downloaded" else "Download") }, enabled = !downloaded, onClick = { chMenu = false; downloadChapter(ch.url) })
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

// ---- Reader (streaming long-strip, scale by width) ------------------------------------------

private data class ReaderChapter(val url: String, val name: String, val number: Float, val scanlator: String?)

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
private fun ReaderScreen(s: Screen.Reader, panelOpen: Boolean, onOpenChapter: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var pages by remember(s.chapterUrl) { mutableStateOf<List<Page>>(emptyList()) }
    var chapters by remember(s.mangaUrl) { mutableStateOf<List<ReaderChapter>>(emptyList()) }
    var loading by remember(s.chapterUrl) { mutableStateOf(true) }

    LaunchedEffect(s.chapterUrl) {
        loading = true
        withContext(Dispatchers.IO) {
            ReadStore.setRead(s.sourceId, s.mangaUrl, s.chapterUrl, true)
            if (chapters.isEmpty()) {
                chapters = runCatching {
                    SourceBrowser.details(s.sourceId, s.mangaUrl).chapters.map {
                        ReaderChapter(it.url, it.name, runCatching { it.chapter_number }.getOrDefault(-1f), runCatching { it.scanlator }.getOrNull())
                    }
                }.getOrDefault(emptyList())
            }
            pages = SourceImage.pageList(s.sourceId, s.chapterUrl)
        }
        loading = false
        listState.scrollToItem(0)
    }

    // Scanlator-aware prev/next: navigate the de-duplicated list, preferring the current scanlator.
    val current = chapters.firstOrNull { it.url == s.chapterUrl }
    val nav = dedupChapters(current, chapters)
    val idx = nav.indexOfFirst { it.url == s.chapterUrl }
    val prevChapter = nav.getOrNull(idx + 1)
    val nextChapter = nav.getOrNull(idx - 1)
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val showTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 1 } }

    Row(Modifier.fillMaxSize()) {
        if (panelOpen) {
            ReaderSidebar(
                title = s.mangaTitle, chapterName = s.chapterName, chapters = chapters, currentChapterUrl = s.chapterUrl,
                sourceId = s.sourceId, mangaUrl = s.mangaUrl, pageCount = pages.size, currentPage = currentPage,
                prevChapter = prevChapter, nextChapter = nextChapter, onOpenChapter = onOpenChapter,
                onJumpPage = { i -> scope.launch { listState.animateScrollToItem(i.coerceIn(0, (pages.size - 1).coerceAtLeast(0))) } },
            )
            Box(Modifier.width(1.dp).fillMaxSize().background(MaterialTheme.colorScheme.outline))
        }
        Box(Modifier.fillMaxSize().background(MuTheme.Ink)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) { items(pages) { p -> ReaderPage(s.sourceId, p) } }
            if (loading) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = MuTheme.Vermilion) }
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
    prevChapter: ReaderChapter?, nextChapter: ReaderChapter?,
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
        Text("Continuous vertical · Fit width", color = MuTheme.Paper, fontSize = 13.sp)
        Text("(more reading modes & filters coming)", color = MuTheme.Muted, fontSize = 11.sp)
    }
}

@Composable
private fun ReaderPage(sourceId: Long, page: Page) {
    var bmp by remember(page) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(page) { mutableStateOf(false) }
    LaunchedEffect(page) {
        val b = withContext(Dispatchers.IO) { SourceImage.pageBytes(sourceId, page)?.let { decode(it) } }
        if (b == null) failed = true else bmp = b
    }
    val b = bmp
    when {
        b != null -> Image(b, null, Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height.toFloat()), contentScale = ContentScale.FillWidth)
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
