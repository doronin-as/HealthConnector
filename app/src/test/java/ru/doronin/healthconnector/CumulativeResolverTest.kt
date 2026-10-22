package ru.doronin.healthconnector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.doronin.healthconnector.CumulativeResolver.Interval

class CumulativeResolverTest {
    private val hour = 3_600_000L
    private val dayStart = 0L
    private val dayEnd = 24 * hour
    private val fitbit = HealthSourceCatalog.FITBIT_PACKAGE
    private val mi = HealthSourceCatalog.MI_FITNESS_PACKAGE
    private val fit = HealthSourceCatalog.GOOGLE_FIT_PACKAGE
    private val phone = "com.android.healthconnect.phone.j437397a9c"
    private val priority: (String) -> Int = HealthSourceCatalog::priority

    private fun hourly(source: String, perHour: Double, hours: IntRange) =
        hours.map { Interval(source, it * hour, (it + 1) * hour, perHour) }

    @Test
    fun aggregateDoubleCountReplacedByBracelet() {
        // Real case 21.10.2026: bracelet ≈15k, Mi Fitness ≈15k, aggregate 32k.
        val records = hourly(fitbit, 1000.0, 8..22) + hourly(mi, 980.0, 8..22) + hourly(phone, 200.0, 9..18)
        val by = CumulativeResolver.perSource(records, dayStart, dayEnd)
        assertEquals(15000.0, by.getValue(fitbit).total, 0.1)
        val r = CumulativeResolver.resolve(32243.0, by, priority, CumulativeResolver.STEPS_TOLERANCE)
        assertTrue(r.overcount)
        assertEquals(CumulativeResolver.METHOD_SOURCE, r.method)
        assertEquals(fitbit, r.chosenSource)
        assertEquals(15000.0, r.value!!, 0.1)
    }

    @Test
    fun plausibleAggregateIsKept() {
        // Aggregate merges bracelet + phone-only hours: a bit above bracelet, below limit.
        val by = CumulativeResolver.perSource(hourly(fitbit, 1000.0, 8..19) + hourly(phone, 500.0, 20..22), dayStart, dayEnd)
        val r = CumulativeResolver.resolve(13400.0, by, priority, CumulativeResolver.STEPS_TOLERANCE)
        assertFalse(r.overcount)
        assertEquals(CumulativeResolver.METHOD_AGGREGATE, r.method)
        assertEquals(13400.0, r.value!!, 0.0)
    }

    @Test
    fun duplicatesCountedOnceAndBoundaryProrated() {
        val records = listOf(
            Interval(fitbit, 1 * hour, 2 * hour, 600.0),
            Interval(fitbit, 1 * hour, 2 * hour, 600.0),          // exact duplicate
            Interval(fitbit, -1 * hour, 1 * hour, 400.0),          // half before midnight
            Interval(fitbit, 30 * hour, 31 * hour, 999.0)          // next day
        )
        val by = CumulativeResolver.perSource(records, dayStart, dayEnd)
        assertEquals(800.0, by.getValue(fitbit).total, 0.01)
        assertFalse(by.getValue(fitbit).selfOverlap)
    }

    @Test
    fun selfOverlappingSourceIsNotPreferred() {
        val overlapping = listOf(
            Interval(fitbit, 8 * hour, 12 * hour, 4000.0),
            Interval(fitbit, 8 * hour, 12 * hour, 3999.0),
            Interval(fitbit, 9 * hour, 11 * hour, 2000.0)
        )
        val by = CumulativeResolver.perSource(overlapping + hourly(mi, 1000.0, 8..11), dayStart, dayEnd)
        assertTrue(by.getValue(fitbit).selfOverlap)
        assertEquals(mi, CumulativeResolver.preferredSource(by, priority))
    }

    @Test
    fun overrideWinsWhenItHasData() {
        val by = CumulativeResolver.perSource(hourly(fitbit, 1000.0, 8..10) + hourly(mi, 900.0, 8..10), dayStart, dayEnd)
        val r = CumulativeResolver.resolve(3000.0, by, priority, CumulativeResolver.STEPS_TOLERANCE, override = mi)
        assertEquals(CumulativeResolver.METHOD_OVERRIDE, r.method)
        assertEquals(2700.0, r.value!!, 0.1)
        val missing = CumulativeResolver.resolve(3000.0, by, priority, CumulativeResolver.STEPS_TOLERANCE, override = fit)
        assertEquals(CumulativeResolver.METHOD_AGGREGATE, missing.method)
    }

    @Test
    fun noAggregateFallsBackToPreferredSource() {
        val by = CumulativeResolver.perSource(hourly(phone, 100.0, 0..9) + hourly(fit, 300.0, 0..9), dayStart, dayEnd)
        val r = CumulativeResolver.resolve(null, by, priority, CumulativeResolver.STEPS_TOLERANCE)
        assertEquals(fit, r.chosenSource)
        assertNull(CumulativeResolver.resolve(null, emptyMap(), priority, CumulativeResolver.STEPS_TOLERANCE).value)
    }

    @Test
    fun strideCheck() {
        assertTrue(CumulativeResolver.strideLooksWrong(32243.0, 13.06))   // 0.40 m
        assertFalse(CumulativeResolver.strideLooksWrong(17000.0, 13.06))  // 0.77 m
        assertFalse(CumulativeResolver.strideLooksWrong(500.0, 0.1))
    }

    @Test
    fun describeSortsBySize() {
        val by = CumulativeResolver.perSource(hourly(fitbit, 1000.0, 8..22) + hourly(mi, 100.0, 8..9), dayStart, dayEnd)
        assertEquals("Fitbit 15 000; Mi Fitness 200", CumulativeResolver.describe(by, HealthSourceCatalog::displayName))
    }
}
