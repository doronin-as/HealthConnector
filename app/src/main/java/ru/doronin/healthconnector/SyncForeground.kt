package ru.doronin.healthconnector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import java.util.concurrent.ConcurrentHashMap

object SyncForeground {
    const val MANUAL_NOTIFICATION_ID = 16160
    const val BACKGROUND_NOTIFICATION_ID = 16161
    private const val CHANNEL_ID = "health_connector_sync"

    private val startedAt = ConcurrentHashMap<Int, Long>()
    private val lastProgress = ConcurrentHashMap<Int, Int>()

    private val percentRegex = Regex("(?<!\\d)(\\d{1,3})\\s*%")
    private val stageRegex = Regex("(?:этап|шаг)\\s*(\\d+)\\s*/\\s*(\\d+)", RegexOption.IGNORE_CASE)

    fun info(context: Context, id: Int, title: String, text: String): ForegroundInfo {
        ensureChannel(context)
        startedAt.putIfAbsent(id, System.currentTimeMillis())
        val notification = notification(context, id, title, text)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    fun update(context: Context, id: Int, title: String, text: String) {
        ensureChannel(context)
        startedAt.putIfAbsent(id, System.currentTimeMillis())
        context.getSystemService(NotificationManager::class.java)
            .notify(id, notification(context, id, title, text))
    }

    private fun notification(context: Context, id: Int, title: String, text: String): Notification {
        val parsedProgress = parseProgress(text)
        if (parsedProgress != null) lastProgress[id] = parsedProgress
        val progress = parsedProgress ?: lastProgress[id]

        val visibleTitle = if (progress != null) "$title · $progress%" else title
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle(visibleTitle)
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setWhen(startedAt[id] ?: System.currentTimeMillis())
            .setUsesChronometer(true)
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

        if (progress != null) {
            builder.setProgress(100, progress, false)
            builder.setSubText("Синхронизация · $progress%")
        } else {
            builder.setProgress(0, 0, true)
            builder.setSubText("Синхронизация выполняется")
        }

        return builder.build()
    }

    private fun parseProgress(text: String): Int? {
        percentRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return it.coerceIn(0, 100)
        }

        val stage = stageRegex.find(text) ?: return null
        val current = stage.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val total = stage.groupValues.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        return ((current.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
    }

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
                description = "Прогресс длительной синхронизации Health Connect"
                setSound(null, null)
            }
        )
    }
}
