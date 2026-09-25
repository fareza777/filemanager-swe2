package com.filezen.files.ui.similar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.filezen.files.core.imohash.ImoHash
import com.filezen.files.core.model.formatSize
import com.filezen.files.core.tlsh.Tlsh
import com.filezen.files.ui.AppViewModel
import com.filezen.files.ui.common.FolderPickerSheet
import com.filezen.files.ui.common.MassActionsBar
import com.filezen.files.ui.common.rememberSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Similar & fast-duplicate finder:
 *  - "Near-duplicates" via TLSH fuzzy hashes (files that are *similar*, not identical)
 *  - "Exact duplicates" pre-scanned with imohash (begin/mid/end sampling) then
 *    confirmed with full SHA-256 — fast even on large files.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SimilarScreen(nav: NavController, appVm: AppViewModel) {
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var simGroups by remember { mutableStateOf<List<List<Pair<File, Int>>>>(emptyList()) }
    var dupGroups by remember { mutableStateOf<List<List<File>>>(emptyList()) }
    var stats by remember { mutableStateOf("") }
    val sel = rememberSelection()
    val filesVer by appVm.filesVersion.collectAsState()

    fun scan(dir: File) {
        folder = dir
        scope.launch(Dispatchers.IO) {
            busy = "Hashing files…"
            try {
                val files = dir.walkTopDown().filter { it.isFile && it.length() >= 256 }.toList()
                // TLSH digests
                val digests = files.mapNotNull { f ->
                    val t = runCatching { Tlsh.digest(f) }.getOrNull()
                    if (t != null && t.isValid) f to t else null
                }
                // pairwise near-duplicates: diff <= 60 = similar cluster
                val parent = IntArray(digests.size) { it }
                fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
                fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }
                val bestDiff = IntArray(digests.size) { Int.MAX_VALUE }
                for (i in digests.indices) for (j in i + 1 until digests.size) {
                    val d = digests[i].second.diff(digests[j].second)
                    if (d <= 60) {
                        union(i, j)
                        bestDiff[i] = minOf(bestDiff[i], d); bestDiff[j] = minOf(bestDiff[j], d)
                    }
                }
                val groups = digests.indices.groupBy { find(it) }.values
                    .filter { it.size > 1 }
                    .map { idx -> idx.map { digests[it].first to bestDiff[it] } }
                    .sortedByDescending { it.size }

                // imohash fast pre-scan → sha256 confirm on colliding groups
                busy = "Pre-scanning for exact duplicates…"
                val imoGroups = files.filter { it.length() > 0 }
                    .groupBy { runCatching { ImoHash.hex(it.absolutePath) }.getOrDefault("") }
                    .values.filter { it.size > 1 }
                val exact = mutableListOf<List<File>>()
                for (g in imoGroups) {
                    val bySha = g.groupBy {
                        runCatching {
                            val md = MessageDigest.getInstance("SHA-256")
                            it.inputStream().use { s ->
                                val buf = ByteArray(1 shl 20); var n: Int
                                while (s.read(buf).also { n = it } > 0) md.update(buf, 0, n)
                            }
                            md.digest().joinToString("") { b -> "%02x".format(b) }
                        }.getOrDefault("err-${it.absolutePath}")
                    }.values.filter { it.size > 1 }
                    exact += bySha
                }
                withContext(Dispatchers.Main) {
                    simGroups = groups
                    dupGroups = exact
                    stats = "${files.size} files scanned — ${digests.size} TLSH digests, ${imoGroups.size} imohash candidate groups"
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { stats = "Failed: ${t.message}" }
            } finally { withContext(Dispatchers.Main) { busy = null } }
        }
    }

    LaunchedEffect(filesVer) { if (filesVer > 0) folder?.let { scan(it) } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Similar files", fontWeight = FontWeight.SemiBold) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, null) } }) },
        bottomBar = {}
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Card(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Fuzzy file DNA", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "TLSH fingerprints find files that are *similar* — same document edited, re-downloaded, re-encoded. imohash pre-scans large files for exact duplicates without reading them whole.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { picking = true }, enabled = busy == null) {
                        Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Pick folder to scan")
                    }
                    if (stats.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(stats, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            busy?.let { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)); Spacer(Modifier.height(4.dp)); Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
            LazyColumn(Modifier.fillMaxSize()) {
                if (simGroups.isNotEmpty()) {
                    item { Text("Near-duplicates (TLSH) — tap to select", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 8.dp)) }
                    items(simGroups) { g ->
                        Card(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                g.forEach { (f, d) ->
                                    val p = f.absolutePath
                                    val isSel = p in sel.selected
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = { sel.toggle(p) },
                                                onLongClick = { sel.toggle(p) })
                                            .padding(vertical = 4.dp),
                                    ) {
                                        Icon(
                                            if (isSel) Icons.Rounded.CheckCircle
                                            else Icons.Rounded.InsertDriveFile, null,
                                            tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                            Text("${formatSize(f.length())} · diff $d", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (dupGroups.isNotEmpty()) {
                    item { Text("Exact duplicates (imohash + SHA-256) — tap to select", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 8.dp)) }
                    items(dupGroups) { g ->
                        Card(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                g.forEach { f ->
                                    val p = f.absolutePath
                                    val isSel = p in sel.selected
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = { sel.toggle(p) },
                                                onLongClick = { sel.toggle(p) })
                                            .padding(vertical = 4.dp),
                                    ) {
                                        Icon(
                                            if (isSel) Icons.Rounded.CheckCircle
                                            else Icons.Rounded.ContentCopy, null,
                                            tint = if (isSel) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.secondary)
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                            Text(formatSize(f.length()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (simGroups.isEmpty() && dupGroups.isEmpty() && folder != null && busy == null) {
                    item { Text("No duplicates or near-duplicates found.", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        MassActionsBar(
            appVm, sel,
            allPaths = simGroups.flatten().map { it.first.absolutePath } +
                dupGroups.flatten().map { it.absolutePath },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        }
    }

    if (picking) {
        FolderPickerSheet(
            onPick = { p ->
                picking = false
                folder = File(p)
                scan(File(p))
            },
            onDismiss = { picking = false },
        )
    }
}
