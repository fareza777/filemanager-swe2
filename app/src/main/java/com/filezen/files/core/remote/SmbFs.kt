package com.filezen.files.core.remote

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthentication
import jcifs.smb.SmbFile
import java.io.InputStream
import java.util.Properties

/**
 * SMB backend over jcifs-ng. [RemoteConnection.root] is "share" or
 * "share/sub/dir" — the share name is the first segment; [path] below it.
 */
class SmbFs(private val c: RemoteConnection) : RemoteFs {

    private val ctx: CIFSContext by lazy {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.resolveOrder", "DNS")
            setProperty("jcifs.smb.client.connTimeout", "15000")
            setProperty("jcifs.smb.client.responseTimeout", "20000")
        }
        val base = BaseContext(PropertyConfiguration(props))
        base.withCredentials(NtlmPasswordAuthentication(base, null, c.user, c.pass))
    }

    private val shareName: String get() =
        c.root.trim('/').substringBefore('/').ifEmpty { c.root.trim('/') }

    private fun urlFor(path: String): String {
        val share = c.root.trim('/')
        val rel = path.trim('/')
        val sb = StringBuilder("smb://${c.host}")
        if (c.port > 0 && c.port != 445) sb.append(":${c.port}")
        if (share.isNotEmpty()) sb.append('/').append(share)
        if (rel.isNotEmpty()) sb.append('/').append(rel)
        if (!rel.isNotEmpty() || path.endsWith("/")) sb.append('/')
        return sb.toString()
    }

    private fun smb(path: String, dir: Boolean = path.endsWith("/")): SmbFile =
        SmbFile(urlFor(if (dir) path.trimEnd('/') + "/" else path), ctx)

    override fun list(path: String): List<RemoteEntry> {
        val dir = smb(path, true)
        if (!dir.exists()) throw FsException("No such share/dir: ${dir.uncPath}")
        return dir.listFiles()?.map { f ->
            RemoteEntry(
                name = f.name.trimEnd('/'),
                path = RemoteFs.joinPath(path, f.name.trimEnd('/')),
                isDir = f.isDirectory,
                size = if (f.isDirectory) 0 else runCatching { f.length() }.getOrDefault(0),
                mtime = runCatching { f.lastModified }.getOrDefault(0L),
            )
        } ?: emptyList()
    }

    override fun stat(path: String): RemoteEntry? {
        val f = smb(path, false)
        return if (f.exists()) RemoteEntry(f.name.trimEnd('/'), path, f.isDirectory,
            f.length(), f.lastModified) else null
    }

    override fun openInput(path: String): InputStream = smb(path).inputStream

    override fun write(path: String, input: InputStream, len: Long) {
        smb(path).outputStream.use { input.copyTo(it, 128 * 1024) }
    }

    override fun mkdir(path: String) { smb(path, true).mkdir() }

    override fun delete(path: String, isDir: Boolean) {
        val f = if (isDir) smb(path, true) else smb(path)
        f.delete()
    }

    override fun rename(from: String, to: String) {
        smb(from).renameTo(smb(to))
    }
}
