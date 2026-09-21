package com.filezen.files.core.delta

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Delta transfer engine on top of [FastCdc] — the FileZen port of Google's
 * cdc-file-transfer (Apache-2.0). An old file is reduced to a signature of
 * content-defined chunks; a new file is re-chunked and every chunk that
 * already exists in the signature is referenced instead of copied, producing a
 * `.fzpatch` that is typically a small fraction of the new file.
 *
 * Format (.fzpatch v1):
 *   magic "FZP1" | newSize (long) | op count (int)
 *   op: 0 = COPY  oldChunkIndex(int) len(int)
 *       1 = INSERT len(int) bytes[len]
 * For COPY, oldChunkIndex refers to the old file's chunk list (offset known).
 */
object DeltaEngine {
    private const val MAGIC = "FZP1"
    private val cdc = FastCdc()

    data class Signature(val chunks: List<FastCdc.Chunk>) {
        val size: Long get() = chunks.sumOf { it.length.toLong() }
    }

    data class DeltaStats(
        val newSize: Long,
        val totalChunks: Int,
        val reusedChunks: Int,
        val reusedBytes: Long,
        val insertBytes: Long,
    ) {
        val reusedPercent: Int
            get() = if (newSize <= 0) 100 else ((reusedBytes * 100) / newSize).toInt()
    }

    /** Content signature of a file: chunk hashes keyed by SHA-256. */
    fun signature(file: File): Signature {
        val chunks = mutableListOf<FastCdc.Chunk>()
        FileInputStream(file).use { ins ->
            cdc.chunks(ins) { off, bytes ->
                chunks += FastCdc.Chunk(off, bytes.size,
                    MessageDigest.getInstance("SHA-256").digest(bytes))
            }
        }
        return Signature(chunks)
    }

    /** How much of [newFile] can be rebuilt from [oldSig]. */
    fun diff(oldSig: Signature, newFile: File): DeltaStats {
        val oldHashes = oldSig.chunks.associate { it.sha256.toHex() to it }
        var reused = 0; var reusedB = 0L; var insertB = 0L; var total = 0
        FileInputStream(newFile).use { ins ->
            cdc.chunks(ins) { _, bytes ->
                total++
                if (oldHashes.containsKey(bytes.sha256())) { reused++; reusedB += bytes.size }
                else insertB += bytes.size
            }
        }
        return DeltaStats(newFile.length(), total, reused, reusedB, insertB)
    }

    /**
     * Write a patch describing [newFile] in terms of [oldFile]'s chunks.
     * INSERT data is embedded; COPY ops point at old chunks.
     */
    fun createPatch(oldFile: File, newFile: File, out: File): DeltaStats {
        val oldSig = signature(oldFile)
        val oldByHash = HashMap<String, Int>()
        oldSig.chunks.forEachIndexed { i, ch -> oldByHash[ch.sha256.toHex()] = i }

        var reused = 0; var reusedB = 0L; var insertB = 0L; var total = 0
        DataOutputStream(out.outputStream().buffered()).use { o ->
            o.writeBytes(MAGIC)
            o.writeLong(newFile.length())
            // Chunk count unknown ahead of time — stream into a temp then rewrite.
            val opsTmp = File.createTempFile("fzpatch", ".ops", out.parentFile)
            var count = 0
            try {
                DataOutputStream(opsTmp.outputStream().buffered()).use { oops ->
                    FileInputStream(newFile).use { ins ->
                        cdc.chunks(ins) { _, bytes ->
                            total++
                            val idx = oldByHash[bytes.sha256()]
                            if (idx != null) {
                                oops.writeByte(0)
                                oops.writeInt(idx)
                                oops.writeInt(bytes.size)
                                reused++; reusedB += bytes.size
                            } else {
                                oops.writeByte(1)
                                oops.writeInt(bytes.size)
                                oops.write(bytes)
                                insertB += bytes.size
                            }
                            count++
                        }
                    }
                }
                o.writeInt(count)
                opsTmp.inputStream().use { it.copyTo(o) }
            } finally { opsTmp.delete() }
        }
        return DeltaStats(newFile.length(), total, reused, reusedB, insertB)
    }

    /** Rebuild the new file from [oldFile] + [patch] into [outFile]. */
    fun applyPatch(oldFile: File, patch: File, outFile: File) {
        val oldSig = signature(oldFile)
        RandomAccessFile(oldFile, "r").use { old ->
            DataInputStream(patch.inputStream().buffered()).use { i ->
                val magic = ByteArray(4).also { i.readFully(it) }.decodeToString()
                require(magic == MAGIC) { "Not a FileZen patch file" }
                i.readLong() // newSize — informational
                val count = i.readInt()
                outFile.outputStream().buffered().use { out ->
                    repeat(count) {
                        when (i.readByte().toInt()) {
                            0 -> {
                                val ch = oldSig.chunks[i.readInt()]
                                val len = i.readInt()
                                old.seek(ch.offset)
                                old.channel.transferTo(ch.offset, len.toLong(),
                                    java.nio.channels.Channels.newChannel(out))
                            }
                            1 -> {
                                val len = i.readInt()
                                val buf = ByteArray(len)
                                i.readFully(buf)
                                out.write(buf)
                            }
                            else -> error("Bad patch op")
                        }
                    }
                }
            }
        }
    }

    /**
     * How much of [newFile] already exists inside [oldFile] as content chunks —
     * used by folder sync to report real delta savings when both sides are
     * local (remote stores stream whole files; delta needs random access).
     */
    fun reuseStats(oldFile: File, newFile: File): DeltaStats? {
        if (!oldFile.isFile || oldFile.length() == 0L) return null
        return runCatching { diff(signature(oldFile), newFile) }.getOrNull()
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
