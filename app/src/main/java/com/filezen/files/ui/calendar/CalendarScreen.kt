package com.filezen.files.ui.calendar

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.filezen.files.FileZenApp
import com.filezen.files.Routes
import com.filezen.files.core.model.FileEntry
import com.filezen.files.core.scan.Scanner
import com.filezen.files.ops.Intents
import com.filezen.files.ui.AppViewModel
import com.filezen.files.ui.common.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle as Jts
import java.util.Locale

class CalendarViewModel : ViewModel() {
    private val _byDay = MutableStateFlow<Map<LocalDate, List<FileEntry>>>(emptyMap())
    val byDay: StateFlow<Map<LocalDate, List<FileEntry>>> = _byDay

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    init { rescan() }

    fun rescan() {
        if (_scanning.value) return
        _scanning.value = true
        viewModelScope.launch {
            try {
                val map = HashMap<LocalDate, MutableList<FileEntry>>()
                Scanner.scan(
                    listOf(android.os.Environment.getExternalStorageDirectory()),
                    maxDepth = 12,
                ).collect { batch ->
                    for (e in batch) {
                        if (e.isDirectory) continue
                        val d = java.time.Instant.ofEpochMilli(e.lastModified)
                            .atZone(ZoneId.systemDefault()).toLocalDate()
                        val list = map.getOrPut(d) { mutableListOf() }
                        if (list.size < 400) list += e
                    }
                    _byDay.value = map.mapValues { it.value.sortedByDescending { e -> e.lastModified } }
                }
            } finally { _scanning.value = false }
        }
    }

    fun drop(day: LocalDate, paths: Collection<String>) {
        _byDay.value = _byDay.value[day]?.let { list ->
            _byDay.value + (day to list.filter { it.path !in paths })
        } ?: _byDay.value
    }
}

private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(nav: NavController, appVm: AppViewModel, vm: CalendarViewModel = viewModel()) {
    val byDay by vm.byDay.collectAsState()
    val scanning by vm.scanning.collectAsState()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var dayMenu by remember { mutableStateOf(false) }
    var trashConfirm by remember { mutableStateOf<List<String>?>(null) }
    val ctx = nav.context

    androidx.activity.compose.BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }

    val dayFiles = byDay[selected].orEmpty()

    Scaffold(
        topBar = {
            if (selection.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${selection.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Rounded.Close, "Clear") }
                    },
                    actions = {
                        IconButton(onClick = {
                            Intents.share(ctx, dayFiles.filter { it.path in selection })
                        }) { Icon(Icons.Rounded.Share, "Share") }
                        IconButton(onClick = { trashConfirm = selection.toList() }) {
                            Icon(Icons.Rounded.Delete, "Trash")
                        }
                        IconButton(onClick = { selection = dayFiles.map { it.path }.toSet() }) {
                            Icon(Icons.Rounded.SelectAll, "All")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CalendarMonth, null,
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("File calendar", fontWeight = FontWeight.Bold)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, "Back") }
                    },
                    actions = {
                        IconButton(onClick = { vm.rescan() }) { Icon(Icons.Rounded.Refresh, "Rescan") }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            // ---- Month header ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { month = month.minusMonths(1) }) {
                    Icon(Icons.Rounded.ChevronLeft, "Previous")
                }
                Text(
                    "${month.month.getDisplayName(Jts.FULL, Locale.getDefault())} ${month.year}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                IconButton(onClick = { month = month.plusMonths(1) }) {
                    Icon(Icons.Rounded.ChevronRight, "Next")
                }
            }

            // ---- Weekday letters ----
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                DAY_LETTERS.forEach { l ->
                    Text(l, Modifier.weight(1f), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(4.dp))

            // ---- Day grid ----
            val first = month.atDay(1)
            val lead = (first.dayOfWeek.value - 1) // Monday-first
            val cells = lead + month.lengthOfMonth()
            val rows = (cells + 6) / 7
            val today = LocalDate.now()

            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                repeat(rows) { w ->
                    Row(Modifier.fillMaxWidth()) {
                        repeat(7) { i ->
                            val n = w * 7 + i - lead + 1
                            val date = if (n in 1..month.lengthOfMonth()) month.atDay(n) else null
                            DayCell(
                                date = date,
                                count = date?.let { byDay[it]?.size ?: 0 } ?: 0,
                                selected = date == selected,
                                today = date == today,
                                modifier = Modifier.weight(1f),
                            ) { d -> selected = d; selection = emptySet() }
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 10.dp))

            // ---- Day detail ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        selected.let { "${it.dayOfMonth} ${it.month.getDisplayName(Jts.FULL, Locale.getDefault())} ${it.year}" },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (dayFiles.isEmpty()) (if (scanning) "Scanning…" else "No files")
                        else "${dayFiles.size} files",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (dayFiles.isNotEmpty() && selection.isEmpty()) {
                    Box {
                        IconButton(onClick = { dayMenu = true }) {
                            Icon(Icons.Rounded.MoreVert, "Day actions")
                        }
                        DropdownMenu(expanded = dayMenu, onDismissRequest = { dayMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Select all") },
                                leadingIcon = { Icon(Icons.Rounded.SelectAll, null) },
                                onClick = { selection = dayFiles.map { it.path }.toSet(); dayMenu = false })
                            DropdownMenuItem(
                                text = { Text("Zip all to this folder") },
                                leadingIcon = { Icon(Icons.Rounded.FolderZip, null) },
                                onClick = {
                                    dayFiles.firstOrNull()?.let {
                                        appVm.opZip(dayFiles.map { e -> e.path }, File(it.path).parentFile!!)
                                    }
                                    dayMenu = false
                                })
                            DropdownMenuItem(
                                text = { Text("Share all") },
                                leadingIcon = { Icon(Icons.Rounded.Share, null) },
                                onClick = { Intents.share(ctx, dayFiles); dayMenu = false })
                            DropdownMenuItem(
                                text = { Text("Move all to trash", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { trashConfirm = dayFiles.map { it.path }; dayMenu = false })
                        }
                    }
                }
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(dayFiles, key = { it.path }) { e ->
                    FileRow(
                        e = e, selected = e.path in selection,
                        onClick = {
                            if (selection.isNotEmpty()) {
                                selection = if (e.path in selection) selection - e.path else selection + e.path
                            } else nav.navigate(Routes.preview(e.path))
                        },
                        onLongClick = {
                            selection = if (e.path in selection) selection - e.path else selection + e.path
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
                vm.drop(selected, paths)
                selection = emptySet()
                trashConfirm = null
            },
            onDismiss = { trashConfirm = null },
        )
    }
}

/** One calendar day cell: number, today ring, selected fill, count dots. */
@Composable
private fun DayCell(
    date: LocalDate?,
    count: Int,
    selected: Boolean,
    today: Boolean,
    modifier: Modifier = Modifier,
    onClick: (LocalDate) -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val scale by animateFloatAsState(if (selected) 1f else 0.9f, tween(180), label = "cell")

    Box(
        modifier = modifier.aspectRatio(1f).padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (date == null) return
        val bg = when {
            selected -> primary
            today -> MaterialTheme.colorScheme.surfaceVariant
            else -> Color.Transparent
        }
        val fg = when {
            selected -> MaterialTheme.colorScheme.onPrimary
            today -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        }
        Column(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(bg)
                .clickable { onClick(date) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("${date.dayOfMonth}", color = fg,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected || today) FontWeight.Bold else FontWeight.Normal)
            if (count > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(minOf(3, count)) {
                        Box(
                            Modifier.size(4.dp).clip(CircleShape)
                                .background(if (selected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.tertiary))
                    }
                }
            } else {
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
