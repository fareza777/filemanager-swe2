package com.filezen.files.core.fileops

import android.content.Context
import com.filezen.files.data.db.TrashEntry
import com.filezen.files.data.db.ZenDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Soft-delete: files deleted through the app are moved into an app-private trash
 * directory and recorded in Room, so they can be restored or purged later.
 * Never permanently deletes anything outside the trash dir unless explicitly
 * asked (purge).
 */
class TrashManager(private val ctx: Context, private val db: ZenDatabase) {

    private val trashRoot: File
        get() = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "trash").apply { mkdirs() }

    private val engine = FileEngine()

    suspend fun trash(sources: List<File>): OpSummary = withContext(Dispatchers.IO) {
        val results = mutableListOf<ItemResult>()
        for (src in sources) {
            currentCoroutineContext().ensureActive()
            try {
                if (!src.exists()) {
                    results += ItemResult(src.path, null, ItemStatus.FAILED, "Not found")
                    continue
                }
                val size = engine.sizeOf(src)
                val dst = File(trashRoot, "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}_${src.name}")
                val moved = src.renameTo(dst) || run {
                    // cross-volume or rename restriction: copy-verify-delete
                    engine.copy(listOf(src), File(dst.parent!!), ConflictPolicy.KEEP_BOTH)
                    val copied = File(dst.parent!!, src.name)
                    if (!copied.exists()) return@run false
                    if (!copied.renameTo(dst)) return@run false
                    engine.delete(listOf(src)); true
                }
                if (moved) {
                    db.trash().insert(
                        TrashEntry(
                            name = src.name, originalPath = src.absolutePath,
                            trashPath = dst.absolutePath, size = size,
                            isDir = src.isDirectory || dst.isDirectory,
                            deletedAt = System.currentTimeMillis(),
                        )
                    )
                    results += ItemResult(src.path, dst.path, ItemStatus.DONE)
                } else {
                    results += ItemResult(src.path, null, ItemStatus.FAILED, "Move to trash failed")
                }
            } catch (e: Exception) {
                results += ItemResult(src.path, null, ItemStatus.FAILED, e.message)
            }
        }
        OpSummary(OpKind.TRASH, results)
    }

    suspend fun restore(entry: TrashEntry): ItemResult = withContext(Dispatchers.IO) {
        try {
            val trashFile = File(entry.trashPath)
            val dest = File(entry.originalPath)
            if (!trashFile.exists()) {
                db.trash().delete(entry)
                return@withContext ItemResult(entry.trashPath, null, ItemStatus.FAILED, "Trashed file missing")
            }
            val parent = dest.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            val finalDest = if (dest.exists()) engine.uniqueName(parent!!, entry.name) else dest
            val ok = trashFile.renameTo(finalDest) || run {
                engine.copy(listOf(trashFile), finalDest.parentFile!!, ConflictPolicy.KEEP_BOTH)
                engine.delete(listOf(trashFile))
                true
            }
            if (ok) {
                db.trash().delete(entry)
                ItemResult(entry.trashPath, finalDest.path, ItemStatus.DONE)
            } else ItemResult(entry.trashPath, null, ItemStatus.FAILED, "Restore failed")
        } catch (e: Exception) {
            ItemResult(entry.trashPath, null, ItemStatus.FAILED, e.message)
        }
    }

    /** Permanently delete a trashed file. */
    suspend fun purge(entry: TrashEntry): ItemResult = withContext(Dispatchers.IO) {
        val f = File(entry.trashPath)
        val ok = !f.exists() || engine.delete(listOf(f)).failed == 0
        if (ok) {
            db.trash().delete(entry)
            ItemResult(entry.trashPath, null, ItemStatus.DONE)
        } else ItemResult(entry.trashPath, null, ItemStatus.FAILED, "Purge failed")
    }

    suspend fun purgeAll(): Int = withContext(Dispatchers.IO) {
        var n = 0
        db.trash().entriesBefore(Long.MAX_VALUE).forEach { e ->
            val f = File(e.trashPath)
            if (!f.exists() || f.deleteRecursively()) { db.trash().delete(e); n++ }
        }
        n
    }

    suspend fun autoPurge(olderThanDays: Int): Int = withContext(Dispatchers.IO) {
        var n = 0
        val cutoff = System.currentTimeMillis() - olderThanDays * 86_400_000L
        db.trash().entriesBefore(cutoff).forEach { e ->
            val f = File(e.trashPath)
            if (!f.exists() || f.deleteRecursively()) { db.trash().delete(e); n++ }
        }
        n
    }
}
