package ru.doronin.healthconnector.floors

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch
import ru.doronin.healthconnector.source.PhoneSensorDataSource
import java.util.Locale

class PhoneFloorSettingsActivity : AppCompatActivity() {
    private lateinit var enabledSwitch: SwitchMaterial
    private lateinit var statusText: TextView
    private lateinit var healthConnectText: TextView
    private var suppressSwitchCallback = false
    private var pendingEnable = false
    private var healthWriteGranted: Boolean? = null

    private val activityPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (pendingEnable && granted) {
            pendingEnable = false
            enableTracking()
        } else {
            pendingEnable = false
            render()
        }
    }

    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        refreshHealthConnectPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Этажи по датчикам телефона"
        setContentView(buildContent())
        render()
        refreshHealthConnectPermission()
    }

    override fun onResume() {
        super.onResume()
        render()
        refreshHealthConnectPermission()
    }

    private fun buildContent(): ScrollView {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        scroll.addView(container)

        container.addView(TextView(this).apply {
            text = "Датчики телефона"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        container.addView(TextView(this).apply {
            text = "Барометр измеряет относительное изменение высоты, а датчик шагов подтверждает, что подъём был пешком. Лифт без шагов не засчитывается."
            textSize = 14f
            alpha = 0.76f
            setPadding(0, dp(8), 0, dp(18))
        })

        enabledSwitch = SwitchMaterial(this).apply {
            text = "Считать этажи в фоне"
            textSize = 17f
            setOnCheckedChangeListener { _, checked ->
                if (suppressSwitchCallback) return@setOnCheckedChangeListener
                if (checked) requestEnable() else disableTracking()
            }
        }
        container.addView(enabledSwitch)

        statusText = TextView(this).apply {
            textSize = 15f
            setLineSpacing(0f, 1.18f)
            setPadding(0, dp(16), 0, dp(12))
        }
        container.addView(statusText)

        healthConnectText = TextView(this).apply {
            textSize = 14f
            alpha = 0.78f
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(4), 0, dp(12))
        }
        container.addView(healthConnectText)

        container.addView(Button(this).apply {
            text = "Разрешить запись в Health Connect"
            setOnClickListener {
                if (HealthConnectClient.getSdkStatus(this@PhoneFloorSettingsActivity) == HealthConnectClient.SDK_AVAILABLE) {
                    healthPermissionLauncher.launch(FloorHealthConnectWriter.writePermissions)
                } else {
                    Toast.makeText(this@PhoneFloorSettingsActivity, "Health Connect недоступен", Toast.LENGTH_SHORT).show()
                }
            }
        })

        container.addView(Button(this).apply {
            text = "Сбросить этажи за сегодня"
            setOnClickListener {
                PhoneFloorStore.resetToday(this@PhoneFloorSettingsActivity)
                render()
            }
        })

        container.addView(TextView(this).apply {
            text = "Архитектура источников модульная: этот экран управляет только PhoneSensors. Носимые устройства и сторонние сервисы можно добавлять отдельными источниками без переделки алгоритма телефона."
            textSize = 13f
            alpha = 0.66f
            setPadding(0, dp(18), 0, 0)
        })
        return scroll
    }

    private fun requestEnable() {
        if (!PhoneSensorDataSource.hasPressureSensor(this)) {
            Toast.makeText(this, "В телефоне не найден барометр", Toast.LENGTH_LONG).show()
            render()
            return
        }
        if (!PhoneSensorDataSource.hasStepDetector(this)) {
            Toast.makeText(this, "В телефоне не найден датчик шагов", Toast.LENGTH_LONG).show()
            render()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !PhoneSensorDataSource.activityPermissionGranted(this)
        ) {
            pendingEnable = true
            activityPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            return
        }
        enableTracking()
    }

    private fun enableTracking() {
        PhoneFloorStore.setEnabled(this, true)
        PhoneSensorDataSource.start(this)
        render()
        lifecycleScope.launch {
            val available = HealthConnectClient.getSdkStatus(this@PhoneFloorSettingsActivity) == HealthConnectClient.SDK_AVAILABLE
            if (available && !FloorHealthConnectWriter.hasAllPermissions(this@PhoneFloorSettingsActivity)) {
                healthPermissionLauncher.launch(FloorHealthConnectWriter.writePermissions)
            }
        }
    }

    private fun disableTracking() {
        pendingEnable = false
        PhoneFloorStore.setEnabled(this, false)
        PhoneSensorDataSource.stop(this)
        render()
    }

    private fun render() {
        val sourceStatus = PhoneSensorDataSource.status(this)
        val snapshot = PhoneFloorStore.snapshot(this)
        suppressSwitchCallback = true
        enabledSwitch.isChecked = snapshot.enabled
        enabledSwitch.isEnabled = sourceStatus.missingRequirements.none {
            it.startsWith("Барометр") || it.startsWith("Датчик шагов")
        }
        suppressSwitchCallback = false

        val elevation = String.format(Locale.getDefault(), "%.0f", snapshot.elevationMeters)
        statusText.text = buildString {
            append(if (PhoneSensorDataSource.hasPressureSensor(this@PhoneFloorSettingsActivity)) "Барометр: ✓" else "Барометр: —")
            append("\n")
            append(if (PhoneSensorDataSource.hasStepDetector(this@PhoneFloorSettingsActivity)) "Датчик шагов: ✓" else "Датчик шагов: —")
            append("\nФизическая активность: ")
            append(if (PhoneSensorDataSource.activityPermissionGranted(this@PhoneFloorSettingsActivity)) "✓" else "нужно разрешение")
            append("\n\nСегодня: ${snapshot.floors} этажей · +$elevation м")
            PhoneFloorStore.formatLastDetection(snapshot)?.let { append("\nПоследний подъём: $it") }
            snapshot.lastHealthConnectError?.let { append("\nПоследняя ошибка Health Connect: $it") }
        }

        healthConnectText.text = when {
            HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE ->
                "Health Connect: недоступен. Локальный подсчёт всё равно работает."
            healthWriteGranted == true ->
                "Health Connect: запись этажей и набора высоты разрешена. Отображение этих записей зависит от приложения, которое читает Health Connect."
            healthWriteGranted == false ->
                "Health Connect: нет разрешения на запись этажей/высоты. Данные пока сохраняются только локально."
            else -> "Health Connect: проверяю разрешения…"
        }
    }

    private fun refreshHealthConnectPermission() {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            healthWriteGranted = false
            render()
            return
        }
        lifecycleScope.launch {
            healthWriteGranted = runCatching {
                FloorHealthConnectWriter.hasAllPermissions(this@PhoneFloorSettingsActivity)
            }.getOrDefault(false)
            render()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
