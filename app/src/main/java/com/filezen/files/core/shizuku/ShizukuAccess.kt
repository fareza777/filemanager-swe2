package com.filezen.files.core.shizuku

import android.content.Context
import android.content.pm.PackageManager
import com.filezen.files.core.model.FileEntry
import com.filezen.files.core.model.FileType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Power file access via Shizuku (https://github.com/RikkaApps/Shizuku, Apache-2.0).
 *
 * FileZen binds to the Shizuku service (installed + started by the user or by
 * Sui on rooted devices) and spawns processes as the shell user (uid 2000),
 * which can read and write folders that are off-limits to apps — most notably
 * /sdcard/Android/data and /sdcard/Android/obb on Android 11+.
 *
 * All privileged work goes through toybox shell commands over the Shizuku
 * process channel; no code is run inside the user's app sandbox.
 */
class ShizukuAccess(private val app: Context) {

    enum class Status { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    private val _status = MutableStateFlow(probeStatus())
    val status: StateFlow<Status> = _status

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        _status.value = if (grantResult == PackageManager.PERMISSION_GRANTED) Status.READY
            else Status.NO_PERMISSION
    }

    init {
        runCatching { Shizuku.addRequestPermissionResultListener(permListener) }
    }

    fun refreshStatus() { _status.value = probeStatus() }

    private fun probeStatus(): Status {
        if (!installed()) return Status.NOT_INSTALLED
        val binder = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binder) return Status.NOT_RUNNING
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (granted) Status.READY else Status.NO_PERMISSION
    }

    fun installed(): Boolean =
        runCatching {
            @Suppress("DEPRECATION")
            app.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        }.isSuccess

    fun ready(): Boolean = _status.value == Status.READY

    /** Ask the Shizuku app to grant us permission (shows its dialog). */
    fun requestPermission(requestCode: Int = 42) {
        runCatching {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                // no-op: we always explain in our own UI first
            }
            Shizuku.requestPermission(requestCode)
        }
    }

    // ---------- process spawn (Shizuku's hidden newProcess via reflection) ----------

    private val newProcessMethod by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java,
            Array<String>::class.java, String::class.java,
        ).apply { isAccessible = true }
    }

    private fun spawn(cmd: List<String>): java.lang.Process =
        newProcessMethod.invoke(null, cmd.toTypedArray(), null, null) as java.lang.Process

    data class ExecResult(val code: Int, val stdout: String, val stderr: String)

    fun exec(cmd: List<String>): ExecResult {
        val p = spawn(cmd)
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val t1 = Thread { p.inputStream.copyTo(out) }
        val t2 = Thread { p.errorStream.copyTo(err) }
        t1.start(); t2.start()
        val code = p.waitFor()
        t1.join(2000); t2.join(2000)
        return ExecResult(code, out.toString(), err.toString())
    }

    fun execShell(script: String): ExecResult = exec(listOf("sh", "-c", script))

    // ---------- file ops ----------

    /**
     * List a directory the sandbox cannot read. `stat -c` gives
     * type|size|mtime|name per entry — robust against spaces in names.
     */
    fun list(path: String, showHidden: Boolean): List<FileEntry> {
        val q = shq(path)
        val script = if (showHidden)
            "stat -c '%F|%s|%Y|%n' $q/* $q/.[!.]* $q/..?* 2>/dev/null"
        else
            "stat -c '%F|%s|%Y|%n' $q/* 2>/dev/null"
        val r = execShell(script)
        if (r.code != 0 && r.stdout.isBlank()) return emptyList()
        return r.stdout.lineSequence().mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 4) return@mapNotNull null
            val full = parts.subList(3, parts.size).joinToString("|")
            val name = full.substringAfterLast('/')
            val isDir = parts[0].contains("directory")
            val type = if (isDir) FileType.FOLDER else FileEntry.typeOf(name)
            FileEntry(
                path = full, name = name, isDirectory = isDir,
                size = parts[1].toLongOrNull() ?: 0L,
                lastModified = (parts[2].toDoubleOrNull() ?: 0.0).toLong() * 1000,
                type = type,
            )
        }.toList()
    }

    fun stat(path: String): FileEntry? {
        val r = execShell("stat -c '%F|%s|%Y|%n' ${shq(path)}")
        if (r.code != 0) return null
        val parts = r.stdout.trim().split('|')
        if (parts.size < 4) return null
        val full = parts.subList(3, parts.size).joinToString("|")
        val isDir = parts[0].contains("directory")
        return FileEntry(full, full.substringAfterLast('/'), isDir,
            parts[1].toLongOrNull() ?: 0L,
            (parts[2].toDoubleOrNull() ?: 0.0).toLong() * 1000,
            if (isDir) FileType.FOLDER else FileEntry.typeOf(full))
    }

    fun exists(path: String): Boolean =
        execShell("test -e ${shq(path)}").code == 0

    fun mkdir(path: String): Boolean =
        execShell("mkdir -p ${shq(path)}").code == 0

    fun delete(path: String): Boolean =
        execShell("rm -rf ${shq(path)}").code == 0

    fun move(src: String, dst: String): Boolean =
        execShell("mv ${shq(src)} ${shq(dst)}").code == 0

    fun copy(src: String, dst: String): Boolean =
        execShell("cp -a ${shq(src)} ${shq(dst)}").code == 0

    /** Stream a privileged file's bytes into a normal writable destination. */
    fun readTo(path: String, dest: File): Boolean {
        return try {
            val p = spawn(listOf("cat", path))
            FileOutputStream(dest).use { p.inputStream.copyTo(it) }
            p.waitFor() == 0
        } catch (e: Exception) { false }
    }

    /** Write a normal file into a privileged destination. */
    fun writeFrom(src: File, dst: String): Boolean {
        return try {
            val p = spawn(listOf("sh", "-c", "cat > ${shq(dst)}"))
            src.inputStream().use { it.copyTo(p.outputStream) }
            p.outputStream.close()
            p.waitFor() == 0
        } catch (e: Exception) { false }
    }

    /** True when [path] is somewhere the sandbox normally can't reach. */
    fun isRestrictedPath(path: String): Boolean =
        path.startsWith("/storage/emulated/0/Android/data") ||
            path.startsWith("/storage/emulated/0/Android/obb") ||
            path.startsWith("/sdcard/Android/data") ||
            path.startsWith("/sdcard/Android/obb") ||
            (path.startsWith("/data") && !path.startsWith("/data/data/com.filezen.files"))

    private fun shq(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"
}
