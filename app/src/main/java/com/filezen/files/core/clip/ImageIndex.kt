package com.filezen.files.core.clip

import android.util.Log
import com.filezen.files.core.vec.VecIndex
import com.filezen.files.data.db.ImgEmbedding
import com.filezen.files.data.db.ZenDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CLIP photo index — one 512-dim embedding per image file, stored float16 in
 * the `img_embeddings` table (same vec_f16 scheme as document chunks).
 * Incremental: re-embeds only new or changed files.
 */
class ImageIndex(private val db: ZenDatabase, private val clip: ClipEngine) {

    data class Progress(val total: Int, val done: Int, val current: String = "")
    data class Hit(val path: String, val score: Float)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress
    val indexedCount = db.imgIndex().count()

    /** App-level scope — indexing keeps running when the user leaves the
     *  Search screen or switches tabs; it only dies with the process. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val queued = AtomicBoolean(false)

    companion object {
        val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        /** CLIP cosine sims on normalised vecs live ~0.15–0.40 — below this the
         *  match is noise. */
        private const val MIN_SCORE = 0.19f
        private const val TAG = "ImageIndex"
    }

    fun indexable(f: File): Boolean =
        f.isFile && f.length() > 0 && f.extension.lowercase() in EXTENSIONS

    /** Request an incremental sync — returns immediately. The pass runs on an
     *  app-level scope, so it continues while the user browses other pages.
     *  Calls arriving mid-run are coalesced into one follow-up pass. */
    fun kick() {
        if (!clip.isReady) return
        queued.set(true)
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (queued.getAndSet(false)) runPass()
            } finally {
                running.set(false)
                _progress.value = null
                // A kick landing during teardown would otherwise be lost.
                if (queued.get()) kick()
            }
        }
    }

    private suspend fun runPass() {
        val dao = db.imgIndex()
        val stored = dao.all().associateBy { it.path }.toMutableMap()
        val candidates = db.fileIndex().allRows()
            .map { File(it.path) }
            .filter { indexable(it) && it.exists() }

        val live = candidates.mapTo(HashSet()) { it.absolutePath }
        for (gone in stored.keys - live) { dao.remove(gone); stored.remove(gone) }

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

    /** Clear everything and re-index — also backgrounded. */
    fun rebuildAll() {
        scope.launch {
            db.imgIndex().clear()
            kick()
        }
    }

    /** Text → photo hits, best-first. */
    suspend fun search(query: String, k: Int = 60): List<Hit> = withContext(Dispatchers.Default) {
        if (!clip.isReady) return@withContext emptyList()
        val qv = clip.embedText(query)
        val rows = db.imgIndex().all().filter { it.embedding.isNotEmpty() }
        val knn = VecIndex.knn(qv, rows.map { it.path to it.embedding }, k = k)
        knn.map { (path, sim) -> Hit(path, sim) }
            .filter { it.score >= MIN_SCORE && File(it.path).exists() }
    }
}
