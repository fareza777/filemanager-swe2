package com.filezen.files.core.clip

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.filezen.files.FileZenApp
import kotlinx.coroutines.CancellationException

/**
 * Photo-embedding worker — enqueued by [ImageIndex.kick] as unique work
 * ("photo-index", APPEND_OR_REPLACE). Runs as expedited work, so indexing
 * keeps going after the user leaves the app; if the process is killed,
 * WorkManager reschedules it and the incremental pass resumes from the
 * per-file rows already committed — never from zero.
 */
class PhotoIndexWorker(ctx: Context, params: WorkerParameters) :
    CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        setForeground(foregroundInfo(applicationContext))
        val index = FileZenApp.instance.container.imageIndex
        return try {
            if (inputData.getBoolean(KEY_REBUILD, false)) index.clearAll()
            index.drainQueue()
            Result.success()
        } catch (t: CancellationException) {
            throw t
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "photo-index"
        const val KEY_REBUILD = "rebuild"
        private const val CHANNEL = "photo-index"
        private const val NOTIF_ID = 42

        fun foregroundInfo(ctx: Context): ForegroundInfo {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Photo index",
                    NotificationManager.IMPORTANCE_LOW))
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("Indexing photos")
                .setContentText("FileZen is learning what your photos show — runs in background.")
                .setOngoing(true)
                .setSilent(true)
                .build()
            return ForegroundInfo(NOTIF_ID, n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
    }
}
