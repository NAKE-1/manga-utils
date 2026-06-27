/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package mangautils.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    data class Detail(val sourceId: Long, val url: String, val title: String) : Screen

    data class Reader(
        val sourceId: Long,
        val mangaUrl: String,
        val mangaTitle: String,
        val chapterUrl: String,
        val chapterName: String,
    ) : Screen

    data class Stub(val label: String) : Screen
}

private data class MangaRef(val title: String, val url: String, val thumb: String?)

@Composable
fun App() {
    val backStack = remember { mutableStateListOf<Screen>(Screen.Library) }
    val current = backStack.last()
    var sidebarOpen by remember { mutableStateOf(true) }
    fun go(s: Screen) = backStack.add(s)
    fun back() { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

    Surface(Modifier.fillMaxSize(), color = MuTheme.Ink) {
        Column(Modifier.fillMaxSize()) {
            TopBar(titleFor(current), backStack.size > 1, ::back) { sidebarOpen = !sidebarOpen }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(Modifier.fillMaxSize()) {
                if (current !is Screen.Reader && sidebarOpen) {
                    Sidebar(current) { backStack.clear(); backStack.add(it) }
                    Box(Modifier.width(1.dp).fillMaxSize().background(MaterialTheme.colorScheme.outline))
                }
                Box(Modifier.fillMaxSize()) {
                    when (val s = current) {
                        Screen.Library -> LibraryScreen { go(Screen.Detail(it.sourceId, it.mangaUrl, it.title)) }
                        Screen.Browse -> BrowseScreen { id, name, latest -> go(Screen.SourceBrowse(id, name, latest)) }
                        is Screen.SourceBrowse -> SourceBrowseScreen(s) { m -> go(Screen.Detail(s.sourceId, m.url, m.title)) }
                        is Screen.Detail -> DetailScreen(s) { chUrl, chName -> go(Screen.Reader(s.sourceId, s.url, s.title, chUrl, chName)) }
                        is Screen.Reader -> ReaderScreen(s, sidebarOpen) { chUrl, chName ->
                            backStack[backStack.lastIndex] = Screen.Reader(s.sourceId, s.mangaUrl, s.mangaTitle, chUrl, chName)
                        }
                        is Screen.Stub -> Empty("${s.label} — coming soon")
                    }
                }
            }
        }
    }
}

private fun titleFor(s: Screen): String =
    when (s) {
        Screen.Library -> "Library"
        Screen.Browse -> "Browse"
        is Screen.SourceBrowse -> s.name
        is Screen.Detail -> s.title
        is Screen.Reader -> s.mangaTitle
        is Screen.Stub -> s.label
    }

// ---- chrome ---------------------------------------------------------------------------------

@Composable
private fun TopBar(title: String, canGoBack: Boolean, onBack: () -> Unit, onMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MuTheme.Ink).padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, "Menu", tint = MuTheme.Paper) }
        if (canGoBack) IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = MuTheme.Paper) }
        Spacer(Modifier.width(6.dp))
        Text(title, color = MuTheme.Paper, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            NavItem("Settings", Icons.Filled.Settings, Screen.Stub("Settings")),
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

// ---- Library --------------------------------------------------------------------------------

@Composable
private fun LibraryScreen(onOpen: (LibraryEntry) -> Unit) {
    var entries by remember { mutableStateOf<List<LibraryEntry>>(emptyList()) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { entries = LibraryService.list() } }
    if (entries.isEmpty()) { Empty("Your library is empty.\nBrowse a source and add a series."); return }
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp),
        Modifier.fillMaxSize().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(entries) { e -> MangaCover(e.sourceId, MangaRef(e.title, e.mangaUrl, e.thumbnailUrl), "${e.knownChapters.size} ch") { onOpen(e) } }
    }
}

@Composable
private fun MangaCover(sourceId: Long, m: MangaRef, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MuTheme.Panel), modifier = Modifier.width(150.dp)) {
        Cover(sourceId, m.thumb, m.title, Modifier.fillMaxWidth().aspectRatio(0.7f))
        Column(Modifier.padding(8.dp)) {
            Text(m.title, color = MuTheme.Paper, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = MuTheme.Muted, fontSize = 11.sp)
        }
    }
}

// ---- Browse (tabs: Source / Extension / Migrate) --------------------------------------------

