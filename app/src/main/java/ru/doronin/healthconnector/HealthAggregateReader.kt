package ru.doronin.healthconnector

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * Reads cumulative daily values through Health Connect's Aggregate API.
 *
 * Raw records are still exported for diagnostics/archive, but they must never be summed to produce
 * dashboard totals: multiple data origins may overlap. Aggregate API applies Health Connect's own
 * deduplication and user data-origin priorities.
 */
class HealthAggregateReader(
    private val client: HealthConnectClient
) {
    suspend fun readDay(start: Instant, end: Instant): DayAggregates = DayAggregates(
        steps = read(StepsRecord.COUNT_TOTAL, start, end),
        distance = read(DistanceRecord.DISTANCE_TOTAL, start, end),
        activeCalories = read(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, start, end),
        totalCalories = read(TotalCaloriesBurnedRecord.ENERGY_TOTAL, start, end),
        elevation = read(ElevationGainedRecord.ELEVATION_GAINED_TOTAL, start, end),
        floors = read(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL, start, end)
    )

    private suspend fun <T : Any> read(
        metric: AggregateMetric<T>,
        start: Instant,
        end: Instant
    ): MetricRead<T> {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                val result = client.aggregate(
                    AggregateRequest(
                        metrics = setOf(metric),
                        timeRangeFilter = TimeRangeFilter.between(start, end)
                    )
                )
                return MetricRead.Available(
                    value = result[metric],
                    sourcePackages = result.dataOrigins.map { it.packageName }.toSet()
                )
            } catch (error: Throwable) {
                if (HealthConnectErrorUtils.isPermissionFailure(error)) {
                    return MetricRead.PermissionDenied(error.message.orEmpty())
                }
                lastError = error
                if (attempt < 2) delay(300L * (attempt + 1L))
            }
        }
        throw IllegalStateException(
            "Health Connect aggregate failed: ${lastError?.message ?: "unknown error"}",
            lastError
        )
    }


    data class DayAggregates(
        val steps: MetricRead<Long>,
        val distance: MetricRead<Length>,
        val activeCalories: MetricRead<Energy>,
        val totalCalories: MetricRead<Energy>,
        val elevation: MetricRead<Length>,
        val floors: MetricRead<Double>
    )
}

sealed interface MetricRead<out T> {
    data class Available<T>(
        val value: T?,
        val sourcePackages: Set<String> = emptySet()
    ) : MetricRead<T>

    data class PermissionDenied(val reason: String) : MetricRead<Nothing>
}
