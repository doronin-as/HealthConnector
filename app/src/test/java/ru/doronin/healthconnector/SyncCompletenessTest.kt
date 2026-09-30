package ru.doronin.healthconnector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SyncCompletenessTest {
    private val today = LocalDate.of(2026, 9, 30)
    private val yesterday = today.minusDays(1)

    @Test
    fun emptyAggregateIsNotReportedAsZero() {
        val empty: MetricRead<Long> = MetricRead.Available(null)
        assertEquals(TypeReadState.EMPTY, empty.readState())
        assertNull(empty.reportableValue())
    }

    @Test
    fun deniedAggregateIsNotReportedAsZero() {
        val denied: MetricRead<Long> = MetricRead.PermissionDenied("no permission")
        assertEquals(TypeReadState.PERMISSION_DENIED, denied.readState())
        assertNull(denied.reportableValue())
    }

    @Test
    fun realZeroAggregateIsReportedAsZero() {
        val zero: MetricRead<Long> = MetricRead.Available(0L, setOf("com.fitbit.FitbitMobile"))
        assertEquals(TypeReadState.HAS_DATA, zero.readState())
        assertEquals(0L, zero.reportableValue())
    }

    @Test
    fun recordReadStatesSeparateEmptyDeniedAndData() {
        assertEquals(TypeReadState.EMPTY, TypeReadState.of(permissionDenied = false, recordCount = 0))
        assertEquals(TypeReadState.PERMISSION_DENIED, TypeReadState.of(permissionDenied = true, recordCount = 0))
        assertEquals(TypeReadState.HAS_DATA, TypeReadState.of(permissionDenied = false, recordCount = 3))
    }

    @Test
    fun mergeKeepsDenialAndData() {
        assertEquals(TypeReadState.HAS_DATA, TypeReadState.merge(TypeReadState.EMPTY, TypeReadState.HAS_DATA))
        assertEquals(TypeReadState.HAS_DATA, TypeReadState.merge(TypeReadState.HAS_DATA, TypeReadState.EMPTY))
        assertEquals(TypeReadState.PERMISSION_DENIED, TypeReadState.merge(TypeReadState.HAS_DATA, TypeReadState.PERMISSION_DENIED))
        assertEquals(TypeReadState.EMPTY, TypeReadState.merge(null, TypeReadState.EMPTY))
    }

    @Test
    fun sumOfNoRecordsIsNullButSumOfZeroRecordsIsZero() {
        assertNull(sumOrNull(emptyList<Double>()) { it })
        assertEquals(0.0, sumOrNull(listOf(0.0, 0.0)) { it }!!, 0.0)
        assertEquals(5.5, sumOrNull(listOf(2.0, 3.5)) { it }!!, 0.0)
    }

    @Test
    fun finishedDayWithAllTypesReadIsComplete() {
        val verdict = DaySyncStateEvaluator.evaluate(
            date = yesterday,
            today = today,
            typeStates = mapOf(
                "StepsRecord" to TypeReadState.HAS_DATA,
                "WeightRecord" to TypeReadState.EMPTY
            ),
            sleepNeedsRepair = false
        )
        assertEquals(DaySyncState.COMPLETE, verdict.state)
        assertTrue(verdict.state.isComplete)
        assertEquals(setOf("WeightRecord"), verdict.emptyTypes)
    }

    @Test
    fun deniedTypeBlocksCompletionAndIsNamed() {
        val verdict = DaySyncStateEvaluator.evaluate(
            date = yesterday,
            today = today,
            typeStates = mapOf(
                "StepsRecord" to TypeReadState.HAS_DATA,
                "HeartRateRecord" to TypeReadState.PERMISSION_DENIED
            ),
            sleepNeedsRepair = false
        )
        assertEquals(DaySyncState.PERMISSION_BLOCKED, verdict.state)
        assertFalse(verdict.state.isComplete)
        assertEquals(setOf("HeartRateRecord"), verdict.deniedTypes)
    }

    @Test
    fun todayAndMissingRecentSleepWaitForSource() {
        val readable = mapOf("StepsRecord" to TypeReadState.HAS_DATA)
        assertEquals(
            DaySyncState.WAITING_FOR_SOURCE,
            DaySyncStateEvaluator.evaluate(today, today, readable, sleepNeedsRepair = false).state
        )
        assertEquals(
            DaySyncState.WAITING_FOR_SOURCE,
            DaySyncStateEvaluator.evaluate(yesterday, today, readable, sleepNeedsRepair = true).state
        )
    }

    @Test
    fun interruptedDayIsPartial() {
        val verdict = DaySyncStateEvaluator.evaluate(
            yesterday, today, emptyMap(), sleepNeedsRepair = false, interrupted = true
        )
        assertEquals(DaySyncState.PARTIAL, verdict.state)
    }

    @Test
    fun tokenExpiryQueuesWholeReadableWindow() {
        val from = ReconciliationWindow.afterTokenExpiry(today, pendingFrom = null)
        assertEquals(today.minusDays(29), from)
        val dates = ReconciliationWindow.pendingDates(from, today)
        assertEquals(30, dates.size)
        assertEquals(from, dates.first())
        assertEquals(today, dates.last())
    }

    @Test
    fun reconciliationAdvancesOnlyAsDaysAreReRead() {
        val from = today.minusDays(2)
        // Re-reading some other day does not move the marker.
        assertEquals(from, ReconciliationWindow.advance(from, today, today))
        val afterFirst = ReconciliationWindow.advance(from, from, today)
        assertEquals(today.minusDays(1), afterFirst)
        val afterSecond = ReconciliationWindow.advance(afterFirst, today.minusDays(1), today)
        assertEquals(today, afterSecond)
        assertNull(ReconciliationWindow.advance(afterSecond, today, today))
        assertTrue(ReconciliationWindow.pendingDates(null, today).isEmpty())
    }

    @Test
    fun staleReconciliationMarkerIsClippedToReadableWindow() {
        val stale = today.minusDays(90)
        assertEquals(30, ReconciliationWindow.pendingDates(stale, today).size)
        assertEquals(
            today.minusDays(28),
            ReconciliationWindow.advance(stale, today.minusDays(29), today)
        )
    }
}
