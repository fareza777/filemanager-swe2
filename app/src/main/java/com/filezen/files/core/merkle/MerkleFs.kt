package com.filezen.files.core.merkle

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Merkle folder fingerprinting — FileZen's port of the IPFS UnixFSv1 spec
 * (https://github.com/ipfs/specs, CC BY-SA 4.0).
 *
 * Produces the same CIDv1 a real `ipfs add --cid-version=1` (kubo) would:
 * - files ≤ 256 KiB are a single "raw leaf" block (CIDv1 + raw codec)
 * - larger files are chunked at 256 KiB; leaves are raw blocks and the
 *   root is a dag-pb PBNode with a UnixFS `File` Data message
 * - directories are dag-pb PBNodes with a UnixFS `Directory` Data message
 *   and one PBLink per child (sorted by name — the UnixFS requirement)
 *
 * Because every CID is a content hash, a folder fingerprint changes if and
 * only if something inside changed — so one short string can verify a whole
 * tree. [diff] reports which entries were added/removed/changed.
 */
object MerkleFs {

    private const val CHUNK = 256 * 1024
    private const val MAX_LINKS = 174 // unixfs fanout, like kubo's balanced layout

    private const val CODEC_RAW = 0x55L
    private const val CODEC_DAG_PB = 0x70L

    data class NodeResult(
        val cid: String,
        /** Full CIDv1 bytes (version+codec+multihash) — what PBLink.Hash embeds. */
        val cidBytes: ByteArray,
        /** Cumulative size — PBLink Tsize. For files: byte size; for dirs: own block + children. */
        val tsize: Long,
    )

    data class Fingerprint(
        val rootCid: String,
        /** relPath -> cid ("" for the root itself). */
        val entries: Map<String, String>,
        val sizes: Map<String, Long>,
        val fileCount: Int,
        val dirCount: Int,
        val totalBytes: Long,
    )

    data class Diff(
        val added: List<String>,
        val removed: List<String>,
        val changed: List<String>,
    ) {
        val identical get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
    }

    // ---------------- public API ----------------

    /** Content-address a file (chunked, raw leaves, dag-pb root when needed). */
    fun hashFile(f: File): NodeResult {
        val blocks = ArrayList<ByteArray>()
        FileInputStream(f).use { ins ->
            val buf = ByteArray(CHUNK)
            var n: Int
            while (ins.read(buf).also { n = it } > 0) {
                blocks += buf.copyOf(n)
            }
        }
        return fileNode(blocks, f.length())
    }

    /** Content-address a directory recursively. */
    fun hashDir(
        root: File,
        onFile: ((File) -> Unit)? = null,
    ): Fingerprint {
        val entries = LinkedHashMap<String, String>()
        val sizes = LinkedHashMap<String, Long>()
        var files = 0
        var dirs = 0
        var bytes = 0L

        fun walk(dir: File, rel: String): NodeResult {
            dirs++
            val kids = dir.listFiles()?.filter { !it.isHidden }
                ?.sortedBy { it.name } ?: emptyList()
            val links = ArrayList<Triple<ByteArray, String, Long>>() // cidBytes, name, tsize
            var totalBytesHere = 0L
            for (k in kids) {
                val childRel = if (rel.isEmpty()) k.name else "$rel/${k.name}"
                val res = if (k.isDirectory) walk(k, childRel)
                    else {
                        onFile?.invoke(k)
                        val r = hashFile(k)
                        files++; bytes += k.length()
                        totalBytesHere += k.length()
                        r
                    }
                entries[childRel] = res.cid
                sizes[childRel] = if (k.isDirectory) -1L else k.length()
                links += Triple(res.cidBytes, k.name, res.tsize)
            }
            if (rel.isNotEmpty()) sizes[rel] = totalBytesHere
            return dirNode(links)
        }

        val root = walk(root, "")
        return Fingerprint(root.cid, entries, sizes, files, dirs - 1, bytes)
    }

    /** Compare two fingerprints' per-entry CIDs. */
    fun diff(a: Fingerprint, b: Fingerprint): Diff {
        val added = ArrayList<String>()
        val changed = ArrayList<String>()
        for ((p, cid) in b.entries) {
            val old = a.entries[p]
            when {
                old == null -> added += p
                old != cid -> changed += p
            }
        }
        val removed = a.entries.keys.filter { it !in b.entries }
        return Diff(added.sorted(), removed.sorted(), changed.sorted())
    }

    // ---------------- dag-pb / unixfs encoding ----------------

    private fun fileNode(blocks: List<ByteArray>, totalSize: Long): NodeResult {
        if (blocks.size <= 1) return rawLeaf(blocks.firstOrNull() ?: ByteArray(0))
        // Balanced unixfs tree: group leaves into dag-pb intermediates.
        var level = blocks.map { rawLeaf(it) }
        while (level.size > MAX_LINKS) {
            level = level.chunked(MAX_LINKS).map { fileParent(it) }
        }
        return fileParent(level, filesize = totalSize)
    }

    /** dag-pb file node linking leaf/intermediate nodes. */
    private fun fileParent(
        children: List<NodeResult>,
        filesize: Long = children.sumOf { it.tsize },
    ): NodeResult {
        // blocksizes = cumulative size of each child block/link
        val data = pbData(type = 2 /* File */, filesize = filesize,
            blockSizes = children.map { it.tsize })
        val links = children.map { link(it.cidBytes, "", it.tsize) }
        val node = pbNode(data, links)
        val cb = cidBytesV1(CODEC_DAG_PB, node)
        return NodeResult(cidString(cb), cb, filesize)
    }

    /** dag-pb directory node. */
    private fun dirNode(children: List<Triple<ByteArray, String, Long>>): NodeResult {
        val data = pbData(type = 1 /* Directory */)
        val links = children.map { (cb, name, tsize) -> link(cb, name, tsize) }
        val node = pbNode(data, links)
        val tsize = children.sumOf { it.third } + node.size.toLong()
        val cb = cidBytesV1(CODEC_DAG_PB, node)
        return NodeResult(cidString(cb), cb, tsize)
    }

    private fun rawLeaf(bytes: ByteArray): NodeResult {
        val cb = cidBytesV1(CODEC_RAW, bytes)
        return NodeResult(cidString(cb), cb, bytes.size.toLong())
    }

    // ---------------- CIDv1 / multihash / base32 ----------------

    private fun multihash(data: ByteArray): ByteArray {
        val d = MessageDigest.getInstance("SHA-256").digest(data)
        return byteArrayOf(0x12, 0x20) + d // sha2-256, 32 bytes
    }

    private fun cidBytesV1(codec: Long, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            writeVarint(0x01) // cidv1
            writeVarint(codec)
            write(multihash(payload))
        }.toByteArray()

    private fun cidString(cidBytes: ByteArray) = "b" + base32Lower(cidBytes)

    private fun base32Lower(data: ByteArray): String {
        val alpha = "abcdefghijklmnopqrstuvwxyz234567"
        val sb = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0; var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(alpha[(buffer ushr bits) and 0x1F])
            }
        }
        if (bits > 0) sb.append(alpha[(buffer shl (5 - bits)) and 0x1F])
        return sb.toString()
    }

    // ---------------- minimal protobuf writer ----------------

    private fun ByteArrayOutputStream.writeVarint(v0: Long) {
        var v = v0
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) { write(b); return }
            write(b or 0x80)
        }
    }

    private fun ByteArrayOutputStream.writeTag(field: Int, wire: Int) =
        writeVarint(((field shl 3) or wire).toLong())

    private fun ByteArrayOutputStream.writeBytes(field: Int, bytes: ByteArray) {
        writeTag(field, 2); writeVarint(bytes.size.toLong()); write(bytes)
    }

    private fun ByteArrayOutputStream.writeVarintField(field: Int, v: Long) {
        writeTag(field, 0); writeVarint(v)
    }

    private fun pbData(type: Int, filesize: Long? = null, blockSizes: List<Long>? = null): ByteArray =
        ByteArrayOutputStream().apply {
            writeVarintField(1, type.toLong())           // Type
            filesize?.let { writeVarintField(3, it) }    // filesize
            blockSizes?.forEach { writeVarintField(4, it) } // blocksizes
        }.toByteArray()

    private fun link(cidBytes: ByteArray, name: String, tsize: Long): ByteArray =
        ByteArrayOutputStream().apply {
            writeBytes(1, cidBytes)                      // Hash — full CID bytes
            writeBytes(2, name.toByteArray(Charsets.UTF_8)) // Name (always present, may be empty)
            writeVarintField(3, tsize)                   // Tsize
        }.toByteArray()

    /** PBNode: Links (field 2) first, then Data (field 1) — dag-pb canonical order. */
    private fun pbNode(data: ByteArray, links: List<ByteArray>): ByteArray =
        ByteArrayOutputStream().apply {
            links.forEach { writeBytes(2, it) }
            writeBytes(1, data)
        }.toByteArray()
}
