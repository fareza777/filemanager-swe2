package com.filezen.files

import com.filezen.files.core.fileops.SortRuleEngine
import com.filezen.files.core.model.FileEntry
import com.filezen.files.data.db.SortRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SortRulesTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `extension rule matches`() {
        val r = SortRule(matchType = "EXTENSION", pattern = "pdf", targetPath = "/x")
        val e = FileEntry.from(File.createTempFile("doc", ".pdf"))
        assertTrue(SortRuleEngine.matches(r, e))
        val img = FileEntry.from(File.createTempFile("pic", ".png"))
        assertFalse(SortRuleEngine.matches(r, img))
    }

    @Test
    fun `contains rule matches name`() {
        val r = SortRule(matchType = "CONTAINS", pattern = "invoice", targetPath = "/x")
        val e = FileEntry.from(File.createTempFile("invoice-2024", ".pdf"))
        assertTrue(SortRuleEngine.matches(r, e))
    }

    @Test
    fun `preview finds matching files recursively`() = runTest {
        val root = tmp.newFolder("messy")
        File(root, "a.pdf").writeText("1")
        File(root, "b.txt").writeText("2")
        val sub = File(root, "sub"); sub.mkdir()
        File(sub, "c.pdf").writeText("3")
        val target = File(root, "pdfs").absolutePath
        val rule = SortRule(matchType = "EXTENSION", pattern = "pdf", targetPath = target)
        val found = SortRuleEngine.preview(root, listOf(rule))
        assertEquals(2, found.size)
        assertTrue(found.all { it.target == target })
    }

    @Test
    fun `file already in target dir is not re-proposed`() = runTest {
        val root = tmp.newFolder("messy2")
        val pdfs = File(root, "pdfs"); pdfs.mkdir()
        File(pdfs, "already.pdf").writeText("1")
        val rule = SortRule(matchType = "EXTENSION", pattern = "pdf", targetPath = pdfs.absolutePath)
        val found = SortRuleEngine.preview(root, listOf(rule))
        assertTrue(found.isEmpty())
    }
}
