package com.filezen.files.ui

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filezen.files.FileZenApp
import com.filezen.files.core.fileops.*
import com.filezen.files.core.inbox.InboxRepository
import com.filezen.files.core.model.*
import com.filezen.files.core.scan.*
import com.filezen.files.data.db.*
import com.filezen.files.data.prefs.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

/** Shared app-level state: clipboard, multi-folder basket, running op. */
class AppViewModel : ViewModel() {
    private val c get() = FileZenApp.c

    val theme = c.settings.theme.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
    val adFree = c.billing.adFree
    val runningOp = c.ops.current
    val lastSummary = c.ops.lastSummary

    // ---- selection basket (files picked across folders, e.g. for tidy) ----
    private val _basket = MutableStateFlow<Set<String>>(emptySet())
    val basket: StateFlow<Set<String>> = _basket
    fun basketAdd(path: String) { _basket.value += path }
    fun basketRemove(path: String) { _basket.value -= path }
    fun basketClear() { _basket.value = emptySet() }

    // ---- clipboard ----
    data class Clipboard(val paths: List<String>, val cut: Boolean)
    private val _clipboard = MutableStateFlow<Clipboard?>(null)
    val clipboard: StateFlow<Clipboard?> = _clipboard
    fun setClipboard(paths: List<String>, cut: Boolean) { _clipboard.value = Clipboard(paths, cut) }

    val favorites: StateFlow<List<Favorite>> =
        c.db.favorites().all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addFavorite(path: String, label: String) = viewModelScope.launch {
        c.db.favorites().insert(Favorite(path, label, System.currentTimeMillis(), favorites.value.size))
    }
    fun removeFavorite(path: String) = viewModelScope.launch { c.db.favorites().remove(path) }
    fun isFavorite(path: String): Boolean = favorites.value.any { it.path == path }

    // ---- file ops wrappers (write to history + show progress) ----
    fun opCopy(paths: List<String>, dest: File, policy: ConflictPolicy) {
        c.ops.launch(OpKind.COPY, "Copying", paths, dest.path) { cb ->
            c.fileEngine.copy(paths.map { File(it) }, dest, policy, cb)
        }
    }
    fun opMove(paths: List<String>, dest: File, policy: ConflictPolicy) {
        c.ops.launch(OpKind.MOVE, "Moving", paths, dest.path) { cb ->
            c.fileEngine.move(paths.map { File(it) }, dest, policy, cb)
        }
    }
    fun opTrash(paths: List<String>) {
        c.ops.launch(OpKind.TRASH, "Moving to trash", paths, null) {
            c.trash.trash(paths.map { File(it) })
        }
    }
    fun opDeleteForever(paths: List<String>) {
        c.ops.launch(OpKind.DELETE, "Deleting", paths, null) { cb ->
            c.fileEngine.delete(paths.map { File(it) }, cb)
        }
    }
    fun opZip(paths: List<String>, dest: File) {
        c.ops.launch(OpKind.ZIP, "Compressing", paths, dest.path) { cb ->
            val zipName = if (paths.size == 1)
                File(paths[0]).nameWithoutExtension + ".zip" else "archive-${System.currentTimeMillis()}.zip"
            ZipEngine(c.fileEngine).compress(
                paths.map { File(it) },
                c.fileEngine.uniqueName(dest, zipName), cb,
            ).let { s ->
                OpSummary(OpKind.ZIP, listOf(s))
            }
        }
    }
    fun opUnzip(path: String, dest: File, policy: ConflictPolicy) {
        c.ops.launch(OpKind.UNZIP, "Extracting", listOf(path), dest.path) { cb ->
            ZipEngine(c.fileEngine).extract(File(path), dest, policy, cb)
        }
    }
    fun cancelOp() = c.ops.cancel()
    fun dismissSummary() { FileZenApp.c.ops.clearSummary() }
}

class HomeViewModel : ViewModel() {
    private val c get() = FileZenApp.c
    private val _recent = MutableStateFlow<List<FileEntry>>(emptyList())
    val recent: StateFlow<List<FileEntry>> = _recent
    private val _lastPath = MutableStateFlow<String?>(null)
    val lastPath: StateFlow<String?> = _lastPath
    private val _usage = MutableStateFlow<StorageUsage?>(null)
    val usage: StateFlow<StorageUsage?> = _usage
    val favorites = c.db.favorites().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val untidyCount = c.db.inbox().untidy()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _lastPath.value = c.settings.lastBrowsePath.first()
            _usage.value = StorageAnalyzer.usage()
            val roots = listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            ).filter { it.exists() }
            _recent.value = withContext(Dispatchers.IO) {
                Scanner.recent(roots, limit = 40)
            }
        }
    }
}

class BrowseViewModel : ViewModel() {
    private val c get() = FileZenApp.c

    private val _path = MutableStateFlow(Environment.getExternalStorageDirectory().absolutePath)
    val path: StateFlow<String> = _path

