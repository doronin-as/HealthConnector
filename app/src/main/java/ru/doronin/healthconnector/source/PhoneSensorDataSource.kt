package ru.doronin.healthconnector.source

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import ru.doronin.healthconnector.floors.PhoneFloorStore
import ru.doronin.healthconnector.floors.PhoneFloorTrackingService

object PhoneSensorDataSource : HealthDataSource {
    override val id: String = "phone_sensors"
    override val displayName: String = "Датчики телефона"
    override val supportedMetrics: Set<HealthMetric> = setOf(
        HealthMetric.FLOORS_CLIMBED,
        HealthMetric.ELEVATION_GAINED
    )

    override fun status(context: Context): HealthDataSourceStatus {
        val sensorManager = context.getSystemService(SensorManager::class.java)
        val pressureAvailable = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) != null
        val stepDetectorAvailable = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null
        val activityPermissionGranted = activityPermissionGranted(context)
        val missing = buildList {
            if (!pressureAvailable) add("Барометр (TYPE_PRESSURE)")
            if (!stepDetectorAvailable) add("Датчик шагов (TYPE_STEP_DETECTOR)")
            if (!activityPermissionGranted) add("Разрешение «Физическая активность»")
        }
        val enabled = PhoneFloorStore.isEnabled(context)
        val summary = when {
            missing.isNotEmpty() -> "Недоступно: ${missing.joinToString()}"
            enabled -> "Подсчёт этажей включён"
            else -> "Готов к подсчёту этажей"
        }
        return HealthDataSourceStatus(
            available = missing.isEmpty(),
            enabled = enabled,
            summary = summary,
            missingRequirements = missing
        )
    }

    override fun start(context: Context) {
        if (!status(context).available || !PhoneFloorStore.isEnabled(context)) return
        ContextCompat.startForegroundService(
            context,
            Intent(context, PhoneFloorTrackingService::class.java)
        )
    }

    override fun stop(context: Context) {
        context.stopService(Intent(context, PhoneFloorTrackingService::class.java))
    }

    fun hasPressureSensor(context: Context): Boolean =
        context.getSystemService(SensorManager::class.java)
            .getDefaultSensor(Sensor.TYPE_PRESSURE) != null

    fun hasStepDetector(context: Context): Boolean =
        context.getSystemService(SensorManager::class.java)
            .getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null

    fun activityPermissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
}
