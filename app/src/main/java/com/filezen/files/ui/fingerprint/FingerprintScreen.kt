package com.filezen.files.ui.fingerprint

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.filezen.files.FileZenApp
import com.filezen.files.core.merkle.MerkleFs
import com.filezen.files.core.model.formatSize
import com.filezen.files.data.db.Fingerprint
import com.filezen.files.data.db.FingerprintEntry
import com.filezen.files.ui.common.FolderPickerSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Folder fingerprint — Merkle tree hashing in IPFS UnixFSv1 CIDv1 form.
 * One content-address summarises a whole folder tree: re-verify later to see
 * exactly which files were added, changed or removed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FingerprintScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    val dao = remember { FileZenApp.c.db.fingerprints() }
    val saved by dao.all().collectAsState(initial = emptyList())

    var picking by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf<String?>(null) }
    var current by remember { mutableStateOf<Pair<File, MerkleFs.Fingerprint>?>(null) }
    var verifyResult by remember { mutableStateOf<Pair<Fingerprint, MerkleFs.Diff>?>(null) }
    var verifying by remember { mutableStateOf<Fingerprint?>(null) }

    fun fingerprint(f: File, onDone: (MerkleFs.Fingerprint) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            working = "Scanning ${f.name}…"
            try {
                val fp = MerkleFs.hashDir(f) { file -> working = file.name }
                withContext(Dispatchers.Main) {
                    current = f to fp
                    working = null
                }
                onDone(fp)
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { working = "Failed: ${t.message}" }
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Folder fingerprint", fontWeight = FontWeight.Bold) },
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
                            Icon(Icons.Rounded.Fingerprint, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Merkle folder fingerprint",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Computes one IPFS-style CID that uniquely describes the whole folder tree. " +
                                "Save a snapshot now, verify later — any added, changed or deleted file changes the fingerprint.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { picking = true }, enabled = working == null) {
                            Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Fingerprint a folder")
                        }
                    }
                }
            }

            working?.let { msg ->
                item {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(msg, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
            }

            current?.let { (folder, fp) ->
                item {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(0.4f))) {
                        Column(Modifier.padding(18.dp)) {
                            Text(folder.name, fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium)
                            Text(folder.absolutePath,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1)
                            Spacer(Modifier.height(10.dp))
                            Text(fp.rootCid, fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(8.dp))
                            Text("${fp.fileCount} files · ${fp.dirCount} folders · ${formatSize(fp.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        val id = dao.insert(Fingerprint(
                                            path = folder.absolutePath, cid = fp.rootCid,
                                            fileCount = fp.fileCount, dirCount = fp.dirCount,
                                            totalBytes = fp.totalBytes,
                                            takenAt = System.currentTimeMillis()))
                                        dao.insertEntries(fp.entries.map { (rel, cid) ->
                                            FingerprintEntry(id, rel, cid,
                                                fp.sizes[rel] ?: -1L,
                                                fp.sizes[rel] == -1L)
                                        })
                                    }
                                }) {
                                    Icon(Icons.Rounded.Save, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Save snapshot")
                                }
                                OutlinedButton(onClick = { current = null }) {
                                    Text("Dismiss")
                                }
                            }
                        }
                    }
                }
            }

            if (saved.isNotEmpty()) {
                item {
                    Text("Saved fingerprints", fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                }
                items(saved, key = { it.id }) { fp ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(File(fp.path).name, fontWeight = FontWeight.SemiBold)
                                    Text(fp.path, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1)
                                    Text(fp.cid.take(28) + "…",
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                    Text(
                                        "${fp.fileCount} files · ${formatSize(fp.totalBytes)} · " +
                                            SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
                                                .format(Date(fp.takenAt)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Column {
                                    TextButton(
                                        enabled = verifying == null,
                                        onClick = {
                                            verifying = fp
                                            scope.launch(Dispatchers.IO) {
                                                val oldEntries = dao.entries(fp.id)
                                                    .associate { it.relPath to it.cid }
                                                val now = try {
                                                    MerkleFs.hashDir(File(fp.path))
                                                } catch (t: Throwable) { null }
                                                withContext(Dispatchers.Main) {
                                                    verifying = null
                                                    if (now == null) {
                                                        verifyResult = fp to MerkleFs.Diff(
                                                            emptyList(), listOf("(folder missing)"), emptyList())
                                                    } else {
                                                        val old = MerkleFs.Fingerprint(
                                                            "", oldEntries, emptyMap(), 0, 0, 0)
                                                        verifyResult = fp to MerkleFs.diff(old, now)
                                                    }
                                                }
                                            }
                                        }) {
                                        if (verifying == fp)
                                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                        else Text("Verify")
                                    }
                                    TextButton(onClick = {
                                        scope.launch(Dispatchers.IO) { dao.deleteFull(fp) }
                                    }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (picking) {
        FolderPickerSheet(
            onPick = { path -> picking = false; fingerprint(File(path)) },
            onDismiss = { picking = false },
        )
    }

    verifyResult?.let { (fp, diff) ->
        AlertDialog(
            onDismissRequest = { verifyResult = null },
            title = { Text(if (diff.identical) "Unchanged" else "Changes detected") },
            text = {
                Column {
                    if (diff.identical) {
                        Text("Fingerprint still matches — nothing inside ${File(fp.path).name} changed.")
                        Spacer(Modifier.height(6.dp))
                        Text(fp.cid, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall)
                    } else {
                        if (diff.changed.isNotEmpty()) {
                            Text("Changed (${diff.changed.size})", fontWeight = FontWeight.SemiBold)
                            diff.changed.take(12).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        }
                        if (diff.added.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Added (${diff.added.size})", fontWeight = FontWeight.SemiBold)
                            diff.added.take(12).forEach { Text("+ $it", style = MaterialTheme.typography.bodySmall) }
                        }
                        if (diff.removed.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Removed (${diff.removed.size})", fontWeight = FontWeight.SemiBold)
                            diff.removed.take(12).forEach { Text("− $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { verifyResult = null }) { Text("OK") }
            },
        )
    }
}