    private val _entries = MutableStateFlow<List<FileEntry>>(emptyList())
    val entries: StateFlow<List<FileEntry>> = _entries

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection

    private var lastSelectedIndex = -1

    val viewMode = c.settings.viewMode.stateIn(viewModelScope, SharingStarted.Eagerly, ViewMode.LIST)
    val sortField = c.settings.sortField.stateIn(viewModelScope, SharingStarted.Eagerly, SortField.NAME)
    val sortAsc = c.settings.sortAsc.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val showHidden = c.settings.showHidden.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val safRoots = c.settings.safRoots.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    private val _volumes = MutableStateFlow<List<Volume>>(emptyList())
    val volumes: StateFlow<List<VolumesShim>> = _volumes.map { list -> list.map { VolumesShim(it.name, it.root.path, it.removable) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var navigated = false

    init {
        viewModelScope.launch {
            c.settings.lastBrowsePath.first()?.let { if (!navigated && File(it).exists()) _path.value = it }
        }
        viewModelScope.launch {
            combine(showHidden, sortField, sortAsc) { a, b, s -> Triple(a, b, s) }.collect {
                load(_path.value)
            }
        }
        viewModelScope.launch {
            safRoots.collect { r -> _volumes.value = Volumes.detect(FileZenApp.instance, r) }
        }
    }

    fun navigate(p: String) {
        navigated = true
        if (p == _path.value) { load(p); return }
        _path.value = p
        _selection.value = emptySet()
        lastSelectedIndex = -1
        load(p)
        viewModelScope.launch { c.settings.setLastBrowsePath(p) }
    }

    fun refresh() = load(_path.value)

    private fun load(p: String) {
        _loading.value = true
        viewModelScope.launch {
            val hidden = showHidden.value
            val list = withContext(Dispatchers.IO) { Scanner.listDir(p, hidden) }
            _entries.value = sort(list, sortField.value, sortAsc.value)
            _loading.value = false
        }
    }

    private fun sort(l: List<FileEntry>, f: SortField, asc: Boolean): List<FileEntry> {
        val cmp: Comparator<FileEntry> = when (f) {
            SortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortField.SIZE -> compareBy { it.size }
            SortField.DATE -> compareBy { it.lastModified }
            SortField.TYPE -> compareBy({ it.type.name }, { it.name.lowercase() })
        }
        return l.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.then(if (asc) cmp else cmp.reversed()))
    }

    fun toggleSelect(path: String) {
        _selection.value = if (path in _selection.value) _selection.value - path else _selection.value + path
        lastSelectedIndex = _entries.value.indexOfFirst { it.path == path }
    }

    fun longPressSelect(path: String) {
        val idx = _entries.value.indexOfFirst { it.path == path }
        if (lastSelectedIndex >= 0 && idx >= 0 && _selection.value.isNotEmpty()) {
            // range select between anchor and this
            val (a, b) = if (lastSelectedIndex < idx) lastSelectedIndex to idx else idx to lastSelectedIndex
            val range = _entries.value.subList(a, b + 1).map { it.path }
            _selection.value = _selection.value + range
        } else {
            toggleSelect(path)
        }
    }

    fun selectAll() { _selection.value = _entries.value.map { it.path }.toSet() }
    fun clearSelection() { _selection.value = emptySet(); lastSelectedIndex = -1 }

    fun setViewMode(v: ViewMode) = viewModelScope.launch { c.settings.setViewMode(v) }
    fun setSort(f: SortField, asc: Boolean) = viewModelScope.launch {
        c.settings.setSortField(f); c.settings.setSortAsc(asc)
    }

    fun saveScroll(i: Int, o: Int) = viewModelScope.launch { c.settings.saveScroll(_path.value, i, o) }
    fun scrollFor(p: String) = c.settings.scrollFor(p)

    fun addSafRoot(uri: String) = viewModelScope.launch { c.settings.setSafRoots(safRoots.value + uri) }
    fun removeSafRoot(uri: String) = viewModelScope.launch { c.settings.setSafRoots(safRoots.value - uri) }
}

data class VolumesShim(val name: String, val path: String, val removable: Boolean)

class InboxViewModel : ViewModel() {
    private val c get() = FileZenApp.c

    val untidy = c.db.inbox().untidy()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val tidy = c.db.inbox().tidy()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val roots = c.settings.inboxRoots
        .map { saved -> if (saved.isEmpty()) setOf(c.inbox.defaultRoot().absolutePath) else saved }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection

    init { scan() }

    fun scan() {
        if (_scanning.value) return
        _scanning.value = true
        viewModelScope.launch {
            try { c.inbox.scan() } finally { _scanning.value = false }
        }
    }

    fun toggleSelect(path: String) {
        _selection.value = if (path in _selection.value) _selection.value - path else _selection.value + path
    }
    fun clearSelection() { _selection.value = emptySet() }
    fun selectAllUntidy() { _selection.value = untidy.value.map { it.path }.toSet() }

