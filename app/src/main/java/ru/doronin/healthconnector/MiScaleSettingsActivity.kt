package ru.doronin.healthconnector

import android.content.Intent
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeParseException

class MiScaleSettingsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var enabled: Switch
    private lateinit var height: EditText
    private lateinit var birthDate: EditText
    private lateinit var sex: Spinner
    private lateinit var boundInfo: TextView
    private lateinit var profileSyncInfo: TextView
    private lateinit var scannerInfo: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshBoundInfo()
            refreshScannerDiagnostics()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Умные весы"
        setContentView(buildContent())
        autoLoadProfileFromDashboard()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun buildContent(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        root.addView(TextView(this).apply {
            text = "Xiaomi Mi Body Composition Scale 2"
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Модель XMTZC05HM. HealthConnector слушает BLE-пакеты весов без Mi Fitness и сохраняет измерения в Dashboard. Рост, дата рождения и пол сначала ищутся автоматически в листе «Профиль»; ручной ввод остаётся резервным."
            textSize = 14f
            alpha = 0.72f
            setPadding(0, dp(8), 0, dp(12))
        })

        enabled = Switch(this).apply {
            text = "Считывать весы автоматически"
            isChecked = prefs.getBoolean(MiScaleScanner.PREF_ENABLED, true)
        }
        root.addView(enabled)

        profileSyncInfo = TextView(this).apply {
            text = "Автоподхват профиля: проверяю Dashboard…"
            textSize = 13f
            alpha = 0.78f
            setPadding(0, dp(12), 0, dp(4))
        }
        root.addView(profileSyncInfo)
        root.addView(Button(this).apply {
            text = "Обновить профиль из Dashboard"
            setOnClickListener { autoLoadProfileFromDashboard(manual = true) }
        })

        root.addView(label("Рост, см"))
        height = EditText(this).apply {
            hint = "Например, 180"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(prefs.getString(MiScaleProfile.PREF_HEIGHT_CM, ""))
        }
        root.addView(height)

        root.addView(label("Дата рождения"))
        birthDate = EditText(this).apply {
            hint = "ГГГГ-ММ-ДД"
            inputType = InputType.TYPE_CLASS_DATETIME
            setText(prefs.getString(MiScaleProfile.PREF_BIRTH_DATE, ""))
        }
        root.addView(birthDate)

        root.addView(label("Пол для формул состава тела"))
        sex = Spinner(this)
        val values = listOf("Выберите", "Мужской", "Женский")
        sex.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)
        sex.setSelection(
            when (prefs.getString(MiScaleProfile.PREF_SEX, "")) {
                "male" -> 1
                "female" -> 2
                else -> 0
            }
        )
        root.addView(sex)

        boundInfo = TextView(this).apply {
            textSize = 13f
            alpha = 0.82f
            setPadding(0, dp(16), 0, dp(6))
        }
        root.addView(boundInfo)

        scannerInfo = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(scannerInfo)
        refreshBoundInfo()
        refreshScannerDiagnostics()

        root.addView(Button(this).apply {
            text = "Найти весы вручную"
            setOnClickListener {
                prefs.edit().putBoolean(MiScaleScanner.PREF_ENABLED, true).apply()
                enabled.isChecked = true
                startActivity(Intent(this@MiScaleSettingsActivity, MiScaleFinderActivity::class.java))
            }
        })

        root.addView(Button(this).apply {
            text = "Проверить BLE-сканер сейчас"
            setOnClickListener {
                prefs.edit().putBoolean(MiScaleScanner.PREF_ENABLED, enabled.isChecked).apply()
                val started = MiScaleScanner.start(this@MiScaleSettingsActivity)
                refreshScannerDiagnostics()
                Toast.makeText(
                    this@MiScaleSettingsActivity,
                    if (started) "BLE-сканер запущен. Встаньте на весы." else "Сканер не запустился — см. диагностику ниже.",
                    Toast.LENGTH_LONG
                ).show()
            }
        })

        root.addView(Button(this).apply {
            text = "Сбросить привязку весов"
            setOnClickListener {
                prefs.edit().remove(MiScaleScanner.PREF_BOUND_ADDRESS).apply()
                refreshBoundInfo()
                Toast.makeText(this@MiScaleSettingsActivity, "Привязка сброшена. Следующее измерение привяжет весы заново.", Toast.LENGTH_LONG).show()
            }
        })

        root.addView(TextView(this).apply {
            text = "После сохранения встаньте на весы босиком. Первое стабильное измерение автоматически привяжет ближайшие совместимые весы; импеданс нужен для расчёта состава тела. Если автопривязка не срабатывает, откройте «Найти весы вручную»: там запускается активный 30-секундный BLE-поиск без фильтра и можно выбрать весы вручную."
            textSize = 13f
            alpha = 0.72f
            setPadding(0, dp(12), 0, dp(12))
        })

        root.addView(Button(this).apply {
            text = "Сохранить и включить"
            setOnClickListener { saveAndContinue() }
        })
        root.addView(Button(this).apply {
            text = "Пока пропустить"
            setOnClickListener { continueToMain() }
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun autoLoadProfileFromDashboard(manual: Boolean = false) {
        val endpoint = prefs.getString("endpoint", "").orEmpty().trim()
        val token = SecureTokenStore(this).getToken().trim()
        if (endpoint.isBlank() || token.isBlank()) {
            profileSyncInfo.text = "Автоподхват: сначала сохрани URL Apps Script и токен в HealthConnector."
            return
        }

        profileSyncInfo.text = if (manual) "Обновляю профиль из Dashboard…" else "Автоподхват профиля: читаю лист «Профиль»…"
        lifecycleScope.launch {
            runCatching { DashboardApi.fetch(endpoint, token) }
                .onSuccess { snapshot ->
                    val applied = DashboardApi.applyScaleProfile(prefs, snapshot.profile)
                    snapshot.profile.heightCm?.let { height.setText(trimNumber(it)) }
                    snapshot.profile.birthDate?.let { birthDate.setText(it) }
                    when (snapshot.profile.sex) {
                        "male" -> sex.setSelection(1)
                        "female" -> sex.setSelection(2)
                    }
                    val found = buildList {
                        if (snapshot.profile.heightCm != null) add("рост ✓")
                        if (snapshot.profile.birthDate != null) add("дата рождения ✓")
                        if (snapshot.profile.sex != null) add("пол ✓") else add("пол — нет в таблице")
                    }
                    profileSyncInfo.text = "Профиль загружен из Dashboard: ${found.joinToString(" · ")}" +
                        if (applied.isEmpty()) "" else "\nАвтоматически сохранено: ${applied.joinToString()}"
                }
                .onFailure { error ->
                    profileSyncInfo.text = "Не удалось загрузить профиль из Dashboard: ${error.message ?: error.javaClass.simpleName}. Можно заполнить поля вручную."
                }
        }
    }

    private fun trimNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

    private fun label(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    private fun saveAndContinue() {
        val heightValue = height.text.toString().trim().replace(',', '.').toDoubleOrNull()
        if (enabled.isChecked && (heightValue == null || heightValue !in 100.0..220.0)) {
            height.error = "Укажи рост от 100 до 220 см"
            return
        }
        val birth = if (enabled.isChecked) {
            try { LocalDate.parse(birthDate.text.toString().trim()) }
            catch (_: DateTimeParseException) {
                birthDate.error = "Формат: ГГГГ-ММ-ДД"
                return
            }
        } else null
        if (enabled.isChecked && (birth == null || birth.isAfter(LocalDate.now().minusYears(13)))) {
            birthDate.error = "Проверь дату рождения"
            return
        }
        val sexValue = when (sex.selectedItemPosition) {
            1 -> "male"
            2 -> "female"
            else -> null
        }
        if (enabled.isChecked && sexValue == null) {
            Toast.makeText(this, "Выбери пол для расчёта состава тела", Toast.LENGTH_LONG).show()
            return
        }

        val edit = prefs.edit()
            .putBoolean(MiScaleScanner.PREF_ENABLED, enabled.isChecked)
            .putBoolean(MiScaleScanner.PREF_SETUP_PROMPTED, true)
        if (heightValue != null) edit.putString(MiScaleProfile.PREF_HEIGHT_CM, heightValue.toString())
        if (birth != null) edit.putString(MiScaleProfile.PREF_BIRTH_DATE, birth.toString())
        if (sexValue != null) edit.putString(MiScaleProfile.PREF_SEX, sexValue)
        edit.apply()

        val started = if (enabled.isChecked) MiScaleScanner.start(this) else {
            MiScaleScanner.stop(this)
            false
        }
        refreshScannerDiagnostics()
        Toast.makeText(
            this,
            when {
                !enabled.isChecked -> "Считывание весов выключено"
                started -> "BLE-сканер запущен. Встаньте на весы для привязки."
                else -> "Не удалось запустить BLE-сканер — откройте настройки весов и посмотрите диагностику."
            },
            Toast.LENGTH_LONG
        ).show()
        continueToMain()
    }

    private fun refreshBoundInfo() {
        val address = prefs.getString(MiScaleScanner.PREF_BOUND_ADDRESS, "").orEmpty()
        boundInfo.text = if (address.isBlank()) {
            "Весы ещё не привязаны"
        } else {
            "Привязаны весы: $address"
        }
    }

    private fun refreshScannerDiagnostics() {
        if (!::scannerInfo.isInitialized) return
        val state = prefs.getString(MiScaleScanner.PREF_SCAN_STATE, "Сканер ещё не запускался").orEmpty()
        val packetAt = prefs.getLong(MiScaleScanner.PREF_LAST_PACKET_AT, 0L)
        val address = prefs.getString(MiScaleScanner.PREF_LAST_PACKET_ADDRESS, "").orEmpty()
        val name = prefs.getString(MiScaleScanner.PREF_LAST_PACKET_NAME, "").orEmpty()
        val rssi = prefs.getInt(MiScaleScanner.PREF_LAST_PACKET_RSSI, 0)
        val last = prefs.getString(MiScaleScanner.PREF_LAST_MEASUREMENT, "").orEmpty()
        val error = prefs.getString(MiScaleScanner.PREF_LAST_ERROR, "").orEmpty()
        val locationEnabled = runCatching {
            getSystemService(LocationManager::class.java)?.isLocationEnabled ?: true
        }.getOrDefault(true)
        val permissionsText = buildString {
            append("BLE Scan: ${if (MiScaleScanner.hasBluetoothScanPermission(this@MiScaleSettingsActivity)) "✓" else "НЕТ"}")
            append(" · Bluetooth Connect: ${if (MiScaleScanner.hasBluetoothConnectPermission(this@MiScaleSettingsActivity)) "✓" else "НЕТ"}")
            append("\nГеолокация-разрешение: ${if (MiScaleScanner.hasLocationPermission(this@MiScaleSettingsActivity)) "✓" else "НЕТ"}")
            append(" · системная геолокация: ${if (locationEnabled) "включена" else "ВЫКЛЮЧЕНА"}")
        }
        val packetText = if (packetAt <= 0L) {
            "BLE-пакеты: пока не получены"
        } else {
            val sec = ((System.currentTimeMillis() - packetAt).coerceAtLeast(0L) / 1000L)
            val device = listOf(name, address).filter { it.isNotBlank() }.joinToString(" · ")
            "Последний BLE-пакет: ${sec}с назад${if (device.isBlank()) "" else " · $device"}${if (rssi == 0) "" else " · $rssi dBm"}"
        }
        scannerInfo.text = buildString {
            append("Диагностика:\n$permissionsText\n$state\n$packetText")
            if (last.isNotBlank()) append("\nПоследнее измерение: $last")
            if (error.isNotBlank()) append("\nОшибка: $error")
        }
    }

    private fun continueToMain() {
        if (intent.getBooleanExtra(EXTRA_OPEN_MAIN_AFTER, false)) {
            startActivity(Intent(this, StreamingMainActivity::class.java))
        }
        finish()
    }

    companion object {
        const val EXTRA_OPEN_MAIN_AFTER = "open_main_after"
    }
}
