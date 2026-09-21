package com.filezen.files

import com.filezen.files.core.privacy.MetadataCleaner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

class MetadataCleanerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun w32(v: Int) {
            out.write((v ushr 24) and 0xFF); out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF); out.write(v and 0xFF)
        }
        w32(data.size)
        out.write(type.toByteArray())
        out.write(data)
        w32(0) // fake CRC — the cleaner copies it verbatim
        return out.toByteArray()
    }

    private fun makePng(withJunk: Boolean): File {
        val f = tmp.newFile("photo.png")
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        out.write(pngChunk("IHDR", ByteArray(13) { it.toByte() }))
        if (withJunk) {
            out.write(pngChunk("tEXt", "Author\u0000John Doe".toByteArray()))
            out.write(pngChunk("eXIf", ByteArray(200) { 1 }))
            out.write(pngChunk("tIME", ByteArray(7)))
        }
        out.write(pngChunk("IDAT", ByteArray(64) { 2 }))
        out.write(pngChunk("IEND", ByteArray(0)))
        f.writeBytes(out.toByteArray())
        return f
    }

    private fun chunkTypes(f: File): List<String> {
        val types = mutableListOf<String>()
        RandomAccessFile(f, "r").use { raf ->
            raf.skipBytes(8)
            while (raf.filePointer + 8 <= raf.length()) {
                val len = raf.readInt()
                val t = ByteArray(4); raf.readFully(t)
                types += String(t)
                raf.skipBytes(len + 4)
            }
        }
        return types
    }

    @Test
    fun `png clean drops text exif and time chunks`() {
        val src = makePng(withJunk = true)
        val dst = tmp.newFile("photo-cleaned.png")
        assertTrue(MetadataCleaner.clean(src, dst))
        val types = chunkTypes(dst)
        assertTrue("IHDR" in types && "IDAT" in types && "IEND" in types)
        assertFalse("tEXt" in types || "eXIf" in types || "tIME" in types)
    }

    @Test
    fun `png scan reports text chunks`() {
        val src = makePng(withJunk = true)
        val r = MetadataCleaner.scan(src)
        assertTrue(r.cleanable)
        assertTrue(r.fields.any { it.label.contains("EXIF") })
        assertTrue(r.fields.any { it.value.contains("Author") })
    }

    @Test
    fun `webp clean drops exif and xmp`() {
        val f = tmp.newFile("pic.webp")
        fun chunk(type: String, data: ByteArray): ByteArray {
            val o = ByteArrayOutputStream()
            o.write(type.toByteArray())
            o.write(data.size and 0xFF); o.write((data.size ushr 8) and 0xFF)
            o.write((data.size ushr 16) and 0xFF); o.write((data.size ushr 24) and 0xFF)
            o.write(data); if (data.size and 1 == 1) o.write(0)
            return o.toByteArray()
        }
        val payload = ByteArrayOutputStream()
        payload.write(chunk("VP8X", ByteArray(10) { 0x0C })) // EXIF+XMP flags set
        payload.write(chunk("EXIF", ByteArray(40) { 9 }))
        payload.write(chunk("XMP ", ByteArray(30) { 7 }))
        payload.write(chunk("VP8 ", ByteArray(32) { 3 }))
        val body = payload.toByteArray()
        val head = ByteArrayOutputStream()
        head.write("RIFF".toByteArray())
        val sz = 4 + body.size
        head.write(sz and 0xFF); head.write((sz ushr 8) and 0xFF)
        head.write((sz ushr 16) and 0xFF); head.write((sz ushr 24) and 0xFF)
        head.write("WEBP".toByteArray()); head.write(body)
        f.writeBytes(head.toByteArray())

        val scan = MetadataCleaner.scan(f)
        assertTrue(scan.fields.any { it.label.contains("EXIF") })
        assertTrue(scan.fields.any { it.label.contains("XMP") })

        val dst = tmp.newFile("pic-cleaned.webp")
        assertTrue(MetadataCleaner.clean(f, dst))
        val scan2 = MetadataCleaner.scan(dst)
        assertTrue(scan2.fields.isEmpty())
        // VP8X flags cleared
        val b = dst.readBytes()
        assertEquals(0.toByte(), b[20])
    }

    @Test
    fun `unsupported types are not cleanable`() {
        assertFalse(MetadataCleaner.isCleanable("x.mp4"))
        assertFalse(MetadataCleaner.isCleanable("x.txt"))
        assertTrue(MetadataCleaner.isCleanable("x.jpg"))
        assertTrue(MetadataCleaner.isCleanable("x.PDF"))
    }
}
