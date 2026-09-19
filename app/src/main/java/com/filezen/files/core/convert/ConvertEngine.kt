package com.filezen.files.core.convert

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import com.filezen.files.core.model.FileEntry
import com.filezen.files.core.model.FileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/**
 * Offline file conversion + recompression. No network, no codecs beyond what
 * Android ships: bitmap formats, and PDF via PdfDocument.
 */
object ConvertEngine {

    enum class Target(val label: String, val ext: String) {
        PNG("PNG", "png"),
        JPEG("JPEG", "jpg"),
        WEBP("WebP", "webp"),
        PDF("PDF", "pdf"),
    }

    /** Formats a file can realistically be converted into. */
    fun targetsFor(e: FileEntry): List<Target> = when (e.type) {
        FileType.IMAGE -> listOf(Target.PNG, Target.JPEG, Target.WEBP, Target.PDF)
        FileType.TEXT, FileType.DOCUMENT ->
            if (e.extension in setOf("txt", "md", "log", "csv")) listOf(Target.PDF)
            else emptyList()
        else -> emptyList()
    }

    /** Whether lossy recompression is worthwhile for this file. */
    fun canCompress(e: FileEntry): Boolean =
        e.type == FileType.IMAGE && e.extension in setOf("jpg", "jpeg", "png", "webp")

    data class Result(val output: File, val bytesSaved: Long)

    /** Convert [src] to [target]; the output lands next to the source. */
    suspend fun convert(src: File, target: Target): Result = withContext(Dispatchers.IO) {
        val out = uniqueIn(src.parentFile!!, src.nameWithoutExtension + "." + target.ext)
        when (target) {
            Target.PNG, Target.JPEG, Target.WEBP -> {
                val bmp = decodeCapped(src) ?: error("Cannot read image")
                writeBitmap(bmp, out, target)
                bmp.recycle()
            }
            Target.PDF -> writePdf(src, out)
        }
        Result(out, src.length() - out.length())
    }

    /**
     * Recompress an image in place when it saves space: decode at capped
     * resolution, encode JPEG q80 (or keep WebP), replace only if smaller.
     * Returns the (possibly unchanged) file and how many bytes were freed.
     */
    suspend fun compressImage(src: File): Result = withContext(Dispatchers.IO) {
        val bmp = decodeCapped(src, maxDim = 2048) ?: error("Cannot read image")
        val fmt = if (src.extension == "webp") Target.WEBP else Target.JPEG
        val tmp = File(src.parentFile, src.nameWithoutExtension + ".fztmp")
        try {
            writeBitmap(bmp, tmp, fmt, quality = 80)
            bmp.recycle()
            if (tmp.length() < src.length()) {
                val out = if (fmt.ext == src.extension || fmt == Target.JPEG &&
                    src.extension in setOf("jpg", "jpeg")) src
                    else uniqueIn(src.parentFile!!, src.nameWithoutExtension + "." + fmt.ext)
                val saved = src.length() - tmp.length()
                if (out == src) {
                    src.delete()
                    tmp.renameTo(src)
                } else {
                    tmp.renameTo(out)
                    // keep original when the extension changed? No — the goal is
                    // reclaiming space, so the original is removed after a
                    // successful recompress to a different-format file.
                    src.delete()
                }
                Result(out, saved)
            } else {
                tmp.delete()
                Result(src, 0)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun decodeCapped(src: File, maxDim: Int = 4096): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim ||
            bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
        return BitmapFactory.decodeFile(
            src.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun writeBitmap(bmp: Bitmap, out: File, target: Target, quality: Int = 92) {
        val src = if (target == Target.JPEG) flattenAlpha(bmp) else bmp
        val fmt = when (target) {
            Target.PNG -> Bitmap.CompressFormat.PNG
            Target.JPEG -> Bitmap.CompressFormat.JPEG
            Target.WEBP -> if (Build.VERSION.SDK_INT >= 30)
                Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            Target.PDF -> error("PDF is handled by writePdf")
        }
        out.outputStream().use { src.compress(fmt, quality, it) }
    }

    /** JPEG has no alpha — draw onto white first. */
    private fun flattenAlpha(bmp: Bitmap): Bitmap {
        if (!bmp.hasAlpha()) return bmp
        val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(flat)
        c.drawColor(Color.WHITE)
        c.drawBitmap(bmp, 0f, 0f, null)
        return flat
    }

    private fun writePdf(src: File, out: File) {
        val ext = src.extension
        val doc = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4
            val page = doc.startPage(pageInfo)
            if (ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")) {
                decodeCapped(src, 2480)?.let { bmp ->
                    val scale = min(595f / bmp.width, 842f / bmp.height)
                    val w = (bmp.width * scale).toInt(); val h = (bmp.height * scale).toInt()
                    val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
                    page.canvas.drawBitmap(scaled, (595 - w) / 2f, (842 - h) / 2f, null)
                    bmp.recycle(); scaled.recycle()
                } ?: page.canvas.drawText("(image could not be decoded)", 40f, 80f, textPaint())
            } else {
                // text-ish → flowed lines
                val paint = textPaint()
                val lines = src.readLines().flatMap { wrapLine(it, 88) }
                var y = 60f
                for (line in lines.take(52)) {
                    page.canvas.drawText(line, 40f, y, paint); y += 16f
                }
            }
            doc.finishPage(page)
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    private fun textPaint() = Paint().apply {
        color = Color.BLACK; textSize = 10f; isAntiAlias = true
    }

    private fun wrapLine(s: String, w: Int): List<String> =
        if (s.length <= w) listOf(s) else s.chunked(w)

    private fun uniqueIn(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"); i++
        }
        return f
    }
}
