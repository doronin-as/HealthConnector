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
        MiScaleScanner.start(this)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!prefs.getBoolean(MiScaleScanner.PREF_SETUP_PROMPTED, false)) {
            prefs.edit().putBoolean(MiScaleScanner.PREF_SETUP_PROMPTED, true).apply()
            startActivity(
                Intent(this, MiScaleSettingsActivity::class.java)
                    .putExtra(MiScaleSettingsActivity.EXTRA_OPEN_MAIN_AFTER, true)
            )
        } else {
            startActivity(
                Intent(this, StreamingMainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        }
        finish()
    }
}
