package ru.doronin.healthconnector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class PermissionGateActivity : AppCompatActivity() {

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        continueLaunch()
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
        if (missing.isNotEmpty()) permissionsLauncher.launch(missing.toTypedArray()) else continueLaunch()
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
