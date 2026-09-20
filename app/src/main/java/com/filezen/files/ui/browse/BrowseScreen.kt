package com.filezen.files.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.filezen.files.Routes
import com.filezen.files.core.fileops.ConflictPolicy
import com.filezen.files.core.model.*
import com.filezen.files.data.prefs.*
import com.filezen.files.ui.AppViewModel
import com.filezen.files.ui.BrowseViewModel
import com.filezen.files.ui.common.*
import com.filezen.files.ops.Intents
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun BrowseScreen(
    nav: NavController,
    appVm: AppViewModel,
    fixedPath: String?,
    vm: BrowseViewModel = viewModel(),
) {
    val path by vm.path.collectAsState()
    val entries by vm.entries.collectAsState()
    val selection by vm.selection.collectAsState()
    val viewMode by vm.viewMode.collectAsState()
    val sortField by vm.sortField.collectAsState()
    val sortAsc by vm.sortAsc.collectAsState()
    val volumes by vm.volumes.collectAsState()
    val loading by vm.loading.collectAsState()
    val dirSizes by vm.dirSizes.collectAsState()
    val folderColors by vm.folderColors.collectAsState()
    var colorTarget by remember { mutableStateOf<FileEntry?>(null) }
    val clipboard by appVm.clipboard.collectAsState()
    val favorites by appVm.favorites.collectAsState()
    val basket by appVm.basket.collectAsState()

    var showMkdir by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var conflictFile by remember { mutableStateOf<String?>(null) }
    var pendingOp by remember { mutableStateOf<Pair<List<String>, Boolean>?>(null) } // paths, isMove
    var deleteConfirm by remember { mutableStateOf<List<String>?>(null) }
    var extractTarget by remember { mutableStateOf<FileEntry?>(null) }
    var convertTarget by remember { mutableStateOf<FileEntry?>(null) }
    var showDestPicker by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf<List<String>?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showRulesPreview by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var gridCols by remember { mutableStateOf(0) } // 0 = adaptive; pinch sets 2..6

    // SAF picker: grant FileZen access to an SD card / USB volume root.
    val safLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            val cur = vm.safRoots.value
            scope.launch { com.filezen.files.FileZenApp.c.settings.setSafRoots(cur + uri.toString()) }
        }
    }

    LaunchedEffect(fixedPath) { fixedPath?.let { vm.navigate(it) } }
    val isRootPicker = fixedPath == null

    androidx.activity.compose.BackHandler(enabled = selection.isNotEmpty()) { vm.clearSelection() }

    // Per-folder scroll memory: restore once when the path changes,
    // save the live position (debounced so it doesn't thrash DataStore).
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    LaunchedEffect(path) {
        val s = vm.scrollFor(path).first()
        if (s.index > 0 || s.offset > 0) {
            listState.scrollToItem(s.index, s.offset)
            gridState.scrollToItem(s.index, s.offset)
        }
    }
    LaunchedEffect(path, viewMode) {
        if (viewMode == ViewMode.LIST) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .distinctUntilChanged().debounce(250)
                .collect { (i, o) -> vm.saveScroll(i, o) }
        } else {
            snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
                .distinctUntilChanged().debounce(250)
                .collect { (i, o) -> vm.saveScroll(i, o) }
        }
    }

    fun doPaste(dest: String, policy: ConflictPolicy) {
        val cb = clipboard ?: return
        if (cb.cut) appVm.opMove(cb.paths, File(dest), policy)
        else appVm.opCopy(cb.paths, File(dest), policy)
        appVm.setClipboard(emptyList(), false)
    }

    fun openEntry(e: FileEntry) {
        if (selection.isNotEmpty()) { vm.toggleSelect(e.path); return }
        if (e.isDirectory) nav.navigate(Routes.folder(e.path))
        else nav.navigate(Routes.preview(e.path))
    }

    Scaffold(
        topBar = {
            if (selection.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${selection.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { vm.clearSelection() }) { Icon(Icons.Rounded.Close, "Clear") }
                    },
                    actions = {
                        IconButton(onClick = { appVm.setClipboard(selection.toList(), cut = false); vm.clearSelection() }) {
                            Icon(Icons.Rounded.ContentCopy, "Copy")
                        }
                        IconButton(onClick = { appVm.setClipboard(selection.toList(), cut = true); vm.clearSelection() }) {
                            Icon(Icons.Rounded.DriveFileMove, "Move")
                        }
                        IconButton(onClick = { moveTarget = selection.toList() }) {
                            Icon(Icons.Rounded.FolderShared, "Move to folder…")
                        }
                        IconButton(onClick = {
                            val files = entries.filter { it.path in selection && !it.isDirectory }
                                .map { it.path }
                            if (files.isNotEmpty()) appVm.opMove(files,
                                com.filezen.files.FileZenApp.c.transfer.shareDir,
                                ConflictPolicy.KEEP_BOTH)
                            vm.clearSelection()
                        }) {
                            Icon(Icons.Rounded.Phonelink, "Send to Transfer folder")
                        }
                        IconButton(onClick = { deleteConfirm = selection.toList() }) {
                            Icon(Icons.Rounded.Delete, "Delete")
                        }
                        IconButton(onClick = { appVm.opZip(selection.toList(), File(path)); vm.clearSelection() }) {
                            Icon(Icons.Rounded.FolderZip, "Zip")
                        }
                        IconButton(onClick = { Intents.share(nav.context, entries.filter { it.path in selection }) }) {
                            Icon(Icons.Rounded.Share, "Share")
                        }
                        IconButton(onClick = {
                            selection.forEach { appVm.basketAdd(it) }; vm.clearSelection()
                        }) { Icon(Icons.Rounded.AddShoppingCart, "Add to basket") }
                        IconButton(onClick = { vm.selectAll() }) {
                            Icon(Icons.Rounded.SelectAll, "Select all")
                        }
                        if (selection.size == 1) {
                            IconButton(onClick = {
                                renameTarget = entries.firstOrNull { it.path == selection.first() }
                            }) { Icon(Icons.Rounded.Edit, "Rename") }
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        if (isRootPicker) Text("Browse", fontWeight = FontWeight.Bold)
                        else Text(File(path).name.ifBlank { "Storage" }, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        if (!isRootPicker && fixedPath != null) {
                            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, "Back") }
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.setViewMode(if (viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST) }) {
                            Icon(if (viewMode == ViewMode.LIST) Icons.Rounded.GridView else Icons.Rounded.ViewList, "View mode")
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) { Icon(Icons.Rounded.Sort, "Sort") }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                SortField.values().forEach { f ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(f.name.lowercase().replaceFirstChar { it.uppercase() })
                                                if (f == sortField) {
                                                    Spacer(Modifier.width(8.dp))
                                                    Icon(if (sortAsc) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                                                        null, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        },
                                        onClick = {
                                            vm.setSort(f, if (f == sortField) !sortAsc else true)
                                            showSortMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { vm.refresh() }) { Icon(Icons.Rounded.Refresh, "Refresh") }
                    },
                )
            }
        },
        floatingActionButton = {
            if (selection.isEmpty() && !isRootPicker) {
                ExtendedFloatingActionButton(
                    onClick = { showMkdir = true },
                    icon = { Icon(Icons.Rounded.CreateNewFolder, null) },
                    text = { Text("New folder") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!isRootPicker) {
                Breadcrumb(path) { p -> if (p == "/") nav.popBackStack() else vm.navigate(p) }
            }

            // clipboard paste bar
            clipboard?.takeIf { it.paths.isNotEmpty() }?.let { cb ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (cb.cut) Icons.Rounded.ContentCut else Icons.Rounded.ContentCopy, null)
                        Spacer(Modifier.width(8.dp))
                        Text("${cb.paths.size} item(s) ready", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = {
                            val dest = File(path)
                            val conflicts = cb.paths.any { File(dest, File(it).name).exists() }
                            if (conflicts) {
                                pendingOp = cb.paths to cb.cut
                                conflictFile = cb.paths.first { File(dest, File(it).name).exists() }
                            } else doPaste(path, ConflictPolicy.SKIP)
                        }) { Text("Paste") }
                        TextButton(onClick = { appVm.setClipboard(emptyList(), false) }) { Text("Clear") }
                    }
                }
            }

            // basket bar
            if (basket.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ShoppingCart, null)
                        Spacer(Modifier.width(8.dp))
                        Text("${basket.size} in basket", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { showDestPicker = true }) { Text("Move") }
                        TextButton(onClick = { appVm.basketClear() }) { Text("Clear") }
                    }
                }
            }

            if (loading && entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            if (isRootPicker) {
                // Volumes + categories overview
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(volumes) { _, v ->
                        Card(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
                                .clickable { nav.navigate(Routes.folder(v.path)) },
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Row(
                                Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            if (v.removable) Icons.Rounded.SdCard else Icons.Rounded.Smartphone,
                                            null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(v.name, fontWeight = FontWeight.SemiBold)
                                    Text(v.path, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Icons.Rounded.ChevronRight, null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item {
                        ListItem(
                            headlineContent = { Text("Transfer to PC") },
                            supportingContent = {
                                Text("Share files over Wi-Fi — open the link in any browser",
                                    style = MaterialTheme.typography.bodySmall)
                            },
                            leadingContent = {
                                Icon(Icons.Rounded.Phonelink, null,
                                    tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.clickable {
                                nav.navigate(Routes.TRANSFER)
                            },
                        )
                    }
                    item {
                        ListItem(
                            headlineContent = { Text("Grant SD card / USB access") },
                            supportingContent = {
                                Text("Pick the removable volume so FileZen can write to it",
                                    style = MaterialTheme.typography.bodySmall)
                            },
                            leadingContent = {
                                Icon(Icons.Rounded.SdCard, null,
                                    tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.clickable {
                                runCatching { safLauncher.launch(null) }
                            },
                        )
                    }
                    item { SectionHeader("Categories") }
                    val cats = listOf(
                        FileType.IMAGE to "Images", FileType.VIDEO to "Videos",
                        FileType.AUDIO to "Audio", FileType.DOCUMENT to "Documents",
                        FileType.PDF to "PDFs", FileType.APK to "Apps",
                        FileType.ARCHIVE to "Archives", FileType.TEXT to "Text",
                    )
                    itemsIndexed(cats) { _, (t, label) ->
                        val dummy = FileEntry("", "", false, 0, 0, t)
                        ListItem(
                            headlineContent = { Text(label) },
                            leadingContent = {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = tintFor(dummy).copy(alpha = 0.16f),
                                    modifier = Modifier.size(38.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(iconFor(dummy), null, tint = tintFor(dummy),
                                            modifier = Modifier.size(20.dp))
                                    }
                                }
                            },
                            trailingContent = {
                                Icon(Icons.Rounded.ChevronRight, null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp))
                            },
                            modifier = Modifier.clickable { nav.navigate("${Routes.SEARCH}?type=${t.name}") },
                        )
                    }
                }
            } else if (loading) {
                Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                    repeat(9) { SkeletonRow() }
                }
            } else if (entries.isEmpty() && !loading) {
                EmptyState(Icons.Rounded.FolderOpen, "Empty folder", "Nothing to see here yet.")
            } else if (viewMode == ViewMode.LIST) {
                // Drag-select: while a selection is active, dragging vertically
                // over rows keeps adding them.
                var lastDragIdx by remember { mutableStateOf(-1) }
                LazyColumn(
                    Modifier.fillMaxSize().pointerInput(selection.isEmpty()) {
                        if (selection.isEmpty()) return@pointerInput
                        detectDragGestures(
                            onDragStart = { lastDragIdx = -1 },
                        ) { change, _ ->
                            val y = change.position.y
                            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                y >= it.offset && y < it.offset + it.size
                            } ?: return@detectDragGestures
                            val idx = info.index
                            if (idx != lastDragIdx && idx < entries.size) {
                                lastDragIdx = idx
                                val p = entries[idx].path
                                if (p !in selection) vm.toggleSelect(p)
                            }
                        }
                    },
                    state = listState,
                ) {
                    itemsIndexed(entries, key = { _, e -> e.path }) { _, e ->
                        FileRow(
                            e = e,
                            selected = e.path in selection,
                            dirSize = dirSizes[e.path],
                            folderColor = folderColors[e.path]?.let { Color(it) },
                            onClick = { openEntry(e) },
                            onLongClick = { vm.longPressSelect(e.path) },
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                if (e.isDirectory) {
                                    val fav = appVm.isFavorite(e.path)
                                    IconButton(onClick = {
                                        if (fav) appVm.removeFavorite(e.path)
                                        else appVm.addFavorite(e.path, e.name)
                                    }, modifier = Modifier.size(34.dp)) {
                                        Icon(
                                            if (fav) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                                            if (fav) "Remove favourite" else "Add favourite",
                                            tint = if (fav) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                                OverflowMenu(
                                    e = e,
                                    onOpen = { openEntry(e) },
                                    onRename = { renameTarget = e },
                                    onColorTag = if (e.isDirectory) ({ colorTarget = e }) else null,
                                    onZip = { appVm.opZip(listOf(e.path), File(path)) },
                                    onExtract = { extractTarget = e },
                                    onTrash = { deleteConfirm = listOf(e.path) },
                                    onShare = { Intents.share(nav.context, listOf(e)) },
                                    onFavorite = {
                                        if (appVm.isFavorite(e.path)) appVm.removeFavorite(e.path)
                                        else appVm.addFavorite(e.path, e.name)
                                    },
                                    isFavorite = appVm.isFavorite(e.path),
                                    onBasket = { appVm.basketAdd(e.path) },
                                    onConvert = { convertTarget = e },
                                    onCompress = { appVm.opCompressImage(e.path) },
                                    onMoveTo = { moveTarget = listOf(e.path) },
                                    onToTransfer = if (!e.isDirectory) ({
                                        appVm.opMove(listOf(e.path),
                                            com.filezen.files.FileZenApp.c.transfer.shareDir,
                                            ConflictPolicy.KEEP_BOTH)
                                    }) else null,
                                )
                                }
                            },
                        )
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            } else {
                var pinchZoom by remember { mutableStateOf(1f) }
                LazyVerticalGrid(
                    state = gridState,
                    columns = if (gridCols > 0) GridCells.Fixed(gridCols) else GridCells.Adaptive(110.dp),
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, zoom, _ ->
                                pinchZoom *= zoom
                                if (pinchZoom > 1.18f) { gridCols = (if (gridCols==0) 4 else gridCols) - 1; pinchZoom = 1f }
                                else if (pinchZoom < 0.85f) { gridCols = (if (gridCols==0) 3 else gridCols) + 1; pinchZoom = 1f }
                                gridCols = gridCols.coerceIn(0, 6)
                            }
                        },
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries.size, key = { entries[it].path }) { i ->
                        val e = entries[i]
                        FileCard(
                            e = e,
                            selected = e.path in selection,
                            folderColor = folderColors[e.path]?.let { Color(it) },
                            onClick = { openEntry(e) },
                            onLongClick = { vm.longPressSelect(e.path) },
                            menu = {
                                OverflowMenu(
                                    e = e,
                                    onOpen = { openEntry(e) },
                                    onRename = { renameTarget = e },
                                    onColorTag = if (e.isDirectory) ({ colorTarget = e }) else null,
                                    onZip = { appVm.opZip(listOf(e.path), File(path)) },
                                    onExtract = { extractTarget = e },
                                    onTrash = { deleteConfirm = listOf(e.path) },
                                    onShare = { Intents.share(nav.context, listOf(e)) },
                                    onFavorite = {
                                        if (appVm.isFavorite(e.path)) appVm.removeFavorite(e.path)
                                        else appVm.addFavorite(e.path, e.name)
                                    },
                                    isFavorite = appVm.isFavorite(e.path),
                                    onBasket = { appVm.basketAdd(e.path) },
                                    onConvert = { convertTarget = e },
                                    onCompress = { appVm.opCompressImage(e.path) },
                                    onMoveTo = { moveTarget = listOf(e.path) },
                                    onToTransfer = if (!e.isDirectory) ({
                                        appVm.opMove(listOf(e.path),
                                            com.filezen.files.FileZenApp.c.transfer.shareDir,
                                            ConflictPolicy.KEEP_BOTH)
                                    }) else null,
                                )
                            },
                        )
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
    }

    // ---- dialogs ----
    if (showMkdir) {
        TextInputDialog("New folder", hint = "Folder name", onConfirm = { name ->
            scope.launch { FileZenEngine.mkdir(File(path), name); vm.refresh() }
            showMkdir = false
        }, onDismiss = { showMkdir = false })
    }
    renameTarget?.let { e ->
        TextInputDialog("Rename", initial = e.name, hint = "Name", onConfirm = { newName ->
            scope.launch { FileZenEngine.rename(File(e.path), newName); vm.refresh() }
            renameTarget = null
        }, onDismiss = { renameTarget = null })
    }
    colorTarget?.let { e ->
        AlertDialog(
            onDismissRequest = { colorTarget = null },
            title = { Text("Folder colour") },
            text = {
                Column {
                    Text(e.name, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(0xFFF5B94E, 0xFF58A6FF, 0xFF57AB5A, 0xFFEF5B5B,
                               0xFFBC8CF2, 0xFF8B949E).forEach { col ->
                            Surface(
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = Color(col),
                                modifier = Modifier.size(40.dp)
                                    .clickable {
                                        scope.launch {
                                            com.filezen.files.FileZenApp.c.settings
                                                .setFolderColor(e.path, col.toInt())
                                        }
                                        colorTarget = null
                                    },
                            ) {}
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    scope.launch {
                        com.filezen.files.FileZenApp.c.settings.setFolderColor(e.path, null)
                    }
                    colorTarget = null
                }) { Text("Clear") }
            },
        )
    }
    conflictFile?.let { fname ->
        AlertDialog(
            onDismissRequest = { conflictFile = null; pendingOp = null },
            title = { Text("Name conflict") },
            text = { Text("“$fname” already exists at the destination. Apply to all conflicts.") },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        pendingOp?.let { (paths, cut) ->
                            val cb = appVm.clipboard.value
                            if (cut) appVm.opMove(paths, File(path), ConflictPolicy.OVERWRITE)
                            else appVm.opCopy(paths, File(path), ConflictPolicy.OVERWRITE)
                            appVm.setClipboard(emptyList(), false)
                        }
                        conflictFile = null; pendingOp = null
                    }) { Text("Replace all") }
                    TextButton(onClick = {
                        pendingOp?.let { (paths, cut) ->
                            if (cut) appVm.opMove(paths, File(path), ConflictPolicy.KEEP_BOTH)
                            else appVm.opCopy(paths, File(path), ConflictPolicy.KEEP_BOTH)
                            appVm.setClipboard(emptyList(), false)
                        }
                        conflictFile = null; pendingOp = null
                    }) { Text("Keep both") }
                    TextButton(onClick = {
                        pendingOp?.let { (paths, cut) ->
                            if (cut) appVm.opMove(paths, File(path), ConflictPolicy.SKIP)
                            else appVm.opCopy(paths, File(path), ConflictPolicy.SKIP)
                            appVm.setClipboard(emptyList(), false)
                        }
                        conflictFile = null; pendingOp = null
                    }) { Text("Skip conflicts") }
                }
            },
        )
    }
    deleteConfirm?.let { paths ->
        ConfirmDialog(
            title = "Move to trash?",
            text = "${paths.size} item(s) will be moved to FileZen trash. You can restore them later.",
            confirmLabel = "Move to trash",
            onConfirm = { appVm.opTrash(paths); deleteConfirm = null; vm.clearSelection() },
            onDismiss = { deleteConfirm = null },
        )
    }
    convertTarget?.let { e ->
        ConvertDialog(e.name, com.filezen.files.core.convert.ConvertEngine.targetsFor(e),
            onConvert = { t -> appVm.opConvert(e.path, t); convertTarget = null },
            onDismiss = { convertTarget = null })
    }
    extractTarget?.let { e ->
        ConfirmDialog(
            title = "Extract archive?",
            text = "Extract “${e.name}” into this folder?",
            confirmLabel = "Extract",
            onConfirm = {
                appVm.opUnzip(e.path, File(path), ConflictPolicy.KEEP_BOTH)
                extractTarget = null
            },
            onDismiss = { extractTarget = null },
        )
    }
    if (showDestPicker) {
        DestinationSheet(
            favorites = favorites,
            currentPath = path,
            shareDir = com.filezen.files.FileZenApp.c.transfer.shareDir,
            onPick = { dest ->
                appVm.opMove(basket.toList(), File(dest), ConflictPolicy.KEEP_BOTH)
                appVm.basketClear()
                showDestPicker = false
            },
            onDismiss = { showDestPicker = false },
        )
    }
    moveTarget?.let { paths ->
        DestinationSheet(
            favorites = favorites,
            currentPath = path,
            shareDir = com.filezen.files.FileZenApp.c.transfer.shareDir,
            onPick = { dest ->
                appVm.opMove(paths, File(dest), ConflictPolicy.KEEP_BOTH)
                vm.clearSelection()
                moveTarget = null
            },
            onDismiss = { moveTarget = null },
        )
    }
}

@Composable
private fun OverflowMenu(
    e: FileEntry,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onZip: () -> Unit,
    onExtract: () -> Unit,
    onTrash: () -> Unit,
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    isFavorite: Boolean,
    onBasket: () -> Unit,
    onConvert: () -> Unit,
    onCompress: () -> Unit,
    onColorTag: (() -> Unit)? = null,
    onMoveTo: () -> Unit,
    onToTransfer: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.MoreVert, "More")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Open") }, onClick = { onOpen(); open = false },
                leadingIcon = { Icon(Icons.Rounded.FileOpen, null) })
            if (!e.isDirectory) {
                DropdownMenuItem(text = { Text("Open with…") }, onClick = {
                    Intents.openWith(ctx, e); open = false
                }, leadingIcon = { Icon(Icons.Rounded.OpenInNew, null) })
                DropdownMenuItem(text = { Text("Share") }, onClick = { onShare(); open = false },
                    leadingIcon = { Icon(Icons.Rounded.Share, null) })
            }
            DropdownMenuItem(text = { Text("Rename") }, onClick = { onRename(); open = false },
                leadingIcon = { Icon(Icons.Rounded.Edit, null) })
            DropdownMenuItem(text = { Text("Move to folder…") }, onClick = { onMoveTo(); open = false },
                leadingIcon = { Icon(Icons.Rounded.DriveFileMove, null) })
            if (onToTransfer != null) {
                DropdownMenuItem(text = { Text("Send to Transfer folder") },
                    onClick = { onToTransfer(); open = false },
                    leadingIcon = { Icon(Icons.Rounded.Phonelink, null) })
            }
            DropdownMenuItem(text = { Text("Add to basket") }, onClick = { onBasket(); open = false },
                leadingIcon = { Icon(Icons.Rounded.AddShoppingCart, null) })
            if (e.isDirectory) {
                DropdownMenuItem(
                    text = { Text(if (isFavorite) "Remove favourite" else "Add favourite") },
                    onClick = { onFavorite(); open = false },
                    leadingIcon = { Icon(Icons.Rounded.Star, null) },
                )
            }
            val convTargets = com.filezen.files.core.convert.ConvertEngine.targetsFor(e)
            if (convTargets.isNotEmpty()) {
                DropdownMenuItem(text = { Text("Convert…") },
                    onClick = { onConvert(); open = false },
                    leadingIcon = { Icon(Icons.Rounded.Transform, null) })
            }
            if (com.filezen.files.core.convert.ConvertEngine.canCompress(e)) {
                DropdownMenuItem(text = { Text("Compress image") },
                    onClick = { onCompress(); open = false },
                    leadingIcon = { Icon(Icons.Rounded.Compress, null) })
            }
            DropdownMenuItem(text = { Text("Compress to ZIP") }, onClick = { onZip(); open = false },
                leadingIcon = { Icon(Icons.Rounded.FolderZip, null) })
            if (e.type == FileType.ARCHIVE) {
                DropdownMenuItem(text = { Text("Extract here") }, onClick = { onExtract(); open = false },
                    leadingIcon = { Icon(Icons.Rounded.Unarchive, null) })
            }
            DropdownMenuItem(text = { Text("Move to trash") }, onClick = { onTrash(); open = false },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) })
        }
    }
}

private val FileZenEngine = com.filezen.files.FileZenApp.c.fileEngine
