package com.filezen.files.ui.typecheck

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
import com.filezen.files.core.model.formatSize
import com.filezen.files.core.sig.SigDetect
import com.filezen.files.ui.common.FilePickerSheet
import com.filezen.files.ui.common.FolderPickerSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * True file-type check (Siegfried-style): identify files by magic bytes and
 * flag extensions that lie about the real content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypeCheckScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(0) } // 0 none, 1 file, 2 folder
    var scanning by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SigDetect.Result>>(emptyList()) }
    var scannedLabel by remember { mutableStateOf<String?>(null) }

    fun scan(target: File) {
        scope.launch(Dispatchers.IO) {
            scanning = true
            try {
                val files = if (target.isDirectory)
                    target.walkTopDown().filter { it.isFile && !it.isHidden }
                        .take(500).toList()
                else listOf(target)
                val rs = files.map { SigDetect.detect(it) }
                withContext(Dispatchers.Main) {
                    results = rs
                    scannedLabel = target.name
                }
            } finally {
                withContext(Dispatchers.Main) { scanning = false }
            }
        }
    }

    val mismatches = remember(results) { results.count { !it.matches } }

    Scaffold(topBar = {
        TopAppBar(title = { Text("True type check", fontWeight = FontWeight.Bold) },
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
                            Icon(Icons.Rounded.FactCheck, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Siegfried-style type check",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Identifies files by their magic bytes — catches mislabeled or disguised " +
                                "files (e.g. a file named photo.jpg that's really an EXE or a PDF).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { picking = 1 }, enabled = !scanning) {
                                Icon(Icons.Rounded.InsertDriveFile, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp)); Text("Check a file")
                            }
                            OutlinedButton(onClick = { picking = 2 }, enabled = !scanning) {
                                Icon(Icons.Rounded.FolderOpen, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp)); Text("Scan a folder")
                            }
                        }
                    }
                }
            }

            if (scanning) {
                item {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Scanning…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (!scanning && scannedLabel != null) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(scannedLabel!!, fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f))
                        if (mismatches > 0) {
                            Surface(color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(8.dp)) {
                                Text("$mismatches mismatch${if (mismatches > 1) "es" else ""}",
                                    Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        } else {
                            Surface(color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(8.dp)) {
                                Text("all honest", Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }
                items(results, key = { it.path }) { r ->
                    ListItem(
                        headlineContent = {
                            Text(File(r.path).name, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                if (r.detected == null) "Unknown content"
                                else r.detected + (r.mime?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (r.matches) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.error)
                        },
                        leadingContent = {
                            Icon(
                                if (r.matches) Icons.Rounded.CheckCircle
                                else Icons.Rounded.Warning,
                                null,
                                tint = if (r.matches) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error)
                        },
                        trailingContent = {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(".${r.ext.ifEmpty { "—" }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold)
                                Text(formatSize(File(r.path).length()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    when (picking) {
        1 -> FilePickerSheet(
            onPick = { p -> picking = 0; scan(File(p)) },
            onDismiss = { picking = 0 })
        2 -> FolderPickerSheet(
            onPick = { p -> picking = 0; scan(File(p)) },
            onDismiss = { picking = 0 })
    }
}
