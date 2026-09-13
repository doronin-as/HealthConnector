package ru.doronin.healthconnector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class MiScaleUploadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val measurementJson = inputData.getString(KEY_MEASUREMENT) ?: return@withContext Result.failure()
        val measurement = runCatching { JSONObject(measurementJson) }.getOrElse { return@withContext Result.failure() }
        val prefs = applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val endpoint = prefs.getString("endpoint", "").orEmpty().trim()
        val token = SecureTokenStore(applicationContext).getToken().trim()
        if (endpoint.isBlank() || token.isBlank()) return@withContext Result.retry()
        val safeEndpoint = runCatching { EndpointSecurity.requireHttps(endpoint) }.getOrElse { return@withContext Result.failure() }

        val payload = JSONObject().apply {
            put("action", "bodyCompositionV1")
            put("schemaVersion", 1)
            put("token", token)
            put("measurement", measurement)
        }

        val response = runCatching { post(safeEndpoint, payload) }.getOrElse { return@withContext Result.retry() }
        if (!response.first) return@withContext if (response.second) Result.retry() else Result.failure()

        val id = measurement.optString("id")
        prefs.edit()
            .putString(PREF_LAST_MEASUREMENT_ID, id)
            .putLong(PREF_LAST_MEASUREMENT_AT, measurement.optLong("measuredAtEpochMs", System.currentTimeMillis()))
            .putString(PREF_LAST_WEIGHT, measurement.optDouble("weightKg").toString())
            .apply()
        showSavedNotification(applicationContext, measurement)
        Result.success()
    }

    private fun post(endpoint: String, payload: JSONObject): Pair<Boolean, Boolean> {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 35_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload.toString()) }
            val code = connection.responseCode
            val text = runCatching {
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            if (code !in 200..299) return false to (code == 404 || code == 408 || code == 429 || code >= 500)
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (json != null && !json.optBoolean("ok", true)) {
                val busy = json.optString("errorCode") == "LOCK_BUSY"
                false to busy
            } else true to false
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val KEY_MEASUREMENT = "measurement"
        const val PREF_LAST_MEASUREMENT_ID = "scale_last_measurement_id"
        const val PREF_LAST_MEASUREMENT_AT = "scale_last_measurement_at"
        const val PREF_LAST_WEIGHT = "scale_last_weight"
        private const val CHANNEL_ID = "health_connector_scale"
        private const val NOTIFICATION_ID = 18118

        fun enqueue(context: Context, measurement: MiScaleMeasurement, metrics: MiScaleBodyMetricsResult?) {
            val json = JSONObject().apply {
                put("id", measurement.id)
                put("measuredAtEpochMs", measurement.measuredAtEpochMs)
                put("measuredAt", Instant.ofEpochMilli(measurement.measuredAtEpochMs).toString())
                put("date", Instant.ofEpochMilli(measurement.measuredAtEpochMs).atZone(ZoneId.systemDefault()).toLocalDate().toString())
                put("deviceAddress", measurement.deviceAddress)
                put("model", "XMTZC05HM")
                put("weightKg", measurement.weightKg)
                put("impedanceOhm", measurement.impedanceOhm ?: JSONObject.NULL)
                put("hasImpedance", measurement.hasImpedance)
                if (metrics != null) {
                    put("bmi", metrics.bmi)
                    put("bodyFatPercent", metrics.bodyFatPercent)
                    put("fatMassKg", metrics.fatMassKg)
                    put("waterPercent", metrics.waterPercent)
                    put("waterMassKg", metrics.waterMassKg)
                    put("muscleMassKg", metrics.muscleMassKg)
                    put("musclePercent", metrics.musclePercent)
                    put("leanBodyMassKg", metrics.leanBodyMassKg)
                    put("boneMassKg", metrics.boneMassKg)
                    put("visceralFat", metrics.visceralFat)
                    put("proteinPercent", metrics.proteinPercent)
                    put("basalMetabolicRateKcal", metrics.basalMetabolicRateKcal)
                }
            }
            val request = OneTimeWorkRequestBuilder<MiScaleUploadWorker>()
                .setInputData(workDataOf(KEY_MEASUREMENT to json.toString()))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "mi_scale_upload_${measurement.id.hashCode()}",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        private fun showSavedNotification(context: Context, measurement: JSONObject) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Умные весы", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Новые измерения Xiaomi Mi Body Composition Scale 2"
                    }
                )
            }
            val weight = measurement.optDouble("weightKg")
            val fat = measurement.optDouble("bodyFatPercent", Double.NaN)
            val water = measurement.optDouble("waterPercent", Double.NaN)
            val details = buildString {
                append(String.format(java.util.Locale.US, "%.2f кг", weight))
                if (!fat.isNaN()) append(String.format(java.util.Locale.US, " · жир %.1f%%", fat))
                if (!water.isNaN()) append(String.format(java.util.Locale.US, " · вода %.1f%%", water))
            }
            val pending = PendingIntent.getActivity(
                context,
                18118,
                Intent(context, StreamingMainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_app_logo)
                    .setContentTitle("⚖️ Измерение сохранено")
                    .setContentText(details)
                    .setStyle(NotificationCompat.BigTextStyle().bigText("$details\nСохранено в Dashboard"))
                    .setAutoCancel(true)
                    .setContentIntent(pending)
                    .build()
            )
        }
    }
}
