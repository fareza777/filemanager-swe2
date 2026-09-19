package com.filezen.files.core.inbox

import android.content.Context
import android.os.Environment
import com.filezen.files.core.model.FileEntry
import com.filezen.files.data.db.InboxItem
import com.filezen.files.data.db.ZenDatabase
import com.filezen.files.data.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
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
     * (untidy), drop rows whose files no longer exist. Recursive, depth-limited,
     * incremental — safe to call on every Inbox open.
     */
    suspend fun scan(maxDepth: Int = 4, maxItems: Int = 5000): Unit = withContext(Dispatchers.IO) {
        val known = db.inbox().allPaths().toMutableSet()
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
        if (newItems.isNotEmpty()) db.inbox().insertAll(newItems)
        val gone = known.filter { it !in found }
        if (gone.isNotEmpty()) db.inbox().remove(gone)
    }

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
