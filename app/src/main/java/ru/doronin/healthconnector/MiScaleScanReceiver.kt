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
        val error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (error != 0) return
        val results = intent.getParcelableArrayListExtra<ScanResult>(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT).orEmpty()
        val serviceUuid = ParcelUuid.fromString(MiScaleAdvertisementParser.BODY_COMPOSITION_SERVICE_UUID)
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(MiScaleScanner.PREF_ENABLED, false)) return

        for (result in results) {
            val payload = result.scanRecord?.getServiceData(serviceUuid) ?: continue
            val measurement = MiScaleAdvertisementParser.parsePayload(payload, result.device.address) ?: continue
            var boundAddress = prefs.getString(MiScaleScanner.PREF_BOUND_ADDRESS, "").orEmpty()
            if (boundAddress.isBlank()) {
                boundAddress = measurement.deviceAddress
                prefs.edit().putString(MiScaleScanner.PREF_BOUND_ADDRESS, boundAddress).apply()
            }
            if (!measurement.deviceAddress.equals(boundAddress, ignoreCase = true)) continue

            val profile = MiScaleProfile.fromPrefs(context)
            val metrics = if (profile != null && measurement.impedanceOhm != null) {
                val date = Instant.ofEpochMilli(measurement.measuredAtEpochMs)
                    .atZone(ZoneId.systemDefault()).toLocalDate()
                MiScaleBodyMetrics.calculate(measurement.weightKg, measurement.impedanceOhm, profile, date)
            } else null

            MiScaleUploadWorker.enqueue(context, measurement, metrics)
        }
    }
}
