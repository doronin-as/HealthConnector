# Changelog

## 1.6.18 — Xiaomi Mi Body Composition Scale 2 integration

- Added direct Bluetooth LE support for Xiaomi Mi Body Composition Scale 2 (`XMTZC05HM`).
- Added persistent BLE scanning based on `PendingIntent`, so the scale can be detected without keeping the main activity open.
- Added first-device binding: the first compatible scale can be remembered and subsequent measurements are accepted only from the bound device until it is reset.
- Added a dedicated scale settings screen with profile fields required for body-composition calculations: height, date of birth, and sex.
- Added runtime Bluetooth / nearby-device permission flow and automatic scan restart after reboot or Bluetooth state changes.
- Parse stabilized scale measurements and bioimpedance from BLE advertisements.
- Added body-composition calculations for BMI, body-fat percentage and mass, water percentage and mass, muscle mass and percentage, lean body mass, bone mass, visceral fat, protein percentage, and BMR.
- Added unit tests for scale packet parsing and body-composition calculations.
- Added background upload worker for scale measurements using the existing Apps Script endpoint and secure API token storage.
- Added `bodyCompositionV1` Apps Script action.
- Added `HC_Состав_тела` with columns for raw scale data and calculated body-composition metrics.
- Scale weight also updates `HC_Дни.WeightKg` without marking the whole day complete and is mirrored into `HC_Измерения` as a raw `Weight` measurement.
- Added a notification after a successful scale upload with weight and available body-composition values.
- Added an “Умные весы Xiaomi” card to HealthConnector settings with current state, bound device, last weight, and a shortcut to scale settings.
- Bumped Android version to `1.6.18` / versionCode `32`.
- Apps Script production deployment was updated automatically through the existing GitHub Actions deployment workflow.

## 1.6.17 — Synchronization progress notification

- Expanded foreground synchronization notification to show live synchronization progress in the Android notification shade.
- Added progress percentage when the synchronization stage exposes a determinate value; otherwise the notification uses an indeterminate progress bar.
- Notification text includes the current synchronization stage and elapsed time.
- Tapping the foreground notification opens HealthConnector.
- Added Android 13+ runtime request for `POST_NOTIFICATIONS`, so foreground progress is actually visible in the notification shade.
- Bumped Android version to `1.6.17`.

## 1.6.16 — Reliable foreground manual synchronization

- Manual synchronization is no longer tied to `Activity.lifecycleScope`.
- The Sync button now enqueues a WorkManager job, allowing synchronization to continue after the app is minimized or the screen is turned off.
- Long manual synchronization runs as a foreground worker with a persistent Android notification.
- Added explicit verification of Health Connect background-read capability and permission before a foreground/background read.
- Existing synchronization diagnostics continue to receive worker progress while the UI is closed.
- Added redirect-aware HTTP behavior and retry handling for transient HTTP failures, including unexpected `404` responses that occur after the endpoint already passed preflight.
- Transient server requests are retried instead of immediately aborting the whole day synchronization.
- Worker cancellation is distinguished from application/data errors.

## 1.6.15 — Visible build/version identity

- Added a small version badge directly in the application UI.
- Version text is read from the actually installed APK through `PackageManager`, avoiding a dependency on generated `BuildConfig` fields.
- Side-by-side test builds show a `TEST` marker next to the version.
- Test APK launcher labels include the version (for example `HC 1.6.15 TEST`) so multiple installed test builds can be distinguished on the home screen.

## 1.6.14 — Dashboard-driven resume synchronization

- Replaced fixed “always re-read N recent days” behavior for manual repair with a synchronization plan driven by the state of `HC_Дни`.
- Added authenticated Apps Script action `healthSyncPlanV3`.
- Added `SyncComplete` and `CompletedAt` columns to `HC_Дни`.
- Early/day checkpoints write `SyncComplete=false`; a past day is marked complete only after its final snapshot has been successfully written.
- The current day remains incomplete because more Health Connect data can still arrive later the same day.
- The next synchronization resumes from the earliest incomplete day or first repairable gap in the 30-day readable Health Connect window.
- Missing historical dates older than the 30-day repair window no longer force endless rebuilds.
- When rebuilding a day, Apps Script clears the previous day row before writing the checkpoint/final snapshot so stale partial values cannot survive.
- If `healthSyncPlanV3` is unavailable, the Android client logs the condition and falls back to the configured local day range.
- Measurements, workouts, and sleep remain upserted by ID, so repeated large batches do not create duplicate physical rows.
- Added automatic Apps Script production deployment via `.github/workflows/apps-script-deploy.yml` using `clasp` and an existing deployment ID, preserving the same `/exec` URL.

## 1.6.13 — Live synchronization telemetry

- Added detailed live synchronization stages and heartbeat updates during long operations.
- Synchronization UI reports stages such as `1/8 … 8/8`, batch counters, cumulative measurement counts, and server acknowledgements.
- Added a heartbeat while a long raw-measurement batch is being processed so the UI does not look frozen.
- Manual-sync progress refreshes continuously while WorkManager/server activity is still alive.
- Repeated tapping of the manual Sync button no longer creates a second queued manual synchronization or hides the progress of the currently active run.

## 1.6.11 — Background worker stability

- Changed periodic WorkManager re-registration from replacement behavior to `ExistingPeriodicWorkPolicy.KEEP`.
- Reapplying background-sync settings no longer cancels an already running periodic synchronization.
- WorkManager cancellation is no longer surfaced to the user as an application synchronization error.

## 1.6.10 — Secure-token dashboard status

- Fixed Google Sheets configuration/status checks to read the API token from `SecureTokenStore` instead of legacy preferences.
- Dashboard/server status correctly reflects the secure token after token-storage migration.
- Busy UI states now include the save/write stage instead of appearing idle during persistence.

## 1.6.9 — Early day checkpoint before raw measurements

- Persist `HC_Дни` and `HC_Сон` before large high-frequency raw-measurement streaming starts.
- An interrupted long synchronization therefore keeps dashboard-critical day and sleep data instead of losing the whole run.
- The final day snapshot is still sent later to fill the remaining metrics after measurement streaming completes.
- Bumped Android version to `1.6.9` / versionCode `23`.

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

## Planned / backlog

- Design a secure automatic recovery mechanism for HealthConnector configuration/credentials after reinstall, data reset, or moving between side-by-side test builds.
- Recovery must not store API tokens or other secrets in plaintext and must account for Android Keystore material being removed when an app package is uninstalled.
- Prefer a user-controlled encrypted backup/recovery flow for endpoint + API token/configuration, with clear rotation/revocation behavior.
- Continue improving scale integration after real XMTZC05HM measurements are compared with Mi Fit/Zepp Life results; calibrate formulas if systematic differences are found.