@Composable
private fun BrowseScreen(onOpenSource: (Long, String, Boolean) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = MuTheme.Ink, contentColor = MuTheme.Vermilion) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Source") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Extension") })
            Tab(tab == 2, { tab = 2 }, text = { Text("Migrate") })
        }
        when (tab) {
            0 -> SourceTab(onOpenSource)
            1 -> ExtensionTab()
            else -> Empty("Migrate — coming soon")
        }
    }
}

@Composable
private fun SourceTab(onOpen: (Long, String, Boolean) -> Unit) {
    var sources by remember { mutableStateOf<List<Triple<Long, String, String>>>(emptyList()) }
    var nsfwIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            sources = SourceManager.listInstalledSources().map { Triple(it.id, it.name, it.lang) }
            nsfwIds = InstalledStore.list().filter { it.nsfw }.flatMap { it.sources.map { s -> s.id } }.toSet()
        }
    }
    if (sources.isEmpty()) { Empty("No sources installed.\nInstall an extension from the Extension tab."); return }
    val byLang = sources.groupBy { it.third.ifBlank { "other" } }.toSortedMap()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        byLang.forEach { (lang, group) ->
            item { SectionHeader(lang.uppercase()) }
            items(group) { s -> SourceRow(s.first, s.second, s.third, s.first in nsfwIds, onOpen) }
        }
    }
}

@Composable
private fun SourceRow(id: Long, name: String, lang: String, nsfw: Boolean, onOpen: (Long, String, Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(id, name, false) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(coverColor(name)), Alignment.Center) {
            Text(name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = MuTheme.Paper, fontWeight = FontWeight.SemiBold)
            Row {
                Text(lang.uppercase(), color = MuTheme.Muted, fontSize = 11.sp)
                if (nsfw) { Spacer(Modifier.width(6.dp)); Text("18+", color = MuTheme.Vermilion, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
        }
        TextButton(onClick = { onOpen(id, name, true) }) { Text("LATEST", color = MuTheme.Vermilion) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
}

@Composable
private fun ExtensionTab() {
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf<List<InstalledExtension>>(emptyList()) }
    var available by remember { mutableStateOf<List<Pair<ExtensionRepoEntry, String>>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableStateOf(0) }
    var showRepos by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    val nsfwVisible = remember { mangautils.core.config.SettingsStore.get().nsfwVisible }

    LaunchedEffect(refresh) {
        loading = true
        withContext(Dispatchers.IO) {
            installed = InstalledStore.list()
            val have = installed.map { it.pkg }.toSet()
            val client = ExtensionRepoClient()
            available =
                RepoStore.list().flatMap { repo ->
                    runCatching { client.fetchIndex(repo) }.getOrDefault(emptyList()).map { it to repo }
                }.filter { it.first.pkg !in have && (nsfwVisible || !it.first.isNsfw) }
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Search extensions") }, leadingIcon = { Icon(Icons.Filled.Search, null) })
            Spacer(Modifier.width(8.dp))
            // "R" — open the extension repositories dialog.
            OutlinedButton(onClick = { showRepos = true }) { Text("R", fontWeight = FontWeight.Black, color = MuTheme.Vermilion) }
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = MuTheme.Vermilion) }
        } else {
            val filteredAvail = available.filter { it.first.name.contains(query, ignoreCase = true) }
            LazyColumn(Modifier.fillMaxSize()) {
                item { SectionHeader("Installed") }
                if (installed.isEmpty()) item { Text("None installed.", color = MuTheme.Muted, modifier = Modifier.padding(vertical = 8.dp)) }
                items(installed) { ext ->
                    ExtRow(ext.name, "${ext.lang.uppercase()} · v${ext.versionName}${if (ext.nsfw) " · 18+" else ""}", ext.pkg, installed = true) {
                        removeExtension(ext); refresh++
                    }
                }
                item { SectionHeader("Available (${filteredAvail.size})") }
                items(filteredAvail) { (entry, repo) ->
                    ExtRow(entry.name, "${entry.lang.uppercase()} · v${entry.version}${if (entry.isNsfw) " · 18+" else ""}", entry.pkg, installed = false) {
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { ExtensionInstaller().install(entry, repo) } }
                            refresh++
                        }
                    }
                }
            }
        }
    }

    if (showRepos) RepoDialog { showRepos = false; refresh++ }
}

@Composable
private fun ExtRow(name: String, meta: String, pkg: String, installed: Boolean, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(coverColor(name)), Alignment.Center) {
            Text(name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = MuTheme.Paper, fontWeight = FontWeight.SemiBold)
            Text(meta, color = MuTheme.Muted, fontSize = 11.sp)
        }
        if (installed) {
            OutlinedButton(onClick = onAction) { Text("UNINSTALL", color = MuTheme.Vermilion) }
        } else {
            Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = MuTheme.Vermilion)) { Text("INSTALL") }
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

