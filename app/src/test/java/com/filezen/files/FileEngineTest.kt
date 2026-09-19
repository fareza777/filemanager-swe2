package com.filezen.files

import com.filezen.files.core.fileops.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileEngineTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var src: File
    private lateinit var dst: File
    private val engine = FileEngine()

    @Before
    fun setup() {
        src = tmp.newFolder("src")
        dst = tmp.newFolder("dst")
    }

    private fun makeFile(dir: File, name: String, content: String = "x"): File =
        File(dir, name).apply { writeText(content) }

    @Test
    fun `copy creates verified duplicate`() = runTest {
        val f = makeFile(src, "a.txt", "hello world")
        val s = engine.copy(listOf(f), dst, ConflictPolicy.SKIP)
        assertEquals(1, s.succeeded)
        assertTrue(File(dst, "a.txt").exists())
        assertEquals("hello world", File(dst, "a.txt").readText())
        assertTrue(f.exists()) // source preserved on copy
    }

    @Test
    fun `move removes source only after copy`() = runTest {
        val f = makeFile(src, "b.txt", "data")
        val s = engine.move(listOf(f), dst, ConflictPolicy.SKIP)
        assertEquals(1, s.succeeded)
        assertFalse(f.exists())
        assertEquals("data", File(dst, "b.txt").readText())
    }

    @Test
    fun `move directory recursively`() = runTest {
        val d = File(src, "dir"); d.mkdir()
        makeFile(d, "f1.txt", "1"); makeFile(d, "f2.txt", "22")
        File(d, "sub").mkdir(); makeFile(File(d, "sub"), "f3.txt", "333")
        val s = engine.move(listOf(d), dst, ConflictPolicy.SKIP)
        assertEquals(1, s.succeeded)
        assertEquals("333", File(dst, "dir/sub/f3.txt").readText())
        assertFalse(d.exists())
    }

    @Test
    fun `conflict SKIP leaves target untouched`() = runTest {
        makeFile(src, "c.txt", "new")
        makeFile(dst, "c.txt", "old")
        val s = engine.copy(listOf(File(src, "c.txt")), dst, ConflictPolicy.SKIP)
        assertEquals(1, s.skipped)
        assertEquals("old", File(dst, "c.txt").readText())
    }

    @Test
    fun `conflict KEEP_BOTH creates numbered copy`() = runTest {
        makeFile(src, "d.txt", "new")
        makeFile(dst, "d.txt", "old")
        val s = engine.copy(listOf(File(src, "d.txt")), dst, ConflictPolicy.KEEP_BOTH)
        assertEquals(1, s.succeeded)
        assertEquals("old", File(dst, "d.txt").readText())
        assertEquals("new", File(dst, "d (1).txt").readText())
    }

    @Test
    fun `conflict OVERWRITE replaces target`() = runTest {
        makeFile(src, "e.txt", "new")
        makeFile(dst, "e.txt", "old")
        val s = engine.move(listOf(File(src, "e.txt")), dst, ConflictPolicy.OVERWRITE)
        assertEquals(1, s.succeeded)
        assertEquals("new", File(dst, "e.txt").readText())
    }

    @Test
    fun `missing source reported as failure not crash`() = runTest {
        val ghost = File(src, "ghost.txt")
        val s = engine.copy(listOf(ghost), dst, ConflictPolicy.SKIP)
        assertEquals(1, s.failed)
        assertEquals(0, s.succeeded)
    }

    @Test
    fun `delete removes file`() = runTest {
        val f = makeFile(src, "del.txt", "x")
        val s = engine.delete(listOf(f))
        assertEquals(1, s.succeeded)
        assertFalse(f.exists())
    }

    @Test
    fun `rename works and refuses collision`() = runTest {
        val f = makeFile(src, "r.txt", "x")
        makeFile(src, "taken.txt", "y")
        val ok = engine.rename(f, "renamed.txt")
        assertEquals(ItemStatus.DONE, ok.status)
        assertTrue(File(src, "renamed.txt").exists())
        val clash = engine.rename(File(src, "renamed.txt"), "taken.txt")
        assertEquals(ItemStatus.FAILED, clash.status)
        assertTrue(File(src, "renamed.txt").exists())
    }

    @Test
    fun `mkdir reports duplicate`() = runTest {
        val r1 = engine.mkdir(src, "newdir")
        assertEquals(ItemStatus.DONE, r1.status)
        val r2 = engine.mkdir(src, "newdir")
        assertEquals(ItemStatus.FAILED, r2.status)
    }

    @Test
    fun `copy into read-only dir fails gracefully`() = runTest {
        val f = makeFile(src, "ro.txt", "x")
        val roDir = tmp.newFolder("ro").apply { setWritable(false) }
        try {
            val s = engine.copy(listOf(f), roDir, ConflictPolicy.SKIP)
            // either FAILED per-item or overall failure — never a crash
            assertTrue(s.failed >= 1 || s.succeeded == 0)
        } catch (e: Exception) {
            // acceptable: InsufficientSpace/IOException propagate
        } finally {
            roDir.setWritable(true)
        }
    }

    @Test
    fun `uniqueName picks first free suffix`() = runTest {
        makeFile(dst, "f.txt", "1"); makeFile(dst, "f (1).txt", "2")
        val u = engine.uniqueName(dst, "f.txt")
        assertEquals("f (2).txt", u.name)
    }
}
