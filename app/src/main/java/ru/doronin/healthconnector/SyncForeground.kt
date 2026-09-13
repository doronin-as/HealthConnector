package ru.doronin.healthconnector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

object SyncForeground {
    const val MANUAL_NOTIFICATION_ID = 16160
    const val BACKGROUND_NOTIFICATION_ID = 16161
    private const val CHANNEL_ID = "health_connector_sync"

    fun info(context: Context, id: Int, title: String, text: String): ForegroundInfo {
        ensureChannel(context)
        val n = notification(context, title, text)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, n)
        }
    }

    fun update(context: Context, id: Int, title: String, text: String) {
        ensureChannel(context)
        context.getSystemService(NotificationManager::class.java)
            .notify(id, notification(context, title, text))
    }

    private fun notification(context: Context, title: String, text: String) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle(title)
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, StreamingMainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Синхронизация HealthConnector",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ход длительной синхронизации Health Connect"
                setSound(null, null)
            }
        )
    }
}
