package com.filezen.files.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
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

    // One-shot entrance: sections fade + rise in together.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val enter = fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 12 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("FileZen", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary)
                },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Rounded.Settings, "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            // Search bar
            AnimatedVisibility(entered, enter = enter) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .clickable { nav.navigate(Routes.SEARCH) },
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Text("Search files…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            }

            // Inbox nudge — hero card with brand gradient
            if (untidy > 0) {
                Spacer(Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clickable { nav.navigate(Routes.INBOX) },
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
                                            .compositeOver(MaterialTheme.colorScheme.primary)
                                    )
                                )
                            )
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color.White.copy(alpha = 0.18f),
                            modifier = Modifier.size(46.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Inbox, null, tint = Color.White)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("$untidy new files in Inbox",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White)
                            Text("Tap to tidy them up — or auto-tidy them all",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = .85f))
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = Color.White)
                    }
                }
            }

            // Recent files
            SectionHeader("Recent") {
                TextButton(onClick = { nav.navigate(Routes.RECENT) }) { Text("See all") }
            }
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
                            ThumbBox(e, 76.dp)
                            Spacer(Modifier.height(6.dp))
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
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Rounded.Star, null,
                                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.size(18.dp))
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
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
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.size(34.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.History, null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(18.dp))
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(p, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // Storage summary mini-card — bar animates to its fullness
            usage?.let { u ->
                val fraction = (u.used.toFloat() / u.total.coerceAtLeast(1)).coerceIn(0f, 1f)
                val animated by animateFloatAsState(fraction, tween(900), label = "storage")
                SectionHeader("Storage")
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clickable { nav.navigate(Routes.STORAGE) },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(58.dp)) {
                            CircularProgressIndicator(
                                progress = { animated },
                                modifier = Modifier.size(58.dp),
                                strokeWidth = 6.dp,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                            Text("${(fraction * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("${formatSize(u.used)} of ${formatSize(u.total)} used",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium)
                            Text("Tap for categories, large files & trash",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            if (!adFree) ZenBanner(Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
}
