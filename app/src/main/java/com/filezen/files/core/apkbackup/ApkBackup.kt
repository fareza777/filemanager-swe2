package com.filezen.files.core.apkbackup

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import java.io.File

/**
 * APK exporter — lists installed apps and copies their .apk (base + splits)
 * out to storage. Inspired by github.com/ryosoftware/apks-exporter.
 */
object ApkBackup {

    data class App(
        val label: String,
        val packageName: String,
        val version: String,
        val apkSize: Long,
        val sourceDir: String,
        val splitDirs: List<String>,
        val icon: Drawable?,
    )

    fun listApps(ctx: Context): List<App> {
        val pm = ctx.packageManager
        val pkgs = pm.getInstalledPackages(PackageManager.GET_META_DATA)
        return pkgs.mapNotNull { p ->
            val ai = p.applicationInfo ?: return@mapNotNull null
            val src = ai.sourceDir ?: return@mapNotNull null
            val splits = ai.splitSourceDirs?.toList().orEmpty()
            val size = runCatching { File(src).length() }.getOrDefault(0) +
                splits.sumOf { runCatching { File(it).length() }.getOrDefault(0) }
            if (size <= 0) return@mapNotNull null
            App(
                label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(p.packageName),
                packageName = p.packageName,
                version = p.versionName ?: "-",
                apkSize = size,
                sourceDir = src,
                splitDirs = splits,
                icon = runCatching { ai.loadIcon(pm) }.getOrNull(),
            )
        }.sortedBy { it.label.lowercase() }
    }

    /** Copies base + split APKs of [app] into [destDir]. Returns written files. */
    fun backup(app: App, destDir: File): List<File> {
        destDir.mkdirs()
        val safe = app.label.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { app.packageName }
        val base = File(app.sourceDir)
        val out = mutableListOf<File>()
        val baseOut = File(destDir, "$safe-${app.version}.apk")
        base.copyTo(baseOut, overwrite = true)
        out += baseOut
        app.splitDirs.forEachIndexed { i, s ->
            val f = File(s)
            val sp = File(destDir, "$safe-${app.version}.split$i.apk")
            f.copyTo(sp, overwrite = true)
            out += sp
        }
        return out
    }
}
