package com.filezen.files.core.fileops

import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

enum class ConflictPolicy { ASK, SKIP, KEEP_BOTH, OVERWRITE }

enum class ItemStatus { DONE, SKIPPED, FAILED }

enum class OpKind { COPY, MOVE, DELETE, RENAME, MKDIR, ZIP, UNZIP, TRASH, RESTORE, SORT, TIDY, CONVERT, COMPRESS }

data class ItemResult(
    val source: String,
    val target: String?,
    val status: ItemStatus,
    val error: String? = null,
)

data class OpSummary(
    val kind: OpKind,
    val results: List<ItemResult>,
    val cancelled: Boolean = false,
) {
    val succeeded: Int get() = results.count { it.status == ItemStatus.DONE }
    val failed: Int get() = results.count { it.status == ItemStatus.FAILED }
    val skipped: Int get() = results.count { it.status == ItemStatus.SKIPPED }
    val total: Int get() = results.size
}

data class OpProgress(
    val itemsDone: Int,
    val itemsTotal: Int,
    val currentName: String,
    val bytesDone: Long,
    val bytesTotal: Long,
)

class InsufficientSpaceException(val needed: Long, val available: Long) :
    IOException("Not enough space")

class PermissionDeniedException(path: String) : IOException("Permission denied: $path")

typealias ProgressCb = suspend (OpProgress) -> Unit

/**
 * Safe file engine. Never deletes a source before the destination copy is
 * verified (byte count + size match). Cancellable via coroutine cancellation —
 * partial destination files are cleaned up. Per-item failures never abort the
 * batch.
 */
class FileEngine {

    private val io = Dispatchers.IO

    private suspend fun File.checkActive() = currentCoroutineContext().ensureActive()

    fun sizeOf(f: File): Long =
        if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()

    fun exists(f: File) = f.exists()

    /** Free bytes on the volume holding [dir] (walks up until a mounted parent is found). */
    fun freeSpace(dir: File): Long {
        var d = dir
        while (!d.exists()) {
            d = d.parentFile ?: return Long.MAX_VALUE
        }
        return try { StatFs(d.absolutePath).availableBytes } catch (e: Exception) { Long.MAX_VALUE }
    }

    suspend fun copy(
        sources: List<File>,
        destDir: File,
        policy: ConflictPolicy,
        onProgress: ProgressCb = {},
    ): OpSummary = withContext(io) {
        // Small files copy faster in parallel (bounded 4-way); large files/dirs stay sequential.
        val small = sources.filter { it.isFile && it.length() < 4L * 1024 * 1024 }
        if (small.size < 4 || small.size != sources.size) {
            return@withContext runBatch(OpKind.COPY, sources, destDir, policy, onProgress) { src, dst ->
                copyTree(src, dst)
            }
        }
        if (!destDir.exists() && !destDir.mkdirs()) {
            return@withContext OpSummary(OpKind.COPY, sources.map {
                ItemResult(it.path, null, ItemStatus.FAILED, "Cannot create destination")
            })
        }
        // Resolve all targets sequentially first so name conflicts can't race.
        val plan = sources.map { src ->
            val dst = if (!src.exists()) null else resolveTarget(destDir, src.name, policy)
            Triple(src, dst, if (src.exists()) 0 else 1)
        }
        val results = java.util.concurrent.ConcurrentLinkedQueue<ItemResult>()
        val bytesDone = java.util.concurrent.atomic.AtomicLong()
        val bytesTotal = sources.sumOf { it.length() }
        val done = java.util.concurrent.atomic.AtomicInteger()
        val gate = kotlinx.coroutines.sync.Semaphore(4)
        coroutineScope {
            plan.map { (src, dst, missing) ->
                async {
                    currentCoroutineContext().ensureActive()
                    if (missing == 1) {
                        results += ItemResult(src.path, null, ItemStatus.FAILED, "Source not found")
                        done.incrementAndGet(); return@async
                    }
                    if (dst == null) {
                        results += ItemResult(src.path, null, ItemStatus.SKIPPED)
                        done.incrementAndGet(); return@async
                    }
                    gate.acquire()
                    try {
                        if (dst.exists() && policy == ConflictPolicy.OVERWRITE) dst.deleteRecursively()
                        copyTree(src, dst)
                        bytesDone.addAndGet(src.length())
                        results += ItemResult(src.path, dst.path, ItemStatus.DONE)
                    } catch (ce: CancellationException) {
                        if (dst.exists() && dst.path != src.path) dst.deleteRecursively()
                        throw ce
                    } catch (e: Exception) {
                        results += ItemResult(src.path, null, ItemStatus.FAILED, e.message ?: "I/O error")
                    } finally { gate.release() }
                    val i = done.incrementAndGet()
                    onProgress(OpProgress(i, sources.size, src.name, bytesDone.get(), bytesTotal))
                }
            }.awaitAll()
        }
        OpSummary(OpKind.COPY, results.toList())
    }

