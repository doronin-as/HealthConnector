package ru.doronin.healthconnector.wellbeing

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ru.doronin.healthconnector.R
import ru.doronin.healthconnector.local.LocalStore
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Fires at the morning/evening check-in time, then schedules the next one. */
class WellbeingReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val slot = inputData.getString(KEY_SLOT) ?: ReminderSchedule.SLOT_MORNING
        val today = ZonedDateTime.now().toLocalDate().toString()
        if (isEnabled(applicationContext) && LocalStore.get(applicationContext).wellbeingFor(today, slot) == null) {
            notify(applicationContext, slot)
        }
        schedule(applicationContext)
        return Result.success()
    }

    companion object {
        const val PREF_ENABLED = "wellbeing_reminders_enabled"
        const val PREF_MORNING = "wellbeing_morning_time"
        const val PREF_EVENING = "wellbeing_evening_time"
        const val NOTIFICATION_ID = 18200
        private const val CHANNEL_ID = "health_connector_wellbeing"
        private const val KEY_SLOT = "slot"
        private const val UNIQUE = "wellbeing_reminder"
        val DEFAULT_MORNING: LocalTime = LocalTime.of(9, 30)
        val DEFAULT_EVENING: LocalTime = LocalTime.of(21, 30)

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(PREF_ENABLED, true)

        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context.applicationContext)
            if (!isEnabled(context)) {
                manager.cancelUniqueWork(UNIQUE)
                return
            }
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val next = ReminderSchedule.next(
                ZonedDateTime.now(),
                ReminderSchedule.parse(prefs.getString(PREF_MORNING, null), DEFAULT_MORNING),
                ReminderSchedule.parse(prefs.getString(PREF_EVENING, null), DEFAULT_EVENING)
            )
            val delayMs = Duration.between(ZonedDateTime.now(), next.at).toMillis().coerceAtLeast(0)
            val request = OneTimeWorkRequestBuilder<WellbeingReminderWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_SLOT to next.slot))
                .build()
            manager.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, request)
        }

        fun notify(context: Context, slot: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Самочувствие", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Утренняя и вечерняя отметка самочувствия"
                    }
                )
            }
            val open = PendingIntent.getActivity(
                context, 18201,
                Intent(context, WellbeingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val title = if (slot == ReminderSchedule.SLOT_MORNING) "Как вы утром?" else "Как прошёл день?"
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app_logo)
                .setContentTitle(title)
                .setContentText("Отметьте одним нажатием или откройте для подробностей")
                .setContentIntent(open)
                .setAutoCancel(true)
            listOf(4 to "😊 Хорошо", 3 to "😐 Норм", 2 to "😣 Плохо").forEach { (score, label) ->
                builder.addAction(0, label, WellbeingQuickReceiver.pendingIntent(context, score))
            }
            runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build()) }
        }
    }
}
