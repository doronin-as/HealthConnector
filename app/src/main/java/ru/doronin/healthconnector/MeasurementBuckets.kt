package ru.doronin.healthconnector

import java.time.Instant
import java.util.TreeMap

/**
 * Downsamples high-frequency Health Connect series (heart rate, speed, cadence, power,
 * skin-temperature deltas) into fixed UTC buckets before upload to Google Sheets.
 *
 * One raw row per sample made HC_Измерения grow toward the 10M-cell spreadsheet limit.
 * A bucket keeps average/min/max/sample count, which is what the dashboard needs, and
 * stays exactly mergeable (weighted by [BucketStats.count]) on the server.
 *
 * Bucket ids are `<recordId>|5m|<bucketStartEpochSeconds>`. The record id stays the first
 * `|`-segment, so Health Connect deletions still match by prefix on the server, and the
 * Apps Script migration builds identical ids from historical per-sample rows.
 */
object MeasurementBuckets {
    const val BUCKET_SECONDS = 300L
    const val BUCKET_TAG = "5m"

    fun bucketStart(time: Instant): Long =
        Math.floorDiv(time.epochSecond, BUCKET_SECONDS) * BUCKET_SECONDS

    fun bucketId(recordId: String, bucketStartEpochSeconds: Long): String =
        "$recordId|$BUCKET_TAG|$bucketStartEpochSeconds"

    /** Groups samples by bucket; non-finite values are ignored. Result is ordered by time. */
    fun group(samples: Sequence<Pair<Instant, Double>>): TreeMap<Long, BucketStats> {
        val buckets = TreeMap<Long, BucketStats>()
        for ((time, value) in samples) {
            if (!value.isFinite()) continue
            buckets.getOrPut(bucketStart(time)) { BucketStats() }.add(value)
        }
        return buckets
    }

    class BucketStats {
        var count: Int = 0
            private set
        var min: Double = Double.NaN
            private set
        var max: Double = Double.NaN
            private set
        private var sum: Double = 0.0

        fun add(value: Double) {
            if (count == 0) {
                min = value
                max = value
            } else {
                if (value < min) min = value
                if (value > max) max = value
            }
            count++
            sum += value
        }

        val average: Double get() = if (count == 0) Double.NaN else sum / count
    }
}
