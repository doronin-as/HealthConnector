const DAY_HEADERS_V3 = DAY_HEADERS.concat([
  'MainSleepHours', 'NapCount', 'NapMinutes'
]);

const DAY_FIELD_TO_HEADER_V3 = Object.freeze({
  steps: 'Steps',
  distanceKm: 'DistanceKm',
  activeCaloriesKcal: 'ActiveCaloriesKcal',
  totalCaloriesKcal: 'TotalCaloriesKcal',
  sleepHours: 'SleepHours',
  deepSleepMinutes: 'DeepSleepMin',
  lightSleepMinutes: 'LightSleepMin',
  remSleepMinutes: 'RemSleepMin',
  awakeMinutes: 'AwakeMin',
  averageHeartRate: 'AvgHeartRate',
  minimumHeartRate: 'MinHeartRate',
  maximumHeartRate: 'MaxHeartRate',
  restingHeartRate: 'RestingHeartRate',
  averageSpO2: 'AvgSpO2',
  minimumSpO2: 'MinSpO2',
  maximumSpO2: 'MaxSpO2',
  weightKg: 'WeightKg',
  workoutCount: 'WorkoutCount',
  workoutMinutes: 'WorkoutMinutes',
  sourcePackages: 'Sources',
  sleepStart: 'SleepStart',
  sleepEnd: 'SleepEnd',
  sleepSessionCount: 'SleepSessionCount',
  sleepStageCount: 'SleepStageCount',
  heartRateSamples: 'HeartRateSamples',
  spO2Samples: 'SpO2Samples',
  averageHrvRmssdMs: 'AvgHrvRmssdMs',
  minimumHrvRmssdMs: 'MinHrvRmssdMs',
  maximumHrvRmssdMs: 'MaxHrvRmssdMs',
  hrvSamples: 'HrvSamples',
  averageRespiratoryRate: 'AvgRespiratoryRate',
  minimumRespiratoryRate: 'MinRespiratoryRate',
  maximumRespiratoryRate: 'MaxRespiratoryRate',
  respiratorySamples: 'RespiratorySamples',
  vo2Max: 'Vo2Max',
  skinTempBaselineC: 'SkinTempBaselineC',
  averageSkinTempDeltaC: 'AvgSkinTempDeltaC',
  minimumSkinTempDeltaC: 'MinSkinTempDeltaC',
  maximumSkinTempDeltaC: 'MaxSkinTempDeltaC',
  skinTempSamples: 'SkinTempSamples',
  elevationGainedM: 'ElevationGainedM',
  floorsClimbed: 'FloorsClimbed',
  averageSpeedKmh: 'AvgSpeedKmh',
  maximumSpeedKmh: 'MaxSpeedKmh',
  averageStepCadence: 'AvgStepCadence',
  maximumStepCadence: 'MaxStepCadence',
  averageCyclingCadence: 'AvgCyclingCadence',
  maximumCyclingCadence: 'MaxCyclingCadence',
  averagePowerW: 'AvgPowerW',
  maximumPowerW: 'MaxPowerW',
  mainSleepHours: 'MainSleepHours',
  napCount: 'NapCount',
  napMinutes: 'NapMinutes'
});

