package com.filezen.files.core.remote

import java.io.InputStream

/** One remote entry (file or directory). Path is always the backend's absolute path. */
data class RemoteEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
)

enum class RemoteType(val label: String, val defaultPort: Int) {
    SFTP("SFTP / SSH", 22),
    SMB("SMB / Windows share", 445),
    WEBDAV("WebDAV", 80),
    S3("S3 bucket", 443),
}

data class RemoteConnection(
    val id: Long,
    val type: RemoteType,
    val label: String,
    val host: String,
    val port: Int,
    val user: String,
    val pass: String,
    /** Remote start dir — for SMB "share/path", for S3 "bucket/prefix", others "/path". */
    val root: String,
    /** S3 region; ignored by other types. */
    val extra: String = "",
)

class FsException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

/**
 * The one interface every remote backend implements — Twig's FileSystem idea.
 * All functions are blocking; callers run them on Dispatchers.IO.
 */
interface RemoteFs {
    fun list(path: String): List<RemoteEntry>
    fun stat(path: String): RemoteEntry?
    fun openInput(path: String): InputStream
    /** Upload [len] bytes from [input] to [path]. len -1 = unknown. */
    fun write(path: String, input: InputStream, len: Long = -1L)
    fun mkdir(path: String)
    fun delete(path: String, isDir: Boolean)
    fun rename(from: String, to: String)
    fun close() {}

    companion object {
        fun of(c: RemoteConnection): RemoteFs = when (c.type) {
            RemoteType.SFTP -> SftpFs(c)
            RemoteType.WEBDAV -> WebDavFs(c)
            RemoteType.SMB -> SmbFs(c)
            RemoteType.S3 -> S3Fs(c)
        }
        fun joinPath(dir: String, name: String): String =
            if (dir.endsWith("/")) dir + name else "$dir/$name"
    }
}
