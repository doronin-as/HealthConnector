/**
 * Health Dashboard Sync — Google Apps Script endpoint.
 * Health Connect + FatSecret CSV import.
 */

const DEFAULT_SPREADSHEET_ID = '1lb8VInEtXyawXUiNxe47iMebWCLKV7rH3hSuDxF4RwM';
const DAYS_SHEET = 'HC_Дни';
const WORKOUTS_SHEET = 'HC_Тренировки';
const LOG_SHEET = 'HC_Журнал';
const SLEEP_SHEET = 'HC_Сон';
const MEASUREMENTS_SHEET = 'HC_Измерения';
const DIARY_SHEET = 'Дневник';
const FOOD_TRACKER_SHEET = 'Трекер_питания';
const NUTRITION_SHEET = 'Питание';

const DAY_HEADERS = [
  'Date', 'Steps', 'DistanceKm', 'ActiveCaloriesKcal', 'TotalCaloriesKcal',
  'SleepHours', 'DeepSleepMin', 'LightSleepMin', 'RemSleepMin', 'AwakeMin',
  'AvgHeartRate', 'MinHeartRate', 'MaxHeartRate', 'RestingHeartRate',
  'AvgSpO2', 'MinSpO2', 'WeightKg', 'WorkoutCount', 'WorkoutMinutes',
  'Sources', 'SyncedAt',
  'SleepStart', 'SleepEnd', 'SleepSessionCount', 'SleepStageCount',
  'HeartRateSamples', 'MaxSpO2', 'SpO2Samples',
  'AvgHrvRmssdMs', 'MinHrvRmssdMs', 'MaxHrvRmssdMs', 'HrvSamples',
  'AvgRespiratoryRate', 'MinRespiratoryRate', 'MaxRespiratoryRate', 'RespiratorySamples',
  'Vo2Max', 'SkinTempBaselineC', 'AvgSkinTempDeltaC', 'MinSkinTempDeltaC', 'MaxSkinTempDeltaC', 'SkinTempSamples',
  'ElevationGainedM', 'FloorsClimbed', 'AvgSpeedKmh', 'MaxSpeedKmh',
  'AvgStepCadence', 'MaxStepCadence', 'AvgCyclingCadence', 'MaxCyclingCadence',
  'AvgPowerW', 'MaxPowerW'
];

const WORKOUT_HEADERS = [
  'Id', 'Start', 'End', 'ExerciseType', 'Title', 'DurationMin', 'SourcePackage', 'SyncedAt',
  'Notes', 'DistanceKm', 'Steps', 'ActiveCaloriesKcal', 'TotalCaloriesKcal',
  'AvgHeartRate', 'MinHeartRate', 'MaxHeartRate', 'AvgSpeedKmh', 'MaxSpeedKmh',
  'AvgStepCadence', 'MaxStepCadence', 'AvgCyclingCadence', 'MaxCyclingCadence',
  'AvgPowerW', 'MaxPowerW', 'ElevationGainedM', 'FloorsClimbed',
  'SegmentsJson', 'LapsJson', 'RouteState', 'SourceName'
];

const SLEEP_HEADERS = [
  'Id', 'Start', 'End', 'DurationMin', 'Title', 'Notes',
  'DeepSleepMin', 'LightSleepMin', 'RemSleepMin', 'AwakeMin', 'StageCount',
  'StagesJson', 'SourcePackage', 'SourceName', 'SyncedAt'
];

const MEASUREMENT_HEADERS = [
  'Id', 'Time', 'Start', 'End', 'Type', 'Value', 'Unit',
  'SourcePackage', 'SourceName', 'SyncedAt'
];

const LOG_HEADERS = [
  'Timestamp', 'DeviceId', 'RangeStart', 'RangeEnd', 'Days', 'Workouts',
  'Status', 'Message'
];

const FOOD_TRACKER_HEADERS = [
  'Дата', 'Прием', 'Белок', 'Углеводы', 'Жиры', 'Калории', 'Еда', 'Статус', 'Комментарий'
];

const NUTRITION_HEADERS = ['Дата', 'Белок', 'Углеводы', 'Жиры', 'Калории'];
const FATSECRET_COL = Object.freeze({ KCAL: 1, FAT: 2, CARBS: 4, PROTEIN: 7 });
const MEALS = new Set(['Завтрак', 'Обед', 'Полдник', 'Ужин', 'Перекус/Другое']);
const MEAL_ALIASES = Object.freeze({
  'Breakfast': 'Завтрак',
  'Lunch': 'Обед',
  'Afternoon Snack': 'Полдник',
  'Dinner': 'Ужин',
  'Snacks/Other': 'Перекус/Другое'
});
const IMPORT_STATUS = 'FatSecret CSV';
const KCAL_TOLERANCE = 2;
const MACRO_TOLERANCE = 0.2;

