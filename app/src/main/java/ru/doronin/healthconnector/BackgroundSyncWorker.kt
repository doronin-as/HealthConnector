package ru.doronin.healthconnector

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class BackgroundSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(BackgroundSyncScheduler.PREF_ENABLED, true)) return Result.success()

        val endpoint = prefs.getString("endpoint", "").orEmpty().trim()
        val token = SecureTokenStore(applicationContext).getToken().trim()
        if (endpoint.isBlank() || token.isBlank()) {
            saveBackgroundStatus("Фоновая синхронизация: не настроен Google Sheets")
            return Result.success()
        }

        val safeEndpoint = runCatching { EndpointSecurity.requireHttps(endpoint) }
            .getOrElse {
                saveBackgroundStatus("Фоновая синхронизация: нужен HTTPS URL Apps Script")
                return Result.success()
            }

        if (HealthConnectClient.getSdkStatus(applicationContext) != HealthConnectClient.SDK_AVAILABLE) {
            saveBackgroundStatus("Фоновая синхронизация: Health Connect недоступен")
            return Result.success()
        }

        val client = HealthConnectClient.getOrCreate(applicationContext)
        val backgroundFeature = client.features.getFeatureStatus(
            HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
        )
        if (backgroundFeature != HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) {
            saveBackgroundStatus("Фоновое чтение Health Connect не поддерживается на этом устройстве")
            return Result.success()
        }

        val granted = runCatching { client.permissionController.getGrantedPermissions() }
            .getOrElse {
                saveBackgroundStatus("Не удалось проверить разрешения Health Connect")
                return Result.retry()
            }
        if (HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in granted) {
            saveBackgroundStatus("Нужно разрешить Health Connect читать данные в фоне")
            return Result.success()
        }

        return try {
            val result = HealthSyncStreamer(applicationContext, client).sync(
                endpoint = safeEndpoint,
                token = token,
                days = BackgroundSyncScheduler.BACKGROUND_DAYS,
                onProgress = { }
            )
            val now = System.currentTimeMillis()
            prefs.edit()
                .putLong(BackgroundSyncScheduler.PREF_LAST_RUN, now)
                .putString(
                    BackgroundSyncScheduler.PREF_LAST_STATUS,
                    "Успешно: ${result.days} дн., ${result.workouts} трен., ${result.measurements} изм."
                )
                .putLong("dashboard_last_sync", now)
                .putInt("dashboard_last_days", result.days)
                .putInt("dashboard_last_workouts", result.workouts)
                .putInt("dashboard_last_measurements", result.measurements)
                .putInt("dashboard_last_sources", result.sources)
                .putBoolean("dashboard_sheets_ok", true)
                .apply()
            Result.success()
        } catch (error: Throwable) {
            saveBackgroundStatus("Ошибка: ${error.message ?: error.javaClass.simpleName}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun saveBackgroundStatus(status: String) {
        applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putLong(BackgroundSyncScheduler.PREF_LAST_RUN, System.currentTimeMillis())
            .putString(BackgroundSyncScheduler.PREF_LAST_STATUS, status)
            .apply()
    }
}
