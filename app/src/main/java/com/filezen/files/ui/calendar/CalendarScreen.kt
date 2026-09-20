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
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.filezen.files.FileZenApp
import com.filezen.files.Routes
import com.filezen.files.core.model.FileEntry
import com.filezen.files.core.model.formatSize
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
                val idx = com.filezen.files.FileZenApp.c.fileIndex
                if (idx.ready.value) {
                    // Instant path: group the persisted index by day.
                    for (e in idx.all()) {
                        val d = java.time.Instant.ofEpochMilli(e.lastModified)
                            .atZone(ZoneId.systemDefault()).toLocalDate()
                        val list = map.getOrPut(d) { mutableListOf() }
                        if (list.size < 400) list += FileEntry(
                            e.path, e.name, false, e.size, e.lastModified,
                            com.filezen.files.core.model.FileType.valueOf(e.type))
                    }
                } else {
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
                }
                _byDay.value = map.mapValues { it.value.sortedByDescending { e -> e.lastModified } }
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
    var moveTarget by remember { mutableStateOf<List<String>?>(null) }
    val favorites by appVm.favorites.collectAsState()
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
                        IconButton(onClick = { moveTarget = selection.toList() }) {
                            Icon(Icons.Rounded.FolderShared, "Move to folder…")
                        }
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

            // ---- Calendar card ----
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
            Column(Modifier.padding(bottom = 14.dp)) {
            // ---- Month header ----
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { month = month.minusMonths(1) }) {
                    Icon(Icons.Rounded.ChevronLeft, "Previous",
                        tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${month.month.getDisplayName(Jts.FULL, Locale.getDefault())} ${month.year}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                    )
                    val monthCount = byDay.filterKeys { YearMonth.from(it) == month }
                        .values.sumOf { it.size }
                    Text(if (monthCount > 0) "$monthCount files this month" else " ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { month = month.plusMonths(1) }) {
                    Icon(Icons.Rounded.ChevronRight, "Next",
                        tint = MaterialTheme.colorScheme.primary)
                }
            }

            // ---- Weekday letters ----
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                DAY_LETTERS.forEachIndexed { i, l ->
                    Text(l, Modifier.weight(1f), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (i >= 5) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(6.dp))

            // ---- Day grid ----
            val first = month.atDay(1)
            val lead = (first.dayOfWeek.value - 1) // Monday-first
            val cells = lead + month.lengthOfMonth()
            val rows = (cells + 6) / 7
            val today = LocalDate.now()
            val maxDay = byDay.filterKeys { YearMonth.from(it) == month }
                .values.maxOfOrNull { it.size } ?: 1

            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                repeat(rows) { w ->
                    Row(Modifier.fillMaxWidth()) {
                        repeat(7) { i ->
                            val n = w * 7 + i - lead + 1
                            val date = if (n in 1..month.lengthOfMonth()) month.atDay(n) else null
                            DayCell(
                                date = date,
                                count = date?.let { byDay[it]?.size ?: 0 } ?: 0,
                                maxCount = maxDay,
                                selected = date == selected,
                                today = date == today,
                                modifier = Modifier.weight(1f),
                            ) { d -> selected = d; selection = emptySet() }
                        }
                    }
                }
            }
            // Jump back to today
            if (month != YearMonth.now() || selected != LocalDate.now()) {
                TextButton(
                    onClick = { month = YearMonth.now(); selected = LocalDate.now()
                        selection = emptySet() },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text("Jump to today") }
            }
            }
            }

            Spacer(Modifier.height(10.dp))

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
                        else "${dayFiles.size} files · " +
                            formatSize(dayFiles.sumOf { it.size }),
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
                                text = { Text("Move all to folder…") },
                                leadingIcon = { Icon(Icons.Rounded.FolderShared, null) },
                                onClick = { moveTarget = dayFiles.map { it.path }; dayMenu = false })
                            DropdownMenuItem(
                                text = { Text("Send all to Transfer folder") },
                                leadingIcon = { Icon(Icons.Rounded.Phonelink, null) },
                                onClick = {
                                    appVm.opMove(dayFiles.map { it.path },
                                        FileZenApp.c.transfer.shareDir,
                                        com.filezen.files.core.fileops.ConflictPolicy.KEEP_BOTH)
                                    vm.drop(selected, dayFiles.map { it.path })
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

    moveTarget?.let { paths ->
        DestinationSheet(
            favorites = favorites,
            currentPath = File(paths.first()).parent ?: "/storage/emulated/0",
            shareDir = FileZenApp.c.transfer.shareDir,
            onPick = { dest ->
                appVm.opMove(paths, File(dest),
                    com.filezen.files.core.fileops.ConflictPolicy.KEEP_BOTH)
                vm.drop(selected, paths)
                selection = emptySet()
                moveTarget = null
            },
            onDismiss = { moveTarget = null },
        )
    }
}

/** One calendar day cell: number, heat-intensity fill by file count, today ring, selected pill. */
@Composable
private fun DayCell(
    date: LocalDate?,
    count: Int,
    maxCount: Int,
    selected: Boolean,
    today: Boolean,
    modifier: Modifier = Modifier,
    onClick: (LocalDate) -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val scale by animateFloatAsState(if (selected) 1f else 0.92f, tween(180), label = "cell")

    Box(
        modifier = modifier.aspectRatio(1f).padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (date == null) return
        // Busier days glow more: fill alpha scales with the day's share of the
        // month's max file count.
        val heat = if (count > 0) (0.10f + 0.30f * (count.toFloat() / maxCount.coerceAtLeast(1)))
            else 0f
        val bg = when {
            selected -> primary
            count > 0 -> primary.copy(alpha = heat)
            else -> Color.Transparent
        }
        val fg = when {
            selected -> MaterialTheme.colorScheme.onPrimary
            today -> MaterialTheme.colorScheme.primary
            count > 0 -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        }
        val border = if (today && !selected)
            Modifier.border(1.4.dp, primary, CircleShape) else Modifier
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .then(border)
                .background(bg)
                .clickable { onClick(date) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("${date.dayOfMonth}", color = fg,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected || today || count > 0) FontWeight.Bold
                    else FontWeight.Normal)
            if (count > 0) {
                Text(if (count > 99) "99+" else "$count",
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall
                        .copy(fontSize = 8.sp),
                    fontWeight = FontWeight.Bold)
            } else {
                Spacer(Modifier.height(9.dp))
            }
        }
    }
}
