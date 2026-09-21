package com.filezen.files.core.zst

import com.github.luben.zstd.Zstd
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Seekable zstd archive — the official zstd "seekable" format
 * (contrib/seekable_format): the payload is split into independent frames
 * plus a seek-table footer (magic 0x8F92EAB1), so any block can be
 * decompressed without reading the whole file — and standard zstd tools
 * still decompress it end-to-end.
 */
object SeekableZstd {

    private const val BLOCK = 128 * 1024
    private const val SEEKABLE_MAGIC = 0x8F92EAB1.toInt()

    data class Result(val out: File, val origSize: Long, val compressedSize: Long, val frames: Int)

    /** Compress [src] into [src].zst as a seekable archive. */
    fun compress(src: File, level: Int = 9): Result {
        val out = File(src.parentFile, src.name + ".zst")
        val frameSizes = mutableListOf<Long>()
        RandomAccessFile(src, "r").use { raf ->
            out.outputStream().buffered().use { o ->
                val buf = ByteArray(BLOCK)
                var total = 0L
                while (true) {
                    val n = raf.read(buf)
                    if (n <= 0) break
                    val frame = Zstd.compress(buf.copyOf(n), level)
                    o.write(frame)
                    frameSizes.add((frame.size.toLong() shl 32) or n.toLong())
                    total += n
                }
                // seek table = skippable frame: magic 0x184D2A50 + u32 size,
                // then per frame (u32 cSize, u32 dSize), u32 numFrames,
                // u8 flags(0), u32 magic 0x8F92EAB1
                val n = frameSizes.size
                val ft = ByteBuffer.allocate(8 + n * 8 + 9).order(ByteOrder.LITTLE_ENDIAN)
                ft.putInt(0x184D2A50)
                ft.putInt(n * 8 + 9)
                for (fs in frameSizes) {
                    ft.putInt((fs shr 32).toInt())
                    ft.putInt(fs.toInt())
                }
                ft.putInt(n)
                ft.put(0)   // flags: no checksum field
                ft.putInt(SEEKABLE_MAGIC)
                o.write(ft.array())
                return Result(out, total, out.length(), frameSizes.size)
            }
        }
    }

    /** Decompress a .zst produced by [compress] back to its original name. */
    fun decompress(zst: File): File {
        val frames = readFooter(zst)
        val origName = zst.name.removeSuffix(".zst")
        val out = File(zst.parentFile, origName + ".restored")
        RandomAccessFile(zst, "r").use { raf ->
            out.outputStream().buffered().use { o ->
                var pos = 0L
                for ((cSize, dSize) in frames) {
                    val comp = ByteArray(cSize.toInt())
                    raf.seek(pos); raf.readFully(comp)
                    pos += cSize
                    o.write(Zstd.decompress(comp, dSize.toInt()))
                }
            }
        }
        return out
    }

    /** Reads the seek-table footer → list of (compressedSize, decompressedSize). */
    private fun readFooter(zst: File): List<LongArray> {
        RandomAccessFile(zst, "r").use { raf ->
            val len = raf.length()
            raf.seek(len - 9)
            val tail = ByteArray(9)
            raf.readFully(tail)
            val bb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            val numFrames = bb.int
            bb.get() // flags
            require(bb.int == SEEKABLE_MAGIC) { "not a seekable zstd file" }
            val tableBytes = numFrames * 8
            raf.seek(len - 9 - tableBytes)
            val tb = ByteArray(tableBytes)
            raf.readFully(tb)
            val tbb = ByteBuffer.wrap(tb).order(ByteOrder.LITTLE_ENDIAN)
            return List(numFrames) { longArrayOf(tbb.int.toLong() and 0xFFFFFFFFL, tbb.int.toLong() and 0xFFFFFFFFL) }
        }
    }
}