// ---- Source browse (popular / latest / search grid) -----------------------------------------

@Composable
private fun SourceBrowseScreen(s: Screen.SourceBrowse, onOpen: (MangaRef) -> Unit) {
    val scope = rememberCoroutineScope()
    var mode by remember(s) { mutableStateOf(if (s.startLatest) "latest" else "popular") }
    var query by remember(s) { mutableStateOf("") }
    val items = remember(s) { mutableStateListOf<MangaRef>() }
    var page by remember(s) { mutableStateOf(0) }
    var hasNext by remember(s) { mutableStateOf(false) }
    var loading by remember(s) { mutableStateOf(false) }

    fun loadNext(reset: Boolean) {
        if (loading) return
        loading = true
        val next = if (reset) 1 else page + 1
        scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        when (mode) {
                            "latest" -> SourceBrowser.latest(s.sourceId, next)
                            "search" -> SourceBrowser.search(s.sourceId, query, next)
                            else -> SourceBrowser.popular(s.sourceId, next)
                        }
                    }.getOrNull()
                }
            if (reset) items.clear()
            result?.mangas?.forEach { m -> items.add(MangaRef(runCatching { m.title }.getOrDefault(m.url), m.url, m.thumbnail_url)) }
            hasNext = result?.hasNextPage ?: false
            page = next
            loading = false
        }
    }

    LaunchedEffect(s, mode) { if (mode != "search") loadNext(true) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModeChip("Popular", mode == "popular") { mode = "popular" }
            Spacer(Modifier.width(8.dp))
            ModeChip("Latest", mode == "latest") { mode = "latest" }
            Spacer(Modifier.width(16.dp))
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Search this source") })
            Spacer(Modifier.width(8.dp))
            Button(onClick = { mode = "search"; loadNext(true) }) { Text("Search") }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f)) {
            LazyVerticalGrid(
                GridCells.Adaptive(150.dp),
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(items) { m -> MangaCover(s.sourceId, m, "") { onOpen(m) } }
            }
            if (loading && items.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = MuTheme.Vermilion) }
        }
        if (hasNext) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { loadNext(false) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                Text(if (loading) "Loading..." else "Load more")
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MuTheme.Vermilion else MuTheme.Panel
    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)) {
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
    val readUrls = remember(s) { ReadStore.readUrls(s.sourceId, s.url) }
    val dateFmt = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }

    LaunchedEffect(s) {
        withContext(Dispatchers.IO) {
            inLibrary = LibraryService.isFollowed(s.sourceId, s.url)
            sourceLabel = SourceManager.listInstalledSources().firstOrNull { it.id == s.sourceId }?.let { "${it.name} (${it.lang.uppercase()})" } ?: ""
            runCatching { SourceBrowser.details(s.sourceId, s.url) }.onSuccess { details = it }.onFailure { error = it.message }
        }
    }
    val d = details

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

    when {
        error != null -> Empty("Couldn't load: $error")
        d == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = MuTheme.Vermilion) }
        else -> {
            val continueCh = d.chapters.reversed().firstOrNull { it.url !in readUrls } ?: d.chapters.firstOrNull()
            Row(Modifier.fillMaxSize()) {
                // LEFT: cover + info + library + description + tags
                Column(Modifier.weight(0.42f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Cover(s.sourceId, d.manga.thumbnail_url, s.title, Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(12.dp)))
                    Spacer(Modifier.height(12.dp))
                    Text(s.title, color = MuTheme.Paper, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    d.manga.author?.takeIf { it.isNotBlank() }?.let { InfoRow("Author", it) }
                    d.manga.artist?.takeIf { it.isNotBlank() }?.let { InfoRow("Artist", it) }
                    InfoRow("Status", SourceBrowser.statusLabel(d.manga.status))
                    if (sourceLabel.isNotBlank()) InfoRow("Source", sourceLabel)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { toggleLibrary() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = if (inLibrary) MuTheme.Vermilion else MuTheme.Panel),
                    ) { Text(if (inLibrary) "♥  In library" else "♡  Add to library", color = if (inLibrary) Color.White else MuTheme.Paper) }
                    Spacer(Modifier.height(12.dp))
                    d.manga.description?.takeIf { it.isNotBlank() }?.let { Text(it.trim(), color = MuTheme.Paper.copy(alpha = 0.85f), fontSize = 13.sp) }
                    Spacer(Modifier.height(12.dp))
                    val genres = d.manga.genre?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        genres.forEach { g ->
                            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(MuTheme.Panel).padding(horizontal = 12.dp, vertical = 6.dp)) {
                                Text(g, color = MuTheme.Paper, fontSize = 12.sp)
                            }
                        }
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
                // RIGHT: chapter list + RESUME
                Box(Modifier.weight(0.58f).fillMaxHeight()) {
                    Column(Modifier.fillMaxSize()) {
                        Text("${d.chapters.size} chapters", color = MuTheme.Paper, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
                        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                            items(d.chapters) { ch ->
                                val read = ch.url in readUrls
                                Card(
                                    onClick = { onReadChapter(ch.url, ch.name) },
                                    colors = CardDefaults.cardColors(containerColor = if (read) MuTheme.Ink else MuTheme.Panel),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                ) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(ch.name, color = if (read) MuTheme.Muted else MuTheme.Paper, fontWeight = if (read) FontWeight.Normal else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val meta = listOfNotNull(
                                            ch.scanlator?.takeIf { it.isNotBlank() },
                                            ch.date_upload.takeIf { it > 0 }?.let { dateFmt.format(Date(it)) },
                                        ).joinToString("  ·  ")
                                        if (meta.isNotBlank()) Text(meta, color = MuTheme.Muted, fontSize = 11.sp)
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(72.dp)) }
                        }
                    }
                    if (continueCh != null) {
                        ExtendedFloatingActionButton(
                            onClick = { onReadChapter(continueCh.url, continueCh.name) },
                            containerColor = MuTheme.Vermilion,
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

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 1.dp)) {
        Text("$label  ", color = MuTheme.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = MuTheme.Paper, fontSize = 12.sp)
    }
}

// ---- Reader (streaming long-strip, scale by width) ------------------------------------------

@Composable
private fun ReaderScreen(s: Screen.Reader, panelOpen: Boolean, onOpenChapter: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var pages by remember(s.chapterUrl) { mutableStateOf<List<Page>>(emptyList()) }
    var chapters by remember(s.mangaUrl) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loading by remember(s.chapterUrl) { mutableStateOf(true) }

    LaunchedEffect(s.chapterUrl) {
        loading = true
        withContext(Dispatchers.IO) {
            ReadStore.setRead(s.sourceId, s.mangaUrl, s.chapterUrl, true)
            if (chapters.isEmpty()) {
                chapters = runCatching { SourceBrowser.details(s.sourceId, s.mangaUrl).chapters.map { it.url to it.name } }.getOrDefault(emptyList())
            }
            pages = SourceImage.pageList(s.sourceId, s.chapterUrl)
        }
        loading = false
        listState.scrollToItem(0)
    }

    val idx = chapters.indexOfFirst { it.first == s.chapterUrl }
    val prevChapter = chapters.getOrNull(idx + 1)
    val nextChapter = chapters.getOrNull(idx - 1)
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
    title: String, chapterName: String, chapters: List<Pair<String, String>>, currentChapterUrl: String,
    sourceId: Long, mangaUrl: String, pageCount: Int, currentPage: Int,
    prevChapter: Pair<String, String>?, nextChapter: Pair<String, String>?,
    onOpenChapter: (String, String) -> Unit, onJumpPage: (Int) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.width(300.dp).fillMaxSize().background(MuTheme.Panel).padding(16.dp)) {
        Text(title, color = MuTheme.Paper, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(chapterName, color = MuTheme.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            OutlinedButton(onClick = { prevChapter?.let { onOpenChapter(it.first, it.second) } }, enabled = prevChapter != null) { Text("<") }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) { Text(chapterName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                    chapters.forEach { (url, name) ->
                        val read = ReadStore.isRead(sourceId, mangaUrl, url) || url == currentChapterUrl
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(if (read) MuTheme.VermilionDim else MuTheme.Vermilion))
                                    Spacer(Modifier.width(8.dp))
                                    Text(name, color = if (read) MuTheme.Muted else MuTheme.Paper)
                                }
                            },
                            onClick = { menuOpen = false; onOpenChapter(url, name) },
                        )
                    }
                }
            }
            OutlinedButton(onClick = { nextChapter?.let { onOpenChapter(it.first, it.second) } }, enabled = nextChapter != null) { Text(">") }
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
