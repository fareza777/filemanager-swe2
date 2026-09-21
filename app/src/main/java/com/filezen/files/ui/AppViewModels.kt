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
    val accent = c.settings.accent.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeAccent.TEAL)
    val amoled = c.settings.amoled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
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
    /** Restore the N most recently trashed entries (Undo for the trash snackbar). */
    fun undoTrash(count: Int) = viewModelScope.launch {
        val rows = c.db.trash().all().first().take(count)
        rows.forEach { c.trash.restore(it) }
    }
    fun opDeleteForever(paths: List<String>) {
        c.ops.launch(OpKind.DELETE, "Deleting", paths, null) { cb ->
            c.fileEngine.delete(paths.map { File(it) }, cb)
        }
    }
    fun opConvert(path: String, target: com.filezen.files.core.convert.ConvertEngine.Target) {
        c.ops.launch(OpKind.CONVERT, "Converting to ${target.label}", listOf(path), null) {
            val r = com.filezen.files.core.convert.ConvertEngine.convert(File(path), target)
            OpSummary(OpKind.CONVERT, listOf(ItemResult(path, r.output.path, ItemStatus.DONE,
                "saved ${com.filezen.files.core.model.formatSize(r.bytesSaved)}")))
        }
    }
    fun opCompressImage(path: String) {
        c.ops.launch(OpKind.COMPRESS, "Compressing image", listOf(path), null) {
            val r = com.filezen.files.core.convert.ConvertEngine.compressImage(File(path))
            OpSummary(OpKind.COMPRESS, listOf(ItemResult(path, r.output.path, ItemStatus.DONE,
                if (r.bytesSaved > 0) "saved ${com.filezen.files.core.model.formatSize(r.bytesSaved)}"
                else "already optimal")))
        }
    }
    /** Batch recompress: one operation row, per-file progress. */
    fun opCompressImages(paths: List<String>) {
        c.ops.launch(OpKind.COMPRESS, "Compressing ${paths.size} photos", paths, null) { cb ->
            val results = mutableListOf<ItemResult>()
            paths.forEachIndexed { i, p ->
                cb(OpProgress(i, paths.size, File(p).name, 0, 0))
                try {
                    val r = com.filezen.files.core.convert.ConvertEngine.compressImage(File(p))
                    results += ItemResult(p, r.output.path, ItemStatus.DONE,
                        if (r.bytesSaved > 0) "saved ${com.filezen.files.core.model.formatSize(r.bytesSaved)}"
                        else "already optimal")
                } catch (e: Exception) {
                    results += ItemResult(p, null, ItemStatus.FAILED, e.message)
                }
            }
            cb(OpProgress(paths.size, paths.size, "", 0, 0))
            OpSummary(OpKind.COMPRESS, results)
        }
    }
    /** Batch convert: one operation row covering all files. */
    fun opConvertMany(paths: List<String>, target: com.filezen.files.core.convert.ConvertEngine.Target) {
        c.ops.launch(OpKind.CONVERT, "Converting to ${target.label}", paths, null) { cb ->
            val results = mutableListOf<ItemResult>()
            paths.forEachIndexed { i, p ->
                cb(OpProgress(i, paths.size, File(p).name, 0, 0))
                try {
                    val r = com.filezen.files.core.convert.ConvertEngine.convert(File(p), target)
                    results += ItemResult(p, r.output.path, ItemStatus.DONE,
                        "saved ${com.filezen.files.core.model.formatSize(r.bytesSaved)}")
                } catch (e: Exception) {
                    results += ItemResult(p, null, ItemStatus.FAILED, e.message)
                }
            }
            cb(OpProgress(paths.size, paths.size, "", 0, 0))
            OpSummary(OpKind.CONVERT, results)
        }
    }
    fun opZip(paths: List<String>, dest: File) {
        c.ops.launch(OpKind.ZIP, "Compressing", paths, dest.path) { cb ->
            val zipName = if (paths.size == 1)
                File(paths[0]).nameWithoutExtension + ".zip" else "archive-${System.currentTimeMillis()}.zip"
            ZipEngine(c.fileEngine).compress(
                paths.map { File(it) },
                c.fileEngine.uniqueName(dest, zipName), onProgress = cb,
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

    /**
     * Safe Share: write metadata-free copies of every supported file next to
     * the originals ("<name>-cleaned.<ext>"). Unsupported files are reported.
     */
    fun opCleanMetadata(paths: List<String>) {
        c.ops.launch(OpKind.CLEAN, "Removing metadata", paths, null) { cb ->
            val results = mutableListOf<ItemResult>()
            paths.forEachIndexed { i, p ->
                cb(OpProgress(i, paths.size, File(p).name, 0, 0))
                val src = File(p)
                if (!com.filezen.files.core.privacy.MetadataCleaner.isCleanable(p)) {
                    results += ItemResult(p, null, ItemStatus.SKIPPED, "nothing strippable")
                    return@forEachIndexed
                }
                try {
                    val dst = c.fileEngine.uniqueName(
                        src.parentFile ?: src,
                        com.filezen.files.core.privacy.MetadataCleaner.cleanedName(src),
                    )
                    val ok = com.filezen.files.core.privacy.MetadataCleaner.clean(src, dst)
                    results += ItemResult(
                        p, if (ok) dst.path else null,
                        if (ok) ItemStatus.DONE else ItemStatus.FAILED,
                        if (ok) "clean copy saved" else "clean failed",
                    )
                } catch (e: Exception) {
                    results += ItemResult(p, null, ItemStatus.FAILED, e.message)
                }
            }
            cb(OpProgress(paths.size, paths.size, "", 0, 0))
            OpSummary(OpKind.CLEAN, results)
        }
    }
}

/** Feeds the Privacy / Safe-share screen with files that can carry metadata. */
class PrivacyViewModel : ViewModel() {
    private val c get() = FileZenApp.c

    private val _files = MutableStateFlow<List<FileEntry>>(emptyList())
    val files: StateFlow<List<FileEntry>> = _files
    private val _scanning = MutableStateFlow(true)
    val scanning: StateFlow<Boolean> = _scanning

    init { refresh() }

    fun refresh() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _scanning.value = true
            _files.value = c.db.fileIndex().allRows()
                .filter { com.filezen.files.core.privacy.MetadataCleaner.hasAnySupport(it.path) }
                .sortedByDescending { it.lastModified }
                .take(300)
                .map { FileEntry.from(File(it.path)) }
            _scanning.value = false
        }
    }
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

    private val _allRecent = MutableStateFlow<List<FileEntry>>(emptyList())
    val allRecent: StateFlow<List<FileEntry>> = _allRecent

    val trashSize = c.db.trash().totalSize()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    private val _dupWasted = MutableStateFlow(0L)
    val dupWasted: StateFlow<Long> = _dupWasted
    private var dupScanned = false

    init {
        refresh()
        // Duplicate scan is heavy — run once per VM, off the UI path.
        viewModelScope.launch(Dispatchers.IO) {
            _dupWasted.value = runCatching {
                StorageAnalyzer.duplicates(Environment.getExternalStorageDirectory(), cache = c.db.hashCache())
                    .sumOf { it.wasted }
            }.getOrDefault(0L)
            dupScanned = true
        }
    }

    private fun recentRoots(): List<File> = listOf(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
    ).filter { it.exists() }

    fun refresh() {
        viewModelScope.launch {
            _lastPath.value = c.settings.lastBrowsePath.first()
            _usage.value = StorageAnalyzer.usage()
            _recent.value = withContext(Dispatchers.IO) {
                recentViaIndex(40) ?: Scanner.recent(recentRoots(), limit = 40)
            }
        }
    }

    fun loadAllRecent() {
        viewModelScope.launch {
            _allRecent.value = withContext(Dispatchers.IO) {
                recentViaIndex(2000) ?: Scanner.recent(recentRoots(), limit = 500)
            }
        }
    }

    /** Index-backed "every file on storage, newest first" — null when the index
     *  hasn't finished building yet so callers fall back to a flat scan. */
    private suspend fun recentViaIndex(limit: Int): List<FileEntry>? {
        if (!c.fileIndex.ready.value) return null
        val rows = c.fileIndex.recent(limit)
        if (rows.isEmpty()) return null
        return rows.map { FileEntry(it.path, it.name, false, it.size, it.lastModified,
            FileType.valueOf(it.type)) }
    }

    /** Optimistically drop entries after they're moved/deleted. */
    fun dropRecent(paths: Collection<String>) {
        _allRecent.value = _allRecent.value.filter { it.path !in paths }
        _recent.value = _recent.value.filter { it.path !in paths }
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
    val folderSizes = c.settings.folderSizes.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val folderColors = c.settings.folderColors.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    private val _dirSizes = MutableStateFlow<Map<String, Long>>(emptyMap())
    val dirSizes: StateFlow<Map<String, Long>> = _dirSizes
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
            _dirSizes.value = emptyMap()
            if (folderSizes.value) {
                val dirs = list.filter { it.isDirectory }.take(60)
                for (d in dirs) {
                    val sz = withContext(Dispatchers.IO) {
                        runCatching { c.fileEngine.sizeOf(File(d.path)) }.getOrDefault(0L)
                    }
                    _dirSizes.value = _dirSizes.value + (d.path to sz)
                }
            }
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

    init {
        scan()
        // Realtime inbox: file writes under watched roots trigger a debounced rescan.
        c.inbox.startWatching(viewModelScope) { scan() }
        // Watched roots can change at any time (dialog add/remove, quick chips) —
        // rebuild the FileObserver set and rescan whenever they do.
        viewModelScope.launch {
            roots.collect {
                c.inbox.startWatching(viewModelScope) { scan() }
                scan()
            }
        }
    }

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

    // ---- Auto-tidy: rules first, then sensible type defaults ----

    val rules = c.db.sortRules().all()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    data class TidyPlanItem(val path: String, val name: String, val dest: String, val via: String)

    fun autoTidyPlan(paths: List<String>): Pair<List<TidyPlanItem>, List<String>> {
        val activeRules = rules.value.filter { it.enabled }
        val planned = mutableListOf<TidyPlanItem>()
        val skipped = mutableListOf<String>()
        paths.forEach { p ->
            val f = File(p)
            val e = FileEntry.from(f)
            val rule = activeRules.firstOrNull { SortRuleEngine.matches(it, e) }
            val dest = rule?.targetPath ?: defaultTidyDest(e.type)
            if (dest == null) skipped += p
            else planned += TidyPlanItem(
                path = p, name = f.name, dest = dest,
                via = when {
                    rule != null -> "Rule: ${SortRuleEngine.describe(rule)}"
                    defaultTidyDest(e.type).endsWith("FileZen") -> "Catch-all folder"
                    else -> "By type"
                },
            )
        }
        return planned to skipped
    }

    /** Folders that make sense as automatic destinations by file type.
     *  Unknown/archive/binary types land in Documents/FileZen so nothing is
     *  ever left behind — every file always gets a home. */
    private fun defaultTidyDest(t: FileType): String {
        val dir = when (t) {
            FileType.IMAGE -> Environment.DIRECTORY_PICTURES
            FileType.VIDEO -> Environment.DIRECTORY_MOVIES
            FileType.AUDIO -> Environment.DIRECTORY_MUSIC
            FileType.DOCUMENT, FileType.PDF, FileType.TEXT -> Environment.DIRECTORY_DOCUMENTS
            else -> null
        }
        if (dir != null) return Environment.getExternalStoragePublicDirectory(dir).absolutePath
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "FileZen").absolutePath
    }

    /** Auto-tidy: move each planned file to its resolved destination, mark tidy. */
    fun autoTidy(plan: List<TidyPlanItem>) {
        viewModelScope.launch {
            val allResults = mutableListOf<ItemResult>()
            plan.groupBy { it.dest }.forEach { (dest, items) ->
                val d = File(dest)
                if (!d.exists()) d.mkdirs()
                val summary = c.ops.runSync(OpKind.TIDY, "Auto-tidying", items.map { it.path }, dest) { cb ->
                    c.fileEngine.move(items.map { File(it.path) }, d, ConflictPolicy.KEEP_BOTH, cb)
                }
                allResults += summary.results
                summary.results.filter { it.status == ItemStatus.DONE && it.target != null }.forEach { r ->
                    c.inbox.fileMoved(r.source, File(r.target!!))
                    _selection.value -= r.source
                }
            }
            // report a single combined snackbar instead of the last group's
            c.ops.postSummary(OpSummary(OpKind.TIDY, allResults))
        }
    }
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
    val autoSort = c.settings.autoSort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun analyze() {
        if (_analyzing.value) return
        _analyzing.value = true
        viewModelScope.launch {
            try {
                val root = Environment.getExternalStorageDirectory()
                _usage.value = StorageAnalyzer.usage(root)
                _categories.value = StorageAnalyzer.categories(root)
                _large.value = StorageAnalyzer.largeFiles(root)
                _dups.value = StorageAnalyzer.duplicates(root, cache = c.db.hashCache())
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

    fun addRule(matchType: String, pattern: String, target: String, sourcePath: String? = null) =
        viewModelScope.launch {
            File(target).mkdirs()
            c.db.sortRules().insert(SortRule(
                matchType = matchType, pattern = pattern,
                targetPath = target, sourcePath = sourcePath))
        }
    fun deleteRule(r: SortRule) = viewModelScope.launch { c.db.sortRules().delete(r) }
    fun toggleRule(r: SortRule, enabled: Boolean) = viewModelScope.launch { c.db.sortRules().setEnabled(r.id, enabled) }
    fun setAutoSort(v: Boolean) = viewModelScope.launch { c.settings.setAutoSort(v) }

    /** Apply every enabled rule to existing files: each watched inbox root plus
     *  each rule's sourcePath folder gets swept once. Moves are logged. */
    fun runRulesNow() = viewModelScope.launch {
        val rules = c.db.sortRules().enabled()
        if (rules.isEmpty()) return@launch
        val dirs = LinkedHashSet<File>()
        dirs += c.inbox.roots()
        rules.mapNotNull { it.sourcePath }.forEach { dirs += File(it) }
        val engine = SortRuleEngine
        for (dir in dirs) {
            dir.listFiles()?.filter { it.isFile && !it.isHidden }?.forEach { f ->
                val e = FileEntry.from(f)
                val rule = rules.firstOrNull { engine.matches(it, e) } ?: return@forEach
                val target = File(rule.targetPath)
                if (target.absolutePath == f.parentFile?.absolutePath) return@forEach
                c.fileEngine.move(listOf(f), target, ConflictPolicy.KEEP_BOTH)
            }
        }
        c.inbox.scan()
    }
}

class SearchViewModel : ViewModel() {
    private val c get() = FileZenApp.c

    private val _filter = MutableStateFlow(SearchFilter())
    val filter: StateFlow<SearchFilter> = _filter

    private val _results = MutableStateFlow<List<FileEntry>>(emptyList())
    val results: StateFlow<List<FileEntry>> = _results

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    // Content (semantic inside-files) mode
    private val _mode = MutableStateFlow("name") // name | content
    val mode: StateFlow<String> = _mode
    private val _hits = MutableStateFlow<List<com.filezen.files.core.semsearch.ContentIndex.Hit>>(emptyList())
    val hits: StateFlow<List<com.filezen.files.core.semsearch.ContentIndex.Hit>> = _hits
    val indexProgress = c.contentIndex.progress
    val indexedFiles = c.contentIndex.indexedFiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val recentQueries = c.settings.recentQueries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var job: Job? = null
    private var cJob: Job? = null

    fun setFilter(f: SearchFilter) { _filter.value = f }

    fun setMode(m: String) {
        _mode.value = m
        if (m == "content") {
            // Kick an incremental index refresh if nothing is indexed yet.
            viewModelScope.launch { c.contentIndex.sync() }
        }
    }

    fun rebuildIndex() { viewModelScope.launch { c.contentIndex.rebuildAll() } }

    fun contentSearch(q: String) {
        cJob?.cancel()
        if (q.isBlank()) { _hits.value = emptyList(); return }
        _searching.value = true
        cJob = viewModelScope.launch {
            try { _hits.value = c.contentIndex.search(q.trim()) }
            finally { _searching.value = false }
        }
    }

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
                if (c.fileIndex.ready.value) {
                    // Instant path: query the persisted disk index.
                    val rows = c.fileIndex.search(f.query.takeIf { it.isNotBlank() } ?: "")
                    _results.value = rows.asSequence()
                        .map { FileEntry(it.path, it.name, false, it.size, it.lastModified,
                            FileType.valueOf(it.type)) }
                        .filter { com.filezen.files.core.scan.Scanner.matches(it, f) }
                        .sortedByDescending { it.lastModified }
                        .take(2000).toList()
                } else {
                    Scanner.scan(roots, f).collect { batch ->
                        _results.value = (_results.value + batch)
                            .sortedByDescending { it.lastModified }
                            .take(2000)
                    }
                }
            } finally { _searching.value = false }
        }
    }

    fun cancel() { job?.cancel(); _searching.value = false }
}

