package com.filezen.files.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.filezen.files.core.model.formatDate
import com.filezen.files.core.model.formatSize
import com.filezen.files.data.db.SortRule
import com.filezen.files.ui.common.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TrashScreen(nav: NavController, appVm: AppViewModel, vm: StorageViewModel = viewModel()) {
    val entries by vm.trashEntries.collectAsState()
    val size by vm.trashSize.collectAsState()
    var purgeAll by remember { mutableStateOf(false) }
    var purgeOne by remember { mutableStateOf<Long?>(null) }
    var selIds by remember { mutableStateOf(setOf<Long>()) }
    var purgeSel by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trash", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, "Back") }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        IconButton(onClick = { purgeAll = true }) {
                            Icon(Icons.Rounded.DeleteSweep, "Empty trash")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyState(Icons.Rounded.DeleteOutline, "Trash is empty",
                "Files you delete in FileZen are kept here for recovery.")
        } else {
            Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Text(
                        "${entries.size} items · ${formatSize(size)} — tap to select",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(entries, key = { it.id }) { e ->
                    val isSel = e.id in selIds
                    ListItem(
                        headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text("${formatSize(e.size)} · deleted ${formatDate(e.deletedAt)}\nfrom ${e.originalPath}",
                                style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        },
                        leadingContent = {
                            Icon(
                                if (isSel) Icons.Rounded.CheckCircle
                                else Icons.Rounded.InsertDriveFile, null,
                                tint = if (isSel) MaterialTheme.colorScheme.primary
                                    else LocalContentColor.current)
                        },
                        trailingContent = {
                            if (selIds.isEmpty()) Row {
                                TextButton(onClick = { vm.restoreTrash(e.id) }) { Text("Restore") }
                                TextButton(onClick = { purgeOne = e.id }) {
                                    Text("Delete", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        },
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                selIds = if (isSel) selIds - e.id else selIds + e.id
                            },
                            onLongClick = {
                                selIds = if (isSel) selIds - e.id else selIds + e.id
                            }),
                        colors = ListItemDefaults.colors(
                            containerColor = if (isSel)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            else Color.Transparent),
                    )
                }
                item { Spacer(Modifier.height(if (selIds.isNotEmpty()) 140.dp else 16.dp)) }
            }
            if (selIds.isNotEmpty()) {
                Surface(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 4.dp, shadowElevation = 8.dp,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                ) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${selIds.size} selected",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                selIds = if (selIds.size == entries.size) emptySet()
                                    else entries.map { it.id }.toSet()
                            }) { Text(if (selIds.size == entries.size) "None" else "All") }
                            IconButton(onClick = { selIds = emptySet() }) {
                                Icon(Icons.Rounded.Close, "Close",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = {
                                    vm.restoreTrashMany(selIds.toList()); selIds = emptySet()
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.Restore, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp)); Text("Restore")
                            }
                            OutlinedButton(
                                onClick = { purgeSel = true },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.DeleteForever, null,
                                    Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(6.dp))
                                Text("Delete forever",
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            }
        }
    }

    if (purgeAll) {
        ConfirmDialog("Empty trash?", "All ${entries.size} items will be permanently deleted. This can't be undone.",
            "Empty trash", danger = true,
            onConfirm = { vm.purgeAll(); purgeAll = false },
            onDismiss = { purgeAll = false })
    }
    purgeOne?.let { id ->
        ConfirmDialog("Delete permanently?", "This item will be permanently deleted.",
            "Delete", danger = true,
            onConfirm = { vm.purgeTrash(id); purgeOne = null },
            onDismiss = { purgeOne = null })
    }
    if (purgeSel) {
        ConfirmDialog("Delete ${selIds.size} permanently?",
            "Selected items will be permanently deleted. This can't be undone.",
            "Delete", danger = true,
            onConfirm = {
                vm.purgeTrashMany(selIds.toList()); selIds = emptySet(); purgeSel = false
            },
            onDismiss = { purgeSel = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortRulesScreen(nav: NavController, appVm: AppViewModel, vm: StorageViewModel = viewModel()) {
    val rules by vm.rules.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<com.filezen.files.data.db.SortRule?>(null) }
    var matchType by remember { mutableStateOf("EXTENSION") }
    var pattern by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var source by remember { mutableStateOf<String?>(null) }
    var pickFolder by remember { mutableStateOf(false) }
    var pickSource by remember { mutableStateOf(false) }

    // Open the rule dialog prefilled — for templates (new rule) or editing.
    fun openEditor(mt: String, p: String, t: String, s: String?,
                   r: com.filezen.files.data.db.SortRule? = null) {
        matchType = mt; pattern = p; target = t; source = s
        editing = r; showAdd = true
    }

    val storage = android.os.Environment.getExternalStorageDirectory().absolutePath
    val waMedia = "$storage/Android/media/com.whatsapp/WhatsApp/Media"
    val templates = remember {
        fun pub(name: String) =
            android.os.Environment.getExternalStoragePublicDirectory(name).absolutePath
        listOf(
            RuleTemplate("PDFs", "pdf", pub(android.os.Environment.DIRECTORY_DOCUMENTS) + "/PDFs", Icons.Rounded.PictureAsPdf),
            RuleTemplate("Photos", "jpg,jpeg,png,webp,heic,heif", pub(android.os.Environment.DIRECTORY_PICTURES), Icons.Rounded.Image),
            RuleTemplate("Videos", "mp4,mkv,webm,3gp,mov,avi", pub(android.os.Environment.DIRECTORY_MOVIES), Icons.Rounded.Movie),
            RuleTemplate("Music & audio", "mp3,m4a,aac,flac,ogg,wav,opus", pub(android.os.Environment.DIRECTORY_MUSIC), Icons.Rounded.MusicNote),
            RuleTemplate("Documents", "doc,docx,xls,xlsx,ppt,pptx,txt,csv,rtf", pub(android.os.Environment.DIRECTORY_DOCUMENTS), Icons.Rounded.Description),
            RuleTemplate("App installers", "apk,apkm,xapk", pub(android.os.Environment.DIRECTORY_DOWNLOADS) + "/APKs", Icons.Rounded.Android),
            RuleTemplate("Archives", "zip,rar,7z,tar,gz,bz2", pub(android.os.Environment.DIRECTORY_DOWNLOADS) + "/Archives", Icons.Rounded.FolderZip),
            RuleTemplate("WhatsApp PDFs", "pdf", pub(android.os.Environment.DIRECTORY_DOCUMENTS) + "/WhatsApp",
                Icons.Rounded.ChatBubble, source = "$waMedia/WhatsApp Documents"),
            RuleTemplate("WhatsApp images", "jpg,jpeg,png,webp", pub(android.os.Environment.DIRECTORY_PICTURES) + "/WhatsApp",
                Icons.Rounded.ChatBubble, source = "$waMedia/WhatsApp Images"),
            RuleTemplate("WhatsApp video", "mp4,mkv,3gp", pub(android.os.Environment.DIRECTORY_MOVIES) + "/WhatsApp",
                Icons.Rounded.ChatBubble, source = "$waMedia/WhatsApp Video"),
            RuleTemplate("WhatsApp audio", "opus,aac,m4a,mp3", pub(android.os.Environment.DIRECTORY_MUSIC) + "/WhatsApp",
                Icons.Rounded.ChatBubble, source = "$waMedia/WhatsApp Audio"),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sort rules", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, "Back") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("New rule") },
            )
        },
    ) { padding ->
        run {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item(key = "tpl-head") {
                    Text("Quick templates — tap to customise & add, or + for quick add",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(templates.filter { t ->
                    rules.none { it.matchType == t.matchType && it.pattern == t.pattern &&
                        it.targetPath == t.targetPath && it.sourcePath == t.source }
                }, key = { it.title }) { t ->
                    ListItem(
                        headlineContent = { Text(t.title) },
                        supportingContent = {
                            Text("${t.pattern.split(",").joinToString(" ") { "*.$it" }}" +
                                (t.source?.let { " in ${java.io.File(it).name}" } ?: "") +
                                "  →  ${t.targetPath.substringAfterLast('/')}",
                                style = MaterialTheme.typography.bodySmall)
                        },
                        leadingContent = { Icon(t.icon, null,
                            tint = MaterialTheme.colorScheme.tertiary) },
                        trailingContent = {
                            IconButton(onClick = {
                                vm.addRule(t.matchType, t.pattern, t.targetPath, t.source)
                            }) { Icon(Icons.Rounded.AddCircle, "Add rule") }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            openEditor(t.matchType, t.pattern, t.targetPath, t.source)
                        },
                    )
                }
                item(key = "rules-head") {
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Your rules (${rules.size})",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (rules.any { it.enabled }) {
                            AssistChip(
                                onClick = { vm.runRulesNow() },
                                label = { Text("Run now") },
                                leadingIcon = { Icon(Icons.Rounded.PlayArrow, null,
                                    Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
                if (rules.isEmpty()) {
                    item(key = "rules-empty") {
                        Text("No rules yet — tap + on a template above, or New rule for a custom one.",
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(rules, key = { it.id }) { r ->
                    val typeIcon = when (r.matchType) {
                        "EXTENSION" -> Icons.Rounded.Extension
                        "CONTAINS" -> Icons.Rounded.Abc
                        else -> Icons.Rounded.Code
                    }
                    Card(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (r.enabled)
                                MaterialTheme.colorScheme.surfaceContainerLow
                            else MaterialTheme.colorScheme.surfaceContainerLowest),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(typeIcon, null,
                                    tint = if (r.enabled) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        com.filezen.files.core.fileops.SortRuleEngine.describe(r),
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (r.enabled) Color.Unspecified
                                            else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(2.dp))
                                    Text("→ ${r.targetPath}", maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = r.enabled,
                                    onCheckedChange = { vm.toggleRule(r, it) })
                                IconButton(onClick = {
                                    openEditor(r.matchType, r.pattern, r.targetPath,
                                        r.sourcePath, r)
                                }) {
                                    Icon(Icons.Rounded.Edit, "Edit",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                }
                                IconButton(onClick = { vm.deleteRule(r) }) {
                                    Icon(Icons.Rounded.Delete, "Delete",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp))
                                }
                            }
                            // Scope + pattern chips
                            Row(
                                Modifier.padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text(r.matchType.lowercase()
                                        .replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.height(26.dp),
                                )
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text(r.pattern.split(",")
                                        .joinToString(" ") {
                                            if (r.matchType == "EXTENSION") "*.$it" else it },
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.height(26.dp).weight(1f, fill = false),
                                )
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text(r.sourcePath?.let {
                                            "in ${java.io.File(it).name}" } ?: "Anywhere",
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    icon = { Icon(Icons.Rounded.Source, null,
                                        Modifier.size(12.dp)) },
                                    modifier = Modifier.height(26.dp),
                                )
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Rules run automatically when new files land in watched folders — and on demand from a folder's overflow → “Apply sort rules”, or Run now above.",
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DriveFileMove, null,
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(if (editing != null) "Edit sort rule" else "New sort rule")
                }
            },
            text = {
                Column {
                    Text("When a file matches…", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("EXTENSION", "CONTAINS", "REGEX").forEachIndexed { i, t ->
                            SegmentedButton(
                                selected = matchType == t,
                                onClick = { matchType = t },
                                shape = SegmentedButtonDefaults.itemShape(i, 3),
                            ) { Text(t.lowercase().replaceFirstChar { it.uppercase() }) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pattern, onValueChange = { pattern = it },
                        leadingIcon = {
                            Icon(when (matchType) {
                                "EXTENSION" -> Icons.Rounded.Extension
                                "CONTAINS" -> Icons.Rounded.Abc
                                else -> Icons.Rounded.Code
                            }, null)
                        },
                        label = { Text(when (matchType) {
                            "EXTENSION" -> "Extension (e.g. pdf)"
                            "CONTAINS" -> "Name contains"
                            else -> "Regex"
                        }) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    Text("…move it to", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        onClick = { pickFolder = true },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Folder, null,
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (target.isBlank()) "Choose folder…"
                                    else target.substringAfterLast('/').ifBlank { target },
                                    fontWeight = FontWeight.Medium, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                if (target.isNotBlank()) {
                                    Text(target, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Icon(Icons.Rounded.ChevronRight, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Only inside folder (optional)", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        onClick = { pickSource = true },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Source, null,
                                tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    source?.substringAfterLast('/')?.ifBlank { source!! }
                                        ?: "Anywhere",
                                    fontWeight = FontWeight.Medium, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                if (source != null) {
                                    Text(source!!, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (source != null) {
                                IconButton(onClick = { source = null }) {
                                    Icon(Icons.Rounded.Close, "Anywhere", Modifier.size(18.dp))
                                }
                            } else {
                                Icon(Icons.Rounded.ChevronRight, null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (pattern.isNotBlank() && target.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        ) {
                            Text(
                                "${com.filezen.files.core.fileops.SortRuleEngine.describe(
                                    com.filezen.files.data.db.SortRule(
                                        matchType = matchType, pattern = pattern,
                                        targetPath = target, sourcePath = source))} → ${target.substringAfterLast('/')}",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val p = pattern.trim(); val t = target.trim()
                        val r = editing
                        if (r != null) vm.updateRule(r.copy(
                            matchType = matchType, pattern = p,
                            targetPath = t, sourcePath = source))
                        else vm.addRule(matchType, p, t, source)
                        pattern = ""; target = ""; source = null
                        editing = null; showAdd = false
                    },
                    enabled = pattern.isNotBlank() && target.isNotBlank(),
                ) { Text(if (editing != null) "Save" else "Add rule") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null; showAdd = false }) { Text("Cancel") }
            },
        )
    }

    if (pickFolder) {
        FolderPickerSheet(
            startPath = target.ifBlank {
                android.os.Environment.getExternalStorageDirectory().absolutePath },
            onPick = { target = it; pickFolder = false },
            onDismiss = { pickFolder = false },
        )
    }
    if (pickSource) {
        FolderPickerSheet(
            startPath = source ?: android.os.Environment.getExternalStorageDirectory().absolutePath,
            onPick = { source = it; pickSource = false },
            onDismiss = { pickSource = false },
        )
    }
}

private data class RuleTemplate(
    val title: String,
    val pattern: String,
    val targetPath: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val matchType: String = "EXTENSION",
    val source: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(nav: NavController, vm: StorageViewModel = viewModel()) {
    val history by vm.history.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Operation history", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        if (history.isEmpty()) {
            EmptyState(Icons.Rounded.History, "No operations yet",
                "Copy, move, tidy and delete actions show up here with their status.")
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(history, key = { it.id }) { r ->
                    val (icon, tint) = when (r.status) {
                        "OK" -> Icons.Rounded.CheckCircle to Color(0xFF3DDC84)
                        "PARTIAL" -> Icons.Rounded.Warning to Color(0xFFF5B94E)
                        "CANCELLED" -> Icons.Rounded.Cancel to Color(0xFF9AA4B2)
                        else -> Icons.Rounded.Error to MaterialTheme.colorScheme.error
                    }
                    ListItem(
                        headlineContent = {
                            Text("${r.kind.lowercase().replaceFirstChar { it.uppercase() }} · ${r.itemCount} item(s)")
                        },
                        supportingContent = {
                            Column {
                                r.targetDir?.let { Text("→ $it", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                r.detail?.let { Text(it, maxLines = 2, style = MaterialTheme.typography.bodySmall) }
                                Text(formatDate(r.timestamp), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        leadingContent = { Icon(icon, null, tint = tint) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

/** Every recent file (up to 500) — the Home "See all" destination. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen(nav: NavController, appVm: AppViewModel, vm: HomeViewModel = viewModel()) {
    val recent by vm.allRecent.collectAsState()
    var selection by remember { mutableStateOf(setOf<String>()) }
    var trashConfirm by remember { mutableStateOf<List<String>?>(null) }
    var moveTarget by remember { mutableStateOf<List<String>?>(null) }
    val favorites by appVm.favorites.collectAsState()
    val ctx = nav.context

    LaunchedEffect(Unit) { vm.loadAllRecent() }

    androidx.activity.compose.BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }

    Scaffold(
        topBar = {
            if (selection.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${selection.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selection = emptySet() }) {
                            Icon(Icons.Rounded.Close, "Clear")
                        }
                    },
                    actions = {
                        if (selection.size == 1) {
                            IconButton(onClick = {
                                recent.firstOrNull { it.path == selection.first() }
                                    ?.let { com.filezen.files.ops.Intents.openWith(ctx, it) }
                            }) { Icon(Icons.Rounded.OpenInNew, "Open with") }
                        }
                        IconButton(onClick = {
                            com.filezen.files.ops.Intents.share(
                                ctx, recent.filter { it.path in selection })
                        }) { Icon(Icons.Rounded.Share, "Share") }
                        IconButton(onClick = { moveTarget = selection.toList() }) {
                            Icon(Icons.Rounded.FolderShared, "Move to folder…")
                        }
                        IconButton(onClick = {
                            appVm.opMove(selection.toList(),
                                com.filezen.files.FileZenApp.c.transfer.shareDir,
                                com.filezen.files.core.fileops.ConflictPolicy.KEEP_BOTH)
                            selection = emptySet()
                        }) { Icon(Icons.Rounded.Phonelink, "Send to Transfer folder") }
                        IconButton(onClick = { trashConfirm = selection.toList() }) {
                            Icon(Icons.Rounded.Delete, "Trash")
                        }
                        IconButton(onClick = { selection = recent.map { it.path }.toSet() }) {
                            Icon(Icons.Rounded.SelectAll, "All")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Recent files", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.Rounded.ArrowBack, "Back")
                        }
                    },
                )
            }
        },
    ) { padding ->
        if (recent.isEmpty()) {
            EmptyState(Icons.Rounded.Schedule, "No recent files",
                "New files from Downloads, Pictures, DCIM and Documents appear here.",
                Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(recent, key = { it.path }) { e ->
                    FileRow(
                        e = e, selected = e.path in selection,
                        onClick = {
                            if (selection.isNotEmpty()) {
                                selection = if (e.path in selection) selection - e.path
                                else selection + e.path
                            } else if (e.isDirectory) nav.navigate(Routes.folder(e.path))
                            else nav.navigate(Routes.preview(e.path))
                        },
                        onLongClick = {
                            selection = if (e.path in selection) selection - e.path
                            else selection + e.path
                        },
                    )
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }

    trashConfirm?.let { paths ->
        ConfirmDialog(
            "Move to trash?", "${paths.size} item(s) will be moved to trash.",
            "Move to trash",
            onConfirm = {
                appVm.opTrash(paths)
                vm.dropRecent(paths)
                selection = emptySet()
                trashConfirm = null
            },
            onDismiss = { trashConfirm = null },
        )
    }

    moveTarget?.let { paths ->
        DestinationSheet(
            favorites = favorites,
            currentPath = File(paths.first()).parent ?: "/storage/emulated/0",
            shareDir = com.filezen.files.FileZenApp.c.transfer.shareDir,
            onPick = { dest ->
                appVm.opMove(paths, File(dest),
                    com.filezen.files.core.fileops.ConflictPolicy.KEEP_BOTH)
                vm.dropRecent(paths)
                selection = emptySet()
                moveTarget = null
            },
            onDismiss = { moveTarget = null },
        )
    }
}