function setup() {
  const properties = PropertiesService.getScriptProperties();
  if (!properties.getProperty('SPREADSHEET_ID')) {
    properties.setProperty('SPREADSHEET_ID', DEFAULT_SPREADSHEET_ID);
  }
  let tokenCreated = false;
  if (!properties.getProperty('API_TOKEN')) {
    const token = Utilities.getUuid().replace(/-/g, '') + Utilities.getUuid().replace(/-/g, '');
    properties.setProperty('API_TOKEN', token);
    tokenCreated = true;
  }
  const spreadsheet = getSpreadsheet_();
  ensureSheet_(spreadsheet, DAYS_SHEET, DAY_HEADERS);
  ensureSheet_(spreadsheet, WORKOUTS_SHEET, WORKOUT_HEADERS);
  ensureSheet_(spreadsheet, LOG_SHEET, LOG_HEADERS);
  ensureSheet_(spreadsheet, SLEEP_SHEET, SLEEP_HEADERS);
  ensureSheet_(spreadsheet, MEASUREMENTS_SHEET, MEASUREMENT_HEADERS);

  const result = {
    spreadsheetId: properties.getProperty('SPREADSHEET_ID'),
    tokenConfigured: Boolean(properties.getProperty('API_TOKEN')),
    tokenCreated: tokenCreated
  };
  console.log(JSON.stringify(result));
  return result;
}

function doGet() {
  return json_({
    ok: true,
    service: 'Health Dashboard Sync',
    schemaVersion: 3,
    time: new Date().toISOString()
  });
}

function doPost(e) {
  const lock = LockService.getScriptLock();
  try {
    lock.waitLock(30000);
    const payload = JSON.parse((e.postData && e.postData.contents) || '{}');
    if (!payload || typeof payload !== 'object') throw new Error('Пустой JSON');

    const expectedToken = PropertiesService.getScriptProperties().getProperty('API_TOKEN');
    if (!expectedToken || payload.token !== expectedToken) {
      throw new Error('Неверный API-токен');
    }

    const spreadsheet = getSpreadsheet_();
    const logSheet = ensureSheet_(spreadsheet, LOG_SHEET, LOG_HEADERS);

    if (payload.action === 'fatsecretCsv') {
      return json_(importFatSecretCsv_(spreadsheet, logSheet, payload));
    }
    if (payload.action === 'healthChangesV3') {
      return json_(handleHealthChangesV3_(spreadsheet, logSheet, payload));
    }

    validateHealthPayload_(payload);
    if (payload.action === 'healthSyncV3') {
      return json_(importHealthPayloadV3_(spreadsheet, logSheet, payload));
    }
    return json_(importHealthPayload_(spreadsheet, logSheet, payload));
  } catch (error) {
    try {
      const spreadsheet = getSpreadsheet_();
      const logSheet = ensureSheet_(spreadsheet, LOG_SHEET, LOG_HEADERS);
      logSheet.appendRow([new Date(), '', '', '', 0, 0, 'ERROR', String(error)]);
    } catch (_) {}
    const message = String(error && error.message || error);
    return json_({ ok: false, error: message, message: message });
  } finally {
    try { lock.releaseLock(); } catch (_) {}
  }
}

function validateHealthPayload_(payload) {
  if (!Array.isArray(payload.days)) throw new Error('Поле days должно быть массивом');
  if (!Array.isArray(payload.workouts)) throw new Error('Поле workouts должно быть массивом');
  if (payload.sleepSessions != null && !Array.isArray(payload.sleepSessions)) throw new Error('Поле sleepSessions должно быть массивом');
  if (payload.measurements != null && !Array.isArray(payload.measurements)) throw new Error('Поле measurements должно быть массивом');
  if (payload.days.length > 31) throw new Error('Слишком большой диапазон дней');
  if (payload.workouts.length > 5000) throw new Error('Слишком много тренировок');
  if ((payload.sleepSessions || []).length > 5000) throw new Error('Слишком много сессий сна');
  if ((payload.measurements || []).length > 250000) throw new Error('Слишком много сырых измерений');
}

