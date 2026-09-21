package com.filezen.files.ui.privacy

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
import com.filezen.files.core.model.FileEntry
import com.filezen.files.core.model.formatSize
import com.filezen.files.core.privacy.MetadataCleaner
import com.filezen.files.ops.Intents
import com.filezen.files.ui.AppViewModel
import com.filezen.files.ui.PrivacyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Safe Share — files that can carry metadata, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(nav: NavController, appVm: AppViewModel, vm: PrivacyViewModel = viewModel()) {
    val files by vm.files.collectAsState()
    val scanning by vm.scanning.collectAsState()
    var target by remember { mutableStateOf<FileEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PrivacyTip, null,
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Safe share", fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Rounded.Refresh, "Rescan") }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Photos, videos & documents carry hidden data — GPS location, " +
                                "camera model, author, timestamps. Strip it before sharing.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "JPEG · PNG · WebP · PDF can be cleaned. Media is view-only.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (scanning) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (files.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Rounded.VerifiedUser, null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text("No photos, videos or documents found",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(files, key = { it.path }) { e ->
                    val cleanable = MetadataCleaner.isCleanable(e.path)
                    ListItem(
                        headlineContent = { Text(e.name, maxLines = 1) },
                        supportingContent = {
                            Text(
                                "${formatSize(e.size)} · ${e.path.substringBeforeLast('/')}",
                                maxLines = 1,
                            )
                        },
                        leadingContent = {
                            Icon(
                                if (cleanable) Icons.Rounded.Shield else Icons.Rounded.ShieldMoon,
                                null,
                                tint = if (cleanable) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            Text(
                                if (cleanable) "clean" else "view",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (cleanable) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .clickable { target = e },
                        tonalElevation = 0.dp,
                    )
                    HorizontalDivider(
                        Modifier.padding(start = 72.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }

    target?.let { e ->
        MetadataDialog(entry = e, appVm = appVm, onDismiss = { target = null })
    }
}

/**
 * Metadata report + clean/share actions for one file. Reused by Browse.
 */
@Composable
fun MetadataDialog(entry: FileEntry, appVm: AppViewModel, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember(entry.path) { mutableStateOf<MetadataCleaner.Report?>(null) }
    var busy by remember { mutableStateOf(false) }
    var doneMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(entry.path) {
        report = withContext(Dispatchers.IO) { MetadataCleaner.scan(File(entry.path)) }
    }

    fun shareCleaned() {
        scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) {
                val dir = File(ctx.cacheDir, "cleaned").apply { mkdirs() }
                val dst = File(dir, File(entry.path).name)
                MetadataCleaner.clean(File(entry.path), dst)
            }
            busy = false
            if (ok) {
                Intents.share(
                    ctx,
                    listOf(FileEntry.from(File(ctx.cacheDir, "cleaned/${File(entry.path).name}"))),
                )
                onDismiss()
            } else doneMsg = "Clean failed for this file"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.PrivacyTip, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(entry.name, maxLines = 1) },
        text = {
            Column {
                val r = report
                if (r == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Scanning metadata…")
                    }
                } else {
                    Text(
                        "${r.kind} · ${if (r.cleanable) "metadata can be removed" else "view only"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (r.cleanable) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (r.fields.isEmpty()) {
                        Text(
                            r.note ?: "No embedded metadata found — this file is already clean.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Column(Modifier.heightIn(max = 260.dp)) {
                            LazyColumn {
                                items(r.fields) { f ->
                                    Row(Modifier.padding(vertical = 3.dp)) {
                                        Icon(
                                            if (f.label.contains("Location"))
                                                Icons.Rounded.LocationOn else Icons.Rounded.Label,
                                            null, modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text("${f.label}: ", style = MaterialTheme.typography.bodySmall)
                                        Text(f.value, style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium, maxLines = 2)
                                    }
                                }
                            }
                        }
                    }
                    r.note?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (busy) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    doneMsg?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            val r = report
            TextButton(
                enabled = !busy && r != null && r.cleanable,
                onClick = {
                    appVm.opCleanMetadata(listOf(entry.path))
                    onDismiss()
                },
            ) { Text("Save cleaned copy") }
        },
        dismissButton = {
            val r = report
            Row {
                TextButton(
                    enabled = !busy && r != null && r.cleanable,
                    onClick = { shareCleaned() },
                ) { Text("Share clean") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
