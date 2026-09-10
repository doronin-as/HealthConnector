package ru.doronin.healthconnector

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

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
            val syncResult = SyncRunGate.tryRunBackground {
                val runId = SyncDiagnostics.begin(applicationContext, "background")
                try {
                    val result = HealthSyncStreamer(applicationContext, client).sync(
                        endpoint = safeEndpoint,
                        token = token,
                        days = BackgroundSyncScheduler.BACKGROUND_DAYS,
                        includeHistoricalChanges = false,
                        onProgress = { message ->
                            SyncDiagnostics.progress(applicationContext, runId, "background", message)
                        }
                    )
                    SyncDiagnostics.finish(
                        applicationContext,
                        runId,
                        "background",
                        "Успешно: ${result.days} дн., ${result.workouts} трен., ${result.measurements} изм."
                    )
                    result
                } catch (cancelled: CancellationException) {
                    // WorkManager can cancel a worker when constraints/state change.
                    // Cancellation is lifecycle control, not an application failure.
                    SyncDiagnostics.skipped(
                        applicationContext,
                        "background",
                        "Фоновый запуск отменён системой; следующий запуск выполнится по расписанию"
                    )
                    throw cancelled
                } catch (error: Throwable) {
                    SyncDiagnostics.failure(applicationContext, runId, "background", error)
                    throw error
                }
            }

            if (syncResult == null) {
                val running = SyncRunGate.currentOrigin ?: "другой запуск"
                val message = "Пропущено: уже выполняется $running"
                SyncDiagnostics.skipped(applicationContext, "background", message)
                saveBackgroundStatus(message)
                Result.success()
            } else {
                val now = System.currentTimeMillis()
                prefs.edit()
                    .putLong(BackgroundSyncScheduler.PREF_LAST_RUN, now)
                    .putString(
                        BackgroundSyncScheduler.PREF_LAST_STATUS,
                        "Успешно: ${syncResult.days} дн., ${syncResult.workouts} трен., ${syncResult.measurements} изм."
                    )
                    .putLong("dashboard_last_sync", now)
                    .putInt("dashboard_last_days", syncResult.days)
                    .putInt("dashboard_last_workouts", syncResult.workouts)
                    .putInt("dashboard_last_measurements", syncResult.measurements)
                    .putInt("dashboard_last_sources", syncResult.sources)
                    .putBoolean("dashboard_sheets_ok", true)
                    .apply()
                Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
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