function importHealthPayload_(spreadsheet, logSheet, payload) {
  const daysSheet = ensureSheet_(spreadsheet, DAYS_SHEET, DAY_HEADERS);
  const workoutsSheet = ensureSheet_(spreadsheet, WORKOUTS_SHEET, WORKOUT_HEADERS);
  const sleepSheet = ensureSheet_(spreadsheet, SLEEP_SHEET, SLEEP_HEADERS);
  const measurementsSheet = ensureSheet_(spreadsheet, MEASUREMENTS_SHEET, MEASUREMENT_HEADERS);

  const syncedAt = payload.syncedAt || new Date().toISOString();
  const dayRows = (payload.days || []).map(d => [
    d.date, nullable_(d.steps), nullable_(d.distanceKm),
    nullable_(d.activeCaloriesKcal), nullable_(d.totalCaloriesKcal),
    nullable_(d.sleepHours), nullable_(d.deepSleepMinutes), nullable_(d.lightSleepMinutes),
    nullable_(d.remSleepMinutes), nullable_(d.awakeMinutes), nullable_(d.averageHeartRate),
    nullable_(d.minimumHeartRate), nullable_(d.maximumHeartRate), nullable_(d.restingHeartRate),
    nullable_(d.averageSpO2), nullable_(d.minimumSpO2), nullable_(d.weightKg),
    Number(d.workoutCount || 0), Number(d.workoutMinutes || 0),
    Array.isArray(d.sourcePackages) ? d.sourcePackages.join(', ') : '', syncedAt,
    nullable_(d.sleepStart), nullable_(d.sleepEnd), nullable_(d.sleepSessionCount), nullable_(d.sleepStageCount),
    nullable_(d.heartRateSamples), nullable_(d.maximumSpO2), nullable_(d.spO2Samples),
    nullable_(d.averageHrvRmssdMs), nullable_(d.minimumHrvRmssdMs), nullable_(d.maximumHrvRmssdMs), nullable_(d.hrvSamples),
    nullable_(d.averageRespiratoryRate), nullable_(d.minimumRespiratoryRate), nullable_(d.maximumRespiratoryRate), nullable_(d.respiratorySamples),
    nullable_(d.vo2Max), nullable_(d.skinTempBaselineC), nullable_(d.averageSkinTempDeltaC),
    nullable_(d.minimumSkinTempDeltaC), nullable_(d.maximumSkinTempDeltaC), nullable_(d.skinTempSamples),
    nullable_(d.elevationGainedM), nullable_(d.floorsClimbed), nullable_(d.averageSpeedKmh), nullable_(d.maximumSpeedKmh),
    nullable_(d.averageStepCadence), nullable_(d.maximumStepCadence), nullable_(d.averageCyclingCadence), nullable_(d.maximumCyclingCadence),
    nullable_(d.averagePowerW), nullable_(d.maximumPowerW)
  ]);

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

  upsertByKey_(daysSheet, dayRows, 1);
  upsertByKey_(workoutsSheet, workoutRows, 1);
  upsertByKey_(sleepSheet, sleepRows, 1);
  upsertByKey_(measurementsSheet, measurementRows, 1);

  logSheet.appendRow([
    new Date(), payload.deviceId || '', payload.rangeStart || '', payload.rangeEnd || '',
    dayRows.length, workoutRows.length, 'OK',
    `Синхронизация v${payload.schemaVersion || 1}: сон ${sleepRows.length}; измерения ${measurementRows.length}`
  ]);

  return {
    ok: true,
    days: dayRows.length,
    workouts: workoutRows.length,
    sleepSessions: sleepRows.length,
    measurements: measurementRows.length,
    message: `Записано дней: ${dayRows.length}; тренировок: ${workoutRows.length}; сна: ${sleepRows.length}; измерений: ${measurementRows.length}`
  };
}

