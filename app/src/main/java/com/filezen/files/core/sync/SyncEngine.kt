package com.filezen.files.core.sync

import com.filezen.files.core.remote.RemoteFs
import com.filezen.files.data.db.SyncPair
import com.filezen.files.data.db.SyncStateEntry
import java.io.File
import java.io.FileInputStream
import kotlin.math.abs

/**
 * Folder-pair sync engine — a port of OpenSync's SyncEngine
 * (https://github.com/thedickestrick/opensync, MIT) adapted to FileZen's
 * RemoteFs. One side is always a device folder; the other is either another
 * device folder or an SMB/SFTP/WebDAV/S3 connection.
 *
 * Directions: TO_REMOTE / FROM_REMOTE mirror the source onto the destination;
 * TWO_WAY uses the last persisted [SyncStateEntry] snapshot to detect which
 * side changed and to propagate deletions.
 */
interface SyncStore {
    fun walk(subfolders: Boolean): Map<String, Entry>
    fun walkDirs(subfolders: Boolean): Set<String>
    fun makeDir(rel: String)
    fun delete(rel: String, isDir: Boolean)
    /** Copy [rel] from this store into [out] (a local temp file). */
    fun readTo(rel: String, out: File)
    /** Write local temp [src] into this store at [rel], preserving [mtime]. */
    fun writeFrom(src: File, rel: String, mtime: Long)
    /** Fast local↔local copy shortcut; null when either side is remote. */
    fun localFile(rel: String): File? = null
    fun stat(rel: String): Entry?
    fun close() {}

    data class Entry(val relPath: String, val size: Long, val mtime: Long)
}

class LocalSyncStore(private val root: File) : SyncStore {
    override fun walk(subfolders: Boolean): Map<String, SyncStore.Entry> {
        val out = LinkedHashMap<String, SyncStore.Entry>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        val rootLen = root.absolutePath.trimEnd('/').length + 1
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val kids = d.listFiles() ?: if (d == root) return emptyMap() else continue
            for (f in kids) {
                if (f.isDirectory) {
                    if (subfolders) stack.addLast(f)
                } else {
                    out[f.absolutePath.substring(rootLen)] =
                        SyncStore.Entry(f.absolutePath.substring(rootLen), f.length(), f.lastModified())
                }
            }
        }
        return out
    }

    override fun walkDirs(subfolders: Boolean): Set<String> {
        val out = LinkedHashSet<String>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        val rootLen = root.absolutePath.trimEnd('/').length + 1
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            for (f in d.listFiles() ?: continue) {
                if (f.isDirectory) {
                    out.add(f.absolutePath.substring(rootLen))
                    if (subfolders) stack.addLast(f)
                }
            }
        }
        return out
    }

    override fun makeDir(rel: String) { File(root, rel).mkdirs() }
    override fun delete(rel: String, isDir: Boolean) {
        val f = File(root, rel)
        if (isDir) f.deleteRecursively() else f.delete()
    }
    override fun readTo(rel: String, out: File) { File(root, rel).copyTo(out, overwrite = true) }
    override fun writeFrom(src: File, rel: String, mtime: Long) {
        val dst = File(root, rel)
        dst.parentFile?.mkdirs()
        src.copyTo(dst, overwrite = true)
        if (mtime > 0) dst.setLastModified(mtime)
    }
    override fun localFile(rel: String): File = File(root, rel)
    override fun stat(rel: String): SyncStore.Entry? {
        val f = File(root, rel)
        return if (f.isFile) SyncStore.Entry(rel, f.length(), f.lastModified()) else null
    }
}

/** Adapts a FileZen [RemoteFs] connection to [SyncStore]. Paths are rel to [base]. */
class RemoteSyncStore(private val fs: RemoteFs, private val base: String) : SyncStore {
    private fun abs(rel: String) = if (rel.isEmpty()) base else RemoteFs.joinPath(base, rel)

