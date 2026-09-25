package com.filezen.files.ui.dualpane

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.filezen.files.FileZenApp
import com.filezen.files.core.model.FileEntry
import com.filezen.files.core.model.formatSize
import com.filezen.files.core.scan.Scanner
import com.filezen.files.ui.AppViewModel
import com.filezen.files.ui.common.iconFor
import com.filezen.files.ui.common.tintFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Minimal per-pane state for the dual-pane browser. */
private class PaneState(initial: String) {
    var path by mutableStateOf(initial)
    var entries by mutableStateOf<List<FileEntry>>(emptyList())
    var selection = mutableStateOf<Set<String>>(emptySet())
    var error by mutableStateOf<String?>(null)
}

/**
 * Twig-style dual-pane: two file lists side-by-side (wide screens) or stacked
 * (portrait). Tap files to select, tap a folder to open it, then copy/move the
 * selection into the other pane.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DualPaneScreen(nav: NavController, appVm: AppViewModel) {
    val c = FileZenApp.c
    val scope = rememberCoroutineScope()
    val startDir = remember {
        kotlinx.coroutines.runBlocking {
            c.settings.lastBrowsePath.first()
        }?.takeIf { it.isNotBlank() }
            ?: Environment.getExternalStorageDirectory().absolutePath
    }
    // The two panes should start on DIFFERENT folders — copying a folder onto
    // itself is useless. Right pane prefers Downloads, the usual drop target.
    val rightDir = remember {
        val dl = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS).absolutePath
        if (startDir != dl) dl else Environment.getExternalStorageDirectory().absolutePath
    }
    val left = remember { PaneState(startDir) }
    val right = remember { PaneState(rightDir) }
    var active by remember { mutableStateOf(0) }        // 0 = left/top, 1 = right/bottom

    suspend fun load(p: PaneState) = withContext(Dispatchers.IO) {
        try { p.entries = Scanner.listDir(p.path, false); p.error = null }
        catch (e: Exception) { p.error = e.message; p.entries = emptyList() }
    }
    LaunchedEffect(Unit) { load(left); load(right) }

    // Folder picker — which pane is being redirected (null = closed).
    var pickFor by remember { mutableStateOf<PaneState?>(null) }

    // Only one pane may hold a selection at a time — keeps "copy to other"
    // unambiguous.
    fun selectIn(p: PaneState, other: PaneState, path: String) {
        p.selection.value = p.selection.value.toMutableSet().apply {
            if (path in this) remove(path) else add(path)
        }
        if (p.selection.value.isNotEmpty()) other.selection.value = emptySet()
    }

    suspend fun transfer(move: Boolean) {
        val src = if (left.selection.value.isNotEmpty()) left else right
        val dst = if (src === left) right else left
        val paths = src.selection.value
        if (paths.isEmpty()) return
        if (move) appVm.opMove(paths.toList(), File(dst.path),
            com.filezen.files.core.fileops.ConflictPolicy.KEEP_BOTH)
        else appVm.opCopy(paths.toList(), File(dst.path),
            com.filezen.files.core.fileops.ConflictPolicy.KEEP_BOTH)
        src.selection.value = emptySet()
        load(dst); load(src)
    }

    val selCount = left.selection.value.size + right.selection.value.size
    val selSrc = if (left.selection.value.isNotEmpty()) left else right
    val selDst = if (selSrc === left) right else left
    val wide = LocalConfiguration.current.screenWidthDp >= 600 ||
        LocalConfiguration.current.screenHeightDp < 500

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dual pane", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, "Back") }
                },
            )
        },
        bottomBar = {
            if (selCount > 0) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("$selCount selected → ${selDst.path}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { scope.launch { transfer(false) } },
                                modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Copy here")
                            }
                            OutlinedButton(onClick = { scope.launch { transfer(true) } },
                                modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.DriveFileMove, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Move here")
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        val panes = listOf(left, right)
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text("Tap files to select, tap a folder to open it, then copy / move to the other pane.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    panes.forEachIndexed { i, p ->
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            Pane(p, i, active == i, { active = i }, { load(p) },
                                { path -> selectIn(p, panes[1 - i], path) },
                                { pickFor = p })
                        }
                        if (i == 0) VerticalDivider()
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    panes.forEachIndexed { i, p ->
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            Pane(p, i, active == i, { active = i }, { load(p) },
                                { path -> selectIn(p, panes[1 - i], path) },
                                { pickFor = p })
                        }
                        if (i == 0) HorizontalDivider()
                    }
                }
            }
        }
    }

    pickFor?.let { p ->
        com.filezen.files.ui.common.FolderPickerSheet(
            startPath = p.path,
            onPick = { picked ->
                p.path = picked
                p.selection.value = emptySet()
                pickFor = null
                scope.launch { load(p) }
            },
            onDismiss = { pickFor = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Pane(p: PaneState, index: Int, active: Boolean,
                 onFocus: () -> Unit, reload: suspend () -> Unit,
                 onSelect: (String) -> Unit, onPickFolder: () -> Unit) {
    val scope = rememberCoroutineScope()
    val parent = File(p.path).parent
    Column(Modifier.fillMaxSize().clickable { onFocus() }) {
        Surface(
            color = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .4f)
                    else MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (parent != null) IconButton(onClick = {
                    p.path = parent; p.selection.value = emptySet()
                    scope.launch { reload() }
                }, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Rounded.ArrowUpward, "Up", modifier = Modifier.size(16.dp))
                }
                Text(p.path, style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onPickFolder, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Rounded.FolderOpen, "Choose folder",
                        modifier = Modifier.size(16.dp))
                }
                if (p.selection.value.isNotEmpty()) {
                    Text("${p.selection.value.size} sel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { p.selection.value = emptySet() }) {
                        Text("Clear", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(p.entries, key = { it.path }) { e ->
                val sel = e.path in p.selection.value
                ListItem(
                    headlineContent = { Text(e.name, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall) },
                    supportingContent = if (e.isDirectory) null else {
                        { Text(formatSize(e.size),
                            style = MaterialTheme.typography.labelSmall) }
                    },
                    leadingContent = {
                        Icon(iconFor(e), null, modifier = Modifier.size(18.dp),
                            tint = tintFor(e))
                    },
                    trailingContent = {
                        if (sel) Icon(Icons.Rounded.CheckCircle, null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary)
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (sel)
                            MaterialTheme.colorScheme.primary.copy(alpha = .12f)
                            else androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier
                        .combinedClickable(
                            onClick = {
                                onFocus()
                                if (e.isDirectory && !sel) {
                                    p.path = e.path
                                    p.selection.value = emptySet()
                                    scope.launch { reload() }
                                } else {
                                    // Files toggle-select on tap — no
                                    // long-press needed for the main flow.
                                    onSelect(e.path)
                                }
                            },
                            onLongClick = {
                                onFocus()
                                onSelect(e.path)
                            },
                        ),
                )
            }
        }
    }
}
