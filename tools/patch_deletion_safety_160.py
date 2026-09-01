from pathlib import Path

p = Path('app/src/main/java/ru/doronin/healthconnector/HealthSyncStreamer.kt')
s = p.read_text()

old = '''        val deletionDates = if (changes.deletedRecordIds.isNotEmpty()) {
            postDeletedRecordIds(endpoint, token, changes.deletedRecordIds)
        } else {
            emptySet()
        }

        val oldestReadable = today.minusDays(29)
        val dates = linkedSetOf<LocalDate>().apply {
            addAll(requestedDates)
            addAll(changes.affectedDates)
            addAll(deletionDates)
        }'''
new = '''        val deletionDates = if (changes.deletedRecordIds.isNotEmpty()) {
            postDeletedRecordIds(endpoint, token, changes.deletedRecordIds)
        } else {
            emptySet()
        }
        // A DeletionChange contains only recordId, not its timestamp. Usually the server can
        // recover the affected date from the raw row. To guarantee correctness even when that row
        // was never exported by an older app version, any deletion triggers a rare 30-day rebuild.
        val deletionSafetyDates = if (changes.deletedRecordIds.isNotEmpty()) {
            (0L..29L).map { today.minusDays(it) }
        } else {
            emptyList()
        }

        val oldestReadable = today.minusDays(29)
        val dates = linkedSetOf<LocalDate>().apply {
            addAll(requestedDates)
            addAll(changes.affectedDates)
            addAll(deletionDates)
            addAll(deletionSafetyDates)
        }'''
if old not in s:
    raise SystemExit('deletion block not found')
s = s.replace(old, new, 1)

# Cumulative raw records are cheap compared with high-frequency samples. Export all origins so a
# future DeletionChange can be mapped back to a date even when Health Connect's preferred origin is
# different from the app's old source heuristic. Dashboard totals still come exclusively from Aggregate API.
for record in [
    'StepsRecord',
    'DistanceRecord',
    'ActiveCaloriesBurnedRecord',
    'TotalCaloriesBurnedRecord',
    'ElevationGainedRecord',
    'FloorsClimbedRecord',
]:
    old_call = f'preferBestSource(safeReadAll<{record}>(dayStart, dayEnd))'
    new_call = f'safeReadAll<{record}>(dayStart, dayEnd)'
    if old_call not in s:
        raise SystemExit(f'raw cumulative source block not found: {record}')
    s = s.replace(old_call, new_call, 1)

p.write_text(s)
