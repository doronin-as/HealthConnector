from pathlib import Path

p = Path('app/src/main/java/ru/doronin/healthconnector/HealthSyncStreamer.kt')
s = p.read_text()

marker = ') {\n\n    suspend fun sync('
replacement = ') {\n    private val aggregateReader = HealthAggregateReader(client)\n    private val changesTracker = HealthChangesTracker(context, client)\n    private val permissionDeniedTypes = linkedSetOf<String>()\n\n    suspend fun sync('
if marker not in s:
    raise SystemExit('class marker not found')
s = s.replace(marker, replacement, 1)

start = s.index('    suspend fun sync(\n')
end = s.index('\n    private suspend fun syncDay(', start)
new_sync = '''    suspend fun sync(
        endpoint: String,
        token: String,
        days: Int,
        onProgress: (String) -> Unit
    ): SyncResult {
        val safeDays = days.coerceIn(1, 30)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val requestedDates = (safeDays - 1 downTo 0).map { today.minusDays(it.toLong()) }

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
'''
s = s[:start] + new_sync + s[end:]

needle = '''        val dayStart = date.atStartOfDay(zone).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
        val syncedAt = Instant.now().toString()'''
repl = '''        permissionDeniedTypes.clear()
        val dayStart = date.atStartOfDay(zone).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
        val syncedAt = Instant.now().toString()'''
if needle not in s:
    raise SystemExit('syncDay header not found')
s = s.replace(needle, repl, 1)

old_vars = '''        var stepsTotal = 0L
        var hasStepRecords = false
        var distanceKm = 0.0
        var activeCaloriesKcal = 0.0
        var totalCaloriesKcal = 0.0
        var restingHeartRate: Double? = null
        var vo2Max: Double? = null
        var skinTempBaselineC: Double? = null
        var elevationGainedM = 0.0
        var floorsClimbed = 0.0
        var weightKg: Double? = null'''
new_vars = '''        var restingHeartRate: Double? = null
        var vo2Max: Double? = null
        var skinTempBaselineC: Double? = null
        var weightKg: Double? = null'''
if old_vars not in s:
    raise SystemExit('metric vars not found')
s = s.replace(old_vars, new_vars, 1)

interval_marker = '        // Interval totals: one type is loaded, summarized, emitted and then becomes collectible.\n'
aggregate_block = '''        // Cumulative dashboard totals use Health Connect Aggregate API so overlapping origins
        // are deduplicated according to the user's Health Connect data priority.
        val aggregates = aggregateReader.readDay(dayStart, dayEnd)
        val stepsTotal = metricValue(aggregates.steps, sources)
        val distanceKm = metricValue(aggregates.distance, sources)?.inKilometers
        val activeCaloriesKcal = metricValue(aggregates.activeCalories, sources)?.inKilocalories
        val totalCaloriesKcal = metricValue(aggregates.totalCalories, sources)?.inKilocalories
        val elevationGainedM = metricValue(aggregates.elevation, sources)?.inMeters
        val floorsClimbed = metricValue(aggregates.floors, sources)

'''
if interval_marker not in s:
    raise SystemExit('interval marker not found')
s = s.replace(interval_marker, aggregate_block + interval_marker, 1)

for old in [
    '            hasStepRecords = records.isNotEmpty()\n            stepsTotal = records.sumOf { it.count }\n',
    '            distanceKm = records.sumOf { it.distance.inKilometers }\n',
    '            activeCaloriesKcal = records.sumOf { it.energy.inKilocalories }\n',
    '            totalCaloriesKcal = records.sumOf { it.energy.inKilocalories }\n',
    '            elevationGainedM = records.sumOf { it.elevation.inMeters }\n',
    '            floorsClimbed = records.sumOf { it.floors }\n',
]:
    if old not in s:
        raise SystemExit('raw total assignment not found: ' + old.strip())
    s = s.replace(old, '', 1)

old_sleep = '''            val records = preferBestSource(safeReadAll<SleepSessionRecord>(sleepQueryStart, dayEnd))
                .filter { it.endTime.atZone(zone).toLocalDate() == date }
                .distinctBy { "${it.startTime}|${it.endTime}|${it.sourcePackage()}" }'''
new_sleep = '''            val records = safeReadAll<SleepSessionRecord>(sleepQueryStart, dayEnd)
                .filter { it.endTime.atZone(zone).toLocalDate() == date }
                .distinctBy { "${it.startTime}|${it.endTime}|${it.sourcePackage()}" }'''
if old_sleep not in s:
    raise SystemExit('sleep source block not found')
s = s.replace(old_sleep, new_sleep, 1)

gate_start = s.find('        if (!hasStepRecords) {')
if gate_start < 0:
    raise SystemExit('step gate not found')
