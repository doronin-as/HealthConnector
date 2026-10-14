package ru.doronin.healthconnector

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure sleep interval arithmetic shared by [HealthSyncStreamer].
 *
 * All durations are computed on [Instant]s, so DST transitions and timezone
 * changes never stretch or shrink a session; only day attribution depends on
 * the zone.
 */
internal object SleepMath {
    data class Interval(val start: Instant, val end: Instant) {
        val millis: Long get() = Duration.between(start, end).toMillis()
    }

    data class Summary(
        val rawMillis: Long,
        val uniqueMillis: Long,
        val main: Interval?,
        val naps: List<Interval>
    ) {
        val uniqueHours: Double get() = uniqueMillis / 3_600_000.0
        val rawHours: Double get() = rawMillis / 3_600_000.0
        val mainHours: Double? get() = main?.let { it.millis / 3_600_000.0 }
        val napMinutes: Long get() = naps.sumOf { Duration.between(it.start, it.end).toMinutes() }
    }

    /** A session belongs to the local calendar day on which the user wakes up. */
    fun wakeDate(end: Instant, zone: ZoneId): LocalDate = end.atZone(zone).toLocalDate()

    /** Unions overlapping or touching intervals; empty/inverted intervals are dropped. */
    fun merge(intervals: List<Interval>): List<Interval> {
        val sorted = intervals.filter { it.start < it.end }.sortedBy { it.start }
        if (sorted.isEmpty()) return emptyList()
        val merged = mutableListOf<Interval>()
        var currentStart = sorted.first().start
        var currentEnd = sorted.first().end
        for (interval in sorted.drop(1)) {
            if (!interval.start.isAfter(currentEnd)) {
                if (interval.end > currentEnd) currentEnd = interval.end
            } else {
                merged += Interval(currentStart, currentEnd)
                currentStart = interval.start
                currentEnd = interval.end
            }
        }
        merged += Interval(currentStart, currentEnd)
        return merged
    }

    /** Returns null when no interval has positive length. */
    fun summarize(intervals: List<Interval>): Summary? {
        val valid = intervals.filter { it.start < it.end }
        if (valid.isEmpty()) return null
        val merged = merge(valid)
        val main = merged.maxByOrNull { it.millis }
        return Summary(
            rawMillis = valid.sumOf { it.millis },
            uniqueMillis = merged.sumOf { it.millis },
            main = main,
            naps = merged.filter { it != main }
        )
    }
}
