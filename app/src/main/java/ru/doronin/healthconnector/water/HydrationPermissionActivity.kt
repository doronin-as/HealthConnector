package ru.doronin.healthconnector.water

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController

/**
 * Asks only for the optional hydration permissions. They are deliberately not part of
 * HealthConnectPermissionSet, so declining them never blocks the main sync.
 */
class HydrationPermissionActivity : ComponentActivity() {
    private val request = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        val ok = granted.containsAll(WaterRepository.healthConnectPermissions)
        Toast.makeText(
            this,
            if (ok) "Вода будет записываться и в Health Connect" else "Без Health Connect: вода сохраняется в приложении и таблице",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            Toast.makeText(this, "Health Connect недоступен на этом устройстве", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (savedInstanceState == null) request.launch(WaterRepository.healthConnectPermissions)
    }
}
