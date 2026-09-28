package com.filezen.files.core.clip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On-device CLIP semantic image search — text query → matching photos.
 *
 * Two ONNX models, downloaded on demand (~200 MB once, into internal storage):
 *  - vision: OpenAI CLIP ViT-B/32 image encoder (Xenova q4 build) →
 *    `image_embeds` [1,512] from `pixel_values` [1,3,224,224].
 *  - text: sentence-transformers/clip-ViT-B-32-multilingual-v1 — a
 *    multilingual transformer whose text space is aligned to that same vision
 *    space, so Indonesian queries ("foto rapat", "kucing di sofa") hit the
 *    same embeddings. It outputs `last_hidden_state`; FileZen does the
 *    sentence-transformers post-pipeline itself: masked mean-pool →
 *    768→512 linear projection (weights pulled from the model's safetensors
 *    blob) → L2 normalise.
 *
 * Preprocessing matches OpenAI CLIP: centre-crop-square → 224×224, RGB,
 * normalised with the CLIP mean/std.
 */
class ClipEngine(private val app: Context) {

    private val dir get() = File(app.filesDir, "clip")
    private val visionFile get() = File(dir, "vision_model_q4.onnx")
    private val textFile get() = File(dir, "text_model_int8.onnx")
    private val vocabFile get() = File(dir, "vocab.txt")
    private val denseFile get() = File(dir, "dense.safetensors")

    /** Everything needed locally? (partial downloads resume — see [download]) */
    val isReady: Boolean get() =
        visionFile.length() > 1_000_000 && textFile.length() > 1_000_000 &&
            vocabFile.length() > 100_000 && denseFile.length() > 1_000_000

    data class DownloadProgress(val fileIx: Int, val fileCount: Int,
        val label: String, val bytesDone: Long, val bytesTotal: Long)
    private val _dl = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _dl
    @Volatile private var downloading = false

    private val http = OkHttpClient()

    private data class Part(val url: String, val file: File, val label: String, val size: Long)

    private fun parts(): List<Part> = listOf(
        Part("$HF/Xenova/clip-vit-base-patch32/resolve/main/onnx/vision_model_q4.onnx",
            visionFile, "image encoder", 63_642_858),
        Part("$HF/sentence-transformers/clip-ViT-B-32-multilingual-v1/resolve/main/onnx/model_qint8_arm64.onnx",
            textFile, "multilingual text encoder", 135_336_307),
        Part("$HF/sentence-transformers/clip-ViT-B-32-multilingual-v1/resolve/main/vocab.txt",
            vocabFile, "tokenizer", 995_526),
        Part("$HF/sentence-transformers/clip-ViT-B-32-multilingual-v1/resolve/main/2_Dense/model.safetensors",
            denseFile, "projection", 1_572_984),
    )

    /** Download every missing model part (resumable). Throws on failure. */
    suspend fun download() = withContext(Dispatchers.IO) {
        if (downloading || isReady) return@withContext
        downloading = true
        try {
            dir.mkdirs()
            val list = parts()
            val total = list.sumOf { it.size }
            val doneBefore = list.sumOf { it.file.length().coerceAtMost(it.size) }
            for ((ix, p) in list.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (p.file.length() >= p.size && p.file.length() - p.size < 1024) continue
                fetch(p, ix, list.size, total, doneBefore)
            }
            // sanity — every file must be fully present
            if (!isReady) error("Download incomplete — try again")
        } finally {
            downloading = false
            _dl.value = null
        }
    }

