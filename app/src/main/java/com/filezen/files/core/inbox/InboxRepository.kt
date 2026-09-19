package com.filezen.files.core.inbox

import android.content.Context
import android.os.Environment
import com.filezen.files.core.fileops.ConflictPolicy
import com.filezen.files.core.fileops.FileEngine
import com.filezen.files.core.fileops.ItemStatus
import com.filezen.files.core.fileops.SortRuleEngine
import com.filezen.files.core.model.FileEntry
import com.filezen.files.data.db.InboxItem
import com.filezen.files.data.db.OperationRecord
import com.filezen.files.data.db.ZenDatabase
import com.filezen.files.data.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Inbox File": surfaces new/unfiled files from watched roots (Downloads by
 * default + user-picked folders) so they can be triaged — previewed, renamed,
 * moved to a favourite folder, or marked tidy — without digging through folders.
 */
class InboxRepository(
    private val ctx: Context,
    private val db: ZenDatabase,
    private val settings: SettingsStore,
    private val fileEngine: FileEngine,
) {
    val items: Flow<List<InboxItem>> get() = db.inbox().all()
    val untidy: Flow<List<InboxItem>> get() = db.inbox().untidy()
    val tidy: Flow<List<InboxItem>> get() = db.inbox().tidy()

    fun defaultRoot(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    suspend fun roots(): List<File> {
        val saved = settings.snapshotInboxRoots()
        val roots = if (saved.isEmpty()) setOf(defaultRoot().absolutePath) else saved
        return roots.map { File(it) }.filter { it.exists() }
    }

    suspend fun setRoots(paths: Set<String>) = settings.setInboxRoots(paths)

    /**
     * Reconcile DB with the filesystem: insert files seen for the first time
     * (untidy), drop rows whose files no longer exist or that left the watched
     * roots while still untidy. Tidy rows survive un-watching a root so their
     * state isn't lost. Recursive, depth-limited, incremental.
     */
    suspend fun scan(maxDepth: Int = 4, maxItems: Int = 5000): Unit = withContext(Dispatchers.IO) {
        val knownItems = db.inbox().all().first()
        val known = knownItems.map { it.path }.toMutableSet()
        val tidyPaths = knownItems.filter { it.tidy }.map { it.path }.toSet()
        val found = mutableSetOf<String>()
        val newItems = mutableListOf<InboxItem>()
        val roots = roots()

        val stack = ArrayDeque<Pair<File, Int>>()
        roots.forEach { stack.add(it to 0) }
        var visited = 0
        while (stack.isNotEmpty() && visited < maxItems) {
            currentCoroutineContext().ensureActive()
            val (dir, depth) = stack.removeLast()
            if (depth > maxDepth) continue
            val kids = try { dir.listFiles() } catch (e: Exception) { null } ?: continue
            for (f in kids) {
                visited++
                if (f.isDirectory) {
                    if (!f.isHidden) stack.add(f to depth + 1)
                    continue
                }
                if (f.isHidden) continue
                found += f.absolutePath
                if (f.absolutePath !in known) {
                    val e = FileEntry.from(f)
                    newItems += InboxItem(
                        path = f.absolutePath, name = f.name, size = e.size,
                        lastModified = f.lastModified(), type = e.type.name,
                        root = dir.absolutePath, firstSeen = System.currentTimeMillis(),
                        tidy = false,
                    )
                }
            }
        }
        // Auto-sort: immediately move newly seen files that match an enabled
        // rule (e.g. "*.pdf → Documents/PDFs"). Moved items are recorded as
        // tidy at their destination, so they never show up as untidy.
        val finalItems = if (newItems.isEmpty()) newItems else autoSort(newItems)
        if (finalItems.isNotEmpty()) db.inbox().insertAll(finalItems)
        val gone = known.filter { it !in found }
            .filter { p -> p !in tidyPaths || !File(p).exists() }
        if (gone.isNotEmpty()) db.inbox().remove(gone)
    }

    /**
     * Applies enabled sort rules to freshly detected inbox files. Files whose
     * rule target can't be written are left untidy in place. Each successful
     * move is logged in the operation history as AUTO_SORT.
     */
    private suspend fun autoSort(items: List<InboxItem>): List<InboxItem> {
        if (!settings.autoSort.first()) return items
        val rules = db.sortRules().enabled()
        if (rules.isEmpty()) return items
        val out = ArrayList<InboxItem>(items.size)
        for (item in items) {
            val e = FileEntry.from(File(item.path))
            val rule = rules.firstOrNull { SortRuleEngine.matches(it, e) }
            val targetDir = rule?.let { File(it.targetPath) }
            if (rule == null || targetDir == null ||
                targetDir.absolutePath == File(item.path).parentFile?.absolutePath) {
                out += item; continue
            }
            val summary = fileEngine.move(
                listOf(File(item.path)), targetDir, ConflictPolicy.KEEP_BOTH)
            val moved = summary.results.firstOrNull()
            if (moved != null && moved.status == ItemStatus.DONE && moved.target != null) {
                val dst = File(moved.target)
                out += item.copy(path = dst.absolutePath, name = dst.name, tidy = true)
                db.operations().insert(OperationRecord(
                    kind = "AUTO_SORT", sources = item.path, targetDir = targetDir.absolutePath,
                    status = "OK", detail = SortRuleEngine.describe(rule),
                    itemCount = 1, timestamp = System.currentTimeMillis()))
            } else {
                out += item
            }
        }
        return out
    }

    /**
     * Watch the top level of each inbox root with FileObserver; after a quiet
     * period the [onChange] callback fires once (debounced — a burst of writes
     * produces one rescan).
     */
    fun startWatching(
        scope: kotlinx.coroutines.CoroutineScope,
        debounceMs: Long = 1500,
        onChange: suspend () -> Unit,
    ) {
        stopWatching()
        val job = kotlinx.coroutines.SupervisorJob()
        watchJob = job
        val watchScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default + job)
        var pending: kotlinx.coroutines.Job? = null
        val fire = {
            synchronized(this) {
                pending?.cancel()
                pending = watchScope.launch {
                    kotlinx.coroutines.delay(debounceMs)
                    onChange()
                }
            }
        }
        watchScope.launch(Dispatchers.IO) {
            val dirs = roots().flatMap { r ->
                listOf(r) + (r.listFiles()?.filter { it.isDirectory && !it.isHidden } ?: emptyList())
            }
            dirs.forEach { d ->
                val obs = object : android.os.FileObserver(d.absolutePath,
                    android.os.FileObserver.CREATE or android.os.FileObserver.MOVED_TO or
                    android.os.FileObserver.DELETE or android.os.FileObserver.MOVED_FROM or
                    android.os.FileObserver.MOVE_SELF) {
                    override fun onEvent(event: Int, path: String?) { fire() }
                }
                obs.startWatching()
                observers += obs
            }
        }
    }

    fun stopWatching() {
        observers.forEach { it.stopWatching() }
        observers.clear()
        watchJob?.cancel(); watchJob = null
    }

    private val observers = mutableListOf<android.os.FileObserver>()
    private var watchJob: kotlinx.coroutines.Job? = null

    suspend fun markTidy(paths: List<String>, tidy: Boolean = true) {
        paths.forEach { db.inbox().setTidy(it, tidy) }
    }

    /** After a file was moved out by "Rapikan", track it at its new path as tidy. */
    suspend fun fileMoved(from: String, to: File) {
        db.inbox().updatePath(from, to.absolutePath, to.name)
        db.inbox().setTidy(to.absolutePath, true)
    }

    suspend fun removeFromInbox(path: String) = db.inbox().remove(path)
}
