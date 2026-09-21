package com.filezen.files

import com.filezen.files.core.sync.FolderSyncEngine
import com.filezen.files.core.sync.LocalSyncStore
import com.filezen.files.data.db.SyncPair
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FolderSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pair(dir: String = "TO_REMOTE", del: Boolean = true, conflict: String = "NEWER_WINS") =
        SyncPair(id = 1, name = "t", localFolder = "", remoteFolder = "",
            direction = dir, conflictRule = conflict, deleteOrphans = del)

    private fun engine(a: File, b: File, p: SyncPair) =
        FolderSyncEngine(LocalSyncStore(a), LocalSyncStore(b), p,
            File.createTempFile("sync", "d").let { it.delete(); it.mkdirs(); it })

    private fun w(d: File, rel: String, content: String, mtime: Long = 0L): File {
        val f = File(d, rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
        if (mtime > 0) f.setLastModified(mtime)
        return f
    }

    @Test
    fun `one-way copies new files and mirrors dirs`() {
        val a = tmp.newFolder("a"); val b = tmp.newFolder("b")
        w(a, "x.txt", "hello"); w(a, "sub/y.txt", "nested")
        val r = engine(a, b, pair()).run(emptyList(), null)
        assertEquals(2, r.filesCopied)
        assertEquals("hello", File(b, "x.txt").readText())
        assertEquals("nested", File(b, "sub/y.txt").readText())
    }

    @Test
    fun `one-way deletes orphans when enabled`() {
        val a = tmp.newFolder("a2"); val b = tmp.newFolder("b2")
        w(a, "keep.txt", "k"); w(b, "orphan.txt", "o")
        engine(a, b, pair(del = true)).run(emptyList(), null)
        assertFalse(File(b, "orphan.txt").exists())
        assertTrue(File(b, "keep.txt").exists())
    }

    @Test
    fun `one-way keeps orphans when disabled`() {
        val a = tmp.newFolder("a3"); val b = tmp.newFolder("b3")
        w(a, "keep.txt", "k"); w(b, "orphan.txt", "o")
        engine(a, b, pair(del = false)).run(emptyList(), null)
        assertTrue(File(b, "orphan.txt").exists())
    }

    @Test
    fun `from-remote pulls files to local`() {
        val a = tmp.newFolder("a4"); val b = tmp.newFolder("b4")
        w(b, "r.txt", "remote")
        engine(a, b, pair(dir = "FROM_REMOTE")).run(emptyList(), null)
        assertEquals("remote", File(a, "r.txt").readText())
    }

    @Test
    fun `two-way merges both sides`() {
        val a = tmp.newFolder("a5"); val b = tmp.newFolder("b5")
        w(a, "onlyA.txt", "a"); w(b, "onlyB.txt", "b")
        val r = engine(a, b, pair(dir = "TWO_WAY")).run(emptyList(), null)
        assertEquals(2, r.filesCopied)
        assertTrue(File(a, "onlyB.txt").exists())
        assertTrue(File(b, "onlyA.txt").exists())
        assertEquals(2, r.newState.size)
    }

    @Test
    fun `two-way propagates deletion via state`() {
        val a = tmp.newFolder("a6"); val b = tmp.newFolder("b6")
        w(a, "gone.txt", "x"); w(b, "gone.txt", "x")
        val first = engine(a, b, pair(dir = "TWO_WAY")).run(emptyList(), null)
        // Now delete on B, sync again — A's copy must be deleted too.
        File(b, "gone.txt").delete()
        val second = engine(a, b, pair(dir = "TWO_WAY")).run(first.newState, null)
        assertEquals(1, second.filesDeleted)
        assertFalse(File(a, "gone.txt").exists())
    }

    @Test
    fun `two-way newer wins on conflict`() {
        val a = tmp.newFolder("a7"); val b = tmp.newFolder("b7")
        w(a, "c.txt", "old")
        val r0 = engine(a, b, pair(dir = "TWO_WAY")).run(emptyList(), null)
        // Touch both sides after the sync — B is newer.
        w(a, "c.txt", "local-edit", 1_000_000)
        w(b, "c.txt", "remote-edit", 2_000_000)
        val r = engine(a, b, pair(dir = "TWO_WAY")).run(r0.newState, null)
        assertEquals(1, r.conflicts)
        assertEquals("remote-edit", File(a, "c.txt").readText())
    }

    @Test
    fun `hamming distance counts differing bits`() {
        assertEquals(0, com.filezen.files.core.cleaner.PhotoLens.hammingDistance(0L, 0L))
        assertEquals(1, com.filezen.files.core.cleaner.PhotoLens.hammingDistance(0L, 1L))
        assertEquals(8, com.filezen.files.core.cleaner.PhotoLens.hammingDistance(0L, 0xFFL))
    }
}
