package ru.doronin.healthconnector

import android.content.Context
import android.os.SystemClock
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException

class ManualSyncWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val prefs=applicationContext.getSharedPreferences("settings",Context.MODE_PRIVATE)
        val endpoint=prefs.getString("endpoint","").orEmpty().trim()
        val token=SecureTokenStore(applicationContext).getToken().trim()
        val days=prefs.getInt("days",7).coerceIn(1,30)
        if (endpoint.isBlank() || token.isBlank()) return failure("Не настроены URL Apps Script или токен")
        val safeEndpoint=runCatching { EndpointSecurity.requireHttps(endpoint) }.getOrElse { return failure(it.message ?: "Некорректный URL Apps Script") }
        if (HealthConnectClient.getSdkStatus(applicationContext)!=HealthConnectClient.SDK_AVAILABLE) return failure("Health Connect недоступен")
        val client=HealthConnectClient.getOrCreate(applicationContext)

        val missingReadPermissions = runCatching { HealthConnectPermissionSet.missingReadPermissions(client) }
            .getOrElse { return failure("Не удалось проверить разрешения Health Connect") }
        if (missingReadPermissions.isNotEmpty()) {
            return failure(
                "Синхронизация остановлена: не выданы все разрешения Health Connect " +
                    "(${missingReadPermissions.size}). Открой приложение и выдай разрешения, чтобы не потерять данные Dashboard."
            )
        }

        val bgFeature=client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)
        if (bgFeature != HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) {
            return failure(
                "Фоновое чтение Health Connect не поддерживается на этом устройстве. " +
                    "Запусти ручную синхронизацию из открытого приложения."
            )
        }
        val granted=runCatching { client.permissionController.getGrantedPermissions() }
            .getOrElse { return failure("Не удалось проверить разрешения Health Connect") }
        if (HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in granted) {
            return failure("Разреши Health Connect чтение данных в фоне")
        }
        setForeground(SyncForeground.info(applicationContext,SyncForeground.MANUAL_NOTIFICATION_ID,"HealthConnector · синхронизация","Запуск… приложение можно свернуть"))
        val runId=SyncDiagnostics.begin(applicationContext,"manual")
        val startedAt=SystemClock.elapsedRealtime()
        return try {
            val result=SyncRunGate.runManual(onWaiting={ running -> val m="Ожидаю завершения ${running ?: "другой"} синхронизации…"; publish(m); SyncDiagnostics.progress(applicationContext,runId,"manual",m) }) {
                HealthSyncStreamer(applicationContext,client).sync(endpoint=safeEndpoint,token=token,days=days,includeHistoricalChanges=true,onProgress={ message -> val elapsed=(SystemClock.elapsedRealtime()-startedAt)/1000L; publish("$message · ${elapsed} с"); SyncDiagnostics.progress(applicationContext,runId,"manual",message) })
            }
            if (result==null) { SyncDiagnostics.skipped(applicationContext,"manual","Повторный ручной запуск не создан"); Result.success() }
            else {
                val now=System.currentTimeMillis()
                prefs.edit().putLong("dashboard_last_sync",now).putInt("dashboard_last_days",result.days).putInt("dashboard_last_workouts",result.workouts).putInt("dashboard_last_measurements",result.measurements).putInt("dashboard_last_sources",result.sources).putBoolean("dashboard_sheets_ok",true).apply()
                SyncDiagnostics.finish(applicationContext,runId,"manual","Дней ${result.days}, тренировок ${result.workouts}, измерений ${result.measurements}")
                Result.success(workDataOf(KEY_DAYS to result.days,KEY_WORKOUTS to result.workouts,KEY_MEASUREMENTS to result.measurements,KEY_SOURCES to result.sources))
            }
        } catch (cancelled: CancellationException) { SyncDiagnostics.skipped(applicationContext,"manual","Foreground-задание отменено системой"); throw cancelled }
        catch (error: Throwable) { SyncDiagnostics.failure(applicationContext,runId,"manual",error); failure(error.message ?: error.javaClass.simpleName) }
    }
    private fun publish(message:String) { setProgressAsync(workDataOf(KEY_PROGRESS to message)); SyncForeground.update(applicationContext,SyncForeground.MANUAL_NOTIFICATION_ID,"HealthConnector · синхронизация",message) }
    private fun failure(message:String):Result=Result.failure(Data.Builder().putString(KEY_ERROR,message).build())
    companion object { const val KEY_PROGRESS="progress"; const val KEY_ERROR="error"; const val KEY_DAYS="days"; const val KEY_WORKOUTS="workouts"; const val KEY_MEASUREMENTS="measurements"; const val KEY_SOURCES="sources" }
}