function importFatSecretCsv_(spreadsheet, logSheet, payload) {
  const csvText = String(payload.csvText || '');
  if (!csvText.trim()) throw new Error('CSV пустой');

  const parsed = parseFatSecretCsv_(csvText, spreadsheet);
  if (!parsed.days.length) throw new Error('В секции # Report Details не найдены дневные данные FatSecret');

  const validation = validateFatSecretParsed_(parsed);
  if (!validation.ok) {
    validation.errors.forEach(msg => {
      logSheet.appendRow([new Date(), 'FatSecret', '', '', 0, 0, 'ERROR', msg]);
    });
    return {
      ok: false,
      error: 'Проверка целостности FatSecret не пройдена',
      message: validation.errors.join(' | ')
    };
  }

  const tracker = spreadsheet.getSheetByName(FOOD_TRACKER_SHEET);
  const nutrition = spreadsheet.getSheetByName(NUTRITION_SHEET);
  if (!tracker) throw new Error(`Не найден лист ${FOOD_TRACKER_SHEET}`);
  if (!nutrition) throw new Error(`Не найден лист ${NUTRITION_SHEET}`);

  upsertFatSecretMeals_(tracker, parsed, payload.fileName || 'fatsecret.csv', spreadsheet);
  upsertNutritionDays_(nutrition, parsed.days, spreadsheet);

  validation.okMessages.forEach(msg => {
    logSheet.appendRow([new Date(), 'FatSecret', '', '', 1, 0, 'OK', msg]);
  });
  logSheet.appendRow([
    new Date(), 'FatSecret', parsed.days[0].key, parsed.days[parsed.days.length - 1].key,
    parsed.days.length, parsed.meals.length, 'OK',
    `CSV импортирован: ${payload.fileName || 'fatsecret.csv'}`
  ]);

  return {
    ok: true,
    days: parsed.days.length,
    meals: parsed.meals.length,
    message: `CSV импортирован. Дней: ${parsed.days.length}; приёмов пищи: ${parsed.meals.length}`
  };
}

function parseFatSecretCsv_(csvText, spreadsheet) {
  const rows = Utilities.parseCsv(csvText.replace(/^\uFEFF/, ''));
  const reportStart = rows.findIndex(row => String((row && row[0]) || '').trim() === '# Report Details');
  if (reportStart < 0) throw new Error('Не найдена секция # Report Details');

  const tz = spreadsheet.getSpreadsheetTimeZone();
  const dayMap = new Map();
  const meals = [];
  let currentDay = null;
  let currentMeal = null;
  let currentFood = null;

  for (let i = reportStart + 1; i < rows.length; i++) {
    const row = rows[i] || [];
    const label = String(row[0] || '').trim();
    if (!label) continue;
    if (isReportTotalLabel_(label)) break;

    const parsedDate = parseReportDate_(label, tz);
    if (parsedDate) {
      const nutrition = readFixedNutrition_(row, false, `суточный итог ${parsedDate.key}`);
      currentDay = {
        date: parsedDate.date,
        key: parsedDate.key,
        kcal: nutrition.kcal,
        fat: nutrition.fat,
        carbs: nutrition.carbs,
        protein: nutrition.protein,
        meals: []
      };
      dayMap.set(parsedDate.key, currentDay);
      currentMeal = null;
      currentFood = null;
      continue;
    }

    const mealName = normalizeMeal_(label);
    if (currentDay && mealName && MEALS.has(mealName)) {
      const nutrition = readFixedNutrition_(row, true, `${currentDay.key} ${mealName}`);
      currentMeal = {
        date: currentDay.date,
        key: currentDay.key,
        meal: mealName,
        kcal: nutrition.kcal,
        fat: nutrition.fat,
        carbs: nutrition.carbs,
        protein: nutrition.protein,
        foods: [],
        _foodObjects: []
      };
      currentDay.meals.push(currentMeal);
      meals.push(currentMeal);
      currentFood = null;
      continue;
    }

    if (!currentDay || !currentMeal) continue;
    if (isDailySubtotalLabel_(label)) {
      currentMeal = null;
      currentFood = null;
      continue;
    }

    if (hasNutritionCells_(row)) {
      currentFood = { name: label, amount: '' };
      currentMeal._foodObjects.push(currentFood);
      continue;
    }

    if (currentFood) {
      currentFood.amount = currentFood.amount ? `${currentFood.amount} ${label}` : label;
    }
  }

  const days = Array.from(dayMap.values()).sort((a, b) => a.key.localeCompare(b.key));
  meals.forEach(m => {
    m.foods = m._foodObjects.map(f => f.amount ? `${f.name} — ${f.amount}` : f.name);
    delete m._foodObjects;
  });

  return { days, meals };
}

function readFixedNutrition_(row, blankAsZero, context) {
  if (row.length <= FATSECRET_COL.PROTEIN) {
    throw new Error(`${context}: строка короче ожидаемой структуры FatSecret`);
  }
  const values = {
    kcal: parseFixedNumber_(row[FATSECRET_COL.KCAL], blankAsZero),
    fat: parseFixedNumber_(row[FATSECRET_COL.FAT], blankAsZero),
    carbs: parseFixedNumber_(row[FATSECRET_COL.CARBS], blankAsZero),
    protein: parseFixedNumber_(row[FATSECRET_COL.PROTEIN], blankAsZero)
  };
  Object.keys(values).forEach(k => {
    if (values[k] === null) throw new Error(`${context}: не удалось прочитать ${k} в фиксированной колонке`);
  });
  return values;
}

