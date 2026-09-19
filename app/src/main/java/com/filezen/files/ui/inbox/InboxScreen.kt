package com.filezen.files.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.filezen.files.core.model.FileEntry
import com.filezen.files.core.model.FileType
import com.filezen.files.data.db.InboxItem
import com.filezen.files.ui.AppViewModel
import com.filezen.files.ui.InboxViewModel
import com.filezen.files.ui.common.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(nav: NavController, appVm: AppViewModel, vm: InboxViewModel = viewModel()) {
    val untidy by vm.untidy.collectAsState()
    val tidy by vm.tidy.collectAsState()
    val selection by vm.selection.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val favorites by appVm.favorites.collectAsState()
    val roots by vm.roots.collectAsState()
    val rules by vm.rules.collectAsState()

    var tab by remember { mutableStateOf(0) }
    var showTidyFor by remember { mutableStateOf<List<String>?>(null) }
    var tidyRename by remember { mutableStateOf("") }
    var showRoots by remember { mutableStateOf(false) }
    var addRootPath by remember { mutableStateOf(false) }
    var trashConfirm by remember { mutableStateOf<List<String>?>(null) }
    var autoFor by remember { mutableStateOf<List<String>?>(null) }

    val shown = if (tab == 0) untidy else tidy

    androidx.activity.compose.BackHandler(enabled = selection.isNotEmpty()) { vm.clearSelection() }

    Scaffold(
        topBar = {
            if (selection.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${selection.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { vm.clearSelection() }) { Icon(Icons.Rounded.Close, "Clear") }
                    },
                    actions = {
                        IconButton(onClick = { showTidyFor = selection.toList() }) {
                            Icon(Icons.Rounded.DriveFileMove, "Tidy up")
                        }
                        IconButton(onClick = { autoFor = selection.toList() }) {
                            Icon(Icons.Rounded.AutoAwesome, "Auto-tidy")
                        }
                        IconButton(onClick = { vm.markTidy(selection.toList(), true) }) {
                            Icon(Icons.Rounded.DoneAll, "Mark tidy")
                        }
                        IconButton(onClick = { trashConfirm = selection.toList() }) {
                            Icon(Icons.Rounded.Delete, "Trash")
                        }
                        IconButton(onClick = { vm.selectAllUntidy() }) {
                            Icon(Icons.Rounded.SelectAll, "All")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Inbox", fontWeight = FontWeight.Bold) },
                    actions = {
                        if (tab == 0 && untidy.isNotEmpty()) {
                            IconButton(onClick = { autoFor = untidy.map { it.path } }) {
                                Icon(Icons.Rounded.AutoAwesome, "Auto-tidy all")
                            }
                        }
                        IconButton(onClick = { showRoots = true }) {
                            Icon(Icons.Rounded.Source, "Watched folders")
                        }
                        IconButton(onClick = { vm.scan() }) { Icon(Icons.Rounded.Refresh, "Rescan") }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
                SegmentedButton(
                    selected = tab == 0, onClick = { tab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("To tidy (${untidy.size})") }
                SegmentedButton(
                    selected = tab == 1, onClick = { tab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("Tidied (${tidy.size})") }
            }

            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (shown.isEmpty()) {
                EmptyState(
                    Icons.Rounded.Inbox,
                    if (tab == 0) "All tidy!" else "Nothing tidied yet",
                    if (tab == 0)
                        "New files from Downloads and your watched folders land here."
                    else "Files you organise show up here.",
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.path }) { item ->
                        InboxRow(
                            item = item,
                            selected = item.path in selection,
                            selectionMode = selection.isNotEmpty(),
                            onClick = {
                                if (selection.isNotEmpty()) vm.toggleSelect(item.path)
                                else nav.navigate(Routes.preview(item.path))
                            },
                            onLongClick = { vm.toggleSelect(item.path) },
                            onTidy = { showTidyFor = listOf(item.path); tidyRename = item.name },
                            onMarkTidy = { vm.markTidy(listOf(item.path), tab == 0) },
                            isTidiedTab = tab == 1,
                        )
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
    }

    // Tidy-up sheet: optional rename (single file) + pick destination
    showTidyFor?.let { paths ->
        ModalBottomSheet(onDismissRequest = { showTidyFor = null }) {
            Text("Tidy up ${paths.size} file(s)", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp))
            if (paths.size == 1) {
                OutlinedTextField(
                    value = tidyRename, onValueChange = { tidyRename = it },
                    label = { Text("File name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            Text("Move to…", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(favorites) { f ->
                    ListItem(
                        headlineContent = { Text(f.label) },
                        supportingContent = { Text(f.path, maxLines = 1) },
                        leadingContent = { Icon(Icons.Rounded.Star, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable {
                            val rename = if (paths.size == 1 && tidyRename != paths.first().substringAfterLast('/'))
                                tidyRename else null
                            vm.tidyMove(paths, File(f.path), rename)
                            showTidyFor = null
                        },
                    )
                }
            }
            if (favorites.isEmpty()) {
                Text(
                    "No favourites yet — star folders in Browse for one-tap tidy targets.",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = {
                    nav.navigate(Routes.BROWSE) {
                        popUpTo(Routes.HOME)
                        launchSingleTop = true
                    }
                    showTidyFor = null
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            ) { Text("Pick a folder in Browse (select then Move)") }
            Spacer(Modifier.height(28.dp))
        }
    }

    // Watched folders
    if (showRoots) {
        AlertDialog(
            onDismissRequest = { showRoots = false },
            title = { Text("Watched folders") },
            text = {
                Column {
                    val effective = if (roots.isEmpty())
                        setOf(android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS).absolutePath)
                    else roots
                    effective.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Folder, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(r, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            if (roots.isNotEmpty()) {
                                IconButton(onClick = { vm.removeRoot(r) }) {
                                    Icon(Icons.Rounded.Close, "Remove", Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { addRootPath = true }) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                        Text("Add folder")
                    }
                    if (roots.isNotEmpty()) {
                        TextButton(onClick = { vm.resetRoots() }) { Text("Reset to Downloads") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRoots = false }) { Text("Done") } },
        )
    }
    if (addRootPath) {
        TextInputDialog("Add watched folder", hint = "/storage/emulated/0/Pictures",
            onConfirm = { vm.addRoot(it); addRootPath = false; vm.scan() },
            onDismiss = { addRootPath = false })
    }
    trashConfirm?.let { paths ->
        ConfirmDialog(
            "Move to trash?", "${paths.size} item(s) will be moved to trash.",
            "Move to trash",
            onConfirm = { appVm.opTrash(paths); trashConfirm = null; vm.clearSelection() },
            onDismiss = { trashConfirm = null },
        )
    }

    // Auto-tidy: preview resolved destinations (sort rules first, then type defaults)
    autoFor?.let { paths ->
        val (plan, skipped) = remember(paths, rules) { vm.autoTidyPlan(paths) }
        ModalBottomSheet(onDismissRequest = { autoFor = null }) {
            Row(
                Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Auto-tidy ${paths.size} file(s)", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
            }
            if (plan.isEmpty()) {
                Text(
                    "Nothing matched a sort rule or a standard folder. Add a rule in Storage → Sort rules.",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(plan) { item ->
                        ListItem(
                            headlineContent = {
                                Text(item.name, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            },
                            supportingContent = { Text(item.via, style = MaterialTheme.typography.labelSmall) },
                            trailingContent = {
                                Text("→ ${item.dest.substringAfterLast('/')}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                            },
                            leadingContent = {
                                Icon(Icons.Rounded.Folder, null, Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                        )
                    }
                }
                if (skipped.isNotEmpty()) {
                    Text(
                        "${skipped.size} file(s) have no rule or standard folder — they'll stay where they are.",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { vm.autoTidy(plan); autoFor = null },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                ) { Text("Tidy ${plan.size} files") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InboxRow(
    item: InboxItem,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onTidy: () -> Unit,
    onMarkTidy: () -> Unit,
    isTidiedTab: Boolean,
) {
    val e = FileEntry(
        path = item.path, name = item.name, isDirectory = false,
        size = item.size, lastModified = item.lastModified,
        type = runCatching { FileType.valueOf(item.type) }.getOrDefault(FileType.OTHER),
    )
    FileRow(
        e = e, selected = selected, onClick = onClick, onLongClick = onLongClick,
        trailing = {
            if (isTidiedTab) {
                TextButton(onClick = onMarkTidy) { Text("Unfile") }
            } else if (!selectionMode) {
                TextButton(onClick = onTidy) { Text("Tidy up") }
            }
        },
    )
}
