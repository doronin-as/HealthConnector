package ru.doronin.healthconnector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.doronin.healthconnector.watchdog.WatchdogPolicy
import ru.doronin.healthconnector.water.WaterFormat
import ru.doronin.healthconnector.wellbeing.ReminderSchedule
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class WaterWellbeingWatchdogTest {
    private val moscow = ZoneId.of("Europe/Moscow")
    private val morning = LocalTime.of(9, 30)
    private val evening = LocalTime.of(21, 30)

    @Test
    fun litersFormatting() {
        assertEquals("0", WaterFormat.liters(0))
        assertEquals("0,25", WaterFormat.liters(250))
        assertEquals("1,25", WaterFormat.liters(1250))
        assertEquals("1,3", WaterFormat.liters(1300))
        assertEquals("2", WaterFormat.liters(2000))
        assertEquals("1,25 / 2 л", WaterFormat.summary(1250, 2000))
    }

    @Test
    fun progressIsClamped() {
        assertEquals(50, WaterFormat.progressPercent(1000, 2000))
        assertEquals(100, WaterFormat.progressPercent(5000, 2000))
        assertEquals(0, WaterFormat.progressPercent(100, 0))
    }

    @Test
    fun nextReminderOrder() {
        val day = LocalDate.of(2026, 10, 22)
        fun at(h: Int, m: Int) = ZonedDateTime.of(day, LocalTime.of(h, m), moscow)
        assertEquals(ReminderSchedule.Next(ReminderSchedule.SLOT_MORNING, at(9, 30)),
            ReminderSchedule.next(at(7, 0), morning, evening))
        assertEquals(ReminderSchedule.Next(ReminderSchedule.SLOT_EVENING, at(21, 30)),
            ReminderSchedule.next(at(9, 30), morning, evening))
        val tomorrow = ReminderSchedule.next(at(23, 0), morning, evening)
        assertEquals(ReminderSchedule.SLOT_MORNING, tomorrow.slot)
        assertEquals(day.plusDays(1), tomorrow.at.toLocalDate())
    }

    @Test
    fun slotBoundary() {
        assertEquals(ReminderSchedule.SLOT_MORNING, ReminderSchedule.slotFor(LocalTime.of(14, 59)))
        assertEquals(ReminderSchedule.SLOT_EVENING, ReminderSchedule.slotFor(LocalTime.of(15, 0)))
        assertEquals(morning, ReminderSchedule.parse("garbage", morning))
        assertEquals(LocalTime.of(8, 0), ReminderSchedule.parse("08:00", morning))
    }

    @Test
    fun staleDaysNeedsYesterday() {
        val today = LocalDate.of(2026, 10, 22)
        assertNull(WatchdogPolicy.staleDays(today, today))
        assertNull(WatchdogPolicy.staleDays(today, today.minusDays(1)))
        assertNotNull(WatchdogPolicy.staleDays(today, today.minusDays(2)))
        assertNotNull(WatchdogPolicy.staleDays(today, null))
    }

    @Test
    fun outboxStuckAfterTwelveHours() {
        val now = 100L * 3_600_000L
        assertNull(WatchdogPolicy.outboxStuck(now, 0, null, ""))
        assertNull(WatchdogPolicy.outboxStuck(now, 3, now - 11 * 3_600_000L, ""))
        val alert = WatchdogPolicy.outboxStuck(now, 3, now - 13 * 3_600_000L, "HTTP 500")
        assertTrue(alert is WatchdogPolicy.Alert.OutboxStuck)
        assertTrue(WatchdogPolicy.message(alert!!).second.contains("HTTP 500"))
    }

    @Test
    fun notificationsRateLimited() {
        val now = 50L * 3_600_000L
        assertTrue(WatchdogPolicy.shouldNotify(now, null))
        assertTrue(!WatchdogPolicy.shouldNotify(now, now - 3_600_000L))
        assertTrue(WatchdogPolicy.shouldNotify(now, now - 25 * 3_600_000L))
    }
}
