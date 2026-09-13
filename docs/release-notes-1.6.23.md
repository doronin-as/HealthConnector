# HealthConnector 1.6.23

## BLE permission contract fix

- Fixed Android 12+ BLE discovery permission declaration by adding `android:usesPermissionFlags="neverForLocation"` to `BLUETOOTH_SCAN`.
- Kept `ACCESS_FINE_LOCATION` limited to Android 11 and lower because HealthConnector does not derive physical location from BLE scan results.
- This closes a permission-contract gap where Nearby Devices permission could be granted while Android still returned zero BLE scan callbacks.
- Added explicit validation of the return code from the persistent `BluetoothLeScanner.startScan(..., PendingIntent)` call instead of assuming registration succeeded.
- Scanner registration errors are now persisted in scale diagnostics instead of being reported as a successful scan start.
- Bumped Android version to `1.6.23` / versionCode `37`.

## Test target

The primary regression test is the forced Xiaomi Scale finder. On Android 12+ it should now receive raw BLE callbacks after Nearby Devices is granted. The XMTZC05HM can then be recognized from its Body Composition service data/name as before.
