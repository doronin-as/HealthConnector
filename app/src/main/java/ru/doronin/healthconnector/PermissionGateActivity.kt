package ru.doronin.healthconnector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        lifecycleScope.launchWhenStarted {
            val granted = runCatching { client.permissionController.getGrantedPermissions() }
                .getOrElse {
                    continueLaunch()
                    return@launchWhenStarted
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