    fun markTidy(paths: List<String>, tidyFlag: Boolean = true) = viewModelScope.launch {
        c.inbox.markTidy(paths, tidyFlag)
        _selection.value -= paths.toSet()
    }

    fun fileMoved(from: String, to: File) = viewModelScope.launch { c.inbox.fileMoved(from, to) }

    /** Inbox "Rapikan": move files into [dest] (optional per-path new name), then mark tidy. */
    fun tidyMove(paths: List<String>, dest: File, newName: String? = null) {
        viewModelScope.launch {
            val summary = c.ops.runSync(OpKind.TIDY, "Tidying", paths, dest.path) { cb ->
                c.fileEngine.move(paths.map { File(it) }, dest, ConflictPolicy.KEEP_BOTH, cb)
            }
            val okPairs = summary.results.filter { it.status == ItemStatus.DONE && it.target != null }
            okPairs.forEach { r ->
                c.inbox.fileMoved(r.source, File(r.target!!))
                _selection.value -= r.source
            }
            // optional single-file rename right after the move
            if (newName != null && okPairs.size == 1) {
                val t = okPairs.first().target!!
                val rr = c.fileEngine.rename(File(t), newName)
                if (rr.status == ItemStatus.DONE && rr.target != null) {
                    c.inbox.fileMoved(t, File(rr.target))
                }
            }
        }
    }

    fun addRoot(path: String) = viewModelScope.launch { c.inbox.setRoots(roots.value + path) }
    fun removeRoot(path: String) = viewModelScope.launch { c.inbox.setRoots(roots.value - path) }
    fun resetRoots() = viewModelScope.launch { c.inbox.setRoots(emptySet()) }
}

class StorageViewModel : ViewModel() {
    private val c get() = FileZenApp.c

    private val _usage = MutableStateFlow<StorageUsage?>(null)
    val usage: StateFlow<StorageUsage?> = _usage

    private val _categories = MutableStateFlow<List<CategorySize>>(emptyList())
    val categories: StateFlow<List<CategorySize>> = _categories

    private val _large = MutableStateFlow<List<LargeFile>>(emptyList())
    val large: StateFlow<List<LargeFile>> = _large

    private val _dups = MutableStateFlow<List<DuplicateGroup>>(emptyList())
    val dups: StateFlow<List<DuplicateGroup>> = _dups

    private val _analyzing = MutableStateFlow(false)
    val analyzing: StateFlow<Boolean> = _analyzing

    val trashEntries = c.db.trash().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val trashSize = c.db.trash().totalSize()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val rules = c.db.sortRules().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val history = c.db.operations().recent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun analyze() {
        if (_analyzing.value) return
        _analyzing.value = true
        viewModelScope.launch {
            try {
                val root = Environment.getExternalStorageDirectory()
                _usage.value = StorageAnalyzer.usage(root)
                _categories.value = StorageAnalyzer.categories(root)
                _large.value = StorageAnalyzer.largeFiles(root)
                _dups.value = StorageAnalyzer.duplicates(root)
            } finally { _analyzing.value = false }
        }
    }

    fun restoreTrash(id: Long) = viewModelScope.launch {
        c.db.trash().get(id)?.let { c.trash.restore(it) }
    }
    fun purgeTrash(id: Long) = viewModelScope.launch {
        c.db.trash().get(id)?.let { c.trash.purge(it) }
    }
    fun purgeAll() = viewModelScope.launch { c.trash.purgeAll() }

    fun addRule(matchType: String, pattern: String, target: String) = viewModelScope.launch {
        c.db.sortRules().insert(SortRule(matchType = matchType, pattern = pattern, targetPath = target))
    }
    fun deleteRule(r: SortRule) = viewModelScope.launch { c.db.sortRules().delete(r) }
    fun toggleRule(r: SortRule, enabled: Boolean) = viewModelScope.launch { c.db.sortRules().setEnabled(r.id, enabled) }
}

class SearchViewModel : ViewModel() {
    private val c get() = FileZenApp.c

    private val _filter = MutableStateFlow(SearchFilter())
    val filter: StateFlow<SearchFilter> = _filter

    private val _results = MutableStateFlow<List<FileEntry>>(emptyList())
    val results: StateFlow<List<FileEntry>> = _results

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    val recentQueries = c.settings.recentQueries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var job: Job? = null

    fun setFilter(f: SearchFilter) { _filter.value = f }

    fun search(roots: List<File>) {
        job?.cancel()
        _results.value = emptyList()
        val f = _filter.value
        if (f.query.isBlank() && f.types.isEmpty() && f.minSize == null &&
            f.maxSize == null && f.modifiedAfter == null) return
        _searching.value = true
        job = viewModelScope.launch {
            try {
                if (f.query.isNotBlank()) c.settings.addRecentQuery(f.query)
                Scanner.scan(roots, f).collect { batch ->
                    _results.value = (_results.value + batch)
                        .sortedByDescending { it.lastModified }
                        .take(2000)
                }
            } finally { _searching.value = false }
        }
    }

    fun cancel() { job?.cancel(); _searching.value = false }
}
