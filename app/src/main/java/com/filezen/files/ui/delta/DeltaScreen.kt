package com.filezen.files.ui.delta

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.filezen.files.core.delta.DeltaEngine
import com.filezen.files.core.model.formatSize
import com.filezen.files.ui.common.FilePickerSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Delta transfer (FastCDC) — FileZen's port of Google's cdc-file-transfer
 * (Apache-2.0). Split an old and a new version of a file into content-defined
 * chunks, keep only what's actually new in a `.fzpatch`, and rebuild the new
 * file anywhere the old one exists.
 *
 * Folder sync also uses the same chunker to report how much of each changed
 * file already existed at the destination ("Δ reused N%" in sync results).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeltaScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    var baseFile by remember { mutableStateOf<File?>(null) }
    var newFile by remember { mutableStateOf<File?>(null) }
    var pickTarget by remember { mutableStateOf<Int?>(null) } // 0 base, 1 new
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf<DeltaEngine.DeltaStats?>(null) }
    var applyTarget by remember { mutableStateOf<Pair<File, File>?>(null) } // patch, base
    var showApplyPicker by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Delta transfer", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.Rounded.ArrowBack, "Back")
                }
            })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Difference, null, Modifier.size(34.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text("FastCDC delta patches", fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium)
                                Text("Send only what changed — Google's content-defined " +
                                    "chunking finds the pieces of the old file that are " +
                                    "already inside the new one.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            item {
                Text("Create a patch", Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
            item {
                PickRow(icon = Icons.Rounded.History, label = "Old version (base)",
                    file = baseFile) { pickTarget = 0 }
            }
            item {
                PickRow(icon = Icons.Rounded.NewReleases, label = "New version",
                    file = newFile) { pickTarget = 1 }
            }
            item {
                Button(
                    onClick = {
                        val b = baseFile ?: return@Button
                        val n = newFile ?: return@Button
                        working = true; result = null; stats = null
                        scope.launch(Dispatchers.IO) {
                            val out = File(n.parentFile, n.name + ".fzpatch")
                            val st = runCatching { DeltaEngine.createPatch(b, n, out) }
                                .getOrNull()
                            withContext(Dispatchers.Main) {
                                working = false
                                stats = st
                                result = if (st != null)
                                    "Patch saved: ${out.path}\n" +
                                        "${out.length().let(::formatSize)} patch vs " +
                                        "${formatSize(st.newSize)} file — ${st.reusedPercent}% " +
                                        "of bytes reused (${formatSize(st.reusedBytes)}), " +
                                        "${formatSize(st.insertBytes)} new."
                                else "Could not create patch"
                            }
                        }
                    },
                    enabled = !working && baseFile != null && newFile != null,
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                ) { Text(if (working) "Working…" else "Create .fzpatch") }
            }

            stats?.let { st ->
                item {
                    Card(Modifier.fillMaxWidth().padding(16.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Result", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(result.orEmpty(), style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { st.reusedPercent / 100f },
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            } ?: result?.let {
                item {
                    Text(it, Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Apply a patch", Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
            item {
                PickRow(icon = Icons.Rounded.Construction, label = ".fzpatch file",
                    file = applyTarget?.first) {
                    showApplyPicker = true
                }
            }
            item {
                PickRow(icon = Icons.Rounded.History, label = "Old version (base)",
                    file = applyTarget?.second) {
                    pickTarget = 3
                }
            }
            item {
                Button(
                    onClick = {
                        val (patch, base) = applyTarget ?: return@Button
                        working = true; result = null; stats = null
                        scope.launch(Dispatchers.IO) {
                            val outName = patch.name.removeSuffix(".fzpatch") + ".rebuilt"
                            val out = File(base.parentFile, outName)
                            val ok = runCatching { DeltaEngine.applyPatch(base, patch, out) }
                                .isSuccess
                            withContext(Dispatchers.Main) {
                                working = false
                                result = if (ok) "Rebuilt: ${out.path} " +
                                    "(${formatSize(out.length())})"
                                else "Patch apply failed — wrong base file?"
                            }
                        }
                    },
                    enabled = !working && applyTarget != null &&
                        (applyTarget?.second?.isFile == true),
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                ) { Text("Rebuild new file") }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    pickTarget?.let { which ->
        FilePickerSheet(onPick = { p ->
            when (which) {
                0 -> baseFile = File(p)
                1 -> newFile = File(p)
                else -> applyTarget = applyTarget?.copy(second = File(p))
                    ?: (File("") to File(p))
            }
            pickTarget = null
        }, onDismiss = { pickTarget = null })
    }
    if (showApplyPicker) {
        FilePickerSheet(filter = { it.name.endsWith(".fzpatch") }, onPick = { p ->
            applyTarget = (applyTarget ?: (File(p) to File("")))
                .copy(first = File(p))
            showApplyPicker = false
        }, onDismiss = { showApplyPicker = false })
    }
}

@Composable
private fun PickRow(icon: androidx.compose.ui.graphics.vector.ImageVector,
                    label: String, file: File?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            Text(file?.let { "${it.name} (${formatSize(it.length())})" } ?: "Tap to choose…",
                style = MaterialTheme.typography.labelSmall, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
        modifier = Modifier.clickable { onClick() },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), thickness = 0.5.dp)
}
