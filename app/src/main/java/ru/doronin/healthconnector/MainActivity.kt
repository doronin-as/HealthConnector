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
import androidx.health.connect.client.records.Record
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
            lifecycleScope.launch { sync(settings.endpoint, settings.token, settings.days) }
        }
    }

    private fun saveSettingsFromForm(): Settings {
        val endpoint = binding.endpoint.text.toString().trim()
        val token = binding.token.text.toString().trim()
        val days = binding.days.text.toString().toIntOrNull()?.coerceIn(1, 30) ?: 7
        prefs.edit().putString("endpoint", endpoint).putString("token", token).putInt("days", days).apply()
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
            require(json.optString("format") == "HealthConnectorConfig") { "Это не файл настроек HealthConnector" }
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

    private suspend fun sync(endpoint: String, token: String, days: Int) {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            binding.status.text = "Health Connect недоступен"
            return
        }
        if (endpoint.isBlank() || token.isBlank()) {
            binding.status.text = "Укажи URL Apps Script и токен"
            return
        }

        binding.status.text = "Читаю все доступные данные Health Connect…"
        runCatching {
            val payload = readPayload(days)
            binding.status.text = "Отправляю данные…"
            val body = JSONObject().apply {
                put("token", token)
                put("action", "healthSyncV2")
                payload.keys().forEach { key -> put(key, payload.get(key)) }
            }
            val response = postBody(endpoint, body)
            Pair(payload, response)
        }.onSuccess { (payload, response) ->
            val sourceCount = payload.optJSONArray("sources")?.length() ?: 0
            val dayCount = response?.optInt("days", payload.optJSONArray("days")?.length() ?: 0) ?: 0
            val workoutCount = response?.optInt("workouts", payload.optJSONArray("workouts")?.length() ?: 0) ?: 0
            val measurementCount = response?.optInt("measurements", payload.optJSONArray("measurements")?.length() ?: 0) ?: 0
            binding.status.text = "Синхронизация завершена: дней $dayCount, тренировок $workoutCount, измерений $measurementCount, источников $sourceCount"
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

        val steps = safeReadAll<StepsRecord>(start, end)
        val distances = safeReadAll<DistanceRecord>(start, end)
        val activeCalories = safeReadAll<ActiveCaloriesBurnedRecord>(start, end)
        val totalCalories = safeReadAll<TotalCaloriesBurnedRecord>(start, end)
        val sleeps = safeReadAll<SleepSessionRecord>(start, end)
        val heart = safeReadAll<HeartRateRecord>(start, end)
        val restingHeart = safeReadAll<RestingHeartRateRecord>(start, end)
        val hrv = safeReadAll<HeartRateVariabilityRmssdRecord>(start, end)
        val oxygen = safeReadAll<OxygenSaturationRecord>(start, end)
        val respiratory = safeReadAll<RespiratoryRateRecord>(start, end)
        val vo2 = safeReadAll<Vo2MaxRecord>(start, end)
        val skinTemperature = safeReadAll<SkinTemperatureRecord>(start, end)
        val elevation = safeReadAll<ElevationGainedRecord>(start, end)
        val floors = safeReadAll<FloorsClimbedRecord>(start, end)
        val speed = safeReadAll<SpeedRecord>(start, end)
        val stepCadence = safeReadAll<StepsCadenceRecord>(start, end)
        val cyclingCadence = safeReadAll<CyclingPedalingCadenceRecord>(start, end)
        val power = safeReadAll<PowerRecord>(start, end)
        val workouts = safeReadAll<ExerciseSessionRecord>(start, end)
        val weights = safeReadAll<WeightRecord>(start, end)

        val allRecords = mutableListOf<Record>()
        allRecords.addAll(steps); allRecords.addAll(distances); allRecords.addAll(activeCalories)
        allRecords.addAll(totalCalories); allRecords.addAll(sleeps); allRecords.addAll(heart)
        allRecords.addAll(restingHeart); allRecords.addAll(hrv); allRecords.addAll(oxygen)
        allRecords.addAll(respiratory); allRecords.addAll(vo2); allRecords.addAll(skinTemperature)
        allRecords.addAll(elevation); allRecords.addAll(floors); allRecords.addAll(speed)
        allRecords.addAll(stepCadence); allRecords.addAll(cyclingCadence); allRecords.addAll(power)
        allRecords.addAll(workouts); allRecords.addAll(weights)

        val allSources = allRecords.map { it.sourcePackage() }.filter { it.isNotBlank() }
            .distinct().sortedBy { sourcePriority(it) }

        val dayArray = JSONArray()
        repeat(days) { offset ->
            val date = startDate.plusDays(offset.toLong())
            val dayStart = date.atStartOfDay(zone).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()

            val dSteps = preferBestSource(steps.filter { overlaps(it.startTime, it.endTime, dayStart, dayEnd) })
            val dDistance = preferBestSource(distances.filter { overlaps(it.startTime, it.endTime, dayStart, dayEnd) })
            val dActiveCalories = preferBestSource(activeCalories.filter { overlaps(it.startTime, it.endTime, dayStart, dayEnd) })
            val dTotalCalories = preferBestSource(totalCalories.filter { overlaps(it.startTime, it.endTime, dayStart, dayEnd) })
            val dSleeps = preferBestSource(sleeps.filter { overlaps(it.startTime, it.endTime, dayStart, dayEnd) })
            val dHeart = preferBestSource(heart.filter { record -> record.samples.any { it.time >= dayStart && it.time < dayEnd } })
            val dResting = preferBestSource(restingHeart.filter { it.time >= dayStart && it.time < dayEnd })
            val dHrv = preferBestSource(hrv.filter { it.time >= dayStart && it.time < dayEnd })
            val dOxygen = preferBestSource(oxygen.filter { it.time >= dayStart && it.time < dayEnd })
            val dRespiratory = preferBestSource(respiratory.filter { it.time >= dayStart && it.time < dayEnd })
            val dVo2 = preferBestSource(vo2.filter { it.time >= dayStart && it.time < dayEnd })
            val dSkin = preferBestSource(skinTemperature.filter { overlaps(it.startTime, it.endTime, dayStart, dayEnd) })
            val dElevation = preferBestSource(elevation.filter { overlaps(it.startTime, it.endTime, dayStart, dayEnd) })
            val dFloors = preferBestSource(floors.filter { overlaps(it.startTime, it.endTime, dayStart, dayEnd) })
            val dSpeed = preferBestSource(speed.filter { record -> record.samples.any { it.time >= dayStart && it.time < dayEnd } })
            val dStepCadence = preferBestSource(stepCadence.filter { record -> record.samples.any { it.time >= dayStart && it.time < dayEnd } })
            val dCyclingCadence = preferBestSource(cyclingCadence.filter { record -> record.samples.any { it.time >= dayStart && it.time < dayEnd } })
            val dPower = preferBestSource(power.filter { record -> record.samples.any { it.time >= dayStart && it.time < dayEnd } })
            val dWorkouts = preferBestSource(workouts.filter { it.startTime >= dayStart && it.startTime < dayEnd })
                .distinctBy { "${it.startTime}|${it.endTime}|${it.exerciseType}" }
            val dWeights = preferBestSource(weights.filter { it.time >= dayStart && it.time < dayEnd })

            val sleep = aggregateSleep(dSleeps, dayStart, dayEnd)
            val heartSamples = dHeart.flatMap { it.samples }.filter { it.time >= dayStart && it.time < dayEnd }
            val oxygenValues = dOxygen.map { it.percentage.value }
            val hrvValues = dHrv.map { it.heartRateVariabilityMillis }
            val respiratoryValues = dRespiratory.map { it.rate }
            val speedValues = dSpeed.flatMap { it.samples }.filter { it.time >= dayStart && it.time < dayEnd }.map { it.speed.inKilometersPerHour }
            val stepCadenceValues = dStepCadence.flatMap { it.samples }.filter { it.time >= dayStart && it.time < dayEnd }.map { it.rate }
            val cyclingCadenceValues = dCyclingCadence.flatMap { it.samples }.filter { it.time >= dayStart && it.time < dayEnd }.map { it.revolutionsPerMinute }
            val powerValues = dPower.flatMap { it.samples }.filter { it.time >= dayStart && it.time < dayEnd }.map { it.power.inWatts }
            val skinDeltas = dSkin.flatMap { it.deltas }.filter { it.time >= dayStart && it.time < dayEnd }.map { it.delta.inCelsius }

            val dayRecords = mutableListOf<Record>()
            dayRecords.addAll(dSteps); dayRecords.addAll(dDistance); dayRecords.addAll(dActiveCalories); dayRecords.addAll(dTotalCalories)
            dayRecords.addAll(dSleeps); dayRecords.addAll(dHeart); dayRecords.addAll(dResting); dayRecords.addAll(dHrv); dayRecords.addAll(dOxygen)
            dayRecords.addAll(dRespiratory); dayRecords.addAll(dVo2); dayRecords.addAll(dSkin); dayRecords.addAll(dElevation); dayRecords.addAll(dFloors)
            dayRecords.addAll(dSpeed); dayRecords.addAll(dStepCadence); dayRecords.addAll(dCyclingCadence); dayRecords.addAll(dPower)
            dayRecords.addAll(dWorkouts); dayRecords.addAll(dWeights)
            val daySources = dayRecords.map { it.sourcePackage() }.filter { it.isNotBlank() }.distinct().sortedBy { sourcePriority(it) }

            dayArray.put(JSONObject().apply {
                put("date", date.toString())
                put("steps", dSteps.sumOf { it.count })
                put("distanceKm", dDistance.sumOf { it.distance.inKilometers })
                put("activeCaloriesKcal", dActiveCalories.sumOf { it.energy.inKilocalories })
                put("totalCaloriesKcal", dTotalCalories.sumOf { it.energy.inKilocalories })
                put("sleepHours", sleep.hours ?: JSONObject.NULL)
                put("deepSleepMinutes", sleep.deepMinutes)
                put("lightSleepMinutes", sleep.lightMinutes)
                put("remSleepMinutes", sleep.remMinutes)
                put("awakeMinutes", sleep.awakeMinutes)
                put("sleepStart", sleep.start?.toString() ?: JSONObject.NULL)
                put("sleepEnd", sleep.end?.toString() ?: JSONObject.NULL)
                put("sleepSessionCount", sleep.sessionCount)
                put("sleepStageCount", sleep.stageCount)
                put("sleepSuspicious", sleep.suspicious)
                put("sleepRawHours", sleep.rawHours)
                putNullable("averageHeartRate", heartSamples.map { it.beatsPerMinute.toDouble() }.averageOrNull())
                putNullable("minimumHeartRate", heartSamples.minOfOrNull { it.beatsPerMinute })
                putNullable("maximumHeartRate", heartSamples.maxOfOrNull { it.beatsPerMinute })
                put("heartRateSamples", heartSamples.size)
                putNullable("restingHeartRate", dResting.map { it.beatsPerMinute.toDouble() }.averageOrNull())
                putNullable("averageSpO2", oxygenValues.averageOrNull())
                putNullable("minimumSpO2", oxygenValues.minOrNull())
                putNullable("maximumSpO2", oxygenValues.maxOrNull())
                put("spO2Samples", oxygenValues.size)
                putNullable("averageHrvRmssdMs", hrvValues.averageOrNull())
                putNullable("minimumHrvRmssdMs", hrvValues.minOrNull())
                putNullable("maximumHrvRmssdMs", hrvValues.maxOrNull())
                put("hrvSamples", hrvValues.size)
                putNullable("averageRespiratoryRate", respiratoryValues.averageOrNull())
                putNullable("minimumRespiratoryRate", respiratoryValues.minOrNull())
                putNullable("maximumRespiratoryRate", respiratoryValues.maxOrNull())
                put("respiratorySamples", respiratoryValues.size)
                putNullable("vo2Max", dVo2.maxByOrNull { it.time }?.vo2MillilitersPerMinuteKilogram)
                putNullable("skinTempBaselineC", dSkin.mapNotNull { it.baseline?.inCelsius }.lastOrNull())
                putNullable("averageSkinTempDeltaC", skinDeltas.averageOrNull())
                putNullable("minimumSkinTempDeltaC", skinDeltas.minOrNull())
                putNullable("maximumSkinTempDeltaC", skinDeltas.maxOrNull())
                put("skinTempSamples", skinDeltas.size)
                put("elevationGainedM", dElevation.sumOf { it.elevation.inMeters })
                put("floorsClimbed", dFloors.sumOf { it.floors })
                putNullable("averageSpeedKmh", speedValues.averageOrNull())
                putNullable("maximumSpeedKmh", speedValues.maxOrNull())
                putNullable("averageStepCadence", stepCadenceValues.averageOrNull())
                putNullable("maximumStepCadence", stepCadenceValues.maxOrNull())
                putNullable("averageCyclingCadence", cyclingCadenceValues.averageOrNull())
                putNullable("maximumCyclingCadence", cyclingCadenceValues.maxOrNull())
                putNullable("averagePowerW", powerValues.averageOrNull())
                putNullable("maximumPowerW", powerValues.maxOrNull())
                putNullable("weightKg", dWeights.maxByOrNull { it.time }?.weight?.inKilograms)
                put("workoutCount", dWorkouts.size)
                put("workoutMinutes", dWorkouts.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() })
                put("sourcePackages", JSONArray(daySources))
            })
        }

        val preferredWorkouts = workouts.groupBy { it.startTime.atZone(zone).toLocalDate() }
            .values.flatMap { preferBestSource(it) }
            .distinctBy { "${it.startTime}|${it.endTime}|${it.exerciseType}" }

        val workoutArray = JSONArray()
        preferredWorkouts.forEach { record ->
            val ws = record.startTime
            val we = record.endTime
            val wSteps = preferBestSource(steps.filter { overlaps(it.startTime, it.endTime, ws, we) })
            val wDistance = preferBestSource(distances.filter { overlaps(it.startTime, it.endTime, ws, we) })
            val wActive = preferBestSource(activeCalories.filter { overlaps(it.startTime, it.endTime, ws, we) })
            val wTotal = preferBestSource(totalCalories.filter { overlaps(it.startTime, it.endTime, ws, we) })
            val wHeartSamples = preferBestSource(heart.filter { r -> r.samples.any { it.time >= ws && it.time <= we } })
                .flatMap { it.samples }.filter { it.time >= ws && it.time <= we }
            val wSpeed = preferBestSource(speed.filter { r -> r.samples.any { it.time >= ws && it.time <= we } })
                .flatMap { it.samples }.filter { it.time >= ws && it.time <= we }.map { it.speed.inKilometersPerHour }
            val wStepCadence = preferBestSource(stepCadence.filter { r -> r.samples.any { it.time >= ws && it.time <= we } })
                .flatMap { it.samples }.filter { it.time >= ws && it.time <= we }.map { it.rate }
            val wCyclingCadence = preferBestSource(cyclingCadence.filter { r -> r.samples.any { it.time >= ws && it.time <= we } })
                .flatMap { it.samples }.filter { it.time >= ws && it.time <= we }.map { it.revolutionsPerMinute }
            val wPower = preferBestSource(power.filter { r -> r.samples.any { it.time >= ws && it.time <= we } })
                .flatMap { it.samples }.filter { it.time >= ws && it.time <= we }.map { it.power.inWatts }
            val wElevation = preferBestSource(elevation.filter { overlaps(it.startTime, it.endTime, ws, we) })
            val wFloors = preferBestSource(floors.filter { overlaps(it.startTime, it.endTime, ws, we) })

            workoutArray.put(JSONObject().apply {
                put("id", record.metadata.id)
                put("start", record.startTime.toString())
                put("end", record.endTime.toString())
                put("exerciseType", record.exerciseType)
                put("title", record.title ?: "")
                put("notes", record.notes ?: "")
                put("durationMinutes", Duration.between(record.startTime, record.endTime).toMinutes())
                put("distanceKm", wDistance.sumOf { it.distance.inKilometers })
                put("steps", wSteps.sumOf { it.count })
                put("activeCaloriesKcal", wActive.sumOf { it.energy.inKilocalories })
                put("totalCaloriesKcal", wTotal.sumOf { it.energy.inKilocalories })
                putNullable("averageHeartRate", wHeartSamples.map { it.beatsPerMinute.toDouble() }.averageOrNull())
                putNullable("minimumHeartRate", wHeartSamples.minOfOrNull { it.beatsPerMinute })
                putNullable("maximumHeartRate", wHeartSamples.maxOfOrNull { it.beatsPerMinute })
                putNullable("averageSpeedKmh", wSpeed.averageOrNull())
                putNullable("maximumSpeedKmh", wSpeed.maxOrNull())
                putNullable("averageStepCadence", wStepCadence.averageOrNull())
                putNullable("maximumStepCadence", wStepCadence.maxOrNull())
                putNullable("averageCyclingCadence", wCyclingCadence.averageOrNull())
                putNullable("maximumCyclingCadence", wCyclingCadence.maxOrNull())
                putNullable("averagePowerW", wPower.averageOrNull())
                putNullable("maximumPowerW", wPower.maxOrNull())
                put("elevationGainedM", wElevation.sumOf { it.elevation.inMeters })
                put("floorsClimbed", wFloors.sumOf { it.floors })
                put("segmentsJson", JSONArray(record.segments.map { it.toString() }).toString())
                put("lapsJson", JSONArray(record.laps.map { it.toString() }).toString())
                put("routeState", record.exerciseRouteResult.javaClass.simpleName)
                put("sourcePackage", record.sourcePackage())
                put("sourceName", sourceName(record.sourcePackage()))
            })
        }

        val sleepArray = JSONArray()
        preferBestSource(sleeps).forEach { record ->
            val stageTotals = stageTotals(record.stages, record.startTime, record.endTime)
            sleepArray.put(JSONObject().apply {
                put("id", record.metadata.id)
                put("start", record.startTime.toString())
                put("end", record.endTime.toString())
                put("durationMinutes", Duration.between(record.startTime, record.endTime).toMinutes())
                put("title", record.title ?: "")
                put("notes", record.notes ?: "")
                put("deepSleepMinutes", stageTotals.deepMinutes)
                put("lightSleepMinutes", stageTotals.lightMinutes)
                put("remSleepMinutes", stageTotals.remMinutes)
                put("awakeMinutes", stageTotals.awakeMinutes)
                put("stageCount", record.stages.size)
                put("stagesJson", JSONArray(record.stages.map { stage ->
                    JSONObject().apply {
                        put("start", stage.startTime.toString())
                        put("end", stage.endTime.toString())
                        put("stage", stage.stage)
                        put("stageName", sleepStageName(stage.stage))
                    }
                }).toString())
                put("sourcePackage", record.sourcePackage())
                put("sourceName", sourceName(record.sourcePackage()))
            })
        }

        val measurements = JSONArray()
        fun addMeasurement(type: String, time: Instant?, startTime: Instant?, endTime: Instant?, value: Any?, unit: String, record: Record, idSuffix: String = "") {
            measurements.put(JSONObject().apply {
                put("id", record.metadata.id + idSuffix)
                put("time", time?.toString() ?: JSONObject.NULL)
                put("start", startTime?.toString() ?: JSONObject.NULL)
                put("end", endTime?.toString() ?: JSONObject.NULL)
                put("type", type)
                put("value", value ?: JSONObject.NULL)
                put("unit", unit)
                put("sourcePackage", record.sourcePackage())
                put("sourceName", sourceName(record.sourcePackage()))
            })
        }

        preferBestSource(steps).forEach { addMeasurement("Steps", null, it.startTime, it.endTime, it.count, "steps", it) }
        preferBestSource(distances).forEach { addMeasurement("Distance", null, it.startTime, it.endTime, it.distance.inKilometers, "km", it) }
        preferBestSource(activeCalories).forEach { addMeasurement("ActiveCalories", null, it.startTime, it.endTime, it.energy.inKilocalories, "kcal", it) }
        preferBestSource(totalCalories).forEach { addMeasurement("TotalCalories", null, it.startTime, it.endTime, it.energy.inKilocalories, "kcal", it) }
        preferBestSource(heart).forEach { r -> r.samples.forEachIndexed { i, s -> addMeasurement("HeartRate", s.time, null, null, s.beatsPerMinute, "bpm", r, "|$i|${s.time}") } }
        preferBestSource(restingHeart).forEach { addMeasurement("RestingHeartRate", it.time, null, null, it.beatsPerMinute, "bpm", it) }
        preferBestSource(hrv).forEach { addMeasurement("HRV_RMSSD", it.time, null, null, it.heartRateVariabilityMillis, "ms", it) }
        preferBestSource(oxygen).forEach { addMeasurement("SpO2", it.time, null, null, it.percentage.value, "%", it) }
        preferBestSource(respiratory).forEach { addMeasurement("RespiratoryRate", it.time, null, null, it.rate, "breaths/min", it) }
        preferBestSource(vo2).forEach { addMeasurement("VO2Max", it.time, null, null, it.vo2MillilitersPerMinuteKilogram, "ml/min/kg", it) }
        preferBestSource(skinTemperature).forEach { r ->
            r.baseline?.let { addMeasurement("SkinTemperatureBaseline", null, r.startTime, r.endTime, it.inCelsius, "°C", r, "|baseline") }
            r.deltas.forEachIndexed { i, d -> addMeasurement("SkinTemperatureDelta", d.time, null, null, d.delta.inCelsius, "°C", r, "|$i|${d.time}") }
        }
        preferBestSource(elevation).forEach { addMeasurement("ElevationGained", null, it.startTime, it.endTime, it.elevation.inMeters, "m", it) }
        preferBestSource(floors).forEach { addMeasurement("FloorsClimbed", null, it.startTime, it.endTime, it.floors, "floors", it) }
        preferBestSource(speed).forEach { r -> r.samples.forEachIndexed { i, s -> addMeasurement("Speed", s.time, null, null, s.speed.inKilometersPerHour, "km/h", r, "|$i|${s.time}") } }
        preferBestSource(stepCadence).forEach { r -> r.samples.forEachIndexed { i, s -> addMeasurement("StepCadence", s.time, null, null, s.rate, "steps/min", r, "|$i|${s.time}") } }
        preferBestSource(cyclingCadence).forEach { r -> r.samples.forEachIndexed { i, s -> addMeasurement("CyclingCadence", s.time, null, null, s.revolutionsPerMinute, "rpm", r, "|$i|${s.time}") } }
        preferBestSource(power).forEach { r -> r.samples.forEachIndexed { i, s -> addMeasurement("Power", s.time, null, null, s.power.inWatts, "W", r, "|$i|${s.time}") } }
        preferBestSource(weights).forEach { addMeasurement("Weight", it.time, null, null, it.weight.inKilograms, "kg", it) }

        return JSONObject().apply {
            put("schemaVersion", 2)
            put("syncedAt", Instant.now().toString())
            put("deviceId", android.os.Build.MODEL ?: "Android")
            put("rangeStart", startDate.toString())
            put("rangeEnd", endDate.minusDays(1).toString())
            put("days", dayArray)
            put("workouts", workoutArray)
            put("sleepSessions", sleepArray)
            put("measurements", measurements)
            put("sources", JSONArray().apply {
                allSources.forEach { pkg -> put(JSONObject().apply {
                    put("packageName", pkg)
                    put("name", sourceName(pkg))
                    put("priority", sourcePriority(pkg))
                }) }
            })
        }
    }

    private fun stageTotals(stages: List<SleepSessionRecord.Stage>, start: Instant, end: Instant): StageTotals {
        var deep = 0L
        var light = 0L
        var rem = 0L
        var awake = 0L
        stages.forEach { stage ->
            val clippedStart = if (stage.startTime < start) start else stage.startTime
            val clippedEnd = if (stage.endTime > end) end else stage.endTime
            if (clippedStart >= clippedEnd) return@forEach
            val minutes = Duration.between(clippedStart, clippedEnd).toMinutes()
            when (stage.stage) {
                SleepSessionRecord.STAGE_TYPE_DEEP -> deep += minutes
                SleepSessionRecord.STAGE_TYPE_LIGHT -> light += minutes
                SleepSessionRecord.STAGE_TYPE_REM -> rem += minutes
                SleepSessionRecord.STAGE_TYPE_AWAKE,
                SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> awake += minutes
            }
        }
        return StageTotals(deep, light, rem, awake)
    }

    private fun aggregateSleep(records: List<SleepSessionRecord>, dayStart: Instant, dayEnd: Instant): SleepAggregation {
        val intervals = records.mapNotNull { record ->
            val clippedStart = if (record.startTime < dayStart) dayStart else record.startTime
            val clippedEnd = if (record.endTime > dayEnd) dayEnd else record.endTime
            if (clippedStart < clippedEnd) SleepInterval(clippedStart, clippedEnd) else null
        }.sortedBy { it.start }

        val stages = records.flatMap { it.stages }
        val stageTotals = stageTotals(stages, dayStart, dayEnd)
        val main = records.maxByOrNull { record ->
            val s = if (record.startTime < dayStart) dayStart else record.startTime
            val e = if (record.endTime > dayEnd) dayEnd else record.endTime
            if (s < e) Duration.between(s, e).toMillis() else 0L
        }

        if (intervals.isEmpty()) {
            return SleepAggregation(null, false, 0.0, records.size, stages.size, null, null,
                stageTotals.deepMinutes, stageTotals.lightMinutes, stageTotals.remMinutes, stageTotals.awakeMinutes)
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
        val rawHours = rawMillis / 3_600_000.0
        val suspicious = uniqueHours > MAX_SLEEP_HOURS_PER_DAY

        return SleepAggregation(
            hours = if (suspicious) null else uniqueHours,
            suspicious = suspicious,
            rawHours = rawHours,
            sessionCount = records.size,
            stageCount = stages.size,
            start = main?.startTime,
            end = main?.endTime,
            deepMinutes = stageTotals.deepMinutes,
            lightMinutes = stageTotals.lightMinutes,
            remMinutes = stageTotals.remMinutes,
            awakeMinutes = stageTotals.awakeMinutes
        )
    }

    private fun sleepStageName(stage: Int): String = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> "Awake"
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> "Sleeping"
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "OutOfBed"
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "Light"
        SleepSessionRecord.STAGE_TYPE_DEEP -> "Deep"
        SleepSessionRecord.STAGE_TYPE_REM -> "REM"
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "AwakeInBed"
        else -> "Unknown"
    }

    private fun overlaps(start: Instant, end: Instant, rangeStart: Instant, rangeEnd: Instant): Boolean =
        start < rangeEnd && end > rangeStart

    private fun <T : Record> preferBestSource(records: List<T>): List<T> {
        if (records.isEmpty()) return emptyList()
        val bestPackage = records.map { it.sourcePackage() }.distinct().minByOrNull { sourcePriority(it) } ?: return records
        return records.filter { it.sourcePackage() == bestPackage }
    }

    private fun Record.sourcePackage(): String = metadata.dataOrigin.packageName

    private fun sourcePriority(packageName: String): Int {
        val value = packageName.lowercase()
        return when {
            value.contains("xiaomi") || value.contains("mifitness") || value.contains("mi.health") || value.contains("wearable") -> 0
            value == "com.google.android.apps.fitness" || (value.contains("google") && value.contains("fitness")) -> 1
            else -> 2
        }
    }

    private fun sourceName(packageName: String): String {
        val value = packageName.lowercase()
        return when {
            value.contains("xiaomi") || value.contains("mifitness") || value.contains("wearable") -> "Mi Fitness"
            value == "com.google.android.apps.fitness" || (value.contains("google") && value.contains("fitness")) -> "Google Fit"
            packageName.isBlank() -> "Неизвестный источник"
            else -> packageName
        }
    }

    private suspend inline fun <reified T : Record> safeReadAll(start: Instant, end: Instant): List<T> =
        runCatching { readAll<T>(start, end) }.getOrElse { emptyList() }

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

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

    private data class SleepInterval(val start: Instant, val end: Instant)
    private data class StageTotals(val deepMinutes: Long, val lightMinutes: Long, val remMinutes: Long, val awakeMinutes: Long)
    private data class SleepAggregation(
        val hours: Double?,
        val suspicious: Boolean,
        val rawHours: Double,
        val sessionCount: Int,
        val stageCount: Int,
        val start: Instant?,
        val end: Instant?,
        val deepMinutes: Long,
        val lightMinutes: Long,
        val remMinutes: Long,
        val awakeMinutes: Long
    )
    private data class Settings(val endpoint: String, val token: String, val days: Int)

    companion object {
        private const val MAX_SLEEP_HOURS_PER_DAY = 16.0
    }
}
