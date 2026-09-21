package com.filezen.files.core.privacy

import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Safe Share — view & strip embedded metadata (EXIF/GPS/XMP/doc properties) before a
 * file leaves the phone. Fully offline.
 *
 * JPEG XMP/trailer scrub adapted from MetadataRemover (MIT License,
 * (c) 2018 Jan Heinrich Reimer — github.com/Crazy-Marvin/MetadataRemover).
 */
object MetadataCleaner {

    data class Field(val label: String, val value: String)

    data class Report(
        val cleanable: Boolean,
        val kind: String,
        val fields: List<Field>,
        val note: String? = null,
    )

    enum class Kind { JPEG, PNG, WEBP, PDF, MEDIA, NONE }

    private val JPEG_EXT = setOf("jpg", "jpeg", "jpe")
    private val PNG_EXT = setOf("png")
    private val WEBP_EXT = setOf("webp")
    private val PDF_EXT = setOf("pdf")
    private val MEDIA_EXT = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "3gp", "3gpp",
        "mp3", "m4a", "aac", "flac", "ogg", "wav", "opus", "wma",
    )

    fun kindOf(path: String): Kind = when (ext(path)) {
        in JPEG_EXT -> Kind.JPEG
        in PNG_EXT -> Kind.PNG
        in WEBP_EXT -> Kind.WEBP
        in PDF_EXT -> Kind.PDF
        in MEDIA_EXT -> Kind.MEDIA
        else -> Kind.NONE
    }

    fun isCleanable(path: String) = kindOf(path).let {
        it == Kind.JPEG || it == Kind.PNG || it == Kind.WEBP || it == Kind.PDF
    }

    fun hasAnySupport(path: String) = kindOf(path) != Kind.NONE

    fun cleanedName(src: File): String {
        val base = src.nameWithoutExtension
        val ext = src.extension
        return if (ext.isEmpty()) "$base-cleaned" else "$base-cleaned.$ext"
    }

    // ------------------------------------------------------------- scan ----

    fun scan(f: File): Report = when (kindOf(f.path)) {
        Kind.JPEG -> scanExif(f, "JPEG")
        Kind.PNG -> scanPng(f)
        Kind.WEBP -> scanWebp(f)
        Kind.PDF -> scanPdf(f)
        Kind.MEDIA -> scanMedia(f)
        Kind.NONE -> Report(false, "File", emptyList(), "No readable metadata for this type")
    }

    private fun scanExif(f: File, kind: String): Report = runCatching {
        val exif = ExifInterface(f)
        val fields = mutableListOf<Field>()
        fun add(label: String, tag: String) {
            exif.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { fields += Field(label, it) }
        }
        val lat = exif.latLong
        if (lat != null) fields += Field(
            "Location (GPS)",
            "%.5f, %.5f".format(Locale.US, lat[0], lat[1]),
        )
        add("Camera", ExifInterface.TAG_MODEL)
        add("Make", ExifInterface.TAG_MAKE)
        add("Taken", ExifInterface.TAG_DATETIME_ORIGINAL)
        add("Modified", ExifInterface.TAG_DATETIME)
        add("Software", ExifInterface.TAG_SOFTWARE)
        add("Artist / author", ExifInterface.TAG_ARTIST)
        add("Owner", ExifInterface.TAG_CAMERA_OWNER_NAME)
        add("Copyright", ExifInterface.TAG_COPYRIGHT)
        add("Description", ExifInterface.TAG_IMAGE_DESCRIPTION)
        add("User comment", ExifInterface.TAG_USER_COMMENT)
        add("Lens", ExifInterface.TAG_LENS_MODEL)
        add("Serial", ExifInterface.TAG_BODY_SERIAL_NUMBER)
        add("Exposure", ExifInterface.TAG_EXPOSURE_TIME)
        add("Aperture", ExifInterface.TAG_F_NUMBER)
        add("ISO", ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
        add("Focal length", ExifInterface.TAG_FOCAL_LENGTH)
        if (exif.isFlipped || exif.rotationDegrees != 0)
            fields += Field("Orientation", "${exif.rotationDegrees}°")
        if (containsXmp(f.readBytes())) fields += Field("XMP block", "present")
        Report(true, kind, fields)
    }.getOrElse { Report(true, kind, emptyList(), "Could not parse EXIF — will still try to clean") }

    private fun scanPng(f: File): Report = runCatching {
        val found = mutableListOf<Field>()
        pngChunks(f) { type, data ->
            when (type) {
                "eXIf" -> found += Field("EXIF block", "present")
                "tIME" -> found += Field("Last-modified time", "present")
                "tEXt", "zTXt", "iTXt" -> {
                    val key = String(data, 0, data.size.coerceAtMost(80))
                        .substringBefore('\u0000').ifBlank { type }
                    found += Field("Text chunk ($type)", key)
                }
            }
            true
        }
        Report(true, "PNG", found)
    }.getOrElse { Report(true, "PNG", emptyList(), "Not a parseable PNG") }

    private fun scanWebp(f: File): Report = runCatching {
        val found = mutableListOf<Field>()
        riffChunks(f) { type, _ ->
            when (type) {
                "EXIF" -> found += Field("EXIF block", "present")
                "XMP " -> found += Field("XMP block", "present")
            }
            true
        }
        Report(true, "WebP", found)
    }.getOrElse { Report(true, "WebP", emptyList(), "Not a parseable WebP") }

    private fun scanPdf(f: File): Report = runCatching {
        val fields = mutableListOf<Field>()
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(f).use { doc ->
            val i = doc.documentInformation
            fun add(label: String, v: Any?) {
                v?.toString()?.takeIf { it.isNotBlank() }?.let { fields += Field(label, it) }
            }
            add("Title", i?.title)
            add("Author", i?.author)
            add("Subject", i?.subject)
            add("Keywords", i?.keywords)
            add("Creator app", i?.creator)
            add("Producer", i?.producer)
            add("Created", i?.creationDate?.time)
            add("Modified", i?.modificationDate?.time)
            if (doc.documentCatalog?.metadata != null) fields += Field("XMP block", "present")
        }
        Report(true, "PDF", fields)
    }.getOrElse { Report(false, "PDF", emptyList(), "Could not open PDF") }

    private fun scanMedia(f: File): Report = runCatching {
        val r = MediaMetadataRetriever()
        r.setDataSource(f.path)
        val fields = mutableListOf<Field>()
        fun add(label: String, key: Int) {
            r.extractMetadata(key)?.takeIf { it.isNotBlank() }?.let { fields += Field(label, it) }
        }
        add("Title", MediaMetadataRetriever.METADATA_KEY_TITLE)
        add("Artist", MediaMetadataRetriever.METADATA_KEY_ARTIST)
        add("Album", MediaMetadataRetriever.METADATA_KEY_ALBUM)
        add("Album artist", MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
        add("Author", MediaMetadataRetriever.METADATA_KEY_AUTHOR)
        add("Writer", MediaMetadataRetriever.METADATA_KEY_WRITER)
        add("Composer", MediaMetadataRetriever.METADATA_KEY_COMPOSER)
        add("Genre", MediaMetadataRetriever.METADATA_KEY_GENRE)
        add("Date", MediaMetadataRetriever.METADATA_KEY_DATE)
        add("Location", MediaMetadataRetriever.METADATA_KEY_LOCATION)
        r.release()
        Report(
            cleanable = false, kind = "Media", fields = fields,
            note = "Audio/video metadata needs a remux — FileZen reports it but cannot strip it yet.",
        )
    }.getOrElse { Report(false, "Media", emptyList(), "Could not read media metadata") }

    // ------------------------------------------------------------ clean ----

    /** Writes a metadata-free copy of [src] to [dst]. Returns false if unsupported. */
    fun clean(src: File, dst: File): Boolean = when (kindOf(src.path)) {
        Kind.JPEG -> cleanJpeg(src, dst)
        Kind.PNG -> cleanPng(src, dst)
        Kind.WEBP -> cleanWebp(src, dst)
        Kind.PDF -> cleanPdf(src, dst)
        else -> false
    }

    private fun cleanJpeg(src: File, dst: File): Boolean = runCatching {
        dst.delete()
        src.copyTo(dst, overwrite = true)
        val exif = ExifInterface(dst.path)
        EXIF_TAGS.forEach { exif.setAttribute(it, null) }
        exif.saveAttributes()
        val cleaned = scrubJpeg(dst.readBytes())
        if (cleaned != null) dst.writeBytes(cleaned)
        true
    }.getOrElse { false }

    private fun cleanPng(src: File, dst: File): Boolean {
        val keep = setOf(
            "IHDR", "PLTE", "IDAT", "IEND", // critical
            "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "tRNS", "bKGD", "pHYs", // render-safe
        )
        return runCatching {
            val sig = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
            RandomAccessFile(src, "r").use { raf ->
                if (raf.length() < 8) return false
                val head = ByteArray(8); raf.readFully(head)
                if (!head.contentEquals(sig)) return false
                ByteArrayOutputStream(src.length().toInt()).use { out ->
                    out.write(sig)
                    while (true) {
                        if (raf.filePointer + 8 > raf.length()) break
                        val len = raf.readInt()
                        val typeBytes = ByteArray(4); raf.readFully(typeBytes)
                        val type = String(typeBytes, Charsets.US_ASCII)
                        if (len < 0 || raf.filePointer + len + 4 > raf.length()) break
                        val data = ByteArray(len); raf.readFully(data)
                        val crc = raf.readInt()
                        if (type in keep) {
                            writeInt(out, len); out.write(typeBytes); out.write(data); writeInt(out, crc)
                        }
                        if (type == "IEND") break
                    }
                    dst.writeBytes(out.toByteArray())
                }
            }
            true
        }.getOrElse { false }
    }

    private fun cleanWebp(src: File, dst: File): Boolean = runCatching {
        val bytes = src.readBytes()
        if (bytes.size < 12 ||
            String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WEBP"
        ) return false
        val keep = setOf("VP8 ", "VP8L", "VP8X", "ALPH", "ICCP", "ANIM", "ANMF")
        val chunks = mutableListOf<Pair<String, ByteArray>>()
        var i = 12
        while (i + 8 <= bytes.size) {
            val type = String(bytes, i, 4)
            val len = le32(bytes, i + 4)
            val end = i + 8 + len
            if (end > bytes.size) break
            var data = bytes.copyOfRange(i + 8, end)
            if (type == "VP8X" && data.size >= 10) {
                // clear EXIF (0x08) and XMP (0x04) capability bits
                data = data.copyOf(); data[0] = (data[0].toInt() and 0xF3).toByte()
            }
            if (type in keep) chunks += type to data
            i = end + (len and 1) // chunks are 2-byte aligned
        }
        if (chunks.isEmpty()) return false
        val body = ByteArrayOutputStream()
        chunks.forEach { (type, data) ->
            body.write(type.toByteArray(Charsets.US_ASCII))
            writeLe32(body, data.size); body.write(data)
            if (data.size and 1 == 1) body.write(0)
        }
        val payload = body.toByteArray()
        val out = ByteArrayOutputStream(12 + payload.size)
        out.write("RIFF".toByteArray()); writeLe32(out, 4 + payload.size)
        out.write("WEBP".toByteArray()); out.write(payload)
        dst.writeBytes(out.toByteArray())
        true
    }.getOrElse { false }

    private fun cleanPdf(src: File, dst: File): Boolean = runCatching {
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(src).use { doc ->
            doc.documentInformation =
                com.tom_roush.pdfbox.pdmodel.PDDocumentInformation()
            doc.documentCatalog?.cosObject?.removeItem(
                com.tom_roush.pdfbox.cos.COSName.METADATA,
            )
            doc.save(dst)
        }
        true
    }.getOrElse { false }

    // ------------------------------------------ container chunk walkers ----

    private fun pngChunks(f: File, cb: (type: String, data: ByteArray) -> Boolean) {
        RandomAccessFile(f, "r").use { raf ->
            if (raf.length() < 8) return
            raf.skipBytes(8)
            while (raf.filePointer + 8 <= raf.length()) {
                val len = raf.readInt()
                val typeBytes = ByteArray(4); raf.readFully(typeBytes)
                val type = String(typeBytes, Charsets.US_ASCII)
                if (len < 0 || raf.filePointer + len + 4 > raf.length()) break
                val data = ByteArray(len); raf.readFully(data)
                raf.skipBytes(4) // crc
                if (!cb(type, data) || type == "IEND") break
            }
        }
    }

    private fun riffChunks(f: File, cb: (type: String, data: ByteArray) -> Boolean) {
        val bytes = f.readBytes()
        if (bytes.size < 12 || String(bytes, 0, 4) != "RIFF") return
        var i = 12
        while (i + 8 <= bytes.size) {
            val type = String(bytes, i, 4)
            val len = le32(bytes, i + 4)
            if (i + 8 + len > bytes.size) break
            if (!cb(type, bytes.copyOfRange(i + 8, i + 8 + len))) break
            i += 8 + len + (len and 1)
        }
    }

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF); out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF); out.write(v and 0xFF)
    }

    private fun writeLe32(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
    }

    private fun ext(path: String) = path.substringAfterLast('.', "").lowercase(Locale.ROOT)

    // ----------------------------------- JPEG XMP / trailer byte scrubber --
    // Adapted from MetadataRemover (MIT) — removes XMP APP1 segments and any
    // trailer after EOI (e.g. Samsung SEF) with no recompression.

    private val XMP_SIG = "http://ns.adobe.com/xap/1.0/ ".toByteArray(Charsets.US_ASCII)
    private val XMP_EXT_SIG =
        "http://ns.adobe.com/xmp/extension/ ".toByteArray(Charsets.US_ASCII)

    private fun u(a: ByteArray, idx: Int) = a[idx].toInt() and 0xFF

    private fun startsWith(data: ByteArray, off: Int, sig: ByteArray): Boolean {
        if (off + sig.size > data.size) return false
        for (k in sig.indices) if (data[off + k] != sig[k]) return false
        return true
    }

    private fun containsXmp(data: ByteArray): Boolean =
        indexOf(data, XMP_SIG) >= 0 || indexOf(data, XMP_EXT_SIG) >= 0

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun scrubJpeg(data: ByteArray): ByteArray? {
        if (data.size < 2 || u(data, 0) != 0xFF || u(data, 1) != 0xD8) return null
        val out = ByteArrayOutputStream(data.size)
        out.write(0xFF); out.write(0xD8)
        var i = 2
        while (i + 1 < data.size) {
            if (u(data, i) != 0xFF) return null
            var m = i + 1
            while (m < data.size && u(data, m) == 0xFF) m++
            if (m >= data.size) break
            val code = u(data, m)
            when {
                code == 0xDA -> {
                    if (m + 2 >= data.size) return null
                    val len = (u(data, m + 1) shl 8) or u(data, m + 2)
                    val headerEnd = m + 1 + len
                    if (headerEnd > data.size) return null
                    out.write(0xFF); out.write(0xDA)
                    out.write(data, m + 1, len)
                    var j = headerEnd
                    var resumed = false
                    while (j < data.size) {
                        val b = u(data, j)
                        if (b != 0xFF) { out.write(b); j++; continue }
                        if (j + 1 >= data.size) { out.write(0xFF); j++; break }
                        val n = u(data, j + 1)
                        when {
                            n == 0x00 -> { out.write(0xFF); out.write(0x00); j += 2 }
                            n in 0xD0..0xD7 -> { out.write(0xFF); out.write(n); j += 2 }
                            n == 0xD9 -> { out.write(0xFF); out.write(0xD9); return out.toByteArray() }
                            else -> { i = j; resumed = true; break }
                        }
                    }
                    if (!resumed) break
                }
                code == 0xD9 -> { out.write(0xFF); out.write(0xD9); return out.toByteArray() }
                code in 0xD0..0xD7 || code == 0x01 -> { out.write(0xFF); out.write(code); i = m + 1 }
                else -> {
                    if (m + 2 >= data.size) return null
                    val len = (u(data, m + 1) shl 8) or u(data, m + 2)
                    val payloadStart = m + 3
                    val payloadLen = len - 2
                    if (payloadLen < 0 || payloadStart + payloadLen > data.size) return null
                    val dropXmp = code == 0xE1 &&
                        (startsWith(data, payloadStart, XMP_SIG) ||
                            startsWith(data, payloadStart, XMP_EXT_SIG))
                    if (!dropXmp) {
                        out.write(0xFF); out.write(code)
                        out.write(u(data, m + 1)); out.write(u(data, m + 2))
                        out.write(data, payloadStart, payloadLen)
                    }
                    i = payloadStart + payloadLen
                }
            }
        }
        return out.toByteArray()
    }

    // EXIF tags wiped on clean. saveAttributes() rebuilds APP1 so anything not
    // re-set stays out; the scrubber then removes residual XMP/trailers.
    private val EXIF_TAGS = arrayOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_SATELLITES, ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_MEASURE_MODE, ExifInterface.TAG_GPS_DOP,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_USER_COMMENT, ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_SPECIFICATION, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_EXPOSURE_MODE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_MAKER_NOTE, ExifInterface.TAG_RELATED_SOUND_FILE,
        ExifInterface.TAG_IMAGE_UNIQUE_ID, ExifInterface.TAG_XMP,
    )
}
