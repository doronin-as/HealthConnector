# HealthConnector 1.6.26 — Integrity & Auto Config Recovery

This release candidate starts the integrity refactor and adds first-launch recovery of the JSON configuration already exported by HealthConnector.

## Auto Config Recovery

- On a fresh/unconfigured installation, HealthConnector now automatically searches for `healthconnector-config*.json` in locations Android allows the current app installation to read.
- Search sources include persisted document URIs, app-accessible storage, readable legacy Downloads/Documents paths, and MediaStore Downloads.
- When several readable candidates exist, the newest valid HealthConnector config is selected.
- The parser validates `format=HealthConnectorConfig`, config version, HTTPS endpoint, sync-day range and background-sync setting before applying anything.
- Manual JSON import now uses the same parser as automatic restore.
- Modern exports still do **not** write the API token in plaintext.
- Legacy/user-created JSON containing `token` is accepted and migrated into Android Keystore through `SecureTokenStore`.
- Android 11–16 scoped storage can hide a Downloads document after reinstall. When silent discovery cannot read the old file, HealthConnector opens the system document picker once as the safe fallback instead of requesting `MANAGE_EXTERNAL_STORAGE`.

## Health Connect fixes included so far

- Improved sleep synchronization diagnostics: logs distinguish raw `SleepSessionRecord` count from records remaining after wake-date filtering and report source packages.
- Fixed sleep-stage duration loss for short Fitbit segments: durations are accumulated in milliseconds and converted to minutes only after summation.
- Updated the interim source policy so Fitbit is preferred over Google Fit and legacy Xiaomi/Mi Fitness data in source-selected metrics.
- Added `TODO_1.6.26.md` with the P0/P1/P2 integrity roadmap and release gate.
- Bumped Android version to `1.6.26` / versionCode `40`.

## Important

This branch does not request broad file-system access. Android's scoped-storage model intentionally prevents a freshly reinstalled normal app from silently scanning arbitrary Documents/Downloads content, so the one-time system picker is the fallback when automatic discovery cannot legally access the surviving JSON file.
