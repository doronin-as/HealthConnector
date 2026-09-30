package ru.doronin.healthconnector

import java.time.LocalDate

/**
 * Result of one Health Connect aggregate read. `Available(null)` means Health Connect had no
 * records for the range; it is not the same as a real zero and must never be reported as one.
 */
sealed interface MetricRead<out T> {
    data class Available<T>(
        val value: T?,
        val sourcePackages: Set<String> = emptySet()
    ) : MetricRead<T>

    data class PermissionDenied(val reason: String) : MetricRead<Nothing>
}

/** What one read of one record type for one day actually proved. */
enum class TypeReadState {
    /** Records were returned (their values may legitimately sum to zero). */
    HAS_DATA,

    /** The type was readable but Health Connect returned no records. Not a zero. */
    EMPTY,

    /** The type could not be read at all. */
    PERMISSION_DENIED;

    companion object {
        fun of(permissionDenied: Boolean, recordCount: Int): TypeReadState = when {
            permissionDenied -> PERMISSION_DENIED
            recordCount > 0 -> HAS_DATA
            else -> EMPTY
        }

        /** Several reads of the same type in one day: denial wins, then any data. */
        fun merge(previous: TypeReadState?, next: TypeReadState): TypeReadState = when {
            previous == PERMISSION_DENIED || next == PERMISSION_DENIED -> PERMISSION_DENIED
            previous == HAS_DATA || next == HAS_DATA -> HAS_DATA
            else -> EMPTY
        }
    }
}

fun MetricRead<*>.readState(): TypeReadState = when (this) {
    is MetricRead.PermissionDenied -> TypeReadState.PERMISSION_DENIED
    is MetricRead.Available -> if (value == null) TypeReadState.EMPTY else TypeReadState.HAS_DATA
}

/** The value to report for a day field: only a real read with records, never an inferred zero. */
fun <T> MetricRead<T>.reportableValue(): T? = (this as? MetricRead.Available<T>)?.value

/** Sums records, or returns null when there were none to sum ("no records" is not "0"). */
inline fun <R> sumOrNull(records: List<R>, selector: (R) -> Double): Double? =
    if (records.isEmpty()) null else records.sumOf(selector)

/** Per-day synchronization state sent to Google Sheets alongside the legacy boolean. */
enum class DaySyncState {
    /** Every tracked type was read and nothing is expected to arrive later. */
    COMPLETE,

    /** The sync for this day stopped part-way (early checkpoint, interrupted upload). */
    PARTIAL,

    /** The day is not over yet, or a recent day's sleep has not arrived from the source app. */
    WAITING_FOR_SOURCE,

    /** At least one tracked record type could not be read because permission is missing. */
    PERMISSION_BLOCKED;

    val isComplete: Boolean get() = this == COMPLETE
}

data class DaySyncVerdict(
    val state: DaySyncState,
    val deniedTypes: Set<String>,
    val emptyTypes: Set<String>
)

object DaySyncStateEvaluator {
    fun evaluate(
        date: LocalDate,
        today: LocalDate,
        typeStates: Map<String, TypeReadState>,
        sleepNeedsRepair: Boolean,
        interrupted: Boolean = false
    ): DaySyncVerdict {
        val denied = typeStates.filterValues { it == TypeReadState.PERMISSION_DENIED }.keys.toSortedSet()
        val empty = typeStates.filterValues { it == TypeReadState.EMPTY }.keys.toSortedSet()
        val state = when {
            interrupted -> DaySyncState.PARTIAL
            denied.isNotEmpty() -> DaySyncState.PERMISSION_BLOCKED
            !date.isBefore(today) -> DaySyncState.WAITING_FOR_SOURCE
            sleepNeedsRepair -> DaySyncState.WAITING_FOR_SOURCE
            else -> DaySyncState.COMPLETE
        }
        return DaySyncVerdict(state, denied, empty)
    }
}

/**
 * When a Health Connect changes token expires, the changes it covered are gone. Instead of
 * silently starting a new token, every readable day is queued for a re-read. The queue is a
 * single "reconcile from" date that advances as days are re-read, so an interrupted run resumes.
 */
object ReconciliationWindow {
    /** Health Connect keeps roughly 30 days readable for apps without history permission. */
    const val READABLE_DAYS = 30L

    fun oldestReadable(today: LocalDate): LocalDate = today.minusDays(READABLE_DAYS - 1)

    fun afterTokenExpiry(today: LocalDate, pendingFrom: LocalDate?): LocalDate {
        val start = oldestReadable(today)
        return if (pendingFrom != null && pendingFrom.isBefore(start)) pendingFrom else start
    }

