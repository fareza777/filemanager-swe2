package com.filezen.files.core.semsearch

import android.content.Context
import com.filezen.files.data.db.DocChunk
import com.filezen.files.data.db.DocIndexDao
import com.filezen.files.data.db.DocManifest
import com.filezen.files.data.db.ZenDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Indexes the *content* of documents on disk for semantic "inside files"
 * search. Incremental: a per-file sha256 manifest means only new/changed
 * files are re-read. Embeddings are computed with [Embedding] — fully
 * offline, no model download.
 */
class ContentIndex(private val app: Context, private val db: ZenDatabase) {

    data class Progress(val total: Int, val done: Int, val current: String = "")
    data class Hit(val path: String, val title: String, val score: Float, val snippet: String)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress
    val indexedFiles = db.docIndex().indexedFileCount()

    @Volatile private var running = false

    companion object {
        /** Extensions whose content we can meaningfully index. */
        val INDEXABLE = setOf(
            "txt", "md", "pdf", "docx", "csv", "json", "xml", "html", "htm",
            "log", "ini", "cfg", "properties", "yml", "yaml", "toml", "sql",
            "kt", "java", "py", "js", "ts", "jsx", "tsx", "c", "cpp", "h",
            "hpp", "cs", "go", "rs", "rb", "php", "sh", "bash", "bat", "ps1",
            "swift", "dart", "lua", "r", "gradle", "kts", "srt", "vtt",
        )
        private const val MAX_FILE_BYTES = 3L * 1024 * 1024
        private const val MAX_TEXT_CHARS = 300_000
        private const val CHUNK = 2000
        private const val MIN_SCORE = 0.18f
    }

    fun indexable(f: File): Boolean =
        f.isFile && f.length() in 1..MAX_FILE_BYTES &&
            f.extension.lowercase() in INDEXABLE

    /** Incrementally sync the content index with the current file_index. */
    suspend fun sync(filePaths: List<File>? = null) = withContext(Dispatchers.IO) {
        if (running) return@withContext
        running = true
        try {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(app)
            val dao = db.docIndex()
            val manifest = dao.manifest().associateBy { it.path }.toMutableMap()

            val candidates: List<File> = filePaths
                ?: db.fileIndex().allRows()
                    .map { File(it.path) }
                    .filter { it.extension.lowercase() in INDEXABLE && it.length() in 1..MAX_FILE_BYTES }

            // Drop manifest entries for files that no longer exist or aren't indexable.
            val live = candidates.mapTo(HashSet()) { it.absolutePath }
            for (gone in manifest.keys - live) {
                dao.removeManifest(gone); dao.removeChunks(gone); manifest.remove(gone)
            }

            // Files needing (re)index: new or size/mtime changed.
            val todo = candidates.filter { f ->
                val m = manifest[f.absolutePath]
                m == null || m.size != f.length() || m.lastModified != f.lastModified()
            }
            if (todo.isEmpty()) { _progress.value = null; return@withContext }

            var done = 0
            _progress.value = Progress(todo.size, 0)
            for (f in todo) {
                currentCoroutineContext().ensureActive()
                _progress.value = Progress(todo.size, done, f.name)
                try {
                    indexFile(f, dao)
                } catch (_: Throwable) {
                    // Unreadable file — drop any stale chunks, keep manifest out so it may retry later.
                    dao.removeChunks(f.absolutePath)
                }
                done++
            }
            _progress.value = null
        } finally {
            running = false
            _progress.value = null
        }
    }

    suspend fun rebuildAll() = withContext(Dispatchers.IO) {
        db.docIndex().clearChunks(); db.docIndex().clearManifest()
        running = false
        sync()
    }

    private suspend fun indexFile(f: File, dao: DocIndexDao) {
        val text = extractText(f)?.take(MAX_TEXT_CHARS) ?: return
        if (text.isBlank()) return
        val sha = sha256(f)
        dao.removeChunks(f.absolutePath)
        val chunks = ArrayList<DocChunk>()
        var i = 0; var ix = 0
        while (i < text.length && ix < 200) {
            val part = text.substring(i, minOf(i + CHUNK, text.length))
            val title = if (ix == 0) f.nameWithoutExtension else "${f.nameWithoutExtension} #${ix + 1}"
            chunks += DocChunk(
                path = f.absolutePath, chunkIx = ix, title = title,
                snippet = part.take(280).replace(Regex("\\s+"), " "),
                embedding = Embedding.pack(Embedding.embed("$title $part")),
            )
            i += CHUNK; ix++
            if (ix % 10 == 0) currentCoroutineContext().ensureActive()
        }
        dao.insertChunks(chunks)
        dao.putManifest(DocManifest(f.absolutePath, f.length(), f.lastModified(), sha))
    }

    suspend fun search(query: String, k: Int = 40): List<Hit> = withContext(Dispatchers.Default) {
        val qv = Embedding.embed(query)
        val best = HashMap<String, Hit>()
        for (c in db.docIndex().allChunks()) {
            val s = Embedding.cosine(qv, Embedding.unpack(c.embedding))
            if (s < MIN_SCORE) continue
            val prev = best[c.path]
            if (prev == null || s > prev.score)
                best[c.path] = Hit(c.path, c.title.substringBeforeLast(" #"), s, c.snippet)
        }
        best.values.sortedByDescending { it.score }.take(k)
    }

    // ---------- text extraction ----------

    private fun extractText(f: File): String? = try {
        when (f.extension.lowercase()) {
            "pdf" -> readPdf(f)
            "docx" -> readDocx(f)
            else -> f.inputStream().buffered().reader(Charsets.UTF_8)
                .use { it.readText().take(MAX_TEXT_CHARS + 1) }
        }
    } catch (_: Throwable) { null }

    private fun readPdf(f: File): String? = try {
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(f).use { doc ->
            com.tom_roush.pdfbox.text.PDFTextStripper().getText(doc)
        }
    } catch (_: Throwable) { null }

    private fun readDocx(f: File): String? = try {
        var xml: String? = null
        ZipInputStream(FileInputStream(f)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name == "word/document.xml") {
                    xml = z.readBytes().toString(Charsets.UTF_8); break
                }
                e = z.nextEntry
            }
        }
        xml?.let {
            it.replace(Regex("</w:p>"), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace(Regex("&amp;"), "&").replace(Regex("&lt;"), "<")
                .replace(Regex("&gt;"), ">").replace(Regex("&quot;"), "\"")
        }
    } catch (_: Throwable) { null }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(8192); var n: Int
            while (ins.read(buf).also { n = it } > 0) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