    /**
     * Move = copy + verify + delete. Source is only removed after a byte-verified
     * copy exists at the destination.
     */
    suspend fun move(
        sources: List<File>,
        destDir: File,
        policy: ConflictPolicy,
        onProgress: ProgressCb = {},
    ): OpSummary = withContext(io) {
        // Fast path: same-volume rename is atomic and instant.
        val results = mutableListOf<ItemResult>()
        val slow = mutableListOf<File>()
        val slowTargets = mutableListOf<File>()
        for (src in sources) {
            currentCoroutineContext().ensureActive()
            if (!src.exists()) {
                results += ItemResult(src.path, null, ItemStatus.FAILED, "Source not found")
                continue
            }
            val dst = resolveTarget(destDir, src.name, policy)
            if (dst == null) {
                results += ItemResult(src.path, null, ItemStatus.SKIPPED)
                continue
            }
            if (!destDir.exists() && !destDir.mkdirs()) {
                results += ItemResult(src.path, dst.path, ItemStatus.FAILED, "Cannot create destination")
                continue
            }
            if (src.renameTo(dst)) {
                results += ItemResult(src.path, dst.path, ItemStatus.DONE)
            } else {
                // Cross-volume or permission → verified copy then delete.
                if (policy == ConflictPolicy.OVERWRITE && dst.exists() && !dst.deleteRecursively()) {
                    results += ItemResult(src.path, dst.path, ItemStatus.FAILED, "Cannot overwrite existing")
                    continue
                }
                slow += src; slowTargets += dst
            }
        }
        val slowResults = copyVerifiedThenDelete(slow, slowTargets, onProgress, results.size, sources.size)
        results += slowResults.results
        OpSummary(OpKind.MOVE, results, slowResults.cancelled)
    }

    private suspend fun runBatch(
        kind: OpKind,
        sources: List<File>,
        destDir: File,
        policy: ConflictPolicy,
        onProgress: ProgressCb,
        op: suspend (File, File) -> Unit,
    ): OpSummary {
        val results = mutableListOf<ItemResult>()
        if (!destDir.exists() && !destDir.mkdirs()) {
            return OpSummary(kind, sources.map {
                ItemResult(it.path, null, ItemStatus.FAILED, "Cannot create destination")
            })
        }
        val sizes = sources.map { it to sizeOf(it) }.toMap()
        val needed = sizes.values.sum()
        val free = freeSpace(destDir)
        if (needed > 0 && free < needed) throw InsufficientSpaceException(needed, free)

        var bytesDone = 0L
        val bytesTotal = needed
        sources.forEachIndexed { i, src ->
            currentCoroutineContext().ensureActive()
            var dst: File? = null
            try {
                if (!src.exists()) {
                    results += ItemResult(src.path, null, ItemStatus.FAILED, "Source not found")
                    return@forEachIndexed
                }
                dst = resolveTarget(destDir, src.name, policy)
                if (dst == null) {
                    results += ItemResult(src.path, null, ItemStatus.SKIPPED)
                    return@forEachIndexed
                }
                if (dst.exists() && policy == ConflictPolicy.OVERWRITE) dst.deleteRecursively()
                onProgress(OpProgress(i, sources.size, src.name, bytesDone, bytesTotal))
                op(src, dst)
                bytesDone += sizes[src] ?: 0
                results += ItemResult(src.path, dst.path, ItemStatus.DONE)
            } catch (ce: CancellationException) {
                // clean partial destination then rethrow
                dst?.takeIf { it.exists() && it.path != src.path }?.deleteRecursively()
                throw ce
            } catch (e: Exception) {
                results += ItemResult(src.path, null, ItemStatus.FAILED, e.message ?: "I/O error")
            }
        }
        onProgress(OpProgress(sources.size, sources.size, "", bytesDone, bytesTotal))
        return OpSummary(kind, results)
    }

    private suspend fun copyVerifiedThenDelete(
        sources: List<File>,
        targets: List<File>,
        onProgress: ProgressCb,
        doneOffset: Int,
        total: Int,
    ): OpSummary {
        val results = mutableListOf<ItemResult>()
        val bytesTotal = sources.sumOf { sizeOf(it) }
        var bytesDone = 0L
        var cancelled = false
        sources.forEachIndexed { i, src ->
            val dst = targets[i]
            try {
                currentCoroutineContext().ensureActive()
                onProgress(OpProgress(doneOffset + i, total, src.name, bytesDone, bytesTotal))
                copyTree(src, dst)
                val srcSize = sizeOf(src)
                verifyCopy(src, dst)
                if (!src.deleteRecursively()) {
                    dst.deleteRecursively()
                    results += ItemResult(src.path, dst.path, ItemStatus.FAILED, "Could not remove source")
                    return@forEachIndexed
                }
                bytesDone += srcSize
                results += ItemResult(src.path, dst.path, ItemStatus.DONE)
            } catch (ce: CancellationException) {
                dst.deleteRecursively() // remove partial target; keep source
                cancelled = true
                throw ce
            } catch (e: Exception) {
                results += ItemResult(src.path, null, ItemStatus.FAILED, e.message ?: "I/O error")
            }
        }
        return OpSummary(OpKind.MOVE, results, cancelled)
    }

