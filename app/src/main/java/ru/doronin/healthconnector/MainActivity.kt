package ru.doronin.healthconnector

import android.app.Activity
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.ExperimentalMatchmakingApi
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.matchmaking.MatchmakingRequest
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.request.AggregateRequest
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

@OptIn(ExperimentalMatchmakingApi::class)
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val client by lazy { HealthConnectClient.getOrCreate(this) }
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class)
    )

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        binding.status.text = if (granted.containsAll(permissions)) {
            "Разрешения Health Connect выданы"
        } else {
            "Выданы не все разрешения. Для полной синхронизации разреши все запрошенные типы данных."
        }
    }

    private val matchmakingLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        binding.status.text = if (result.resultCode == Activity.RESULT_OK) {
            "Источник данных подключён. Теперь можно синхронизировать."
        } else {
            "Подключение источника завершено без изменений."
        }
    }

    private val exportConfigLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) exportConfig(uri)
    }

    private val importConfigLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importConfig(uri)
    }

    private val importCsvLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importFatSecretCsv(uri)
    }

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
        binding.connectWearable.setOnClickListener {
            lifecycleScope.launch { launchWearableMatchmaking() }
        }
        binding.sync.setOnClickListener {
            val settings = saveSettingsFromForm()
            lifecycleScope.launch { sync(settings.endpoint, settings.token, settings.days) }
        }
    }

    private suspend fun launchWearableMatchmaking() {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            binding.status.text = "Health Connect недоступен"
            return
        }

        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(permissions)) {
            binding.status.text = "Сначала выдай разрешения Health Connect, затем повтори подключение."
            return
        }

        if (
            client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_MATCHMAKING) !=
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        ) {
            binding.status.text =
                "Автопоиск источников пока недоступен на этом устройстве. Подключи приложение часов к Health Connect вручную."
            return
        }

        binding.status.text = "Ищу совместимые приложения и устройства…"
        runCatching {
            val request = MatchmakingRequest(recordTypes = emptySet())
            val response = client.checkIfMatchmakingIsPossible(request)
            if (!response.isMatchmakingPossible) null else client.createMatchmakingIntent(request)
        }.onSuccess { intent ->
            if (intent == null) {
                binding.status.text =
                    "Новых совместимых источников не найдено. Уже подключённые источники будут использованы автоматически."
            } else {
                matchmakingLauncher.launch(intent)
            }
        }.onFailure {
            binding.status.text = "Не удалось открыть подключение источника: ${it.message}"
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
                put("version", 1)
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
            prefs.edit().putString("endpoint", endpoint).putString("token", token).putInt("days", days).apply()
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
                val days = response?.optInt("days", 0) ?: 0
                val meals = response?.optInt("meals", 0) ?: 0
                binding.status.text = "CSV импортирован: дней $days, приёмов пищи $meals"
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

    private suspend fun sync(endpoint: String, token: String, days: Int) {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            binding.status.text = "Health Connect недоступен"
            return
        }
        if (endpoint.isBlank() || token.isBlank()) {
            binding.status.text = "Укажи URL Apps Script и токен"
            return
        }

        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(permissions)) {
            binding.status.text = "Не выданы все разрешения Health Connect. Нажми «Выдать разрешения»."
            return
        }

        binding.status.text = "Читаю данные…"
        runCatching {
            val payload = readPayload(days)
            binding.status.text = "Отправляю данные…"
            val body = JSONObject().apply {
                put("token", token)
                put("action", "healthSync")
                payload.keys().forEach { key -> put(key, payload.get(key)) }
            }
            postBody(endpoint, body)
            payload
        }.onSuccess { payload ->
            val sourceCount = payload.optJSONArray("sources")?.length() ?: 0
            val suspiciousDates = mutableListOf<String>()
            val dayArray = payload.optJSONArray("days")
            if (dayArray != null) {
                for (i in 0 until dayArray.length()) {
                    val day = dayArray.optJSONObject(i) ?: continue
                    if (day.optBoolean("sleepSuspicious", false)) suspiciousDates += day.optString("date")
                }
            }
            binding.status.text = if (suspiciousDates.isEmpty()) {
                "Синхронизация завершена. Источников: $sourceCount"
            } else {
                "Синхронизация завершена. Сон не записан как недостоверный: ${suspiciousDates.joinToString()}"
            }
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
        val distance = readAll<DistanceRecord>(start, end)
        val sleeps = readAll<SleepSessionRecord>(start, end)
        val heart = readAll<HeartRateRecord>(start, end)
        val restingHeart = readAll<RestingHeartRateRecord>(start, end)
        val oxygen = readAll<OxygenSaturationRecord>(start, end)
        val workouts = readAll<ExerciseSessionRecord>(start, end)
        val activeCalories = readAll<ActiveCaloriesBurnedRecord>(start, end)
        val totalCalories = readAll<TotalCaloriesBurnedRecord>(start, end)
        val weights = readAll<WeightRecord>(start, end)

        val allRecords = buildList<Record> {
            addAll(steps)
            addAll(distance)
            addAll(sleeps)
            addAll(heart)
            addAll(restingHeart)
            addAll(oxygen)
            addAll(workouts)
            addAll(activeCalories)
            addAll(totalCalories)
            addAll(weights)
        }
        val sourceGroups = allRecords
            .filter { it.sourcePackage().isNotBlank() }
            .groupBy { it.sourcePackage() }
            .toList()
            .sortedWith(compareBy<Pair<String, List<Record>>> { sourcePriority(it.second) }.thenBy { sourceName(it.first) })

        val dayArray = JSONArray()
        repeat(days) { offset ->
            val date = startDate.plusDays(offset.toLong())
            val dayStart = date.atStartOfDay(zone).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()

            val dailyAggregate = readDailyAggregate(dayStart, dayEnd)
            val dayStepRecords = preferBestSource(steps.filter { it.startTime < dayEnd && it.endTime > dayStart })
            val daySleepRecords = preferBestSource(sleeps.filter { it.startTime < dayEnd && it.endTime > dayStart })
            val sleep = aggregateSleep(daySleepRecords, dayStart, dayEnd)
            val dayHeartRecords = preferBestSource(
                heart.filter { record -> record.samples.any { it.time >= dayStart && it.time < dayEnd } }
            )
            val dayRestingHeartRecords = preferBestSource(restingHeart.filter { it.time >= dayStart && it.time < dayEnd })
            val dayOxygenRecords = preferBestSource(oxygen.filter { it.time >= dayStart && it.time < dayEnd })
            val dayWorkoutRecords = preferBestSource(workouts.filter { it.startTime >= dayStart && it.startTime < dayEnd })
                .distinctBy { "${it.startTime}|${it.endTime}|${it.exerciseType}" }
            val heartSamples = dayHeartRecords.flatMap { it.samples }.filter { it.time >= dayStart && it.time < dayEnd }

            val aggregateSleepHours = dailyAggregate.sleepHours
            val suspiciousSleep = (aggregateSleepHours ?: sleep.hours ?: 0.0) > MAX_SLEEP_HOURS_PER_DAY
            val effectiveSleepHours = if (suspiciousSleep) null else aggregateSleepHours ?: sleep.hours

            val daySources = allRecords.asSequence()
                .filter { recordOverlapsDay(it, dayStart, dayEnd) }
                .map { it.sourcePackage() }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
                .toList()

            dayArray.put(JSONObject().apply {
                put("date", date.toString())
                put("steps", dailyAggregate.steps)
                put("distanceKm", dailyAggregate.distanceKm ?: JSONObject.NULL)
                put("activeCaloriesKcal", dailyAggregate.activeCaloriesKcal ?: JSONObject.NULL)
                put("totalCaloriesKcal", dailyAggregate.totalCaloriesKcal ?: JSONObject.NULL)
                put("sleepHours", effectiveSleepHours ?: JSONObject.NULL)
                put("sleepSuspicious", suspiciousSleep)
                put("sleepRawHours", sleep.rawHours)
                put("sleepSessionCount", sleep.sessionCount)
                put("sleepMergedIntervalCount", sleep.mergedIntervalCount)
                put("deepSleepMinutes", sleep.deepMinutes)
                put("lightSleepMinutes", sleep.lightMinutes)
                put("remSleepMinutes", sleep.remMinutes)
                put("awakeMinutes", sleep.awakeMinutes)
                put("averageHeartRate", heartSamples.map { it.beatsPerMinute.toDouble() }.averageOrNull())
                put("minimumHeartRate", heartSamples.minOfOrNull { it.beatsPerMinute })
                put("maximumHeartRate", heartSamples.maxOfOrNull { it.beatsPerMinute })
                put("restingHeartRate", dayRestingHeartRecords.map { it.beatsPerMinute.toDouble() }.averageOrNull())
                put("averageSpO2", dayOxygenRecords.map { it.percentage.value }.averageOrNull())
                put("minimumSpO2", dayOxygenRecords.minOfOrNull { it.percentage.value })
                put("weightKg", dailyAggregate.weightKg ?: JSONObject.NULL)
                put("workoutCount", dayWorkoutRecords.size)
                put("workoutMinutes", dayWorkoutRecords.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() })
                put("sourcePackages", JSONArray(daySources))
                put("stepsSource", dayStepRecords.firstOrNull()?.sourcePackage().orEmpty())
                put("sleepSource", daySleepRecords.firstOrNull()?.sourcePackage().orEmpty())
                put("heartSource", dayHeartRecords.firstOrNull()?.sourcePackage().orEmpty())
                put("restingHeartSource", dayRestingHeartRecords.firstOrNull()?.sourcePackage().orEmpty())
                put("spo2Source", dayOxygenRecords.firstOrNull()?.sourcePackage().orEmpty())
                put("workoutSource", dayWorkoutRecords.firstOrNull()?.sourcePackage().orEmpty())
            })
        }

        val preferredWorkouts = workouts.groupBy { it.startTime.atZone(zone).toLocalDate() }
            .values.flatMap { preferBestSource(it) }.distinctBy { "${it.startTime}|${it.endTime}|${it.exerciseType}" }
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
                put("device", record.deviceLabel() ?: "")
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
                sourceGroups.forEach { (pkg, records) ->
                    put(JSONObject().apply {
                        put("packageName", pkg)
                        put("name", sourceName(pkg))
                        put("priority", sourcePriority(records))
                        put("devices", JSONArray(records.mapNotNull { it.deviceLabel() }.distinct().sorted()))
                        put("deviceTypes", JSONArray(records.mapNotNull { it.metadata.device?.type }.distinct().map { deviceTypeName(it) }))
                    })
                }
            })
        }
    }

    private suspend fun readDailyAggregate(start: Instant, end: Instant): DailyAggregate {
        val result = client.aggregate(
            AggregateRequest(
                metrics = setOf(
                    StepsRecord.COUNT_TOTAL,
                    DistanceRecord.DISTANCE_TOTAL,
                    ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                    TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                    SleepSessionRecord.SLEEP_DURATION_TOTAL,
                    WeightRecord.WEIGHT_AVG
                ),
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        )
        return DailyAggregate(
            steps = result[StepsRecord.COUNT_TOTAL] ?: 0L,
            distanceKm = result[DistanceRecord.DISTANCE_TOTAL]?.inKilometers,
            activeCaloriesKcal = result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
            totalCaloriesKcal = result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories,
            sleepHours = result[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMillis()?.div(3_600_000.0),
            weightKg = result[WeightRecord.WEIGHT_AVG]?.inKilograms
        )
    }

    private fun aggregateSleep(records: List<SleepSessionRecord>, dayStart: Instant, dayEnd: Instant): SleepAggregation {
        val intervals = records.mapNotNull { record ->
            val clippedStart = if (record.startTime < dayStart) dayStart else record.startTime
            val clippedEnd = if (record.endTime > dayEnd) dayEnd else record.endTime
            if (clippedStart < clippedEnd) SleepInterval(clippedStart, clippedEnd) else null
        }.sortedBy { it.start }

        if (intervals.isEmpty()) {
            return SleepAggregation(
                hours = null,
                rawHours = 0.0,
                sessionCount = records.size,
                mergedIntervalCount = 0,
                deepMinutes = 0L,
                lightMinutes = 0L,
                remMinutes = 0L,
                awakeMinutes = 0L
            )
        }

        val rawMillis = intervals.sumOf { Duration.between(it.start, it.end).toMillis() }
        val merged = mutableListOf<SleepInterval>()
        var currentStart = intervals.first().start
        var currentEnd = intervals.first().end

        for (interval in intervals.drop(1)) {
            if (!interval.start.isAfter(currentEnd)) {
                if (interval.end > currentEnd) currentEnd = interval.end
            } else {
                merged += SleepInterval(currentStart, currentEnd)
                currentStart = interval.start
                currentEnd = interval.end
            }
        }
        merged += SleepInterval(currentStart, currentEnd)

        val uniqueMillis = merged.sumOf { Duration.between(it.start, it.end).toMillis() }
        val uniqueHours = uniqueMillis / 3_600_000.0
        val stageMinutes = records.flatMap { it.stages }
            .mapNotNull { stage ->
                val clippedStart = if (stage.startTime < dayStart) dayStart else stage.startTime
                val clippedEnd = if (stage.endTime > dayEnd) dayEnd else stage.endTime
                if (clippedStart < clippedEnd) stage.stage to Duration.between(clippedStart, clippedEnd).toMinutes() else null
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, values) -> values.sum() }

        return SleepAggregation(
            hours = uniqueHours,
            rawHours = rawMillis / 3_600_000.0,
            sessionCount = records.size,
            mergedIntervalCount = merged.size,
            deepMinutes = stageMinutes[SleepSessionRecord.STAGE_TYPE_DEEP] ?: 0L,
            lightMinutes = stageMinutes[SleepSessionRecord.STAGE_TYPE_LIGHT] ?: 0L,
            remMinutes = stageMinutes[SleepSessionRecord.STAGE_TYPE_REM] ?: 0L,
            awakeMinutes =
                (stageMinutes[SleepSessionRecord.STAGE_TYPE_AWAKE] ?: 0L) +
                (stageMinutes[SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED] ?: 0L) +
                (stageMinutes[SleepSessionRecord.STAGE_TYPE_OUT_OF_BED] ?: 0L)
        )
    }

    private fun <T : Record> preferBestSource(records: List<T>): List<T> {
        if (records.isEmpty()) return emptyList()
        return records.groupBy { it.sourcePackage() }
            .entries
            .sortedWith(
                compareBy<Map.Entry<String, List<T>>> { sourcePriority(it.value) }
                    .thenByDescending { it.value.size }
                    .thenBy { sourceName(it.key) }
            )
            .firstOrNull()
            ?.value
            .orEmpty()
    }

    private fun sourcePriority(records: List<out Record>): Int {
        val deviceTypes = records.mapNotNull { it.metadata.device?.type }.toSet()
        val packageName = records.firstOrNull()?.sourcePackage().orEmpty()
        return when {
            deviceTypes.any { it in WEARABLE_DEVICE_TYPES } -> 0
            deviceTypes.contains(Device.TYPE_SCALE) -> 1
            looksLikeWearableProvider(packageName) -> 1
            deviceTypes.contains(Device.TYPE_PHONE) -> 3
            else -> 2
        }
    }

    private fun Record.sourcePackage(): String = metadata.dataOrigin.packageName

    private fun Record.deviceLabel(): String? {
        val device = metadata.device ?: return null
        val name = listOfNotNull(device.manufacturer, device.model)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
        return name.ifBlank { deviceTypeName(device.type) }
    }

    private fun sourceName(packageName: String): String {
        if (packageName.isBlank()) return "Неизвестный источник"

        val installedLabel = runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString().trim()
        }.getOrNull()
        if (!installedLabel.isNullOrBlank()) return installedLabel

        val value = packageName.lowercase()
        return when {
            value.startsWith("com.android.healthconnect.phone") -> "Android"
            value.contains("xiaomi") || value.contains("mifitness") || value.contains("mi.health") -> "Mi Fitness"
            value.contains("shealth") || value.contains("samsung") && value.contains("health") -> "Samsung Health"
            value.contains("garmin") -> "Garmin Connect"
            value.contains("fitbit") -> "Fitbit"
            value.contains("huami") || value.contains("amazfit") || value.contains("zepp") -> "Zepp"
            value.contains("huawei") && value.contains("health") -> "Huawei Health"
            value.contains("polar") -> "Polar"
            value.contains("withings") -> "Withings"
            value.contains("oura") -> "Oura"
            value.contains("coros") -> "COROS"
            value.contains("suunto") -> "Suunto"
            value == "com.google.android.apps.fitness" || value.contains("google") && value.contains("fitness") -> "Google Fit"
            else -> packageName
        }
    }

    private fun looksLikeWearableProvider(packageName: String): Boolean {
        val value = packageName.lowercase()
        return listOf(
            "xiaomi", "mifitness", "mi.health", "wearable", "shealth", "samsung",
            "garmin", "fitbit", "huami", "amazfit", "zepp", "huawei", "polar",
            "withings", "oura", "coros", "suunto"
        ).any(value::contains)
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        Device.TYPE_WATCH -> "часы"
        Device.TYPE_FITNESS_BAND -> "фитнес-браслет"
        Device.TYPE_RING -> "умное кольцо"
        Device.TYPE_CHEST_STRAP -> "нагрудный датчик"
        Device.TYPE_SCALE -> "весы"
        Device.TYPE_PHONE -> "телефон"
        Device.TYPE_HEAD_MOUNTED -> "носимое устройство"
        Device.TYPE_SMART_DISPLAY -> "умный дисплей"
        else -> "устройство"
    }

    private fun recordOverlapsDay(record: Record, start: Instant, end: Instant): Boolean = when (record) {
        is StepsRecord -> record.startTime < end && record.endTime > start
        is DistanceRecord -> record.startTime < end && record.endTime > start
        is SleepSessionRecord -> record.startTime < end && record.endTime > start
        is HeartRateRecord -> record.samples.any { it.time >= start && it.time < end }
        is RestingHeartRateRecord -> record.time >= start && record.time < end
        is OxygenSaturationRecord -> record.time >= start && record.time < end
        is ExerciseSessionRecord -> record.startTime < end && record.endTime > start
        is ActiveCaloriesBurnedRecord -> record.startTime < end && record.endTime > start
        is TotalCaloriesBurnedRecord -> record.startTime < end && record.endTime > start
        is WeightRecord -> record.time >= start && record.time < end
        else -> false
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

    private suspend fun postBody(endpoint: String, body: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
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

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

    private data class SleepInterval(val start: Instant, val end: Instant)
    private data class SleepAggregation(
        val hours: Double?,
        val rawHours: Double,
        val sessionCount: Int,
        val mergedIntervalCount: Int,
        val deepMinutes: Long,
        val lightMinutes: Long,
        val remMinutes: Long,
        val awakeMinutes: Long
    )
    private data class DailyAggregate(
        val steps: Long,
        val distanceKm: Double?,
        val activeCaloriesKcal: Double?,
        val totalCaloriesKcal: Double?,
        val sleepHours: Double?,
        val weightKg: Double?
    )
    private data class Settings(val endpoint: String, val token: String, val days: Int)

    companion object {
        private const val MAX_SLEEP_HOURS_PER_DAY = 16.0
        private val WEARABLE_DEVICE_TYPES = setOf(
            Device.TYPE_WATCH,
            Device.TYPE_FITNESS_BAND,
            Device.TYPE_RING,
            Device.TYPE_CHEST_STRAP
        )
    }
}
