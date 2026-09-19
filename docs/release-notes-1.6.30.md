# HealthConnector 1.6.30 — per-type Health Connect diagnostics

## UI

The header now reflects the actual data path:

`Fitbit · Google Fit · Mi Fitness → Health Connect → Google Sheets`

## Health Connect inventory

At the end of each synchronized day the manual/background diagnostic log now emits one compact inventory covering every record type read from Health Connect.

Each line shows:

- raw Health Connect record count;
- selected count after source-priority filtering, when filtering changed the result;
- sample/stage counts for series such as heart rate, speed, cadence, power and sleep;
- every observed `DataOrigin.packageName`;
- an explicit `НЕТ РАЗРЕШЕНИЯ` marker when the app cannot read that type.

This makes a true zero-record response distinguishable from a permission problem and shows whether records came from Fitbit, Google Fit, Mi Fitness, HealthConnector itself or another producer.

Version: `1.6.30`  
versionCode: `44`
