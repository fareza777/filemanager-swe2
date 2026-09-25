package com.filezen.files.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Phonelink
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.filezen.files.FileZenApp
import com.filezen.files.core.fileops.ConflictPolicy
import com.filezen.files.core.model.FileEntry
import com.filezen.files.ops.Intents
import com.filezen.files.ui.AppViewModel
import java.io.File

/**
 * Shared mass-selection state + bottom action bar used by every file list in
 * the app. Long-press (or tap while a selection is active) selects rows; the
 * bar offers Share / Move / Copy / Send to Transfer / Zip / Clean metadata /
 * Delete on all selected paths at once.
 */
class SelectionState {
    var selected by mutableStateOf(setOf<String>())
        private set
    val active get() = selected.isNotEmpty()
    fun toggle(path: String) {
        selected = if (path in selected) selected - path else selected + path
    }
    fun clear() { selected = emptySet() }
    fun setAll(paths: Collection<String>) { selected = paths.toSet() }
}

@Composable
fun rememberSelection(): SelectionState = remember { SelectionState() }

/** Bottom action bar for a mass selection. Rendered inside a Box at the
 *  bottom of the screen (Modifier.align(Alignment.BottomCenter)). */
@Composable
fun MassActionsBar(
    appVm: AppViewModel,
    sel: SelectionState,
    allPaths: List<String>,
    modifier: Modifier = Modifier,
) {
    if (!sel.active) return
    val ctx = LocalContext.current
    var movePicker by remember { mutableStateOf(false) }
    var copyPicker by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    val paths = sel.selected.toList()
    val entries = paths.map { FileEntry.from(File(it)) }
    val filePaths = entries.filter { !it.isDirectory }.map { it.path }
    val allChecked = sel.selected.size == allPaths.size && allPaths.isNotEmpty()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${sel.selected.size} selected",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    if (allChecked) sel.clear() else sel.setAll(allPaths)
                }) {
                    Icon(
                        Icons.Rounded.SelectAll, null,
                        Modifier.size(18.dp).padding(end = 4.dp),
                    )
                    Text(if (allChecked) "None" else "All")
                }
                IconButton(onClick = { sel.clear() }) {
                    Icon(Icons.Rounded.Close, "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionIcon(Icons.Rounded.Share, "Share", enabled = filePaths.isNotEmpty()) {
                    Intents.share(ctx, entries.filter { !it.isDirectory })
                }
                ActionIcon(Icons.Rounded.DriveFileMove, "Move to folder…") { movePicker = true }
                ActionIcon(Icons.Rounded.ContentCopy, "Copy to folder…") { copyPicker = true }
                ActionIcon(Icons.Rounded.Phonelink, "Send to Transfer folder",
                    enabled = filePaths.isNotEmpty()) {
                    appVm.opCopy(filePaths, FileZenApp.c.transfer.shareDir, ConflictPolicy.KEEP_BOTH)
                    sel.clear()
                }
                ActionIcon(Icons.Rounded.FolderZip, "Zip here") {
                    val dest = File(paths.first()).parentFile
                        ?: android.os.Environment.getExternalStorageDirectory()
                    appVm.opZip(paths, dest)
                    sel.clear()
                }
                ActionIcon(Icons.Rounded.PrivacyTip, "Clean metadata",
                    enabled = filePaths.isNotEmpty()) {
                    appVm.opCleanMetadata(filePaths)
                    sel.clear()
                }
                ActionIcon(Icons.Rounded.Delete, "Delete",
                    tint = MaterialTheme.colorScheme.error) { deleteConfirm = true }
            }
        }
    }

    if (movePicker) FolderPickerSheet(
        onPick = { dest ->
            movePicker = false
            appVm.opMove(paths, File(dest), ConflictPolicy.KEEP_BOTH)
            sel.clear()
        },
        onDismiss = { movePicker = false },
    )
    if (copyPicker) FolderPickerSheet(
        onPick = { dest ->
            copyPicker = false
            appVm.opCopy(paths, File(dest), ConflictPolicy.KEEP_BOTH)
            sel.clear()
        },
        onDismiss = { copyPicker = false },
    )
    if (deleteConfirm) ConfirmDialog(
        title = "Move ${paths.size} item${if (paths.size > 1) "s" else ""} to trash?",
        text = "You can restore them from Trash.",
        confirmLabel = "Move to trash",
        danger = true,
        onConfirm = {
            deleteConfirm = false
            appVm.opTrash(paths)
            sel.clear()
        },
        onDismiss = { deleteConfirm = false },
    )
}

@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 6.dp),
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, label, tint = if (enabled) tint else MaterialTheme.colorScheme.outline)
        }
        Text(
            label.substringBefore(' '), style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.outline,
        )
    }
}
