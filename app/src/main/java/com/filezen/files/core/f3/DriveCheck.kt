package com.filezen.files.core.f3

import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

/**
 * Fake flash drive check — FileZen's port of F3 (Fight Flash Fraud,
 * https://github.com/AltraMayor/f3, GPL-3.0).
 *
 * Fakes advertise e.g. 512 GB but physically hold 16 GB; writes beyond the
 * real capacity wrap around and silently destroy earlier data. The check
 * fills the target with blocks stamped with their position, then reads
 * everything back: each block must still contain its own stamp. From the
 * verified bytes you get the *real* capacity plus true write/read speeds.
 */
object DriveCheck {

    private const val BLOCK = 4096
    private const val FILE_SIZE = 64L * 1024 * 1024   // 64 MB per test file
    private const val LEAVE_FREE = 128L * 1024 * 1024 // keep 128 MB free
    private val MAGIC = 0xF35A_4369_5AA5_5AA5u.toLong()
    private val MULT = 0xFF51AFD7ED558CCDu.toLong()
    private val GOLDEN = 0x9E3779B97F4A7C15u.toLong()

    enum class Phase { WRITE, VERIFY }
    data class Progress(
        val phase: Phase,
        val doneBytes: Long,
        val totalBytes: Long,
        val mbps: Double,
    )

    data class Report(
        val target: File,
        val written: Long,
        val verified: Long,      // bytes still correct
        val corrupted: Long,     // bytes with wrong stamp/content
        val claimedTotal: Long,  // what the FS reports as total size
        val writeMbps: Double,
        val readMbps: Double,
        val cancelled: Boolean,
    ) {
        /** Rough guess of the drive's real capacity. */
        val realCapacity: Long get() = verified
        val suspicious: Boolean get() = corrupted > 0
        val integrity: Float get() =
            if (written == 0L) 1f else verified.toFloat() / written
    }

    /** Deterministic content for the block at global index [g]. */
    private fun fillBlock(g: Long, buf: ByteArray) {
        buf[0] = (g ushr 56).toByte(); buf[1] = (g ushr 48).toByte()
        buf[2] = (g ushr 40).toByte(); buf[3] = (g ushr 32).toByte()
        buf[4] = (g ushr 24).toByte(); buf[5] = (g ushr 16).toByte()
        buf[6] = (g ushr 8).toByte();  buf[7] = g.toByte()
        buf[8] = (MAGIC ushr 56).toByte(); buf[9] = (MAGIC ushr 48).toByte()
        buf[10] = (MAGIC ushr 40).toByte(); buf[11] = (MAGIC ushr 32).toByte()
        buf[12] = (MAGIC ushr 24).toByte(); buf[13] = (MAGIC ushr 16).toByte()
        buf[14] = (MAGIC ushr 8).toByte();  buf[15] = MAGIC.toByte()
        // Deterministic fill — cheap to regenerate for verification.
        var x = g * GOLDEN
        for (i in 16 until buf.size) {
            x = x xor (x ushr 33)
            x *= MULT
            buf[i] = (x ushr 56).toByte()
        }
    }

    private fun verifyBlock(g: Long, buf: ByteArray): Boolean {
        val expected = ByteArray(buf.size)
        fillBlock(g, expected)
        return buf.contentEquals(expected)
    }

    private fun testFiles(dir: File): List<File> =
        dir.listFiles { f -> f.name.startsWith("fz-f3-") && f.name.endsWith(".bin") }
            ?.sortedBy { it.name } ?: emptyList()

    /** Remove any leftover test files. */
    fun cleanup(dir: File) = testFiles(dir).forEach { it.delete() }

    /**
     * Run the check. [limitBytes] caps how much is written (0 = all free
     * space minus a safety margin). Test files are always removed after.
     */
    suspend fun run(
        dir: File,
        limitBytes: Long = 0,
        onProgress: (Progress) -> Unit,
        space: (File) -> Pair<Long, Long> = { f ->
            val s = StatFs(f.absolutePath); s.totalBytes to s.availableBytes
        },
    ): Report = withContext(Dispatchers.IO) {
        cleanup(dir)
        val (claimed, free) = space(dir)
        val budget = if (limitBytes > 0)
            minOf(limitBytes, free - LEAVE_FREE) else free - LEAVE_FREE
        if (budget <= 0) return@withContext Report(dir, 0, 0, 0, claimed, 0.0, 0.0, false)

        var written = 0L
        var blockIndex = 0L
        val buf = ByteArray(BLOCK)
        val writeStart = System.nanoTime()
        var cancelled = false
        val files = ArrayList<File>()

        fun elapsedMbps(done: Long, startNanos: Long) =
            done.toDouble() / ((System.nanoTime() - startNanos) / 1e9).coerceAtLeast(1e-9) / 1e6

        // ---- WRITE ----
        try {
            var fileIx = 0
            while (written < budget) {
                coroutineContext.ensureActive()
                val f = File(dir, "fz-f3-%03d.bin".format(fileIx++))
                val left = budget - written
                val fileTarget = minOf(FILE_SIZE, left)
                RandomAccessFile(f, "rw").use { raf ->
                    var inFile = 0L
                    while (inFile < fileTarget && written < budget) {
                        coroutineContext.ensureActive()
                        fillBlock(blockIndex, buf)
                        raf.write(buf)
                        inFile += BLOCK; written += BLOCK; blockIndex++
                        if (blockIndex % 512 == 0L)
                            onProgress(Progress(Phase.WRITE, written, budget,
                                elapsedMbps(written, writeStart)))
                    }
                }
                rafSync(f)
                files += f
            }
        } catch (_: CancellationException) {
            cancelled = true
        } catch (_: Throwable) {
            // Disk full early / IO error — still verify what got written.
        }
        val writeSecs = (System.nanoTime() - writeStart) / 1e9

        // ---- VERIFY ----
        var verified = 0L
        var corrupted = 0L
        val readStart = System.nanoTime()
        var g = 0L
        try {
            for (f in files) {
                if (!f.exists()) { corrupted += FILE_SIZE; continue }
                RandomAccessFile(f, "r").use { raf ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = raf.read(buf)
                        if (n < BLOCK) break
                        if (verifyBlock(g, buf)) verified += BLOCK else corrupted += BLOCK
                        g++
                        if (g % 512 == 0L)
                            onProgress(Progress(Phase.VERIFY, g * BLOCK, written,
                                elapsedMbps(g * BLOCK, readStart)))
                    }
                }
            }
        } catch (_: CancellationException) {
            cancelled = true
        } catch (_: Throwable) {
        } finally {
            cleanup(dir)
        }
        val readSecs = (System.nanoTime() - readStart) / 1e9

        Report(dir, written, verified, corrupted, claimed,
            written.toDouble() / writeSecs.coerceAtLeast(1e-9) / 1e6,
            (verified + corrupted).toDouble() / readSecs.coerceAtLeast(1e-9) / 1e6,
            cancelled)
    }

    private fun rafSync(f: File) = runCatching {
        RandomAccessFile(f, "rw").use { it.fd.sync() }
    }
}
