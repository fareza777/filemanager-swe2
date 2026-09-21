package com.filezen.files.ui.drivecheck

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
import com.filezen.files.core.f3.DriveCheck
import com.filezen.files.core.model.formatSize
import com.filezen.files.ui.common.FolderPickerSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Fake SD/USB check (F3 port): fill the drive with stamped blocks, read them
 * back, measure real capacity + true speeds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveCheckScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<File?>(null) }
    var picking by remember { mutableStateOf(false) }
    var limitGb by remember { mutableStateOf(2L) }  // 0 = fill all free space
    var progress by remember { mutableStateOf<DriveCheck.Progress?>(null) }
    var report by remember { mutableStateOf<DriveCheck.Report?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var confirm by remember { mutableStateOf(false) }

    fun start() {
        val dir = target ?: return
        job = scope.launch(Dispatchers.IO) {
            progress = DriveCheck.Progress(DriveCheck.Phase.WRITE, 0, 1, 0.0)
            try {
                val r = DriveCheck.run(dir, limitGb * 1024 * 1024 * 1024,
                    onProgress = { p -> progress = p })
                report = r
            } finally {
                progress = null
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Fake drive check", fontWeight = FontWeight.Bold) },
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
                            Icon(Icons.Rounded.SdCard, null,
                                Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text("F3-style drive check",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Detects fake SD cards / USB sticks that claim more capacity than they " +
                                "really have. Writes stamped test data to the drive, reads it all back, " +
                                "and reports real capacity + true write/read speed. Test files are " +
                                "deleted afterwards.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(onClick = { picking = true },
                            enabled = job == null) {
                            Icon(Icons.Rounded.FolderOpen, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(target?.absolutePath ?: "Choose drive/folder…")
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("Test size", style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(1L to "1 GB", 2L to "2 GB", 4L to "4 GB", 0L to "All free").forEach { (gb, label) ->
                                FilterChip(selected = limitGb == gb,
                                    onClick = { limitGb = gb },
                                    label = { Text(label) },
                                    enabled = job == null)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (job == null) {
                            Button(onClick = { confirm = true },
                                enabled = target != null) {
                                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp)); Text("Start check")
                            }
                        } else {
                            OutlinedButton(onClick = { job?.cancel() }) {
                                Icon(Icons.Rounded.Stop, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp)); Text("Stop")
                            }
                        }
                    }
                }
            }

            progress?.let { p ->
                item {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                if (p.phase == DriveCheck.Phase.WRITE) "Writing test data…"
                                else "Verifying…",
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { (p.doneBytes.toFloat() / p.totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${formatSize(p.doneBytes)} / ${formatSize(p.totalBytes)} · ${"%.1f".format(p.mbps)} MB/s",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            report?.let { r ->
                item {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (r.suspicious)
                                MaterialTheme.colorScheme.errorContainer.copy(0.4f)
                            else MaterialTheme.colorScheme.primaryContainer.copy(0.4f))) {
                        Column(Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (r.suspicious) Icons.Rounded.Warning else Icons.Rounded.Verified,
                                    null,
                                    tint = if (r.suspicious) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    when {
                                        r.cancelled -> "Stopped early"
                                        r.suspicious -> "FAKE / corrupted blocks found"
                                        else -> "All data verified OK"
                                    },
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(Modifier.height(10.dp))
                            StatRow("Reported capacity", formatSize(r.claimedTotal))
                            StatRow("Tested & verified", "${formatSize(r.verified)} of ${formatSize(r.written)}")
                            if (r.corrupted > 0)
                                StatRow("Corrupted", formatSize(r.corrupted),
                                    MaterialTheme.colorScheme.error)
                            StatRow("Write speed", "%.1f MB/s".format(r.writeMbps))
                            StatRow("Read speed", "%.1f MB/s".format(r.readMbps))
                            if (r.suspicious) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "This drive does not hold what it claims — data written past the real " +
                                        "capacity is destroyed. Do not trust it for important files.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
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
            onPick = { p -> picking = false; target = File(p) },
            onDismiss = { picking = false })
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Fill drive with test data?") },
            text = {
                Text("FileZen will write ${if (limitGb > 0) "$limitGb GB" else "all free space"} " +
                    "of test files into ${target?.name ?: "the drive"}, verify them, then delete them. " +
                    "Don't unplug the drive during the check.")
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; report = null; start() }) {
                    Text("Start")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Cancel") }
            })
    }
}

@Composable
private fun StatRow(label: String, value: String,
                    color: androidx.compose.ui.graphics.Color =
                        androidx.compose.ui.graphics.Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold, color = color)
    }
}
