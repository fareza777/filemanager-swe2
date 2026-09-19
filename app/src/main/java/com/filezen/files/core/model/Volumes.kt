package com.filezen.files.core.model

import android.content.Context
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

data class Volume(val name: String, val root: File, val removable: Boolean, val safUri: String? = null)

object Volumes {

    /** Internal shared storage plus any mounted removable volume roots we can see. */
    fun detect(ctx: Context, safRoots: Set<String> = emptySet()): List<Volume> {
        val out = mutableListOf<Volume>()
        val primary = Environment.getExternalStorageDirectory()
        out += Volume("Internal storage", primary, removable = false)

        // Removable volumes derived from app-specific dirs (path root = strip /Android/data/pkg/files)
        val dirs = ContextCompat.getExternalFilesDirs(ctx, null)
        for (d in dirs.drop(1)) {
            if (d == null) continue
            val p = d.absolutePath
            val marker = "/Android/"
            val idx = p.indexOf(marker)
            if (idx <= 0) continue
            val root = File(p.substring(0, idx))
            if (!root.exists()) continue
            val label = if (root.absolutePath.contains("usb", true)) "USB storage"
                else "SD card (${root.name})"
            out += Volume(label, root, removable = true)
        }

        // SAF picked roots
        safRoots.forEach { uri -> out += Volume("Shared folder", File(uri), removable = true, safUri = uri) }
        return out
    }
}
