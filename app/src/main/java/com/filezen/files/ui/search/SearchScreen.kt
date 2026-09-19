package com.filezen.files.ui.search

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.filezen.files.Routes
import com.filezen.files.core.model.FileType
import com.filezen.files.core.scan.SearchFilter
import com.filezen.files.ui.AppViewModel
import com.filezen.files.ui.SearchViewModel
import com.filezen.files.ui.common.EmptyState
import com.filezen.files.ui.common.FileRow
import com.filezen.files.ops.Intents
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    nav: NavController,
    appVm: AppViewModel,
    presetType: String? = null,
    vm: SearchViewModel = viewModel(),
) {
    val filter by vm.filter.collectAsState()
    val results by vm.results.collectAsState()
    val searching by vm.searching.collectAsState()
    val recentQueries by vm.recentQueries.collectAsState()

    var query by remember { mutableStateOf("") }
    var selectedTypes by remember { mutableStateOf(setOf<FileType>()) }
    var minSizeMb by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf("everywhere") } // everywhere / downloads / current
    var selection by remember { mutableStateOf(setOf<String>()) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var showSortPick by remember { mutableStateOf(false) }

    val roots: List<File> = remember(scope) {
        when (scope) {
            "downloads" -> listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
            else -> listOf(Environment.getExternalStorageDirectory())
        }
    }

    LaunchedEffect(presetType) {
        presetType?.let { t ->
            runCatching { FileType.valueOf(t) }.getOrNull()?.let { ft ->
                selectedTypes = setOf(ft)
                vm.setFilter(filter.copy(types = setOf(ft)))
                vm.search(listOf(Environment.getExternalStorageDirectory()))
            }
        }
    }

    fun runSearch() {
        val min = minSizeMb.toLongOrNull()?.times(1024 * 1024)
        vm.setFilter(
            filter.copy(
                query = query.trim(), types = selectedTypes, minSize = min,
            )
        )
        vm.search(roots)
    }

    Scaffold(
        topBar = {
            if (selection.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${selection.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Rounded.Close, "Clear") }
                    },
                    actions = {
                        IconButton(onClick = { deleteConfirm = true }) {
                            Icon(Icons.Rounded.Delete, "Delete")
                        }
                        IconButton(onClick = {
                            Intents.share(nav.context, results.filter { it.path in selection })
                        }) { Icon(Icons.Rounded.Share, "Share") }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Search", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, "Back") }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; runSearch() },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = ""; runSearch() }) {
                        Icon(Icons.Rounded.Close, "Clear")
                    }
                },
                placeholder = { Text("File name…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            // Filters
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val typeChips = listOf(
                    FileType.IMAGE to "Images", FileType.VIDEO to "Videos",
                    FileType.AUDIO to "Audio", FileType.DOCUMENT to "Docs",
                    FileType.PDF to "PDF", FileType.APK to "Apps",
                    FileType.ARCHIVE to "Archives",
                )
                typeChips.forEach { (t, label) ->
                    FilterChip(
                        selected = t in selectedTypes,
                        onClick = {
                            selectedTypes = if (t in selectedTypes) selectedTypes - t else selectedTypes + t
                            runSearch()
                        },
                        label = { Text(label) },
                    )
                }
                FilterChip(
                    selected = scope == "downloads",
                    onClick = {
                        scope = if (scope == "downloads") "everywhere" else "downloads"
                        runSearch()
                    },
                    label = { Text("Downloads only") },
                )
            }
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = minSizeMb, onValueChange = { minSizeMb = it.filter { c -> c.isDigit() }; runSearch() },
                    label = { Text("Min size (MB)") }, singleLine = true,
                    modifier = Modifier.width(140.dp),
                )
            }

            if (query.isBlank() && recentQueries.isNotEmpty() && results.isEmpty()) {
                Text("Recent searches", modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    recentQueries.forEach { q ->
                        SuggestionChip(onClick = { query = q; runSearch() }, label = { Text(q) })
                    }
                }
            }

            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())

            when {
                results.isEmpty() && !searching && (query.isNotBlank() || selectedTypes.isNotEmpty()) ->
                    EmptyState(Icons.Rounded.SearchOff, "No matches", "Try a different name or remove some filters.")
                results.isEmpty() ->
                    EmptyState(Icons.Rounded.ManageSearch, "Find anything",
                        "Search by name, or tap a type above to browse all files of that kind.")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.path }) { e ->
                        FileRow(
                            e = e,
                            selected = e.path in selection,
                            onClick = {
                                if (selection.isNotEmpty()) {
                                    selection = if (e.path in selection) selection - e.path else selection + e.path
                                } else if (e.isDirectory) nav.navigate(Routes.folder(e.path))
                                else nav.navigate(Routes.preview(e.path))
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
    }

    if (deleteConfirm) {
        com.filezen.files.ui.common.ConfirmDialog(
            "Move to trash?", "${selection.size} item(s) will be moved to trash.",
            "Move to trash",
            onConfirm = { appVm.opTrash(selection.toList()); selection = emptySet(); deleteConfirm = false },
            onDismiss = { deleteConfirm = false },
        )
    }
}
