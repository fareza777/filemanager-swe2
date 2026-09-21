package com.filezen.files.ui.zstd

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.filezen.files.core.model.formatSize
import com.filezen.files.core.zst.SeekableZstd
import com.filezen.files.ui.common.FilePickerSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Seekable zstd archive — compress a file to .zst (frame-per-block + seek
 * table, the official zstd seekable format) and decompress it back
 * byte-identical.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZstdScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(0) } // 1 compress, 2 decompress
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var last by remember { mutableStateOf<SeekableZstd.Result?>(null) }

    fun compress(f: File) {
        scope.launch(Dispatchers.IO) {
            busy = true; message = null
            try {
                val r = SeekableZstd.compress(f)
                withContext(Dispatchers.Main) {
                    last = r
                    message = "Compressed → ${r.out.name}"
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { message = "Failed: ${t.message}" }
            } finally { withContext(Dispatchers.Main) { busy = false } }
        }
    }

    fun decompress(f: File) {
        scope.launch(Dispatchers.IO) {
            busy = true; message = null
            try {
                val out = SeekableZstd.decompress(f)
                withContext(Dispatchers.Main) { message = "Decompressed → ${out.name} (${formatSize(out.length())})" }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { message = "Failed: ${t.message}" }
            } finally { withContext(Dispatchers.Main) { busy = false } }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Zstd archive", fontWeight = FontWeight.SemiBold) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, null) } }) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Seekable compressed archive", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Zstandard with a per-block seek table — the archive is made of independent frames so any part can be extracted without decompressing the whole file. Standard zstd tools can read it too.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { picking = 1 }, enabled = !busy) {
                            Icon(Icons.Rounded.Compress, null); Spacer(Modifier.width(6.dp)); Text("Compress")
                        }
                        OutlinedButton(onClick = { picking = 2 }, enabled = !busy) {
                            Icon(Icons.Rounded.Unarchive, null); Spacer(Modifier.width(6.dp)); Text("Decompress .zst")
                        }
                    }
                }
            }
            last?.let { r ->
                Spacer(Modifier.height(12.dp))
                Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row {
                            AssistChip(onClick = {}, label = { Text("${formatSize(r.origSize)} → ${formatSize(r.compressedSize)}") })
                            Spacer(Modifier.width(8.dp))
                            AssistChip(onClick = {}, label = { Text("${r.frames} frames") })
                            Spacer(Modifier.width(8.dp))
                            val ratio = if (r.origSize > 0) (100 - r.compressedSize * 100 / r.origSize) else 0
                            AssistChip(onClick = {}, label = { Text("-$ratio%") })
                        }
                    }
                }
            }
            if (busy) { Spacer(Modifier.height(12.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            message?.let { Spacer(Modifier.height(12.dp)); Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }

    if (picking == 1) {
        FilePickerSheet(onPick = { p -> picking = 0; compress(File(p)) }, onDismiss = { picking = 0 })
    }
    if (picking == 2) {
        FilePickerSheet(onPick = { p -> picking = 0; decompress(File(p)) }, onDismiss = { picking = 0 })
    }
}
