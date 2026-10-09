package ru.doronin.healthconnector

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class MeasurementBucketsTest {
    @Test
    fun groupsSamplesIntoUtcFiveMinuteBuckets() {
        val samples = sequenceOf(
            Instant.parse("2026-09-10T10:00:00Z") to 60.0,
            Instant.parse("2026-09-10T10:04:59.900Z") to 80.0,
            Instant.parse("2026-09-10T10:05:00Z") to 100.0,
            Instant.parse("2026-09-10T10:07:30Z") to Double.NaN
        )

        val buckets = MeasurementBuckets.group(samples)

        assertEquals(2, buckets.size)
        val first = buckets.getValue(Instant.parse("2026-09-10T10:00:00Z").epochSecond)
        assertEquals(2, first.count)
        assertEquals(70.0, first.average, 1e-9)
        assertEquals(60.0, first.min, 1e-9)
        assertEquals(80.0, first.max, 1e-9)
        val second = buckets.getValue(Instant.parse("2026-09-10T10:05:00Z").epochSecond)
        assertEquals(1, second.count)
        assertEquals(100.0, second.average, 1e-9)
    }

    @Test
    fun bucketIdKeepsRecordIdAsFirstSegment() {
        val start = Instant.parse("2026-09-10T10:05:00Z").epochSecond
        assertEquals("abc-123|5m|$start", MeasurementBuckets.bucketId("abc-123", start))
    }

    @Test
    fun bucketStartFloorsTimesBeforeEpoch() {
        assertEquals(-300L, MeasurementBuckets.bucketStart(Instant.ofEpochSecond(-1)))
    }
}
