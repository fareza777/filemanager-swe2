package com.filezen.files.ops

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.filezen.files.BuildConfig
import com.filezen.files.core.model.FileEntry
import java.io.File

object Intents {

    private fun uriFor(ctx: Context, f: File): Uri = try {
        FileProvider.getUriForFile(ctx, "${BuildConfig.APPLICATION_ID}.fileprovider", f)
    } catch (e: Exception) {
        Uri.fromFile(f)
    }

    fun openWith(ctx: Context, e: FileEntry) {
        val f = File(e.path)
        val uri = uriFor(ctx, f)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, FileEntry.mimeOf(e))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (ex: Exception) { /* no handler */ }
    }

    fun share(ctx: Context, items: List<FileEntry>) {
        val uris = items.filter { !it.isDirectory }.map { uriFor(ctx, File(it.path)) }
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (ex: Exception) { }
    }
}