function parseFixedNumber_(value, blankAsZero) {
  const text = String(value == null ? '' : value).trim().replace(/\s/g, '').replace(',', '.');
  if (!text) return blankAsZero ? 0 : null;
  const n = Number(text);
  return Number.isFinite(n) ? n : null;
}

function hasNutritionCells_(row) {
  const indexes = [FATSECRET_COL.KCAL, FATSECRET_COL.FAT, FATSECRET_COL.CARBS, FATSECRET_COL.PROTEIN];
  return indexes.some(i => i < row.length && String(row[i] == null ? '' : row[i]).trim() !== '');
}

function normalizeMeal_(label) {
  const trimmed = String(label || '').trim();
  if (MEALS.has(trimmed)) return trimmed;
  return MEAL_ALIASES[trimmed] || null;
}

function isReportTotalLabel_(label) {
  const v = String(label || '').trim().toLowerCase();
  return v === 'всего' || v === 'total';
}

function isDailySubtotalLabel_(label) {
  const v = String(label || '').trim().toLowerCase();
  return v === 'итого' || v === 'daily total';
}

function parseReportDate_(text, tz) {
  const months = {
    'января':1,'февраля':2,'марта':3,'апреля':4,'мая':5,'июня':6,'июля':7,'августа':8,'сентября':9,'октября':10,'ноября':11,'декабря':12,
    'january':1,'february':2,'march':3,'april':4,'may':5,'june':6,'july':7,'august':8,'september':9,'october':10,'november':11,'december':12
  };
  const lower = String(text || '').toLowerCase();
  const m = lower.match(/(?:понедельник|вторник|среда|четверг|пятница|суббота|воскресенье|monday|tuesday|wednesday|thursday|friday|saturday|sunday)?\s*,?\s*([а-яa-z]+)\s+(\d{1,2})\s*,?\s*(\d{4})/i);
  if (!m || months[m[1]] === undefined) return null;
  const date = Utilities.parseDate(`${m[2]}.${months[m[1]]}.${m[3]} 00:00`, tz, 'd.M.yyyy HH:mm');
  return { date, key: Utilities.formatDate(date, tz, 'yyyy-MM-dd') };
}

function validateFatSecretParsed_(parsed) {
  const errors = [];
  const okMessages = [];

  parsed.days.forEach(day => {
    const dayPrefix = formatKeyRu_(day.key);
    validateNutritionRange_(day, `${dayPrefix} сутки`, errors);
    day.meals.forEach(meal => validateNutritionRange_(meal, `${dayPrefix} ${meal.meal}`, errors));

    const sum = day.meals.reduce((acc, meal) => {
      acc.kcal += meal.kcal;
      acc.fat += meal.fat;
      acc.carbs += meal.carbs;
      acc.protein += meal.protein;
      return acc;
    }, { kcal: 0, fat: 0, carbs: 0, protein: 0 });

    const diffs = {
      kcal: Math.abs(sum.kcal - day.kcal),
      fat: Math.abs(sum.fat - day.fat),
      carbs: Math.abs(sum.carbs - day.carbs),
      protein: Math.abs(sum.protein - day.protein)
    };

    const dayErrors = [];
    if (diffs.kcal > KCAL_TOLERANCE) dayErrors.push(`kcal meals=${round2_(sum.kcal)}, daily=${round2_(day.kcal)}`);
    if (diffs.fat > MACRO_TOLERANCE) dayErrors.push(`fat meals=${round2_(sum.fat)}, daily=${round2_(day.fat)}`);
    if (diffs.carbs > MACRO_TOLERANCE) dayErrors.push(`carbs meals=${round2_(sum.carbs)}, daily=${round2_(day.carbs)}`);
    if (diffs.protein > MACRO_TOLERANCE) dayErrors.push(`protein meals=${round2_(sum.protein)}, daily=${round2_(day.protein)}`);

    if (dayErrors.length) {
      errors.push(`${dayPrefix}: ERROR — ${dayErrors.join('; ')} — import rejected`);
    } else {
      okMessages.push(`${dayPrefix}: OK — сумма приёмов ${round2_(sum.kcal)} kcal, суточный итог ${round2_(day.kcal)} kcal`);
    }
  });

  return { ok: errors.length === 0, errors, okMessages };
}

