package com.filezen.files.core.scan

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppStorageEntry(
    val packageName: String,
    val label: String,
    val appBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
    val isSystem: Boolean = false,
    /** The app's shared-storage data dir when it exists (browsable). */
    val extDataPath: String? = null,
) {
    val totalBytes: Long get() = appBytes + dataBytes + cacheBytes
}

/**
 * Per-app storage via StorageStatsManager — the only Play-safe way to see how
 * much room each installed app occupies (app + private data + cache). Requires
 * Usage Access (PACKAGE_USAGE_STATS / AppOps MODE_ALLOWED); everything degrades
 * gracefully without it. Never touches other apps' private files directly.
 */
object AppStorageAnalyzer {

    fun hasUsageAccess(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = try {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), ctx.packageName)
        } catch (e: Exception) { return false }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Total device capacity incl. system-reserved space (e.g. "128 GB").
     *  StatFs total is only the usable part — the gap is OEM/system overhead. */
    fun deviceTotalBytes(ctx: Context): Long = try {
        val sm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        sm.getTotalBytes(StorageManager.UUID_DEFAULT)
    } catch (e: Exception) {
        StatFs("/data").totalBytes
    }

    /** Every installed package with non-zero storage, biggest first. */
    suspend fun perApp(ctx: Context): List<AppStorageEntry> = withContext(Dispatchers.IO) {
        val sm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val pm = ctx.packageManager
        val user = Process.myUserHandle()
        val extData = java.io.File(
            android.os.Environment.getExternalStorageDirectory(),
            "Android/data")
        val out = ArrayList<AppStorageEntry>(256)
        for (ai in pm.getInstalledApplications(0)) {
            try {
                val st = sm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, user)
                val total = st.appBytes + st.dataBytes + st.cacheBytes
                if (total <= 0) continue
                val extDir = java.io.File(extData, ai.packageName)
                out += AppStorageEntry(
                    packageName = ai.packageName,
                    label = runCatching { ai.loadLabel(pm).toString() }
                        .getOrDefault(ai.packageName),
                    appBytes = st.appBytes,
                    dataBytes = st.dataBytes,
                    cacheBytes = st.cacheBytes,
                    isSystem = ai.flags and
                        android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0,
                    extDataPath = if (extDir.exists()) extDir.absolutePath else null)
            } catch (e: Exception) {
                // Package uninstalled mid-scan or restricted profile — skip.
            }
        }
        out.sortByDescending { it.totalBytes }
        out
    }
}