    fun pendingDates(pendingFrom: LocalDate?, today: LocalDate): List<LocalDate> {
        if (pendingFrom == null) return emptyList()
        var cursor = maxOf(pendingFrom, oldestReadable(today))
        val result = ArrayList<LocalDate>()
        while (!cursor.isAfter(today)) {
            result += cursor
            cursor = cursor.plusDays(1)
        }
        return result
    }

    /** Returns the new marker after [processed] was re-read, or null once nothing is left. */
    fun advance(pendingFrom: LocalDate?, processed: LocalDate, today: LocalDate): LocalDate? {
        if (pendingFrom == null) return null
        val effective = maxOf(pendingFrom, oldestReadable(today))
        if (processed != effective) return pendingFrom
        val next = processed.plusDays(1)
        return if (next.isAfter(today)) null else next
    }
}

/**
 * A day re-read because Health Connect reported deletions for it, or because the change log was
 * lost, is authoritative: once it is COMPLETE, what was read is the whole truth for that day.
 * Only then may the server clear stored values, accept a real zero over an older positive value,
 * and drop raw rows the re-read no longer contains. Ordinary syncs stay additive so a transient
 * empty read never erases good history.
 */
object AuthoritativeDayRules {
    /** Day-summary fields produced from each record type (keys match Apps Script DAY_FIELD_TO_HEADER_V3). */
    val DAY_FIELDS_BY_TYPE: Map<String, List<String>> = mapOf(
        "StepsRecord" to listOf("steps"),
        "DistanceRecord" to listOf("distanceKm"),
        "ActiveCaloriesBurnedRecord" to listOf("activeCaloriesKcal"),
        "TotalCaloriesBurnedRecord" to listOf("totalCaloriesKcal"),
        "ElevationGainedRecord" to listOf("elevationGainedM"),
        "FloorsClimbedRecord" to listOf("floorsClimbed"),
        "SleepSessionRecord" to listOf(
            "sleepHours", "deepSleepMinutes", "lightSleepMinutes", "remSleepMinutes", "awakeMinutes",
            "sleepStart", "sleepEnd", "sleepSessionCount", "sleepStageCount", "mainSleepHours",
            "napCount", "napMinutes"
        ),
        "HeartRateRecord" to listOf("averageHeartRate", "minimumHeartRate", "maximumHeartRate", "heartRateSamples"),
        "RestingHeartRateRecord" to listOf("restingHeartRate"),
        "OxygenSaturationRecord" to listOf("averageSpO2", "minimumSpO2", "maximumSpO2", "spO2Samples"),
        "HeartRateVariabilityRmssdRecord" to listOf(
            "averageHrvRmssdMs", "minimumHrvRmssdMs", "maximumHrvRmssdMs", "hrvSamples"
        ),
        "RespiratoryRateRecord" to listOf(
            "averageRespiratoryRate", "minimumRespiratoryRate", "maximumRespiratoryRate", "respiratorySamples"
        ),
        "Vo2MaxRecord" to listOf("vo2Max"),
        "SkinTemperatureRecord" to listOf(
            "skinTempBaselineC", "averageSkinTempDeltaC", "minimumSkinTempDeltaC",
            "maximumSkinTempDeltaC", "skinTempSamples"
        ),
        "SpeedRecord" to listOf("averageSpeedKmh", "maximumSpeedKmh"),
        "StepsCadenceRecord" to listOf("averageStepCadence", "maximumStepCadence"),
        "CyclingPedalingCadenceRecord" to listOf("averageCyclingCadence", "maximumCyclingCadence"),
        "PowerRecord" to listOf("averagePowerW", "maximumPowerW"),
        "WeightRecord" to listOf("weightKg"),
        "ExerciseSessionRecord" to listOf("workoutCount", "workoutMinutes")
    )

    fun isAuthoritative(state: DaySyncState, reReadForReconciliation: Boolean): Boolean =
        reReadForReconciliation && state == DaySyncState.COMPLETE

    /**
     * Fields the server should overwrite even with empty or zero: fields of readable types that
     * the re-read did not produce (cleared), plus produced fields whose value is a real zero.
     */
    fun clearFields(
        state: DaySyncState,
        reReadForReconciliation: Boolean,
        typeStates: Map<String, TypeReadState>,
        presentFields: Set<String>,
        zeroValuedFields: Set<String>
    ): Set<String> {
        if (!isAuthoritative(state, reReadForReconciliation)) return emptySet()
        val result = sortedSetOf<String>()
        typeStates.forEach { (type, readState) ->
            if (readState == TypeReadState.PERMISSION_DENIED) return@forEach
            DAY_FIELDS_BY_TYPE[type].orEmpty().filterTo(result) { it !in presentFields }
        }
        result += zeroValuedFields.filter { it in presentFields }
        return result
    }
}
