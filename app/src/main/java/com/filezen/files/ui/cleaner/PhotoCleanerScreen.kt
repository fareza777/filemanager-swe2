package com.filezen.files.ui.cleaner

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.filezen.files.core.cleaner.PhotoLens
import com.filezen.files.core.model.FileEntry
import com.filezen.files.core.model.FileType
import com.filezen.files.core.model.formatSize
import com.filezen.files.ui.AppViewModel
import com.filezen.files.ui.CleanerViewModel
import java.io.File

/**
 * Smart photo cleaner (ClearLens port): groups duplicates, similar shots and
 * low-quality photos; the best copy is auto-marked Keep — user reviews and
 * trashes the rest. Nothing is removed automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoCleanerScreen(nav: NavController, appVm: AppViewModel, vm: CleanerViewModel = viewModel()) {
    val report by vm.report.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val progress by vm.progress.collectAsState()
    val selected by vm.selected.collectAsState()

    val selectedBytes = remember(report, selected) {
        report?.groups?.flatMap { it.photos }
            ?.filter { it.path in selected }?.sumOf { it.sizeBytes } ?: 0L
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Photo cleaner", fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (report != null) {
                        TextButton(onClick = { vm.selectRecommended() }) { Text("Recommended") }
                    }
                    IconButton(onClick = { vm.scan() }) { Icon(Icons.Rounded.Refresh, "Rescan") }
                },
            )
        },
        bottomBar = {
            AnimatedVisibility(visible = selected.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${selected.size} selected", fontWeight = FontWeight.SemiBold)
                            Text(
                                "${formatSize(selectedBytes)} to trash",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { vm.clearSelection() }) { Text("Clear") }
                        Button(
                            onClick = {
                                appVm.opTrash(selected.toList())
                                vm.clearSelection()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Move to trash")
                        }
                    }
                }
            }
        },
    ) { pad ->
        when {
            scanning || report == null -> {
                Column(
                    Modifier.padding(pad).fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        progress.ifEmpty { "Preparing scan…" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            report!!.groups.isEmpty() -> {
                Column(
                    Modifier.padding(pad).fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Rounded.CheckCircle, null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Gallery looks clean", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${report!!.scanned} photos scanned — no duplicates, similar shots or quality issues found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> LazyColumn(
                modifier = Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${formatSize(report!!.recoverableBytes)} recoverable",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "${report!!.flaggedPhotos} photos in ${report!!.groups.size} groups · " +
                                        "${report!!.scanned} scanned" +
                                        if (report!!.failed > 0) " · ${report!!.failed} unreadable" else "",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                items(report!!.groups, key = { it.id }) { group ->
                    FindingCard(group, selected, onToggle = { vm.toggle(it) })
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun FindingCard(
    group: PhotoLens.FindingGroup,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    val bestPath = group.photos.firstOrNull()?.path
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val iconTint: Pair<ImageVector, Color> = when (group.type) {
                    PhotoLens.FindingType.EXACT_DUPLICATE -> Icons.Rounded.CopyAll to MaterialTheme.colorScheme.error
                    PhotoLens.FindingType.SIMILAR -> Icons.Rounded.Collections to MaterialTheme.colorScheme.tertiary
                    PhotoLens.FindingType.BLURRY -> Icons.Rounded.BlurOn to MaterialTheme.colorScheme.secondary
                    PhotoLens.FindingType.DARK -> Icons.Rounded.NightsStay to MaterialTheme.colorScheme.secondary
                    PhotoLens.FindingType.BLANK -> Icons.Rounded.Crop169 to MaterialTheme.colorScheme.outline
                }
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(iconTint.second.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(iconTint.first, null, tint = iconTint.second, modifier = Modifier.size(19.dp)) }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(group.title, fontWeight = FontWeight.SemiBold)
                    Text(group.type.label, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                if (group.recoverableBytes > 0) {
                    Text(
                        formatSize(group.recoverableBytes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                group.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(group.photos, key = { it.path }) { photo ->
                    PhotoCell(
                        photo = photo,
                        isBest = photo.path == bestPath && group.recommendedDeletePaths.isNotEmpty(),
                        checked = photo.path in selected,
                        onToggle = { onToggle(photo.path) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoCell(
    photo: PhotoLens.LensPhoto,
    isBest: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
        Box {
            AsyncImage(
                model = File(photo.path),
                contentDescription = photo.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onToggle),
            )
            // Selection check badge.
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .size(24.dp).clip(CircleShape)
                    .background(
                        if (checked) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) Icon(
                    Icons.Rounded.Check, null,
                    tint = MaterialTheme.colorScheme.onError, modifier = Modifier.size(15.dp))
            }
            if (isBest) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                ) {
                    Text(
                        "Keep",
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Text(
            photo.name, maxLines = 1,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            formatSize(photo.sizeBytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
