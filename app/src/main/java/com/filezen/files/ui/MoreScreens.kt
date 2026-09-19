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
            title = { Text("New sort rule") },
            text = {
                Column {
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
                        label = { Text(when (matchType) {
                            "EXTENSION" -> "Extension (e.g. pdf)"
                            "CONTAINS" -> "Name contains"
                            else -> "Regex"
                        }) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = target, onValueChange = { target = it },
                        label = { Text("Destination folder (absolute path)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pattern.isNotBlank() && target.isNotBlank()) {
                        vm.addRule(matchType, pattern.trim(), target.trim())
                        pattern = ""; target = ""; showAdd = false
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
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
