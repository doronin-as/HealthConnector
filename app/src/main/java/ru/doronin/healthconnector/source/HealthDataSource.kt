package ru.doronin.healthconnector.source

import android.content.Context

enum class HealthMetric {
    FLOORS_CLIMBED,
    ELEVATION_GAINED,
    STEPS,
    HEART_RATE,
    SLEEP,
    OXYGEN_SATURATION,
    WEIGHT,
    BODY_COMPOSITION
}

data class HealthDataSourceStatus(
    val available: Boolean,
    val enabled: Boolean,
    val summary: String,
    val missingRequirements: List<String> = emptyList()
)

/**
 * Common contract for local sensors, wearables and external health providers.
 *
 * A source owns data acquisition only. Storage/sync stays outside the source so
 * additional providers (Mi Fitness, Fitbit, Garmin, Samsung Health, etc.) can be
 * added without coupling them to the UI or to Apps Script.
 */
interface HealthDataSource {
    val id: String
    val displayName: String
    val supportedMetrics: Set<HealthMetric>

    fun status(context: Context): HealthDataSourceStatus
    fun start(context: Context)
    fun stop(context: Context)
}
