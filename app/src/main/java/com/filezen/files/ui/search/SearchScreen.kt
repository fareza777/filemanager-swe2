package com.filezen.files.ui.search

import android.os.Environment
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.filezen.files.Routes
import com.filezen.files.core.model.FileType
import com.filezen.files.core.scan.SearchFilter
import com.filezen.files.ui.AppViewModel
import com.filezen.files.ui.SearchViewModel
import com.filezen.files.ui.common.EmptyState
import com.filezen.files.ui.common.FileRow
import com.filezen.files.ui.common.MassActionsBar
import com.filezen.files.ui.common.rememberSelection
import com.filezen.files.ops.Intents
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    nav: NavController,
    appVm: AppViewModel,
    presetType: String? = null,
    presetMode: String? = null,
    vm: SearchViewModel = viewModel(),
) {
    val filter by vm.filter.collectAsState()
    val results by vm.results.collectAsState()
    val searching by vm.searching.collectAsState()
    val recentQueries by vm.recentQueries.collectAsState()
    val mode by vm.mode.collectAsState()
    val hits by vm.hits.collectAsState()
    val indexProgress by vm.indexProgress.collectAsState()
    val indexedFiles by vm.indexedFiles.collectAsState()
    val photoHits by vm.photoHits.collectAsState()
    val imgProgress by vm.imgProgress.collectAsState()
    val imgIndexed by vm.imgIndexed.collectAsState()
    val clipReady by vm.clipReady.collectAsState()
    val clipDl by vm.clipDownload.collectAsState()
    val clipError by vm.clipError.collectAsState()

    var query by remember { mutableStateOf("") }
    var selectedTypes by remember { mutableStateOf(setOf<FileType>()) }
    var minSizeMb by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf("everywhere") } // everywhere / downloads / current
    val sel = rememberSelection()
    androidx.activity.compose.BackHandler(enabled = sel.active) { sel.clear() }

    val roots: List<File> = remember(scope) {
        when (scope) {
            "downloads" -> listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
            else -> listOf(Environment.getExternalStorageDirectory())
        }
    }

    LaunchedEffect(presetType, presetMode) {
        if (presetMode == "content") vm.setMode("content")
        if (presetMode == "photos") vm.setMode("photos")
        presetType?.let { t ->
            runCatching { FileType.valueOf(t) }.getOrNull()?.let { ft ->
                selectedTypes = setOf(ft)
                vm.setFilter(filter.copy(types = setOf(ft)))
                vm.search(listOf(Environment.getExternalStorageDirectory()))
            }
        }
    }

    fun runSearch() {
        if (mode == "content") { vm.contentSearch(query); return }
        if (mode == "photos") { vm.photoSearch(query); return }
        val min = minSizeMb.toLongOrNull()?.times(1024 * 1024)
        vm.setFilter(
            filter.copy(
                query = query.trim(), types = selectedTypes, minSize = min,
            )
        )
        vm.search(roots)
    }

    val filesVer by appVm.filesVersion.collectAsState()
    LaunchedEffect(filesVer) {
        if (filesVer > 0 && (results.isNotEmpty() || hits.isNotEmpty() || photoHits.isNotEmpty())) runSearch()
    }

    Scaffold(
        topBar = {
            if (sel.active) {
                TopAppBar(
                    title = { Text("${sel.selected.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { sel.clear() }) { Icon(Icons.Rounded.Close, "Clear") }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Search", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, "Back") }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; runSearch() },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = ""; runSearch() }) {
                        Icon(Icons.Rounded.Close, "Clear")
                    }
                },
                placeholder = { Text(when (mode) {
                    "content" -> "Inside documents…"
                    "photos" -> "Describe the photo… (e.g. \"meeting photo\")"
                    else -> "File name…"
                }) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            SingleChoiceSegmentedButtonRow(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                SegmentedButton(
                    selected = mode == "name",
                    onClick = { vm.setMode("name"); runSearch() },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                    icon = { Icon(Icons.Rounded.Abc, null, Modifier.size(18.dp)) },
                ) { Text("By name") }
                SegmentedButton(
                    selected = mode == "content",
                    onClick = { vm.setMode("content"); runSearch() },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                    icon = { Icon(Icons.Rounded.Article, null, Modifier.size(18.dp)) },
                ) { Text("Inside files") }
                SegmentedButton(
                    selected = mode == "photos",
                    onClick = { vm.setMode("photos"); runSearch() },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                    icon = { Icon(Icons.Rounded.ImageSearch, null, Modifier.size(18.dp)) },
                ) { Text("Photos") }
            }

            if (mode == "content") {
                val p = indexProgress
                ElevatedCard(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Rounded.ManageSearch, null,
                            tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            if (p != null) {
                                Text("Indexing documents… ${p.done}/${p.total}",
                                    style = MaterialTheme.typography.labelLarge)
                                if (p.current.isNotEmpty())
                                    Text(p.current, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1)
                                LinearProgressIndicator(
                                    progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f },
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                )
                            } else {
                                Text("$indexedFiles documents indexed",
                                    style = MaterialTheme.typography.labelLarge)
                                Text("Semantic search — % shows how closely the document's text matches your query",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (p == null) {
                            TextButton(onClick = { vm.rebuildIndex() }) { Text("Rebuild") }
                        }
                    }
                }
            }

            if (mode == "photos") {
                val dl = clipDl
                val ip = imgProgress
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                var battRestricted by remember { mutableStateOf(true) }
                fun refreshBatt() {
                    val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                    battRestricted = !pm.isIgnoringBatteryOptimizations(ctx.packageName)
                }
                LaunchedEffect(Unit) { refreshBatt() }
                DisposableEffect(lifecycleOwner) {
                    val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
                        if (ev == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshBatt()
                    }
                    lifecycleOwner.lifecycle.addObserver(obs)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
                }
                ElevatedCard(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Rounded.ImageSearch, null,
                            tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            when {
                                !clipReady && dl == null -> {
                                    Text("On-device AI photo search",
                                        style = MaterialTheme.typography.labelLarge)
                                    Text("Download the CLIP model once (~200 MB) — then find photos by describing them, fully offline.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    clipError?.let {
                                        Text(it, style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error)
                                    }
                                }
                                dl != null -> {
                                    Text("Downloading AI model… ${(dl.bytesDone * 100 / dl.bytesTotal).toInt()}%",
                                        style = MaterialTheme.typography.labelLarge)
                                    Text("${dl.label} (${dl.fileIx + 1}/${dl.fileCount}) — ${dl.bytesDone / 1048576}/${dl.bytesTotal / 1048576} MB",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    LinearProgressIndicator(
                                        progress = { (dl.bytesDone.toFloat() / dl.bytesTotal).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                                }
                                ip != null -> {
                                    Text("Indexing photos… ${ip.done}/${ip.total}",
                                        style = MaterialTheme.typography.labelLarge)
                                    if (ip.current.isNotEmpty())
                                        Text(ip.current, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    LinearProgressIndicator(
                                        progress = { if (ip.total > 0) ip.done.toFloat() / ip.total else 0f },
                                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                                }
                                else -> {
                                    Text("$imgIndexed photos indexed",
                                        style = MaterialTheme.typography.labelLarge)
                                    Text("Search from what a photo shows — English & Indonesian both work.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (battRestricted && clipReady) {
                                TextButton(
                                    onClick = {
                                        runCatching {
                                            ctx.startActivity(android.content.Intent(
                                                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                android.net.Uri.parse("package:${ctx.packageName}")))
                                        }
                                    },
                                    contentPadding = PaddingValues(0.dp),
                                ) {
                                    Text("Keep indexing when app is closed →",
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        when {
                            !clipReady && dl == null ->
                                TextButton(onClick = { vm.downloadClip() }) { Text("Download") }
                            clipReady && ip == null ->
                                TextButton(onClick = { vm.rebuildPhotoIndex() }) { Text("Rebuild") }
                            else -> {}
                        }
                    }
                }
            }

            // Filters (name mode only — content mode ignores them)
            if (mode == "name") Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val typeChips = listOf(
                    FileType.IMAGE to "Images", FileType.VIDEO to "Videos",
                    FileType.AUDIO to "Audio", FileType.DOCUMENT to "Docs",
                    FileType.PDF to "PDF", FileType.APK to "Apps",
                    FileType.ARCHIVE to "Archives",
                )
                typeChips.forEach { (t, label) ->
                    FilterChip(
                        selected = t in selectedTypes,
                        onClick = {
                            selectedTypes = if (t in selectedTypes) selectedTypes - t else selectedTypes + t
                            runSearch()
                        },
                        label = { Text(label) },
                    )
                }
                FilterChip(
                    selected = scope == "downloads",
                    onClick = {
                        scope = if (scope == "downloads") "everywhere" else "downloads"
                        runSearch()
                    },
                    label = { Text("Downloads only") },
                )
            }
            if (mode == "name") Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = minSizeMb, onValueChange = { minSizeMb = it.filter { c -> c.isDigit() }; runSearch() },
                    label = { Text("Min size (MB)") }, singleLine = true,
                    modifier = Modifier.width(140.dp),
                )
            }

            if (query.isBlank() && recentQueries.isNotEmpty() && results.isEmpty()) {
                Text("Recent searches", modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    recentQueries.forEach { q ->
                        SuggestionChip(onClick = { query = q; runSearch() }, label = { Text(q) })
                    }
                }
            }

            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())

            Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                mode == "photos" -> when {
                    !clipReady -> EmptyState(Icons.Rounded.ImageSearch, "AI photo search",
                        "Download the model above first — one-time, then fully offline.")
                    photoHits.isEmpty() && !searching && query.isNotBlank() ->
                        EmptyState(Icons.Rounded.SearchOff, "No matching photos",
                            "Try describing the scene differently — objects, people, places, vibes.")
                    photoHits.isEmpty() && imgProgress != null ->
                        EmptyState(Icons.Rounded.ImageSearch, "Indexing photos…",
                            "First-time photo indexing is running above.")
                    photoHits.isEmpty() ->
                        EmptyState(Icons.Rounded.ImageSearch, "Search your photos",
                            "Try \"meeting photo\", \"cat on a sofa\", \"sunset at the beach\".")
                    else -> LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(photoHits.size) { i ->
                            val h = photoHits[i]
                            val isSel = h.path in sel.selected
                            Box(
                                Modifier.aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .combinedClickable(
                                        onClick = {
                                            if (sel.active) sel.toggle(h.path)
                                            else nav.navigate(Routes.preview(h.path))
                                        },
                                        onLongClick = { sel.toggle(h.path) }),
                            ) {
                                coil.compose.AsyncImage(
                                    model = File(h.path),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                                if (isSel) {
                                    Box(Modifier.fillMaxSize()
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)))
                                    Icon(Icons.Rounded.CheckCircle, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
                                } else {
                                    Surface(
                                        color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.align(Alignment.BottomStart)
                                            .padding(6.dp),
                                    ) {
                                        Text("${(h.score * 100).toInt()}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = androidx.compose.ui.graphics.Color.White,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                    }
                                }
                            }
                        }
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                            Spacer(Modifier.height(if (sel.active) 150.dp else 96.dp))
                        }
                    }
                }
                mode == "content" -> when {
                    hits.isEmpty() && !searching && query.isNotBlank() ->
                        EmptyState(Icons.Rounded.SearchOff, "No matching content",
                            "Try different words — the index covers document text, not file names.")
                    hits.isEmpty() && !searching && indexProgress != null ->
                        EmptyState(Icons.Rounded.ManageSearch, "Building index…",
                            "First-time document indexing is running above.")
                    hits.isEmpty() ->
                        EmptyState(Icons.Rounded.Article, "Search inside files",
                            "Type a phrase — matches come from inside your documents' text.")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(hits, key = { it.path }) { h ->
                            val isSel = h.path in sel.selected
                            ListItem(
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        if (sel.active) sel.toggle(h.path)
                                        else nav.navigate(Routes.preview(h.path))
                                    },
                                    onLongClick = { sel.toggle(h.path) }),
                                headlineContent = {
                                    Text(File(h.path).name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                },
                                supportingContent = {
                                    Column {
                                        Text(h.snippet, maxLines = 2,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(File(h.path).parent ?: "", maxLines = 1,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                leadingContent = {
                                    Icon(if (isSel) Icons.Rounded.CheckCircle else Icons.Rounded.Article,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary)
                                },
                                trailingContent = {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("${(h.score * 100).toInt()}%",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold)
                                        Text("match",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = if (isSel)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                    else androidx.compose.ui.graphics.Color.Transparent),
                            )
                        }
                        item { Spacer(Modifier.height(if (sel.active) 150.dp else 96.dp)) }
                    }
                }
                results.isEmpty() && !searching && (query.isNotBlank() || selectedTypes.isNotEmpty()) ->
                    EmptyState(Icons.Rounded.SearchOff, "No matches", "Try a different name or remove some filters.")
                results.isEmpty() ->
                    EmptyState(Icons.Rounded.ManageSearch, "Find anything",
                        "Search by name, or tap a type above to browse all files of that kind.")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.path }) { e ->
                        FileRow(
                            e = e,
                            selected = e.path in sel.selected,
                            onClick = {
                                if (sel.active) sel.toggle(e.path)
                                else if (e.isDirectory) nav.navigate(Routes.folder(e.path))
                                else nav.navigate(Routes.preview(e.path))
                            },
                            onLongClick = { sel.toggle(e.path) },
                        )
                    }
                    item { Spacer(Modifier.height(if (sel.active) 150.dp else 96.dp)) }
                }
            }
            MassActionsBar(
                appVm, sel,
                allPaths = results.map { it.path } + hits.map { it.path } + photoHits.map { it.path },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            }
        }
    }
}
