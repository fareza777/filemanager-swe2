package com.filezen.files.ui.storage

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.filezen.files.core.model.formatSize
import com.filezen.files.core.scan.AppStorageAnalyzer
import com.filezen.files.core.scan.AppStorageEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AppsDataViewModel : ViewModel() {
    private val _granted = MutableStateFlow<Boolean?>(null)
    val granted: StateFlow<Boolean?> = _granted
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _apps = MutableStateFlow<List<AppStorageEntry>>(emptyList())
    val apps: StateFlow<List<AppStorageEntry>> = _apps

    fun load() {
        val ctx = FileZenApp.instance
        val ok = AppStorageAnalyzer.hasUsageAccess(ctx)
        _granted.value = ok
        if (!ok) { _apps.value = emptyList(); return }
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            try { _apps.value = AppStorageAnalyzer.perApp(ctx) }
            finally { _loading.value = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsDataScreen(nav: NavController, vm: AppsDataViewModel = viewModel()) {
    val granted by vm.granted.collectAsState()
    val loading by vm.loading.collectAsState()
    val apps by vm.apps.collectAsState()
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

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
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item {
                    Text(
                        "${apps.size} apps · ${formatSize(apps.sumOf { it.totalBytes })}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                items(apps, key = { it.packageName }) { a ->
                    AppStorageRow(a)
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun AppStorageRow(a: AppStorageEntry) {
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
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
}
