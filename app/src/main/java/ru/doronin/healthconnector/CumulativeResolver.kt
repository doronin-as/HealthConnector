package ru.doronin.healthconnector

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Cross-checks a cumulative daily total (steps, distance, active calories) taken from the
 * Health Connect Aggregate API against the totals of every individual data origin.
 *
 * Aggregate is normally right: it removes overlap between apps according to the user's
 * Health Connect priority list. When several apps write the same steps and the priority
 * list does not cover them, the aggregate can exceed every single source — e.g. bracelet
 * 15 000 + Mi Fitness 15 000 = 30 000. No real total can be larger than the largest single
 * source by much, so in that case the value of the preferred source is used instead.
 *
 * Pure logic, unit-tested.
 */
object CumulativeResolver {
    data class Interval(val source: String, val startMs: Long, val endMs: Long, val value: Double)

    data class Tolerance(val relative: Double, val absolute: Double)

    val STEPS_TOLERANCE = Tolerance(0.15, 300.0)
    val DISTANCE_TOLERANCE = Tolerance(0.15, 0.3)
    val CALORIES_TOLERANCE = Tolerance(0.15, 50.0)

    const val METHOD_AGGREGATE = "aggregate"
    const val METHOD_SOURCE = "source"
    const val METHOD_OVERRIDE = "override"
    const val METHOD_NONE = "none"

    data class SourceTotal(val total: Double, val records: Int, val selfOverlap: Boolean)

    data class Resolution(
        val value: Double?,
        val method: String,
        val chosenSource: String?,
        val overcount: Boolean
    )

    /**
     * Per-origin totals inside [dayStartMs, dayEndMs). Exact duplicate records are counted
     * once; records crossing the day boundary are prorated by time.
     */
    fun perSource(intervals: List<Interval>, dayStartMs: Long, dayEndMs: Long): Map<String, SourceTotal> =
        intervals
            .filter { it.value.isFinite() && it.value > 0 && it.endMs >= it.startMs }
            .distinctBy { Triple(it.source, it.startMs to it.endMs, it.value) }
            .groupBy { it.source }
            .mapValues { (_, list) ->
                var total = 0.0
                for (item in list) total += insideDay(item, dayStartMs, dayEndMs)
                SourceTotal(total, list.size, hasSelfOverlap(list))
            }
            .filterValues { it.total > 0 }

    private fun insideDay(item: Interval, dayStartMs: Long, dayEndMs: Long): Double {
        val duration = item.endMs - item.startMs
        if (duration <= 0) return if (item.startMs in dayStartMs until dayEndMs) item.value else 0.0
        val overlap = min(item.endMs, dayEndMs) - max(item.startMs, dayStartMs)
        if (overlap <= 0) return 0.0
        return item.value * overlap.toDouble() / duration.toDouble()
    }

    /** True when one app's own records overlap by more than 10% of their duration. */
    private fun hasSelfOverlap(list: List<Interval>): Boolean {
        val sorted = list.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        if (sorted.size < 2) return false
        var sum = 0L
        var union = 0L
        var curStart = sorted[0].startMs
        var curEnd = sorted[0].endMs
        for (item in sorted) {
            sum += item.endMs - item.startMs
            if (item.startMs > curEnd) {
                union += curEnd - curStart
                curStart = item.startMs
                curEnd = item.endMs
            } else if (item.endMs > curEnd) {
                curEnd = item.endMs
            }
        }
        union += curEnd - curStart
        return union > 0 && sum > union * 1.1
    }

    /**
     * Chooses the daily value. [priority]: lower is more trusted. [override]: package the
     * user selected in settings, used whenever it has data.
     */
    fun resolve(
        aggregate: Double?,
        perSource: Map<String, SourceTotal>,
        priority: (String) -> Int,
        tolerance: Tolerance,
        override: String? = null
    ): Resolution {
        val overrideTotal = override?.takeIf { it.isNotBlank() }?.let { perSource[it] }
        if (override != null && overrideTotal != null) {
            return Resolution(overrideTotal.total, METHOD_OVERRIDE, override, overcount = false)
        }
        val preferred = preferredSource(perSource, priority)
        if (aggregate == null || !aggregate.isFinite()) {
            return if (preferred == null) Resolution(null, METHOD_NONE, null, false)
            else Resolution(perSource.getValue(preferred).total, METHOD_SOURCE, preferred, false)
        }
        val maxSingle = perSource.values.maxOfOrNull { it.total } ?: return Resolution(aggregate, METHOD_AGGREGATE, null, false)
        val limit = maxSingle * (1 + tolerance.relative) + tolerance.absolute
        if (aggregate > limit && preferred != null) {
            return Resolution(perSource.getValue(preferred).total, METHOD_SOURCE, preferred, overcount = true)
        }
        return Resolution(aggregate, METHOD_AGGREGATE, null, false)
    }

    /** Most trusted source that is not self-overlapping; ties broken by the larger total. */
    fun preferredSource(perSource: Map<String, SourceTotal>, priority: (String) -> Int): String? =
        perSource.entries
            .sortedWith(
                compareBy<Map.Entry<String, SourceTotal>> { it.value.selfOverlap }
                    .thenBy { priority(it.key) }
                    .thenByDescending { it.value.total }
            )
            .firstOrNull()?.key

    /** Walking/running stride outside 0.45–1.4 m means steps or distance is wrong. */
    fun strideLooksWrong(steps: Double?, distanceKm: Double?): Boolean {
        if (steps == null || distanceKm == null || steps < 2000 || distanceKm < 1.0) return false
        val stride = distanceKm * 1000.0 / steps
        return stride < 0.45 || stride > 1.4
    }

    /** "Fitbit 15 210; Mi Fitness 14 980" — for the sheet and the sync log. */
    fun describe(perSource: Map<String, SourceTotal>, name: (String) -> String, decimals: Int = 0): String =
        perSource.entries.sortedByDescending { it.value.total }.joinToString("; ") { (pkg, total) ->
            val value = if (decimals == 0) {
                String.format(Locale("ru"), "%,d", total.total.roundToLong()).replace(' ', ' ')
            } else {
                String.format(Locale("ru"), "%.${decimals}f", total.total)
            }
            name(pkg) + " " + value + if (total.selfOverlap) " (дубли)" else ""
        }
}
