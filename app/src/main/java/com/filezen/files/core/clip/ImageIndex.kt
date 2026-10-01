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

    private val ocr = OcrEngine()
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress
    val indexedCount = db.imgIndex().count()

    /** New work is coalesced through this flag + unique-work queueing, so a
     *  flood of kicks never spawns overlapping passes. */
    private val queued = AtomicBoolean(false)

    companion object {
        /** webp excluded: on Android it's almost never a user photo — it's
         *  WhatsApp/Telegram stickers, emoji packs and app icons that pollute
         *  every result. jpg/png/heic cover real cameras. */
        val EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "heic", "heif")
        /** CLIP cosine sims on normalised vecs live ~0.15–0.40 — below this the
         *  match is noise. */
        private const val MIN_SCORE = 0.21f
        /** Score added when the image's OCR text contains every query token —
         *  strong enough to outrank visually-similar but textually-irrelevant
         *  photos, small enough not to drown real visual matches. */
        private const val OCR_BOOST = 0.10f
        private const val TAG = "ImageIndex"
    }

    fun indexable(f: File): Boolean =
        f.isFile && f.length() > 0 && f.extension.lowercase() in EXTENSIONS &&
            !isJunkPath(f.absolutePath)

    /** Paths that carry app-generated imagery rather than user photos:
     *  hidden directories (.Statuses, .thumbnails, .trash, .Shared), sticker
     *  packs (WhatsApp & friends ship hundreds of webp stickers/icons that
     *  embed fine but pollute every result), and emoji/sticker caches. */
    fun isJunkPath(path: String): Boolean {
        val segs = path.lowercase().split('/')
        if (segs.any { it.startsWith('.') && it.length > 1 }) return true
        return segs.any { it.contains("sticker") || it.contains("emoji") }
    }

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
        // indexable() already encodes: file exists, supported extension,
        // non-junk path — stale rows from an older ruleset get cleaned too.
        for (stale in stored.keys.filter { !indexable(File(it)) }) {
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

        // Progress counts everything already embedded too — a resumed pass
        // shows "N/M" where N includes prior work, so it doesn't look like a
        // restart from zero.
        var done = candidates.count { f ->
            stored[f.absolutePath]?.embedding?.isNotEmpty() == true }
        val total = done + todo.size
        _progress.value = Progress(total, done)
        for (f in todo) {
            currentCoroutineContext().ensureActive()
            _progress.value = Progress(total, done, f.name)
            try {
                val v = clip.embedImage(f)
                val text = ocr.read(f)
                dao.put(ImgEmbedding(f.absolutePath,
                    VecIndex.pack16(v), f.length(), f.lastModified(), text))
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
        // Multi-variant search: the query plus its Indonesian→English
        // translations (Indonesian scores ~0.10 below English on the same
        // images). Each image keeps its best score across variants.
        val variants = clip.expandQueries(query)
        val qvs = variants.map { clip.embedText(it) }
        val rows = db.imgIndex().all().filter { it.embedding.isNotEmpty() }
        val pairs = rows.map { it.path to it.embedding }
        // Sim per path = max cosine over all query variants — take the union
        // of each variant's knn so a strong match in any variant survives.
        val best = HashMap<String, Float>()
        for (qv in qvs) {
            for ((path, sim) in VecIndex.knn(qv, pairs, k = k))
                if (sim > (best[path] ?: 0f)) best[path] = sim
        }
        // OCR channel: a receipt/screenshot whose recognised text literally
        // contains every query token gets a boost — visual CLIP can't read
        // text, so this is what makes "bukti transfer" find transfer-proof
        // screenshots instead of just visually similar photos. Tokens are
        // drawn from every variant, so an Indonesian query also matches a
        // receipt written in English.
        val toksPerVariant = variants.map { v ->
            v.lowercase().split(Regex("\\s+")).filter { it.length >= 3 } }
        val ocrByPath = rows.associate { it.path to it.ocr }
        val hits = best.map { (path, sim) ->
            val text = ocrByPath[path].orEmpty().lowercase()
            // Boost = best per-variant match: all tokens of one variant →
            // full boost, any token → half.
            var boost = 0f
            if (text.isNotEmpty()) {
                for (toks in toksPerVariant) {
                    if (toks.isEmpty()) continue
                    val matched = toks.count { text.contains(it) }
                    if (matched == toks.size) { boost = OCR_BOOST; break }
                    if (matched > 0 && boost == 0f) boost = OCR_BOOST / 2
                }
            }
            Hit(path, sim + boost)
        }.sortedByDescending { it.score }
            .filter { it.score >= MIN_SCORE && indexable(File(it.path)) }
        // Relative cutoff: junk neighbours can sit just above the floor for
        // any query — keep only hits reasonably close to the best match. The
        // loose factor keeps recall high so everything relevant shows up.
        val top = hits.firstOrNull()?.score ?: return@withContext emptyList()
        return@withContext hits.filter { it.score >= maxOf(MIN_SCORE, top * 0.65f) }
    }
}
