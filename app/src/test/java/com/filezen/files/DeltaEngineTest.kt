package com.filezen.files

import com.filezen.files.core.delta.DeltaEngine
import com.filezen.files.core.delta.FastCdc
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Random

class DeltaEngineTest {

    private fun tmp(bytes: ByteArray): File {
        val f = File.createTempFile("cdc", ".bin")
        f.writeBytes(bytes)
        f.deleteOnExit()
        return f
    }

    private fun rnd(seed: Long, size: Int): ByteArray =
        ByteArray(size).also { Random(seed).nextBytes(it) }

    @Test
    fun `identical files reuse every chunk`() {
        val data = rnd(7, 700_000)
        val old = tmp(data); val new = tmp(data)
        val st = DeltaEngine.diff(DeltaEngine.signature(old), new)
        assertEquals(0, st.insertBytes)
        assertEquals(data.size.toLong(), st.reusedBytes)
    }

    @Test
    fun `small edit keeps most chunks reusable`() {
        val data = rnd(11, 900_000)
        // Change ~1KB in the middle.
        data[400_000] = (data[400_000] + 1).toByte()
        data[400_001] = (data[400_001] + 1).toByte()
        val edited = tmp(data)
        val orig = tmp(rnd(11, 900_000))
        val st = DeltaEngine.diff(DeltaEngine.signature(orig), edited)
        assertTrue("expected >85% reuse, got ${st.reusedPercent}%", st.reusedPercent > 85)
    }

    @Test
    fun `insert at start still finds shifted chunks`() {
        // The CDC superpower vs fixed chunks: prepend 1KB, everything shifts.
        val body = rnd(21, 600_000)
        val old = tmp(body)
        val new = tmp(rnd(22, 1_024) + body)
        val st = DeltaEngine.diff(DeltaEngine.signature(old), new)
        assertTrue("expected >80% reuse after insert, got ${st.reusedPercent}%",
            st.reusedPercent > 80)
    }

    @Test
    fun `patch round-trip rebuilds identical bytes`() {
        val old = tmp(rnd(31, 500_000))
        val newBytes = rnd(31, 500_000)
        newBytes[100_000] = 42; newBytes[250_000] = 7
        val appended = newBytes + rnd(32, 60_000)
        val new = tmp(appended)
        val patch = File.createTempFile("patch", ".fzpatch")
        DeltaEngine.createPatch(old, new, patch)
        assertTrue(patch.length() < appended.size)
        val rebuilt = File.createTempFile("rebuilt", ".bin")
        DeltaEngine.applyPatch(old, patch, rebuilt)
        assertArrayEquals(appended, rebuilt.readBytes())
    }

    @Test
    fun `empty file produces empty signature`() {
        val empty = tmp(ByteArray(0))
        assertEquals(0, DeltaEngine.signature(empty).chunks.size)
    }

    @Test
    fun `chunker respects size bounds`() {
        val cdc = FastCdc(minSize = 4_096, avgSize = 16_384, maxSize = 65_536)
        val chunks = mutableListOf<Pair<Long, Int>>()
        cdc.chunks(rnd(9, 400_000).inputStream()) { off, b -> chunks += off to b.size }
        assertTrue(chunks.isNotEmpty())
        // contiguous coverage
        var off = 0L
        chunks.forEach { (o, len) -> assertEquals(off, o); off += len }
        assertEquals(400_000L, off)
        assertTrue(chunks.all { it.second <= 65_536 })
        assertTrue(chunks.dropLast(1).all { it.second >= 4_096 })
    }
}
