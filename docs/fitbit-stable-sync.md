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

## Google Health API vitals

The Google Health API connector (the in-app **Google Health Cloud** card) now also fills heart rate (average, min, max, sample count), resting heart rate, HRV, SpO2 and respiratory rate in `HC_Дни`, plus one daily row per metric in `HC_Измерения` (`google-health|<type>|<date>`). It needs the `googlehealth.health_metrics_and_measurements.readonly` and `googlehealth.activity_and_fitness.readonly` scopes, so an existing sleep-only authorization must be repeated once; the app says so in the card status.

It runs three ways, all additive (a metric the API does not return never clears a stored value):
- after every app sync, for the synced days within the last three days (`googleHealthSyncVitalsV1`);
- from the card's **Загрузить сон и показатели за 7 дней** button;
- every six hours for the last three days (`googleHealthScheduledSyncV1`, installed on OAuth callback or with `googleHealthInstallTriggerV1()`); `googleHealthSyncRecentV1(days)` backfills up to 30 days from the editor.

Endpoints that fail (for example a data type the account has no data for) are logged as one `WARNING` row in `HC_Журнал` and do not stop the other metrics.

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

Android 1.6.29 uses a canonical origin registry for `com.fitbit.FitbitMobile` (Fitbit), `com.google.android.apps.fitness` (Google Fit), and `com.xiaomi.wearable` (Mi Fitness). Sleep recovery probes all three origins independently after migration/reinstall, merges records idempotently, and reports `all=N; Fitbit=N; Google Fit=N; Mi Fitness=N` in live sync diagnostics.