    override fun walk(subfolders: Boolean): Map<String, SyncStore.Entry> {
        val out = LinkedHashMap<String, SyncStore.Entry>()
        val stack = ArrayDeque<String>()
        stack.addLast("")
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val kids = try { fs.list(abs(dir)) } catch (e: Exception) {
                if (dir.isEmpty()) throw e else continue
            }
            for (e in kids) {
                if (e.isDir) {
                    if (subfolders) stack.addLast(if (dir.isEmpty()) e.name else "$dir/${e.name}")
                } else {
                    val rel = if (dir.isEmpty()) e.name else "$dir/${e.name}"
                    out[rel] = SyncStore.Entry(rel, e.size, e.mtime)
                }
            }
        }
        return out
    }

    override fun walkDirs(subfolders: Boolean): Set<String> {
        val out = LinkedHashSet<String>()
        val stack = ArrayDeque<String>()
        stack.addLast("")
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            for (e in try { fs.list(abs(dir)) } catch (_: Exception) { continue }) {
                if (e.isDir) {
                    val rel = if (dir.isEmpty()) e.name else "$dir/${e.name}"
                    out.add(rel)
                    if (subfolders) stack.addLast(rel)
                }
            }
        }
        return out
    }

    override fun makeDir(rel: String) { fs.mkdir(abs(rel)) }
    override fun delete(rel: String, isDir: Boolean) { fs.delete(abs(rel), isDir) }
    override fun readTo(rel: String, out: File) {
        fs.openInput(abs(rel)).use { it.copyTo(out.outputStream()) }
    }
    override fun writeFrom(src: File, rel: String, mtime: Long) {
        ensureParents(rel)
        FileInputStream(src).use { fs.write(abs(rel), it, src.length()) }
        // Remote mtime preservation is best-effort; RemoteFs has no setMtime.
        stat(rel)?.let { /* recorded by caller */ }
    }
    private fun ensureParents(rel: String) {
        val parts = rel.split('/').dropLast(1)
        var cur = base
        for (p in parts) {
            cur = RemoteFs.joinPath(cur, p)
            runCatching { fs.mkdir(cur) }
        }
    }
    override fun stat(rel: String): SyncStore.Entry? =
        fs.stat(abs(rel))?.let { SyncStore.Entry(rel, it.size, it.mtime) }
    override fun close() = fs.close()
}

