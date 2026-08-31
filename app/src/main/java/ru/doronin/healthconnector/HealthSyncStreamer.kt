package ru.doronin.healthconnector

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Memory-bounded Health Connect synchronizer.
 *
 * The old implementation loaded every record type for the whole requested range and then created
 * several additional copies while building JSON. High-frequency records (heart rate, speed,
 * cadence and power) can make that exceed Android's heap limit. This implementation processes one
 * day and one record type at a time and uploads raw measurements in small idempotent batches.
 */
class HealthSyncStreamer(
    private val context: Context,
    private val client: HealthConnectClient
) {

    suspend fun sync(
        endpoint: String,
        token: String,
        days: Int,
        onProgress: (String) -> Unit
    ): SyncResult {
        val safeDays = days.coerceIn(1, 30)
        val zone = ZoneId.systemDefault()
        val endDate = LocalDate.now(zone).plusDays(1)
        val startDate = endDate.minusDays(safeDays.toLong())

        var totalWorkouts = 0
        var totalMeasurements = 0
        val allSources = linkedSetOf<String>()

        repeat(safeDays) { offset ->
            val date = startDate.plusDays(offset.toLong())
            onProgress("Читаю день ${offset + 1}/$safeDays: $date…")
            val result = syncDay(endpoint, token, date, zone, onProgress)
            totalWorkouts += result.workouts
            totalMeasurements += result.measurements
            allSources += result.sources
            if (offset + 1 < safeDays) delay(HEALTH_CONNECT_DAY_PAUSE_MS)
        }

        return SyncResult(
            days = safeDays,
            workouts = totalWorkouts,
            measurements = totalMeasurements,
            sources = allSources.size
        )
    }

    private suspend fun syncDay(
        endpoint: String,
        token: String,
        date: LocalDate,
        zone: ZoneId,
        onProgress: (String) -> Unit
    ): DayResult {
        val dayStart = date.atStartOfDay(zone).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
        val syncedAt = Instant.now().toString()
        val sources = linkedSetOf<String>()

        var stepsTotal = 0L
        var hasStepRecords = false
        var distanceKm = 0.0
        var activeCaloriesKcal = 0.0
        var totalCaloriesKcal = 0.0
        var restingHeartRate: Double? = null
        var vo2Max: Double? = null
        var skinTempBaselineC: Double? = null
        var elevationGainedM = 0.0
        var floorsClimbed = 0.0
        var weightKg: Double? = null

        val heartStats = Stats()
        val spo2Stats = Stats()
        val hrvStats = Stats()
        val respiratoryStats = Stats()
        val skinDeltaStats = Stats()
        val speedStats = Stats()
        val stepCadenceStats = Stats()
        val cyclingCadenceStats = Stats()
        val powerStats = Stats()

        var sleepSummary = SleepAggregation.empty()
        val sleepSessions = JSONArray()
        val workouts = JSONArray()

        val batcher = MeasurementBatcher(MAX_MEASUREMENTS_PER_REQUEST) { batch ->
            onProgress("Отправляю измерения за $date…")
            postHealthPayload(
                endpoint = endpoint,
                token = token,
                syncedAt = syncedAt,
                rangeDate = date,
                days = JSONArray(),
                workouts = JSONArray(),
                sleepSessions = JSONArray(),
                measurements = batch,
                sources = sources
            )
        }

        suspend fun emit(
            type: String,
            time: Instant?,
            start: Instant?,
            end: Instant?,
            value: Any?,
            unit: String,
            record: Record,
            suffix: String = ""
        ) {
            val pkg = record.sourcePackage()
            if (pkg.isNotBlank()) sources += pkg
            batcher.add(JSONObject().apply {
                put("id", measurementId(record, suffix))
                putNullable("time", time?.toString())
                putNullable("start", start?.toString())
                putNullable("end", end?.toString())
                put("type", type)
                putNullable("value", value)
                put("unit", unit)
                put("sourcePackage", pkg)
                put("sourceName", sourceName(pkg))
            })
        }

        // Interval totals: one type is loaded, summarized, emitted and then becomes collectible.
        run {
            val records = preferBestSource(safeReadAll<StepsRecord>(dayStart, dayEnd))
            addSources(records, sources)
            hasStepRecords = records.isNotEmpty()
            stepsTotal = records.sumOf { it.count }
            for (r in records) emit("Steps", null, r.startTime, r.endTime, r.count, "steps", r)
        }
        run {
            val records = preferBestSource(safeReadAll<DistanceRecord>(dayStart, dayEnd))
            addSources(records, sources)
            distanceKm = records.sumOf { it.distance.inKilometers }
            for (r in records) emit("Distance", null, r.startTime, r.endTime, r.distance.inKilometers, "km", r)
        }
        run {
            val records = preferBestSource(safeReadAll<ActiveCaloriesBurnedRecord>(dayStart, dayEnd))
            addSources(records, sources)
            activeCaloriesKcal = records.sumOf { it.energy.inKilocalories }
            for (r in records) emit("ActiveCalories", null, r.startTime, r.endTime, r.energy.inKilocalories, "kcal", r)
        }
        run {
            val records = preferBestSource(safeReadAll<TotalCaloriesBurnedRecord>(dayStart, dayEnd))
            addSources(records, sources)
            totalCaloriesKcal = records.sumOf { it.energy.inKilocalories }
            for (r in records) emit("TotalCalories", null, r.startTime, r.endTime, r.energy.inKilocalories, "kcal", r)
        }

        run {
            val records = preferBestSource(safeReadAll<SleepSessionRecord>(dayStart, dayEnd))
            addSources(records, sources)
            sleepSummary = aggregateSleep(records, dayStart, dayEnd)
            for (r in records) {
                val stageTotals = stageTotals(r.stages, r.startTime, r.endTime)
                sleepSessions.put(JSONObject().apply {
                    put("id", stableRecordId(r, "sleep|${r.startTime}|${r.endTime}"))
                    put("start", r.startTime.toString())
                    put("end", r.endTime.toString())
                    put("durationMinutes", Duration.between(r.startTime, r.endTime).toMinutes())
                    put("title", r.title ?: "")
                    put("notes", r.notes ?: "")
                    put("deepSleepMinutes", stageTotals.deepMinutes)
                    put("lightSleepMinutes", stageTotals.lightMinutes)
                    put("remSleepMinutes", stageTotals.remMinutes)
                    put("awakeMinutes", stageTotals.awakeMinutes)
                    put("stageCount", r.stages.size)
                    put("stagesJson", JSONArray().apply {
                        r.stages.forEach { stage ->
                            put(JSONObject().apply {
                                put("start", stage.startTime.toString())
                                put("end", stage.endTime.toString())
                                put("stage", stage.stage)
                                put("stageName", sleepStageName(stage.stage))
                            })
                        }
                    }.toString())
                    put("sourcePackage", r.sourcePackage())
                    put("sourceName", sourceName(r.sourcePackage()))
                })
            }
        }

        // High-frequency data is never flattened into another giant list. Samples are accumulated
        // into primitive statistics and streamed straight into measurement batches.
        run {
            val records = preferBestSource(safeReadAll<HeartRateRecord>(dayStart, dayEnd))
            addSources(records, sources)
            for (r in records) {
                r.samples.forEachIndexed { index, sample ->
                    if (sample.time >= dayStart && sample.time < dayEnd) {
                        heartStats.add(sample.beatsPerMinute.toDouble())
                        emit("HeartRate", sample.time, null, null, sample.beatsPerMinute, "bpm", r, "|$index|${sample.time}")
                    }
                }
            }
        }
        run {
            val records = preferBestSource(safeReadAll<RestingHeartRateRecord>(dayStart, dayEnd))
            addSources(records, sources)
            val stats = Stats()
            for (r in records) {
                stats.add(r.beatsPerMinute.toDouble())
                emit("RestingHeartRate", r.time, null, null, r.beatsPerMinute, "bpm", r)
            }
            restingHeartRate = stats.average()
        }
        run {
            val records = preferBestSource(safeReadAll<HeartRateVariabilityRmssdRecord>(dayStart, dayEnd))
            addSources(records, sources)
            for (r in records) {
                hrvStats.add(r.heartRateVariabilityMillis)
                emit("HRV_RMSSD", r.time, null, null, r.heartRateVariabilityMillis, "ms", r)
            }
        }
        run {
            val records = preferBestSource(safeReadAll<OxygenSaturationRecord>(dayStart, dayEnd))
            addSources(records, sources)
            for (r in records) {
                spo2Stats.add(r.percentage.value)
                emit("SpO2", r.time, null, null, r.percentage.value, "%", r)
            }
        }
        run {
            val records = preferBestSource(safeReadAll<RespiratoryRateRecord>(dayStart, dayEnd))
            addSources(records, sources)
            for (r in records) {
                respiratoryStats.add(r.rate)
                emit("RespiratoryRate", r.time, null, null, r.rate, "breaths/min", r)
            }
        }
        run {
            val records = preferBestSource(safeReadAll<Vo2MaxRecord>(dayStart, dayEnd))
            addSources(records, sources)
            val latest = records.maxByOrNull { it.time }
            vo2Max = latest?.vo2MillilitersPerMinuteKilogram
            for (r in records) emit("VO2Max", r.time, null, null, r.vo2MillilitersPerMinuteKilogram, "ml/min/kg", r)
        }
        run {
            val records = preferBestSource(safeReadAll<SkinTemperatureRecord>(dayStart, dayEnd))
            addSources(records, sources)
            skinTempBaselineC = records.maxByOrNull { it.endTime }?.baseline?.inCelsius
            for (r in records) {
                r.baseline?.let {
                    emit("SkinTemperatureBaseline", null, r.startTime, r.endTime, it.inCelsius, "°C", r, "|baseline")
                }
                r.deltas.forEachIndexed { index, delta ->
                    if (delta.time >= dayStart && delta.time < dayEnd) {
                        skinDeltaStats.add(delta.delta.inCelsius)
                        emit("SkinTemperatureDelta", delta.time, null, null, delta.delta.inCelsius, "°C", r, "|$index|${delta.time}")
                    }
                }
            }
        }
        run {
            val records = preferBestSource(safeReadAll<ElevationGainedRecord>(dayStart, dayEnd))
            addSources(records, sources)
            elevationGainedM = records.sumOf { it.elevation.inMeters }
            for (r in records) emit("ElevationGained", null, r.startTime, r.endTime, r.elevation.inMeters, "m", r)
        }
        run {
            val records = preferBestSource(safeReadAll<FloorsClimbedRecord>(dayStart, dayEnd))
            addSources(records, sources)
            floorsClimbed = records.sumOf { it.floors }
            for (r in records) emit("FloorsClimbed", null, r.startTime, r.endTime, r.floors, "floors", r)
        }
        run {
            val records = preferBestSource(safeReadAll<SpeedRecord>(dayStart, dayEnd))
            addSources(records, sources)
            for (r in records) {
                r.samples.forEachIndexed { index, sample ->
                    if (sample.time >= dayStart && sample.time < dayEnd) {
                        val value = sample.speed.inKilometersPerHour
                        speedStats.add(value)
                        emit("Speed", sample.time, null, null, value, "km/h", r, "|$index|${sample.time}")
                    }
                }
            }
        }
        run {
            val records = preferBestSource(safeReadAll<StepsCadenceRecord>(dayStart, dayEnd))
            addSources(records, sources)
            for (r in records) {
                r.samples.forEachIndexed { index, sample ->
                    if (sample.time >= dayStart && sample.time < dayEnd) {
                        stepCadenceStats.add(sample.rate)
                        emit("StepCadence", sample.time, null, null, sample.rate, "steps/min", r, "|$index|${sample.time}")
                    }
                }
            }
        }
        run {
            val records = preferBestSource(safeReadAll<CyclingPedalingCadenceRecord>(dayStart, dayEnd))
            addSources(records, sources)
            for (r in records) {
                r.samples.forEachIndexed { index, sample ->
                    if (sample.time >= dayStart && sample.time < dayEnd) {
                        cyclingCadenceStats.add(sample.revolutionsPerMinute)
                        emit("CyclingCadence", sample.time, null, null, sample.revolutionsPerMinute, "rpm", r, "|$index|${sample.time}")
                    }
                }
            }
        }
        run {
            val records = preferBestSource(safeReadAll<PowerRecord>(dayStart, dayEnd))
            addSources(records, sources)
            for (r in records) {
                r.samples.forEachIndexed { index, sample ->
                    if (sample.time >= dayStart && sample.time < dayEnd) {
                        val value = sample.power.inWatts
                        powerStats.add(value)
                        emit("Power", sample.time, null, null, value, "W", r, "|$index|${sample.time}")
                    }
                }
            }
        }
        run {
            val records = preferBestSource(safeReadAll<WeightRecord>(dayStart, dayEnd))
            addSources(records, sources)
            weightKg = records.maxByOrNull { it.time }?.weight?.inKilograms
            for (r in records) emit("Weight", r.time, null, null, r.weight.inKilograms, "kg", r)
        }

        val workoutRecords = preferBestSource(safeReadAll<ExerciseSessionRecord>(dayStart, dayEnd))
            .filter { it.startTime >= dayStart && it.startTime < dayEnd }
            .distinctBy { "${it.startTime}|${it.endTime}|${it.exerciseType}" }
        addSources(workoutRecords, sources)

        for (record in workoutRecords) {
            workouts.put(buildWorkoutJson(record, sources))
        }

        if (!hasStepRecords) {
    onProgress("Health Connect не вернул шаги за $date — существующая сводка сохранена")
    batcher.flush()
    return DayResult(
        workouts = workoutRecords.size,
        measurements = batcher.totalCount,
        sources = sources
    )
}

// Make sure the final partial measurement batch is persisted before the day summary.
batcher.flush()

        val dayObject = JSONObject().apply {
            put("date", date.toString())
            put("steps", stepsTotal)
            put("distanceKm", distanceKm)
            put("activeCaloriesKcal", activeCaloriesKcal)
            put("totalCaloriesKcal", totalCaloriesKcal)
            putNullable("sleepHours", sleepSummary.hours)
            put("deepSleepMinutes", sleepSummary.deepMinutes)
            put("lightSleepMinutes", sleepSummary.lightMinutes)
            put("remSleepMinutes", sleepSummary.remMinutes)
            put("awakeMinutes", sleepSummary.awakeMinutes)
            putNullable("sleepStart", sleepSummary.start?.toString())
            putNullable("sleepEnd", sleepSummary.end?.toString())
            put("sleepSessionCount", sleepSummary.sessionCount)
            put("sleepStageCount", sleepSummary.stageCount)
            put("sleepSuspicious", sleepSummary.suspicious)
            put("sleepRawHours", sleepSummary.rawHours)
            putNullable("averageHeartRate", heartStats.average())
            putNullable("minimumHeartRate", heartStats.min)
            putNullable("maximumHeartRate", heartStats.max)
            put("heartRateSamples", heartStats.count)
            putNullable("restingHeartRate", restingHeartRate)
            putNullable("averageSpO2", spo2Stats.average())
            putNullable("minimumSpO2", spo2Stats.min)
            putNullable("maximumSpO2", spo2Stats.max)
            put("spO2Samples", spo2Stats.count)
            putNullable("averageHrvRmssdMs", hrvStats.average())
            putNullable("minimumHrvRmssdMs", hrvStats.min)
            putNullable("maximumHrvRmssdMs", hrvStats.max)
            put("hrvSamples", hrvStats.count)
            putNullable("averageRespiratoryRate", respiratoryStats.average())
            putNullable("minimumRespiratoryRate", respiratoryStats.min)
            putNullable("maximumRespiratoryRate", respiratoryStats.max)
            put("respiratorySamples", respiratoryStats.count)
            putNullable("vo2Max", vo2Max)
            putNullable("skinTempBaselineC", skinTempBaselineC)
            putNullable("averageSkinTempDeltaC", skinDeltaStats.average())
            putNullable("minimumSkinTempDeltaC", skinDeltaStats.min)
            putNullable("maximumSkinTempDeltaC", skinDeltaStats.max)
            put("skinTempSamples", skinDeltaStats.count)
            put("elevationGainedM", elevationGainedM)
            put("floorsClimbed", floorsClimbed)
            putNullable("averageSpeedKmh", speedStats.average())
            putNullable("maximumSpeedKmh", speedStats.max)
            putNullable("averageStepCadence", stepCadenceStats.average())
            putNullable("maximumStepCadence", stepCadenceStats.max)
            putNullable("averageCyclingCadence", cyclingCadenceStats.average())
            putNullable("maximumCyclingCadence", cyclingCadenceStats.max)
            putNullable("averagePowerW", powerStats.average())
            putNullable("maximumPowerW", powerStats.max)
            putNullable("weightKg", weightKg)
            put("workoutCount", workoutRecords.size)
            put("workoutMinutes", workoutRecords.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() })
            put("sourcePackages", jsonStringArray(sources))
        }

        onProgress("Отправляю сводку за $date…")
        postHealthPayload(
            endpoint = endpoint,
            token = token,
            syncedAt = syncedAt,
            rangeDate = date,
            days = JSONArray().put(dayObject),
            workouts = workouts,
            sleepSessions = sleepSessions,
            measurements = JSONArray(),
            sources = sources
        )

        return DayResult(
            workouts = workoutRecords.size,
            measurements = batcher.totalCount,
            sources = sources
        )
    }

    private suspend fun buildWorkoutJson(
        record: ExerciseSessionRecord,
        sources: MutableSet<String>
    ): JSONObject {
        val start = record.startTime
        val end = record.endTime

        val steps = run {
            val r = preferBestSource(safeReadAll<StepsRecord>(start, end)); addSources(r, sources); r.sumOf { it.count }
        }
        val distance = run {
            val r = preferBestSource(safeReadAll<DistanceRecord>(start, end)); addSources(r, sources); r.sumOf { it.distance.inKilometers }
        }
        val activeCalories = run {
            val r = preferBestSource(safeReadAll<ActiveCaloriesBurnedRecord>(start, end)); addSources(r, sources); r.sumOf { it.energy.inKilocalories }
        }
        val totalCalories = run {
            val r = preferBestSource(safeReadAll<TotalCaloriesBurnedRecord>(start, end)); addSources(r, sources); r.sumOf { it.energy.inKilocalories }
        }
        val heart = run {
            val stats = Stats()
            val r = preferBestSource(safeReadAll<HeartRateRecord>(start, end)); addSources(r, sources)
            for (item in r) for (sample in item.samples) if (sample.time >= start && sample.time <= end) stats.add(sample.beatsPerMinute.toDouble())
            stats
        }
        val speed = run {
            val stats = Stats()
            val r = preferBestSource(safeReadAll<SpeedRecord>(start, end)); addSources(r, sources)
            for (item in r) for (sample in item.samples) if (sample.time >= start && sample.time <= end) stats.add(sample.speed.inKilometersPerHour)
            stats
        }
        val stepCadence = run {
            val stats = Stats()
            val r = preferBestSource(safeReadAll<StepsCadenceRecord>(start, end)); addSources(r, sources)
            for (item in r) for (sample in item.samples) if (sample.time >= start && sample.time <= end) stats.add(sample.rate)
            stats
        }
        val cyclingCadence = run {
            val stats = Stats()
            val r = preferBestSource(safeReadAll<CyclingPedalingCadenceRecord>(start, end)); addSources(r, sources)
            for (item in r) for (sample in item.samples) if (sample.time >= start && sample.time <= end) stats.add(sample.revolutionsPerMinute)
            stats
        }
        val power = run {
            val stats = Stats()
            val r = preferBestSource(safeReadAll<PowerRecord>(start, end)); addSources(r, sources)
            for (item in r) for (sample in item.samples) if (sample.time >= start && sample.time <= end) stats.add(sample.power.inWatts)
            stats
        }
        val elevation = run {
            val r = preferBestSource(safeReadAll<ElevationGainedRecord>(start, end)); addSources(r, sources); r.sumOf { it.elevation.inMeters }
        }
        val floors = run {
            val r = preferBestSource(safeReadAll<FloorsClimbedRecord>(start, end)); addSources(r, sources); r.sumOf { it.floors }
        }

        return JSONObject().apply {
            put("id", stableRecordId(record, "workout|$start|$end|${record.exerciseType}"))
            put("start", start.toString())
            put("end", end.toString())
            put("exerciseType", record.exerciseType)
            put("title", record.title ?: "")
            put("notes", record.notes ?: "")
            put("durationMinutes", Duration.between(start, end).toMinutes())
            put("distanceKm", distance)
            put("steps", steps)
            put("activeCaloriesKcal", activeCalories)
            put("totalCaloriesKcal", totalCalories)
            putNullable("averageHeartRate", heart.average())
            putNullable("minimumHeartRate", heart.min)
            putNullable("maximumHeartRate", heart.max)
            putNullable("averageSpeedKmh", speed.average())
            putNullable("maximumSpeedKmh", speed.max)
            putNullable("averageStepCadence", stepCadence.average())
            putNullable("maximumStepCadence", stepCadence.max)
            putNullable("averageCyclingCadence", cyclingCadence.average())
            putNullable("maximumCyclingCadence", cyclingCadence.max)
            putNullable("averagePowerW", power.average())
            putNullable("maximumPowerW", power.max)
            put("elevationGainedM", elevation)
            put("floorsClimbed", floors)
            put("segmentsJson", JSONArray(record.segments.map { it.toString() }).toString())
            put("lapsJson", JSONArray(record.laps.map { it.toString() }).toString())
            put("routeState", record.exerciseRouteResult.javaClass.simpleName)
            put("sourcePackage", record.sourcePackage())
            put("sourceName", sourceName(record.sourcePackage()))
        }
    }

    private suspend fun postHealthPayload(
        endpoint: String,
        token: String,
        syncedAt: String,
        rangeDate: LocalDate,
        days: JSONArray,
        workouts: JSONArray,
        sleepSessions: JSONArray,
        measurements: JSONArray,
        sources: Set<String>
    ): JSONObject? {
        val body = JSONObject().apply {
            put("token", token)
            put("action", "healthSyncV2")
            put("schemaVersion", 2)
            put("syncedAt", syncedAt)
            put("deviceId", android.os.Build.MODEL ?: "Android")
            put("rangeStart", rangeDate.toString())
            put("rangeEnd", rangeDate.toString())
            put("days", days)
            put("workouts", workouts)
            put("sleepSessions", sleepSessions)
            put("measurements", measurements)
            put("sources", JSONArray().apply {
                sources.sortedBy { sourcePriority(it) }.forEach { pkg ->
                    put(JSONObject().apply {
                        put("packageName", pkg)
                        put("name", sourceName(pkg))
                        put("priority", sourcePriority(pkg))
                    })
                }
            })
        }
        return postBody(endpoint, body)
    }

    private suspend fun postBody(endpoint: String, body: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.buffered().use { output ->
            body.toString().byteInputStream(Charsets.UTF_8).use { input -> input.copyTo(output, 16 * 1024) }
        }
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

    private fun aggregateSleep(
        records: List<SleepSessionRecord>,
        dayStart: Instant,
        dayEnd: Instant
    ): SleepAggregation {
        val intervals = records.mapNotNull { record ->
            val start = maxOf(record.startTime, dayStart)
            val end = minOf(record.endTime, dayEnd)
            if (start < end) SleepInterval(start, end) else null
        }.sortedBy { it.start }

        val stageList = records.flatMap { it.stages }
        val stage = stageTotals(stageList, dayStart, dayEnd)
        val main = records.maxByOrNull { record ->
            val start = maxOf(record.startTime, dayStart)
            val end = minOf(record.endTime, dayEnd)
            if (start < end) Duration.between(start, end).toMillis() else 0L
        }

        if (intervals.isEmpty()) {
            return SleepAggregation(
                hours = null,
                suspicious = false,
                rawHours = 0.0,
                sessionCount = records.size,
                stageCount = stageList.size,
                start = null,
                end = null,
                deepMinutes = stage.deepMinutes,
                lightMinutes = stage.lightMinutes,
                remMinutes = stage.remMinutes,
                awakeMinutes = stage.awakeMinutes
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
        val suspicious = uniqueHours > MAX_SLEEP_HOURS_PER_DAY
        return SleepAggregation(
            hours = if (suspicious) null else uniqueHours,
            suspicious = suspicious,
            rawHours = rawMillis / 3_600_000.0,
            sessionCount = records.size,
            stageCount = stageList.size,
            start = main?.startTime,
            end = main?.endTime,
            deepMinutes = stage.deepMinutes,
            lightMinutes = stage.lightMinutes,
            remMinutes = stage.remMinutes,
            awakeMinutes = stage.awakeMinutes
        )
    }

    private fun stageTotals(
        stages: List<SleepSessionRecord.Stage>,
        rangeStart: Instant,
        rangeEnd: Instant
    ): StageTotals {
        var deep = 0L
        var light = 0L
        var rem = 0L
        var awake = 0L
        for (stage in stages) {
            val start = maxOf(stage.startTime, rangeStart)
            val end = minOf(stage.endTime, rangeEnd)
            if (start >= end) continue
            val minutes = Duration.between(start, end).toMinutes()
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

    private fun <T : Record> preferBestSource(records: List<T>): List<T> {
        if (records.isEmpty()) return emptyList()
        val best = records.asSequence()
            .map { it.sourcePackage() }
            .filter { it.isNotBlank() }
            .distinct()
            .minByOrNull { sourcePriority(it) }
            ?: return records
        return records.filter { it.sourcePackage() == best }
    }

    private fun <T : Record> addSources(records: List<T>, target: MutableSet<String>) {
        records.asSequence().map { it.sourcePackage() }.filter { it.isNotBlank() }.forEach(target::add)
    }

    private fun Record.sourcePackage(): String = metadata.dataOrigin.packageName

    private fun stableRecordId(record: Record, fallback: String): String =
        record.metadata.id.takeIf { it.isNotBlank() } ?: "${record.sourcePackage()}|$fallback"

    private fun measurementId(record: Record, suffix: String): String =
        stableRecordId(record, "${record.javaClass.simpleName}|${record.hashCode()}") + suffix

    private fun sourcePriority(packageName: String): Int {
        val value = packageName.lowercase()
        return when {
            value.contains("xiaomi") || value.contains("mifitness") || value.contains("mi.health") || value.contains("wearable") -> 0
            value == "com.google.android.apps.fitness" || (value.contains("google") && value.contains("fitness")) -> 1
            else -> 2
        }
    }

    private fun sourceName(packageName: String): String {
        if (packageName.isBlank()) return "Неизвестный источник"
        val installed = runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString().trim()
        }.getOrNull()
        if (!installed.isNullOrBlank()) return installed

        val value = packageName.lowercase()
        return when {
            value.contains("xiaomi") || value.contains("mifitness") || value.contains("mi.health") -> "Mi Fitness"
            value.contains("fitbit") -> "Fitbit"
            value.contains("shealth") || (value.contains("samsung") && value.contains("health")) -> "Samsung Health"
            value.contains("garmin") -> "Garmin Connect"
            value == "com.google.android.apps.fitness" || (value.contains("google") && value.contains("fitness")) -> "Google Fit"
            else -> packageName
        }
    }

    private suspend inline fun <reified T : Record> safeReadAll(start: Instant, end: Instant): List<T> {
        var lastError: Exception? = null
        repeat(HEALTH_CONNECT_READ_RETRIES) { attempt ->
            try {
                return readAll(start, end)
            } catch (error: Exception) {
                // Android may wrap SecurityException inside HealthConnectException.
                // Missing permission for an optional metric must not abort the whole sync.
                if (isPermissionFailure(error)) return emptyList()

                lastError = error
                if (attempt + 1 < HEALTH_CONNECT_READ_RETRIES) {
                    delay(HEALTH_CONNECT_RETRY_BASE_MS * (attempt + 1L))
                }
            }
        }
        throw IllegalStateException(
            "Health Connect: ошибка чтения ${T::class.simpleName}: ${lastError?.message ?: "неизвестная ошибка"}",
            lastError
        )
    }

    private fun isPermissionFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        repeat(8) {
            val value = current ?: return false
            if (value is SecurityException) return true

            val message = value.message.orEmpty()
            if (
                message.contains("SecurityException", ignoreCase = true) ||
                message.contains("does not have permission", ignoreCase = true) ||
                message.contains("permission to read data", ignoreCase = true) ||
                message.contains("permission denied", ignoreCase = true)
            ) {
                return true
            }
            current = value.cause
        }
        return false
    }

    private suspend inline fun <reified T : Record> readAll(start: Instant, end: Instant): List<T> {
        val all = ArrayList<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = T::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageSize = HEALTH_CONNECT_PAGE_SIZE,
                    pageToken = pageToken
                )
            )
            all.addAll(response.records)
            pageToken = response.pageToken
        } while (pageToken != null)
        return all
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun jsonStringArray(values: Collection<String>): JSONArray = JSONArray().apply {
        values.forEach { put(it) }
    }

    data class SyncResult(
        val days: Int,
        val workouts: Int,
        val measurements: Int,
        val sources: Int
    )

    private data class DayResult(
        val workouts: Int,
        val measurements: Int,
        val sources: Set<String>
    )

    private class Stats {
        var count: Int = 0
            private set
        var min: Double? = null
            private set
        var max: Double? = null
            private set
        private var sum: Double = 0.0

        fun add(value: Double) {
            if (!value.isFinite()) return
            count++
            sum += value
            min = min?.let { kotlin.math.min(it, value) } ?: value
            max = max?.let { kotlin.math.max(it, value) } ?: value
        }

        fun average(): Double? = if (count == 0) null else sum / count
    }

    private class MeasurementBatcher(
        private val maxBatchSize: Int,
        private val sender: suspend (JSONArray) -> Unit
    ) {
        private val pending = ArrayList<JSONObject>(maxBatchSize)
        var totalCount: Int = 0
            private set

        suspend fun add(value: JSONObject) {
            pending += value
            totalCount++
            if (pending.size >= maxBatchSize) flush()
        }

        suspend fun flush() {
            if (pending.isEmpty()) return
            val batch = JSONArray()
            pending.forEach(batch::put)
            sender(batch)
            pending.clear()
        }
    }

    private data class SleepInterval(val start: Instant, val end: Instant)
    private data class StageTotals(
        val deepMinutes: Long,
        val lightMinutes: Long,
        val remMinutes: Long,
        val awakeMinutes: Long
    )

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
    ) {
        companion object {
            fun empty() = SleepAggregation(null, false, 0.0, 0, 0, null, null, 0, 0, 0, 0)
        }
    }

    companion object {
        private const val MAX_SLEEP_HOURS_PER_DAY = 16.0
        private const val HEALTH_CONNECT_PAGE_SIZE = 200
        private const val MAX_MEASUREMENTS_PER_REQUEST = 2000
        private const val HEALTH_CONNECT_READ_RETRIES = 3
        private const val HEALTH_CONNECT_RETRY_BASE_MS = 750L
        private const val HEALTH_CONNECT_DAY_PAUSE_MS = 1000L
    }
}
