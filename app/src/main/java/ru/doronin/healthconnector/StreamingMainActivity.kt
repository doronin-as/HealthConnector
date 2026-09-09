package ru.doronin.healthconnector

import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
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
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.doronin.healthconnector.databinding.ActivityMainBinding
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.time.Instant
import java.util.Date

class StreamingMainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val client by lazy { HealthConnectClient.getOrCreate(this) }
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val secureTokenStore by lazy { SecureTokenStore(this) }

    private var backgroundInfo: TextView? = null
    private var backgroundSwitch: SwitchMaterial? = null

    private val recordPermissions = setOf(
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

    private fun backgroundReadAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE &&
            client.features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    private fun requestedPermissions(): Set<String> = buildSet {
        addAll(recordPermissions)
        if (backgroundReadAvailable()) {
            add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        val requested = requestedPermissions()
        binding.status.text = if (granted.containsAll(requested)) {
            "Все доступные разрешения Health Connect выданы"
        } else {
            val recordsGranted = recordPermissions.count { it in granted }
            val backgroundGranted = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted
            "Выдано разрешений: $recordsGranted/${recordPermissions.size}. " +
                if (backgroundReadAvailable() && !backgroundGranted) {
                    "Фоновое чтение не разрешено."
                } else {
                    "Недоступные показатели останутся пустыми."
                }
        }
        BackgroundSyncScheduler.apply(this)
        refreshBackgroundInfo()
    }

    private val exportConfigLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) exportConfig(uri) }

    private val importConfigLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importConfig(uri) }

    private val importCsvLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importFatSecretCsv(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.endpoint.setText(prefs.getString("endpoint", ""))
        binding.token.setText(secureTokenStore.getToken())
        binding.days.setText(prefs.getInt("days", 7).toString())

        setupBackgroundSyncControls()
        setupDiagnosticsControls()
        BackgroundSyncScheduler.apply(this)

        binding.exportConfig.setOnClickListener {
            saveSettingsFromForm()
            exportConfigLauncher.launch("healthconnector-config.json")
        }
        binding.importConfig.setOnClickListener {
            importConfigLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
        }
        binding.importCsv.setOnClickListener {
            saveSettingsFromForm()
            importCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv"))
        }
        binding.permissions.setOnClickListener { permissionLauncher.launch(requestedPermissions()) }
        binding.sync.setOnClickListener {
            val settings = saveSettingsFromForm()
            lifecycleScope.launch { synchronize(settings) }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBackgroundInfo()
    }

    private fun setupDiagnosticsControls() {
        val container = binding.syncPage.getChildAt(0) as? LinearLayout ?: return
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            strokeWidth = dp(1)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(10))
        }
        content.addView(TextView(this).apply {
            text = "Диагностика синхронизации"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "Нажми на раздел: внутри видны этап, время, повторные попытки и точное место ошибки."
            textSize = 13f
            alpha = 0.70f
            setPadding(0, dp(6), 0, dp(4))
        })
        addDiagnosticDropdown(this, content, "Ручная синхронизация") {
            SyncDiagnostics.render(this, "manual")
        }
        addDiagnosticDropdown(this, content, "Фоновая синхронизация") {
            SyncDiagnostics.render(this, "background")
        }
        addDiagnosticDropdown(this, content, "Google Sheets и сеть") {
            SyncDiagnostics.render(this, "server")
        }
        card.addView(content)
        container.addView(card)
    }

    private fun setupBackgroundSyncControls() {
        val settingsScroll = binding.settingsPage
        val container = settingsScroll.getChildAt(0) as? LinearLayout ?: return

        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            strokeWidth = dp(1)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        content.addView(TextView(this).apply {
            text = "Фоновая синхронизация"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        content.addView(TextView(this).apply {
            text = "Автоматически обновляет последние ${BackgroundSyncScheduler.BACKGROUND_DAYS} дня примерно каждые ${BackgroundSyncScheduler.INTERVAL_HOURS} часов. Это повторно захватывает ночной сон, досыпы и дневной сон."
            textSize = 14f
            alpha = 0.72f
            setPadding(0, dp(6), 0, 0)
        })

        backgroundSwitch = SwitchMaterial(this).apply {
            text = "Синхронизировать в фоне"
            isChecked = prefs.getBoolean(BackgroundSyncScheduler.PREF_ENABLED, true)
            setPadding(0, dp(12), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(BackgroundSyncScheduler.PREF_ENABLED, checked).apply()
                BackgroundSyncScheduler.apply(this@StreamingMainActivity)
                refreshBackgroundInfo()
            }
        }.also(content::addView)

        backgroundInfo = TextView(this).apply {
            textSize = 12f
            alpha = 0.68f
            setPadding(0, dp(8), 0, 0)
        }.also(content::addView)

        card.addView(content)
        container.addView(card)
        refreshBackgroundInfo()
    }

    private fun refreshBackgroundInfo() {
        val info = backgroundInfo ?: return
        val enabled = prefs.getBoolean(BackgroundSyncScheduler.PREF_ENABLED, true)
        backgroundSwitch?.isChecked = enabled
        if (!enabled) {
            info.text = "Выключено"
            return
        }
        if (!backgroundReadAvailable()) {
            info.text = "На этой версии Health Connect фоновое чтение недоступно. Ручная синхронизация продолжит работать."
            return
        }

        lifecycleScope.launch {
            val backgroundGranted = runCatching {
                HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in
                    client.permissionController.getGrantedPermissions()
            }.getOrDefault(false)

            if (!backgroundGranted) {
                info.text = "Нужно нажать «Управление разрешениями» и разрешить доступ к данным Health Connect в фоне."
                return@launch
            }

            val lastRun = prefs.getLong(BackgroundSyncScheduler.PREF_LAST_RUN, 0L)
            val lastStatus = prefs.getString(BackgroundSyncScheduler.PREF_LAST_STATUS, "Ожидает первого фонового запуска")
                .orEmpty()
            val whenText = if (lastRun > 0L) {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastRun))
            } else {
                "ещё не запускалась"
            }
            info.text = "Последний фоновый запуск: $whenText\n$lastStatus"
        }
    }

    private suspend fun synchronize(settings: Settings) {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            binding.status.text = "Health Connect недоступен"
            return
        }
        if (settings.endpoint.isBlank() || settings.token.isBlank()) {
            binding.status.text = "Укажи URL Apps Script и токен"
            return
        }

        runCatching {
            SyncRunGate.runManual(
                onWaiting = { running ->
                    binding.status.text = "Жду завершения ${running ?: "другой"} синхронизации…"
                }
            ) {
                val runId = SyncDiagnostics.begin(this, "manual")
                try {
                    val streamer = HealthSyncStreamer(this, client)
                    val result = streamer.sync(
                        settings.endpoint,
                        settings.token,
                        settings.days,
                        includeHistoricalChanges = true
                    ) { message ->
                        SyncDiagnostics.progress(this, runId, "manual", message)
                        binding.status.text = message
                    }
                    SyncDiagnostics.finish(
                        this,
                        runId,
                        "manual",
                        "Дней ${result.days}, тренировок ${result.workouts}, измерений ${result.measurements}"
                    )
                    result
                } catch (error: Throwable) {
                    SyncDiagnostics.failure(this, runId, "manual", error)
                    throw error
                }
            }
        }.onSuccess { result ->
            binding.status.text =
                "Синхронизация завершена: дней ${result.days}, тренировок ${result.workouts}, " +
                    "измерений ${result.measurements}, источников ${result.sources}"
        }.onFailure { error ->
            binding.status.text = when (error) {
                is OutOfMemoryError -> "Ошибка памяти: данных слишком много даже для дневного пакета. Уменьши диапазон до 1 дня и сообщи мне."
                else -> "Ошибка: ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    private fun saveSettingsFromForm(): Settings {
        val endpoint = binding.endpoint.text.toString().trim()
        val token = binding.token.text.toString().trim()
        val days = binding.days.text.toString().toIntOrNull()?.coerceIn(1, 30) ?: 7
        prefs.edit()
            .putString("endpoint", endpoint)
            .putInt("days", days)
            .remove("token")
            .apply()
        secureTokenStore.setToken(token)
        BackgroundSyncScheduler.apply(this)
        return Settings(endpoint, token, days)
    }

    private fun exportConfig(uri: Uri) {
        runCatching {
            val settings = saveSettingsFromForm()
            val json = JSONObject().apply {
                put("format", "HealthConnectorConfig")
                put("version", 4)
                put("endpoint", settings.endpoint)
                put("days", settings.days)
                put("backgroundSync", prefs.getBoolean(BackgroundSyncScheduler.PREF_ENABLED, true))
                put("tokenIncluded", false)
                put("exportedAt", Instant.now().toString())
            }.toString(2)
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(json) }
                ?: error("Не удалось открыть файл для записи")
        }.onSuccess {
            binding.status.text = "Настройки сохранены без API-токена"
        }.onFailure {
            binding.status.text = "Ошибка сохранения настроек: ${it.message}"
        }
    }

    private fun importConfig(uri: Uri) {
        runCatching {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Не удалось прочитать файл")
            val json = JSONObject(text)
            require(json.optString("format") == "HealthConnectorConfig") {
                "Это не файл настроек HealthConnector"
            }
            val endpoint = json.optString("endpoint").trim()
            val importedToken = json.optString("token").trim()
            val days = json.optInt("days", 7).coerceIn(1, 30)
            val backgroundSync = json.optBoolean("backgroundSync", true)
            require(endpoint.isNotBlank()) { "В файле нет URL Apps Script" }

            binding.endpoint.setText(endpoint)
            if (importedToken.isNotBlank()) {
                secureTokenStore.setToken(importedToken)
                binding.token.setText(importedToken)
            } else {
                binding.token.setText(secureTokenStore.getToken())
            }
            binding.days.setText(days.toString())
            prefs.edit()
                .putString("endpoint", endpoint)
                .putInt("days", days)
                .putBoolean(BackgroundSyncScheduler.PREF_ENABLED, backgroundSync)
                .remove("token")
                .apply()
            backgroundSwitch?.isChecked = backgroundSync
            BackgroundSyncScheduler.apply(this)
        }.onSuccess {
            binding.status.text = "Настройки восстановлены"
            refreshBackgroundInfo()
        }.onFailure {
            binding.status.text = "Ошибка импорта настроек: ${it.message}"
        }
    }

    private fun importFatSecretCsv(uri: Uri) {
        val settings = saveSettingsFromForm()
        if (settings.endpoint.isBlank() || settings.token.isBlank()) {
            binding.status.text = "Сначала укажи URL Apps Script и токен"
            return
        }

        lifecycleScope.launch {
            binding.status.text = "Читаю CSV FatSecret…"
            runCatching {
                val fileName = queryFileName(uri) ?: "fatsecret.csv"
                val csvText = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: error("Не удалось прочитать CSV")
                }
                require(csvText.isNotBlank()) { "CSV пустой" }
                require(csvText.length <= 5_000_000) { "CSV слишком большой: максимум 5 МБ" }

                val normalized = FatSecretCsvNormalizer.normalize(csvText)
                binding.status.text = if (normalized.normalizedMealLabels > 0) {
                    "Объединяю дополнительные приёмы пищи…"
                } else {
                    "Отправляю CSV в Google Таблицу…"
                }
                val body = JSONObject().apply {
                    put("token", settings.token)
                    put("action", "fatsecretCsv")
                    put("fileName", fileName)
                    put("csvText", normalized.csvText)
                    put("uploadedAt", Instant.now().toString())
                    put("sourceMimeType", contentResolver.getType(uri).orEmpty())
                    put("normalizedMealLabels", normalized.normalizedMealLabels)
                }
                postBody(settings.endpoint, body)
            }.onSuccess { response ->
                val importedDays = response?.optInt("days", 0) ?: 0
                val meals = response?.optInt("meals", 0) ?: 0
                binding.status.text = "CSV импортирован: дней $importedDays, приёмов пищи $meals"
            }.onFailure {
                binding.status.text = "Ошибка импорта CSV: ${it.message}"
            }
        }
    }

    private fun queryFileName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null
        } finally {
            cursor?.close()
        }
    }

    private suspend fun postBody(endpoint: String, body: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        val safeEndpoint = EndpointSecurity.requireHttps(endpoint)
        val connection = URL(safeEndpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) error("HTTP $code")
        if (response.isBlank()) return@withContext null
        val json = runCatching { JSONObject(response) }.getOrNull()
        if (json?.optBoolean("ok", true) == false) error(json.optString("message", response))
        json
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class Settings(
        val endpoint: String,
        val token: String,
        val days: Int
    )
}
