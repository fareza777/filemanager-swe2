package com.filezen.files.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.filezen.files.Routes
import com.filezen.files.core.model.formatDate
import com.filezen.files.core.model.formatSize
import com.filezen.files.data.db.SortRule
import com.filezen.files.ui.common.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(nav: NavController, appVm: AppViewModel, vm: StorageViewModel = viewModel()) {
    val entries by vm.trashEntries.collectAsState()
    val size by vm.trashSize.collectAsState()
    var purgeAll by remember { mutableStateOf(false) }
    var purgeOne by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trash", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, "Back") }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        IconButton(onClick = { purgeAll = true }) {
                            Icon(Icons.Rounded.DeleteSweep, "Empty trash")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyState(Icons.Rounded.DeleteOutline, "Trash is empty",
                "Files you delete in FileZen are kept here for recovery.")
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item {
                    Text(
                        "${entries.size} items · ${formatSize(size)}",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(entries, key = { it.id }) { e ->
                    ListItem(
                        headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text("${formatSize(e.size)} · deleted ${formatDate(e.deletedAt)}\nfrom ${e.originalPath}",
                                style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        },
                        leadingContent = { Icon(Icons.Rounded.InsertDriveFile, null) },
                        trailingContent = {
                            Row {
                                TextButton(onClick = { vm.restoreTrash(e.id) }) { Text("Restore") }
                                TextButton(onClick = { purgeOne = e.id }) {
                                    Text("Delete", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }

    if (purgeAll) {
        ConfirmDialog("Empty trash?", "All ${entries.size} items will be permanently deleted. This can't be undone.",
            "Empty trash", danger = true,
            onConfirm = { vm.purgeAll(); purgeAll = false },
            onDismiss = { purgeAll = false })
    }
    purgeOne?.let { id ->
        ConfirmDialog("Delete permanently?", "This item will be permanently deleted.",
            "Delete", danger = true,
            onConfirm = { vm.purgeTrash(id); purgeOne = null },
            onDismiss = { purgeOne = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortRulesScreen(nav: NavController, appVm: AppViewModel, vm: StorageViewModel = viewModel()) {
    val rules by vm.rules.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var matchType by remember { mutableStateOf("EXTENSION") }
    var pattern by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var pickFolder by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sort rules", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, "Back") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("New rule") },
            )
        },
    ) { padding ->
        if (rules.isEmpty()) {
            EmptyState(Icons.Rounded.RuleFolder, "No rules yet",
                "Rules move matching files into a folder — e.g. “*.pdf → Documents/PDFs”.",
                Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(rules, key = { it.id }) { r ->
                    ListItem(
                        headlineContent = { Text(com.filezen.files.core.fileops.SortRuleEngine.describe(r)) },
                        supportingContent = { Text("→ ${r.targetPath}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Rounded.DriveFileMove, null) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = r.enabled, onCheckedChange = { vm.toggleRule(r, it) })
                                IconButton(onClick = { vm.deleteRule(r) }) {
                                    Icon(Icons.Rounded.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                item {
                    Text(
                        "Tip: run a rule from a folder's overflow → “Apply sort rules”.",
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DriveFileMove, null,
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("New sort rule")
                }
            },
            text = {
                Column {
                    Text("When a file matches…", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("EXTENSION", "CONTAINS", "REGEX").forEachIndexed { i, t ->
                            SegmentedButton(
                                selected = matchType == t,
                                onClick = { matchType = t },
                                shape = SegmentedButtonDefaults.itemShape(i, 3),
                            ) { Text(t.lowercase().replaceFirstChar { it.uppercase() }) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pattern, onValueChange = { pattern = it },
                        leadingIcon = {
                            Icon(when (matchType) {
                                "EXTENSION" -> Icons.Rounded.Extension
                                "CONTAINS" -> Icons.Rounded.Abc
                                else -> Icons.Rounded.Code
                            }, null)
                        },
                        label = { Text(when (matchType) {
                            "EXTENSION" -> "Extension (e.g. pdf)"
                            "CONTAINS" -> "Name contains"
                            else -> "Regex"
                        }) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    Text("…move it to", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        onClick = { pickFolder = true },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Folder, null,
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (target.isBlank()) "Choose folder…"
                                    else target.substringAfterLast('/').ifBlank { target },
                                    fontWeight = FontWeight.Medium, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                if (target.isNotBlank()) {
                                    Text(target, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Icon(Icons.Rounded.ChevronRight, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (pattern.isNotBlank() && target.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        ) {
                            Text(
                                "${com.filezen.files.core.fileops.SortRuleEngine.describe(
                                    com.filezen.files.data.db.SortRule(
                                        matchType = matchType, pattern = pattern, targetPath = target))} → ${target.substringAfterLast('/')}",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.addRule(matchType, pattern.trim(), target.trim())
                        pattern = ""; target = ""; showAdd = false
                    },
                    enabled = pattern.isNotBlank() && target.isNotBlank(),
                ) { Text("Add rule") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }

    if (pickFolder) {
        FolderPickerSheet(
            startPath = target.ifBlank {
                android.os.Environment.getExternalStorageDirectory().absolutePath },
            onPick = { target = it; pickFolder = false },
            onDismiss = { pickFolder = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(nav: NavController, vm: StorageViewModel = viewModel()) {
    val history by vm.history.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Operation history", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        if (history.isEmpty()) {
            EmptyState(Icons.Rounded.History, "No operations yet",
                "Copy, move, tidy and delete actions show up here with their status.")
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(history, key = { it.id }) { r ->
                    val (icon, tint) = when (r.status) {
                        "OK" -> Icons.Rounded.CheckCircle to Color(0xFF3DDC84)
                        "PARTIAL" -> Icons.Rounded.Warning to Color(0xFFF5B94E)
                        "CANCELLED" -> Icons.Rounded.Cancel to Color(0xFF9AA4B2)
                        else -> Icons.Rounded.Error to MaterialTheme.colorScheme.error
                    }
                    ListItem(
                        headlineContent = {
                            Text("${r.kind.lowercase().replaceFirstChar { it.uppercase() }} · ${r.itemCount} item(s)")
                        },
                        supportingContent = {
                            Column {
                                r.targetDir?.let { Text("→ $it", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                r.detail?.let { Text(it, maxLines = 2, style = MaterialTheme.typography.bodySmall) }
                                Text(formatDate(r.timestamp), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        leadingContent = { Icon(icon, null, tint = tint) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

/** Every recent file (up to 500) — the Home "See all" destination. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen(nav: NavController, appVm: AppViewModel, vm: HomeViewModel = viewModel()) {
    val recent by vm.allRecent.collectAsState()
    var selection by remember { mutableStateOf(setOf<String>()) }
    var trashConfirm by remember { mutableStateOf<List<String>?>(null) }
    val ctx = nav.context

    LaunchedEffect(Unit) { vm.loadAllRecent() }

    androidx.activity.compose.BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }

    Scaffold(
        topBar = {
            if (selection.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${selection.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selection = emptySet() }) {
                            Icon(Icons.Rounded.Close, "Clear")
                        }
                    },
                    actions = {
                        if (selection.size == 1) {
                            IconButton(onClick = {
                                recent.firstOrNull { it.path == selection.first() }
                                    ?.let { com.filezen.files.ops.Intents.openWith(ctx, it) }
                            }) { Icon(Icons.Rounded.OpenInNew, "Open with") }
                        }
                        IconButton(onClick = {
                            com.filezen.files.ops.Intents.share(
                                ctx, recent.filter { it.path in selection })
                        }) { Icon(Icons.Rounded.Share, "Share") }
                        IconButton(onClick = { trashConfirm = selection.toList() }) {
                            Icon(Icons.Rounded.Delete, "Trash")
                        }
                        IconButton(onClick = { selection = recent.map { it.path }.toSet() }) {
                            Icon(Icons.Rounded.SelectAll, "All")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Recent files", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.Rounded.ArrowBack, "Back")
                        }
                    },
                )
            }
        },
    ) { padding ->
        if (recent.isEmpty()) {
            EmptyState(Icons.Rounded.Schedule, "No recent files",
                "New files from Downloads, Pictures, DCIM and Documents appear here.",
                Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(recent, key = { it.path }) { e ->
                    FileRow(
                        e = e, selected = e.path in selection,
                        onClick = {
                            if (selection.isNotEmpty()) {
                                selection = if (e.path in selection) selection - e.path
                                else selection + e.path
                            } else if (e.isDirectory) nav.navigate(Routes.folder(e.path))
                            else nav.navigate(Routes.preview(e.path))
                        },
                        onLongClick = {
                            selection = if (e.path in selection) selection - e.path
                            else selection + e.path
                        },
                    )
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }

    trashConfirm?.let { paths ->
        ConfirmDialog(
            "Move to trash?", "${paths.size} item(s) will be moved to trash.",
            "Move to trash",
            onConfirm = {
                appVm.opTrash(paths)
                vm.dropRecent(paths)
                selection = emptySet()
                trashConfirm = null
            },
            onDismiss = { trashConfirm = null },
        )
    }
}
