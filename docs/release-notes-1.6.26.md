# HealthConnector 1.6.26 — Integrity Recovery

This release starts the integrity/recovery refactor identified by the full application audit.

## Included in this release candidate

- Added automatic recovery of missing technical row keys in:
  - `HC_Измерения`
  - `HC_Сон`
  - `HC_Тренировки`
  - `HC_Состав_тела`
- Recovery keys are deterministic synthetic IDs built from stable row signatures.
- When the same row later arrives with its real Health Connect record ID, the recovered row is updated in place and the synthetic key is replaced by the authoritative ID.
- Added authenticated Apps Script action `integrityRepairV1` for an explicit integrity scan/repair.
- Improved sleep synchronization diagnostics: logs now distinguish raw SleepSession count from records remaining after wake-date filtering and report detected source packages.
- Fixed sleep-stage duration loss for short Fitbit segments: durations are accumulated in milliseconds and rounded to minutes only after summation.
- Updated interim wearable priority so Fitbit is preferred over Google Fit and legacy Xiaomi/Mi Fitness data in source-selected metrics.
- Added `TODO_1.6.26.md` with the P0/P1/P2 integrity roadmap and release gate.
- Bumped Android version to `1.6.26` / versionCode `40`.

## Important

This is a release candidate branch, not yet the final integrity architecture. Remaining P0 items include authoritative-empty semantics, expired changes-token reconciliation, and stronger day-completeness state.
