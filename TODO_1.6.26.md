# HealthConnector 1.6.26 — TODO / Integrity Recovery

Release goal: make synchronization loss-resistant, diagnosable and recoverable before adding new metrics.

## P0 — data integrity

- [ ] Replace binary `SyncComplete` semantics with explicit per-day/per-type sync state: COMPLETE / PARTIAL / WAITING_FOR_SOURCE / PERMISSION_BLOCKED / ERROR.
- [ ] Introduce tri-state metric transport: unavailable, value, authoritative-empty. Do not infer "0" from "no records".
- [ ] Make authoritative zero/delete updates possible without allowing transient empty reads to erase good historical values.
- [ ] On expired Health Connect changes token, mark the readable lookback window for reconciliation instead of silently starting a new token.
- [ ] Add generation/reconciliation metadata so a completed day can prove which record types were read.
- [ ] Rework deletion reconciliation so stale raw rows and day aggregates are repaired together.
## P0 — configuration recovery

- [x] Add first-launch automatic search for `healthconnector-config*.json` in locations readable by the current Android installation.
- [x] Search persisted document URIs, app-accessible directories, legacy readable Downloads/Documents paths, and MediaStore Downloads.
- [x] Select the newest valid config candidate and validate `format`, `version`, HTTPS endpoint, sync window, and background-sync flag before applying it.
- [x] Reuse the same parser for manual config import.
- [x] Preserve secure token storage; modern exported JSON does not write the API token in plaintext.
- [x] Support legacy/user-created config JSON containing `token` and migrate it into `SecureTokenStore`.
- [x] On Android scoped-storage denial after reinstall, open `ACTION_OPEN_DOCUMENT` once as a fallback instead of requesting broad all-files access.
- [ ] Add an optional encrypted recovery package for endpoint + API token that can survive reinstall without plaintext secrets.
- [ ] Add a visible first-launch report showing where the config was found and which fields were restored.

## P1 — Health Connect correctness

- [ ] Remove hard-coded Xiaomi > Google Fit > all-others source priority.
- [ ] Define metric-specific source policy; cumulative metrics should prefer Health Connect Aggregate API.
- [~] Treat Fitbit as a first-class source: interim Fitbit-first priority is implemented; metric-specific policy still remains.
- [ ] Split core vs optional Health Connect permissions; optional metrics must not block steps/sleep/heart synchronization.
- [ ] Replace `safeReadAll(): List<T>` with structured result: Success(records), PermissionDenied, Error.
- [~] Improve sleep diagnostics: raw record count, post-filter count and source packages are implemented; record offsets still remain.
- [x] Fix sleep-stage aggregation: accumulate duration at millisecond precision and round only after summation.
- [ ] Review historical date attribution against record zone offsets and DST/travel cases.
- [ ] Remove duplicate permission definitions from `StreamingMainActivity`.
- [ ] Remove dead direct `synchronize()` path from `StreamingMainActivity`.

## P1 — backend / security

- [ ] Replace repeated full-column raw-ID scans with indexed/batched/date-partitioned reconciliation.
- [ ] Replace O(rows × deletedIds) deletion matching with a bounded indexed strategy.
- [ ] Centralize all HTTP calls in one API client with one retry/error contract.
- [ ] On endpoint origin change, require explicit trust/re-authentication or clear the retained API token.
- [ ] Apply `sheetSafeExternalText_` to all external Health Connect text fields before Sheets writes.
- [ ] Validate payload sizes per action and reject malformed IDs/dates before mutation.

## P2 — maintainability and UX

- [ ] Split `HealthSyncStreamer` into reader / processors / source resolver / transport / reconciliation components.
- [ ] Add explicit "repair data" UI and show why a day is partial.
- [ ] Rename local-only "reset floors" or delete the app's own HC floor/elevation records when a real reset is requested.
- [ ] Stream FatSecret input with a byte limit instead of reading the full file before checking size.
- [ ] Remove obsolete one-time patch/fix GitHub Actions workflows with `contents: write`.
- [ ] Remove obsolete patch scripts after confirming production state.
- [ ] Add production signing secret/verification if stable APK publishing is expected from CI.

## Tests required before release

- [ ] Health Connect: empty vs permission denied vs real zero.
- [ ] Health Connect: expired changes token triggers reconciliation.
- [ ] Health Connect: deletion changes clear/recompute affected day.
- [ ] Multiple sources: Fitbit + Google Fit + Xiaomi without data loss.
- [ ] Sleep: 30-second stage segments aggregate correctly.
- [ ] Sleep: overnight session and DST/travel timezone cases.
- [ ] Config recovery: newest valid JSON wins when multiple readable candidates exist.
- [ ] Config recovery: invalid/malformed/wrong-format JSON is ignored safely.
- [ ] Config recovery: legacy token is migrated to `SecureTokenStore`.
- [ ] Config recovery: existing configured installation is never overwritten automatically.
- [ ] Apps Script: formula-injection strings remain literal.
- [ ] Apps Script: large raw table performance regression test.
- [ ] End-to-end: sparse day is repaired without erasing previously good values.

## Release gate

1. All P0 items complete.
2. No failing release unit tests.
3. Apps Script syntax/invariant checks pass.
4. TEST APK successfully synchronizes a recent Fitbit day containing sleep + heart data.
5. `HC_Дни`, `HC_Сон`, `HC_Измерения` remain idempotent after two consecutive syncs.
