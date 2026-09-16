# Phone sensor floor tracking

Health Connector can use the phone itself as a health-data source for climbed floors and elevation gain.

## Architecture

The feature is intentionally split into independent layers:

- `HealthDataSource` — common contract for phone sensors, wearables and future providers.
- `PhoneSensorDataSource` — availability/lifecycle of the phone source.
- `FloorEstimator` — pure Kotlin floor-detection algorithm.
- `PhoneFloorTrackingService` — foreground acquisition of barometer + step detector data.
- `PhoneFloorStore` — local daily counters and diagnostics.
- `FloorHealthConnectWriter` — optional write-through to Health Connect.
- `PhoneFloorSettingsActivity` — user-facing controls and permissions.

This keeps Mi Band / Mi Fitness, Fitbit, Garmin, Samsung Health and other future integrations independent from phone-sensor logic.

## Detection logic

The phone source requires:

1. `TYPE_PRESSURE` to estimate relative altitude changes.
2. `TYPE_STEP_DETECTOR` plus `ACTIVITY_RECOGNITION` to confirm the user is walking.
3. A foreground service while tracking is enabled.

The estimator uses a nominal floor height of 3.0 m and requires at least 6 detected steps per credited floor. Altitude changes without recent steps update the baseline and are not counted, which rejects most lift/car events and slow atmospheric-pressure drift.

Absolute altitude is not needed. `SensorManager.getAltitude(PRESSURE_STANDARD_ATMOSPHERE, pressure)` is used only for relative altitude differences.

## Health Connect

Local counting works without Health Connect write access. If the user grants the permissions, each detected climb is written as:

- `FloorsClimbedRecord`
- `ElevationGainedRecord`

with `Metadata.autoRecorded` and `Device.TYPE_PHONE`.

Required manifest permissions:

- `android.permission.health.WRITE_FLOORS_CLIMBED`
- `android.permission.health.WRITE_ELEVATION_GAINED`

Whether a downstream app displays those records is controlled by that app; Health Connector only guarantees the local counter and the Health Connect insertion when permission is granted.

## Android background behavior

The tracker is an opt-in `health` foreground service. It is started from a visible activity when the user enables the source. Health Connector deliberately does not auto-start the health foreground service from `BOOT_COMPLETED`, avoiding Android background-start restrictions. If tracking was enabled before reboot, opening Health Connector restarts the source.

## Future sources

New integrations should implement `HealthDataSource` and declare their supported metrics. A future source registry can then handle source priority and deduplication, for example:

- phone: floors, elevation, GPS
- fitness band/watch: heart rate, sleep, SpO2, workouts, steps
- smart scale: weight and body composition
- Health Connect: shared exchange layer

When multiple sources provide the same metric, deduplication/priority belongs in a separate aggregation layer rather than inside individual source implementations.
