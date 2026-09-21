package com.filezen.files.ui.timemachine

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
import com.filezen.files.core.vcd.TimeMachine
import com.filezen.files.ui.common.FilePickerSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File time machine — snapshot versions of a file, stored as a space-saving
 * VCDIFF delta chain; restore any earlier version on demand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeMachineScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var file by remember { mutableStateOf<File?>(null) }
    var store by remember { mutableStateOf<TimeMachine.Store?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    fun loadStore() {
        val f = file ?: return
        scope.launch(Dispatchers.IO) {
            val s = TimeMachine.storeFor(f)
            withContext(Dispatchers.Main) { store = s }
        }
    }

    fun snapshot() {
        val f = file ?: return
        scope.launch(Dispatchers.IO) {
            busy = "Snapshotting…"
            try {
                val v = TimeMachine.snapshot(f)
                withContext(Dispatchers.Main) {
                    message = "Version ${v.n} saved (${formatSize(v.size)})"
                    loadStore()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { message = "Failed: ${t.message}" }
            } finally { withContext(Dispatchers.Main) { busy = null } }
        }
    }

    fun restore(n: Int) {
        val s = store ?: return
        scope.launch(Dispatchers.IO) {
            busy = "Restoring…"
            try {
                val out = TimeMachine.restore(s, n)
                withContext(Dispatchers.Main) {
                    message = "Restored as ${out.name}"
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { message = "Restore failed: ${t.message}" }
            } finally { withContext(Dispatchers.Main) { busy = null } }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("File time machine", fontWeight = FontWeight.Bold) },
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
                            Icon(Icons.Rounded.History, null,
                                Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text("Delta versions",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Keeps a version history for a file. Each snapshot stores only the " +
                                "changes since the previous version (VCDIFF delta), so history " +
                                "stays small. Restore any version as a copy whenever you need it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { picking = true }, enabled = busy == null) {
                            Icon(Icons.Rounded.InsertDriveFile, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp)); Text("Choose file")
                        }
                        file?.let {
                            Text(it.name, Modifier.padding(top = 10.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold)
                            Text(it.absolutePath, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { snapshot() }, enabled = busy == null) {
                                Icon(Icons.Rounded.Save, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Save current as new version")
                            }
                        }
                    }
                }
            }

            busy?.let { b ->
                item {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(b, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            message?.let { m ->
                item {
                    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(m, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            store?.let { s ->
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${s.versions.size} version(s) — store uses ${formatSize(TimeMachine.storeBytes(s))}",
                            Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = {
                            file?.let { TimeMachine.deleteStore(it) }; store = null
                        }) { Text("Delete history") }
                    }
                }
                items(s.versions.reversed(), key = { it.n }) { v ->
                    ListItem(
                        headlineContent = { Text("v${v.n} — ${formatSize(v.size)}") },
                        supportingContent = {
                            Text(fmt.format(Date(v.time)) +
                                if (v.note.isNotBlank()) " · ${v.note}" else "",
                                style = MaterialTheme.typography.bodySmall)
                        },
                        leadingContent = {
                            Icon(Icons.Rounded.History, null,
                                tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingContent = {
                            TextButton(onClick = { restore(v.n) }, enabled = busy == null) {
                                Text("Restore")
                            }
                        },
                        modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (picking) FilePickerSheet(
        onPick = { p ->
            picking = false
            file = File(p)
            message = null; store = null
            loadStore()
        },
        onDismiss = { picking = false })
}
