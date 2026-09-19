package com.filezen.files.core.model

import android.webkit.MimeTypeMap
import java.io.File
import java.util.Locale

enum class FileType { FOLDER, IMAGE, VIDEO, AUDIO, DOCUMENT, TEXT, PDF, APK, ARCHIVE, OTHER }

data class FileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val type: FileType,
    val hidden: Boolean = false,
) {
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    companion object {
        fun from(f: File): FileEntry {
            val name = f.name
            return FileEntry(
                path = f.absolutePath,
                name = name,
                isDirectory = f.isDirectory,
                size = if (f.isDirectory) 0L else f.length(),
                lastModified = f.lastModified(),
                type = if (f.isDirectory) FileType.FOLDER else typeOf(name),
                hidden = f.isHidden,
            )
        }

        fun typeOf(name: String): FileType = when (
            name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        ) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "avif" -> FileType.IMAGE
            "mp4", "mkv", "webm", "avi", "mov", "3gp", "flv", "wmv", "m4v" -> FileType.VIDEO
            "mp3", "aac", "flac", "ogg", "wav", "m4a", "wma", "opus" -> FileType.AUDIO
            "pdf" -> FileType.PDF
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "csv", "rtf", "epub" -> FileType.DOCUMENT
            "txt", "md", "log", "json", "xml", "yaml", "yml", "ini", "cfg", "properties",
            "kt", "java", "py", "js", "ts", "html", "css", "sh", "sql", "gradle" -> FileType.TEXT
            "apk", "aab", "xapk", "apks" -> FileType.APK
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "jar" -> FileType.ARCHIVE
            else -> FileType.OTHER
        }

        fun mimeOf(entry: FileEntry): String {
            if (entry.isDirectory) return "inode/directory"
            val ext = entry.extension
            if (ext.isEmpty()) return "application/octet-stream"
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"
        }
    }
}

fun formatSize(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = -1
    do { v /= 1024; i++ } while (v >= 1024 && i < units.size - 1)
    return "%.1f %s".format(v, units[i])
}

private val dateFormat = object : ThreadLocal<java.text.SimpleDateFormat>() {
    override fun initialValue() = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
}

fun formatDate(millis: Long): String {
    if (millis <= 0) return "—"
    return dateFormat.get()!!.format(java.util.Date(millis))
}
