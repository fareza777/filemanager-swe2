package com.filezen.files.core.scan

import android.os.Environment
import android.os.FileObserver
import android.os.SystemClock
import com.filezen.files.core.model.FileEntry
import com.filezen.files.data.db.FileIndexEntry
import com.filezen.files.data.db.ZenDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Whole-disk file index in Room. Built once per session in the background;
 * search and the calendar read it instantly instead of walking the tree.
 *
 * After the initial build the "hot" roots (Downloads, Pictures, DCIM,
 * Documents, Movies, Music) are kept fresh two ways: a recursive
 * FileObserver watch fires [refresh] on any create/move/delete/close-write,
 * and callers (Home, Recent, app resume) kick the same incremental delta
 * pass so files missed by the watcher still appear within seconds.
 */
class FileIndex(private val db: ZenDatabase) {

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    private val refreshMutex = Mutex()
    @Volatile private var lastRefresh = 0L
    @Volatile private var refreshRequested = false

    private val observers = CopyOnWriteArrayList<FileObserver>()
    private val watchedPaths = HashSet<String>()
    private var watchJob: Job? = null
    @Volatile private var fire: (() -> Unit)? = null
    private var watchRoots: List<File> = emptyList()

    /** Roots live-watched so Recent/search stay current between rebuilds. */
    fun hotRoots(): List<File> = listOf(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
    ).filter { it.exists() }

    suspend fun size(): Int = db.fileIndex().count()

    /** Rebuild the index. Safe to call repeatedly; runs on IO. */
    suspend fun rebuild() = withContext(Dispatchers.IO) {
        val batch = ArrayList<FileIndexEntry>(1000)
        suspend fun flush() {
            if (batch.isNotEmpty()) { db.fileIndex().upsertAll(batch.toList()); batch.clear() }
        }
        val seen = HashSet<String>()
        Scanner.scan(listOf(Environment.getExternalStorageDirectory()), maxDepth = 24)
            .collect { entries ->
                for (e in entries) {
                    if (e.isDirectory) continue
                    seen += e.path
                    batch += FileIndexEntry(
                        path = e.path, name = e.name, size = e.size,
                        lastModified = e.lastModified, type = e.type.name,
                        parentDir = java.io.File(e.path).parent ?: "")
                    if (batch.size >= 1000) {
                        currentCoroutineContext().ensureActive()
                        flush()
                    }
                }
            }
        flush()
        _ready.value = true
    }

    /**
     * Incremental delta over [roots]: upsert every file found and remove
     * index rows under [roots] whose file is gone. Concurrent calls collapse
     * — callers closer than [minIntervalMs] to the previous pass are dropped,
     * and a pass requested while another is in flight runs once after.
     */
    suspend fun refresh(
        roots: List<File>,
        minIntervalMs: Long = 4000,
        maxItems: Int = 60000,
    ): Unit = withContext(Dispatchers.IO) {
        val liveRoots = roots.filter { it.exists() }
        if (liveRoots.isEmpty()) return@withContext
        val now = SystemClock.elapsedRealtime()
        if (now - lastRefresh < minIntervalMs) return@withContext
        if (!refreshMutex.tryLock()) { refreshRequested = true; return@withContext }
        try {
            lastRefresh = now
            val existing = HashSet<String>()
            for (r in liveRoots) existing += db.fileIndex().pathsUnder(r.absolutePath)
            val seen = HashSet<String>(existing.size.coerceAtLeast(64))
            val batch = ArrayList<FileIndexEntry>(500)
            suspend fun flush() {
                if (batch.isNotEmpty()) { db.fileIndex().upsertAll(batch.toList()); batch.clear() }
            }
            val dirs = ArrayList<File>(256)
            var visited = 0
            var truncated = false
            val stack = ArrayDeque<File>()
            liveRoots.forEach { stack.add(it) }
            while (stack.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val dir = stack.removeLast()
                val kids = try { dir.listFiles() } catch (e: Exception) { null } ?: continue
                dirs += dir
                for (f in kids) {
                    if (++visited > maxItems) { truncated = true; break }
                    if (f.isDirectory) {
                        if (!f.isHidden) stack.add(f)
                        continue
                    }
                    if (f.isHidden) continue
                    seen += f.absolutePath
                    val e = FileEntry.from(f)
                    batch += FileIndexEntry(
                        path = f.absolutePath, name = f.name, size = e.size,
                        lastModified = f.lastModified(), type = e.type.name,
                        parentDir = dir.absolutePath)
                    if (batch.size >= 500) flush()
                }
                if (truncated) break
            }
            flush()
            // Only drop missing rows when the walk covered everything —
            // otherwise we'd wrongly delete files past the item cap.
            if (!truncated) {
                val gone = (existing - seen).toList()
                gone.chunked(500).forEach { db.fileIndex().removeAll(it) }
            }
            // Extend the watch to directories discovered during this pass.
            if (fire != null) watchDirs(dirs)
        } finally {
            refreshMutex.unlock()
            if (refreshRequested) {
                refreshRequested = false
                refresh(liveRoots, minIntervalMs = 0, maxItems = maxItems)
            }
        }
    }

