package com.filezen.files.core.scan

import android.os.Environment
import com.filezen.files.core.model.FileEntry
import com.filezen.files.data.db.FileIndexEntry
import com.filezen.files.data.db.ZenDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Whole-disk file index in Room. Built once per session in the background;
 * search and the calendar read it instantly instead of walking the tree.
 */
class FileIndex(private val db: ZenDatabase) {

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

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

    suspend fun search(query: String, limit: Int = 500): List<FileIndexEntry> =
        db.fileIndex().byName(query, limit)

    suspend fun byType(type: String, limit: Int = 2000): List<FileIndexEntry> =
        db.fileIndex().byType(type, limit)

    suspend fun all(limit: Int = 50000): List<FileIndexEntry> =
        db.fileIndex().allRows(limit)

    suspend fun byDay(fromMs: Long, toMs: Long, limit: Int = 2000) =
        db.fileIndex().byDateRange(fromMs, toMs, limit)
}
