package com.filezen.files.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.filezen.files.Routes
import com.filezen.files.ads.ZenBanner
import com.filezen.files.core.model.*
import com.filezen.files.ui.AppViewModel
import com.filezen.files.ui.HomeViewModel
import com.filezen.files.ui.common.SectionHeader
import com.filezen.files.ui.common.ThumbBox
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavController, appVm: AppViewModel, vm: HomeViewModel = viewModel()) {
    val recent by vm.recent.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val lastPath by vm.lastPath.collectAsState()
    val usage by vm.usage.collectAsState()
    val untidy by vm.untidyCount.collectAsState()
    val adFree by appVm.adFree.collectAsState()

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("FileZen", fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Rounded.Settings, "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            // Search bar
            OutlinedCard(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .clickable { nav.navigate(Routes.SEARCH) },
                shape = RoundedCornerShape(28.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text("Search files…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Inbox nudge
            if (untidy > 0) {
                Spacer(Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clickable { nav.navigate(Routes.INBOX) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Inbox, null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("$untidy new files in Inbox",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("Tap to tidy them up",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f))
                        }
                        Icon(Icons.Rounded.ChevronRight, null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }

            // Recent files
            SectionHeader("Recent")
            if (recent.isEmpty()) {
                Text("No recent files", modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Spacer(Modifier.width(12.dp))
                    recent.take(24).forEach { e ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(88.dp)
                                .clickable {
                                    if (e.isDirectory) nav.navigate(Routes.folder(e.path))
                                    else nav.navigate(Routes.preview(e.path))
                                },
                        ) {
                            ThumbBox(e, 72.dp)
                            Spacer(Modifier.height(4.dp))
                            Text(e.name, style = MaterialTheme.typography.labelSmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }

            // Favourites
            SectionHeader("Favourites") {
                TextButton(onClick = { nav.navigate(Routes.BROWSE) }) { Text("Browse") }
            }
            if (favorites.isEmpty()) {
                Text(
                    "Star folders in Browse for quick tidy-up targets",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth()
                        .height(((favorites.size + 1) / 2 * 84).dp)
                        .padding(horizontal = 12.dp),
                    userScrollEnabled = false,
                ) {
                    items(favorites) { f ->
                        Card(
                            modifier = Modifier.padding(4.dp)
                                .clickable { nav.navigate(Routes.folder(f.path)) },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Star, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(f.label, fontWeight = FontWeight.Medium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(f.path.substringAfterLast('/'),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }

            // Last location
            lastPath?.let { p ->
                if (p.isNotBlank() && File(p).exists()) {
                    SectionHeader("Jump back in")
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            .clickable { nav.navigate(Routes.folder(p)) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.History, null)
                            Spacer(Modifier.width(12.dp))
                            Text(p, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // Storage summary mini-card
            usage?.let { u ->
                SectionHeader("Storage")
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clickable { nav.navigate(Routes.STORAGE) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${formatSize(u.used)} of ${formatSize(u.total)} used",
                            fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (u.used.toFloat() / u.total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            if (!adFree) ZenBanner(Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
}
