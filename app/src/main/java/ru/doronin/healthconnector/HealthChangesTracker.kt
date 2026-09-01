package ru.doronin.healthconnector

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * Tracks Health Connect change tokens per record type, as recommended by Health Connect docs.
 * Tokens are deliberately separate so revoking one optional permission cannot invalidate all data.
 */
class HealthChangesTracker(
    context: Context,
    private val client: HealthConnectClient
) {
    private val prefs = context.getSharedPreferences("health_changes_v1", Context.MODE_PRIVATE)

    private val recordTypes: List<KClass<out Record>> = listOf(
        StepsRecord::class,
        DistanceRecord::class,
        ActiveCaloriesBurnedRecord::class,
        TotalCaloriesBurnedRecord::class,
        SleepSessionRecord::class,
        HeartRateRecord::class,
        RestingHeartRateRecord::class,
        HeartRateVariabilityRmssdRecord::class,
        OxygenSaturationRecord::class,
        RespiratoryRateRecord::class,
        Vo2MaxRecord::class,
        SkinTemperatureRecord::class,
        ElevationGainedRecord::class,
        FloorsClimbedRecord::class,
        SpeedRecord::class,
        StepsCadenceRecord::class,
        CyclingPedalingCadenceRecord::class,
        PowerRecord::class,
        ExerciseSessionRecord::class,
        WeightRecord::class
    )

    suspend fun collect(zone: ZoneId): ChangeSet {
        val dates = linkedSetOf<LocalDate>()
        val deletedIds = linkedSetOf<String>()
        var observedChanges = 0

        for (type in recordTypes) {
            val key = "token_${type.qualifiedName}"
            var token = prefs.getString(key, null)
            if (token.isNullOrBlank()) {
                token = createToken(type) ?: continue
                prefs.edit().putString(key, token).apply()
                continue
            }

            try {
                var nextToken = token
                var keepReading: Boolean
                do {
                    val response = client.getChanges(nextToken)
                    if (response.changesTokenExpired) {
                        createToken(type)?.let { prefs.edit().putString(key, it).apply() }
                        break
                    }
                    response.changes.forEach { change ->
                        observedChanges++
                        when (change) {
                            is UpsertionChange -> dates += affectedDates(change.record, zone)
                            is DeletionChange -> deletedIds += change.recordId
                        }
                    }
                    nextToken = response.nextChangesToken
                    keepReading = response.hasMore
                    if (!keepReading) prefs.edit().putString(key, nextToken).apply()
                } while (keepReading)
            } catch (error: Throwable) {
                if (isPermissionFailure(error)) {
                    // Keep other record-type tokens working. If permission is granted later,
                    // recreate this token and the normal lookback sync will fill recent history.
                    prefs.edit().remove(key).apply()
                    continue
                }
                throw IllegalStateException(
                    "Health Connect changes failed for ${type.simpleName}: ${error.message}",
                    error
                )
            }
        }

        return ChangeSet(dates, deletedIds, observedChanges)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun createToken(type: KClass<out Record>): String? = try {
        client.getChangesToken(
            ChangesTokenRequest(recordTypes = setOf(type as KClass<Record>))
        )
    } catch (error: Throwable) {
        if (isPermissionFailure(error)) null else throw error
    }

    private fun affectedDates(record: Record, zone: ZoneId): Set<LocalDate> = when (record) {
        is SleepSessionRecord -> setOf(record.endTime.atZone(zone).toLocalDate())
        is StepsRecord -> intervalDates(record.startTime, record.endTime, zone)
        is DistanceRecord -> intervalDates(record.startTime, record.endTime, zone)
        is ActiveCaloriesBurnedRecord -> intervalDates(record.startTime, record.endTime, zone)
        is TotalCaloriesBurnedRecord -> intervalDates(record.startTime, record.endTime, zone)
        is HeartRateRecord -> intervalDates(record.startTime, record.endTime, zone)
        is SkinTemperatureRecord -> intervalDates(record.startTime, record.endTime, zone)
        is ElevationGainedRecord -> intervalDates(record.startTime, record.endTime, zone)
        is FloorsClimbedRecord -> intervalDates(record.startTime, record.endTime, zone)
        is SpeedRecord -> intervalDates(record.startTime, record.endTime, zone)
        is StepsCadenceRecord -> intervalDates(record.startTime, record.endTime, zone)
        is CyclingPedalingCadenceRecord -> intervalDates(record.startTime, record.endTime, zone)
        is PowerRecord -> intervalDates(record.startTime, record.endTime, zone)
        is ExerciseSessionRecord -> intervalDates(record.startTime, record.endTime, zone)
        is RestingHeartRateRecord -> setOf(record.time.atZone(zone).toLocalDate())
        is HeartRateVariabilityRmssdRecord -> setOf(record.time.atZone(zone).toLocalDate())
        is OxygenSaturationRecord -> setOf(record.time.atZone(zone).toLocalDate())
        is RespiratoryRateRecord -> setOf(record.time.atZone(zone).toLocalDate())
        is Vo2MaxRecord -> setOf(record.time.atZone(zone).toLocalDate())
        is WeightRecord -> setOf(record.time.atZone(zone).toLocalDate())
        else -> emptySet()
    }

    private fun intervalDates(start: Instant, end: Instant, zone: ZoneId): Set<LocalDate> {
        val first = start.atZone(zone).toLocalDate()
        val last = end.minusNanos(1).atZone(zone).toLocalDate()
        val result = linkedSetOf<LocalDate>()
        var date = first
        while (!date.isAfter(last) && result.size < 32) {
            result += date
            date = date.plusDays(1)
        }
        return result
    }

    private fun isPermissionFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        repeat(8) {
            val value = current ?: return false
            if (value is SecurityException) return true
            val message = value.message.orEmpty()
            if (
                message.contains("SecurityException", ignoreCase = true) ||
                message.contains("does not have permission", ignoreCase = true) ||
                message.contains("permission to read data", ignoreCase = true) ||
                message.contains("permission denied", ignoreCase = true)
            ) return true
            current = value.cause
        }
        return false
    }

    data class ChangeSet(
        val affectedDates: Set<LocalDate>,
        val deletedRecordIds: Set<String>,
        val observedChanges: Int
    )
}
