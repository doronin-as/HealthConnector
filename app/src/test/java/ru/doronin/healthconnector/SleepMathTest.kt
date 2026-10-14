package ru.doronin.healthconnector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class SleepMathTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val moscow = ZoneId.of("Europe/Moscow")
    private val newYork = ZoneId.of("America/New_York")

    private fun at(zone: ZoneId, text: String) = LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun session(zone: ZoneId, start: String, end: String) =
        SleepMath.Interval(at(zone, start), at(zone, end))

    @Test
    fun overnightSessionBelongsToWakeDay() {
        val night = session(moscow, "2026-09-14T23:30", "2026-09-15T07:15")

        assertEquals(LocalDate.of(2026, 9, 15), SleepMath.wakeDate(night.end, moscow))
        assertEquals(7.75, SleepMath.summarize(listOf(night))!!.uniqueHours, 1e-9)
    }

    @Test
    fun springForwardNightIsOneHourShorterThanWallClock() {
        // Europe/Berlin jumps 02:00 -> 03:00 on 2026-03-29.
        val night = session(berlin, "2026-03-28T23:00", "2026-03-29T07:00")

        val summary = SleepMath.summarize(listOf(night))!!

        assertEquals(7.0, summary.uniqueHours, 1e-9)
        assertEquals(7.0, summary.mainHours!!, 1e-9)
        assertEquals(LocalDate.of(2026, 3, 29), SleepMath.wakeDate(night.end, berlin))
    }

    @Test
    fun fallBackNightIsOneHourLongerThanWallClock() {
        // Europe/Berlin repeats 02:00-03:00 on 2026-10-25.
        val night = session(berlin, "2026-10-24T23:00", "2026-10-25T07:00")

        val summary = SleepMath.summarize(listOf(night))!!

        assertEquals(9.0, summary.uniqueHours, 1e-9)
        assertEquals(LocalDate.of(2026, 10, 25), SleepMath.wakeDate(night.end, berlin))
    }

    @Test
    fun usSpringForwardNightKeepsRealDuration() {
        // America/New_York jumps 02:00 -> 03:00 on 2026-03-08.
        val night = session(newYork, "2026-03-07T22:30", "2026-03-08T06:30")

        assertEquals(7.0, SleepMath.summarize(listOf(night))!!.uniqueHours, 1e-9)
    }

    @Test
    fun wakeTimeJustAfterMidnightInOneZoneIsPreviousDayInAnother() {
        // Travel case: the same instant is 00:30 in Moscow but 23:30 the day before in Berlin (CEST).
        val wake = at(moscow, "2026-07-10T00:30")

        assertEquals(LocalDate.of(2026, 7, 10), SleepMath.wakeDate(wake, moscow))
        assertEquals(LocalDate.of(2026, 7, 9), SleepMath.wakeDate(wake, berlin))
    }

    @Test
    fun travelSessionDurationIgnoresZoneChange() {
        // Fell asleep in New York, woke up after flying east; duration is instant-based.
        val start = at(newYork, "2026-06-01T20:00")
        val end = at(berlin, "2026-06-02T08:00")

        val summary = SleepMath.summarize(listOf(SleepMath.Interval(start, end)))!!

        assertEquals(6.0, summary.uniqueHours, 1e-9)
        assertEquals(LocalDate.of(2026, 6, 2), SleepMath.wakeDate(end, berlin))
        assertEquals(LocalDate.of(2026, 6, 2), SleepMath.wakeDate(end, newYork))
    }

    @Test
    fun overlappingDuplicateSessionsAcrossDstAreNotDoubleCounted() {
        val fitbit = session(berlin, "2026-10-24T23:00", "2026-10-25T07:00")
        val phone = session(berlin, "2026-10-25T01:00", "2026-10-25T07:30")

        val summary = SleepMath.summarize(listOf(fitbit, phone))!!

        assertEquals(9.5, summary.uniqueHours, 1e-9)
        assertEquals(9.0 + 7.5, summary.rawHours, 1e-9)
        assertEquals(0, summary.naps.size)
    }

    @Test
    fun separateNapIsReportedApartFromMainSleep() {
        val night = session(moscow, "2026-09-14T23:00", "2026-09-15T07:00")
        val nap = session(moscow, "2026-09-15T14:00", "2026-09-15T14:40")

        val summary = SleepMath.summarize(listOf(nap, night))!!

        assertEquals(night, summary.main)
        assertEquals(listOf(nap), summary.naps)
        assertEquals(40L, summary.napMinutes)
        assertEquals(8.0 + 40.0 / 60.0, summary.uniqueHours, 1e-9)
    }

    @Test
    fun touchingIntervalsMergeIntoOne() {
        val first = session(moscow, "2026-09-14T23:00", "2026-09-15T03:00")
        val second = session(moscow, "2026-09-15T03:00", "2026-09-15T07:00")

        assertEquals(listOf(session(moscow, "2026-09-14T23:00", "2026-09-15T07:00")),
            SleepMath.merge(listOf(second, first)))
    }

    @Test
    fun emptyAndInvertedIntervalsAreIgnored() {
        val zero = session(moscow, "2026-09-15T03:00", "2026-09-15T03:00")
        val inverted = session(moscow, "2026-09-15T07:00", "2026-09-15T03:00")

        assertNull(SleepMath.summarize(listOf(zero, inverted)))
        assertNull(SleepMath.summarize(emptyList()))
    }
}
