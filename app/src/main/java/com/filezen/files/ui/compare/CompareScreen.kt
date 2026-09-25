package com.filezen.files.ui.compare

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.filezen.files.FileZenApp
import com.filezen.files.core.compare.DirCompare
import com.filezen.files.ui.AppViewModel
import com.filezen.files.ui.common.FolderPickerSheet
import com.filezen.files.ui.common.MassActionsBar
import com.filezen.files.ui.common.SectionHeader
import com.filezen.files.ui.common.rememberSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Twig-style directory compare: pick two folders, get a three-way report —
 * only-in-A, only-in-B, and files that differ (size/mtime).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CompareScreen(nav: NavController, appVm: AppViewModel) {
    var dirA by remember { mutableStateOf<File?>(null) }
    var dirB by remember { mutableStateOf<File?>(null) }
    var pickA by remember { mutableStateOf(false) }
    var pickB by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DirCompare.Result?>(null) }
    val scope = rememberCoroutineScope()
    val favorites by FileZenApp.c.db.favorites().all().collectAsState(initial = emptyList())
    val sel = rememberSelection()
    val filesVer by appVm.filesVersion.collectAsState()

    fun run() {
        val a = dirA ?: return; val b = dirB ?: return
        running = true; result = null
        scope.launch(Dispatchers.IO) {
            val r = DirCompare.compare(a, b)
            withContext(Dispatchers.Main) { result = r; running = false }
        }
    }

    LaunchedEffect(filesVer) { if (filesVer > 0 && result != null) run() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compare folders", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.fillMaxSize()) {
            // Pickers
            Row(Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Triple("Folder A", dirA, { pickA = true }),
                       Triple("Folder B", dirB, { pickB = true })).forEach { (tag, dir, pick) ->
                    Card(
                        Modifier.weight(1f).clickable { pick() },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(tag, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                            Text(dir?.name ?: "Tap to choose",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (dir != null) Text(dir.path,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Button(
                onClick = { run() },
                enabled = dirA != null && dirB != null && !running,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (running) "Comparing…" else "Compare now")
            }

            result?.let { r ->
                // Summary strip
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Triple("${r.onlyA.size}", "Only in A", MaterialTheme.colorScheme.error),
                        Triple("${r.different.size}", "Different", MaterialTheme.colorScheme.tertiary),
                        Triple("${r.onlyB.size}", "Only in B", MaterialTheme.colorScheme.primary),
                    ).forEach { (n, lbl, tint) ->
                        Surface(Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                            color = tint.copy(alpha = .12f)) {
                            Column(Modifier.padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(n, fontWeight = FontWeight.Bold, color = tint)
                                Text(lbl, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Text("${r.same} identical",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))

                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    @Composable
                    fun itemRow(icon: androidx.compose.ui.graphics.vector.ImageVector,
                                tint: Color, p: String, base: File) {
                        val abs = File(base, p).absolutePath
                        val isSel = abs in sel.selected
                        ListItem(
                            headlineContent = { Text(p,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = {
                                Icon(
                                    if (isSel) Icons.Rounded.CheckCircle else icon,
                                    null, Modifier.size(18.dp),
                                    tint = if (isSel) MaterialTheme.colorScheme.primary else tint)
                            },
                            modifier = Modifier.combinedClickable(
                                onClick = { sel.toggle(abs) },
                                onLongClick = { sel.toggle(abs) }),
                            colors = ListItemDefaults.colors(
                                containerColor = if (isSel)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                else Color.Transparent),
                        )
                    }
                    if (r.onlyA.isNotEmpty()) {
                        item { SectionHeader("Only in ${dirA?.name} — tap to select") }
                        items(r.onlyA) { itemRow(Icons.Rounded.RemoveCircleOutline,
                            MaterialTheme.colorScheme.error, it, dirA!!) }
                    }
                    if (r.different.isNotEmpty()) {
                        item { SectionHeader("Different content — tap to select (A-side)") }
                        items(r.different) { itemRow(Icons.Rounded.Difference,
                            MaterialTheme.colorScheme.tertiary, it, dirA!!) }
                    }
                    if (r.onlyB.isNotEmpty()) {
                        item { SectionHeader("Only in ${dirB?.name} — tap to select") }
                        items(r.onlyB) { itemRow(Icons.Rounded.AddCircleOutline,
                            MaterialTheme.colorScheme.primary, it, dirB!!) }
                    }
                    if (r.onlyA.isEmpty() && r.onlyB.isEmpty() && r.different.isEmpty()) {
                        item {
                            Text("Identical — ${r.same} entries match.",
                                Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        MassActionsBar(
            appVm, sel,
            allPaths = (result?.let {
                it.onlyA.map { p -> File(dirA!!, p).absolutePath } +
                it.different.map { p -> File(dirA!!, p).absolutePath } +
                it.onlyB.map { p -> File(dirB!!, p).absolutePath }
            } ?: emptyList()),
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        }
    }

    if (pickA) FolderPickerSheet(
        startPath = dirA?.path ?: android.os.Environment.getExternalStorageDirectory().absolutePath,
        onPick = { dirA = File(it); pickA = false },
        onDismiss = { pickA = false })
    if (pickB) FolderPickerSheet(
        startPath = dirB?.path ?: android.os.Environment.getExternalStorageDirectory().absolutePath,
        onPick = { dirB = File(it); pickB = false },
        onDismiss = { pickB = false })
}
