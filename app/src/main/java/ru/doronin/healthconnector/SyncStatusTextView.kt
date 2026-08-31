package ru.doronin.healthconnector

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class SyncStatusTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : MaterialTextView(context, attrs, defStyleAttr) {

    private val prefs by lazy { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private var viewScope: CoroutineScope? = null

    private val expectedPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(RespiratoryRateRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
        HealthPermission.getReadPermission(SkinTemperatureRecord::class),
        HealthPermission.getReadPermission(ElevationGainedRecord::class),
        HealthPermission.getReadPermission(FloorsClimbedRecord::class),
        HealthPermission.getReadPermission(SpeedRecord::class),
        HealthPermission.getReadPermission(StepsCadenceRecord::class),
        HealthPermission.getReadPermission(CyclingPedalingCadenceRecord::class),
        HealthPermission.getReadPermission(PowerRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class)
    )

    init {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                post { consumeStatus(s?.toString().orEmpty()) }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewScope?.cancel()
        viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        post {
            refreshDashboard()
            refreshHealthConnectState()
            consumeStatus(text?.toString().orEmpty())
        }
    }

    override fun onDetachedFromWindow() {
        viewScope?.cancel()
        viewScope = null
        super.onDetachedFromWindow()
    }

    private fun consumeStatus(message: String) {
        val busy = message.startsWith("Читаю") || message.startsWith("Отправляю")
        rootView.findViewById<LinearProgressIndicator>(R.id.syncProgress)?.visibility =
            if (busy) View.VISIBLE else View.GONE

        val syncMatch = Regex(
            "Синхронизация завершена: дней (\\d+), тренировок (\\d+), измерений (\\d+), источников (\\d+)"
        ).find(message)

        if (syncMatch != null) {
            val (days, workouts, measurements, sources) = syncMatch.destructured
            prefs.edit()
                .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                .putInt(KEY_LAST_DAYS, days.toInt())
                .putInt(KEY_LAST_WORKOUTS, workouts.toInt())
                .putInt(KEY_LAST_MEASUREMENTS, measurements.toInt())
                .putInt(KEY_LAST_SOURCES, sources.toInt())
                .putBoolean(KEY_SHEETS_OK, true)
                .apply()
        }

        if (message.startsWith("CSV импортирован")) {
            prefs.edit().putBoolean(KEY_SHEETS_OK, true).apply()
        }

        if (message.startsWith("Все доступные разрешения Health Connect выданы")) {
            prefs.edit()
                .putInt(KEY_HC_GRANTED, expectedPermissions.size)
                .putInt(KEY_HC_TOTAL, expectedPermissions.size)
                .apply()
        } else {
            Regex("Выдано разрешений: (\\d+)/(\\d+)").find(message)?.let { match ->
                prefs.edit()
                    .putInt(KEY_HC_GRANTED, match.groupValues[1].toInt())
                    .putInt(KEY_HC_TOTAL, match.groupValues[2].toInt())
                    .apply()
            }
        }

        refreshDashboard()
    }

    private fun refreshDashboard() {
        val root = rootView
        val lastSync = prefs.getLong(KEY_LAST_SYNC, 0L)
        val dateText = if (lastSync > 0L) {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastSync))
        } else {
            "Ещё не было"
        }

        root.findViewById<TextView>(R.id.lastSyncValue)?.text = dateText
        root.findViewById<TextView>(R.id.syncDaysValue)?.text = prefs.getInt(KEY_LAST_DAYS, 0).toString()
        root.findViewById<TextView>(R.id.syncWorkoutsValue)?.text = prefs.getInt(KEY_LAST_WORKOUTS, 0).toString()
        root.findViewById<TextView>(R.id.syncMeasurementsValue)?.text = prefs.getInt(KEY_LAST_MEASUREMENTS, 0).toString()
        root.findViewById<TextView>(R.id.syncSourcesValue)?.text = prefs.getInt(KEY_LAST_SOURCES, 0).toString()

        val endpointConfigured = !prefs.getString("endpoint", "").isNullOrBlank()
        val tokenConfigured = !prefs.getString("token", "").isNullOrBlank()
        val sheetsOk = prefs.getBoolean(KEY_SHEETS_OK, false)
        root.findViewById<TextView>(R.id.sheetsStatus)?.text = when {
            sheetsOk -> "✓ Google Sheets подключён"
            endpointConfigured && tokenConfigured -> "● Google Sheets настроен"
            else -> "! Google Sheets не настроен"
        }

        val granted = prefs.getInt(KEY_HC_GRANTED, -1)
        val total = prefs.getInt(KEY_HC_TOTAL, expectedPermissions.size)
        root.findViewById<TextView>(R.id.healthConnectStatus)?.text = when {
            HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE -> "✕ Health Connect недоступен"
            granted == total && total > 0 -> "✓ Health Connect подключён"
            granted >= 0 -> "● Health Connect: $granted/$total разрешений"
            else -> "● Health Connect доступен"
        }
    }

    private fun refreshHealthConnectState() {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            prefs.edit().putInt(KEY_HC_GRANTED, 0).putInt(KEY_HC_TOTAL, expectedPermissions.size).apply()
            refreshDashboard()
            return
        }

        val scope = viewScope ?: return
        scope.launch {
            runCatching {
                val client = HealthConnectClient.getOrCreate(context)
                val grantedPermissions = client.permissionController.getGrantedPermissions()
                expectedPermissions.count { it in grantedPermissions }
            }.onSuccess { grantedCount ->
                prefs.edit()
                    .putInt(KEY_HC_GRANTED, grantedCount)
                    .putInt(KEY_HC_TOTAL, expectedPermissions.size)
                    .apply()
                refreshDashboard()
            }
        }
    }

    companion object {
        private const val KEY_LAST_SYNC = "dashboard_last_sync"
        private const val KEY_LAST_DAYS = "dashboard_last_days"
        private const val KEY_LAST_WORKOUTS = "dashboard_last_workouts"
        private const val KEY_LAST_MEASUREMENTS = "dashboard_last_measurements"
        private const val KEY_LAST_SOURCES = "dashboard_last_sources"
        private const val KEY_SHEETS_OK = "dashboard_sheets_ok"
        private const val KEY_HC_GRANTED = "dashboard_hc_granted"
        private const val KEY_HC_TOTAL = "dashboard_hc_total"
    }
}