    suspend fun delete(sources: List<File>, onProgress: ProgressCb = {}): OpSummary =
        withContext(io) {
            val results = mutableListOf<ItemResult>()
            sources.forEachIndexed { i, f ->
                currentCoroutineContext().ensureActive()
                onProgress(OpProgress(i, sources.size, f.name, 0, 0))
                results += if (f.exists() && f.deleteRecursively()) {
                    ItemResult(f.path, null, ItemStatus.DONE)
                } else {
                    ItemResult(f.path, null, ItemStatus.FAILED, "Could not delete")
                }
            }
            OpSummary(OpKind.DELETE, results)
        }

    suspend fun rename(src: File, newName: String): ItemResult = withContext(io) {
        try {
            val dst = File(src.parentFile, newName)
            when {
                newName.isBlank() -> ItemResult(src.path, null, ItemStatus.FAILED, "Empty name")
                dst.exists() -> ItemResult(src.path, dst.path, ItemStatus.FAILED, "Name already exists")
                !src.exists() -> ItemResult(src.path, null, ItemStatus.FAILED, "Source not found")
                src.renameTo(dst) -> ItemResult(src.path, dst.path, ItemStatus.DONE)
                else -> ItemResult(src.path, dst.path, ItemStatus.FAILED, "Rename failed")
            }
        } catch (e: Exception) {
            ItemResult(src.path, null, ItemStatus.FAILED, e.message)
        }
    }

    suspend fun mkdir(parent: File, name: String): ItemResult = withContext(io) {
        val f = File(parent, name)
        when {
            name.isBlank() -> ItemResult(parent.path, null, ItemStatus.FAILED, "Empty name")
            f.exists() -> ItemResult(parent.path, f.path, ItemStatus.FAILED, "Already exists")
            f.mkdirs() -> ItemResult(parent.path, f.path, ItemStatus.DONE)
            else -> ItemResult(parent.path, f.path, ItemStatus.FAILED, "Cannot create folder")
        }
    }

    // ---- internals ----

    private suspend fun copyTree(src: File, dst: File) {
        src.checkActive()
        if (src.isDirectory) {
            if (!dst.exists() && !dst.mkdirs()) throw IOException("Cannot create ${dst.path}")
            val children = src.listFiles() ?: throw PermissionDeniedException(src.path)
            for (c in children) {
                src.checkActive()
                copyTree(c, File(dst, c.name))
            }
        } else {
            copyFile(src, dst)
        }
    }

    private suspend fun copyFile(src: File, dst: File) {
        val parent = dst.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        if (!dst.exists() && !dst.createNewFile() && !dst.exists())
            throw IOException("Cannot write ${dst.path}")
        FileInputStream(src).channel.use { inCh ->
            FileOutputStream(dst).channel.use { outCh ->
                val buf = java.nio.ByteBuffer.allocate(256 * 1024)
                while (true) {
                    src.checkActive()
                    buf.clear()
                    if (inCh.read(buf) < 0) break
                    buf.flip()
                    while (buf.hasRemaining()) {
                        src.checkActive()
                        outCh.write(buf)
                    }
                }
            }
        }
        dst.setLastModified(src.lastModified())
    }

    /** Byte-level verification: same file count + same total size + same per-file sizes. */
    fun verifyCopy(src: File, dst: File) {
        if (src.isDirectory != dst.isDirectory) throw IOException("Type mismatch")
        if (src.isDirectory) {
            val s = src.listFiles() ?: throw PermissionDeniedException(src.path)
            val d = dst.listFiles() ?: throw PermissionDeniedException(dst.path)
            if (s.size != d.size) throw IOException("File count mismatch (${s.size} vs ${d.size})")
            val byName = d.associateBy { it.name }
            for (sf in s) {
                val df = byName[sf.name] ?: throw IOException("Missing ${sf.name}")
                verifyCopy(sf, df)
            }
        } else {
            if (src.length() != dst.length()) throw IOException("Size mismatch for ${src.name}")
        }
    }

    /** Applies conflict policy. Returns null when the item should be skipped. */
    fun resolveTarget(destDir: File, name: String, policy: ConflictPolicy): File? {
        val dst = File(destDir, name)
        if (!dst.exists()) return dst
        return when (policy) {
            ConflictPolicy.SKIP, ConflictPolicy.ASK -> null
            ConflictPolicy.OVERWRITE -> dst
            ConflictPolicy.KEEP_BOTH -> uniqueName(destDir, name)
        }
    }

    fun uniqueName(dir: File, name: String): File {
        var i = 1
        var f: File
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        do {
            f = File(dir, "$base ($i)$ext")
            i++
        } while (f.exists())
        return f
    }

    private fun File.deleteRecursively(): Boolean {
        if (isDirectory) listFiles()?.forEach { it.deleteRecursively() }
        return delete()
    }
}
