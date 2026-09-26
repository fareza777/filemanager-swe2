package com.filezen.files.ui.storage

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.filezen.files.FileZenApp
import com.filezen.files.Routes
import com.filezen.files.core.model.formatSize
import com.filezen.files.core.scan.AppStorageAnalyzer
import com.filezen.files.core.scan.AppStorageEntry
import com.filezen.files.core.shizuku.ShizukuAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class AppSort { TOTAL, APP, DATA, CACHE, NAME }

class AppsDataViewModel : ViewModel() {
    private val _granted = MutableStateFlow<Boolean?>(null)
    val granted: StateFlow<Boolean?> = _granted
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _all = MutableStateFlow<List<AppStorageEntry>>(emptyList())
    val query = MutableStateFlow("")
    val sort = MutableStateFlow(AppSort.TOTAL)

    val filtered: StateFlow<List<AppStorageEntry>> =
        MutableStateFlow<List<AppStorageEntry>>(emptyList()).also { out ->
            viewModelScope.launch {
                kotlinx.coroutines.flow.combine(_all, query, sort) { all, q, s ->
                    val f = if (q.isBlank()) all else all.filter {
                        it.label.contains(q, true) || it.packageName.contains(q, true)
                    }
                    when (s) {
                        AppSort.TOTAL -> f.sortedByDescending { it.totalBytes }
                        AppSort.APP -> f.sortedByDescending { it.appBytes }
                        AppSort.DATA -> f.sortedByDescending { it.dataBytes }
                        AppSort.CACHE -> f.sortedByDescending { it.cacheBytes }
                        AppSort.NAME -> f.sortedBy { it.label.lowercase() }
                    }
                }.collect { out.value = it }
            }
        }

