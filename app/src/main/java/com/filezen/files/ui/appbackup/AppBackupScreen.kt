package com.filezen.files.ui.appbackup

import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation.NavController
import com.filezen.files.core.apkbackup.ApkBackup
import com.filezen.files.core.model.formatSize
import com.filezen.files.ui.common.FolderPickerSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * App backup — list installed apps and export their APK(s) (base + splits)
 * to a folder of choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBackupScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<ApkBackup.App>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<ApkBackup.App?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        busy = "Loading installed apps…"
        apps = withContext(Dispatchers.IO) { runCatching { ApkBackup.listApps(ctx) }.getOrDefault(emptyList()) }
        busy = null
    }

    val destDefault = File("/storage/emulated/0/Documents/AppBackups")

    fun backup(app: ApkBackup.App, dest: File) {
        scope.launch(Dispatchers.IO) {
            busy = "Exporting ${app.label}…"
            try {
                val out = ApkBackup.backup(app, dest)
                withContext(Dispatchers.Main) { message = "Saved ${out.size} file(s) to ${dest.absolutePath}" }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { message = "Failed: ${t.message}" }
            } finally { withContext(Dispatchers.Main) { busy = null } }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("App backup", fontWeight = FontWeight.SemiBold) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, null) } }) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Card(Modifier.padding(16.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Export APKs", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Back up installed apps as installable .apk files (base + splits). Pick a destination folder; defaults to Documents/AppBackups.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search apps") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        singleLine = true,
                    )
                }
            }
            if (busy != null) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)); Spacer(Modifier.height(4.dp)); Text(busy!!, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
            message?.let { Text(it, modifier = Modifier.padding(16.dp, 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            val shown = apps.orEmpty().filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown) { app ->
                    ListItem(
                        leadingContent = {
                            app.icon?.let { Image(it.toBitmap(40, 40).asImageBitmap(), null, modifier = Modifier.size(40.dp)) }
                                ?: Icon(Icons.Rounded.Android, null)
                        },
                        headlineContent = { Text(app.label, maxLines = 1) },
                        supportingContent = { Text("${app.packageName} · v${app.version} · ${formatSize(app.apkSize)}${if (app.splitDirs.isNotEmpty()) " · ${app.splitDirs.size + 1} APKs" else ""}", maxLines = 1, style = MaterialTheme.typography.bodySmall) },
                        trailingContent = {
                            TextButton(onClick = { picking = app }) { Text("Export") }
                        },
                    )
                }
            }
        }
    }

    picking?.let { app ->
        FolderPickerSheet(
            onPick = { p -> picking = null; backup(app, File(p)) },
            onDismiss = { picking = null },
        )
    }
}
