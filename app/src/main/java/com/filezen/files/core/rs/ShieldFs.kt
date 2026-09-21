package com.filezen.files.core.rs

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * .fzrs recovery file — Reed-Solomon parity for one file, PAR2-style.
 *
 * Layout:
 *   "FZRS" u8 ver | u8 nameLen | name | u64 origSize | 32B origSha256
 *   u32 blockSize | u8 k | u8 m | u32 stripeCount
 *   per stripe: (k+m) x 8B hash-prefix | m x blockSize parity bytes
 *
 * A stripe covers k consecutive data blocks; m parity blocks allow
 * repairing up to m corrupt/missing blocks (data or parity) per stripe.
 */
object ShieldFs {

    const val BLOCK = 64 * 1024
    const val K = 8
    const val M = 2

    class CheckResult(
        val file: File,
        val origSize: Long,
        val stripes: Int,
        val badBlocks: Int,
        val badStripes: Int,
        val repairable: Boolean,
        val missing: Boolean,          // original file gone
        val ok: Boolean,               // everything intact
    )

    private fun hash8(b: ByteArray, len: Int): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(b, 0, len)
        return md.digest().copyOf(8)
    }

    /** Create `<file>.fzrs` next to [file]. Returns the shield file. */
    fun create(file: File, onStripe: (done: Int, total: Int) -> Unit = { _, _ -> }): File {
        val rs = ReedSolomon(K, M)
        val out = File(file.parentFile, file.name + ".fzrs")
        val stripes = ((file.length() + BLOCK * K - 1) / (BLOCK * K)).toInt()
        val raf = RandomAccessFile(file, "r")
        val w = RandomAccessFile(out, "rw")
        w.setLength(0)
        // header
        val name = file.name.toByteArray()
        w.write("FZRS".toByteArray()); w.write(1)
        w.write(name.size); w.write(name)
        w.writeLong(file.length())
        val whole = MessageDigest.getInstance("SHA-256")
        // stream twice: once for whole hash… simpler: hash while encoding,
        // patch header at the end.
        w.write(ByteArray(32))
        w.writeInt(BLOCK); w.write(K); w.write(M); w.writeInt(stripes)

        val data = Array(K) { ByteArray(BLOCK) }
        val parity = Array(M) { ByteArray(BLOCK) }
        val meta = ByteArray((K + M) * 8)
        for (s in 0 until stripes) {
            // read k data blocks
            val lens = IntArray(K)
            for (b in 0 until K) {
                data[b].fill(0)
                val want = minOf(BLOCK.toLong(), file.length() - raf.filePointer).toInt()
                if (want > 0) { raf.readFully(data[b], 0, want); lens[b] = want }
            }
            rs.encodeParity(data, parity, 0, BLOCK)
            // meta: hash prefixes of data + parity blocks
            var p = 0
            for (b in 0 until K) { val h = hash8(data[b], BLOCK); System.arraycopy(h, 0, meta, p, 8); p += 8 }
            for (r in 0 until M) { val h = hash8(parity[r], BLOCK); System.arraycopy(h, 0, meta, p, 8); p += 8 }
            w.write(meta)
            for (r in 0 until M) w.write(parity[r])
            onStripe(s + 1, stripes)
        }
        // whole-file hash → patch header
        raf.seek(0)
        val buf = ByteArray(1 shl 20)
        while (true) {
            val n = raf.read(buf); if (n <= 0) break
            whole.update(buf, 0, n)
        }
        w.seek((6 + name.size + 8).toLong())
        w.write(whole.digest())
        w.close(); raf.close()
        return out
    }

    private fun headerOf(shield: File): Pair<RandomAccessFile, Header> {
        val raf = RandomAccessFile(shield, "r")
        val magic = ByteArray(4); raf.readFully(magic)
        require(String(magic) == "FZRS") { "not a .fzrs file" }
        val ver = raf.read()
        val nameLen = raf.read()
        val name = ByteArray(nameLen).also { raf.readFully(it) }
        val size = raf.readLong()
        val sha = ByteArray(32).also { raf.readFully(it) }
        val block = raf.readInt()
        val k = raf.read(); val m = raf.read()
        val stripes = raf.readInt()
        return raf to Header(String(name), size, sha, block, k, m, stripes, raf.filePointer, ver)
    }

    class Header(
        val name: String, val size: Long, val sha256: ByteArray,
        val block: Int, val k: Int, val m: Int, val stripes: Int,
        val dataOffset: Long, val version: Int,
    )

    /** Verify the original file against its shield. */
    fun check(shield: File): CheckResult {
        val (sraf, h) = headerOf(shield)
        val file = File(shield.parentFile, h.name)
        if (!file.exists()) { sraf.close(); return CheckResult(file, h.size, h.stripes, 0, 0, false, true, false) }
        val raf = RandomAccessFile(file, "r")
        val data = Array(h.k) { ByteArray(h.block) }
        val parity = Array(h.m) { ByteArray(h.block) }
        var badBlocks = 0; var badStripes = 0; var repairable = true
        for (s in 0 until h.stripes) {
            val meta = ByteArray((h.k + h.m) * 8).also { sraf.readFully(it) }
            for (r in 0 until h.m) sraf.readFully(parity[r])
            var bad = 0
            for (b in 0 until h.k) {
                data[b].fill(0)
                val off = (s.toLong() * h.k + b) * h.block
                val want = minOf(h.block.toLong(), minOf(h.size, raf.length()) - off).coerceAtLeast(0).toInt()
                if (want > 0) raf.readFully(data[b], 0, want)
                if (!hash8(data[b], h.block).contentEquals(meta.copyOfRange(b * 8, b * 8 + 8))) bad++
            }
            for (r in 0 until h.m)
                if (!hash8(parity[r], h.block).contentEquals(meta.copyOfRange((h.k + r) * 8, (h.k + r) * 8 + 8))) bad++
            if (bad > 0) { badStripes++; badBlocks += bad; if (bad > h.m) repairable = false }
        }
        raf.close(); sraf.close()
        return CheckResult(file, h.size, h.stripes, badBlocks, badStripes, repairable && badBlocks > 0, false, badBlocks == 0)
    }

    /**
     * Repair the original file in place. Returns (recovered, note).
     * Only stripes with ≤m bad shards are repaired; a final SHA-256 check
     * tells whether the file is now byte-identical to when it was shielded.
     */
    fun repair(shield: File): Pair<Boolean, String> {
        val (sraf, h) = headerOf(shield)
        val file = File(shield.parentFile, h.name)
        if (!file.exists()) return false to "Original file is missing — parity alone cannot rebuild a fully deleted file"
        val rs = ReedSolomon(h.k, h.m)
        val raf = RandomAccessFile(file, "rw")
        val data = Array(h.k) { ByteArray(h.block) }
        val parity = Array(h.m) { ByteArray(h.block) }
        var repairedAny = false; var failedStripes = 0
        for (s in 0 until h.stripes) {
            val metaPos = sraf.filePointer
            val meta = ByteArray((h.k + h.m) * 8).also { sraf.readFully(it) }
            for (r in 0 until h.m) sraf.readFully(parity[r])
            val ok = BooleanArray(h.k + h.m) { true }
            var bad = 0
            for (b in 0 until h.k) {
                data[b].fill(0)
                val off = (s.toLong() * h.k + b) * h.block
                val avail = minOf(h.size, raf.length()) - off
                val want = minOf(h.block.toLong(), avail).coerceAtLeast(0).toInt()
                if (want > 0) raf.readFully(data[b], 0, want)
                if (!hash8(data[b], h.block).contentEquals(meta.copyOfRange(b * 8, b * 8 + 8))) { ok[b] = false; bad++ }
            }
            for (r in 0 until h.m)
                if (!hash8(parity[r], h.block).contentEquals(meta.copyOfRange((h.k + r) * 8, (h.k + r) * 8 + 8))) { ok[h.k + r] = false; bad++ }
            if (bad == 0) continue
            if (bad > h.m) { failedStripes++; continue }
            if (!rs.decodeStripe(data, parity, ok, 0, h.block)) { failedStripes++; continue }
            // write recovered blocks back
            for (b in 0 until h.k) {
                if (ok[b]) continue
                val off = (s.toLong() * h.k + b) * h.block
                val want = minOf(h.block.toLong(), h.size - off).coerceAtLeast(0).toInt()
                if (want > 0) { raf.seek(off); raf.write(data[b], 0, want) }
            }
            repairedAny = true
            sraf.seek(metaPos + meta.size + h.m.toLong() * h.block)
        }
        raf.close()
        // Final integrity check
        val md = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { r ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = r.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        val intact = md.digest().contentEquals(h.sha256)
        sraf.close()
        return when {
            intact -> true to "Repaired — file is byte-identical to the shielded original"
            repairedAny -> true to "Partially repaired ($failedStripes stripe(s) exceeded parity)"
            else -> false to "Damage exceeds parity — $failedStripes stripe(s) unrecoverable"
        }
    }
}
