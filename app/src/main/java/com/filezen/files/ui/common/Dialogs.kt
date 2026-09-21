package com.filezen.files.ui.common

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.filezen.files.core.model.formatSize
import com.filezen.files.data.db.Favorite
import java.io.File

@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    hint: String = "",
    confirmLabel: String = "OK",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text(hint) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String = "Confirm",
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel,
                    color = if (danger) MaterialTheme.colorScheme.error else LocalContentColor.current)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Convert dialog: shows the detected source type and format chips. */
@Composable
fun ConvertDialog(
    name: String,
    targets: List<com.filezen.files.core.convert.ConvertEngine.Target>,
    onConvert: (com.filezen.files.core.convert.ConvertEngine.Target) -> Unit,
    onDismiss: () -> Unit,
) {
    var sel by remember { mutableStateOf(targets.firstOrNull()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Transform, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Convert file")
            }
        },
        text = {
            Column {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text("To format", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    targets.forEach { t ->
                        FilterChip(
                            selected = sel == t,
                            onClick = { sel = t },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { sel?.let(onConvert) }, enabled = sel != null) { Text("Convert") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Folder picker sheet: browse the filesystem, descend into directories,
 * create a new folder, and confirm a destination path. Used by the sort-rule
 * editor and any "move to…" flow that needs an arbitrary folder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerSheet(
    startPath: String = Environment.getExternalStorageDirectory().absolutePath,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var dir by remember { mutableStateOf(File(startPath).let { if (it.isDirectory) it else Environment.getExternalStorageDirectory() }) }
    var subdirs by remember { mutableStateOf<List<File>>(emptyList()) }
    var newFolder by remember { mutableStateOf(false) }

    LaunchedEffect(dir) {
        subdirs = runCatching {
            dir.listFiles()?.filter { it.isDirectory && !it.isHidden }?.sortedBy { it.name.lowercase() }
        }.getOrNull() ?: emptyList()
    }

    val shortcuts = remember {
        listOf(
            Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_MOVIES,
        ).map { Environment.getExternalStoragePublicDirectory(it) }.filter { it.exists() || it.parentFile?.exists() == true }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Choose folder", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(dir.absolutePath, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { dir.parentFile?.let { if (it.canRead()) dir = it } }) {
                Icon(Icons.Rounded.ArrowUpward, "Up")
            }
            IconButton(onClick = { newFolder = true }) {
                Icon(Icons.Rounded.CreateNewFolder, "New folder")
            }
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            shortcuts.forEach { f ->
                AssistChip(
                    onClick = { dir = f },
                    label = { Text(f.name) },
                    leadingIcon = { Icon(Icons.Rounded.Folder, null, Modifier.size(16.dp)) },
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 320.dp)) {
            if (subdirs.isEmpty()) {
                item {
                    Text("No subfolders here",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(subdirs, key = { it.absolutePath }) { d ->
                ListItem(
                    headlineContent = { Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        Icon(Icons.Rounded.Folder, null,
                            tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
                    modifier = Modifier.clickable { dir = d },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }

        Button(
            onClick = { onPick(dir.absolutePath) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text("Use this folder") }
        Spacer(Modifier.height(28.dp))
    }

    if (newFolder) {
        TextInputDialog(
            title = "New folder", hint = "Folder name",
            onConfirm = { name ->
                File(dir, name).mkdirs()
                File(dir, name).takeIf { it.isDirectory }?.let { dir = it }
                newFolder = false
            },
            onDismiss = { newFolder = false },
        )
    }
}

/**
 * File picker sheet: like [FolderPickerSheet] but taps pick a FILE —
 * used by delta patch (base/new) and other single-file flows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePickerSheet(
    startPath: String = Environment.getExternalStorageDirectory().absolutePath,
    filter: (File) -> Boolean = { true },
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var dir by remember {
        mutableStateOf(File(startPath).let {
            if (it.isDirectory) it else Environment.getExternalStorageDirectory()
        })
    }
    var kids by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(dir) {
        kids = runCatching {
            dir.listFiles()?.filter { !it.isHidden && (it.isDirectory || filter(it)) }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }.getOrNull() ?: emptyList()
    }
    val shortcuts = remember {
        listOf(Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_DCIM)
            .map { Environment.getExternalStoragePublicDirectory(it) }
            .filter { it.exists() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Choose file", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(dir.absolutePath, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { dir.parentFile?.let { if (it.canRead()) dir = it } }) {
                Icon(Icons.Rounded.ArrowUpward, "Up")
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shortcuts.forEach { f ->
                AssistChip(onClick = { dir = f }, label = { Text(f.name) },
                    leadingIcon = { Icon(Icons.Rounded.Folder, null, Modifier.size(16.dp)) })
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
            if (kids.isEmpty()) {
                item {
                    Text("Nothing here",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(kids, key = { it.absolutePath }) { f ->
                ListItem(
                    headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        if (!f.isDirectory) Text(formatSize(f.length()),
                            style = MaterialTheme.typography.labelSmall)
                    },
                    leadingContent = {
                        Icon(if (f.isDirectory) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile,
                            null, tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable {
                        if (f.isDirectory) dir = f else onPick(f.absolutePath)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * Destination picker for move/copy and the Inbox "Rapikan" flow:
 * favourite folders first, then browse / current folder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinationSheet(
    favorites: List<Favorite>,
    currentPath: String,
    shareDir: File? = null,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var browse by remember { mutableStateOf(false) }
    if (browse) {
        FolderPickerSheet(
            startPath = currentPath,
            onPick = onPick,
            onDismiss = { browse = false },
        )
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Move to…", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
            if (shareDir != null) {
                item(key = "share") {
                    ListItem(
                        headlineContent = { Text("FileZen Share") },
                        supportingContent = {
                            Text("Ready for Transfer to PC · ${shareDir.absolutePath}",
                                style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = {
                            Icon(Icons.Rounded.Phonelink, null,
                                tint = MaterialTheme.colorScheme.tertiary)
                        },
                        modifier = Modifier.clickable {
                            shareDir.mkdirs()
                            onPick(shareDir.absolutePath)
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(0.35f)),
                    )
                }
            }
            items(favorites) { f ->
                ListItem(
                    headlineContent = { Text(f.label) },
                    supportingContent = {
                        Text(f.path, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    },
                    leadingContent = {
                        Icon(Icons.Rounded.Star, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable { onPick(f.path) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = { browse = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        ) { Text("Browse for a folder…") }
        TextButton(
            onClick = { onPick(currentPath) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        ) { Text("Current folder") }
        Spacer(Modifier.height(28.dp))
    }
}
