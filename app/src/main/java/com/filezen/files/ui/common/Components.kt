package com.filezen.files.ui.common

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.filezen.files.core.model.*
import java.io.File

fun iconFor(e: FileEntry): ImageVector = when (e.type) {
    FileType.FOLDER -> Icons.Rounded.Folder
    FileType.IMAGE -> Icons.Rounded.Image
    FileType.VIDEO -> Icons.Rounded.Movie
    FileType.AUDIO -> Icons.Rounded.MusicNote
    FileType.PDF, FileType.DOCUMENT -> Icons.Rounded.Description
    FileType.TEXT -> Icons.Rounded.TextSnippet
    FileType.APK -> Icons.Rounded.Android
    FileType.ARCHIVE -> Icons.Rounded.FolderZip
    FileType.OTHER -> Icons.Rounded.InsertDriveFile
}

fun tintFor(e: FileEntry): Color = when (e.type) {
    FileType.FOLDER -> Color(0xFFF5B94E)
    FileType.IMAGE -> Color(0xFF58A6FF)
    FileType.VIDEO -> Color(0xFFBC8CF2)
    FileType.AUDIO -> Color(0xFFF47067)
    FileType.PDF -> Color(0xFFEF5B5B)
    FileType.DOCUMENT -> Color(0xFF57AB5A)
    FileType.TEXT -> Color(0xFF9AA4B2)
    FileType.APK -> Color(0xFF3DDC84)
    FileType.ARCHIVE -> Color(0xFFDDB892)
    FileType.OTHER -> Color(0xFF8B949E)
}

@Composable
fun ThumbBox(e: FileEntry, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(if (e.isDirectory) 8.dp else 10.dp)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(tintFor(e).copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        if (e.type == FileType.IMAGE || e.type == FileType.VIDEO) {
            AsyncImage(
                model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(File(e.path))
                    .size(256)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(shape),
            )
        } else {
            Icon(iconFor(e), contentDescription = null, tint = tintFor(e),
                modifier = Modifier.size(size * 0.55f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    e: FileEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else Color.Transparent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThumbBox(e, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (e.isDirectory) "Folder" else formatSize(e.size)) + " · " + formatDate(e.lastModified),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) trailing()
        else if (selected) {
            Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileCard(e: FileEntry, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.surfaceContainerLow
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.85f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                ThumbBox(e, 64.dp)
            }
            Text(e.name, style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (e.isDirectory) "Folder" else formatSize(e.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Breadcrumb(path: String, onNavigate: (String) -> Unit) {
    val segs = path.split("/").filter { it.isNotEmpty() }
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            TextButton(onClick = { onNavigate("/") }) {
                Text("/", fontWeight = FontWeight.Bold)
            }
        }
        var acc = ""
        items(segs.size) { i ->
            acc += "/" + segs[i]
            val target = acc
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onNavigate(target) }) {
                Text(segs[i], maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun SectionHeader(text: String, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary)
        action?.invoke()
    }
}

@Composable
fun ConflictDialog(
    fileName: String,
    onDecision: (ConflictChoice) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onDecision(ConflictChoice.SKIP) },
        title = { Text("Name conflict") },
        text = { Text("“$fileName” already exists at the destination.") },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { onDecision(ConflictChoice.OVERWRITE) }) { Text("Replace") }
                TextButton(onClick = { onDecision(ConflictChoice.KEEP_BOTH) }) { Text("Keep both") }
                TextButton(onClick = { onDecision(ConflictChoice.SKIP) }) { Text("Skip") }
            }
        },
    )
}

enum class ConflictChoice { OVERWRITE, KEEP_BOTH, SKIP }

@Composable
fun OpProgressCard(current: com.filezen.files.core.fileops.RunningOp?, onCancel: () -> Unit) {
    current ?: return
    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(current.label, style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(
                    progress = {
                        current.progress?.let {
                            if (it.itemsTotal > 0) it.itemsDone.toFloat() / it.itemsTotal else 0f
                        } ?: 0f
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                current.progress?.let {
                    Text("${it.itemsDone}/${it.itemsTotal} · ${it.currentName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (current.cancellable) TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}


