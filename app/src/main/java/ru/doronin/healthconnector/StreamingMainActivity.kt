package ru.doronin.healthconnector

import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.doronin.healthconnector.databinding.ActivityMainBinding
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

class StreamingMainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val client by lazy { HealthConnectClient.getOrCreate(this) }
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val permissions = setOf(
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

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        binding.status.text = if (granted.containsAll(permissions)) {
            "Все доступные разрешения Health Connect выданы"
        } else {
            "Выдано разрешений: ${granted.size}/${permissions.size}. Недоступные показатели останутся пустыми."
        }
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
        binding.token.setText(prefs.getString("token", ""))
        binding.days.setText(prefs.getInt("days", 7).toString())

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
        binding.permissions.setOnClickListener { permissionLauncher.launch(permissions) }
        binding.sync.setOnClickListener {
            val settings = saveSettingsFromForm()
            lifecycleScope.launch { synchronize(settings) }
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
            val streamer = HealthSyncStreamer(this, client)
            streamer.sync(settings.endpoint, settings.token, settings.days) { message ->
                binding.status.text = message
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
            .putString("token", token)
            .putInt("days", days)
            .apply()
        return Settings(endpoint, token, days)
    }

    private fun exportConfig(uri: Uri) {
        runCatching {
            val settings = saveSettingsFromForm()
            val json = JSONObject().apply {
                put("format", "HealthConnectorConfig")
                put("version", 2)
                put("endpoint", settings.endpoint)
                put("token", settings.token)
                put("days", settings.days)
                put("exportedAt", Instant.now().toString())
            }.toString(2)
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(json) }
                ?: error("Не удалось открыть файл для записи")
        }.onSuccess {
            binding.status.text = "Настройки сохранены в файл"
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
            val endpoint = json.optString("endpoint")
            val token = json.optString("token")
            val days = json.optInt("days", 7).coerceIn(1, 30)
            require(endpoint.isNotBlank() && token.isNotBlank()) { "В файле нет URL или токена" }

            binding.endpoint.setText(endpoint)
            binding.token.setText(token)
            binding.days.setText(days.toString())
            prefs.edit()
                .putString("endpoint", endpoint)
                .putString("token", token)
                .putInt("days", days)
                .apply()
        }.onSuccess {
            binding.status.text = "Настройки восстановлены"
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

                binding.status.text = "Отправляю CSV в Google Таблицу…"
                val body = JSONObject().apply {
                    put("token", settings.token)
                    put("action", "fatsecretCsv")
                    put("fileName", fileName)
                    put("csvText", csvText)
                    put("uploadedAt", Instant.now().toString())
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
        val connection = URL(endpoint).openConnection() as HttpURLConnection
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
        if (code !in 200..299) error("HTTP $code: $response")
        if (response.isBlank()) return@withContext null
        val json = runCatching { JSONObject(response) }.getOrNull()
        if (json?.optBoolean("ok", true) == false) error(json.optString("message", response))
        json
    }

    private data class Settings(
        val endpoint: String,
        val token: String,
        val days: Int
    )
}
