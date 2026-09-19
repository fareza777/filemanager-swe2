package com.filezen.files.ui.storage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.filezen.files.Routes
import com.filezen.files.ads.ZenBanner
import com.filezen.files.core.model.formatDate
import com.filezen.files.core.model.formatSize
import com.filezen.files.ui.AppViewModel
import com.filezen.files.ui.StorageViewModel
import com.filezen.files.ui.common.*
import com.filezen.files.ui.common.iconFor
import com.filezen.files.ui.common.tintFor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(nav: NavController, appVm: AppViewModel, vm: StorageViewModel = viewModel()) {
    val usage by vm.usage.collectAsState()
    val categories by vm.categories.collectAsState()
    val large by vm.large.collectAsState()
    val dups by vm.dups.collectAsState()
    val analyzing by vm.analyzing.collectAsState()
    val trashCount by vm.trashEntries.collectAsState()
    val trashSize by vm.trashSize.collectAsState()
    val adFree by appVm.adFree.collectAsState()

    LaunchedEffect(Unit) { vm.analyze() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { vm.analyze() }) { Icon(Icons.Rounded.Refresh, "Re-analyse") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {

            usage?.let { u ->
                Card(Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Internal storage", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { (u.used.toFloat() / u.total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${formatSize(u.used)} used", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${formatSize(u.free)} free", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (analyzing) {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Analysing storage…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Categories
            if (categories.isNotEmpty()) {
                SectionHeader("By type")
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        categories.take(8).forEach { c ->
                            ListItem(
                                headlineContent = { Text(c.type.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                supportingContent = { Text("${c.count} files") },
                                leadingContent = {
                                    Icon(iconFor(com.filezen.files.core.model.FileEntry("", "", false, 0, 0, c.type)),
                                        null, tint = tintFor(com.filezen.files.core.model.FileEntry("", "", false, 0, 0, c.type)))
                                },
                                trailingContent = { Text(formatSize(c.bytes), fontWeight = FontWeight.Medium) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                }
            }

            // Large files
            if (large.isNotEmpty()) {
                SectionHeader("Largest files")
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column {
                        large.take(10).forEach { lf ->
                            ListItem(
                                headlineContent = { Text(lf.entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(lf.entry.path, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall) },
                                leadingContent = { ThumbBox(lf.entry, 40.dp) },
                                trailingContent = { Text(formatSize(lf.entry.size), fontWeight = FontWeight.Medium) },
                                modifier = Modifier.clickable { nav.navigate(Routes.preview(lf.entry.path)) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                }
            }

            // Duplicates
            if (dups.isNotEmpty()) {
                SectionHeader("Duplicates (${dups.size} groups · ${formatSize(dups.sumOf { it.wasted })} wasted)")
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column {
                        dups.take(6).forEach { g ->
                            ListItem(
                                headlineContent = {
                                    Text("${g.files.size}× ${g.files.first().name}",
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = { Text("identical · ${formatSize(g.files.first().size)} each") },
                                leadingContent = { ThumbBox(g.files.first(), 40.dp) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                }
            }

            // Tools
            SectionHeader("Tools")
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column {
                    ListItem(
                        headlineContent = { Text("Trash") },
                        supportingContent = { Text("${trashCount.size} items · ${formatSize(trashSize)}") },
                        leadingContent = { Icon(Icons.Rounded.Delete, null) },
                        trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
                        modifier = Modifier.clickable { nav.navigate(Routes.TRASH) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    ListItem(
                        headlineContent = { Text("Sort rules") },
                        supportingContent = { Text("Auto-tidy files by name or extension") },
                        leadingContent = { Icon(Icons.Rounded.RuleFolder, null) },
                        trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
                        modifier = Modifier.clickable { nav.navigate(Routes.RULES) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    ListItem(
                        headlineContent = { Text("Operation history") },
                        supportingContent = { Text("Success / failure log of file operations") },
                        leadingContent = { Icon(Icons.Rounded.History, null) },
                        trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
                        modifier = Modifier.clickable { nav.navigate(Routes.HISTORY) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            if (!adFree) ZenBanner(Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(32.dp))
        }
    }
}