    private fun fetch(p: Part, ix: Int, count: Int, grandTotal: Long, doneBefore: Long) {
        val have = p.file.length()
        val req = Request.Builder().url(p.url).apply {
            if (have > 0) header("Range", "bytes=$have-")
        }.build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} downloading ${p.label}")
            val body = resp.body ?: error("empty body")
            val append = have > 0 && resp.header("Content-Range") != null
            RandomAccessFile(p.file, "rw").use { raf ->
                if (!append) raf.setLength(0) else raf.seek(have)
                val buf = ByteArray(256 * 1024)
                var n: Int
                var got = if (append) have else 0L
                while (body.byteStream().read(buf).also { n = it } > 0) {
                    raf.write(buf, 0, n); got += n
                    _dl.value = DownloadProgress(
                        ix, count, p.label,
                        doneBefore + got, grandTotal)
                }
            }
        }
    }

    // ---------- inference ----------

    private val env by lazy { OrtEnvironment.getEnvironment() }
    @Volatile private var vision: OrtSession? = null
    @Volatile private var text: OrtSession? = null
    @Volatile private var tokenizer: WordPieceTokenizer? = null
    @Volatile private var dense: FloatArray? = null // [512,768] row-major

    @Synchronized
    private fun ensureLoaded() {
        if (!isReady) error("model not downloaded")
        if (vision == null) vision = env.createSession(visionFile.absolutePath)
        if (text == null) text = env.createSession(textFile.absolutePath)
        if (tokenizer == null) tokenizer = WordPieceTokenizer(vocabFile)
        if (dense == null) dense = loadDense(denseFile)
    }

    /** CLIP preprocessing: centre-crop square, resize 224, normalise. */
    suspend fun embedImage(src: File): FloatArray = withContext(Dispatchers.Default) {
        ensureLoaded()
        val bmp = decode(src) ?: error("cannot decode image")
        val px = preprocess(bmp)
        bmp.recycle()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(px), longArrayOf(1, 3, 224, 224))
            .use { t ->
                vision!!.run(mapOf("pixel_values" to t)).use { out ->
                    val v = (out.get("image_embeds").get().value
                        as Array<FloatArray>)[0]
                    normalize(v.copyOf())
                }
            }
    }

    /** Tokenise → transformer → masked mean-pool → Dense → L2-norm. */
    suspend fun embedText(query: String): FloatArray = withContext(Dispatchers.Default) {
        ensureLoaded()
        val (ids, mask) = tokenizer!!.encode(query)
        val shape = longArrayOf(1, ids.size.toLong())
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { idsT ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { maskT ->
                text!!.run(mapOf(
                    "input_ids" to idsT,
                    "attention_mask" to maskT,
                )).use { out ->
                    val hidden = (out.get("last_hidden_state").get().value
                        as Array<Array<FloatArray>>)[0]
                    val pooled = meanPool(hidden, mask)
                    val proj = matVec(dense!!, pooled) // [512,768]·[768] → [512]
                    normalize(proj)
                }
            }
        }
    }

    private fun decode(src: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 640 || bounds.outHeight / (sample * 2) >= 640)
            sample *= 2
        val bmp = BitmapFactory.decodeFile(src.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        // Photos store rotation in EXIF — BitmapFactory ignores it, and CLIP is
        // not rotation-invariant, so apply the orientation before embedding.
        val rot = runCatching {
            ExifInterface(src.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val deg = when (rot) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) return bmp
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height,
            Matrix().apply { postRotate(deg) }, true)
        if (out != bmp) bmp.recycle()
        return out
    }

    private fun preprocess(bmp: Bitmap): FloatArray {
        val side = min(bmp.width, bmp.height)
        val x = (bmp.width - side) / 2; val y = (bmp.height - side) / 2
        val crop = Bitmap.createScaledBitmap(Bitmap.createBitmap(bmp, x, y, side, side), 224, 224, true)
        val px = IntArray(224 * 224)
        crop.getPixels(px, 0, 224, 0, 0, 224, 224)
        crop.recycle()
        val out = FloatArray(3 * 224 * 224)
        for (i in px.indices) {
            val r = (px[i] shr 16) and 0xFF; val g = (px[i] shr 8) and 0xFF; val b = px[i] and 0xFF
            out[i] = (r / 255f - 0.48145466f) / 0.26862954f
            out[224 * 224 + i] = (g / 255f - 0.4578275f) / 0.26130258f
            out[2 * 224 * 224 + i] = (b / 255f - 0.40821073f) / 0.27577711f
        }
        return out
    }

    /** Mean over real tokens (masked) → [768]. */
    private fun meanPool(hidden: Array<FloatArray>, mask: LongArray): FloatArray {
        val dim = hidden[0].size
        val out = FloatArray(dim)
        var n = 0f
        for (i in hidden.indices) {
            if (i >= mask.size || mask[i] == 0L) continue
            n += 1f
            for (d in 0 until dim) out[d] += hidden[i][d]
        }
        if (n > 0) for (d in 0 until dim) out[d] /= n
        return out
    }

    private fun matVec(m: FloatArray, v: FloatArray): FloatArray {
        val cols = v.size
        val rows = m.size / cols
        val out = FloatArray(rows)
        for (r in 0 until rows) {
            var s = 0f; val base = r * cols
            for (c in 0 until cols) s += m[base + c] * v[c]
            out[r] = s
        }
        return out
    }

    private fun normalize(v: FloatArray): FloatArray {
        var n = 0f; for (x in v) n += x * x
        n = sqrt(n)
        if (n > 0) for (i in v.indices) v[i] /= n
        return v
    }

    /** safetensors: 8-byte LE header len + JSON {"linear.weight":{shape,offsets}} + f32 data. */
    private fun loadDense(f: File): FloatArray {
        val bytes = f.readBytes()
        val hlen = java.nio.ByteBuffer.wrap(bytes, 0, 8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).long.toInt()
        val header = String(bytes, 8, hlen)
        // single tensor "linear.weight" [512,768] F32 — pull its data offsets
        val m = Regex("\"data_offsets\"\\s*:\\s*\\[(\\d+)\\s*,\\s*(\\d+)\\]").find(header)
            ?: error("bad safetensors header")
        val start = 8 + hlen + m.groupValues[1].toInt()
        val end = 8 + hlen + m.groupValues[2].toInt()
        val n = (end - start) / 4
        val buf = java.nio.ByteBuffer.wrap(bytes, start, n * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = FloatArray(n); buf.get(out)
        return out
    }

    fun close() {
        runCatching { vision?.close() }; runCatching { text?.close() }
        vision = null; text = null
    }

    companion object {
        private const val HF = "https://huggingface.co"
        /** ~201 MB total — shown in the download UI. */
        const val TOTAL_BYTES = 63_642_858L + 135_336_307L + 995_526L + 1_572_984L
    }
}
