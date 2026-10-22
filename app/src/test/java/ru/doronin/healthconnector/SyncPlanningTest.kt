package ru.doronin.healthconnector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.doronin.healthconnector.watchdog.WatchdogPolicy
import java.time.LocalDate

class SyncPlanningTest {
    private val today = LocalDate.of(2026, 10, 22)
    private fun d(day: Int) = LocalDate.of(2026, 10, day)

    @Test
    fun manualReadsOnlyRepairsPlusRecentDays() {
        val repairs = (11..20).map(::d) + d(4)
        val dates = SyncDatePlanner.select(today, repairs, legacyStart = d(4), manual = true, fallbackDays = 7, changedDates = emptySet())
        assertEquals(listOf(d(4)) + (11..22).map(::d), dates)
    }

    @Test
    fun backgroundRepairsThreeOldestPerRun() {
        val repairs = (11..20).map(::d) + d(4)
        val dates = SyncDatePlanner.select(today, repairs, d(4), manual = false, fallbackDays = 2, changedDates = setOf(d(1), d(21)))
        assertEquals(listOf(d(4), d(11), d(12), d(21), d(22)), dates)
    }

    @Test
    fun legacyServerKeepsContiguousRange() {
        val dates = SyncDatePlanner.select(today, null, d(19), manual = true, fallbackDays = 7, changedDates = emptySet())
        assertEquals((19..22).map(::d), dates)
        val bg = SyncDatePlanner.select(today, null, d(1), manual = false, fallbackDays = 2, changedDates = emptySet())
        assertEquals(listOf(d(21), d(22)), bg)
    }

    @Test
    fun nothingOutsideHealthConnectWindow() {
        val dates = SyncDatePlanner.select(today, listOf(LocalDate.of(2026, 9, 1), d(5)), d(5), true, 7, emptySet())
        assertTrue(dates.none { it.isBefore(today.minusDays(29)) })
        assertTrue(d(5) in dates)
    }

    @Test
    fun incompleteDaysAlertOnlyAfterARecheck() {
        assertNull(WatchdogPolicy.incompleteDays(today, listOf(Triple(d(21), listOf("пульс"), 0))))
        assertNotNull(WatchdogPolicy.incompleteDays(today, listOf(Triple(d(20), listOf("пульс"), 1))))
        assertNotNull(WatchdogPolicy.incompleteDays(today, listOf(Triple(d(18), listOf("весь день"), 0))))
        assertNull(WatchdogPolicy.incompleteDays(today, listOf(Triple(d(1), listOf("шаги"), 3))))  // older than a week
    }
}
