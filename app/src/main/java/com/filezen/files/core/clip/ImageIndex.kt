package com.filezen.files.core.clip

import com.filezen.files.core.vec.VecIndex
import com.filezen.files.data.db.ImgEmbedding
import com.filezen.files.data.db.ZenDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

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

    @Volatile private var running = false

    companion object {
        val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        /** CLIP cosine sims on normalised vecs live ~0.15–0.40 — below this the
         *  match is noise. */
        private const val MIN_SCORE = 0.19f
    }

    fun indexable(f: File): Boolean =
        f.isFile && f.length() > 0 && f.extension.lowercase() in EXTENSIONS

    /** Incremental sync — needs the CLIP model downloaded. */
    suspend fun sync() = withContext(Dispatchers.IO) {
        if (running || !clip.isReady) return@withContext
        running = true
        try {
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
            if (todo.isEmpty()) { _progress.value = null; return@withContext }

            var done = 0
            _progress.value = Progress(todo.size, 0)
            for (f in todo) {
                currentCoroutineContext().ensureActive()
                _progress.value = Progress(todo.size, done, f.name)
                try {
                    val v = clip.embedImage(f)
                    dao.put(ImgEmbedding(f.absolutePath,
                        VecIndex.pack16(v), f.length(), f.lastModified()))
                } catch (_: Throwable) {
                    dao.remove(f.absolutePath)
                }
                done++
            }
            _progress.value = null
        } finally {
            running = false
            _progress.value = null
        }
    }

    suspend fun rebuildAll() = withContext(Dispatchers.IO) {
        db.imgIndex().clear()
        running = false
        sync()
    }

    /** Text → photo hits, best-first. */
    suspend fun search(query: String, k: Int = 60): List<Hit> = withContext(Dispatchers.Default) {
        if (!clip.isReady) return@withContext emptyList()
        val qv = clip.embedText(query)
        val rows = db.imgIndex().all()
        val knn = VecIndex.knn(qv, rows.map { it.path to it.embedding }, k = k)
        knn.map { (path, sim) -> Hit(path, sim) }
            .filter { it.score >= MIN_SCORE && File(it.path).exists() }
    }
}
