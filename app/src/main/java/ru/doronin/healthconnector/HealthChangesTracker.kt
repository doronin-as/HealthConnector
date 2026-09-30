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

    private val typesByKey: Map<String, KClass<out Record>> =
        recordTypes.associateBy { it.qualifiedName ?: it.toString() }

    suspend fun collect(zone: ZoneId): ChangeSet {
        val store = object : ChangeTokenStore {
            override fun get(typeKey: String): String? = prefs.getString(tokenKey(typeKey), null)
            override fun put(typeKey: String, token: String) {
                prefs.edit().putString(tokenKey(typeKey), token).apply()
            }
            override fun remove(typeKey: String) {
                prefs.edit().remove(tokenKey(typeKey)).apply()
            }
        }
        val source = object : ChangesSource {
            override suspend fun createToken(typeKey: String): String? =
                this@HealthChangesTracker.createToken(typesByKey.getValue(typeKey))

            override suspend fun readPage(typeKey: String, token: String): ChangesPage {
                val response = client.getChanges(token)
                if (response.changesTokenExpired) return ChangesPage(tokenExpired = true)
                val dates = linkedSetOf<LocalDate>()
                val deletedIds = linkedSetOf<String>()
                response.changes.forEach { change ->
                    when (change) {
                        is UpsertionChange -> dates += affectedDates(change.record, zone)
                        is DeletionChange -> deletedIds += change.recordId
                    }
                }
                return ChangesPage(
                    affectedDates = dates,
                    deletedRecordIds = deletedIds,
                    observedChanges = response.changes.size,
                    hasMore = response.hasMore,
                    nextToken = response.nextChangesToken
                )
            }
        }
        return ChangesCollector(store, source).collect(typesByKey.keys.toList())
    }

    /** First day still waiting for a re-read after a changes token expired, if any. */
    fun pendingReconciliationFrom(): LocalDate? =
        prefs.getString(RECONCILE_FROM_KEY, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun setPendingReconciliationFrom(date: LocalDate?) {
        prefs.edit().apply {
            if (date == null) remove(RECONCILE_FROM_KEY) else putString(RECONCILE_FROM_KEY, date.toString())
        }.apply()
    }

    private fun tokenKey(typeKey: String) = "token_$typeKey"

    @Suppress("UNCHECKED_CAST")
    private suspend fun createToken(type: KClass<out Record>): String? = try {
        client.getChangesToken(
            ChangesTokenRequest(recordTypes = setOf(type as KClass<Record>))
        )
    } catch (error: Throwable) {
        if (HealthConnectErrorUtils.isPermissionFailure(error)) null else throw error
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


    private companion object {
        const val RECONCILE_FROM_KEY = "reconcile_from"
    }
}