function importHealthPayloadV3_(spreadsheet, logSheet, payload) {
  const daysSheet = ensureSheet_(spreadsheet, DAYS_SHEET, DAY_HEADERS_V3);
  const workoutsSheet = ensureSheet_(spreadsheet, WORKOUTS_SHEET, WORKOUT_HEADERS);
  const sleepSheet = ensureSheet_(spreadsheet, SLEEP_SHEET, SLEEP_HEADERS);
  const measurementsSheet = ensureSheet_(spreadsheet, MEASUREMENTS_SHEET, MEASUREMENT_HEADERS);
  const syncedAt = payload.syncedAt || new Date().toISOString();

  upsertDayObjectsPartialV3_(daysSheet, payload.days || [], syncedAt, spreadsheet.getSpreadsheetTimeZone());

  const workoutRows = (payload.workouts || []).map(w => [
    w.id, w.start, w.end, w.exerciseType, nullable_(w.title), Number(w.durationMinutes || 0),
    nullable_(w.sourcePackage), syncedAt, nullable_(w.notes), nullable_(w.distanceKm), nullable_(w.steps),
    nullable_(w.activeCaloriesKcal), nullable_(w.totalCaloriesKcal), nullable_(w.averageHeartRate),
    nullable_(w.minimumHeartRate), nullable_(w.maximumHeartRate), nullable_(w.averageSpeedKmh), nullable_(w.maximumSpeedKmh),
    nullable_(w.averageStepCadence), nullable_(w.maximumStepCadence), nullable_(w.averageCyclingCadence), nullable_(w.maximumCyclingCadence),
    nullable_(w.averagePowerW), nullable_(w.maximumPowerW), nullable_(w.elevationGainedM), nullable_(w.floorsClimbed),
    nullable_(w.segmentsJson), nullable_(w.lapsJson), nullable_(w.routeState), nullable_(w.sourceName)
  ]);
  const sleepRows = (payload.sleepSessions || []).map(x => [
    x.id, x.start, x.end, nullable_(x.durationMinutes), nullable_(x.title), nullable_(x.notes),
    nullable_(x.deepSleepMinutes), nullable_(x.lightSleepMinutes), nullable_(x.remSleepMinutes), nullable_(x.awakeMinutes),
    nullable_(x.stageCount), nullable_(x.stagesJson), nullable_(x.sourcePackage), nullable_(x.sourceName), syncedAt
  ]);
  const measurementRows = (payload.measurements || []).map(x => [
    x.id, nullable_(x.time), nullable_(x.start), nullable_(x.end), nullable_(x.type), nullable_(x.value), nullable_(x.unit),
    nullable_(x.sourcePackage), nullable_(x.sourceName), syncedAt
  ]);

  upsertByKey_(workoutsSheet, workoutRows, 1);
  upsertByKey_(sleepSheet, sleepRows, 1);
  upsertByKey_(measurementsSheet, measurementRows, 1);

  logSheet.appendRow([
    new Date(), payload.deviceId || '', payload.rangeStart || '', payload.rangeEnd || '',
    (payload.days || []).length, workoutRows.length, 'OK',
    `V3 partial sync: сон ${sleepRows.length}; измерения ${measurementRows.length}`
  ]);

  return {
    ok: true,
    schemaVersion: 3,
    days: (payload.days || []).length,
    workouts: workoutRows.length,
    sleepSessions: sleepRows.length,
    measurements: measurementRows.length,
    message: `V3: дней ${(payload.days || []).length}; тренировок ${workoutRows.length}; сна ${sleepRows.length}; измерений ${measurementRows.length}`
  };
}

function upsertDayObjectsPartialV3_(sheet, days, syncedAt, tz) {
  if (!days.length) return;
  const headers = DAY_HEADERS_V3;
  const headerIndex = new Map(headers.map((value, index) => [value, index]));
  const existing = new Map();
  const lastRow = sheet.getLastRow();

  if (lastRow >= 2) {
    const dates = sheet.getRange(2, 1, lastRow - 1, 1).getValues();
    dates.forEach((row, index) => {
      const key = normalizeDateWithTz_(row[0], tz);
      if (key) existing.set(key, index + 2);
    });
  }

  days.forEach(day => {
    const dateKey = String(day.date || '').trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(dateKey)) return;
    const available = Array.isArray(day.availableFields) ? new Set(day.availableFields) : new Set(Object.keys(day));
    let rowNumber = existing.get(dateKey);
    let row;

    if (rowNumber) {
      row = sheet.getRange(rowNumber, 1, 1, headers.length).getValues()[0];
    } else {
      rowNumber = sheet.getLastRow() + 1;
      row = Array(headers.length).fill('');
      row[0] = Utilities.parseDate(`${dateKey} 00:00`, tz, 'yyyy-MM-dd HH:mm');
      existing.set(dateKey, rowNumber);
      if (rowNumber > 2) copyRowFormat_(sheet, rowNumber, headers.length);
    }

    available.forEach(key => {
      const header = DAY_FIELD_TO_HEADER_V3[key];
      if (!header || !headerIndex.has(header)) return;
      const index = headerIndex.get(header);
      const raw = day[key];
      row[index] = key === 'sourcePackages' && Array.isArray(raw)
        ? raw.join(', ')
        : nullable_(raw);
    });

    row[headerIndex.get('SyncedAt')] = syncedAt;
    sheet.getRange(rowNumber, 1, 1, headers.length).setValues([row]);
  });
}

