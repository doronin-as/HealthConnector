package ru.doronin.healthconnector

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutSessionSelectorTest {
    private fun s(id: String, startMin: Long, durationMin: Long, origin: String,
                  priority: Int, type: Int = 79) =
        WorkoutSessionSelector.Candidate(id, startMin * 60_000L,
            (startMin + durationMin) * 60_000L, type, origin, priority)

    @Test fun keepsIndependentSessionsFromDifferentSources() {
        val sessions = listOf(
            s("fit-walk", 0, 25, "fitbit", 0),
            s("mi-strength", 60, 55, "mi", 2, 10),
            s("google-run", 130, 30, "google", 1, 57))
        assertEquals(listOf(0, 1, 2), WorkoutSessionSelector.selectIndices(sessions))
    }

    @Test fun identicalCrossSourceWorkoutUsesPreferredRecord() {
        val sessions = listOf(s("mi-a", 0, 60, "mi", 2),
            s("fit-a", 1, 60, "fitbit", 0))
        assertEquals(listOf(1), WorkoutSessionSelector.selectIndices(sessions))
    }

    @Test fun adjacentBoutsAreNotMerged() {
        val sessions = listOf(s("a", 0, 20, "fitbit", 0),
            s("b", 21, 20, "mi", 2))
        assertEquals(listOf(0, 1), WorkoutSessionSelector.selectIndices(sessions))
    }

    @Test fun distinctTypesAtTheSameTimeArePreserved() {
        val sessions = listOf(s("a", 0, 60, "fitbit", 0, 79),
            s("b", 0, 60, "mi", 2, 10))
        assertEquals(listOf(0, 1), WorkoutSessionSelector.selectIndices(sessions))
    }

    @Test fun sameSourceRetainsSeparateSessionsButDeduplicatesId() {
        val sessions = listOf(s("same", 0, 60, "fitbit", 0),
            s("same", 1, 60, "fitbit", 0), s("other", 10, 45, "fitbit", 0))
        assertEquals(listOf(0, 2), WorkoutSessionSelector.selectIndices(sessions))
    }

    @Test fun invalidIntervalsAreIgnored() {
        assertEquals(listOf(1), WorkoutSessionSelector.selectIndices(listOf(
            s("bad", 0, 0, "fitbit", 0), s("valid", 30, 30, "mi", 2))))
    }
}