    /**
     * Recursively watch [roots] (breadth-first, capped) and run a debounced
     * delta refresh on any file event. Mirrors the inbox watcher.
     */
    fun startWatching(scope: CoroutineScope, roots: List<File>, debounceMs: Long = 1200) {
        stopWatching()
        watchRoots = roots.filter { it.exists() }
        val job = SupervisorJob(scope.coroutineContext[Job])
        watchJob = job
        val ws = CoroutineScope(scope.coroutineContext + Dispatchers.Default + job)
        var pending: Job? = null
        val f = {
            synchronized(this) {
                pending?.cancel()
                pending = ws.launch {
                    delay(debounceMs)
                    refresh(watchRoots, minIntervalMs = 0)
                }
            }
        }
        fire = f
        ws.launch(Dispatchers.IO) { watchDirs(collectDirs(watchRoots)) }
    }

    fun stopWatching() {
        fire = null
        observers.forEach { it.stopWatching() }
        observers.clear()
        watchedPaths.clear()
        watchJob?.cancel(); watchJob = null
    }

    private fun collectDirs(roots: List<File>, cap: Int = 900): List<File> {
        val out = ArrayList<File>(cap)
        val q = ArrayDeque<File>()
        roots.forEach { q.add(it) }
        while (q.isNotEmpty() && out.size < cap) {
            val d = q.removeFirst()
            if (d.isHidden || !d.isDirectory) continue
            out += d
            d.listFiles()?.forEach { if (it.isDirectory && !it.isHidden) q.add(it) }
        }
        return out
    }

    private fun watchDirs(dirs: List<File>) {
        val f = fire ?: return
        for (d in dirs) {
            if (watchedPaths.size >= 900) break
            val p = d.absolutePath
            if (!watchedPaths.add(p)) continue
            val o = object : FileObserver(p,
                FileObserver.CREATE or FileObserver.MOVED_TO or
                FileObserver.DELETE or FileObserver.MOVED_FROM or
                FileObserver.MOVE_SELF or FileObserver.CLOSE_WRITE or
                FileObserver.ATTRIB) {
                override fun onEvent(event: Int, path: String?) { f() }
            }
            o.startWatching()
            observers += o
        }
    }

    suspend fun search(query: String, limit: Int = 500): List<FileIndexEntry> =
        db.fileIndex().byName(query, limit)

    suspend fun byType(type: String, limit: Int = 2000): List<FileIndexEntry> =
        db.fileIndex().byType(type, limit)

    suspend fun all(limit: Int = 50000): List<FileIndexEntry> =
        db.fileIndex().allRows(limit)

    suspend fun recent(limit: Int = 500): List<FileIndexEntry> =
        db.fileIndex().recent(limit)

    suspend fun byDay(fromMs: Long, toMs: Long, limit: Int = 2000) =
        db.fileIndex().byDateRange(fromMs, toMs, limit)
}