class FolderSyncEngine(
    private val local: SyncStore,
    private val remote: SyncStore,
    private val pair: SyncPair,
    private val tempDir: File,
) {
    fun interface Progress { fun update(message: String, done: Int, total: Int) }

    data class Result(
        val filesCopied: Int,
        val filesDeleted: Int,
        val conflicts: Int,
        val bytes: Long,
        val summary: String,
        val newState: List<SyncStateEntry>,
    )

    private var copied = 0
    private var deleted = 0
    private var conflicts = 0
    private var bytes = 0L
    // FastCDC delta accounting (local↔local only): bytes the destination
    // file already contained in chunk form across changed files.
    private var deltaReused = 0L
    private var deltaChanged = 0L

    private class Scan(
        val files: Map<String, SyncStore.Entry>,
        val dirs: Set<String>,
    )

    fun run(prevState: List<SyncStateEntry>, progress: Progress?, isCancelled: () -> Boolean = { false }): Result {
        val localScan = Scan(local.walk(pair.includeSubfolders), local.walkDirs(pair.includeSubfolders))
        val remoteScan = Scan(remote.walk(pair.includeSubfolders), remote.walkDirs(pair.includeSubfolders))

        val newState = when (pair.direction) {
            "TO_REMOTE" -> { mirror(localScan, remoteScan, local, remote, progress, isCancelled, "↑"); emptyList() }
            "FROM_REMOTE" -> { mirror(remoteScan, localScan, remote, local, progress, isCancelled, "↓"); emptyList() }
            else -> twoWay(localScan, remoteScan, prevState, progress, isCancelled)
        }
        val summary = buildString {
            append("Copied $copied, deleted $deleted")
            if (conflicts > 0) append(", $conflicts conflict(s)")
            if (deltaChanged > 0) {
                append(", Δ reused ${deltaReused * 100 / deltaChanged}%")
            }
        }
        return Result(copied, deleted, conflicts, bytes, summary, newState)
    }

    private fun mirror(
        src: Scan, dst: Scan, srcP: SyncStore, dstP: SyncStore,
        progress: Progress?, isCancelled: () -> Boolean, arrow: String,
    ) {
        for (rel in src.dirs.sortedBy { depth(it) }) {
            if (rel !in dst.dirs) dstP.makeDir(rel)
        }
        val total = src.files.size
        var idx = 0
        for ((rel, sf) in src.files) {
            if (isCancelled()) throw InterruptedException("Sync cancelled")
            idx++
            val df = dst.files[rel]
            if (df == null || differs(sf, df)) {
                progress?.update("$arrow ${name(rel)}", idx, total)
                copy(srcP, dstP, rel, sf.mtime)
                copied++; bytes += sf.size
            }
        }
        if (pair.deleteOrphans) {
            for (rel in dst.files.keys) {
                if (rel !in src.files) { dstP.delete(rel, false); deleted++ }
            }
            for (rel in dst.dirs.sortedByDescending { depth(it) }) {
                if (rel !in src.dirs) runCatching { dstP.delete(rel, true) }
            }
        }
    }

    private fun twoWay(
        localScan: Scan, remoteScan: Scan, prevState: List<SyncStateEntry>,
        progress: Progress?, isCancelled: () -> Boolean,
    ): List<SyncStateEntry> {
        val state = prevState.associateBy { it.relPath }
        val newState = LinkedHashMap<String, SyncStateEntry>()

        for (rel in localScan.dirs.sortedBy { depth(it) }) {
            if (rel !in remoteScan.dirs) remote.makeDir(rel)
        }
        for (rel in remoteScan.dirs.sortedBy { depth(it) }) {
            if (rel !in localScan.dirs) local.makeDir(rel)
        }

        val allPaths = LinkedHashSet<String>().apply {
            addAll(localScan.files.keys); addAll(remoteScan.files.keys); addAll(state.keys)
        }
        val total = allPaths.size
        var idx = 0

        for (rel in allPaths) {
            if (isCancelled()) throw InterruptedException("Sync cancelled")
            idx++
            val l = localScan.files[rel]
            val r = remoteScan.files[rel]
            val st = state[rel]
            val localChanged = l != null && (st == null || !sigEq(l, st.localSize, st.localMtime))
            val remoteChanged = r != null && (st == null || !sigEq(r, st.remoteSize, st.remoteMtime))

            when {
                l != null && r != null -> {
                    val bothEqual = l.size == r.size && abs(l.mtime - r.mtime) <= TOL
                    when {
                        bothEqual -> record(newState, rel, l.size, l.mtime, r.size, r.mtime)
                        localChanged && !remoteChanged -> upload(rel, l, progress, idx, total, newState)
                        remoteChanged && !localChanged -> download(rel, r, progress, idx, total, newState)
                        !localChanged && !remoteChanged -> record(newState, rel, l.size, l.mtime, r.size, r.mtime)
                        else -> resolveConflict(rel, l, r, progress, idx, total, newState)
                    }
                }
                l != null && r == null -> {
                    if (st != null && !localChanged && pair.deleteOrphans) {
                        local.delete(rel, false); deleted++ // remote deletion propagated
                    } else upload(rel, l, progress, idx, total, newState)
                }
                r != null && l == null -> {
                    if (st != null && !remoteChanged && pair.deleteOrphans) {
                        remote.delete(rel, false); deleted++
                    } else download(rel, r, progress, idx, total, newState)
                }
                else -> { /* both deleted: drop from state */ }
            }
        }
        return newState.values.toList()
    }

    private fun resolveConflict(
        rel: String, l: SyncStore.Entry, r: SyncStore.Entry,
        progress: Progress?, idx: Int, total: Int,
        newState: MutableMap<String, SyncStateEntry>,
    ) {
        conflicts++
        when (pair.conflictRule) {
            "LOCAL_WINS" -> upload(rel, l, progress, idx, total, newState)
            "REMOTE_WINS" -> download(rel, r, progress, idx, total, newState)
            "SKIP" -> {}
            else -> // NEWER_WINS
                if (l.mtime >= r.mtime) upload(rel, l, progress, idx, total, newState)
                else download(rel, r, progress, idx, total, newState)
        }
    }

    private fun upload(rel: String, l: SyncStore.Entry, progress: Progress?, idx: Int, total: Int, newState: MutableMap<String, SyncStateEntry>) {
        progress?.update("↑ ${name(rel)}", idx, total)
        copy(local, remote, rel, l.mtime)
        copied++; bytes += l.size
        val rf = remote.stat(rel)
        record(newState, rel, l.size, l.mtime, rf?.size ?: l.size, rf?.mtime ?: l.mtime)
    }

    private fun download(rel: String, r: SyncStore.Entry, progress: Progress?, idx: Int, total: Int, newState: MutableMap<String, SyncStateEntry>) {
        progress?.update("↓ ${name(rel)}", idx, total)
        copy(remote, local, rel, r.mtime)
        copied++; bytes += r.size
        val lf = local.stat(rel)
        record(newState, rel, lf?.size ?: r.size, lf?.mtime ?: r.mtime, r.size, r.mtime)
    }

    private fun record(map: MutableMap<String, SyncStateEntry>, rel: String, ls: Long, lm: Long, rs: Long, rm: Long) {
        map[rel] = SyncStateEntry(pair.id, rel, ls, lm, rs, rm)
    }

    private fun copy(from: SyncStore, to: SyncStore, rel: String, mtime: Long) {
        val sf = from.localFile(rel)
        val df = to.localFile(rel)
        if (sf != null && df != null) {
            df.parentFile?.mkdirs()
            if (df.exists() && sf.length() >= 256 * 1024) {
                // FastCDC: how much of the incoming file already exists at the
                // destination — the delta-transfer accounting from
                // cdc-file-transfer applied to local syncs.
                com.filezen.files.core.delta.DeltaEngine.reuseStats(df, sf)?.let {
                    deltaReused += it.reusedBytes; deltaChanged += it.newSize
                }
            }
            sf.copyTo(df, overwrite = true)
            if (mtime > 0) df.setLastModified(mtime)
            return
        }
        val tmp = File.createTempFile("fzen_sync", ".tmp", tempDir)
        try {
            from.readTo(rel, tmp)
            to.writeFrom(tmp, rel, mtime)
        } finally {
            tmp.delete()
        }
    }

    private fun differs(src: SyncStore.Entry, dst: SyncStore.Entry): Boolean =
        src.size != dst.size || src.mtime > dst.mtime + TOL

    private fun sigEq(f: SyncStore.Entry, size: Long, mtime: Long): Boolean =
        f.size == size && abs(f.mtime - mtime) <= TOL

    private fun depth(rel: String) = rel.count { it == '/' }
    private fun name(rel: String) = rel.substringAfterLast('/')

    companion object { private const val TOL = 2000L }
}
