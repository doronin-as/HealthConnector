package ru.doronin.healthconnector.wellbeing

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Pure scheduling logic for check-in reminders (unit-tested). */
object ReminderSchedule {
    const val SLOT_MORNING = "morning"
    const val SLOT_EVENING = "evening"

    data class Next(val slot: String, val at: ZonedDateTime)

    /** Slot a check-in made at [time] belongs to: before 15:00 is morning. */
    fun slotFor(time: LocalTime): String = if (time.isBefore(LocalTime.of(15, 0))) SLOT_MORNING else SLOT_EVENING

    /** Next reminder strictly after [now]. */
    fun next(now: ZonedDateTime, morning: LocalTime, evening: LocalTime): Next {
        val zone: ZoneId = now.zone
        val today: LocalDate = now.toLocalDate()
        val candidates = listOf(
            Next(SLOT_MORNING, ZonedDateTime.of(today, morning, zone)),
            Next(SLOT_EVENING, ZonedDateTime.of(today, evening, zone)),
            Next(SLOT_MORNING, ZonedDateTime.of(today.plusDays(1), morning, zone)),
            Next(SLOT_EVENING, ZonedDateTime.of(today.plusDays(1), evening, zone))
        )
        return candidates.filter { it.at.isAfter(now) }.minBy { it.at }
    }

    fun parse(value: String?, fallback: LocalTime): LocalTime =
        runCatching { LocalTime.parse(value.orEmpty()) }.getOrDefault(fallback)
}