    fun load() {
        val ctx = FileZenApp.instance
        val ok = AppStorageAnalyzer.hasUsageAccess(ctx)
        _granted.value = ok
        if (!ok) { _all.value = emptyList(); return }
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            try { _all.value = AppStorageAnalyzer.perApp(ctx) }
            finally { _loading.value = false }
        }
    }

    /** Clear app data via Shizuku (shell `pm clear`). Returns true on success. */
    suspend fun clearAppData(pkg: String): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val sh = FileZenApp.c.shizuku
            if (!sh.ready()) return@withContext false
            sh.execShell("pm clear $pkg").code == 0
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsDataScreen(nav: NavController, vm: AppsDataViewModel = viewModel()) {
    val granted by vm.granted.collectAsState()
    val loading by vm.loading.collectAsState()
    val apps by vm.filtered.collectAsState()
    val query by vm.query.collectAsState()
    val sort by vm.sort.collectAsState()
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var detail by remember { mutableStateOf<AppStorageEntry?>(null) }
    var confirmClear by remember { mutableStateOf<AppStorageEntry?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var clearMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.load() }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) vm.load()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Apps & data", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (granted == true)
                        IconButton(onClick = { vm.load() }) {
                            Icon(Icons.Rounded.Refresh, "Reload")
                        }
                },
            )
        },
    ) { padding ->
        when {
            granted == false -> Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.Shield, null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text("Usage Access needed", fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Allow Usage Access to analyse app storage.\n" +
                    "Find FileZen in the list and enable it — no data leaves your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                Button(onClick = { ctx.startActivity(AppStorageAnalyzer.usageAccessIntent()) }) {
                    Text("Allow Usage Access")
                }
            }
            apps.isEmpty() && loading -> Box(Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Measuring every app…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                // Filter + sort controls
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    placeholder = { Text("Filter apps…") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty())
                            IconButton(onClick = { vm.query.value = "" }) {
                                Icon(Icons.Rounded.Close, "Clear")
                            }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Sort, null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    AppSort.entries.forEach { s ->
                        FilterChip(
                            selected = sort == s,
                            onClick = { vm.sort.value = s },
                            label = { Text(when (s) {
                                AppSort.TOTAL -> "Total"
                                AppSort.APP -> "App size"
                                AppSort.DATA -> "Data"
                                AppSort.CACHE -> "Cache"
                                AppSort.NAME -> "Name"
                            }, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            "${apps.size} apps · ${formatSize(apps.sumOf { it.totalBytes })}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    items(apps, key = { it.packageName }) { a ->
                        AppStorageRow(a) { detail = a }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    // ---- per-app detail sheet ----
    detail?.let { a ->
        val shizukuReady = FileZenApp.c.shizuku.status.collectAsState().value ==
            ShizukuAccess.Status.READY
        AlertDialog(
            onDismissRequest = { detail = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.label, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            text = {
                Column {
                    Text(a.packageName, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text("Total ${formatSize(a.totalBytes)}",
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    // Stacked split bar
                    val tot = a.totalBytes.coerceAtLeast(1)
                    Row(Modifier.fillMaxWidth().height(10.dp)
                        .clip(RoundedCornerShape(5.dp))) {
                        if (a.appBytes > 0) Box(Modifier.weight(
                            (a.appBytes.toFloat() / tot).coerceAtLeast(0.01f))
                            .fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                        if (a.dataBytes > 0) Box(Modifier.weight(
                            (a.dataBytes.toFloat() / tot).coerceAtLeast(0.01f))
                            .fillMaxHeight().background(MaterialTheme.colorScheme.tertiary))
                        if (a.cacheBytes > 0) Box(Modifier.weight(
                            (a.cacheBytes.toFloat() / tot).coerceAtLeast(0.01f))
                            .fillMaxHeight().background(MaterialTheme.colorScheme.error))
                    }
                    Spacer(Modifier.height(10.dp))
                    SplitLine("App", a.appBytes, MaterialTheme.colorScheme.primary)
                    SplitLine("Data", a.dataBytes, MaterialTheme.colorScheme.tertiary)
                    SplitLine("Cache", a.cacheBytes, MaterialTheme.colorScheme.error)
                    if (a.isSystem) {
                        Spacer(Modifier.height(8.dp))
                        Text("System app — cannot be uninstalled",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    clearMsg?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                Column(Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    a.extDataPath?.let { p ->
                        OutlinedButton(
                            onClick = {
                                detail = null
                                nav.navigate(Routes.folder(p))
                            },
                            modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.FolderOpen, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Browse app files")
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            ctx.startActivity(Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${a.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                        modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Settings, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("App settings (clear cache/data)")
                    }
                    if (shizukuReady && !a.isSystem) {
                        OutlinedButton(
                            onClick = { confirmClear = a },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error)) {
                            Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (clearing) "Clearing…" else "Clear app data")
                        }
                    }
                    if (!a.isSystem) {
                        OutlinedButton(
                            onClick = {
                                ctx.startActivity(Intent(
                                    Intent.ACTION_UNINSTALL_PACKAGE,
                                    Uri.parse("package:${a.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error)) {
                            Icon(Icons.Rounded.Delete, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Uninstall")
                        }
                    }
                    TextButton(onClick = { detail = null; clearMsg = null },
                        modifier = Modifier.fillMaxWidth()) { Text("Close") }
                }
            },
        )
    }

    // ---- confirm clear app data ----
    confirmClear?.let { a ->
        AlertDialog(
            onDismissRequest = { confirmClear = null },
            title = { Text("Clear app data?") },
            text = { Text("All of ${a.label}'s accounts, settings and private files " +
                "will be deleted (${formatSize(a.dataBytes + a.cacheBytes)}). " +
                "This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        clearing = true
                        val ok = vm.clearAppData(a.packageName)
                        clearing = false
                        confirmClear = null
                        clearMsg = if (ok) "App data cleared" else "Failed to clear"
                        if (ok) vm.load()
                    }
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SplitLine(label: String, bytes: Long, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(formatSize(bytes), style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AppStorageRow(a: AppStorageEntry, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, a.packageName) {
        value = runCatching {
            ctx.packageManager.getApplicationIcon(a.packageName)
                .toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
    }
    ListItem(
        headlineContent = { Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(a.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(3.dp))
                Text("app ${formatSize(a.appBytes)} · data ${formatSize(a.dataBytes)}" +
                    if (a.cacheBytes > 0) " · cache ${formatSize(a.cacheBytes)}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingContent = {
            if (icon != null)
                Image(icon!!, null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
            else
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Apps, null, tint = MaterialTheme.colorScheme.primary)
                }
        },
        trailingContent = { Text(formatSize(a.totalBytes), fontWeight = FontWeight.Medium) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
