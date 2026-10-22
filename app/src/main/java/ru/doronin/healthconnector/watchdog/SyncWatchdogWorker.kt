package ru.doronin.healthconnector.watchdog

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
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import ru.doronin.healthconnector.PermissionGateActivity
import ru.doronin.healthconnector.R
import ru.doronin.healthconnector.local.LocalStore
import ru.doronin.healthconnector.local.OutboxWorker
import ru.doronin.healthconnector.local.ServerApi
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Every 6 hours: checks that bracelet days keep arriving in HC_Дни and that locally
 * entered data is being delivered. Notifies at most once a day per problem.
 */
class SyncWatchdogWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val now = System.currentTimeMillis()
        val alerts = mutableListOf<WatchdogPolicy.Alert>()

        val stats = LocalStore.get(context).outboxStats()
        if (stats.count > 0) OutboxWorker.schedule(context)
        WatchdogPolicy.outboxStuck(now, stats.count, stats.oldestCreatedMs, stats.lastError)?.let(alerts::add)

        try {
            val plan = ServerApi.post(
                context,
                "healthSyncPlanV3",
                JSONObject().put("schemaVersion", 3).put("fallbackDays", 2)
            )
            val latest = runCatching { LocalDate.parse(plan.optString("latestDate")) }.getOrNull()
            val today = LocalDate.now(ZoneId.systemDefault())
            WatchdogPolicy.staleDays(today, latest)?.let(alerts::add)
            val incomplete = plan.optJSONArray("incompleteDays")?.let { array ->
                (0 until array.length()).mapNotNull { i ->
                    val item = array.optJSONObject(i) ?: return@mapNotNull null
                    val date = runCatching { LocalDate.parse(item.optString("date")) }.getOrNull() ?: return@mapNotNull null
                    val missing = item.optJSONArray("missing")?.let { m -> (0 until m.length()).map { m.optString(it) } }.orEmpty()
                        .ifEmpty { if (item.optBoolean("notComplete")) listOf("день не завершён") else emptyList() }
                    Triple(date, missing, item.optInt("checks"))
                }
            }.orEmpty()
            WatchdogPolicy.incompleteDays(today, incomplete)?.let(alerts::add)
            clear(context, "server_unreachable")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ServerApi.NotConfigured) {
            return Result.success()
        } catch (error: Throwable) {
            alerts += WatchdogPolicy.Alert.ServerUnreachable(error.message ?: error.javaClass.simpleName)
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val active = alerts.map { it.key }.toSet()
        // Problems that went away may alert again immediately if they come back later.
        listOf("stale_days", "outbox_stuck", "server_unreachable", "incomplete_days").filter { it !in active }.forEach { clear(context, it) }
        for (alert in alerts) {
            val key = "watchdog_last_${alert.key}"
            val last = prefs.getLong(key, 0L).takeIf { it > 0 }
            if (WatchdogPolicy.shouldNotify(now, last)) {
                notify(context, alert)
                prefs.edit().putLong(key, now).apply()
            }
        }
        prefs.edit()
            .putLong(PREF_LAST_CHECK, now)
            .putString(PREF_LAST_RESULT, if (alerts.isEmpty()) "OK" else alerts.joinToString { WatchdogPolicy.message(it).first })
            .apply()
        return Result.success()
    }

    private fun clear(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("watchdog_last_$key").apply()
    }

    companion object {
        const val PREFS = "watchdog"
        const val PREF_LAST_CHECK = "last_check"
        const val PREF_LAST_RESULT = "last_result"
        private const val CHANNEL_ID = "health_connector_watchdog"
        private const val UNIQUE = "sync_watchdog"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWatchdogWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        private fun notify(context: Context, alert: WatchdogPolicy.Alert) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Сторож синхронизации", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Предупреждения, если данные перестали доходить до Google Sheets"
                    }
                )
            }
            val (title, text) = WatchdogPolicy.message(alert)
            val open = PendingIntent.getActivity(
                context, 18300,
                Intent(context, PermissionGateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            runCatching {
                NotificationManagerCompat.from(context).notify(
                    18300 + Math.floorMod(alert.key.hashCode(), 50),
                    NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_app_logo)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build()
                )
            }
        }
    }
}
