# Changelog

## 1.6.28 — Fitbit origin recovery and stable dual-source sync

- Added an explicit Health Connect `DataOrigin("com.fitbit.FitbitMobile")` fallback for sleep after reinstall/device migration.
- Sleep diagnostics now report both unfiltered and explicit Fitbit-origin record counts.
- Kept manual Health Connect reads in the foreground; WorkManager remains background-only.
- Added a dormant server-side Fitbit Web API connector in Apps Script for heart rate, resting heart rate, HRV, SpO2, respiratory rate and cloud sleep fallback.
- Fitbit OAuth access/refresh tokens and client credentials stay in Apps Script Script Properties, never in Android or the JSON backup.
- OAuth tokens refresh automatically; 401 gets one refresh+retry and 429 respects the rate-limit failure instead of retrying aggressively.
- Fitbit cloud enrichment is additive-only and does not alter Health Connect `SyncComplete`.
- OAuth callback installs a 6-hour repair trigger for the last three days.
- Added `docs/fitbit-stable-sync.md` with source policy and setup instructions.
- Version `1.6.28`, versionCode `42`.

## 1.6.27 — Foreground manual sync

- Restored user-triggered Health Connect reads to the foreground Activity, matching the original 1.0 architecture.
- Reserved WorkManager for real background synchronization only.
- Fixed ManualSyncWorker so it refuses to read Health Connect when Background Read capability is unavailable instead of continuing with a partial view of records.
- This targets the regression where third-party Fitbit/Google Health records disappear while system/on-device and HealthConnector-owned records remain visible.

## 1.6.26 — Auto Config Recovery and Health Connect diagnostics

- Added first-launch automatic discovery of `healthconnector-config*.json` in storage locations readable by the current Android installation.
- Added safe one-time `ACTION_OPEN_DOCUMENT` fallback when Android scoped storage hides a surviving config after reinstall.
- Unified automatic and manual JSON config import through one validated parser.
- Preserved secure token handling: modern exports do not include the API token in plaintext; legacy JSON tokens are migrated into Android Keystore.
- Added richer sleep diagnostics with raw-vs-filtered `SleepSessionRecord` counts and detected source packages.
- Fixed loss of short Fitbit sleep-stage intervals by summing duration in milliseconds before converting to minutes.
- Updated interim source priority to prefer Fitbit over Google Fit and legacy Xiaomi/Mi Fitness records for metrics that still use source selection.
- Added `TODO_1.6.26.md` with the remaining integrity/refactoring backlog.
- Bumped Android version to `1.6.26` / versionCode `40`.


## 1.6.22 — BLE zero-result diagnostics and scanner ownership

- Fixed the manual Xiaomi scale finder so it stops the persistent PendingIntent BLE scan before starting its foreground high-power scan.
- Added a short hand-off delay before registering the foreground `ScanCallback`, avoiding OEM Bluetooth stacks that silently starve a second concurrent scanner.
- Manual scale discovery now reports the number of raw BLE callbacks separately from the number of parsed/identified devices.
- Scan results without `scanRecord` are no longer discarded before diagnostics; they are counted and can still be listed when an address is available.
- Added diagnostics for missing scan records and failures while reading Bluetooth device addresses.
- Added an explicit Bluetooth LE hardware-feature check and explicit legacy-advertisement scanning for older Xiaomi scale advertising.
- Added system-location state to live BLE diagnostics. If Android returns zero callbacks and system location is disabled, the UI calls this out explicitly because some Xiaomi/MIUI builds gate BLE scanning behind the location master switch.
- Added shortcuts to Android Bluetooth settings and system location settings directly from the scale finder.
- Timeout messaging now distinguishes: no BLE callbacks at all, BLE callbacks with no accessible devices, BLE devices found but no Xiaomi Scale signature, and a recognized scale.
- The normal persistent background scanner is restarted when the manual finder closes.
- Bumped Android version to `1.6.22` / versionCode `36`.

