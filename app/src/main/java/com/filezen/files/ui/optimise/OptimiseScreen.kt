package com.filezen.files.ui.optimise

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.filezen.files.FileZenApp
import com.filezen.files.core.convert.ConvertEngine
import com.filezen.files.core.model.FileEntry
import com.filezen.files.core.model.FileType
import com.filezen.files.core.model.formatSize
import com.filezen.files.ui.AppViewModel
import com.filezen.files.ui.StorageViewModel
import com.filezen.files.ui.common.ThumbBox
import java.io.File

/**
 * Optimise — the real cleanup workbench behind the Home "Optimise" card.
 * Trash purge, duplicate reclaim, photo recompression and format conversion
 * in one place; Storage stays the *analysis* view.
 */
@Composable
fun OptimiseScreen(nav: NavController, appVm: AppViewModel) {
    val storageVm: StorageViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val c = FileZenApp.c
    val trashEntries by storageVm.trashEntries.collectAsState()
    val trashSize by storageVm.trashSize.collectAsState()
    val dups by storageVm.dups.collectAsState()
    val usage by storageVm.usage.collectAsState()
    val analyzing by storageVm.analyzing.collectAsState()

    // Kick the analyzer once if it has never run (dupes need it).
    LaunchedEffect(Unit) { if (usage == null) storageVm.analyze() }

    // Compressible photos + convertible files straight from the search index.
    val compressible by produceState<List<FileEntry>>(emptyList()) {
        value = c.fileIndex.byType("IMAGE").map { FileEntry(it.path, it.name, false,
            it.size, it.lastModified, FileType.valueOf(it.type)) }
            .filter { ConvertEngine.canCompress(it) && it.size > 300_000 }
    }
    val convertible by produceState<List<FileEntry>>(emptyList()) {
        value = (c.fileIndex.byType("IMAGE") + c.fileIndex.byType("TEXT"))
            .map { FileEntry(it.path, it.name, false, it.size, it.lastModified,
                FileType.valueOf(it.type)) }
            .filter { ConvertEngine.targetsFor(it).isNotEmpty() }
    }

    val dupeReclaim = dups.sumOf { g -> g.files.drop(1).sumOf { it.size } }
    val reclaimTotal = trashSize + dupeReclaim
    val animatedReclaim by animateFloatAsState(reclaimTotal.toFloat(), label = "rk")

    var confirmPurge by remember { mutableStateOf(false) }
    var convertPick by remember { mutableStateOf(false) }
    var compressPick by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            CenterAlignedTopAppBar(
                title = { Text("Optimise storage", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                // Hero: animated reclaimable total on a teal gradient
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                        .background(Brush.linearGradient(
                            listOf(Color(0xFF2E7D64), Color(0xFF124A3B))))
                        .padding(26.dp),
                ) {
                    Column {
                        Text("Reclaimable space",
                            color = Color(0xFFBFE8DC),
                            style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(formatSize(animatedReclaim.toLong()),
                            color = Color.White,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (analyzing) "Analysing storage…"
                            else "${trashEntries.size} in trash · ${dups.size} duplicate groups",
                            color = Color(0xFF9FD6C6),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // — Trash —
            item {
                OptCard(
                    icon = Icons.Rounded.Delete, tint = Color(0xFFF0A7C3),
                    title = "Empty trash",
                    sub = "${trashEntries.size} items · ${formatSize(trashSize)}",
                    actionLabel = if (trashEntries.isEmpty()) null else "Empty all",
                ) { confirmPurge = true }
            }

            // — Duplicates —
            item {
                OptCard(
                    icon = Icons.Rounded.ContentCopy, tint = Color(0xFF8AB4F8),
                    title = "Identical duplicates",
                    sub = if (analyzing) "Scanning…"
                        else "${dups.size} groups · ${formatSize(dupeReclaim)} reclaimable",
                    actionLabel = if (dups.isEmpty()) null else "Auto-clean",
                ) {
                    // Keep the newest copy in each group, trash the rest.
                    val drop = dups.flatMap { g ->
                        g.files.sortedByDescending { it.lastModified }.drop(1)
                            .map { it.path } }
                    if (drop.isNotEmpty()) appVm.opTrash(drop)
                }
            }
            items(dups.take(6)) { g ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ThumbBox(g.files.first(), 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(g.files.first().name, maxLines = 1,
                            style = MaterialTheme.typography.bodyMedium)
                        Text("${g.files.size} copies · ${formatSize(g.files.first().size)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = {
                        val drop = g.files.sortedByDescending { it.lastModified }
                            .drop(1).map { it.path }
                        appVm.opTrash(drop)
                    }) { Text("Keep newest") }
                }
            }

            // — Compress photos —
            item {
                OptCard(
                    icon = Icons.Rounded.Compress, tint = Color(0xFF81D5C0),
                    title = "Compress photos",
                    sub = "${compressible.size} photos >300 KB — re-encode smaller, keep originals when bigger",
                    actionLabel = if (compressible.isEmpty()) null else "Pick & compress",
                ) { compressPick = true }
            }

            // — Convert files —
            item {
                OptCard(
                    icon = Icons.Rounded.Transform, tint = Color(0xFFD9BAF7),
                    title = "Convert files",
                    sub = "Images → PNG / JPEG / WebP / PDF · text → PDF",
                    actionLabel = "Choose files",
                ) { convertPick = true }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmPurge) {
        AlertDialog(
            onDismissRequest = { confirmPurge = false },
            icon = { Icon(Icons.Rounded.DeleteForever, null,
                tint = MaterialTheme.colorScheme.error) },
            title = { Text("Empty trash?") },
            text = { Text("${trashEntries.size} items (${formatSize(trashSize)}) will be deleted permanently. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { storageVm.purgeAll(); confirmPurge = false }) {
                    Text("Empty all", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmPurge = false }) { Text("Cancel") } },
        )
    }

    if (compressPick) {
        FilePickSheet(
            title = "Compress photos",
            entries = compressible,
            actionLabel = "JPEG quality 80 · max 2048 px",
            onRun = { picked -> appVm.opCompressImages(picked); compressPick = false },
            onDismiss = { compressPick = false },
        )
    }
    if (convertPick) {
        ConvertSheet(
            entries = convertible,
            onRun = { picked, target -> appVm.opConvertMany(picked, target); convertPick = false },
            onDismiss = { convertPick = false },
        )
    }
}

@Composable
private fun OptCard(
    icon: ImageVector, tint: Color, title: String, sub: String,
    actionLabel: String?, onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium)
            Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
        if (actionLabel != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Multi-select picker sheet used by compress + convert. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilePickSheet(
    title: String,
    entries: List<FileEntry>,
    actionLabel: String,
    onRun: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val picked = remember { mutableStateMapOf<String, Boolean>() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Text(actionLabel, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 280.dp)) {
                items(entries) { e ->
                    val on = picked[e.path] ?: false
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = { picked[e.path] = it })
                        ThumbBox(e, 40.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, maxLines = 1,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(formatSize(e.size),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            val sel = picked.filterValues { it }.keys.toList()
            Button(
                onClick = { onRun(sel) },
                enabled = sel.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp).height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("Compress ${sel.size} photos") }
        }
    }
}

/** Convert picker: files + target-format chips in one sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConvertSheet(
    entries: List<FileEntry>,
    onRun: (List<String>, ConvertEngine.Target) -> Unit,
    onDismiss: () -> Unit,
) {
    val picked = remember { mutableStateMapOf<String, Boolean>() }
    var target by remember { mutableStateOf(ConvertEngine.Target.JPEG) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Convert files", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConvertEngine.Target.entries.forEach { t ->
                    FilterChip(
                        selected = target == t,
                        onClick = { target = t },
                        label = { Text(t.label) })
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 280.dp)) {
                items(entries) { e ->
                    val ok = ConvertEngine.targetsFor(e).contains(target)
                    val on = picked[e.path] ?: false
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on && ok, enabled = ok,
                            onCheckedChange = { picked[e.path] = it })
                        ThumbBox(e, 40.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, maxLines = 1,
                                color = if (ok) Color.Unspecified
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(if (ok) formatSize(e.size) else "can't convert to ${target.label}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            val sel = picked.filterValues { it }.keys.toList()
            Button(
                onClick = { onRun(sel, target) },
                enabled = sel.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp).height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("Convert ${sel.size} files → ${target.label}") }
        }
    }
}
