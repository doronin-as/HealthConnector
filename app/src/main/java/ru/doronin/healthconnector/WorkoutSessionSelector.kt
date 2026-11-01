package ru.doronin.healthconnector

import kotlin.math.abs

/**
 * Deduplicates EXERCISE sessions without discarding an entire data origin.
 *
 * Two apps frequently publish the same workout at slightly different timestamps.
 * Keep the preferred source only for the *same* session. Different activities,
 * non-overlapping sessions and sequential bouts must survive even when another
 * app also has entries on that day.
 */
internal object WorkoutSessionSelector {
    data class Candidate(
        val id: String,
        val startMs: Long,
        val endMs: Long,
        val exerciseType: Int,
        val source: String,
        val priority: Int
    )

    fun selectIndices(sessions: List<Candidate>): List<Int> {
        val candidates = sessions.indices
            .filter { sessions[it].endMs > sessions[it].startMs }
            .sortedWith(
                compareBy<Int>({ sessions[it].priority }, { sessions[it].startMs },
                    { sessions[it].endMs }, { sessions[it].source }, { sessions[it].id })
            )
        val kept = ArrayList<Int>(candidates.size)
        for (index in candidates) {
            if (kept.none { isDuplicate(sessions[index], sessions[it]) }) kept += index
        }
        return kept.sortedWith(compareBy<Int>({ sessions[it].startMs }, { sessions[it].endMs }))
    }

    private fun isDuplicate(a: Candidate, b: Candidate): Boolean {
        // IDs are only unique within the originating application's namespace.
        if (a.source == b.source && a.id.isNotBlank() && a.id == b.id) return true
        if (a.exerciseType != b.exerciseType) return false
        if (a.startMs == b.startMs && a.endMs == b.endMs) return true
        // Do not collapse separate workouts reported by the SAME source.
        if (a.source == b.source) return false

        val durationA = a.endMs - a.startMs
        val durationB = b.endMs - b.startMs
        val shorter = minOf(durationA, durationB)
        val longer = maxOf(durationA, durationB)
        if (shorter < 120_000L || longer > shorter * 1.15) return false
        val overlap = minOf(a.endMs, b.endMs) - maxOf(a.startMs, b.startMs)
        return overlap >= shorter * 0.9 &&
            abs(a.startMs - b.startMs) <= 300_000L &&
            abs(a.endMs - b.endMs) <= 300_000L
    }
}
