package com.filezen.files

import com.filezen.files.core.imohash.ImoHash
import com.filezen.files.core.tlsh.Tlsh
import com.filezen.files.core.zst.SeekableZstd
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

class TlshTest {

    @Test
    fun `identical files give identical digest`() {
        val data = Random(42).nextBytes(4096)
        val a = File.createTempFile("aaa", ".bin").also { it.writeBytes(data) }
        val b = File.createTempFile("bbb", ".bin").also { it.writeBytes(data) }
        val d1 = Tlsh.digest(a)!!
        val d2 = Tlsh.digest(b)!!
        assertEquals(d1.digest(), d2.digest())
        assertEquals(0, d1.diff(d2))
    }

    @Test
    fun `similar files score low`() {
        val base = Random(7).nextBytes(8192)
        val mod = base.copyOf().also { Random(9).nextBytes(64).copyInto(it, 4000) }
        val f1 = File.createTempFile("sm1", ".bin").also { it.writeBytes(base) }
        val f2 = File.createTempFile("sm2", ".bin").also { it.writeBytes(mod) }
        val d1 = Tlsh.digest(f1)!!
        val d2 = Tlsh.digest(f2)!!
        assertTrue("similar files should diff < 120, got ${d1.diff(d2)}", d1.diff(d2) < 120)
    }

    @Test
    fun `different files score high`() {
        val f1 = File.createTempFile("df1", ".bin").also { it.writeBytes(Random(1).nextBytes(8192)) }
        val f2 = File.createTempFile("df2", ".bin").also { it.writeBytes(Random(2).nextBytes(8192)) }
        val d1 = Tlsh.digest(f1)!!
        val d2 = Tlsh.digest(f2)!!
        assertTrue(d1.diff(d2) > 50)
    }

    @Test
    fun `digest has T1 format`() {
        val f = File.createTempFile("fff", ".bin").also { it.writeBytes(Random(3).nextBytes(2048)) }
        val d = Tlsh.digest(f)!!.digest()
        assertTrue(d.startsWith("T1"))
        assertEquals(72, d.length)
    }

    private fun res(name: String): Tlsh =
        Tlsh.digest(javaClass.getResourceAsStream("/$name")!!, 4096)!!

    @Test
    fun `golden digest matches upstream tlsh`() {
        // verified against the real C++ tlsh (v4.12, BUCKETS_128 build)
        assertEquals("T1C7819524E6514D7D1F175ADCD04E44DF554FCDE302C5002517F186D1C510294440ED1D", res("t_in.bin").digest())
        assertEquals("T1CB817DC213546BB01AC9E10F4A97C6452FBDDA76A78FFCA64CEE81B30C080122CB9B45", res("t_rand.bin").digest())
        assertEquals("T15A817DC213545BB00A89E10E0997CB492F7DDA76A78FFD974CEE91B71C080162CB5B55", res("t_r2.bin").digest())
    }

    @Test
    fun `golden diff scores match upstream`() {
        assertEquals(20, res("t_rand.bin").diff(res("t_r2.bin")))
        assertEquals(307, res("t_in.bin").diff(res("t_rand.bin")))
    }

    @Test
    fun `short files give no digest`() {
        val f = File.createTempFile("tny", ".bin").also { it.writeBytes(Random(4).nextBytes(40)) }
        assertNull(Tlsh.digest(f))
    }
}

class ImoHashTest {

    @Test
    fun `identical big files give identical hash`() {
        val data = Random(11).nextBytes(300 * 1024)
        val a = File.createTempFile("aaa", ".bin").also { it.writeBytes(data) }
        val b = File.createTempFile("bbb", ".bin").also { it.writeBytes(data) }
        assertArrayEquals(ImoHash.hashFile(a.absolutePath), ImoHash.hashFile(b.absolutePath))
    }

    @Test
    fun `size is encoded in digest prefix`() {
        val f = File.createTempFile("sss", ".bin").also { it.writeBytes(ByteArray(500) { 1 }) }
        val h = ImoHash.hashFile(f.absolutePath)
        // 500 = 0x1F4 → varint f4 03
        assertEquals(0xF4.toByte(), h[0])
        assertEquals(0x03.toByte(), h[1])
    }

    @Test
    fun `golden vector matches upstream murmur3`() {
        // bytes(0..255)*4, 1024 B < 128KB → whole-file murmur3, varint(1024)=80 08 prefix
        val f = File.createTempFile("ggg", ".bin").also { it.writeBytes(ByteArray(1024) { (it % 256).toByte() }) }
        assertEquals("80086c31cedcd969c645cc05166c87ed", ImoHash.hex(f.absolutePath))
    }

    @Test
    fun `different content different hash`() {
        val a = File.createTempFile("aaa", ".bin").also { it.writeBytes(Random(5).nextBytes(200 * 1024)) }
        val b = File.createTempFile("bbb", ".bin").also { it.writeBytes(Random(6).nextBytes(200 * 1024)) }
        assertFalse(ImoHash.hashFile(a.absolutePath).contentEquals(ImoHash.hashFile(b.absolutePath)))
    }

    @Test
    fun `same tail different middle differ`() {
        val d = Random(8).nextBytes(200 * 1024)
        val a = File.createTempFile("aaa", ".bin").also { it.writeBytes(d) }
        val d2 = d.copyOf(); d2[100 * 1024] = (d2[100 * 1024] + 1).toByte()
        val b = File.createTempFile("bbb", ".bin").also { it.writeBytes(d2) }
        assertFalse(ImoHash.hashFile(a.absolutePath).contentEquals(ImoHash.hashFile(b.absolutePath)))
    }
}

class ZstdTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `roundtrip byte identical`() {
        val data = Random(21).nextBytes(700 * 1024) // > 5 blocks of 128KB
        val src = tmp.newFile("big.bin").also { it.writeBytes(data) }
        val r = SeekableZstd.compress(src)
        assertTrue(r.frames >= 5)
        val out = SeekableZstd.decompress(r.out)
        assertArrayEquals(
            MessageDigest.getInstance("SHA-256").digest(data),
            MessageDigest.getInstance("SHA-256").digest(out.readBytes()),
        )
        assertEquals(data.size.toLong(), out.length())
    }

    @Test
    fun `seek table is a standard skippable frame`() {
        val data = Random(22).nextBytes(300 * 1024)
        val src = tmp.newFile("s.bin").also { it.writeBytes(data) }
        val r = SeekableZstd.compress(src)
        val bytes = r.out.readBytes()
        val n = r.frames
        val tail = bytes.size - (8 + n * 8 + 9)
        val magic = java.nio.ByteBuffer.wrap(bytes, tail, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        assertEquals(0x184D2A50, magic) // skippable frame header
        val footer = java.nio.ByteBuffer.wrap(bytes, bytes.size - 9, 9).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(n, footer.int)
        footer.get()
        assertEquals(0x8F92EAB1.toInt(), footer.int)
    }

    @Test
    fun `smaller than input on compressible data`() {
        val data = ByteArray(400 * 1024) { (it % 251).toByte() }
        val src = tmp.newFile("rep.bin").also { it.writeBytes(data) }
        val r = SeekableZstd.compress(src)
        assertTrue(r.compressedSize < r.origSize / 2)
    }

    @Test
    fun `bad file rejected`() {
        val junk = tmp.newFile("junk.zst").also { it.writeBytes(Random(3).nextBytes(1024)) }
        assertThrows(Exception::class.java) { SeekableZstd.decompress(junk) }
    }
}
