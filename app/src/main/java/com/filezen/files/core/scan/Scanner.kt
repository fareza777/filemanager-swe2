package com.filezen.files.core.scan

import com.filezen.files.core.model.FileEntry
import com.filezen.files.core.model.FileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

data class SearchFilter(
    val query: String = "",
    val types: Set<FileType> = emptySet(),     // empty = any
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val modifiedAfter: Long? = null,
    val modifiedBefore: Long? = null,
    val includeHidden: Boolean = false,
)

object Scanner {

    /** Shallow listing of one directory. Never throws; unreadable dirs yield empty. */
    fun listDir(path: String, includeHidden: Boolean = false): List<FileEntry> {
        val d = File(path)
        val kids = try { d.listFiles() } catch (e: Exception) { null } ?: return emptyList()
        return kids.map { FileEntry.from(it) }
            .filter { includeHidden || !it.hidden }
    }

    /**
     * Incremental recursive scan — emits batches of [batchSize] so the UI stays
     * responsive. Honour cancellation; skip unreadable dirs.
     */
    fun scan(
        roots: List<File>,
        filter: SearchFilter = SearchFilter(),
        batchSize: Int = 50,
        maxDepth: Int = 24,
    ): Flow<List<FileEntry>> = flow {
        val batch = ArrayList<FileEntry>(batchSize)
        suspend fun emitIfFull() {
            if (batch.size >= batchSize) {
                emit(ArrayList(batch)); batch.clear()
            }
        }
        val stack = ArrayDeque<Pair<File, Int>>()
        roots.forEach { if (it.exists()) stack.add(it to 0) }
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (dir, depth) = stack.removeLast()
            if (depth > maxDepth) continue
            val kids = try { dir.listFiles() } catch (e: Exception) { null } ?: continue
            for (f in kids) {
                currentCoroutineContext().ensureActive()
                val e = FileEntry.from(f)
                if (!filter.includeHidden && e.hidden && !f.isDirectory) continue
                if (f.isDirectory) {
                    if (!e.hidden || filter.includeHidden) stack.add(f to depth + 1)
                    if (matches(e, filter)) { batch += e; emitIfFull() }
                } else if (matches(e, filter)) {
                    batch += e; emitIfFull()
                }
            }
        }
        if (batch.isNotEmpty()) emit(batch)
    }.flowOn(Dispatchers.IO)

    fun matches(e: FileEntry, f: SearchFilter): Boolean {
        if (f.query.isNotBlank() && !e.name.contains(f.query, ignoreCase = true)) return false
        if (f.types.isNotEmpty() && e.type !in f.types && !(e.isDirectory && FileType.FOLDER in f.types)) return false
        if (!e.isDirectory) {
            if (f.minSize != null && e.size < f.minSize) return false
            if (f.maxSize != null && e.size > f.maxSize) return false
        }
        if (f.modifiedAfter != null && e.lastModified < f.modifiedAfter) return false
        if (f.modifiedBefore != null && e.lastModified > f.modifiedBefore) return false
        return true
    }

    /** Newest files under roots (non-recursive-capable), for Home "Recent". */
    fun recent(roots: List<File>, limit: Int = 40, includeHidden: Boolean = false): List<FileEntry> {
        val out = mutableListOf<FileEntry>()
        for (r in roots) {
            val kids = try { r.listFiles() } catch (e: Exception) { null } ?: continue
            for (f in kids) {
                if (f.isFile && (includeHidden || !f.isHidden)) out += FileEntry.from(f)
            }
        }
        return out.sortedByDescending { it.lastModified }.take(limit)
    }
}
