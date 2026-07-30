package com.ytdl.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ytdl.app.MainActivity
import com.ytdl.app.R

object Notifications {

    const val CHANNEL_PROGRESS = "downloads_progress"
    const val CHANNEL_DONE = "downloads_done"
    const val FOREGROUND_ID = 1001

    private const val DONE_ID_BASE = 2000

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                context.getString(R.string.notif_channel_progress),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DONE,
                context.getString(R.string.notif_channel_done),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, flags(update = true))
    }

    private fun flags(update: Boolean): Int {
        val base = if (update) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_ONE_SHOT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            base or PendingIntent.FLAG_IMMUTABLE
        } else base
    }

    fun progressNotification(context: Context, active: List<DownloadTask>): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        val running = active.firstOrNull { it.status == DownloadStatus.RUNNING } ?: active.firstOrNull()
        if (running == null) {
            return builder
                .setContentTitle(context.getString(R.string.app_name))
                .setProgress(0, 0, true)
                .build()
        }

        val statusText = when (running.status) {
            DownloadStatus.MERGING -> context.getString(R.string.merging)
            DownloadStatus.SAVING -> context.getString(R.string.saving)
            DownloadStatus.WAITING_WIFI -> context.getString(R.string.err_wifi_only)
            DownloadStatus.QUEUED -> context.getString(R.string.queued)
            else -> context.getString(R.string.downloading)
        }

        builder.setContentTitle(running.title.ifEmpty { running.displayName })
        builder.setContentText(
            if (active.size > 1) {
                "$statusText · " + context.getString(R.string.notif_downloading_n, active.size)
            } else statusText
        )

        val indeterminate = running.progress <= 0f ||
            running.status == DownloadStatus.MERGING || running.status == DownloadStatus.SAVING
        if (indeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, (running.progress * 100).toInt(), false)
        }
        return builder.build()
    }

    fun notifyFinished(context: Context, task: DownloadTask) {
        val manager = NotificationManagerCompat.from(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentTitle(
                context.getString(
                    if (task.status == DownloadStatus.COMPLETED) R.string.completed else R.string.failed
                )
            )
            .setContentText(task.error ?: task.displayName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(task.error ?: task.displayName))

        val uri = task.resultUri
        if (task.status == DownloadStatus.COMPLETED && uri != null) {
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(uri), Output.mimeFor(task.fileExtension))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            builder.setContentIntent(
                PendingIntent.getActivity(context, task.id.hashCode(), view, flags(update = true))
            )
        } else {
            builder.setContentIntent(openAppIntent(context))
        }

        runCatching {
            manager.notify(DONE_ID_BASE + (task.id.hashCode() and 0xFFF), builder.build())
        }
    }
}