gate_end_marker = '// Make sure the final partial measurement batch is persisted before the day summary.\n'
gate_end = s.find(gate_end_marker, gate_start)
if gate_end < 0:
    raise SystemExit('step gate end not found')
s = s[:gate_start] + '        ' + gate_end_marker + s[gate_end + len(gate_end_marker):]

obj_start = s.index('        val dayObject = JSONObject().apply {')
obj_end = s.index('\n        onProgress("Отправляю сводку за $date…")', obj_start)
new_obj = '''        val dayObject = JSONObject().apply { put("date", date.toString()) }
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
'''
s = s[:obj_start] + new_obj + s[obj_end:]

old_action = '            put("action", "healthSyncV2")\n            put("schemaVersion", 2)'
new_action = '            put("action", "healthSyncV3")\n            put("schemaVersion", 3)'
if old_action not in s:
    raise SystemExit('V2 action not found')
s = s.replace(old_action, new_action, 1)

post_marker = '    private suspend fun postHealthPayload(\n'
post_pos = s.index(post_marker)
delete_method = '''    private suspend fun postDeletedRecordIds(
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

'''
s = s[:post_pos] + delete_method + s[post_pos:]

permission_return = '                if (isPermissionFailure(error)) return emptyList()'
permission_repl = '''                if (isPermissionFailure(error)) {
                    permissionDeniedTypes += typeKey<T>()
                    return emptyList()
                }'''
if permission_return not in s:
    raise SystemExit('permission return not found')
s = s.replace(permission_return, permission_repl, 1)

helper_marker = '    private fun isPermissionFailure(error: Throwable): Boolean {'
helper_pos = s.index(helper_marker)
helper = '''    private inline fun <reified T : Record> typeKey(): String =
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

'''
s = s[:helper_pos] + helper + s[helper_pos:]

old_stage = '''        val stageRangeStart = intervals.first().start
        val stageRangeEnd = intervals.maxOf { it.end }
        val stage = stageTotals(stageList, stageRangeStart, stageRangeEnd)'''
if old_stage not in s:
    raise SystemExit('old stage aggregate not found')
s = s.replace(old_stage, '        val stage = deduplicatedStageTotals(records)', 1)

old_return = '''        return SleepAggregation(
            hours = if (suspicious) null else uniqueHours,
            suspicious = suspicious,
            rawHours = rawMillis / 3_600_000.0,
            sessionCount = records.size,
            stageCount = stageList.size,
            start = merged.first().start,
            end = merged.last().end,
            deepMinutes = stage.deepMinutes,
            lightMinutes = stage.lightMinutes,
            remMinutes = stage.remMinutes,
            awakeMinutes = stage.awakeMinutes
        )'''
new_return = '''        val main = merged.maxByOrNull { Duration.between(it.start, it.end).toMillis() }
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
        )'''
if old_return not in s:
    raise SystemExit('sleep return not found')
s = s.replace(old_return, new_return, 1)

empty_return = '''                start = null,
                end = null,
                deepMinutes = 0,'''
empty_repl = '''                start = null,
                end = null,
                mainHours = null,
                napCount = 0,
                napMinutes = 0,
                deepMinutes = 0,'''
if empty_return not in s:
    raise SystemExit('empty sleep return not found')
s = s.replace(empty_return, empty_repl, 1)

stage_pos = s.index('    private fun stageTotals(\n')
stage_helper = '''    private fun deduplicatedStageTotals(records: List<SleepSessionRecord>): StageTotals {
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

'''
s = s[:stage_pos] + stage_helper + s[stage_pos:]

old_sleep_data = '''        val start: Instant?,
        val end: Instant?,
        val deepMinutes: Long,'''
new_sleep_data = '''        val start: Instant?,
        val end: Instant?,
        val mainHours: Double?,
        val napCount: Int,
        val napMinutes: Long,
        val deepMinutes: Long,'''
if old_sleep_data not in s:
    raise SystemExit('sleep data fields not found')
s = s.replace(old_sleep_data, new_sleep_data, 1)

old_empty = '            fun empty() = SleepAggregation(null, false, 0.0, 0, 0, null, null, 0, 0, 0, 0)'
new_empty = '            fun empty() = SleepAggregation(null, false, 0.0, 0, 0, null, null, null, 0, 0, 0, 0, 0, 0)'
if old_empty not in s:
    raise SystemExit('sleep empty constructor not found')
s = s.replace(old_empty, new_empty, 1)

p.write_text(s)

p = Path('app/build.gradle.kts')
s = p.read_text()
if 'versionCode = 13' not in s or 'versionName = "1.5.7"' not in s:
    raise SystemExit('unexpected app version')
s = s.replace('versionCode = 13', 'versionCode = 14', 1)
s = s.replace('versionName = "1.5.7"', 'versionName = "1.6.0"', 1)
p.write_text(s)
