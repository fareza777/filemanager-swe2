package com.filezen.files.core.archive

import com.filezen.files.core.model.FileEntry
import com.filezen.files.core.model.FileType
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/**
 * Archive-as-folder (Twig's mount-in-place idea): list a .zip/.tar/.tar.gz as
 * if it were a directory — navigate into virtual subdirs, extract a single
 * entry or the whole thing. Read-only.
 */
object ArchiveFs {

    data class Entry(val name: String, val path: String, val isDir: Boolean,
                     val size: Long, val mtime: Long)

    fun isBrowsable(name: String): Boolean =
        name.substringAfterLast('.').lowercase() in
            setOf("zip", "jar", "tar", "tgz", "apk") ||
            name.lowercase().endsWith(".tar.gz")

    /** List direct children of [prefix] inside the archive (prefix "" = root). */
    fun list(archive: File, prefix: String): List<Entry> {
        val all = entries(archive)
        val p = prefix.trimEnd('/').let { if (it.isEmpty()) "" else "$it/" }
        val seen = LinkedHashMap<String, Entry>()
        for (e in all) {
            if (!e.path.startsWith(p) || e.path == p) continue
            val rest = e.path.removePrefix(p)
            val slash = rest.indexOf('/')
            if (slash < 0) {
                seen[rest] = e.copy(name = rest)
            } else {
                val dirName = rest.substring(0, slash)
                val key = "$dirName/"
                if (seen.none { it.key.removeSuffix("/") == dirName })
                    seen[key] = Entry(dirName, "$p$dirName", true, 0, e.mtime)
            }
        }
        return seen.values.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    /** All entries of the archive, paths relative to the archive root. */
    fun entries(archive: File): List<Entry> = when {
        archive.name.endsWith(".zip", true) || archive.name.endsWith(".jar", true)
            || archive.name.endsWith(".apk", true) -> zipEntries(archive)
        archive.name.endsWith(".tar", true) -> tarEntries { FileInputStream(archive) }
        archive.name.endsWith(".tgz", true) || archive.name.endsWith(".tar.gz", true) ->
            tarEntries { GZIPInputStream(FileInputStream(archive)) }
        else -> emptyList()
    }

    private fun zipEntries(f: File): List<Entry> = runCatching {
        ZipFile(f).use { zf ->
            zf.entries().toList().map { ze ->
                Entry(ze.name.substringAfterLast('/').ifEmpty { ze.name.trimEnd('/') },
                    ze.name.removeSuffix("/"), ze.isDirectory, ze.size, ze.time)
            }
        }
    }.getOrDefault(emptyList())

    /** Minimal TAR reader: 512-byte headers, octal size field. */
    private fun tarEntries(open: () -> InputStream): List<Entry> {
        val out = ArrayList<Entry>()
        runCatching {
            open().buffered().use { ins ->
                val hdr = ByteArray(512)
                while (true) {
                    var read = 0
                    while (read < 512) {
                        val r = ins.read(hdr, read, 512 - read)
                        if (r < 0) return@use
                        read += r
                    }
                    if (hdr.all { it == 0.toByte() }) break
                    val name = String(hdr, 0, 100).trim(0.toChar())
                    if (name.isEmpty()) break
                    val sizeStr = String(hdr, 124, 12).trim(0.toChar(), ' ')
                    val size = sizeStr.toLongOrNull(8) ?: 0
                    val mtime = (String(hdr, 136, 12).trim(0.toChar(), ' ')
                        .toLongOrNull(8) ?: 0) * 1000
                    val type = hdr[156].toInt().toChar()
                    val full = String(hdr, 345, 155).trim(0.toChar())
                        .let { if (it.isNotEmpty()) "$it/$name" else name }
                    out += Entry(name.trimEnd('/'), full.trimEnd('/'),
                        type == '5' || full.endsWith("/"), size, mtime)
                    var skip = (size + 511) / 512 * 512
                    while (skip > 0) skip -= ins.skip(skip).coerceAtLeast(0)
                }
            }
        }
        return out
    }

    /** Stream a single entry's content. Returns null for dirs/unsupported. */
    fun open(archive: File, path: String): InputStream? {
        if (archive.name.endsWith(".zip", true) || archive.name.endsWith(".jar", true)
            || archive.name.endsWith(".apk", true)) {
            val zf = ZipFile(archive)
            val ze = zf.getEntry(path) ?: run { zf.close(); return null }
            // Keep ZipFile alive until stream closes.
            val s = zf.getInputStream(ze)
            return object : java.io.FilterInputStream(s) {
                override fun close() { super.close(); zf.close() }
            }
        }
        // TAR: re-scan to the target then wrap a bounded stream.
        if (archive.name.endsWith(".tar", true) || archive.name.endsWith(".tgz", true)
            || archive.name.endsWith(".tar.gz", true)) {
            val ins = (if (archive.name.endsWith(".tar", true)) FileInputStream(archive)
                else GZIPInputStream(FileInputStream(archive))).buffered()
            val hdr = ByteArray(512)
            try {
                while (true) {
                    var read = 0
                    while (read < 512) {
                        val r = ins.read(hdr, read, 512 - read)
                        if (r < 0) { ins.close(); return null }
                        read += r
                    }
                    if (hdr.all { it == 0.toByte() }) { ins.close(); return null }
                    val name = String(hdr, 0, 100).trim(0.toChar())
                    val size = (String(hdr, 124, 12).trim(0.toChar(), ' ')
                        .toLongOrNull(8) ?: 0)
                    val full = String(hdr, 345, 155).trim(0.toChar())
                        .let { if (it.isNotEmpty()) "$it/$name" else name }
                    if (full.trimEnd('/') == path) {
                        // Stream is positioned at the data — wrap to bound reads.
                        return object : java.io.FilterInputStream(ins) {
                            var left = size
                            override fun read(): Int =
                                if (left <= 0) -1 else super.read().also { if (it >= 0) left-- }
                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                if (left <= 0) return -1
                                val n = super.read(b, off, minOf(len.toLong(), left).toInt())
                                if (n > 0) left -= n
                                return n
                            }
                        }
                    }
                    var skip = (size + 511) / 512 * 512
                    while (skip > 0) skip -= ins.skip(skip).coerceAtLeast(0)
                }
            } catch (e: Exception) { ins.close() }
            return null
        }
        return null
    }

    fun toFileEntry(e: Entry): FileEntry = FileEntry(
        path = e.path, name = e.name, isDirectory = e.isDir,
        size = e.size, lastModified = e.mtime,
        type = if (e.isDir) FileType.FOLDER else FileEntry.typeOf(e.name))
}
