package ru.doronin.healthconnector

import java.time.LocalDate

/**
 * Chooses which days one sync run reads (pure, unit-tested).
 *
 * With a v1-quality server the plan names exactly the days that need repair: missing
 * days, unfinished days and days without an expected metric. Instead of re-reading a
 * contiguous range from the first problem to today, only those days plus yesterday and
 * today are read. A background run repairs a few of the oldest problems each time, so
 * holes close on their own; the oldest go first because Health Connect forgets them first.
 */
object SyncDatePlanner {
    const val READABLE_DAYS = 30
    const val BACKGROUND_REPAIR_LIMIT = 3

    fun select(
        today: LocalDate,
        repairDates: List<LocalDate>?,
        legacyStart: LocalDate,
        manual: Boolean,
        fallbackDays: Int,
        changedDates: Set<LocalDate>
    ): List<LocalDate> {
        val oldestReadable = today.minusDays((READABLE_DAYS - 1).toLong())
        val recentStart = today.minusDays((fallbackDays.coerceIn(1, READABLE_DAYS) - 1).toLong())
        val selected = linkedSetOf<LocalDate>()

        if (repairDates == null) {
            // Legacy server: contiguous range from the plan's start date.
            val start = (if (manual) legacyStart else legacyStart.coerceAtLeast(recentStart))
                .coerceAtLeast(oldestReadable).coerceAtMost(today)
            var cursor = start
            while (!cursor.isAfter(today)) {
                selected += cursor
                cursor = cursor.plusDays(1)
            }
        } else {
            selected += today.minusDays(1)
            selected += today
            val repairs = repairDates.filter { !it.isBefore(oldestReadable) && it.isBefore(today) }.sorted()
            selected += if (manual) repairs else repairs.take(BACKGROUND_REPAIR_LIMIT)
        }
        // Days whose Health Connect data changed; background stays bounded to recent days.
        selected += changedDates.filter { manual || !it.isBefore(recentStart) }
        return selected.filter { !it.isBefore(oldestReadable) && !it.isAfter(today) }.distinct().sorted()
    }
}
