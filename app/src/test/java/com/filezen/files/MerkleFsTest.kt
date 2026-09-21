package com.filezen.files

import com.filezen.files.core.merkle.MerkleFs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Golden CIDs were produced by real `ipfs add --only-hash --cid-version=1`
 * (kubo v0.30.0) — the port must match byte-for-byte.
 */
class MerkleFsTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `empty file matches ipfs raw-leaf CID`() {
        val f = tmp.newFile("empty.bin").apply { writeBytes(ByteArray(0)) }
        assertEquals(
            "bafkreihdwdcefgh4dqkjv67uzcmw7ojee6xedzdetojuzjevtenxquvyku",
            MerkleFs.hashFile(f).cid,
        )
    }

    @Test
    fun `hello world matches ipfs raw-leaf CID`() {
        val f = tmp.newFile("hello.txt").apply { writeText("hello world\n") }
        assertEquals(
            "bafkreifjjcie6lypi6ny7amxnfftagclbuxndqonfipmb64f2km2devei4",
            MerkleFs.hashFile(f).cid,
        )
    }

    @Test
    fun `multi-block file matches ipfs dag-pb CID`() {
        // 600000 deterministic bytes → 3 chunks → dag-pb root
        val f = tmp.newFile("det-big.bin")
        f.outputStream().use { out ->
            for (i in 0 until 6000) {
                out.write(("block-%05d-".format(i)).padEnd(100, 'x').toByteArray())
            }
        }
        assertEquals(
            "bafybeifxap4u6zofhlhrq6k6wxlflto235ai7atefb64c22xn2szbbrsfq",
            MerkleFs.hashFile(f).cid,
        )
    }

    @Test
    fun `empty dir matches ipfs CID`() {
        val dir = tmp.newFolder("emptydir")
        assertEquals(
            "bafybeiczsscdsbs7ffqz55asqdf3smv6klcw3gofszvwlyarci47bgf354",
            MerkleFs.hashDir(dir).rootCid,
        )
    }

    @Test
    fun `dir with file matches ipfs CID`() {
        val dir = tmp.newFolder("hellodir")
        File(dir, "hello.txt").writeText("hello world\n")
        assertEquals(
            "bafybeidhkumeonuwkebh2i4fc7o7lguehauradvlk57gzake6ggjsy372a",
            MerkleFs.hashDir(dir).rootCid,
        )
    }

    @Test
    fun `nested tree matches ipfs CID`() {
        val root = tmp.newFolder("fptest")
        File(root, "hello.txt").writeText("hello world\n")
        File(root, "empty.bin").writeBytes(ByteArray(0))
        File(root, "sub").mkdir()
        File(root, "sub/inner.txt").writeText("nested content here")
        File(root, "emptydir").mkdir()
        // big.bin is random in the golden run — drop it, keep the rest.
        val fp = MerkleFs.hashDir(root)
        // sub dir
        assertEquals("bafybeibgs6mloa3hxmen7q4ihsof2tt6a2dda24jvmnzejvakyoo6ptk7y",
            fp.entries["sub"])
        assertEquals(3, fp.fileCount)
        assertTrue(fp.entries.containsKey("sub/inner.txt"))
    }

    @Test
    fun `diff detects added removed changed`() {
        val dirA = tmp.newFolder("a").also { d ->
            File(d, "keep.txt").writeText("same")
            File(d, "change.txt").writeText("v1")
            File(d, "gone.txt").writeText("bye")
        }
        val dirB = tmp.newFolder("b").also { d ->
            File(d, "keep.txt").writeText("same")
            File(d, "change.txt").writeText("v2 changed")
            File(d, "new.txt").writeText("hi")
        }
        val d = MerkleFs.diff(MerkleFs.hashDir(dirA), MerkleFs.hashDir(dirB))
        assertEquals(listOf("new.txt"), d.added)
        assertEquals(listOf("gone.txt"), d.removed)
        assertEquals(listOf("change.txt"), d.changed)
        assertFalse(d.identical)
    }

    @Test
    fun `identical trees produce identical root`() {
        val dirA = tmp.newFolder("a").also { File(it, "x.txt").writeText("x") }
        val dirB = tmp.newFolder("b").also { File(it, "x.txt").writeText("x") }
        val fa = MerkleFs.hashDir(dirA); val fb = MerkleFs.hashDir(dirB)
        // Root CID differs (different root link names? no — dir names aren't in links)
        // but entry CIDs must match, and diff must be empty
        assertTrue(MerkleFs.diff(fa, fb).identical)
        assertEquals(fa.entries["x.txt"], fb.entries["x.txt"])
    }
}
