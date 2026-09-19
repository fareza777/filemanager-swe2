package com.filezen.files.core.scan

import android.os.Environment
import android.os.StatFs
import com.filezen.files.core.model.FileEntry
import com.filezen.files.core.model.FileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class StorageUsage(val total: Long, val free: Long) { val used: Long get() = total - free }
data class CategorySize(val type: FileType, val bytes: Long, val count: Int)
data class LargeFile(val entry: FileEntry)
data class DuplicateGroup(val hash: String, val files: List<FileEntry>) {
    val wasted: Long get() = files.sumOf { it.size } - (files.firstOrNull()?.size ?: 0)
}

object StorageAnalyzer {

    fun usage(volume: File = Environment.getExternalStorageDirectory()): StorageUsage {
        val s = StatFs(volume.absolutePath)
        return StorageUsage(s.totalBytes, s.availableBytes)
    }

    /** Category breakdown + total scanned. Incremental, cancellable. */
    suspend fun categories(root: File, includeHidden: Boolean = false): List<CategorySize> =
        withContext(Dispatchers.IO) {
            val map = HashMap<FileType, Pair<Long, Int>>()
            val stack = ArrayDeque<File>(); stack.add(root)
            while (stack.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val dir = stack.removeLast()
                val kids = try { dir.listFiles() } catch (e: Exception) { null } ?: continue
                for (f in kids) {
                    if (f.isDirectory) {
                        if (includeHidden || !f.isHidden) stack.add(f)
                    } else {
                        val e = FileEntry.from(f)
                        if (!includeHidden && e.hidden) continue
                        val p = map.getOrDefault(e.type, 0L to 0)
                        map[e.type] = (p.first + e.size) to (p.second + 1)
                    }
                }
            }
            map.map { (t, p) -> CategorySize(t, p.first, p.second) }
                .sortedByDescending { it.bytes }
        }

    suspend fun largeFiles(
        root: File,
        minBytes: Long = 50L * 1024 * 1024,
        limit: Int = 200,
        includeHidden: Boolean = false,
    ): List<LargeFile> = withContext(Dispatchers.IO) {
        val heap = java.util.PriorityQueue<FileEntry>(compareBy { it.size })
        val stack = ArrayDeque<File>(); stack.add(root)
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dir = stack.removeLast()
            val kids = try { dir.listFiles() } catch (e: Exception) { null } ?: continue
            for (f in kids) {
                if (f.isDirectory) {
                    if (includeHidden || !f.isHidden) stack.add(f)
                } else if (f.length() >= minBytes && (includeHidden || !f.isHidden)) {
                    val e = FileEntry.from(f)
                    if (heap.size < limit) heap.add(e)
                    else if (e.size > heap.peek()!!.size) { heap.poll(); heap.add(e) }
                }
            }
        }
        heap.toList().sortedByDescending { it.size }.map { LargeFile(it) }
    }

    /**
     * Identical-content duplicates: group by size → confirm by sha256 of full
     * content for same-size groups. Cancellable between files.
     */
    suspend fun duplicates(
        root: File,
        minBytes: Long = 256 * 1024,
        includeHidden: Boolean = false,
    ): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val bySize = HashMap<Long, MutableList<File>>()
        val stack = ArrayDeque<File>(); stack.add(root)
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dir = stack.removeLast()
            val kids = try { dir.listFiles() } catch (e: Exception) { null } ?: continue
            for (f in kids) {
                if (f.isDirectory) {
                    if (includeHidden || !f.isHidden) stack.add(f)
                } else {
                    if (!includeHidden && f.isHidden) continue
                    val sz = f.length()
                    if (sz >= minBytes) bySize.getOrPut(sz) { mutableListOf() }.add(f)
                }
            }
        }
        val groups = mutableListOf<DuplicateGroup>()
        for ((_, files) in bySize) {
            currentCoroutineContext().ensureActive()
            if (files.size < 2) continue
            val byHash = HashMap<String, MutableList<FileEntry>>()
            for (f in files) {
                currentCoroutineContext().ensureActive()
                val h = try { sha256(f) } catch (e: Exception) { continue }
                byHash.getOrPut(h) { mutableListOf() }.add(FileEntry.from(f))
            }
            for ((h, list) in byHash) if (list.size > 1) groups += DuplicateGroup(h, list)
        }
        groups.sortedByDescending { it.wasted }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = ins.read(buf); if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
