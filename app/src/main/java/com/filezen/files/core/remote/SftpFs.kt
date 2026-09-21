package com.filezen.files.core.remote

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.InputStream
import java.util.Vector

/** SFTP backend over JSch. One SSH session per filesystem instance. */
class SftpFs(private val c: RemoteConnection) : RemoteFs {

    private val session: Session by lazy {
        val jsch = JSch()
        val s = jsch.getSession(c.user.ifBlank { "anonymous" }, c.host,
            if (c.port > 0) c.port else 22)
        if (c.pass.isNotEmpty()) s.setPassword(c.pass)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", "password")
        s.connect(15000)
        s
    }

    private val chan: ChannelSftp by lazy {
        (session.openChannel("sftp") as ChannelSftp).apply { connect(15000) }
    }

    private fun abs(path: String): String {
        val root = c.root.trimEnd('/')
        val rel = path.trimStart('/')
        return if (rel.isEmpty()) (if (root.isEmpty()) "." else root)
            else if (root.isEmpty() || root == "/") "/$rel" else "$root/$rel"
    }

    override fun list(path: String): List<RemoteEntry> {
        val out = ArrayList<RemoteEntry>()
        @Suppress("UNCHECKED_CAST")
        val v = chan.ls(abs(path)) as Vector<ChannelSftp.LsEntry>
        for (e in v) {
            if (e.filename == "." || e.filename == "..") continue
            val a = e.attrs
            out += RemoteEntry(
                name = e.filename,
                path = RemoteFs.joinPath(path, e.filename),
                isDir = a.isDir,
                size = a.size,
                mtime = a.mTime.toLong() * 1000L,
            )
        }
        return out
    }

    override fun stat(path: String): RemoteEntry? = try {
        val a = chan.stat(abs(path))
        RemoteEntry(path.substringAfterLast('/'), path, a.isDir, a.size, a.mTime.toLong() * 1000L)
    } catch (e: Exception) { null }

    override fun openInput(path: String): InputStream = chan.get(abs(path))

    override fun write(path: String, input: InputStream, len: Long) {
        chan.put(input, abs(path))
    }

    override fun mkdir(path: String) { chan.mkdir(abs(path)) }

    override fun delete(path: String, isDir: Boolean) {
        if (isDir) {
            for (e in list(path)) delete(e.path, e.isDir)
            chan.rmdir(abs(path))
        } else chan.rm(abs(path))
    }

    override fun rename(from: String, to: String) { chan.rename(abs(from), abs(to)) }

    override fun close() {
        runCatching { chan.disconnect() }
        runCatching { if (session.isConnected) session.disconnect() }
    }
}
