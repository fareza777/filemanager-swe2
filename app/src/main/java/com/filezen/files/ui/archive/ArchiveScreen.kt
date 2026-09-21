package com.filezen.files.ui.archive

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.filezen.files.FileZenApp
import com.filezen.files.Routes
import com.filezen.files.core.archive.ArchiveFs
import com.filezen.files.core.fileops.ConflictPolicy
import com.filezen.files.core.model.FileEntry
import com.filezen.files.ui.common.EmptyState
import com.filezen.files.ui.common.FileRow
import com.filezen.files.ui.common.SkeletonRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Archive-as-folder (Twig-style): browse a ZIP/TAR/TGZ like a directory —
 * enter folders inside it, extract single files or the whole thing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(nav: NavController, archivePath: String, innerPath: String) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val archive = remember(archivePath) { File(archivePath) }

    fun load(prefix: String) {
        scope.launch(Dispatchers.IO) {
            loading = true
            error = null
            try { entries = ArchiveFs.list(archive, prefix).map { ArchiveFs.toFileEntry(it) } }
            catch (e: Exception) { error = e.message; entries = emptyList() }
            loading = false
        }
    }
    LaunchedEffect(innerPath) { load(innerPath) }

    val extractDir = remember {
        File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS), "FileZen Extracted")
    }

    fun extractOne(e: FileEntry) {
        scope.launch(Dispatchers.IO) {
            busy = "Extracting ${e.name}"
            try {
                extractDir.mkdirs()
                val out = File(extractDir, e.name)
                ArchiveFs.open(archive, e.path)?.use { ins ->
                    out.outputStream().use { ins.copyTo(it, 128 * 1024) }
                }
            } finally { busy = null }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(archive.name, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Archive · /$innerPath",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(
                        enabled = busy == null,
                        onClick = {
                            busy = "Extracting all…"
                            scope.launch(Dispatchers.IO) {
                                try {
                                    com.filezen.files.core.fileops.ZipEngine(
                                        FileZenApp.c.fileEngine)
                                        .extract(archive, extractDir,
                                            ConflictPolicy.KEEP_BOTH) {}
                                } finally { busy = null }
                            }
                        }) {
                        Text(if (busy != null) "Working…" else "Extract all")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .5f)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FolderZip, null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Text("Browsing inside the archive — nothing is extracted until you ask.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            if (innerPath.isNotBlank()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.popBackStack() }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.ArrowUpward, "Up", modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("/$innerPath", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            error?.let {
                Text("Error: $it", Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            when {
                loading -> Column(Modifier.padding(horizontal = 10.dp)) { repeat(8) { SkeletonRow() } }
                entries.isEmpty() -> EmptyState(Icons.Rounded.FolderZip, "Empty", "Nothing at this level.")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.path }) { e ->
                        FileRow(
                            e = e,
                            selected = false,
                            onClick = {
                                if (e.isDirectory) {
                                    nav.navigate(Routes.archive(archivePath, e.path))
                                } else extractOne(e)
                            },
                            onLongClick = {},
                            trailing = {
                                if (!e.isDirectory) {
                                    IconButton(onClick = { extractOne(e) }) {
                                        Icon(Icons.Rounded.Output, "Extract file",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            },
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}
