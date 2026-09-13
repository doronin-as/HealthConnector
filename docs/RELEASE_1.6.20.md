# HealthConnector 1.6.20 — Xiaomi Scale BLE discovery fix

## Problem found on real XMTZC05HM hardware

HealthConnector 1.6.18/1.6.19 started the BLE scanner with `ScanFilter.setServiceUuid(0x181B)` only. Xiaomi Mi Body Composition Scale 2 commonly places its 13-byte measurement in BLE **Service Data (AD type 0x16)** keyed by UUID `0x181B`, without necessarily putting `0x181B` into Android's separate advertised-service-UUID list.

As a result, Android could discard every scale advertisement before `MiScaleScanReceiver` received it. The UI then stayed at `Весы ещё не привязаны` and `HC_Состав_тела` was never created.

## Changes in 1.6.20

- BLE discovery now primarily filters by **Service Data UUID `0000181b-0000-1000-8000-00805f9b34fb`**.
- Added compatibility fallback filters for advertised service UUID `0x181B` and local device name `MIBFS`.
- Before starting a scan HealthConnector stops the previous PendingIntent scan, ensuring updated filters are applied immediately after an app update.
- Scanner startup now stores a readable state/error instead of silently returning `false`.
- Added diagnostics for missing Bluetooth permissions, disabled Bluetooth, unavailable adapter/scanner, and Android BLE scan error codes.
- `MiScaleScanReceiver` now records the last seen BLE device, address, RSSI, raw `0x181B` payload, packet time, and latest parsed measurement state.
- Intermediate unstable measurements are explicitly shown as expected (`ожидаю стабильное измерение`) instead of looking like a failure.
- Scale settings now refresh binding and BLE diagnostics every second while the screen is open.
- Added **`Проверить BLE-сканер сейчас`** to restart scanning manually and immediately expose scanner state.
- The screen shows whether runtime Bluetooth permissions are available, whether the scanner started, whether any BLE packet was seen, and the last accepted weight/impedance.
- After a stable packet is accepted, the first compatible XMTZC05HM is bound by Bluetooth address and the existing upload worker sends the measurement to Apps Script / `HC_Состав_тела`.
- Android version bumped to **1.6.20**, versionCode **34**.

## Validation

- Pull request #21 passed security invariants, release unit tests, R8/release APK compilation.
- A dedicated side-by-side `HC 1.6.20 TEST` APK was built with a one-time temporary signing key.
- The TEST package ID is `ru.doronin.healthconnector.side1620`, so it can be installed next to previous HealthConnector builds.

## Real-device test procedure

1. Install `HC 1.6.20 TEST`.
2. Configure the Apps Script URL and API token for this side-by-side package.
3. Open `Настройки → Умные весы Xiaomi`.
4. Confirm the switch `Считывать весы автоматически` is enabled and profile values are present.
5. Tap `Сохранить и включить` or `Проверить BLE-сканер сейчас`.
6. Diagnostics should show `разрешения ✓` and `BLE-сканер запущен`.
7. Stand barefoot on the XMTZC05HM until the reading stabilizes.
8. While weighing, diagnostics should start showing recent BLE packets. A successful stable reading should show weight and impedance and then bind the scale address.
9. Apps Script should create/update `HC_Состав_тела`; the measurement should also update `HC_Дни.WeightKg` and mirror raw weight to `HC_Измерения`.

If diagnostics show no BLE packets at all, the next investigation should focus on Android/Xiaomi BLE background restrictions or the exact advertisement format seen by the phone. If packets appear but no stable measurement is accepted, capture the displayed raw `0x181B` packet and adjust the parser against the real firmware payload.
