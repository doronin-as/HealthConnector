package ru.doronin.healthconnector

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class PermissionGateActivity : AppCompatActivity() {

    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        continueLaunch()
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        requestHealthPermissionsAndContinue()
    }

    private val configPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(ConfigAutoRestore.PREF_AUTO_RESTORE_ATTEMPTED, true).apply()

        if (uri == null) {
            requestRuntimePermissionsAndContinue()
            return@registerForActivityResult
        }

        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        lifecycleScope.launch {
            val result = ConfigAutoRestore.importFromUri(this@PermissionGateActivity, uri)
            Toast.makeText(
                this@PermissionGateActivity,
                result.message,
                if (result.restored) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
            ).show()
            requestRuntimePermissionsAndContinue()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreConfigurationThenContinue()
    }

    private fun restoreConfigurationThenContinue() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val endpoint = prefs.getString("endpoint", "").orEmpty().trim()
        val attempted = prefs.getBoolean(ConfigAutoRestore.PREF_AUTO_RESTORE_ATTEMPTED, false)

        if (endpoint.isNotBlank() || attempted) {
            requestRuntimePermissionsAndContinue()
            return
        }

        lifecycleScope.launch {
            val result = ConfigAutoRestore.tryAutoRestore(this@PermissionGateActivity)
            if (result.restored) {
                Toast.makeText(
                    this@PermissionGateActivity,
                    result.message,
                    Toast.LENGTH_SHORT
                ).show()
                requestRuntimePermissionsAndContinue()
            } else {
                // Scoped storage can hide a config that survived a reinstall.
                // Mark the prompt before opening it so process recreation cannot
                // create an endless picker loop.
                prefs.edit()
                    .putBoolean(ConfigAutoRestore.PREF_AUTO_RESTORE_ATTEMPTED, true)
                    .apply()
                Toast.makeText(
                    this@PermissionGateActivity,
                    "JSON автоматически не найден. Выбери healthconnector-config.json — это потребуется только один раз.",
                    Toast.LENGTH_LONG
                ).show()
                configPickerLauncher.launch(
                    arrayOf("application/json", "text/json", "text/plain")
                )
            }
        }
    }

    private fun requestRuntimePermissionsAndContinue() {
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
            MiScaleScanner.requiredRuntimePermissions().forEach { permission ->
                if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) add(permission)
            }
        }
        if (missing.isNotEmpty()) {
            permissionsLauncher.launch(missing.toTypedArray())
        } else {
            requestHealthPermissionsAndContinue()
        }
    }

    private fun requestHealthPermissionsAndContinue() {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            continueLaunch()
            return
        }
        val client = HealthConnectClient.getOrCreate(this)
        lifecycleScope.launch {
            val granted = runCatching { client.permissionController.getGrantedPermissions() }
                .getOrElse {
                    continueLaunch()
                    return@launch
                }
            val requested = HealthConnectPermissionSet.requestPermissions(client)
            val missing = requested - granted
            if (missing.isEmpty()) continueLaunch() else healthPermissionLauncher.launch(missing)
        }
    }

    private fun continueLaunch() {
        // Do not force the scale profile screen before Apps Script URL/token exist.
        // The main Dashboard can first load profile data from the Google Sheet and
        // the scale settings screen then uses those values automatically.
        MiScaleScanner.start(this)
        startActivity(
            Intent(this, StreamingMainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
    }
}
