package com.botillon.flowscribe.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.botillon.flowscribe.MainActivity
import com.botillon.flowscribe.R

object Notifications {

    const val CHANNEL_SERVICE = "flowscribe_bubble"
    const val CHANNEL_RESULTS = "flowscribe_results"
    const val SERVICE_ID = 1101
    const val RESULT_ID = 1102

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val service = NotificationChannel(
            CHANNEL_SERVICE,
            context.getString(R.string.notification_channel_service),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Maintient la bulle de dictée active."
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        val results = NotificationChannel(
            CHANNEL_RESULTS,
            context.getString(R.string.notification_channel_results),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Confirme les transcriptions insérées ou copiées."
            setShowBadge(false)
        }
        manager.createNotificationChannel(service)
        manager.createNotificationChannel(results)
    }

    fun bubble(
        context: Context,
        title: String = context.getString(R.string.notification_service_title),
        text: String = context.getString(R.string.notification_service_text),
        recording: Boolean = false,
    ): Notification {
        val open = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context,
            11,
            Intent(context, FloatingBubbleService::class.java).setAction(FloatingBubbleService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_flow)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(if (recording) NotificationCompat.CATEGORY_SERVICE else NotificationCompat.CATEGORY_STATUS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.notification_stop), stop)
            .build()
    }

    fun result(context: Context, text: String, copied: Boolean) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val preview = text.replace("\n", " ").trim().let {
            if (it.length > 140) it.take(137) + "…" else it
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_flow)
            .setContentTitle(if (copied) "Texte copié" else "Texte inséré")
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    12,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(RESULT_ID + (System.currentTimeMillis() % 100).toInt(), notification)
        }
    }
}
