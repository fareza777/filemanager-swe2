package com.filezen.files.core.clip

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.filezen.files.core.vec.VecIndex
import com.filezen.files.data.db.ImgEmbedding
import com.filezen.files.data.db.ZenDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CLIP photo index — one 512-dim embedding per image file, stored float16 in
 * the `img_embeddings` table (same vec_f16 scheme as document chunks).
 * Incremental: re-embeds only new or changed files.
 */
class ImageIndex(private val app: Context, private val db: ZenDatabase, private val clip: ClipEngine) {

    data class Progress(val total: Int, val done: Int, val current: String = "")
    data class Hit(val path: String, val score: Float)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress
    val indexedCount = db.imgIndex().count()

    /** New work is coalesced through this flag + unique-work queueing, so a
     *  flood of kicks never spawns overlapping passes. */
    private val queued = AtomicBoolean(false)

    companion object {
        val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        /** CLIP cosine sims on normalised vecs live ~0.15–0.40 — below this the
         *  match is noise. */
        private const val MIN_SCORE = 0.21f
        private const val TAG = "ImageIndex"
    }

    fun indexable(f: File): Boolean =
        f.isFile && f.length() > 0 && f.extension.lowercase() in EXTENSIONS

    /** Request an incremental sync — returns immediately. The work runs in a
     *  WorkManager expedited worker: it keeps going when the user leaves the
     *  app, and if the process is killed it is rescheduled and resumes from
     *  the rows already committed — never from zero. */
    fun kick() {
        if (!clip.isReady) return
        queued.set(true)
        WorkManager.getInstance(app).enqueueUniqueWork(
            PhotoIndexWorker.WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<PhotoIndexWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build())
    }

    /** Run queued passes until none remain — called by [PhotoIndexWorker]. */
    suspend fun drainQueue() {
        try {
            while (queued.getAndSet(false)) runPass()
        } finally {
            _progress.value = null
        }
    }

    /** Drop every row — used by the rebuild work request. */
    suspend fun clearAll() { db.imgIndex().clear() }

    private suspend fun runPass() {
        val dao = db.imgIndex()
        val stored = dao.all().associateBy { it.path }.toMutableMap()
        val candidates = db.fileIndex().allRows()
            .map { File(it.path) }
            .filter { indexable(it) && it.exists() }

        // Prune only rows whose file is confirmed gone. The file index is a
        // discovery source, not ground truth: while it rebuilds (every app
        // start) it briefly returns partial/empty rows — pruning by index
        // membership wiped the whole embedding table mid-pass (the "reset"
        // users saw), so existence on disk is the check instead.
        for (stale in stored.keys.filter { !File(it).exists() }) {
            dao.remove(stale); stored.remove(stale)
        }
        // And if the file index currently sees no images at all, it's almost
        // certainly mid-rebuild — skip this pass rather than act on bad data.
        if (candidates.isEmpty() && stored.isNotEmpty()) return

        val todo = candidates.filter { f ->
            val m = stored[f.absolutePath]
            m == null || m.size != f.length() || m.lastModified != f.lastModified()
        }
        if (todo.isEmpty()) { _progress.value = null; return }

        var done = 0
        _progress.value = Progress(todo.size, 0)
        for (f in todo) {
            currentCoroutineContext().ensureActive()
            _progress.value = Progress(todo.size, done, f.name)
            try {
                val v = clip.embedImage(f)
                dao.put(ImgEmbedding(f.absolutePath,
                    VecIndex.pack16(v), f.length(), f.lastModified()))
            } catch (t: Throwable) {
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "embed failed: ${f.name}", t)
                // Tombstone: empty embedding + real size/mtime, so undecodable
                // files aren't retried forever on every pass.
                dao.put(ImgEmbedding(f.absolutePath,
                    ByteArray(0), f.length(), f.lastModified()))
            }
            done++
        }
        _progress.value = null
    }

    /** Clear everything and re-index — same backgrounded worker path. */
    fun rebuildAll() {
        if (!clip.isReady) return
        queued.set(true)
        WorkManager.getInstance(app).enqueueUniqueWork(
            PhotoIndexWorker.WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<PhotoIndexWorker>()
                .setInputData(workDataOf(PhotoIndexWorker.KEY_REBUILD to true))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build())
    }

    /** Text → photo hits, best-first. */
    suspend fun search(query: String, k: Int = 60): List<Hit> = withContext(Dispatchers.Default) {
        if (!clip.isReady) return@withContext emptyList()
        val qv = clip.embedText(query)
        val rows = db.imgIndex().all().filter { it.embedding.isNotEmpty() }
        val knn = VecIndex.knn(qv, rows.map { it.path to it.embedding }, k = k)
        val hits = knn.map { (path, sim) -> Hit(path, sim) }
            .filter { it.score >= MIN_SCORE && File(it.path).exists() }
        // Relative cutoff: junk neighbours can sit just above the floor for
        // any query — keep only hits reasonably close to the best match.
        val top = hits.firstOrNull()?.score ?: return@withContext emptyList()
        return@withContext hits.filter { it.score >= maxOf(MIN_SCORE, top * 0.75f) }
    }
}
