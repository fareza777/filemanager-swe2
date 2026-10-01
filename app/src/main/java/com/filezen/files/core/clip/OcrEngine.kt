package com.filezen.files.core.clip

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** On-device OCR via ML Kit (bundled Latin model — works offline, no GMS
 *  needed). Returns "" when recognition is unavailable or finds nothing, so
 *  indexing never blocks on it. Stored per image, it makes photo search find
 *  text-heavy images CLIP can't read: receipts, bukti transfer, tickets,
 *  documents, memes with captions. */
class OcrEngine {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Recognise up to [MAX_CHARS] chars of text in the image. Never throws. */
    suspend fun read(f: File): String = withContext(Dispatchers.Default) {
        // Full resolution — downsampling garbles small receipt/screenshot text
        // ("BUKTI TRANSFER" read as "BJKTI THsEA" at 50%). ML Kit manages its
        // own internal sizing; it needs legible pixels, not a smaller bitmap.
        val bmp = runCatching {
            BitmapFactory.decodeFile(f.absolutePath)
        }.getOrNull() ?: return@withContext ""
        val img = InputImage.fromBitmap(bmp, 0)
        suspendCancellableCoroutine<String> { cont ->
            runCatching {
                recognizer.process(img)
                    .addOnSuccessListener { t -> bmp.recycle(); cont.resume(t.text.take(MAX_CHARS)) }
                    .addOnFailureListener { bmp.recycle(); cont.resume("") }
            }.onFailure { bmp.recycle(); cont.resume("") }
        }
    }

    private companion object { const val MAX_CHARS = 400 }
}