function validateNutritionRange_(item, context, errors) {
  const checks = [
    ['protein', item.protein, 0, 500, 'г белка'],
    ['fat', item.fat, 0, 500, 'г жиров'],
    ['carbs', item.carbs, 0, 1000, 'г углеводов'],
    ['kcal', item.kcal, 0, 10000, 'ккал']
  ];
  checks.forEach(([name, value, min, max, unit]) => {
    if (!Number.isFinite(value) || value < min || value > max) {
      errors.push(`${context}: ERROR — ${name}=${value} (${unit}), допустимо ${min}–${max}`);
    }
  });
}

function upsertFatSecretMeals_(sheet, parsed, fileName, spreadsheet) {
  const tz = spreadsheet.getSpreadsheetTimeZone();
  const importedDateKeys = new Set(parsed.days.map(d => d.key));
  const incomingKeys = new Set(parsed.meals.map(m => `${m.key}|${m.meal}`));

  const lastRow = sheet.getLastRow();
  if (lastRow >= 2) {
    const values = sheet.getRange(2, 1, lastRow - 1, 9).getValues();
    const seen = new Set();
    const rowsToDelete = [];
    values.forEach((row, idx) => {
      const dateKey = normalizeDateWithTz_(row[0], tz);
      const meal = String(row[1] || '').trim();
      const status = String(row[7] || '').trim();
      const key = `${dateKey}|${meal}`;
      if (!importedDateKeys.has(dateKey) || !MEALS.has(meal)) return;

      if (status === IMPORT_STATUS) {
        if (!incomingKeys.has(key) || seen.has(key)) rowsToDelete.push(idx + 2);
        else seen.add(key);
      }
    });
    rowsToDelete.sort((a, b) => b - a).forEach(r => sheet.deleteRow(r));
  }

  const existing = new Map();
  const refreshedLast = sheet.getLastRow();
  if (refreshedLast >= 2) {
    const values = sheet.getRange(2, 1, refreshedLast - 1, 2).getValues();
    values.forEach((row, idx) => {
      const dateKey = normalizeDateWithTz_(row[0], tz);
      const meal = String(row[1] || '').trim();
      if (dateKey && meal) existing.set(`${dateKey}|${meal}`, idx + 2);
    });
  }

  parsed.meals.forEach(meal => {
    const key = `${meal.key}|${meal.meal}`;
    const rowValues = [[
      meal.date,
      meal.meal,
      meal.protein,
      meal.carbs,
      meal.fat,
      meal.kcal,
      meal.foods.join(' | '),
      IMPORT_STATUS,
      `Импорт: ${fileName}; ${Utilities.formatDate(new Date(), tz, 'dd.MM.yyyy HH:mm')}`
    ]];

    const targetRow = existing.get(key);
    if (targetRow) {
      sheet.getRange(targetRow, 1, 1, 9).setValues(rowValues);
    } else {
      const newRow = sheet.getLastRow() + 1;
      copyRowFormat_(sheet, newRow, 9);
      sheet.getRange(newRow, 1, 1, 9).setValues(rowValues);
      existing.set(key, newRow);
    }
  });
}

function upsertNutritionDays_(sheet, days, spreadsheet) {
  const tz = spreadsheet.getSpreadsheetTimeZone();
  const existing = new Map();
  const lastRow = sheet.getLastRow();
  if (lastRow >= 2) {
    const values = sheet.getRange(2, 1, lastRow - 1, 1).getValues();
    values.forEach((row, idx) => {
      const key = normalizeDateWithTz_(row[0], tz);
      if (key) existing.set(key, idx + 2);
    });
  }

  days.forEach(day => {
    const values = [[day.date, day.protein, day.carbs, day.fat, day.kcal]];
    const target = existing.get(day.key);
    if (target) {
      sheet.getRange(target, 1, 1, 5).setValues(values);
    } else {
      const newRow = sheet.getLastRow() + 1;
      copyRowFormat_(sheet, newRow, 5);
      sheet.getRange(newRow, 1, 1, 5).setValues(values);
      existing.set(day.key, newRow);
    }
  });
}

