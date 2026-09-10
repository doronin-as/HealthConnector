package ru.doronin.healthconnector

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object BackgroundSyncScheduler {
    const val PREF_ENABLED = "background_sync_enabled"
    const val PREF_LAST_RUN = "background_last_run"
    const val PREF_LAST_STATUS = "background_last_status"
    const val INTERVAL_HOURS = 6L
    const val BACKGROUND_DAYS = 2

    private const val UNIQUE_WORK_NAME = "health_connector_background_sync"

    fun apply(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(PREF_ENABLED, true)
        val manager = WorkManager.getInstance(appContext)

        if (!enabled) {
            manager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(
            INTERVAL_HOURS,
            TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.MINUTES
            )
            .build()

        // KEEP is intentional: apply() is called when the app opens and when settings
        // are saved. UPDATE can replace/cancel an already running generation, which
        // produced overlapping background runs and JobCancellationException in logs.
        manager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
