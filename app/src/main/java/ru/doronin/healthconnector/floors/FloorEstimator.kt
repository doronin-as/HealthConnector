package ru.doronin.healthconnector.floors

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

data class FloorDetection(
    val floors: Int,
    val elevationMeters: Double,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long
)

/**
 * Converts relative altitude changes into climbed floors while requiring recent
 * walking activity. The estimator intentionally works with altitude rather than
 * Android Sensor objects so the algorithm can be unit-tested independently.
 */
class FloorEstimator(
    private val floorHeightMeters: Double = 3.0,
    private val minStepsPerFloor: Int = 6,
    private val maxStepIdleMs: Long = 30_000L,
    private val maxClimbWindowMs: Long = 180_000L,
    private val descentResetMeters: Double = 1.2,
    private val smoothingAlpha: Double = 0.18
) {
    private var filteredAltitude: Double? = null
    private var anchorAltitude: Double? = null
    private var anchorAtEpochMs: Long = 0L
    private var stepsSinceAnchor: Int = 0
    private var lastStepAtEpochMs: Long = Long.MIN_VALUE

    fun onStep(timestampEpochMs: Long) {
        if (timestampEpochMs < anchorAtEpochMs) return
        stepsSinceAnchor += 1
        lastStepAtEpochMs = timestampEpochMs
    }

    fun onAltitude(altitudeMeters: Double, timestampEpochMs: Long): FloorDetection? {
        if (!altitudeMeters.isFinite()) return null

        val previous = filteredAltitude
        val filtered = if (previous == null) {
            altitudeMeters
        } else {
            previous + smoothingAlpha.coerceIn(0.0, 1.0) * (altitudeMeters - previous)
        }
        filteredAltitude = filtered

        val currentAnchor = anchorAltitude
        if (currentAnchor == null) {
            resetAnchor(filtered, timestampEpochMs)
            return null
        }

        val walkingIsRecent = lastStepAtEpochMs != Long.MIN_VALUE &&
            timestampEpochMs - lastStepAtEpochMs in 0..maxStepIdleMs

        // Pressure can drift because of weather or change rapidly in a lift/car.
        // Without recent walking we follow the new baseline instead of crediting it.
        if (!walkingIsRecent) {
            if (abs(filtered - currentAnchor) >= 0.45 || timestampEpochMs - anchorAtEpochMs > maxStepIdleMs) {
                resetAnchor(filtered, timestampEpochMs)
            }
            return null
        }

        // A meaningful descent creates a new baseline for the next climb.
        if (filtered <= currentAnchor - descentResetMeters) {
            resetAnchor(filtered, timestampEpochMs)
            return null
        }

        // Very slow pressure drift should not accumulate into a fake staircase.
        if (timestampEpochMs - anchorAtEpochMs > maxClimbWindowMs && filtered - currentAnchor < floorHeightMeters) {
            resetAnchor(filtered, timestampEpochMs)
            return null
        }

        val riseMeters = filtered - currentAnchor
        if (riseMeters < floorHeightMeters) return null

        val floorsByAltitude = floor(riseMeters / floorHeightMeters).toInt()
        val floorsBySteps = stepsSinceAnchor / minStepsPerFloor
        val detectedFloors = min(floorsByAltitude, floorsBySteps)
        if (detectedFloors <= 0) return null

        val startedAt = anchorAtEpochMs
        val creditedElevation = detectedFloors * floorHeightMeters
        anchorAltitude = currentAnchor + creditedElevation
        anchorAtEpochMs = timestampEpochMs
        stepsSinceAnchor = (stepsSinceAnchor - detectedFloors * minStepsPerFloor).coerceAtLeast(0)

        return FloorDetection(
            floors = detectedFloors,
            elevationMeters = creditedElevation,
            startedAtEpochMs = startedAt,
            endedAtEpochMs = timestampEpochMs
        )
    }

    fun reset() {
        filteredAltitude = null
        anchorAltitude = null
        anchorAtEpochMs = 0L
        stepsSinceAnchor = 0
        lastStepAtEpochMs = Long.MIN_VALUE
    }

    private fun resetAnchor(altitudeMeters: Double, timestampEpochMs: Long) {
        anchorAltitude = altitudeMeters
        anchorAtEpochMs = timestampEpochMs
        stepsSinceAnchor = 0
    }
}
