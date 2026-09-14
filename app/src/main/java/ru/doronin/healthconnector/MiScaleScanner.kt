package ru.doronin.healthconnector

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
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

    const val PREF_SCAN_STATE = "scale_scan_state"
    const val PREF_SCAN_STARTED_AT = "scale_scan_started_at"
    const val PREF_LAST_PACKET_AT = "scale_last_packet_at"
    const val PREF_LAST_PACKET_ADDRESS = "scale_last_packet_address"
    const val PREF_LAST_PACKET_NAME = "scale_last_packet_name"
    const val PREF_LAST_PACKET_RSSI = "scale_last_packet_rssi"
    const val PREF_LAST_PACKET_HEX = "scale_last_packet_hex"
    const val PREF_LAST_MEASUREMENT = "scale_last_measurement"
    const val PREF_LAST_ERROR = "scale_last_error"

    const val ACTION_SCAN_RESULT = "ru.doronin.healthconnector.MI_SCALE_SCAN_RESULT"
    private const val REQUEST_CODE = 1811

    /** Permissions required by Android before a BLE scan can be registered. */
    fun requiredRuntimePermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /**
     * Permissions requested by the interactive scale finder.
     *
     * Android 12+ normally gates BLE discovery with BLUETOOTH_SCAN. Some OEM
     * stacks, including Xiaomi/MIUI builds, still return zero scan callbacks
     * unless location permission is also granted. We therefore request location
     * explicitly for this diagnostic/manual discovery path instead of claiming
     * neverForLocation in the manifest.
     */
    fun finderRuntimePermissions(): Array<String> = buildList {
        addAll(requiredRuntimePermissions())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }.distinct().toTypedArray()

    fun hasPermissions(context: Context): Boolean = requiredRuntimePermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun hasBluetoothScanPermission(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        else -> hasLocationPermission(context)
    }

    fun hasBluetoothConnectPermission(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        else -> true
    }

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Starts a persistent PendingIntent based BLE scan.
     *
     * Mi Body Composition Scale 2 (XMTZC05HM) usually publishes UUID 0x181B as
     * AD type 0x16 (Service Data). It does not have to include 0x181B in the
     * separate advertised service UUID list. Therefore filtering only with
     * setServiceUuid() can silently miss every packet on some phones/firmwares.
     */
    fun start(context: Context): Boolean {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

        fun fail(reason: String): Boolean {
            prefs.edit()
                .putString(PREF_SCAN_STATE, "Ошибка: $reason")
                .putString(PREF_LAST_ERROR, reason)
                .apply()
            return false
        }

        if (!prefs.getBoolean(PREF_ENABLED, false)) return fail("автосчитывание выключено")
        if (!hasPermissions(app)) return fail("нет разрешения Bluetooth")

        val manager = app.getSystemService(BluetoothManager::class.java)
            ?: return fail("BluetoothManager недоступен")
        val adapter = manager.adapter ?: return fail("Bluetooth-адаптер недоступен")
        if (!adapter.isEnabled) return fail("Bluetooth выключен")
        val scanner = adapter.bluetoothLeScanner ?: return fail("BLE-сканер недоступен")
        val serviceUuid = ParcelUuid.fromString(MiScaleAdvertisementParser.BODY_COMPOSITION_SERVICE_UUID)

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceData(serviceUuid, byteArrayOf())
                .build(),
            ScanFilter.Builder()
                .setServiceUuid(serviceUuid)
                .build(),
            ScanFilter.Builder()
                .setDeviceName("MIBFS")
                .build()
        )

        return runCatching {
            runCatching { scanner.stopScan(scanPendingIntent(app)) }
            val startCode = scanner.startScan(
                filters,
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .build(),
                scanPendingIntent(app)
            )
            if (startCode != 0) {
                throw IllegalStateException("BLE scan start code $startCode")
            }
            prefs.edit()
                .putString(PREF_SCAN_STATE, "BLE-сканер запущен")
                .putLong(PREF_SCAN_STARTED_AT, System.currentTimeMillis())
                .remove(PREF_LAST_ERROR)
                .apply()
            true
        }.getOrElse { error ->
            fail(error.message ?: error.javaClass.simpleName)
        }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!hasPermissions(app)) return
        val scanner = app.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        runCatching { scanner.stopScan(scanPendingIntent(app)) }
        prefs.edit().putString(PREF_SCAN_STATE, "BLE-сканер остановлен").apply()
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
