package com.filezen.files.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.filezen.files.data.db.Favorite

@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    hint: String = "",
    confirmLabel: String = "OK",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text(hint) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String = "Confirm",
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel,
                    color = if (danger) MaterialTheme.colorScheme.error else LocalContentColor.current)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Destination picker for move/copy and the Inbox "Rapikan" flow:
 * favourite folders first, then browse / current folder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinationSheet(
    favorites: List<Favorite>,
    currentPath: String,
    onPick: (String) -> Unit,
    onBrowse: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Move to…", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
            items(favorites) { f ->
                ListItem(
                    headlineContent = { Text(f.label) },
                    supportingContent = {
                        Text(f.path, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    },
                    leadingContent = {
                        Icon(Icons.Rounded.Star, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable { onPick(f.path) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = onBrowse,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        ) { Text("Browse for a folder…") }
        TextButton(
            onClick = { onPick(currentPath) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        ) { Text("Current folder") }
        Spacer(Modifier.height(28.dp))
    }
}
