package com.filezen.files.core.fileops

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ZipEngine(private val engine: FileEngine = FileEngine()) {

    /**
     * Compress sources into [zipFile] (created inside destDir).
     * [storeOnly] skips deflate for already-compressed media (jpg, mp4, zip…)
     * — much faster, near-zero size penalty.
     */
    suspend fun compress(
        sources: List<File>,
        zipFile: File,
        storeOnly: Boolean = false,
        onProgress: ProgressCb = {},
    ): ItemResult = withContext(Dispatchers.IO) {
        try {
            if (zipFile.exists() && !zipFile.delete()) throw IOException("Cannot overwrite ${zipFile.name}")
            ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zos ->
                if (storeOnly) zos.setLevel(0)
                var done = 0
                val total = sources.sumOf { countFiles(it) }
                for (src in sources) addToZip(zos, src, src.name, onProgress, done, total)
                    .also { done += countFiles(src) }
            }
            ItemResult(zipFile.path, zipFile.path, ItemStatus.DONE)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            zipFile.delete(); throw ce
        } catch (e: Exception) {
            zipFile.delete()
            ItemResult(zipFile.path, null, ItemStatus.FAILED, e.message)
        }
    }

    private suspend fun addToZip(
        zos: ZipOutputStream, f: File, name: String,
        onProgress: ProgressCb, done: Int, total: Int,
    ) {
        currentCoroutineContext().ensureActive()
        if (f.isDirectory) {
            zos.putNextEntry(ZipEntry("$name/"))
            zos.closeEntry()
            f.listFiles()?.forEach { addToZip(zos, it, "$name/${it.name}", onProgress, done, total) }
        } else {
            onProgress(OpProgress(done, total, f.name, 0, 0))
            zos.putNextEntry(ZipEntry(name))
            FileInputStream(f).use { it.copyTo(zos, 128 * 1024) }
            zos.closeEntry()
        }
    }

    /** Extract [zip] into [destDir] with Zip-Slip protection and conflict policy. */
    suspend fun extract(
        zip: File,
        destDir: File,
        policy: ConflictPolicy,
        onProgress: ProgressCb = {},
    ): OpSummary = withContext(Dispatchers.IO) {
        val results = mutableListOf<ItemResult>()
        try {
            ZipFile(zip).use { zf ->
                val entries = zf.entries().toList()
                val canonicalDest = destDir.canonicalPath + File.separator
                var i = 0
                for (e in entries) {
                    currentCoroutineContext().ensureActive()
                    i++
                    onProgress(OpProgress(i, entries.size, e.name, 0, 0))
                    try {
                        var out = File(destDir, e.name)
                        if (!out.canonicalPath.startsWith(canonicalDest)) {
                            results += ItemResult(e.name, null, ItemStatus.FAILED, "Blocked unsafe path")
                            continue
                        }
                        if (e.isDirectory) { out.mkdirs(); continue }
                        if (out.exists()) {
                            out = when (policy) {
                                ConflictPolicy.SKIP, ConflictPolicy.ASK -> {
                                    results += ItemResult(e.name, out.path, ItemStatus.SKIPPED); continue
                                }
                                ConflictPolicy.OVERWRITE -> out
                                ConflictPolicy.KEEP_BOTH -> engine.uniqueName(destDir, e.name)
                            }
                        }
                        out.parentFile?.mkdirs()
                        zf.getInputStream(e).use { input ->
                            FileOutputStream(out).use { input.copyTo(it, 128 * 1024) }
                        }
                        results += ItemResult(e.name, out.path, ItemStatus.DONE)
                    } catch (se: SecurityException) {
                        results += ItemResult(e.name, null, ItemStatus.FAILED, "Unsafe entry")
                    } catch (ex: Exception) {
                        results += ItemResult(e.name, null, ItemStatus.FAILED, ex.message)
                    }
                }
            }
            OpSummary(OpKind.UNZIP, results)
        } catch (e: IOException) {
            OpSummary(OpKind.UNZIP, results + ItemResult(zip.path, null, ItemStatus.FAILED, "Invalid ZIP"))
        }
    }

    private fun countFiles(f: File): Int =
        if (f.isDirectory) (f.listFiles()?.sumOf { countFiles(it) } ?: 0) else 1
}