function syncDiaryFoodSnack_(spreadsheet, days) {
  const sheet = spreadsheet.getSheetByName(DIARY_SHEET);
  if (!sheet || !days.length) return;

  const headerInfo = findHeader_(sheet);
  if (!headerInfo) return;
  const { row: headerRow, map } = headerInfo;
  const dateColumn = findAlias_(map, ['дата']);
  const snackColumn = findAlias_(map, ['полдник/перекус', 'полдник перекус']);
  if (!dateColumn || !snackColumn) return;

  // Важно: не используем setFormula(). Русская локаль Google Sheets использует
  // другой синтаксис функций/разделителей. Пишем готовое числовое значение.
  const snackByDate = new Map();
  days.forEach(day => {
    const total = (day.meals || [])
      .filter(meal => meal.meal === 'Полдник' || meal.meal === 'Перекус/Другое')
      .reduce((sum, meal) => sum + Number(meal.kcal || 0), 0);
    snackByDate.set(day.key, round2_(total));
  });

  const tz = spreadsheet.getSpreadsheetTimeZone();
  const lastRow = sheet.getLastRow();
  if (lastRow <= headerRow) return;
  const dateValues = sheet.getRange(headerRow + 1, dateColumn, lastRow - headerRow, 1).getValues();
  dateValues.forEach((rowValue, idx) => {
    const key = normalizeDateWithTz_(rowValue[0], tz);
    if (!snackByDate.has(key)) return;
    sheet.getRange(headerRow + 1 + idx, snackColumn).setValue(snackByDate.get(key));
  });
}

function copyRowFormat_(sheet, targetRow, width) {
  if (targetRow <= 2 || sheet.getLastRow() < 2) return;
  const sourceRow = Math.max(2, targetRow - 1);
  sheet.getRange(sourceRow, 1, 1, width)
    .copyTo(sheet.getRange(targetRow, 1, 1, width), SpreadsheetApp.CopyPasteType.PASTE_FORMAT, false);
}

function formatKeyRu_(key) {
  const m = String(key).match(/^(\d{4})-(\d{2})-(\d{2})$/);
  return m ? `${m[3]}.${m[2]}.${m[1]}` : key;
}

function round2_(value) {
  return Math.round((Number(value) + Number.EPSILON) * 100) / 100;
}

function getSpreadsheet_() {
  const id = PropertiesService.getScriptProperties().getProperty('SPREADSHEET_ID')
    || DEFAULT_SPREADSHEET_ID;
  return SpreadsheetApp.openById(id);
}

function ensureSheet_(spreadsheet, name, headers) {
  let sheet = spreadsheet.getSheetByName(name);
  if (!sheet) sheet = spreadsheet.insertSheet(name);
  if (sheet.getMaxColumns() < headers.length) {
    sheet.insertColumnsAfter(sheet.getMaxColumns(), headers.length - sheet.getMaxColumns());
  }
  sheet.getRange(1, 1, 1, headers.length).setValues([headers]);
  sheet.setFrozenRows(1);
  sheet.getRange(1, 1, 1, headers.length).setFontWeight('bold');
  return sheet;
}

function upsertByKey_(sheet, rows, keyColumnOneBased) {
  if (!rows.length) return;
  const lastRow = sheet.getLastRow();
  const keyIndex = keyColumnOneBased - 1;
  const existing = new Map();
  if (lastRow >= 2) {
    const keys = sheet.getRange(2, keyColumnOneBased, lastRow - 1, 1).getDisplayValues();
    keys.forEach((row, index) => {
      const key = String(row[0] || '').trim();
      if (key) existing.set(key, index + 2);
    });
  }

  const appends = [];
  rows.forEach(row => {
    const key = String(row[keyIndex] || '').trim();
    if (!key) return;
    const existingRow = existing.get(key);
    if (existingRow) {
      sheet.getRange(existingRow, 1, 1, row.length).setValues([row]);
    } else {
      appends.push(row);
    }
  });
  if (appends.length) {
    sheet.getRange(sheet.getLastRow() + 1, 1, appends.length, appends[0].length)
      .setValues(appends);
  }
}

