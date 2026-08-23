package ru.doronin.healthconnector

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
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
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Health Connect reader with explicit source selection, de-duplication and quality diagnostics.
 *
 * Health Connect may contain the same physical activity copied by several apps. Cumulative
 * aggregation without a DataOrigin filter can therefore double count steps/calories. This engine
 * chooses one source per logical metric group for each day and always aggregates with a
 * DataOrigin filter.
 */
class HealthSyncEngine(
    private val context: Context,
    private val client: HealthConnectClient
) {

    suspend fun readPayload(days: Int): JSONObject {
        val safeDays = days.coerceIn(1, 30)
        val zone = ZoneId.systemDefault()
        val endDate = LocalDate.now(zone).plusDays(1)
        val startDate = endDate.minusDays(safeDays.toLong())
        val start = startDate.atStartOfDay(zone).toInstant()
        val end = endDate.atStartOfDay(zone).toInstant()

        val steps = readAll<StepsRecord>(start, end)
        val distances = readAll<DistanceRecord>(start, end)
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
            addAll(distances)
            addAll(sleeps)
            addAll(heart)
            addAll(restingHeart)
            addAll(oxygen)
            addAll(workouts)
            addAll(activeCalories)
            addAll(totalCalories)
            addAll(weights)
        }

        val globalWarnings = mutableListOf<String>()
        val missingSpo2Days = mutableListOf<String>()
        val invalidSpo2Count = oxygen.count { it.percentage.value !in MIN_VALID_SPO2..MAX_VALID_SPO2 }
        if (invalidSpo2Count > 0) {
            globalWarnings += "Отброшено некорректных SpO₂: $invalidSpo2Count"
        }

        val dayArray = JSONArray()
        repeat(safeDays) { offset ->
            val date = startDate.plusDays(offset.toLong())
            val dayStart = date.atStartOfDay(zone).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()

            val daySteps = steps.filter { it.startTime < dayEnd && it.endTime > dayStart }
            val dayDistances = distances.filter { it.startTime < dayEnd && it.endTime > dayStart }
            val daySleeps = sleeps.filter { it.startTime < dayEnd && it.endTime > dayStart }
            val dayHeart = heart.filter { record -> record.samples.any { it.time >= dayStart && it.time < dayEnd } }
            val dayRestingHeart = restingHeart.filter { it.time >= dayStart && it.time < dayEnd }
            val dayOxygen = oxygen.filter { it.time >= dayStart && it.time < dayEnd }
            val dayActiveCalories = activeCalories.filter { it.startTime < dayEnd && it.endTime > dayStart }
            val dayTotalCalories = totalCalories.filter { it.startTime < dayEnd && it.endTime > dayStart }
            val dayWeights = weights.filter { it.time >= dayStart && it.time < dayEnd }
            val dayWorkoutCandidates = workouts.filter { it.startTime >= dayStart && it.startTime < dayEnd }

            val activitySource = chooseGroupedSource(
                buildList<Record> { addAll(daySteps); addAll(dayDistances) },
                requiredKinds = 2
            ) { records ->
                setOf(
                    records.any { it is StepsRecord },
                    records.any { it is DistanceRecord }
                ).count { it }
            }
            val calorieSource = chooseGroupedSource(
                buildList<Record> { addAll(dayActiveCalories); addAll(dayTotalCalories) },
                requiredKinds = 2
            ) { records ->
                setOf(
                    records.any { it is ActiveCaloriesBurnedRecord },
                    records.any { it is TotalCaloriesBurnedRecord }
                ).count { it }
            }
            val sleepSource = chooseBestSource(daySleeps)
            val heartSource = chooseBestSource(dayHeart)
            val restingHeartSource = chooseBestSource(dayRestingHeart)
            val spo2Source = chooseBestSource(dayOxygen)
            val weightSource = chooseBestSource(dayWeights, preferScale = true)

            val aggregate = readDailyAggregate(
                start = dayStart,
                end = dayEnd,
                activitySource = activitySource,
                calorieSource = calorieSource,
                sleepSource = sleepSource,
                weightSource = weightSource
            )

            val selectedSleeps = recordsFromSource(daySleeps, sleepSource)
            val sleep = aggregateSleep(selectedSleeps, dayStart, dayEnd)
            val selectedHeart = recordsFromSource(dayHeart, heartSource)
            val heartSamples = selectedHeart.flatMap { it.samples }
                .filter { it.time >= dayStart && it.time < dayEnd }
            val selectedResting = recordsFromSource(dayRestingHeart, restingHeartSource)
            val selectedOxygen = recordsFromSource(dayOxygen, spo2Source)
                .filter { it.percentage.value in MIN_VALID_SPO2..MAX_VALID_SPO2 }

            val workoutResult = deduplicateWorkouts(dayWorkoutCandidates)
            val dayWarnings = mutableListOf<String>()

            val aggregateSleepHours = aggregate.sleepHours
            val sleepCandidate = aggregateSleepHours ?: sleep.hours
            val suspiciousSleep = sleepCandidate != null && sleepCandidate > MAX_SLEEP_HOURS_PER_DAY
            val effectiveSleepHours = if (suspiciousSleep) {
                dayWarnings += "Сон ${format1(sleepCandidate)} ч > $MAX_SLEEP_HOURS_PER_DAY ч — значение исключено"
                null
            } else {
                sleepCandidate
            }

            var effectiveActiveCalories = aggregate.activeCaloriesKcal
            val effectiveTotalCalories = aggregate.totalCaloriesKcal
            if (effectiveActiveCalories != null && effectiveTotalCalories != null &&
                effectiveActiveCalories > effectiveTotalCalories * 1.05
            ) {
                dayWarnings += "Активные ккал (${format1(effectiveActiveCalories)}) больше общих (${format1(effectiveTotalCalories)}) — активные исключены"
                effectiveActiveCalories = null
            }

            if (workoutResult.rejectedLong > 0) {
                dayWarnings += "Исключено слишком длинных тренировок: ${workoutResult.rejectedLong}"
            }
            if (workoutResult.duplicates > 0) {
                dayWarnings += "Удалено дублей тренировок: ${workoutResult.duplicates}"
            }
            val workoutMinutes = workoutResult.records.sumOf {
                Duration.between(it.startTime, it.endTime).toMinutes().coerceAtLeast(0)
            }
            if (workoutMinutes > MAX_WORKOUT_MINUTES_PER_DAY) {
                dayWarnings += "Суммарно тренировок $workoutMinutes мин — подозрительно много"
            }

            if (selectedOxygen.isEmpty()) missingSpo2Days += date.toString()

            val daySources = allRecords.asSequence()
                .filter { recordOverlapsDay(it, dayStart, dayEnd) }
                .map { it.sourcePackage() }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
                .toList()

            dayArray.put(JSONObject().apply {
                put("date", date.toString())
                put("steps", aggregate.steps)
                put("distanceKm", aggregate.distanceKm ?: JSONObject.NULL)
                put("activeCaloriesKcal", effectiveActiveCalories ?: JSONObject.NULL)
                put("totalCaloriesKcal", effectiveTotalCalories ?: JSONObject.NULL)
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
                put("restingHeartRate", selectedResting.map { it.beatsPerMinute.toDouble() }.averageOrNull())
                put("averageSpO2", selectedOxygen.map { it.percentage.value }.averageOrNull())
                put("minimumSpO2", selectedOxygen.minOfOrNull { it.percentage.value })
                put("weightKg", aggregate.weightKg ?: JSONObject.NULL)
                put("workoutCount", workoutResult.records.size)
                put("workoutMinutes", workoutMinutes)
                put("sourcePackages", JSONArray(daySources))

                // Source ownership is explicit. The server may ignore these today, but they make
                // diagnostics deterministic and allow future source-aware storage.
                put("activitySource", activitySource)
                put("stepsSource", activitySource)
                put("distanceSource", activitySource)
                put("calorieSource", calorieSource)
                put("activeCaloriesSource", calorieSource)
                put("totalCaloriesSource", calorieSource)
                put("sleepSource", sleepSource)
                put("heartSource", heartSource)
                put("restingHeartSource", restingHeartSource)
                put("spo2Source", spo2Source)
                put("weightSource", weightSource)
                put("workoutSources", JSONArray(workoutResult.records.map { it.sourcePackage() }.distinct()))
                put("warnings", JSONArray(dayWarnings))
            })
        }

        val dedupedWorkouts = workouts.groupBy { it.startTime.atZone(zone).toLocalDate() }
            .toSortedMap()
            .values
            .flatMap { deduplicateWorkouts(it).records }
        val workoutArray = JSONArray()
        dedupedWorkouts.forEach { record ->
            workoutArray.put(JSONObject().apply {
                put("id", stableWorkoutId(record))
                put("sourceRecordId", record.metadata.id)
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

        val sourceGroups = allRecords
            .filter { it.sourcePackage().isNotBlank() }
            .groupBy { it.sourcePackage() }
            .toList()
            .sortedWith(compareBy<Pair<String, List<Record>>> { sourcePriority(it.second) }.thenBy { sourceName(it.first) })

        return JSONObject().apply {
            put("schemaVersion", 2)
            put("appVersion", BuildConfig.VERSION_NAME)
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
                        put("recordCount", records.size)
                        put("devices", JSONArray(records.mapNotNull { it.deviceLabel() }.distinct().sorted()))
                        put("deviceTypes", JSONArray(records.mapNotNull { it.metadata.device?.type }.distinct().map { deviceTypeName(it) }))
                        put("recordTypes", JSONArray(records.map { it.javaClass.simpleName }.distinct().sorted()))
                    })
                }
            })
            put("diagnostics", JSONObject().apply {
                put("totalRecords", allRecords.size)
                put("recordCounts", JSONObject().apply {
                    put("steps", steps.size)
                    put("distance", distances.size)
                    put("sleep", sleeps.size)
                    put("heartRate", heart.size)
                    put("restingHeartRate", restingHeart.size)
                    put("spo2", oxygen.size)
                    put("exercise", workouts.size)
                    put("activeCalories", activeCalories.size)
                    put("totalCalories", totalCalories.size)
                    put("weight", weights.size)
                })
                put("spo2", JSONObject().apply {
                    put("records", oxygen.size)
                    put("validRecords", oxygen.size - invalidSpo2Count)
                    put("invalidRecords", invalidSpo2Count)
                    put("sources", JSONArray(oxygen.map { it.sourcePackage() }.filter { it.isNotBlank() }.distinct().sorted()))
                    put("missingDays", JSONArray(missingSpo2Days))
                })
                put("warnings", JSONArray(globalWarnings))
            })
        }
    }

    private suspend fun readDailyAggregate(
        start: Instant,
        end: Instant,
        activitySource: String,
        calorieSource: String,
        sleepSource: String,
        weightSource: String
    ): DailyAggregate {
        val activity = aggregateFromSource(
            start,
            end,
            activitySource,
            setOf(StepsRecord.COUNT_TOTAL, DistanceRecord.DISTANCE_TOTAL)
        )
        val calories = aggregateFromSource(
            start,
            end,
            calorieSource,
            setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, TotalCaloriesBurnedRecord.ENERGY_TOTAL)
        )
        val sleep = aggregateFromSource(
            start,
            end,
            sleepSource,
            setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL)
        )
        val weight = aggregateFromSource(
            start,
            end,
            weightSource,
            setOf(WeightRecord.WEIGHT_AVG)
        )

        return DailyAggregate(
            steps = activity?.get(StepsRecord.COUNT_TOTAL) ?: 0L,
            distanceKm = activity?.get(DistanceRecord.DISTANCE_TOTAL)?.inKilometers,
            activeCaloriesKcal = calories?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories,
            totalCaloriesKcal = calories?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories,
            sleepHours = sleep?.get(SleepSessionRecord.SLEEP_DURATION_TOTAL)?.toMillis()?.div(3_600_000.0),
            weightKg = weight?.get(WeightRecord.WEIGHT_AVG)?.inKilograms
        )
    }

    private suspend fun aggregateFromSource(
        start: Instant,
        end: Instant,
        source: String,
        metrics: Set<AggregateMetric<*>>
    ): AggregationResult? {
        if (source.isBlank()) return null
        return client.aggregate(
            AggregateRequest(
                metrics = metrics,
                timeRangeFilter = TimeRangeFilter.between(start, end),
                dataOriginFilter = setOf(DataOrigin(source))
            )
        )
    }

    private fun aggregateSleep(
        records: List<SleepSessionRecord>,
        dayStart: Instant,
        dayEnd: Instant
    ): SleepAggregation {
        val sessionIntervals = records.mapNotNull { record ->
            clippedInterval(record.startTime, record.endTime, dayStart, dayEnd)
        }
        val mergedSessions = mergeIntervals(sessionIntervals)
        val rawMillis = sessionIntervals.sumOf { Duration.between(it.start, it.end).toMillis() }
        val uniqueMillis = mergedSessions.sumOf { Duration.between(it.start, it.end).toMillis() }

        fun stageMinutes(stageType: Int): Long {
            val intervals = records.flatMap { record ->
                record.stages.filter { it.stage == stageType }.mapNotNull { stage ->
                    clippedInterval(stage.startTime, stage.endTime, dayStart, dayEnd)
                }
            }
            return mergeIntervals(intervals).sumOf { Duration.between(it.start, it.end).toMinutes() }
        }

        return SleepAggregation(
            hours = if (mergedSessions.isEmpty()) null else uniqueMillis / 3_600_000.0,
            rawHours = rawMillis / 3_600_000.0,
            sessionCount = records.size,
            mergedIntervalCount = mergedSessions.size,
            deepMinutes = stageMinutes(SleepSessionRecord.STAGE_TYPE_DEEP),
            lightMinutes = stageMinutes(SleepSessionRecord.STAGE_TYPE_LIGHT),
            remMinutes = stageMinutes(SleepSessionRecord.STAGE_TYPE_REM),
            awakeMinutes = stageMinutes(SleepSessionRecord.STAGE_TYPE_AWAKE) +
                stageMinutes(SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED) +
                stageMinutes(SleepSessionRecord.STAGE_TYPE_OUT_OF_BED)
        )
    }

    private fun deduplicateWorkouts(records: List<ExerciseSessionRecord>): WorkoutDedupResult {
        val rejectedLong = records.count {
            val minutes = Duration.between(it.startTime, it.endTime).toMinutes()
            minutes <= 0 || minutes > MAX_SINGLE_WORKOUT_MINUTES
        }
        val candidates = records.filter {
            val minutes = Duration.between(it.startTime, it.endTime).toMinutes()
            minutes in 1..MAX_SINGLE_WORKOUT_MINUTES
        }.sortedWith(
            compareBy<ExerciseSessionRecord> { sourcePriority(listOf(it)) }
                .thenBy { it.startTime }
                .thenByDescending { Duration.between(it.startTime, it.endTime).toMinutes() }
        )

        val accepted = mutableListOf<ExerciseSessionRecord>()
        var duplicates = 0
        for (candidate in candidates) {
            if (accepted.any { workoutsOverlapAsDuplicate(candidate, it) }) {
                duplicates++
            } else {
                accepted += candidate
            }
        }
        return WorkoutDedupResult(accepted.sortedBy { it.startTime }, duplicates, rejectedLong)
    }

    private fun workoutsOverlapAsDuplicate(a: ExerciseSessionRecord, b: ExerciseSessionRecord): Boolean {
        val overlapStart = maxOf(a.startTime, b.startTime)
        val overlapEnd = minOf(a.endTime, b.endTime)
        val overlap = if (overlapEnd > overlapStart) Duration.between(overlapStart, overlapEnd).seconds else 0L
        val aDuration = Duration.between(a.startTime, a.endTime).seconds.coerceAtLeast(1)
        val bDuration = Duration.between(b.startTime, b.endTime).seconds.coerceAtLeast(1)
        val overlapRatio = overlap.toDouble() / min(aDuration, bDuration).toDouble()
        val startDeltaMinutes = abs(Duration.between(a.startTime, b.startTime).toMinutes())
        val durationDeltaMinutes = abs(aDuration - bDuration) / 60

        val stronglyOverlapping = overlapRatio >= 0.60
        val nearlySameWindow = startDeltaMinutes <= 10 && durationDeltaMinutes <= 15
        return stronglyOverlapping || nearlySameWindow
    }

    private fun stableWorkoutId(record: ExerciseSessionRecord): String {
        val sourceId = record.metadata.id.trim()
        if (sourceId.isNotBlank()) return "${record.sourcePackage()}:$sourceId"
        return listOf(
            record.sourcePackage(),
            record.startTime.toString(),
            record.endTime.toString(),
            record.exerciseType.toString()
        ).joinToString("|")
    }

    private fun <T : Record> recordsFromSource(records: List<T>, source: String): List<T> {
        if (source.isBlank()) return emptyList()
        return records.filter { it.sourcePackage() == source }
    }

    private fun <T : Record> chooseBestSource(records: List<T>, preferScale: Boolean = false): String {
        if (records.isEmpty()) return ""
        return records.groupBy { it.sourcePackage() }
            .filterKeys { it.isNotBlank() }
            .entries
            .sortedWith(
                compareBy<Map.Entry<String, List<T>>> {
                    sourcePriority(it.value, preferScale)
                }.thenByDescending { it.value.size }
                    .thenBy { sourceName(it.key) }
            )
            .firstOrNull()?.key.orEmpty()
    }

    private fun chooseGroupedSource(
        records: List<Record>,
        requiredKinds: Int,
        coverage: (List<Record>) -> Int
    ): String {
        if (records.isEmpty()) return ""
        return records.groupBy { it.sourcePackage() }
            .filterKeys { it.isNotBlank() }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<Record>>> {
                    coverage(it.value).coerceAtMost(requiredKinds)
                }.thenBy { sourcePriority(it.value) }
                    .thenByDescending { it.value.size }
                    .thenBy { sourceName(it.key) }
            )
            .firstOrNull()?.key.orEmpty()
    }

    private fun sourcePriority(records: List<out Record>, preferScale: Boolean = false): Int {
        val deviceTypes = records.mapNotNull { it.metadata.device?.type }.toSet()
        val packageName = records.firstOrNull()?.sourcePackage().orEmpty()
        return when {
            preferScale && deviceTypes.contains(Device.TYPE_SCALE) -> 0
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
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString().trim()
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

    private fun clippedInterval(start: Instant, end: Instant, min: Instant, max: Instant): Interval? {
        val clippedStart = if (start < min) min else start
        val clippedEnd = if (end > max) max else end
        return if (clippedStart < clippedEnd) Interval(clippedStart, clippedEnd) else null
    }

    private fun mergeIntervals(intervals: List<Interval>): List<Interval> {
        if (intervals.isEmpty()) return emptyList()
        val sorted = intervals.sortedBy { it.start }
        val merged = mutableListOf<Interval>()
        var start = sorted.first().start
        var end = sorted.first().end
        for (interval in sorted.drop(1)) {
            if (!interval.start.isAfter(end)) {
                if (interval.end > end) end = interval.end
            } else {
                merged += Interval(start, end)
                start = interval.start
                end = interval.end
            }
        }
        merged += Interval(start, end)
        return merged
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
    private fun format1(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)

    private data class Interval(val start: Instant, val end: Instant)
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
    private data class WorkoutDedupResult(
        val records: List<ExerciseSessionRecord>,
        val duplicates: Int,
        val rejectedLong: Int
    )

    companion object {
        private const val MAX_SLEEP_HOURS_PER_DAY = 16.0
        private const val MAX_SINGLE_WORKOUT_MINUTES = 480L
        private const val MAX_WORKOUT_MINUTES_PER_DAY = 960L
        private const val MIN_VALID_SPO2 = 70.0
        private const val MAX_VALID_SPO2 = 100.0
        private val WEARABLE_DEVICE_TYPES = setOf(
            Device.TYPE_WATCH,
            Device.TYPE_FITNESS_BAND,
            Device.TYPE_RING,
            Device.TYPE_CHEST_STRAP
        )
    }
}
