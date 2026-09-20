package com.filezen.files.core.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.filezen.files.FileZenApp
import com.filezen.files.MainActivity
import com.filezen.files.R

/**
 * Foreground service keeping the Transfer HTTP server alive while the app is
 * backgrounded — the user can switch tabs/screens and the PC browser keeps
 * working. Shows a persistent notification with the URL and a Stop action.
 */
class TransferService : Service() {

    companion object {
        const val ACTION_START = "com.filezen.files.TRANSFER_START"
        const val ACTION_STOP = "com.filezen.files.TRANSFER_STOP"
        private const val CHANNEL = "transfer"
        private const val NOTIF_ID = 42

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, TransferService::class.java)
                .setAction(ACTION_START))
        }
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, TransferService::class.java)
                .setAction(ACTION_STOP))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                FileZenApp.c.transfer.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                createChannel()
                startForegroundCompat(notification("Starting server…"))
                FileZenApp.c.transfer.start()
                // Post again with the real URL once the server is up.
                FileZenApp.c.transfer.state.value.url?.let { url ->
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIF_ID, notification(url))
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL, "Transfer to PC", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("FileZen Transfer is live")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }
}
