package ru.doronin.healthconnector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.doronin.healthconnector.floors.FloorEstimator

class FloorEstimatorTest {
    @Test
    fun countsOneFloorWhenAltitudeAndStepsAgree() {
        val estimator = FloorEstimator(smoothingAlpha = 1.0)
        val t0 = 1_000L
        assertNull(estimator.onAltitude(100.0, t0))
        repeat(6) { estimator.onStep(t0 + 1_000L + it * 500L) }

        val detection = estimator.onAltitude(103.1, t0 + 5_000L)

        requireNotNull(detection)
        assertEquals(1, detection.floors)
        assertEquals(3.0, detection.elevationMeters, 0.001)
    }

    @Test
    fun liftWithoutStepsIsNotCounted() {
        val estimator = FloorEstimator(smoothingAlpha = 1.0)
        val t0 = 10_000L
        estimator.onAltitude(100.0, t0)

        val detection = estimator.onAltitude(112.0, t0 + 10_000L)

        assertNull(detection)
    }

    @Test
    fun descentResetsBaselineBeforeNextClimb() {
        val estimator = FloorEstimator(smoothingAlpha = 1.0)
        val t0 = 20_000L
        estimator.onAltitude(100.0, t0)
        estimator.onStep(t0 + 1_000L)
        estimator.onAltitude(97.0, t0 + 2_000L)
        repeat(6) { estimator.onStep(t0 + 3_000L + it * 500L) }

        val detection = estimator.onAltitude(100.2, t0 + 7_000L)

        requireNotNull(detection)
        assertEquals(1, detection.floors)
    }
}
