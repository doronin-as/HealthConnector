# Daily results and raw archives

This change is opt-in. It is **not active** just because the code is deployed.

## Enable

1. Deploy the updated `apps-script/Code.gs` through the existing workflow.
2. In the Apps Script editor, as the project owner, run `installDriveArchiveV1` once and authorize Google Drive and time triggers. This creates a private Drive folder, verifies an archive write/read, and then enables the new writer.
3. Publish/update the web-app deployment after owner authorization if Google requires renewed authorization for the new scopes. If the project uses explicit oauthScopes, add `https://www.googleapis.com/auth/drive` and `https://www.googleapis.com/auth/script.scriptapp` to the existing scopes, preserving them.
4. Run `driveArchiveStatusV1` to inspect progress. A trigger drains history every five minutes; new uploads bypass raw sheets immediately.

Do not report the migration as completed until `enabled` is true and `migration.remaining` is zero. `migration.blocked` identifies reports that still depend on raw sheets; these must be rewritten to use daily totals before those source rows can be removed. No live spreadsheet migration has been run as part of preparing this change.

## Storage

`HC_Дни` remains the existing daily-summary source. Existing Health Connect/client and cloud aggregation logic is preserved; measurements from overlapping providers are not blindly summed. Workouts, sleep sessions, measurements, body composition detail, and synchronization logs go to gzip JSON files in Drive. Historical Mi Fitness, workout details, and sleep segments are included in the migration.

The daily record is committed after detailed archives have been verified. Legacy daily upserts and V3 partial updates retain their existing key semantics. This change does not repair pre-existing duplicate daily rows or missing historical daily aggregates. Existing personal food diary/manual input sheets are retained.

Each file includes schema version, spreadsheet ID, source, timezone, day, operation, headers and rows. A day can have any number of files; 2,000 rows is a file chunk size, not a daily limit. Days use the spreadsheet timezone. Unrecognized dates remain in `undated` files instead of being discarded.

Archives are immutable event/snapshot history, **not one deduplicated current-state export**. Exact repeated parts reuse SHA-256-named files; revised uploads remain separate events with their original SyncedAt fields. Preserve all parts. Historical snapshots preserve values as they existed during migration. Deletion IDs are retained as separate events. A restored current-state view must apply source IDs, synchronization times and deletion events rather than concatenate rows or sum all files. This PR does not ship a current-state replay tool.

A failed readback propagates to the uploader and prevents source deletion. Migration always rereads the current leading block, verifies the stored gzip file, then deletes that block under the same script lock. An interrupted run can retry safely without trusting a row cursor. Old downsampling jobs are disabled when archival is enabled so original high-frequency samples are not dropped.

## Known integration checks before rollout

- Owner must grant Drive and trigger permissions; these cannot be granted by repository changes.
- Check explicit scopes in the deployed manifest (the deploy workflow intentionally preserves the live manifest).
- Inspect `migration.blocked` and migrate report formulas/charts as necessary. Source formula cells are preserved rather than replaced with values.
- Device/source panels that read detailed tables need a future daily-source view once history has drained; existing daily source names remain in `HC_Дни`.
- Deletion events lack dates, so all daily rows are marked incomplete. Automatic correction is bounded by the existing client repair window; older days require explicit resync from the source. No old aggregate is silently recomputed from insufficient data.
- Data already bucketed by the Android client cannot be expanded back to original samples.
- Preserve the Drive folder. Do not disable archival after partially draining history without a deliberate rollback plan.

## Verification

`node --test tests/drive-archive.test.cjs`

Eight mocked Apps Script tests cover timezone grouping, exact values, retry deduplication, large days, readback failures, raw-grid bypass, completion ordering, migration retries and report dependency blocking. These tests are not a live Google permission/deployment test.
