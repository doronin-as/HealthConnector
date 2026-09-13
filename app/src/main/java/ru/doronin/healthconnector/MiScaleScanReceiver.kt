package ru.doronin.healthconnector

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.ParcelUuid
import java.time.Instant
import java.time.ZoneId

class MiScaleScanReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MiScaleScanner.ACTION_SCAN_RESULT) return
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

        val error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (error != 0) {
            prefs.edit()
                .putString(MiScaleScanner.PREF_SCAN_STATE, "Ошибка BLE scan: $error")
                .putString(MiScaleScanner.PREF_LAST_ERROR, "BLE scan error code $error")
                .apply()
            return
        }

        val results = intent.getParcelableArrayListExtra<ScanResult>(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT).orEmpty()
        val serviceUuid = ParcelUuid.fromString(MiScaleAdvertisementParser.BODY_COMPOSITION_SERVICE_UUID)
        if (!prefs.getBoolean(MiScaleScanner.PREF_ENABLED, false)) return

        for (result in results) {
            val address = runCatching { result.device.address }.getOrDefault("")
            val name = result.scanRecord?.deviceName.orEmpty()
            prefs.edit()
                .putLong(MiScaleScanner.PREF_LAST_PACKET_AT, System.currentTimeMillis())
                .putString(MiScaleScanner.PREF_LAST_PACKET_ADDRESS, address)
                .putString(MiScaleScanner.PREF_LAST_PACKET_NAME, name)
                .putInt(MiScaleScanner.PREF_LAST_PACKET_RSSI, result.rssi)
                .apply()

            val payload = result.scanRecord?.getServiceData(serviceUuid)
            if (payload == null) {
                prefs.edit().putString(
                    MiScaleScanner.PREF_SCAN_STATE,
                    "BLE-пакет найден${if (name.isBlank()) "" else " ($name)"}, но без Service Data 0x181B"
                ).apply()
                continue
            }

            prefs.edit()
                .putString(MiScaleScanner.PREF_LAST_PACKET_HEX, payload.toHex())
                .putString(MiScaleScanner.PREF_SCAN_STATE, "Получен пакет весов 0x181B (${payload.size} байт)")
                .apply()

            val measurement = MiScaleAdvertisementParser.parsePayload(payload, address)
            if (measurement == null) {
                // Unstable intermediate readings are expected while the number on the
                // scale is still changing. Keep the raw packet in diagnostics instead
                // of treating it as a fatal scanner error.
                prefs.edit().putString(
                    MiScaleScanner.PREF_LAST_MEASUREMENT,
                    "Пакет получен, ожидаю стабильное измерение"
                ).apply()
                continue
            }

            var boundAddress = prefs.getString(MiScaleScanner.PREF_BOUND_ADDRESS, "").orEmpty()
            if (boundAddress.isBlank()) {
                boundAddress = measurement.deviceAddress
                prefs.edit().putString(MiScaleScanner.PREF_BOUND_ADDRESS, boundAddress).apply()
            }
            if (!measurement.deviceAddress.equals(boundAddress, ignoreCase = true)) continue

            val impedanceText = measurement.impedanceOhm?.let { "$it Ω" } ?: "нет"
            prefs.edit()
                .putString(
                    MiScaleScanner.PREF_LAST_MEASUREMENT,
                    "${measurement.weightKg} кг · импеданс: $impedanceText"
                )
                .putString(MiScaleScanner.PREF_SCAN_STATE, "Стабильное измерение принято")
                .apply()

            val profile = MiScaleProfile.fromPrefs(context)
            val metrics = if (profile != null && measurement.impedanceOhm != null) {
                val date = Instant.ofEpochMilli(measurement.measuredAtEpochMs)
                    .atZone(ZoneId.systemDefault()).toLocalDate()
                MiScaleBodyMetrics.calculate(measurement.weightKg, measurement.impedanceOhm, profile, date)
            } else null

            MiScaleUploadWorker.enqueue(context, measurement, metrics)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
