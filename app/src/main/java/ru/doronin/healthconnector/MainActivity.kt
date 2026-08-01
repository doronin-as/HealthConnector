package ru.doronin.healthconnector

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.doronin.healthconnector.databinding.ActivityMainBinding
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val client by lazy { HealthConnectClient.getOrCreate(this) }
    private val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    )

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        binding.status.text = if (granted.containsAll(permissions)) {
            "Разрешения Health Connect выданы"
        } else {
            "Выданы не все разрешения"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        binding.endpoint.setText(prefs.getString("endpoint", ""))
        binding.token.setText(prefs.getString("token", ""))

        binding.permissions.setOnClickListener { permissionLauncher.launch(permissions) }
        binding.sync.setOnClickListener {
            val endpoint = binding.endpoint.text.toString().trim()
            val token = binding.token.text.toString().trim()
            val days = binding.days.text.toString().toIntOrNull()?.coerceIn(1, 30) ?: 7
            prefs.edit().putString("endpoint", endpoint).putString("token", token).apply()
            lifecycleScope.launch { sync(endpoint, token, days) }
        }
    }

    private suspend fun sync(endpoint: String, token: String, days: Int) {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            binding.status.text = "Health Connect недоступен"
            return
        }
        if (endpoint.isBlank() || token.isBlank()) {
            binding.status.text = "Укажи URL Apps Script и токен"
            return
        }

        binding.status.text = "Читаю данные…"
        runCatching {
            val payload = readPayload(days)
            binding.status.text = "Отправляю данные…"
            postJson(endpoint, token, payload)
            payload
        }.onSuccess { payload ->
            val sourceCount = payload.optJSONArray("sources")?.length() ?: 0
            binding.status.text = "Синхронизация завершена. Источников: $sourceCount"
        }.onFailure {
            binding.status.text = "Ошибка: ${it.message}"
        }
    }

    private suspend fun readPayload(days: Int): JSONObject {
        val zone = ZoneId.systemDefault()
        val endDate = LocalDate.now(zone).plusDays(1)
        val startDate = endDate.minusDays(days.toLong())
        val start = startDate.atStartOfDay(zone).toInstant()
        val end = endDate.atStartOfDay(zone).toInstant()

        val steps = readAll<StepsRecord>(start, end)
        val sleeps = readAll<SleepSessionRecord>(start, end)
        val heart = readAll<HeartRateRecord>(start, end)
        val oxygen = readAll<OxygenSaturationRecord>(start, end)
        val workouts = readAll<ExerciseSessionRecord>(start, end)

        val allSources = (steps.map { it.sourcePackage() } +
            sleeps.map { it.sourcePackage() } +
            heart.map { it.sourcePackage() } +
            oxygen.map { it.sourcePackage() } +
            workouts.map { it.sourcePackage() })
            .filter { it.isNotBlank() }
            .distinct()
            .sortedBy { sourcePriority(it) }

        val dayArray = JSONArray()
        repeat(days) { offset ->
            val date = startDate.plusDays(offset.toLong())
            val dayStart = date.atStartOfDay(zone).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()

            val dayStepRecords = preferBestSource(
                steps.filter { it.startTime < dayEnd && it.endTime > dayStart }
            )
            val daySleepRecords = preferBestSource(
                sleeps.filter { it.endTime.atZone(zone).toLocalDate() == date }
            )
            val dayHeartRecords = preferBestSource(
                heart.filter { record -> record.samples.any { it.time >= dayStart && it.time < dayEnd } }
            )
            val dayOxygenRecords = preferBestSource(
                oxygen.filter { it.time >= dayStart && it.time < dayEnd }
            )
            val dayWorkoutRecords = preferBestSource(
                workouts.filter { it.startTime >= dayStart && it.startTime < dayEnd }
            ).distinctBy { "${it.startTime}|${it.endTime}|${it.exerciseType}" }

            val daySteps = dayStepRecords.sumOf { it.count }
            val sleepMinutes = daySleepRecords.sumOf {
                Duration.between(it.startTime, it.endTime).toMinutes()
            }
            val heartSamples = dayHeartRecords.flatMap { it.samples }
                .filter { it.time >= dayStart && it.time < dayEnd }
            val oxygenSamples = dayOxygenRecords.filter { it.time >= dayStart && it.time < dayEnd }

            dayArray.put(JSONObject().apply {
                put("date", date.toString())
                put("steps", daySteps)
                put("sleepHours", sleepMinutes / 60.0)
                put("averageHeartRate", heartSamples.map { it.beatsPerMinute.toDouble() }.averageOrNull())
                put("minimumHeartRate", heartSamples.minOfOrNull { it.beatsPerMinute })
                put("maximumHeartRate", heartSamples.maxOfOrNull { it.beatsPerMinute })
                put("averageSpO2", oxygenSamples.map { it.percentage.value }.averageOrNull())
                put("workoutCount", dayWorkoutRecords.size)
                put("workoutMinutes", dayWorkoutRecords.sumOf {
                    Duration.between(it.startTime, it.endTime).toMinutes()
                })
                put("stepsSource", dayStepRecords.firstOrNull()?.sourcePackage().orEmpty())
                put("sleepSource", daySleepRecords.firstOrNull()?.sourcePackage().orEmpty())
                put("heartSource", dayHeartRecords.firstOrNull()?.sourcePackage().orEmpty())
                put("spo2Source", dayOxygenRecords.firstOrNull()?.sourcePackage().orEmpty())
                put("workoutSource", dayWorkoutRecords.firstOrNull()?.sourcePackage().orEmpty())
            })
        }

        val preferredWorkouts = workouts
            .groupBy { it.startTime.atZone(zone).toLocalDate() }
            .values
            .flatMap { preferBestSource(it) }
            .distinctBy { "${it.startTime}|${it.endTime}|${it.exerciseType}" }

        val workoutArray = JSONArray()
        preferredWorkouts.forEach { record ->
            workoutArray.put(JSONObject().apply {
                put("id", record.metadata.id)
                put("start", record.startTime.toString())
                put("end", record.endTime.toString())
                put("exerciseType", record.exerciseType)
                put("title", record.title ?: "")
                put("durationMinutes", Duration.between(record.startTime, record.endTime).toMinutes())
                put("sourcePackage", record.sourcePackage())
                put("sourceName", sourceName(record.sourcePackage()))
            })
        }

        return JSONObject().apply {
            put("syncedAt", Instant.now().toString())
            put("deviceId", android.os.Build.MODEL ?: "Android")
            put("rangeStart", startDate.toString())
            put("rangeEnd", endDate.minusDays(1).toString())
            put("days", dayArray)
            put("workouts", workoutArray)
            put("sources", JSONArray().apply {
                allSources.forEach { pkg ->
                    put(JSONObject().apply {
                        put("packageName", pkg)
                        put("name", sourceName(pkg))
                        put("priority", sourcePriority(pkg))
                    })
                }
            })
        }
    }

    /**
     * Источник выбирается отдельно для каждого дня и типа данных:
     * 1) Mi Fitness / Xiaomi;
     * 2) Google Fit;
     * 3) другие приложения Health Connect.
     * Так Google Fit заполняет пропуски, но не удваивает показатели Xiaomi.
     */
    private fun <T : Record> preferBestSource(records: List<T>): List<T> {
        if (records.isEmpty()) return emptyList()
        val packages = records.map { it.sourcePackage() }.distinct()
        val bestPackage = packages.minByOrNull { sourcePriority(it) } ?: return records
        return records.filter { it.sourcePackage() == bestPackage }
    }

    private fun Record.sourcePackage(): String = metadata.dataOrigin.packageName

    private fun sourcePriority(packageName: String): Int {
        val value = packageName.lowercase()
        return when {
            value.contains("xiaomi") ||
                value.contains("mifitness") ||
                value.contains("mi.health") ||
                value.contains("wearable") -> 0
            value == "com.google.android.apps.fitness" || value.contains("google") && value.contains("fitness") -> 1
            else -> 2
        }
    }

    private fun sourceName(packageName: String): String {
        val value = packageName.lowercase()
        return when {
            value.contains("xiaomi") || value.contains("mifitness") || value.contains("wearable") -> "Mi Fitness"
            value == "com.google.android.apps.fitness" || value.contains("google") && value.contains("fitness") -> "Google Fit"
            packageName.isBlank() -> "Неизвестный источник"
            else -> packageName
        }
    }

    private suspend inline fun <reified T : Record> readAll(start: Instant, end: Instant): List<T> {
        val all = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = T::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageSize = 1000,
                    pageToken = pageToken
                )
            )
            all += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return all
    }

    private suspend fun postJson(endpoint: String, token: String, payload: JSONObject) = withContext(Dispatchers.IO) {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")

        // Apps Script ожидает days/workouts в корне запроса, а не внутри payload.
        val body = JSONObject().apply {
            put("token", token)
            payload.keys().forEach { key -> put(key, payload.get(key)) }
        }.toString()

        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) error("HTTP $code: $response")
        if (response.isNotBlank()) {
            val json = runCatching { JSONObject(response) }.getOrNull()
            if (json?.optBoolean("ok", true) == false) {
                error(json.optString("message", response))
            }
        }
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
}
