package ru.doronin.healthconnector.floors

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object PhoneFloorStore {
    private const val PREFS = "phone_floor_tracking"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DATE = "date"
    private const val KEY_FLOORS = "floors"
    private const val KEY_ELEVATION_METERS = "elevation_meters"
    private const val KEY_LAST_DETECTION_AT = "last_detection_at"
    private const val KEY_LAST_HEALTH_CONNECT_ERROR = "last_health_connect_error"
    private const val KEY_HISTORY = "history"
    private const val MAX_HISTORY_POINTS = 240

    data class HistoryPoint(
        val timestampEpochMs: Long,
        val floors: Int,
        val elevationMeters: Double
    )

    data class Snapshot(
        val enabled: Boolean,
        val date: LocalDate,
        val floors: Int,
        val elevationMeters: Double,
        val lastDetectionAtEpochMs: Long,
        val lastHealthConnectError: String?
    )

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun snapshot(context: Context): Snapshot {
        ensureToday(context)
        val p = prefs(context)
        return Snapshot(
            enabled = p.getBoolean(KEY_ENABLED, false),
            date = LocalDate.parse(p.getString(KEY_DATE, today().toString()) ?: today().toString()),
            floors = p.getInt(KEY_FLOORS, 0),
            elevationMeters = java.lang.Double.longBitsToDouble(
                p.getLong(KEY_ELEVATION_METERS, java.lang.Double.doubleToRawLongBits(0.0))
            ),
            lastDetectionAtEpochMs = p.getLong(KEY_LAST_DETECTION_AT, 0L),
            lastHealthConnectError = p.getString(KEY_LAST_HEALTH_CONNECT_ERROR, null)
        )
    }

    fun history(context: Context): List<HistoryPoint> {
        ensureToday(context)
        return decodeHistory(prefs(context).getString(KEY_HISTORY, null))
    }

    fun addDetection(context: Context, detection: FloorDetection): Snapshot {
        ensureToday(context)
        val p = prefs(context)
        val currentFloors = p.getInt(KEY_FLOORS, 0)
        val currentElevation = java.lang.Double.longBitsToDouble(
            p.getLong(KEY_ELEVATION_METERS, java.lang.Double.doubleToRawLongBits(0.0))
        )
        val newFloors = currentFloors + detection.floors
        val newElevation = currentElevation + detection.elevationMeters
        val newHistory = (decodeHistory(p.getString(KEY_HISTORY, null)) +
            HistoryPoint(
                timestampEpochMs = detection.endedAtEpochMs,
                floors = newFloors,
                elevationMeters = newElevation
            ))
            .takeLast(MAX_HISTORY_POINTS)

        p.edit()
            .putInt(KEY_FLOORS, newFloors)
            .putLong(KEY_ELEVATION_METERS, java.lang.Double.doubleToRawLongBits(newElevation))
            .putLong(KEY_LAST_DETECTION_AT, detection.endedAtEpochMs)
            .putString(KEY_HISTORY, encodeHistory(newHistory))
            .apply()
        return snapshot(context)
    }

    fun setHealthConnectError(context: Context, message: String?) {
        prefs(context).edit().apply {
            if (message.isNullOrBlank()) remove(KEY_LAST_HEALTH_CONNECT_ERROR)
            else putString(KEY_LAST_HEALTH_CONNECT_ERROR, message.take(240))
        }.apply()
    }

    fun resetToday(context: Context) {
        prefs(context).edit()
            .putString(KEY_DATE, today().toString())
            .putInt(KEY_FLOORS, 0)
            .putLong(KEY_ELEVATION_METERS, java.lang.Double.doubleToRawLongBits(0.0))
            .putLong(KEY_LAST_DETECTION_AT, 0L)
            .remove(KEY_HISTORY)
            .remove(KEY_LAST_HEALTH_CONNECT_ERROR)
            .apply()
    }

    fun formatLastDetection(snapshot: Snapshot): String? {
        if (snapshot.lastDetectionAtEpochMs <= 0L) return null
        return Instant.ofEpochMilli(snapshot.lastDetectionAtEpochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
            .withNano(0)
            .toString()
    }

    private fun ensureToday(context: Context) {
        val p = prefs(context)
        val current = p.getString(KEY_DATE, null)
        val today = today().toString()
        if (current != today) {
            p.edit()
                .putString(KEY_DATE, today)
                .putInt(KEY_FLOORS, 0)
                .putLong(KEY_ELEVATION_METERS, java.lang.Double.doubleToRawLongBits(0.0))
                .putLong(KEY_LAST_DETECTION_AT, 0L)
                .remove(KEY_HISTORY)
                .remove(KEY_LAST_HEALTH_CONNECT_ERROR)
                .apply()
        }
    }

    private fun encodeHistory(points: List<HistoryPoint>): String =
        points.joinToString(";") { point ->
            "${point.timestampEpochMs},${point.floors},${point.elevationMeters}"
        }

    private fun decodeHistory(raw: String?): List<HistoryPoint> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';').mapNotNull { entry ->
            val parts = entry.split(',')
            if (parts.size != 3) return@mapNotNull null
            val timestamp = parts[0].toLongOrNull() ?: return@mapNotNull null
            val floors = parts[1].toIntOrNull() ?: return@mapNotNull null
            val elevation = parts[2].toDoubleOrNull() ?: return@mapNotNull null
            if (timestamp <= 0L || floors < 0 || !elevation.isFinite() || elevation < 0.0) return@mapNotNull null
            HistoryPoint(timestamp, floors, elevation)
        }.sortedBy { it.timestampEpochMs }.takeLast(MAX_HISTORY_POINTS)
    }

    private fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
