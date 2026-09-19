package com.filezen.files.core.fileops

import com.filezen.files.core.model.FileEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RenamePattern(
    /** Template with tokens: {name} {ext} {n} {date:yyyyMMdd} {parent} */
    val template: String = "{name}{n}",
    val startAt: Int = 1,
    val pad: Int = 1,
    val find: String = "",
    val replace: String = "",
    val keepExtension: Boolean = true,
)

data class RenamePreviewItem(
    val source: FileEntry,
    val newName: String,
    val conflict: Boolean,   // collides with an existing or another planned name
    val unchanged: Boolean,
)

object RenameEngine {

    fun preview(files: List<FileEntry>, pattern: RenamePattern): List<RenamePreviewItem> {
        val planned = LinkedHashMap<String, Int>()
        val dirExisting = mutableSetOf<String>()
        files.firstOrNull()?.let { e ->
            File(e.path).parentFile?.listFiles()?.forEach { dirExisting += it.name.lowercase() }
        }
        val dateFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        var n = pattern.startAt

        return files.map { e ->
            val ext = e.extension
            val stem = e.name.substringBeforeLast('.', e.name)
            var newName = pattern.template
                .replace("{name}", stem)
                .replace("{ext}", ext)
                .replace("{parent}", File(e.path).parentFile?.name ?: "")
                .replace("{date:yyyyMMdd}", dateFmt.format(Date(e.lastModified)))
                .replace("{date}", dateFmt.format(Date(e.lastModified)))
            newName = newName.replace("{n}", n.toString().padStart(pattern.pad, '0'))
            n++
            if (pattern.find.isNotEmpty()) newName = newName.replace(pattern.find, pattern.replace)
            // sanitize illegal chars
            newName = newName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            if (pattern.keepExtension && !e.isDirectory && ext.isNotEmpty() &&
                !newName.lowercase().endsWith(".$ext")
            ) newName = "$newName.$ext"

            val key = newName.lowercase()
            val count = planned.getOrDefault(key, 0)
            planned[key] = count + 1
            val conflict = count > 0 || (dirExisting.contains(key) && key != e.name.lowercase())
            RenamePreviewItem(e, newName, conflict, newName == e.name)
        }
    }

    /** Renames sequentially; on conflict appends " (i)". Skips unchanged names. */
    suspend fun apply(items: List<RenamePreviewItem>, engine: FileEngine): OpSummary {
        val results = mutableListOf<ItemResult>()
        for (item in items) {
            if (item.unchanged) {
                results += ItemResult(item.source.path, null, ItemStatus.SKIPPED)
                continue
            }
            val src = File(item.source.path)
            var target = File(src.parentFile!!, item.newName)
            var i = 1
            while (target.exists() && target.path != src.path) {
                val dot = item.newName.lastIndexOf('.')
                val base = if (dot > 0) item.newName.substring(0, dot) else item.newName
                val ext = if (dot > 0) item.newName.substring(dot) else ""
                target = File(src.parentFile!!, "$base ($i)$ext")
                i++
            }
            val r = engine.rename(src, target.name)
            results += r
        }
        return OpSummary(OpKind.RENAME, results)
    }
}
