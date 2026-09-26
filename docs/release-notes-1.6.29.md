# HealthConnector 1.6.29 — canonical Health Connect origins

## What changed

HealthConnector now treats the three active Health Connect producers as explicit canonical sources:

- Fitbit: `com.fitbit.FitbitMobile`
- Google Fit: `com.google.android.apps.fitness`
- Mi Fitness: `com.xiaomi.wearable`

Source priority no longer depends on package-name substring heuristics. Only exact canonical package IDs receive the Fitbit → Google Fit → Mi Fitness preference. Unknown/lookalike packages remain neutral and cannot accidentally win source selection.

## Sleep recovery

The reinstall/migration sleep recovery path now probes each canonical `DataOrigin` separately in addition to the ordinary unfiltered Health Connect read. Results are merged by stable record ID (or source + interval fallback), so repeated reads remain idempotent.

Live diagnostics show the record count for the unfiltered read and for each canonical source independently. A failure in one origin probe does not discard records from the other origins.

## Compatibility

The existing 1.6.28 Fitbit Web API fallback, Auto Config Recovery, foreground manual sync, partial-data preservation, and Xiaomi scale/floor modules remain intact.

Version: `1.6.29`  
versionCode: `43`
