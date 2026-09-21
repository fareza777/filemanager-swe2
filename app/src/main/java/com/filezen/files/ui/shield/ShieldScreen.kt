package com.filezen.files.ui.shield

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
import com.filezen.files.core.rs.ShieldFs
import com.filezen.files.ui.common.FilePickerSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Recovery shield — create a .fzrs parity file for a file so corruption can
 * be detected and repaired later (Reed-Solomon, PAR2-style).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShieldScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(0) } // 1 = create shield, 2 = verify/repair a .fzrs
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var check by remember { mutableStateOf<ShieldFs.CheckResult?>(null) }
    var shieldFor by remember { mutableStateOf<File?>(null) }
    var shields by remember { mutableStateOf<List<File>>(emptyList()) }

    fun refreshShields() {
        scope.launch(Dispatchers.IO) {
            val found = File("/storage/emulated/0").walkTopDown()
                .filter { it.isFile && it.name.endsWith(".fzrs") }
                .take(100).toList()
            withContext(Dispatchers.Main) { shields = found }
        }
    }
    LaunchedEffect(Unit) { refreshShields() }

    fun create(f: File) {
        scope.launch(Dispatchers.IO) {
            busy = "Creating shield…"
            try {
                val out = ShieldFs.create(f) { _, _ -> }
                withContext(Dispatchers.Main) {
                    message = "Shield saved as ${out.name} (${formatSize(out.length())})"
                    refreshShields()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { message = "Failed: ${t.message}" }
            } finally { withContext(Dispatchers.Main) { busy = null } }
        }
    }

    fun verify(shield: File) {
        scope.launch(Dispatchers.IO) {
            busy = "Verifying…"
            try {
                val r = ShieldFs.check(shield)
                withContext(Dispatchers.Main) { check = r; shieldFor = shield }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { message = "Bad shield: ${t.message}" }
            } finally { withContext(Dispatchers.Main) { busy = null } }
        }
    }

    fun repair(shield: File) {
        scope.launch(Dispatchers.IO) {
            busy = "Repairing…"
            try {
                val (ok, note) = ShieldFs.repair(shield)
                withContext(Dispatchers.Main) {
                    message = note
                    if (ok) check = ShieldFs.check(shield)
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { message = "Repair failed: ${t.message}" }
            } finally { withContext(Dispatchers.Main) { busy = null } }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Recovery shield", fontWeight = FontWeight.Bold) },
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
                            Icon(Icons.Rounded.Shield, null,
                                Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text("Reed-Solomon recovery",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Creates a small .fzrs parity file next to your file. If the file is " +
                                "later damaged (bad sectors, corrupt copy), the shield detects which " +
                                "blocks broke and rebuilds them — like PAR2.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { picking = 1 }, enabled = busy == null) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp)); Text("Shield a file")
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
                        Text(m, Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            check?.let { c ->
                item {
                    Card(Modifier.fillMaxWidth().padding(16.dp),
                        shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (c.missing) Icons.Rounded.Error
                                    else if (c.ok) Icons.Rounded.CheckCircle
                                    else Icons.Rounded.Warning,
                                    null,
                                    tint = if (c.ok) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    when {
                                        c.missing -> "Original file missing"
                                        c.ok -> "File intact"
                                        else -> "Damage found"
                                    },
                                    fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("${c.file.name} — ${formatSize(c.origSize)}, ${c.stripes} stripes",
                                style = MaterialTheme.typography.bodySmall)
                            if (!c.ok && !c.missing) {
                                Text("${c.badBlocks} bad block(s) in ${c.badStripes} stripe(s)" +
                                    if (c.repairable) " — repairable" else " — exceeds parity",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                                if (c.repairable) {
                                    Spacer(Modifier.height(10.dp))
                                    Button(onClick = { shieldFor?.let { repair(it) } },
                                        enabled = busy == null) {
                                        Text("Repair file")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (shields.isNotEmpty()) {
                item {
                    Text("Shields on this device", Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                items(shields, key = { it.absolutePath }) { s ->
                    ListItem(
                        headlineContent = { Text(s.name.removeSuffix(".fzrs"), maxLines = 1) },
                        supportingContent = {
                            Text("${s.parentFile?.parent}${'/'} — shield ${formatSize(s.length())}",
                                style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        },
                        leadingContent = {
                            Icon(Icons.Rounded.Shield, null,
                                tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingContent = {
                            TextButton(onClick = { verify(s) }, enabled = busy == null) {
                                Text("Verify")
                            }
                        },
                        modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (picking == 1) FilePickerSheet(
        onPick = { p -> picking = 0; create(File(p)) },
        onDismiss = { picking = 0 })
    else if (picking == 2) FilePickerSheet(
        onPick = { p -> picking = 0; verify(File(p)) },
        onDismiss = { picking = 0 })
}