function syncDiary_(spreadsheet, days) {
  const sheet = spreadsheet.getSheetByName(DIARY_SHEET);
  if (!sheet || !days.length) return;

  const headerInfo = findHeader_(sheet);
  if (!headerInfo) return;
  const { row: headerRow, map } = headerInfo;
  const dateColumn = findAlias_(map, ['дата']);
  if (!dateColumn) return;

  const aliases = {
    steps: ['шаги', 'количество шагов'],
    distanceKm: ['расстояние км', 'дистанция км', 'расстояние'],
    activeCaloriesKcal: ['активные калории', 'активные ккал'],
    sleepHours: ['сон ч', 'сон часов', 'продолжительность сна', 'сон'],
    restingHeartRate: ['пульс покоя', 'пульс в покое'],
    averageSpO2: ['spo2', 'сатурация', 'кислород'],
    weightKg: ['вес кг', 'вес'],
    workoutFlag: ['тренировка', 'тренировка да нет']
  };

  const targetColumns = {};
  Object.keys(aliases).forEach(key => targetColumns[key] = findAlias_(map, aliases[key]));

  const lastRow = Math.max(sheet.getLastRow(), headerRow + 1);
  const existingDates = new Map();
  if (lastRow > headerRow) {
    const dateValues = sheet.getRange(headerRow + 1, dateColumn, lastRow - headerRow, 1).getValues();
    dateValues.forEach((row, index) => {
      const key = normalizeDateWithTz_(row[0], spreadsheet.getSpreadsheetTimeZone());
      if (key) existingDates.set(key, headerRow + 1 + index);
    });
  }

  days.forEach(day => {
    const dateKey = day.date;
    let row = existingDates.get(dateKey);
    if (!row) {
      row = sheet.getLastRow() + 1;
      const tz = spreadsheet.getSpreadsheetTimeZone();
      const d = Utilities.parseDate(`${dateKey} 00:00`, tz, 'yyyy-MM-dd HH:mm');
      sheet.getRange(row, dateColumn).setValue(d);
      existingDates.set(dateKey, row);
    }
    setIfMapped_(sheet, row, targetColumns.steps, day.steps);
    setIfMapped_(sheet, row, targetColumns.distanceKm, day.distanceKm);
    setIfMapped_(sheet, row, targetColumns.activeCaloriesKcal, day.activeCaloriesKcal);
    setIfMapped_(sheet, row, targetColumns.sleepHours, day.sleepHours);
    setIfMapped_(sheet, row, targetColumns.restingHeartRate, day.restingHeartRate);
    setIfMapped_(sheet, row, targetColumns.averageSpO2, day.averageSpO2);
    setIfMapped_(sheet, row, targetColumns.weightKg, day.weightKg);
    if (targetColumns.workoutFlag) {
      sheet.getRange(row, targetColumns.workoutFlag).setValue(Number(day.workoutCount || 0) > 0 ? 'Да' : 'Нет');
    }
  });
}

function findHeader_(sheet) {
  const rowsToScan = Math.min(10, Math.max(1, sheet.getLastRow()));
  const colsToScan = Math.min(100, Math.max(1, sheet.getLastColumn()));
  const values = sheet.getRange(1, 1, rowsToScan, colsToScan).getDisplayValues();
  for (let r = 0; r < values.length; r++) {
    const map = new Map();
    values[r].forEach((value, c) => {
      const normalized = normalizeHeader_(value);
      if (normalized) map.set(normalized, c + 1);
    });
    if (map.has('дата')) return { row: r + 1, map };
  }
  return null;
}

function findAlias_(map, aliases) {
  for (const alias of aliases) {
    const normalized = normalizeHeader_(alias);
    if (map.has(normalized)) return map.get(normalized);
  }
  return null;
}

function normalizeHeader_(value) {
  return String(value || '')
    .toLowerCase()
    .replace(/₂/g, '2')
    .replace(/ё/g, 'е')
    .replace(/[^a-zа-я0-9]+/g, ' ')
    .trim();
}

function normalizeDateWithTz_(value, tz) {
  if (value instanceof Date && !isNaN(value)) {
    return Utilities.formatDate(value, tz, 'yyyy-MM-dd');
  }
  const text = String(value || '').trim();
  if (/^\d{4}-\d{2}-\d{2}$/.test(text)) return text;
  const match = text.match(/^(\d{1,2})[.\/-](\d{1,2})[.\/-](\d{4})$/);
  if (match) return `${match[3]}-${match[2].padStart(2, '0')}-${match[1].padStart(2, '0')}`;
  return '';
}

function setIfMapped_(sheet, row, column, value) {
  if (!column || value === null || value === undefined || value === '') return;
  sheet.getRange(row, column).setValue(value);
}

function nullable_(value) {
  return value === null || value === undefined ? '' : value;
}

function json_(object) {
  return ContentService.createTextOutput(JSON.stringify(object))
    .setMimeType(ContentService.MimeType.JSON);
}


// ===== Health Connector schema v3 integrity layer =====
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
