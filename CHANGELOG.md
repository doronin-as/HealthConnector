# Changelog

## 1.6.0 — Data Integrity

- Daily cumulative metrics (steps, distance, calories, elevation, floors) now use Health Connect Aggregate API instead of summing raw records.
- Added per-record-type Health Connect Changes API tokens and deletion handling.
- Deleted Health Connect records trigger raw-row cleanup and a safety recomputation of the readable 30-day window.
- Day summaries are partial updates: permission-denied or unreadable metrics no longer overwrite existing values with zero/blank.
- Added server schema v3 compatibility preflight; the Android client refuses to send v3 data to an old Apps Script deployment.
- `Дневник` is now read-only for integrations; Health Connector and FatSecret write only to source sheets.
- Sleep is attributed to wake date, supports multiple sessions per day, identifies main sleep and naps, and de-duplicates overlapping sleep stages.
- Added `MainSleepHours`, `NapCount`, and `NapMinutes` to `HC_Дни`.
- Raw cumulative records retain all data origins for deletion traceability while dashboard totals rely on Aggregate API de-duplication.
- Apps Script schema v3 is bundled into a single deployable `apps-script/Code.gs`.
