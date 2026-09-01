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
    private val aggregateReader = HealthAggregateReader(client)
    private val changesTracker = HealthChangesTracker(context, client)
    private val permissionDeniedTypes = linkedSetOf<String>()

    suspend fun sync(
        endpoint: String,
        token: String,
        days: Int,
        onProgress: (String) -> Unit
    ): SyncResult {
        val safeDays = days.coerceIn(1, 30)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val requestedDates = (safeDays - 1 downTo 0).map { today.minusDays(it.toLong()) }

        onProgress("Проверяю версию Google Sheets…")
        ServerCompatibility.requireV3(endpoint)
        onProgress("Проверяю изменения Health Connect…")
        val changes = changesTracker.collect(zone)
        val deletionDates = if (changes.deletedRecordIds.isNotEmpty()) {
            postDeletedRecordIds(endpoint, token, changes.deletedRecordIds)
        } else {
            emptySet()
        }

        val oldestReadable = today.minusDays(29)
        val dates = linkedSetOf<LocalDate>().apply {
            addAll(requestedDates)
            addAll(changes.affectedDates)
            addAll(deletionDates)
        }.filter { !it.isBefore(oldestReadable) && !it.isAfter(today) }
            .distinct()
            .sorted()

        var totalWorkouts = 0
        var totalMeasurements = 0
        val allSources = linkedSetOf<String>()

        dates.forEachIndexed { index, date ->
            onProgress("Читаю день ${index + 1}/${dates.size}: $date…")
            val result = syncDay(endpoint, token, date, zone, onProgress)
            totalWorkouts += result.workouts
            totalMeasurements += result.measurements
            allSources += result.sources
            if (index + 1 < dates.size) delay(HEALTH_CONNECT_DAY_PAUSE_MS)
        }

        return SyncResult(
            days = dates.size,
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
        permissionDeniedTypes.clear()
        val dayStart = date.atStartOfDay(zone).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
        val syncedAt = Instant.now().toString()
        val sources = linkedSetOf<String>()

        var restingHeartRate: Double? = null
        var vo2Max: Double? = null
        var skinTempBaselineC: Double? = null
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

        // Cumulative dashboard totals use Health Connect Aggregate API so overlapping origins
        // are deduplicated according to the user's Health Connect data priority.
        val aggregates = aggregateReader.readDay(dayStart, dayEnd)
        val stepsTotal = metricValue(aggregates.steps, sources)
        val distanceKm = metricValue(aggregates.distance, sources)?.inKilometers
        val activeCaloriesKcal = metricValue(aggregates.activeCalories, sources)?.inKilocalories
        val totalCaloriesKcal = metricValue(aggregates.totalCalories, sources)?.inKilocalories
        val elevationGainedM = metricValue(aggregates.elevation, sources)?.inMeters
        val floorsClimbed = metricValue(aggregates.floors, sources)

        // Interval totals: one type is loaded, summarized, emitted and then becomes collectible.
        run {
            val records = preferBestSource(safeReadAll<StepsRecord>(dayStart, dayEnd))
            addSources(records, sources)
            for (r in records) emit("Steps", null, r.startTime, r.endTime, r.count, "steps", r)
        }
        run {
            val records = preferBestSource(safeReadAll<DistanceRecord>(dayStart, dayEnd))
            addSources(records, sources)
            for (r in records) emit("Distance", null, r.startTime, r.endTime, r.distance.inKilometers, "km", r)
        }
        run {
            val records = preferBestSource(safeReadAll<ActiveCaloriesBurnedRecord>(dayStart, dayEnd))
            addSources(records, sources)
            for (r in records) emit("ActiveCalories", null, r.startTime, r.endTime, r.energy.inKilocalories, "kcal", r)
        }
        run {
            val records = preferBestSource(safeReadAll<TotalCaloriesBurnedRecord>(dayStart, dayEnd))
            addSources(records, sources)
            for (r in records) emit("TotalCalories", null, r.startTime, r.endTime, r.energy.inKilocalories, "kcal", r)
        }

        run {
            // Attribute each complete sleep session to the day when the user wakes up.
            // This keeps an overnight sleep intact and allows several sessions on the same day
            // (main sleep, nap, additional sleep) without splitting them at midnight.
            val sleepQueryStart = dayStart.minus(Duration.ofHours(24))
            val records = safeReadAll<SleepSessionRecord>(sleepQueryStart, dayEnd)
                .filter { it.endTime.atZone(zone).toLocalDate() == date }
                .distinctBy { "${it.startTime}|${it.endTime}|${it.sourcePackage()}" }
            addSources(records, sources)
            sleepSummary = aggregateSleep(records)
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
            for (r in records) emit("ElevationGained", null, r.startTime, r.endTime, r.elevation.inMeters, "m", r)
        }
        run {
            val records = preferBestSource(safeReadAll<FloorsClimbedRecord>(dayStart, dayEnd))
            addSources(records, sources)
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

        // Make sure the final partial measurement batch is persisted before the day summary.
batcher.flush()

        val dayObject = JSONObject().apply { put("date", date.toString()) }
        val availableFields = linkedSetOf<String>()
        fun putField(key: String, value: Any?) {
            dayObject.put(key, value ?: JSONObject.NULL)
            availableFields += key
        }

        if (aggregates.steps is MetricRead.Available) putField("steps", stepsTotal)
        if (aggregates.distance is MetricRead.Available) putField("distanceKm", distanceKm)
        if (aggregates.activeCalories is MetricRead.Available) putField("activeCaloriesKcal", activeCaloriesKcal)
        if (aggregates.totalCalories is MetricRead.Available) putField("totalCaloriesKcal", totalCaloriesKcal)
        if (aggregates.elevation is MetricRead.Available) putField("elevationGainedM", elevationGainedM)
        if (aggregates.floors is MetricRead.Available) putField("floorsClimbed", floorsClimbed)

        if (isTypeReadable<SleepSessionRecord>()) {
            putField("sleepHours", sleepSummary.hours)
            putField("deepSleepMinutes", sleepSummary.deepMinutes)
            putField("lightSleepMinutes", sleepSummary.lightMinutes)
            putField("remSleepMinutes", sleepSummary.remMinutes)
            putField("awakeMinutes", sleepSummary.awakeMinutes)
            putField("sleepStart", sleepSummary.start?.toString())
            putField("sleepEnd", sleepSummary.end?.toString())
            putField("sleepSessionCount", sleepSummary.sessionCount)
            putField("sleepStageCount", sleepSummary.stageCount)
            putField("mainSleepHours", sleepSummary.mainHours)
            putField("napCount", sleepSummary.napCount)
            putField("napMinutes", sleepSummary.napMinutes)
        }
        if (isTypeReadable<HeartRateRecord>()) {
            putField("averageHeartRate", heartStats.average())
            putField("minimumHeartRate", heartStats.min)
            putField("maximumHeartRate", heartStats.max)
            putField("heartRateSamples", heartStats.count)
        }
        if (isTypeReadable<RestingHeartRateRecord>()) putField("restingHeartRate", restingHeartRate)
        if (isTypeReadable<OxygenSaturationRecord>()) {
            putField("averageSpO2", spo2Stats.average())
            putField("minimumSpO2", spo2Stats.min)
            putField("maximumSpO2", spo2Stats.max)
            putField("spO2Samples", spo2Stats.count)
        }
        if (isTypeReadable<HeartRateVariabilityRmssdRecord>()) {
            putField("averageHrvRmssdMs", hrvStats.average())
            putField("minimumHrvRmssdMs", hrvStats.min)
            putField("maximumHrvRmssdMs", hrvStats.max)
            putField("hrvSamples", hrvStats.count)
        }
        if (isTypeReadable<RespiratoryRateRecord>()) {
            putField("averageRespiratoryRate", respiratoryStats.average())
            putField("minimumRespiratoryRate", respiratoryStats.min)
            putField("maximumRespiratoryRate", respiratoryStats.max)
            putField("respiratorySamples", respiratoryStats.count)
        }
        if (isTypeReadable<Vo2MaxRecord>()) putField("vo2Max", vo2Max)
        if (isTypeReadable<SkinTemperatureRecord>()) {
            putField("skinTempBaselineC", skinTempBaselineC)
            putField("averageSkinTempDeltaC", skinDeltaStats.average())
            putField("minimumSkinTempDeltaC", skinDeltaStats.min)
            putField("maximumSkinTempDeltaC", skinDeltaStats.max)
            putField("skinTempSamples", skinDeltaStats.count)
        }
        if (isTypeReadable<SpeedRecord>()) {
            putField("averageSpeedKmh", speedStats.average())
            putField("maximumSpeedKmh", speedStats.max)
        }
        if (isTypeReadable<StepsCadenceRecord>()) {
            putField("averageStepCadence", stepCadenceStats.average())
            putField("maximumStepCadence", stepCadenceStats.max)
        }
        if (isTypeReadable<CyclingPedalingCadenceRecord>()) {
            putField("averageCyclingCadence", cyclingCadenceStats.average())
            putField("maximumCyclingCadence", cyclingCadenceStats.max)
        }
        if (isTypeReadable<PowerRecord>()) {
            putField("averagePowerW", powerStats.average())
            putField("maximumPowerW", powerStats.max)
        }
        if (isTypeReadable<WeightRecord>()) putField("weightKg", weightKg)
        if (isTypeReadable<ExerciseSessionRecord>()) {
            putField("workoutCount", workoutRecords.size)
            putField("workoutMinutes", workoutRecords.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() })
        }
        putField("sourcePackages", jsonStringArray(sources))
        dayObject.put("availableFields", JSONArray(availableFields.toList()))

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

    private suspend fun postDeletedRecordIds(
        endpoint: String,
        token: String,
        recordIds: Set<String>
    ): Set<LocalDate> {
        if (recordIds.isEmpty()) return emptySet()
        val body = JSONObject().apply {
            put("token", token)
            put("action", "healthChangesV3")
            put("schemaVersion", 3)
            put("deletedRecordIds", JSONArray(recordIds.toList()))
        }
        val response = postBody(endpoint, body) ?: return emptySet()
        val result = linkedSetOf<LocalDate>()
        val dates = response.optJSONArray("affectedDates") ?: return result
        for (i in 0 until dates.length()) {
            runCatching { LocalDate.parse(dates.getString(i)) }.getOrNull()?.let(result::add)
        }
        return result
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
            put("action", "healthSyncV3")
            put("schemaVersion", 3)
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
        records: List<SleepSessionRecord>
    ): SleepAggregation {
        val intervals = records
            .filter { it.startTime < it.endTime }
            .map { SleepInterval(it.startTime, it.endTime) }
            .sortedBy { it.start }

        val stageList = records.flatMap { it.stages }
        if (intervals.isEmpty()) {
            return SleepAggregation(
                hours = null,
                suspicious = false,
                rawHours = 0.0,
                sessionCount = records.size,
                stageCount = stageList.size,
                start = null,
                end = null,
                mainHours = null,
                napCount = 0,
                napMinutes = 0,
                deepMinutes = 0,
                lightMinutes = 0,
                remMinutes = 0,
                awakeMinutes = 0
            )
        }

        val stage = deduplicatedStageTotals(records)
        val rawMillis = intervals.sumOf { Duration.between(it.start, it.end).toMillis() }

        // Union all sleep intervals so overlapping duplicate sessions cannot inflate total sleep.
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

        val main = merged.maxByOrNull { Duration.between(it.start, it.end).toMillis() }
        val naps = merged.filter { it != main }
        val mainHours = main?.let { Duration.between(it.start, it.end).toMillis() / 3_600_000.0 }
        val napMinutes = naps.sumOf { Duration.between(it.start, it.end).toMinutes() }

        return SleepAggregation(
            hours = if (suspicious) null else uniqueHours,
            suspicious = suspicious,
            rawHours = rawMillis / 3_600_000.0,
            sessionCount = records.size,
            stageCount = stageList.size,
            start = main?.start,
            end = main?.end,
            mainHours = mainHours,
            napCount = naps.size,
            napMinutes = napMinutes,
            deepMinutes = stage.deepMinutes,
            lightMinutes = stage.lightMinutes,
            remMinutes = stage.remMinutes,
            awakeMinutes = stage.awakeMinutes
        )
    }

    private fun deduplicatedStageTotals(records: List<SleepSessionRecord>): StageTotals {
        data class Segment(
            val start: Instant,
            val end: Instant,
            val type: Int,
            val priority: Int,
            val source: String
        )
        val segments = records.flatMap { record ->
            val source = record.sourcePackage()
            record.stages.map { stage ->
                Segment(stage.startTime, stage.endTime, stage.stage, sourcePriority(source), source)
            }
        }.filter { it.start < it.end }
        if (segments.isEmpty()) return StageTotals(0, 0, 0, 0)

        val boundaries = segments.flatMap { listOf(it.start, it.end) }.distinct().sorted()
        var deep = 0L
        var light = 0L
        var rem = 0L
        var awake = 0L
        for (index in 0 until boundaries.lastIndex) {
            val start = boundaries[index]
            val end = boundaries[index + 1]
            if (start >= end) continue
            val chosen = segments.asSequence()
                .filter { it.start < end && it.end > start }
                .minWithOrNull(compareBy<Segment> { it.priority }.thenBy { it.source })
                ?: continue
            val minutes = Duration.between(start, end).toMinutes()
            when (chosen.type) {
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
                if (isPermissionFailure(error)) {
                    permissionDeniedTypes += typeKey<T>()
                    return emptyList()
                }

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

    private inline fun <reified T : Record> typeKey(): String =
        T::class.qualifiedName ?: T::class.simpleName ?: "unknown"

    private inline fun <reified T : Record> isTypeReadable(): Boolean =
        typeKey<T>() !in permissionDeniedTypes

    private fun <T> metricValue(read: MetricRead<T>, sources: MutableSet<String>): T? = when (read) {
        is MetricRead.Available -> {
            sources += read.sourcePackages
            read.value
        }
        is MetricRead.PermissionDenied -> null
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
        val mainHours: Double?,
        val napCount: Int,
        val napMinutes: Long,
        val deepMinutes: Long,
        val lightMinutes: Long,
        val remMinutes: Long,
        val awakeMinutes: Long
    ) {
        companion object {
            fun empty() = SleepAggregation(null, false, 0.0, 0, 0, null, null, null, 0, 0, 0, 0, 0, 0)
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
