package com.filezen.files

import com.filezen.files.core.rs.Galois
import com.filezen.files.core.rs.ReedSolomon
import com.filezen.files.core.rs.ShieldFs
import com.filezen.files.core.vcd.TimeMachine
import com.filezen.files.core.vcd.Vcdiff
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.random.Random

class RsTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    @Test fun `galois tables are consistent`() {
        assertEquals(1, Galois.expTable[0])
        for (a in 1 until 256) {
            val inv = Galois.inverse(a).toInt() and 0xFF
            assertEquals(1, Galois.multiply(a, inv).toInt() and 0xFF)
        }
    }

    @Test fun `matrix sanity k2m1`() {
        val rs = ReedSolomon(2, 1)
        // vandermonde rows [0,1],[1,1],[2,1] · inv(top) → parity row [3,2]
        assertEquals(3.toByte(), rs.matrix[2, 0])
        assertEquals(2.toByte(), rs.matrix[2, 1])
        val d0 = byteArrayOf(9, 77, 0, -1)
        val d1 = byteArrayOf(100, 2, 5, 40)
        val data = arrayOf(d0.copyOf(), d1.copyOf())
        val parity = arrayOf(ByteArray(4))
        rs.encodeParity(data, parity, 0, 4)
        for (i in 0 until 4) {
            val expect = (Galois.multiply(3, d0[i].toInt() and 0xFF).toInt() xor
                Galois.multiply(2, d1[i].toInt() and 0xFF).toInt()).toByte()
            assertEquals(expect, parity[0][i])
        }
        // lose d0: decode from [d1, parity]
        data[0].fill(0)
        val ok = booleanArrayOf(false, true, true)
        assertTrue(rs.decodeStripe(data, parity, ok, 0, 4))
        assertArrayEquals(d0, data[0])
    }

    @Test fun `encode-decode stripe recovers two bad blocks`() {
        val rs = ReedSolomon(8, 2)
        val rnd = Random(42)
        val data = Array(8) { rnd.nextBytes(64) }
        val orig2 = data[2].copyOf(); val orig6 = data[6].copyOf()
        val parity = Array(2) { ByteArray(64) }
        rs.encodeParity(data, parity, 0, 64)
        // corrupt data blocks 2 and 6
        data[2].fill(0x55); data[6].fill(0x55)
        val ok = BooleanArray(10) { true }; ok[2] = false; ok[6] = false
        assertTrue(rs.decodeStripe(data, parity, ok, 0, 64))
        assertArrayEquals(orig2, data[2])
        assertArrayEquals(orig6, data[6])
    }

    @Test fun `shield round-trip repairs corrupted file`() {
        val f = tmp.newFile("photo.jpg")
        val rnd = Random(7)
        f.writeBytes(rnd.nextBytes(600_000)) // >1 stripe
        val original = sha(f.readBytes())
        val shield = ShieldFs.create(f)
        assertTrue(shield.exists())
        // intact check
        var c = ShieldFs.check(shield)
        assertTrue(c.ok)
        // corrupt a 64KB block (within stripe 0, ≤ m=2 blocks)
        RandomAccessFile(f, "rw").use { r ->
            r.seek(100_000); r.write(ByteArray(50_000) { 0x7E })
        }
        c = ShieldFs.check(shield)
        assertFalse(c.ok); assertTrue(c.repairable)
        val (ok, _) = ShieldFs.repair(shield)
        assertTrue(ok)
        assertArrayEquals(original, sha(f.readBytes()))
    }

    @Test fun `unrepairable when damage exceeds parity`() {
        val f = tmp.newFile("big.bin")
        f.writeBytes(Random(3).nextBytes(600_000))
        val shield = ShieldFs.create(f)
        // corrupt 3 blocks in one stripe (m=2 can't cover)
        RandomAccessFile(f, "rw").use { r ->
            r.seek(0); r.write(ByteArray(200_000) { 0x11 })
        }
        val c = ShieldFs.check(shield)
        assertFalse(c.ok); assertFalse(c.repairable)
    }
}

class VcdTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `code table has 256 entries and expected anchors`() {
        val t = Vcdiff.TABLE
        assertEquals(256, t.size)
        assertArrayEquals(intArrayOf(2, 0, 0, 0, 0, 0), t[0])          // RUN
        assertArrayEquals(intArrayOf(1, 0, 0, 0, 0, 0), t[1])          // ADD size0
        assertEquals(1, t[2][0]); assertEquals(1, t[2][1])             // ADD size1
        assertEquals(3, t[19][0]); assertEquals(0, t[19][2])           // COPY size0 mode0
        assertEquals(3, t[34][0]); assertEquals(18, t[34][1])          // COPY size18 mode0
        assertEquals(3, t[162][0]); assertEquals(8, t[162][2])         // COPY mode8
        assertEquals(1, t[163][0]); assertEquals(3, t[163][3])         // ADD1+COPY4 m0
        assertEquals(3, t[255][0]); assertEquals(8, t[255][2]); assertEquals(1, t[255][3])
    }

    @Test fun `round trip identical files`() {
        val src = "hello world hello world".toByteArray()
        val d = Vcdiff.encode(src, src)
        assertArrayEquals(src, Vcdiff.decode(src, d))
    }

    @Test fun `round trip edited file`() {
        val src = ("The quick brown fox jumps over the lazy dog. " +
            "Pack my box with five dozen liquor jugs. ").repeat(40).toByteArray()
        val tgt = src.copyOf()
        // edits: change a word mid-file, append a tail
        "QUICK".toByteArray().copyInto(tgt, 4)
        val tgt2 = tgt + "NEW TAIL CONTENT".toByteArray()
        val d = Vcdiff.encode(src, tgt2)
        assertTrue(d.size < tgt2.size / 2)   // delta much smaller than target
        assertArrayEquals(tgt2, Vcdiff.decode(src, d))
    }

    @Test fun `round trip empty source`() {
        val tgt = "brand new file".toByteArray()
        val d = Vcdiff.encode(ByteArray(0), tgt)
        assertArrayEquals(tgt, Vcdiff.decode(ByteArray(0), d))
    }

    @Test fun `time machine stores delta chain and restores versions`() {
        val f = tmp.newFile("notes.txt")
        f.writeText("version one content, reasonably long so deltas have meat")
        val v1 = TimeMachine.snapshot(f)
        assertEquals(1, v1.n)
        f.writeText("version one content, EDITED a bit, plus some appended text")
        val v2 = TimeMachine.snapshot(f)
        assertEquals(2, v2.n)
        val store = TimeMachine.storeFor(f)!!
        assertEquals(2, store.versions.size)
        // delta file much smaller than v1 full
        val d = java.io.File(store.dir, "v2.vcd")
        assertTrue(d.exists() && d.length() < f.length())
        // restore v1 → original bytes
        val out = TimeMachine.restore(store, 1)
        assertEquals("version one content, reasonably long so deltas have meat", out.readText())
        // restore v2 → current bytes
        val out2 = TimeMachine.restore(store, 2)
        assertEquals(f.readText(), out2.readText())
    }
}
