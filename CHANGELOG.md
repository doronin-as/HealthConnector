# Changelog

## 1.6.8 — Security hardening and FatSecret import parity

- Prevent Google Sheets formula injection from external FatSecret food text.
- Use one tested `FatSecretCsvNormalizer` for both Android Share and manual CSV imports.
- Build and publish a non-debuggable, minified signed release APK instead of `app-debug.apk`.
- Remove the browser/file `ACTION_VIEW` entry point from `ShareCsvActivity`.
- Compare API tokens using fixed-size HMAC digests without prefix-dependent early exit.
- Deduplicate Health Connect permission-failure detection and use asynchronous diagnostics persistence.
- Reuse already-read day records for same-day workout summaries; keep a direct-read fallback for cross-midnight sessions.
- Remove unused Apps Script diary synchronization functions and add FatSecret normalizer unit tests.
- Treat an empty Health Connect pagination token as terminal to avoid platform-specific read loops.

## 1.6.7 — Sync diagnostics and lock recovery

- Manual and background synchronization can no longer run in parallel inside the app.
- A background run is skipped cleanly when a manual run is active instead of surfacing a lock timeout.
- Google Sheets lock contention and transient network failures are retried automatically with backoff.
- Added expandable diagnostics for manual sync, background sync, and Google Sheets/network stages.
- Diagnostics record the stage, timestamp, retry, completion, and exact error category without storing the API token.
- Apps Script returns a structured `LOCK_BUSY` response after a short wait so the client can retry.
- The bounded-history behavior from the 1.6.6 hotfix is now part of the main source.

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
