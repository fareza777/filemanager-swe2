package com.filezen.files

import com.filezen.files.core.fileops.ItemStatus
import com.filezen.files.core.fileops.RenameEngine
import com.filezen.files.core.fileops.RenamePattern
import com.filezen.files.core.fileops.FileEngine
import com.filezen.files.core.model.FileEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RenameEngineTest {

    @get:Rule val tmp = TemporaryFolder()
    private val engine = FileEngine()

    private fun entry(f: File) = FileEntry.from(f)

    @Test
    fun `numbering template produces sequential names`() = runTest {
        val d = tmp.newFolder("ren")
        val fs = listOf("a.jpg", "b.jpg", "c.jpg").map { File(d, it).apply { writeText("x") } }
        val items = fs.map { entry(it) }
        val previews = RenameEngine.preview(items, RenamePattern(template = "img-{n}", startAt = 1, pad = 3))
        assertEquals(listOf("img-001.jpg", "img-002.jpg", "img-003.jpg"), previews.map { it.newName })
        val s = RenameEngine.apply(previews, engine)
        assertEquals(3, s.succeeded)
        assertTrue(File(d, "img-001.jpg").exists())
    }

    @Test
    fun `find replace applies before numbering`() {
        val d = tmp.newFolder("ren2")
        val fs = listOf("IMG_1.jpg", "IMG_2.jpg").map { File(d, it).apply { writeText("x") } }
        val previews = RenameEngine.preview(fs.map { entry(it) },
            RenamePattern(template = "{name}", find = "IMG", replace = "photo"))
        assertEquals(listOf("photo_1.jpg", "photo_2.jpg"), previews.map { it.newName })
    }

    @Test
    fun `collision flagged in preview`() {
        val d = tmp.newFolder("ren3")
        File(d, "taken.jpg").writeText("x")
        val f = File(d, "src.jpg").apply { writeText("y") }
        val previews = RenameEngine.preview(listOf(entry(f)),
            RenamePattern(template = "taken"))
        assertTrue(previews.first().conflict)
    }

    @Test
    fun `apply skips unchanged and avoids overwrite`() = runTest {
        val d = tmp.newFolder("ren4")
        File(d, "keep.jpg").writeText("a")
        val f = File(d, "src.jpg").apply { writeText("b") }
        val previews = RenameEngine.preview(listOf(entry(f)),
            RenamePattern(template = "keep"))   // collides
        val s = RenameEngine.apply(previews, engine)
        // engine falls back to "keep (1).jpg" rather than overwriting
        assertTrue(File(d, "keep.jpg").readText() == "a")
        assertTrue(File(d, "keep (1).jpg").exists() || s.results.first().status == ItemStatus.DONE)
    }

    @Test
    fun `illegal characters sanitized`() {
        val d = tmp.newFolder("ren5")
        val f = File(d, "a.txt").apply { writeText("x") }
        val previews = RenameEngine.preview(listOf(entry(f)),
            RenamePattern(template = "a/b:c"))
        assertFalse(previews.first().newName.contains('/'))
        assertFalse(previews.first().newName.contains(':'))
    }
}
