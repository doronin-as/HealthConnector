# Stable Fitbit sync architecture

HealthConnector uses two independent health-data paths so a change in Google Health ↔ Health Connect export does not silently remove Fitbit vitals.

## Source policy

| Metric | Primary source | Fallback |
| --- | --- | --- |
| Steps / cumulative activity | Health Connect Aggregate API | none |
| Floors / elevation | Health Connect + HealthConnector phone sensors | none |
| Weight / body composition | Xiaomi scale integration | Health Connect weight |
| Sleep / sleep stages | Health Connect, including explicit Fitbit DataOrigin probe | Fitbit Web API |
| Heart rate / resting HR | Fitbit Web API | Health Connect when available |
| HRV | Fitbit Web API | Health Connect when available |
| SpO2 | Fitbit Web API | Health Connect when available |
| Respiratory rate | Fitbit Web API | Health Connect when available |

Google Health currently writes sleep and sleep stages to Health Connect but does not write heart rate, HRV, SpO2, respiratory rate, or resting heart rate. Those Fitbit metrics therefore must not depend on Health Connect alone.

## Fitbit Web API setup

1. Register a Fitbit Developer application for personal use.
2. Deploy the repository Apps Script web app.
3. In the Apps Script editor run:
   `configureFitbitOAuthV1("<CLIENT_ID>", "<CLIENT_SECRET>")`
4. Copy the returned `redirectUri` into the Fitbit Developer application exactly.
5. Run `getFitbitAuthorizationUrlV1()` again after saving the redirect URI and open the returned URL.
6. Approve the requested scopes. Fitbit redirects back to Apps Script, which stores access/refresh tokens only in Script Properties.
7. Run `fitbitSyncRecentV1(7)` once to backfill the last seven days.
8. The OAuth callback installs `fitbitScheduledSyncV1`, which runs every six hours and repairs the last three days.

Never put the Fitbit client secret, access token, or refresh token in Android preferences, the JSON backup file, or Google Sheets.

## Failure behaviour

- OAuth access tokens are refreshed automatically before expiry.
- A 401 triggers one forced refresh and one retry.
- A 429 is not retried aggressively; the Fitbit reset interval is surfaced in the error.
- One unavailable Fitbit endpoint does not abort the whole date. Other metrics still sync and the partial failure is appended to `HC_Журнал`.
- Fitbit enrichment does not modify `SyncComplete`; Health Connect reconciliation owns that state.
- Missing Fitbit values are additive-only and do not clear good values already stored in `HC_Дни`.
- Writes use stable IDs and are safe to repeat.

## Diagnostics

Public Apps Script helpers:

- `fitbitStatusV1_()` — internal status object used by the web-app health endpoint.
- `fitbitSyncRecentV1(days)` — manual repair/backfill, 1–30 days.
- `fitbitInstallTriggerV1()` — reinstall the 6-hour trigger.
- `fitbitRemoveTriggerV1()` — remove the trigger.
- `disconnectFitbitOAuthV1()` — remove OAuth tokens while keeping client credentials.

Android 1.6.28 additionally probes `DataOrigin("com.fitbit.FitbitMobile")` specifically for sleep after migration/reinstall and reports `all=N; fitbit=M` in live sync diagnostics.
