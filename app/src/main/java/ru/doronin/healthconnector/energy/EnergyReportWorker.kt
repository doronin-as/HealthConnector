package ru.doronin.healthconnector.energy

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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import ru.doronin.healthconnector.PermissionGateActivity
import ru.doronin.healthconnector.R
import ru.doronin.healthconnector.local.ServerApi
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Monday 09:00: recalculates the balance (stores the weekly row) and sends a summary. */
class EnergyReportWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val result = try {
            EnergyRepository.refresh(applicationContext)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ServerApi.NotConfigured) {
            schedule(applicationContext); return Result.success()
        } catch (_: Throwable) {
            return if (runAttemptCount < 5) Result.retry() else { schedule(applicationContext); Result.success() }
        }
        notify(applicationContext, EnergyFormat.summary(result))
        schedule(applicationContext)
        return Result.success()
    }

    companion object {
        const val PREF_ENABLED = "energy_weekly_report_enabled"
        private const val UNIQUE = "energy_weekly_report"
        private const val CHANNEL_ID = "health_connector_energy"
        private const val NOTIFICATION_ID = 18400

        fun isEnabled(context: Context) =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(PREF_ENABLED, true)

        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context.applicationContext)
            if (!isEnabled(context)) { manager.cancelUniqueWork(UNIQUE); return }
            val now = ZonedDateTime.now()
            val delay = Duration.between(now, EnergyFormat.nextWeeklyReport(now)).toMillis()
            manager.enqueueUniqueWork(
                UNIQUE, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<EnergyReportWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            )
        }

        private fun notify(context: Context, lines: List<String>) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Энергобаланс", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Еженедельный расчёт расхода и нормы калорий"
                    }
                )
            }
            val open = PendingIntent.getActivity(
                context, NOTIFICATION_ID,
                Intent(context, PermissionGateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            runCatching {
                NotificationManagerCompat.from(context).notify(
                    NOTIFICATION_ID,
                    NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_app_logo)
                        .setContentTitle("Энергобаланс за неделю")
                        .setContentText(lines.firstOrNull().orEmpty())
                        .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build()
                )
            }
        }
    }
}
