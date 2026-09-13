package ru.doronin.healthconnector

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat

object MiScaleScanner {
    const val PREF_ENABLED = "scale_enabled"
    const val PREF_BOUND_ADDRESS = "scale_bound_address"
    const val PREF_SETUP_PROMPTED = "scale_setup_prompted"
    const val ACTION_SCAN_RESULT = "ru.doronin.healthconnector.MI_SCALE_SCAN_RESULT"
    private const val REQUEST_CODE = 1811

    fun requiredRuntimePermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasPermissions(context: Context): Boolean = requiredRuntimePermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun start(context: Context): Boolean {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_ENABLED, false) || !hasPermissions(app)) return false

        val manager = app.getSystemService(BluetoothManager::class.java) ?: return false
        val adapter = manager.adapter ?: return false
        if (!adapter.isEnabled) return false
        val scanner = adapter.bluetoothLeScanner ?: return false

        return runCatching {
            scanner.startScan(
                listOf(
                    ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid.fromString(MiScaleAdvertisementParser.BODY_COMPOSITION_SERVICE_UUID))
                        .build()
                ),
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .build(),
                scanPendingIntent(app)
            )
            true
        }.getOrDefault(false)
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        if (!hasPermissions(app)) return
        val scanner = app.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        runCatching { scanner.stopScan(scanPendingIntent(app)) }
    }

    private fun scanPendingIntent(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, MiScaleScanReceiver::class.java).apply {
                action = ACTION_SCAN_RESULT
                setPackage(context.packageName)
            },
            flags
        )
    }
}
