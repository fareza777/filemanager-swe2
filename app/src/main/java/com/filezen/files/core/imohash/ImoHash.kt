package com.filezen.files.core.imohash

import java.io.File
import java.io.RandomAccessFile

/**
 * imohash — fast content hash for large files: samples 16KB from the
 * beginning, middle and end (files < 128KB are hashed whole) plus the file
 * size encoded as a varint inside the 16-byte digest.
 * Ported from github.com/kalafut/imohash (MPL-2.0). Digest bytes are
 * identical to upstream.
 */
object ImoHash {

    const val SIZE = 16
    private const val SAMPLE_THRESHOLD = 128 * 1024
    private const val SAMPLE_SIZE = 16 * 1024

    fun hashFile(path: String): ByteArray {
        val f = File(path)
        RandomAccessFile(f, "r").use { raf ->
            val size = raf.length()
            val h = Murmur3()
            if (size < SAMPLE_THRESHOLD || size < 4L * SAMPLE_SIZE) {
                val buf = ByteArray(64 * 1024)
                var n: Int
                while (raf.read(buf).also { n = it } > 0) h.update(buf, 0, n)
            } else {
                val buf = ByteArray(SAMPLE_SIZE)
                raf.readFully(buf); h.update(buf, 0, SAMPLE_SIZE)        // start
                raf.seek(size / 2); raf.readFully(buf); h.update(buf, 0, SAMPLE_SIZE) // middle
                raf.seek(size - SAMPLE_SIZE); raf.readFully(buf); h.update(buf, 0, SAMPLE_SIZE) // end
            }
            val digest = h.digest()
            putUvarint(digest, size)
            return digest
        }
    }

    fun hex(path: String): String =
        hashFile(path).joinToString("") { "%02x".format(it) }

    /** Writes value as LEB128 varint at the start of buf, overwriting it. */
    private fun putUvarint(buf: ByteArray, value: Long) {
        var v = value
        var i = 0
        while (v >= 0x80 && i < buf.size - 1) {
            buf[i++] = (v or 0x80).toByte()
            v = v ushr 7
        }
        buf[i] = v.toByte()
    }

    /** MurmurHash3 x64 128-bit, seed 0 — used by imohash. */
    private class Murmur3 {
        private var h1 = 0L
        private var h2 = 0L
        private var len = 0
        private val tail = ByteArray(16)
        private var tailLen = 0

        fun update(data: ByteArray, off0: Int, len0: Int) {
            var off = off0
            var len = len0
            this.len += len
            // drain leftover tail first
            if (tailLen > 0) {
                val need = 16 - tailLen
                val take = minOf(need, len)
                System.arraycopy(data, off, tail, tailLen, take)
                tailLen += take; off += take; len -= take
                if (tailLen == 16) { mix(tail, 0); tailLen = 0 }
            }
            val nblocks = len / 16
            for (i in 0 until nblocks) mix(data, off + i * 16)
            val rem = len % 16
            if (rem > 0) {
                System.arraycopy(data, off + nblocks * 16, tail, 0, rem)
                tailLen = rem
            }
        }

        private fun mix(data: ByteArray, off: Int) {
            val k1 = le64(data, off)
            val k2 = le64(data, off + 8)
            h1 = h1 xor fmixK1(k1)
            h1 = java.lang.Long.rotateLeft(h1, 27)
            h1 += h2
            h1 = h1 * 5 + 0x52dce729
            h2 = h2 xor fmixK2(k2)
            h2 = java.lang.Long.rotateLeft(h2, 31)
            h2 += h1
            h2 = h2 * 5 + 0x38495ab5
        }

        fun digest(): ByteArray {
            // finalize tail
            var k1 = 0L; var k2 = 0L
            for (i in tailLen - 1 downTo 8) k2 = (k2 shl 8) or (tail[i].toLong() and 0xFF)
            for (i in minOf(tailLen, 8) - 1 downTo 0) k1 = (k1 shl 8) or (tail[i].toLong() and 0xFF)
            if (tailLen > 8) h2 = h2 xor fmixK2(k2)
            if (tailLen > 0) h1 = h1 xor fmixK1(k1)
            h1 = h1 xor len.toLong()
            h2 = h2 xor len.toLong()
            h1 += h2; h2 += h1
            h1 = fmix64(h1); h2 = fmix64(h2)
            h1 += h2; h2 += h1
            val out = ByteArray(16)
            le64(out, 0, h1); le64(out, 8, h2)
            return out
        }

        private fun fmixK1(k: Long): Long {
            var x = k * -0x783C846EEEBDAC2BL   // 0x87c37b91114253d5
            x = java.lang.Long.rotateLeft(x, 31)
            x *= 0x4cf5ad432745937fL            // c2
            return x
        }

        private fun fmixK2(k: Long): Long {
            var x = k * 0x4cf5ad432745937fL     // c2
            x = java.lang.Long.rotateLeft(x, 33)
            x *= -0x783C846EEEBDAC2BL           // c1
            return x
        }

        private fun fmix64(k0: Long): Long {
            var k = k0
            k = k xor (k ushr 33)
            k *= 0xff51afd7ed558ccduL.toLong()
            k = k xor (k ushr 33)
            k *= 0xc4ceb9fe1a85ec53uL.toLong()
            k = k xor (k ushr 33)
            return k
        }

        private fun le64(b: ByteArray, off: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            return v
        }

        private fun le64(b: ByteArray, off: Int, v: Long) {
            for (i in 0 until 8) b[off + i] = (v ushr (8 * i)).toByte()
        }
    }
}