/** Photo cleaner (ClearLens port): scans images, groups dupes/similar/low-quality. */
class CleanerViewModel : ViewModel() {
    private val c get() = FileZenApp.c

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val _progress = MutableStateFlow("")
    val progress: StateFlow<String> = _progress
    private val _report = MutableStateFlow<com.filezen.files.core.cleaner.PhotoLens.ScanReport?>(null)
    val report: StateFlow<com.filezen.files.core.cleaner.PhotoLens.ScanReport?> = _report

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected

    private var scanJob: Job? = null

    init { scan() }

    fun scan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.Default) {
            _scanning.value = true
            _report.value = null
            _selected.value = emptySet()
            try {
                _progress.value = "Finding photos…"
                val rows = c.db.fileIndex().allRows()
                    .filter { it.type == "IMAGE" && File(it.path).isFile }
                _progress.value = "Analysing ${rows.size} photos…"
                val favs = c.db.favorites().all().first().map { it.path }.toSet()
                val photos = mutableListOf<com.filezen.files.core.cleaner.PhotoLens.LensPhoto>()
                var failed = 0
                rows.forEachIndexed { i, row ->
                    if (i % 20 == 0) _progress.value = "Analysing ${i}/${rows.size}"
                    val f = File(row.path)
                    val bmp = com.filezen.files.core.cleaner.PhotoLens.decodeThumbnail(f)
                    val m = bmp?.let { com.filezen.files.core.cleaner.PhotoLens.metrics(it) }
                    bmp?.recycle()
                    if (m == null) failed++
                    photos += com.filezen.files.core.cleaner.PhotoLens.LensPhoto(
                        path = row.path, name = row.name, sizeBytes = row.size,
                        lastModified = row.lastModified,
                        isFavourite = row.path in favs, metrics = m,
                    )
                }
                _progress.value = "Grouping…"
                val groups = mutableListOf<com.filezen.files.core.cleaner.PhotoLens.FindingGroup>()
                groups += com.filezen.files.core.cleaner.PhotoLens.findExactDuplicates(photos)
                // Exact-duplicate members are removed from the similar pass.
                val exactMembers = groups.flatMap { g -> g.photos.map { it.path } }.toSet()
                val rest = photos.filter { it.path !in exactMembers }
                groups += com.filezen.files.core.cleaner.PhotoLens.findSimilar(rest)
                val similarMembers = groups.flatMap { g -> g.photos.map { it.path } }.toSet()
                groups += rest.filter { it.path !in similarMembers }
                    .mapNotNull { com.filezen.files.core.cleaner.PhotoLens.classifyLowQuality(it) }
                _report.value = com.filezen.files.core.cleaner.PhotoLens.ScanReport(
                    scanned = photos.size, failed = failed, groups = groups,
                )
                // Pre-select recommendations (exact + similar only; quality starts unselected).
                _selected.value = groups.flatMap { it.recommendedDeletePaths }.toSet()
            } finally { _scanning.value = false }
        }
    }

    fun toggle(path: String) { _selected.value = _selected.value.let { if (path in it) it - path else it + path } }
    fun selectRecommended() {
        _selected.value = _report.value?.groups?.flatMap { it.recommendedDeletePaths }?.toSet() ?: emptySet()
    }
    fun clearSelection() { _selected.value = emptySet() }
}

/** Folder-pair sync (OpenSync port). */
class SyncViewModel : ViewModel() {
    private val c get() = FileZenApp.c

    val pairs: StateFlow<List<SyncPair>> =
        c.db.syncPairs().all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connections = c.settings.remoteConnections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _running = MutableStateFlow<Set<Long>>(emptySet())
    val running: StateFlow<Set<Long>> = _running
    private val _progressMsg = MutableStateFlow("")
    val progressMsg: StateFlow<String> = _progressMsg

    fun addPair(pair: SyncPair) = viewModelScope.launch { c.db.syncPairs().insert(pair) }
    fun updatePair(pair: SyncPair) = viewModelScope.launch { c.db.syncPairs().update(pair) }
    fun removePair(pair: SyncPair) = viewModelScope.launch {
        c.db.syncPairs().delete(pair)
        c.db.syncState().clear(pair.id)
    }

    fun runPair(pair: SyncPair) {
        if (pair.id in _running.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _running.value += pair.id
            try {
                c.syncRunner.run(pair) { msg, _, _ -> _progressMsg.value = msg }
            } catch (_: Exception) {
                // Status is persisted by SyncRunner.
            } finally {
                _running.value -= pair.id
                _progressMsg.value = ""
            }
        }
    }
}