## 1.6.21 — Forced Xiaomi scale finder

- Added a dedicated `Найти весы вручную` screen from Xiaomi scale settings.
- Manual discovery runs a 30-second `SCAN_MODE_LOW_LATENCY` BLE scan without restrictive filters, so the phone can surface the scale even when firmware advertisements do not match the known background filters.
- The finder shows a live countdown plus the number of all nearby BLE devices and the number recognized as likely Xiaomi body-composition scales.
- Known scale candidates are detected from Service Data UUID `0x181B`, advertised service UUID `0x181B`, known scale names, and successfully parsed stable measurements.
- Candidate rows show device name, Bluetooth address, RSSI, recognition reason, and weight/impedance when a stable body-composition packet is already available.
- Added explicit manual binding: the user can select a discovered candidate and store its Bluetooth address as the bound scale.
- Added an optional `Показать все BLE-устройства` mode as a diagnostic escape hatch for unknown firmware signatures; manual binding of an unrecognized device remains possible when the user identifies it confidently.
- The normal low-power persistent background scanner is restarted after leaving the manual finder or after binding.
- Added runtime Bluetooth permission handling and system Bluetooth enable flow directly in the finder.
- Bumped Android version to `1.6.21` / versionCode `35`.

## 1.6.20 — Xiaomi scale BLE discovery diagnostics

- Fixed XMTZC05HM discovery so the persistent scanner matches Body Composition `0x181B` when it is published as BLE Service Data, not only as an advertised service UUID.
- Added fallback filters for advertised `0x181B` and the legacy `MIBFS` device name.
- The PendingIntent scanner now stops/restarts before applying new filters, avoiding stale scan registration after an app update.
- Added persistent BLE diagnostics: scan state/start time, last packet time/address/name/RSSI, last parsed measurement, and last scanner error.
- Added live scanner diagnostics to the Xiaomi scale settings screen, refreshed every second while the screen is open.
- Added `Проверить BLE-сканер сейчас` for an explicit background scanner restart/test.
- Bumped Android version to `1.6.20` / versionCode `34`.

## 1.6.19 — In-app Dashboard, devices and automatic profile discovery

- Added a dedicated `Дашборд` tab to HealthConnector as the default application page.
- The in-app Dashboard reads the latest daily snapshot from Google Sheets and shows weight, steps, sleep, resting/average heart rate, SpO₂, active calories and workout count.
- Added an authenticated Apps Script action `dashboardSnapshotV1`; the API token remains required and is not returned by the endpoint.
- Added an `Устройства и источники` block that combines local Xiaomi scale state, Health Connect availability, source apps/packages seen in `HC_Измерения`, `HC_Сон` and `HC_Тренировки`, and scale information from `HC_Состав_тела`.
- Added a button to open the full Google Dashboard and a manual Dashboard refresh action.
- Added automatic scale-profile discovery from the hidden `Профиль` sheet. Height and date of birth are loaded automatically; sex is also loaded when a `Пол`/`Sex`/`Gender` row exists.
- Automatically discovered scale-profile values are persisted locally and reused by body-composition calculations. Missing fields preserve the local/manual fallback instead of being overwritten with blanks.
- The scale settings screen now visibly reports which profile fields were found in Dashboard and includes an explicit `Обновить профиль из Dashboard` button.
- First launch no longer forces the scale settings screen before the Apps Script URL/token are configured; the main screen opens first so Dashboard discovery can work.
- Opening scale settings from the main screen now saves the currently entered Apps Script URL/token first, so profile discovery uses the current connection settings.
- Dashboard refreshes after a successful manual synchronization, after configuration restore, on resume, and on explicit refresh.
- Added retry/redirect handling to Dashboard snapshot requests for transient `404`, `408`, `429`, lock-busy and `5xx` failures.
- Bumped Android version to `1.6.19` / versionCode `33`.

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
