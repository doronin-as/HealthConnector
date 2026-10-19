# HealthConnector: summary-first sync and research-grade raw archive (v4)

## Data flow

```
Health Connect (day-by-day)
  ├─ compute daily statistics and quality flags without building a giant array
  │    → healthSyncV3 days=[summary], dayComplete=false
  │    → Google Sheets HC_Дни (trends, comparisons, overview)
  │
  └─ stage raw samples as bounded JSONL batches in app-private cache
       → healthArchiveV4 workouts + sleep sessions
       → healthArchiveV4 raw measurements (up to 2,000 records per request)
       → Apps Script / Google Drive / immutable verified gzip JSON files
       → final healthSyncV3 days=[{date}], dayComplete=computed completeness
```

*If archive upload fails, the day remains incomplete, and the Health Connect changes token is not advanced. The next sync can reread and retry the day; confirmed archives are not removed. An interrupted run cleans up its local temporary spool.*

## What goes where

| Source | Google Sheets | Google Drive |
| --- | --- | --- |
| Daily steps, distance, sleep duration/stages, energy, weight, heart-rate stats, HRV, SpO2, respiration, VO2 max, exercise totals, cadence/speed/power/elevation | One consolidated row per day in `HC_Дни` | No redundant aggregated copies required |
| Individual weight and sensor measurements | No new high-frequency rows | Full-resolution values with timestamps, type, units, source package and source name |
| High-frequency heart rate, speed, cadence, power and skin-temperature deltas | Only statistics (average/min/max/count) | Individual samples instead of irreversible 5-minute buckets |
| Sleep sessions and stages; workout sessions, segments, laps and metadata | Daily metrics and counts | Session-level detail, including available JSON stage/segment fields |
| Synchronization logs, legacy detailed sheets | Not in trend table | Existing verified archival pipeline |

The Google Drive storage format is **gzip JSON** with schema version, spreadsheet identity, timezone, source name, day, operation, column headers, and rows. Files are **immutable events** and may represent revised uploads of the same record. For ML datasets, deduplicate by `source` + `Id` + latest `SyncedAt`; apply deletion events from `HealthConnectChanges` before building a current-state dataset. Do not blindly sum historical event files. Keep raw source IDs, timestamps, units, source names, and relevant timezone information.

## Storage rollout update (2026-10-19)

The **deployed** V4 server now uses simple standalone raw files, independently of the legacy Drive Archive setting. It stores detailed JSON.gz files in `HealthConnector — подробные данные v4` and automatically creates the folder on first nonempty upload, **provided the Apps Script owner has granted Drive access**. It does not require `installDriveArchiveV1`, and does not migrate or delete legacy raw sheets. See `docs/RAW_FILES_V4.md` in PR #42 for the current server storage contract. The original legacy archival setup below is historical and **must not be used** for the standalone V4 pipeline.

The client continues to transmit verified batches of up to 2,000 samples. v1.6.38 also retries temporary `UnknownHostException` and stops immediately if the server cannot be reached for its synchronization plan, instead of reading seven fallback days pointlessly. A persistent DNS failure still needs a working network or resolver; these changes cannot fix an unreachable domain.

## Reliability and migration prerequisites

1. Deploy the updated Apps Script Web App. Its new authenticated action `healthArchiveV4` refuses to write to Sheets if Drive archive mode is disabled.
2. **As project owner**, run `installDriveArchiveV1()` in the Apps Script editor, approve Drive and trigger scopes, and verify `driveArchiveStatusV1().enabled === true`. Do **not** rely on the previous GitHub Actions `clasp run` attempt; its last logs showed permission errors. Do not migrate/delete historical rows before verifying archives.
3. Install the new APK and allow Health Connect (including background read where supported). After a sync, check a daily summary, raw files, and the final `SyncComplete` flag.
4. Disable the separate legacy 5-minute migration/coarsening trigger after enabling Drive archive. Existing five-minute measurements **cannot** be expanded back into original samples; preservation applies to data read by the new client from Health Connect.
5. Test interruption at each phase: before summary, during first archive batch, after a partial archive, and after last archive acknowledgement. A failed archive must never mark a day complete.
6. Keep archives private. These files contain sensitive personal measurements; do not publish links, add them to the source repository, or use public sharing. Plan data retention and pseudonymization before training models.

## Protocol

`healthSyncV3`: unchanged daily summary writer; new client sends the completed day **without** detailed workouts, sleep sessions or measurements, with `dayComplete=false`. A separate date-only request can set the final `dayComplete` value.

`healthArchiveV4`: authenticated, archive-only action. Requires `DRIVE_ARCHIVE_ENABLED_V1=1`, rejects a nonempty `days` array, validates the payload, writes all detailed records to read-back-verified Google Drive gzip objects and returns `archived:true`, `archiveSchemaVersion:4`, and exact counts of accepted workouts/sleep sessions/measurements. The client verifies these counts for **every** batch before finalizing its day. Re-upload after a network timeout is safe (content-addressed files) though different `SyncedAt` values intentionally create separate history events.

## Important limitations

- Health Connect's normal historical read window is finite. If the device stays offline past its readable window, automatic repair of *old* raw samples is not guaranteed.
- Local staging uses Android app-private cache, which can be cleared; full source records are reread when retrying. The data is not guaranteed to survive deleting Health Connect data or uninstalling the source app.
- The implementation archives the records selected by the existing source-priority logic; it does not promise every overlapping record from every installed health app.
- A Drive file is not a fully queryable database. Before training, build a replay/validation pipeline with deduplication, deletion handling, missingness flags, clock/timezone checks, and per-source provenance.
- No actual cloud migration, authorization or live-phone verification is performed by merging code. Keep the feature isolated until the staged rollout passes.