function handleHealthChangesV3_(spreadsheet, logSheet, payload) {
  const ids = Array.isArray(payload.deletedRecordIds)
    ? Array.from(new Set(payload.deletedRecordIds.map(String).filter(Boolean)))
    : [];
  if (!ids.length) return { ok: true, schemaVersion: 3, deleted: 0, affectedDates: [] };
  if (ids.length > 10000) throw new Error('Слишком много удалений в одном пакете');

  const affected = new Set();
  let deleted = 0;
  deleted += deleteChangedRowsV3_(spreadsheet.getSheetByName(MEASUREMENTS_SHEET), ids, 'measurement', affected, spreadsheet.getSpreadsheetTimeZone());
  deleted += deleteChangedRowsV3_(spreadsheet.getSheetByName(SLEEP_SHEET), ids, 'sleep', affected, spreadsheet.getSpreadsheetTimeZone());
  deleted += deleteChangedRowsV3_(spreadsheet.getSheetByName(WORKOUTS_SHEET), ids, 'workout', affected, spreadsheet.getSpreadsheetTimeZone());

  logSheet.appendRow([
    new Date(), 'HealthConnectChanges', '', '', affected.size, 0, 'OK',
    `Удалено строк: ${deleted}; record ids: ${ids.length}`
  ]);
  return {
    ok: true,
    schemaVersion: 3,
    deleted,
    affectedDates: Array.from(affected).sort()
  };
}

function deleteChangedRowsV3_(sheet, ids, kind, affected, tz) {
  if (!sheet || sheet.getLastRow() < 2) return 0;
  const idSet = new Set(ids);
  const idPrefixes = ids.map(id => `${id}|`);
  const width = Math.min(4, sheet.getLastColumn());
  const values = sheet.getRange(2, 1, sheet.getLastRow() - 1, width).getValues();
  const rows = [];

  values.forEach((row, index) => {
    const id = String(row[0] || '');
    const match = idSet.has(id) || idPrefixes.some(prefix => id.startsWith(prefix));
    if (!match) return;
    rows.push(index + 2);

    let dateValue = null;
    if (kind === 'sleep') dateValue = row[2];
    else if (kind === 'workout') dateValue = row[1];
    else dateValue = row[1] || row[2] || row[3];
    const dateKey = isoDateKeyV3_(dateValue, tz);
    if (dateKey) affected.add(dateKey);
  });

  rows.sort((a, b) => b - a).forEach(row => sheet.deleteRow(row));
  return rows.length;
}

function isoDateKeyV3_(value, tz) {
  if (value instanceof Date && !isNaN(value)) return Utilities.formatDate(value, tz, 'yyyy-MM-dd');
  const text = String(value || '').trim();
  const iso = text.match(/^(\d{4}-\d{2}-\d{2})/);
  if (iso) return iso[1];
  const d = new Date(text);
  return isNaN(d) ? '' : Utilities.formatDate(d, tz, 'yyyy-MM-dd');
}
