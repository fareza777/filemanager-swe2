package com.filezen.files.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.filezen.files.core.scan.StorageUsage
import com.filezen.files.ui.AppViewModel
import com.filezen.files.ui.HomeViewModel
import com.filezen.files.ui.common.SectionHeader
import com.filezen.files.ui.common.ThumbBox
import java.io.File
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavController, appVm: AppViewModel,
               vm: HomeViewModel = viewModel()) {
    val recent by appVm.recent.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val lastPath by vm.lastPath.collectAsState()
    val usage by vm.usage.collectAsState()
    val untidy by vm.untidyCount.collectAsState()
    val trashSize by vm.trashSize.collectAsState()
    val dupWasted by vm.dupWasted.collectAsState()
    val adFree by appVm.adFree.collectAsState()
    val qaPref by com.filezen.files.FileZenApp.c.settings.quickActions
        .collectAsState(initial = null)
    var qaEdit by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val qaShown = QaCatalog.resolve(qaPref)

    LaunchedEffect(Unit) { vm.refresh(); appVm.refreshRecent() }

    // Staggered entrance: each section fades + rises in turn.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    fun enterIn(delay: Int) =
        fadeIn(tween(400, delayMillis = delay)) +
            slideInVertically(tween(400, delayMillis = delay)) { it / 8 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(30.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    androidx.compose.ui.graphics.Brush.linearGradient(
                                        listOf(MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.tertiary)
                                    )
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Bolt, null, tint = Color.White,
                                modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("FileZen", style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary)
                    }
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
            AnimatedVisibility(entered, enter = enterIn(0)) {
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
                    Text(stringResource(com.filezen.files.R.string.search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            }

            // Storage ring — hero gauge, animated fill + count-up
            usage?.let { u ->
                Spacer(Modifier.height(14.dp))
                AnimatedVisibility(entered, enter = enterIn(80)) {
                StorageRingCard(u) { nav.navigate(Routes.STORAGE) }
                }
            }

            // Quick access — flagship tools one tap from Home
            Spacer(Modifier.height(14.dp))
            AnimatedVisibility(entered, enter = enterIn(110)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                qaShown.forEach { qa ->
                    Card(
                        Modifier.weight(1f).clickable { nav.navigate(qa.route) },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 13.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(11.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                                modifier = Modifier.size(36.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(qa.icon, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(19.dp))
                                }
                            }
                            Spacer(Modifier.height(7.dp))
                            Text(qa.label, style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                // Edit tile — pick which tools sit here
                Card(
                    Modifier.weight(1f).clickable { qaEdit = true },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Transparent),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 13.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(11.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Edit, null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(19.dp))
                            }
                        }
                        Spacer(Modifier.height(7.dp))
                        Text("Edit", style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            }

            // Optimise — quick reclaim card (trash + identical duplicates)
            Spacer(Modifier.height(14.dp))
            AnimatedVisibility(entered, enter = enterIn(140)) {
            OptimiseCard(
                reclaimable = trashSize + dupWasted,
                onClick = { nav.navigate(Routes.OPTIMISE) },
            )
            }

            // Inbox nudge — hero card with brand gradient
            if (untidy > 0) {
                Spacer(Modifier.height(16.dp))
                AnimatedVisibility(entered, enter = enterIn(160)) {
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
            }

            // Recent files
            AnimatedVisibility(entered, enter = enterIn(240)) {
            Column {
            SectionHeader("Recent") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { appVm.refreshRecent() },
                        modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Rounded.Refresh, "Refresh recent files",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = { nav.navigate(Routes.RECENT) }) { Text("See all") }
                }
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
            }
            }

            // Favourites
            AnimatedVisibility(entered, enter = enterIn(320)) {
            Column {
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

            }
            }

            // Last location
            AnimatedVisibility(entered, enter = enterIn(400)) {
            Column {
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

            }
            }

            Spacer(Modifier.height(16.dp))
            if (!adFree) ZenBanner(Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(24.dp))
        }
    }

    if (qaEdit) {
        var picked by remember(qaEdit) {
            mutableStateOf(qaShown.map { it.id }.toSet())
        }
        AlertDialog(
            onDismissRequest = { qaEdit = false },
            title = { Text("Quick access cards", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Pick up to ${QaCatalog.MAX} tools to pin on Home.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.lazy.LazyColumn(
                        Modifier.height(340.dp)) {
                        items(QaCatalog.all.size) { i ->
                            val qa = QaCatalog.all[i]
                            val on = qa.id in picked
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(enabled = on || picked.size < QaCatalog.MAX) {
                                        picked = if (on) picked - qa.id else picked + qa.id
                                    }
                                    .padding(vertical = 4.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = on,
                                    onCheckedChange = {
                                        picked = if (on) picked - qa.id
                                        else if (picked.size < QaCatalog.MAX) picked + qa.id
                                        else picked
                                    })
                                Icon(qa.icon, null, Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Text(qa.label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = picked.isNotEmpty(),
                    onClick = {
                        qaEdit = false
                        scope.launch {
                            com.filezen.files.FileZenApp.c.settings
                                .setQuickActions(QaCatalog.all.filter { it.id in picked }
                                    .map { it.id })
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    qaEdit = false
                    scope.launch {
                        com.filezen.files.FileZenApp.c.settings
                            .setQuickActions(QaCatalog.DEFAULT_IDS)
                    }
                }) { Text("Reset") }
            },
        )
    }
}

/** Optimise card — shows reclaimable space (trash + duplicates), opens Storage. */
@Composable
private fun OptimiseCard(reclaimable: Long, onClick: () -> Unit) {
    // Same gradient wash as the storage card above it — sibling, same family.
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(
                            primary.copy(alpha = 0.16f).compositeOver(
                                MaterialTheme.colorScheme.surfaceContainerLow),
                            tertiary.copy(alpha = 0.14f).compositeOver(
                                MaterialTheme.colorScheme.surfaceContainerLow),
                        )
                    )
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = primary.copy(alpha = 0.18f),
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = primary)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Optimise", fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall)
                Text(
                    if (reclaimable > 0)
                        "Free up ~${formatSize(reclaimable)} — trash, duplicates & large files"
                    else "Storage looks tidy — tap to analyse deeper",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Surface(
                shape = CircleShape,
                color = primary.copy(alpha = 0.12f),
                modifier = Modifier.size(34.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.ChevronRight, null, tint = primary,
                        modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** Storage gauge — animated gradient ring with count-up percent, soft gradient card. */
@Composable
private fun StorageRingCard(u: StorageUsage, onClick: () -> Unit) {
    val fraction = (u.used.toFloat() / u.total.coerceAtLeast(1)).coerceIn(0f, 1f)
    val ring by animateFloatAsState(
        fraction,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow),
        label = "ring",
    )
    val pct by animateIntAsState(
        (fraction * 100).toInt(), tween(1000), label = "pct")

    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val track = MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(
                            primary.copy(alpha = 0.16f).compositeOver(
                                MaterialTheme.colorScheme.surfaceContainerLow),
                            tertiary.copy(alpha = 0.14f).compositeOver(
                                MaterialTheme.colorScheme.surfaceContainerLow),
                        )
                    )
                )
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(84.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val stroke = 9.dp.toPx()
                    drawArc(
                        color = track, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    )
                    drawArc(
                        brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                            listOf(primary, tertiary, primary)),
                        startAngle = -90f, sweepAngle = 360f * ring, useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$pct%", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold, color = primary)
                    Text("used", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text("Storage", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                Text("${formatSize(u.used)} of ${formatSize(u.total)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Text("${formatSize(u.free)} free — tap for categories, large files & trash",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One pinnable quick-access entry on the Home cards row. */
data class QaItem(
    val id: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val route: String,
)

/** Catalog of tools the user can pin to the Home quick-access row. */
object QaCatalog {
    const val MAX = 4
    val DEFAULT_IDS = listOf("dualpane", "remote", "transfer", "tools")

    val all = listOf(
        QaItem("inbox", Icons.Rounded.Inbox, "Inbox", Routes.INBOX),
        QaItem("recent", Icons.Rounded.Schedule, "Recent", Routes.RECENT),
        QaItem("dualpane", Icons.Rounded.Splitscreen, "Dual pane", Routes.DUALPANE),
        QaItem("remote", Icons.Rounded.Cloud, "Remote", Routes.CONNECTIONS),
        QaItem("transfer", Icons.Rounded.Phonelink, "To PC", Routes.TRANSFER),
        QaItem("sync", Icons.Rounded.Sync, "Folder sync", Routes.SYNCPAIRS),
        QaItem("photos", Icons.Rounded.ImageSearch, "Photo search",
            Routes.SEARCH + "?mode=photos"),
        QaItem("find", Icons.Rounded.Article, "Find in files",
            Routes.SEARCH + "?mode=content"),
        QaItem("optimise", Icons.Rounded.AutoAwesome, "Optimise", Routes.OPTIMISE),
        QaItem("photoclean", Icons.Rounded.PhotoLibrary, "Photo clean",
            Routes.PHOTOCLEAN),
        QaItem("backup", Icons.Rounded.Apps, "App backup", Routes.APPBACKUP),
        QaItem("appdata", Icons.Rounded.Storage, "Apps data", Routes.APPDATA),
        QaItem("archive", Icons.Rounded.FolderZip, "Zstd pack", Routes.ZSTD),
        QaItem("shield", Icons.Rounded.Shield, "Shield", Routes.SHIELD),
        QaItem("timemachine", Icons.Rounded.Restore, "Time machine",
            Routes.TIMEMACHINE),
        QaItem("similar", Icons.Rounded.CopyAll, "Similar files", Routes.SIMILAR),
        QaItem("typecheck", Icons.Rounded.FactCheck, "Type check", Routes.TYPECHECK),
        QaItem("drivecheck", Icons.Rounded.SdCard, "Drive check", Routes.DRIVECHECK),
        QaItem("metaclean", Icons.Rounded.PrivacyTip, "Safe share", Routes.METACLEAN),
        QaItem("compare", Icons.Rounded.CompareArrows, "Compare", Routes.COMPARE),
        QaItem("fingerprint", Icons.Rounded.Fingerprint, "Fingerprint",
            Routes.FINGERPRINT),
        QaItem("delta", Icons.Rounded.Difference, "Delta", Routes.DELTA),
        QaItem("power", Icons.Rounded.Terminal, "Power access", Routes.POWER),
        QaItem("rules", Icons.Rounded.AutoFixHigh, "Auto-sort", Routes.RULES),
        QaItem("trash", Icons.Rounded.Delete, "Trash", Routes.TRASH),
        QaItem("history", Icons.Rounded.History, "History", Routes.HISTORY),
        QaItem("calendar", Icons.Rounded.CalendarMonth, "Calendar", Routes.CALENDAR),
        QaItem("tools", Icons.Rounded.Build, "All tools", Routes.TOOLS),
    )

    /** Picked ids (or null/blank for defaults) -> display items, catalog order. */
    fun resolve(ids: List<String>?): List<QaItem> {
        val wanted = if (ids.isNullOrEmpty()) DEFAULT_IDS else ids
        return all.filter { it.id in wanted }.take(MAX)
    }
}
