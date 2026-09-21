package com.filezen.files.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.filezen.files.core.remote.RemoteConnection
import com.filezen.files.data.db.SyncPair
import com.filezen.files.ui.AppViewModel
import com.filezen.files.ui.SyncViewModel
import com.filezen.files.ui.common.FolderPickerSheet
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Folder sync (OpenSync port): keeps a local folder in step with another local
 * folder or an SMB/SFTP/WebDAV/S3 remote — one-way mirror or two-way with
 * deletion propagation. Runs on demand or on app open; never deletes silently
 * unless "delete orphans" is on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(nav: NavController, appVm: AppViewModel, vm: SyncViewModel = viewModel()) {
    val pairs by vm.pairs.collectAsState()
    val running by vm.running.collectAsState()
    val progressMsg by vm.progressMsg.collectAsState()
    val conns by vm.connections.collectAsState()
    var editing by remember { mutableStateOf<SyncPair?>(null) }
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Sync, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Folder sync", fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) {
                Icon(Icons.Rounded.Add, "New pair")
            }
        },
    ) { pad ->
        if (pairs.isEmpty()) {
            Column(
                Modifier.padding(pad).fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Rounded.SyncAlt, null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text("No folder pairs yet", fontWeight = FontWeight.SemiBold)
                Text(
                    "Pair a local folder with another folder or a remote " +
                        "(SMB / SFTP / WebDAV / S3) and keep them in sync.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(pairs, key = { it.id }) { pair ->
                    PairCard(
                        pair = pair,
                        remoteLabel = pair.remoteConnId?.let { id ->
                            conns.firstOrNull { it.id == id }?.label
                        },
                        isRunning = pair.id in running,
                        progressMsg = if (pair.id in running) progressMsg else "",
                        onRun = { vm.runPair(pair) },
                        onEdit = { editing = pair },
                        onDelete = { vm.removePair(pair) },
                        onToggle = { vm.updatePair(pair.copy(enabled = !pair.enabled)) },
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    if (adding) PairDialog(
        conns = conns,
        initial = null,
        onSave = { vm.addPair(it); adding = false },
        onDismiss = { adding = false },
    )
    editing?.let { p ->
        PairDialog(
            conns = conns,
            initial = p,
            onSave = { vm.updatePair(it); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun PairCard(
    pair: SyncPair,
    remoteLabel: String?,
    isRunning: Boolean,
    progressMsg: String,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
) {
    val dirLabel = when (pair.direction) {
        "TO_REMOTE" -> "One-way → remote"
        "FROM_REMOTE" -> "One-way → local"
        else -> "Two-way"
    }
    val remoteDesc = remoteLabel?.let { "$it · ${pair.remoteFolder}" } ?: pair.remoteFolder
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).background(
                        MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isRunning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Sync, null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(pair.name, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(dirLabel, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = pair.enabled, onCheckedChange = { onToggle() })
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${shorten(pair.localFolder)}  ⇄  ${shorten(remoteDesc)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pair.lastSyncTime > 0) {
                Text(
                    "Last: ${fmtTime(pair.lastSyncTime)} — ${pair.lastStatus}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (isRunning && progressMsg.isNotEmpty()) {
                Text(progressMsg, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = onRun, enabled = pair.enabled && !isRunning,
                    label = { Text(if (isRunning) "Syncing…" else "Sync now") },
                    leadingIcon = { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp)) },
                )
                AssistChip(
                    onClick = onEdit,
                    label = { Text("Edit") },
                    leadingIcon = { Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp)) },
                )
                AssistChip(
                    onClick = onDelete,
                    label = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(16.dp)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairDialog(
    conns: List<RemoteConnection>,
    initial: SyncPair?,
    onSave: (SyncPair) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var localFolder by remember { mutableStateOf(initial?.localFolder ?: "") }
    var remoteConnId by remember { mutableStateOf(initial?.remoteConnId) }
    var remoteFolder by remember { mutableStateOf(initial?.remoteFolder ?: "") }
    var direction by remember { mutableStateOf(initial?.direction ?: "TWO_WAY") }
    var conflictRule by remember { mutableStateOf(initial?.conflictRule ?: "NEWER_WINS") }
    var deleteOrphans by remember { mutableStateOf(initial?.deleteOrphans ?: true) }
    var includeSubfolders by remember { mutableStateOf(initial?.includeSubfolders ?: true) }
    var syncOnOpen by remember { mutableStateOf(initial?.syncOnOpen ?: false) }
    var pickLocal by remember { mutableStateOf(false) }
    var pickRemoteLocal by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New sync pair" else "Edit sync pair") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true,
                )
                OutlinedButton(onClick = { pickLocal = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Folder, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (localFolder.isEmpty()) "Pick local folder" else shorten(localFolder),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Remote side: local folder or a saved connection.
                var connMenu by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { if (remoteConnId == null) pickRemoteLocal = true else connMenu = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Cloud, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            remoteConnId != null -> {
                                val cn = conns.firstOrNull { it.id == remoteConnId }
                                (cn?.label ?: "Remote") + if (remoteFolder.isNotEmpty()) " · $remoteFolder" else ""
                            }
                            remoteFolder.isNotEmpty() -> shorten(remoteFolder)
                            else -> "Pick destination (local folder)"
                        },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    TextButton(onClick = { connMenu = true }) {
                        Text(if (remoteConnId == null) "Use a remote connection instead"
                             else "Change remote / use local folder instead")
                    }
                    DropdownMenu(expanded = connMenu, onDismissRequest = { connMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Local folder") },
                            onClick = { remoteConnId = null; connMenu = false; pickRemoteLocal = true },
                        )
                        conns.forEach { cn ->
                            DropdownMenuItem(
                                text = { Text("${cn.label} (${cn.type.label})") },
                                onClick = { remoteConnId = cn.id; connMenu = false },
                            )
                        }
                    }
                }
                if (remoteConnId != null) {
                    OutlinedTextField(
                        value = remoteFolder, onValueChange = { remoteFolder = it },
                        label = { Text("Remote path (e.g. /backup/phone)") },
                        singleLine = true,
                    )
                }
                // Direction.
                Text("Direction", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("TO_REMOTE" to "Local →", "FROM_REMOTE" to "← Remote", "TWO_WAY" to "Two-way")
                        .forEach { (v, l) ->
                            FilterChip(
                                selected = direction == v,
                                onClick = { direction = v },
                                label = { Text(l) },
                            )
                        }
                }
                // Conflict rule (only meaningful for two-way).
                if (direction == "TWO_WAY") {
                    Text("Conflicts", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("NEWER_WINS" to "Newest", "LOCAL_WINS" to "Local",
                            "REMOTE_WINS" to "Remote", "SKIP" to "Skip")
                            .forEach { (v, l) ->
                                FilterChip(
                                    selected = conflictRule == v,
                                    onClick = { conflictRule = v },
                                    label = { Text(l) },
                                )
                            }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deleteOrphans, onCheckedChange = { deleteOrphans = it })
                    Text("Propagate deletions", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeSubfolders, onCheckedChange = { includeSubfolders = it })
                    Text("Include subfolders", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = syncOnOpen, onCheckedChange = { syncOnOpen = it })
                    Text("Sync when app opens", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && localFolder.isNotBlank() &&
                    (remoteConnId != null && remoteFolder.isNotBlank() || remoteConnId == null && remoteFolder.isNotBlank()),
                onClick = {
                    onSave((initial ?: SyncPair(name = "", localFolder = "")).copy(
                        name = name.trim(),
                        localFolder = localFolder,
                        remoteConnId = remoteConnId,
                        remoteFolder = remoteFolder.trim(),
                        direction = direction,
                        conflictRule = conflictRule,
                        deleteOrphans = deleteOrphans,
                        includeSubfolders = includeSubfolders,
                        syncOnOpen = syncOnOpen,
                    ))
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickLocal) FolderPickerSheet(
        onPick = { localFolder = it; pickLocal = false },
        onDismiss = { pickLocal = false },
    )
    if (pickRemoteLocal) FolderPickerSheet(
        onPick = { remoteFolder = it; remoteConnId = null; pickRemoteLocal = false },
        onDismiss = { pickRemoteLocal = false },
    )
}

private fun shorten(path: String): String {
    val base = File("/storage/emulated/0").absolutePath
    return if (path.startsWith("$base/")) "Internal/${path.removePrefix("$base/")}" else path
}

private fun fmtTime(t: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(t))
