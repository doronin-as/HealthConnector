const TZ = Session.getScriptTimeZone() || 'Europe/Moscow';
const SHEET_HEALTH_DAYS = 'HC_Дни';
const SHEET_WORKOUTS = 'HC_Тренировки';
const SHEET_LOG = 'HC_Журнал';
const SHEET_FOOD_TRACKER = 'Трекер_питания';
const SHEET_NUTRITION = 'Питание';

function setup() {
  const props = PropertiesService.getScriptProperties();
  if (!props.getProperty('API_TOKEN')) {
    props.setProperty('API_TOKEN', Utilities.getUuid() + Utilities.getUuid());
  }
  ensureSheet_(SHEET_HEALTH_DAYS, ['Date','Steps','SleepHours','AverageHeartRate','MinimumHeartRate','MaximumHeartRate','AverageSpO2','WorkoutCount','WorkoutMinutes','StepsSource','SleepSource','HeartSource','SpO2Source','WorkoutSource','UpdatedAt']);
  ensureSheet_(SHEET_WORKOUTS, ['Id','Start','End','ExerciseType','Title','DurationMinutes','SourcePackage','SourceName','UpdatedAt']);
  ensureSheet_(SHEET_LOG, ['Timestamp','DeviceId','RangeStart','RangeEnd','Days','Workouts','Status','Message']);
  return { apiToken: props.getProperty('API_TOKEN') };
}

function doPost(e) {
  try {
    const data = JSON.parse((e.postData && e.postData.contents) || '{}');
    assertToken_(data.token);
    const action = data.action || 'healthSync';
    if (action === 'fatsecretCsv') return json_(importFatSecretCsv_(data));
    return json_(importHealth_(data));
  } catch (err) {
    log_('', '', '', 0, 0, 'ERROR', String(err));
    return json_({ ok: false, message: String(err) });
  }
}

function importHealth_(data) {
  if (!Array.isArray(data.days)) throw new Error('Поле days должно быть массивом');
  if (!Array.isArray(data.workouts)) throw new Error('Поле workouts должно быть массивом');

  const daySheet = ensureSheet_(SHEET_HEALTH_DAYS, ['Date','Steps','SleepHours','AverageHeartRate','MinimumHeartRate','MaximumHeartRate','AverageSpO2','WorkoutCount','WorkoutMinutes','StepsSource','SleepSource','HeartSource','SpO2Source','WorkoutSource','UpdatedAt']);
  const workoutSheet = ensureSheet_(SHEET_WORKOUTS, ['Id','Start','End','ExerciseType','Title','DurationMinutes','SourcePackage','SourceName','UpdatedAt']);

  upsertRows_(daySheet, 1, data.days.map(d => [
    d.date, d.steps || 0, d.sleepHours || 0, blank_(d.averageHeartRate), blank_(d.minimumHeartRate), blank_(d.maximumHeartRate), blank_(d.averageSpO2),
    d.workoutCount || 0, d.workoutMinutes || 0, d.stepsSource || '', d.sleepSource || '', d.heartSource || '', d.spo2Source || '', d.workoutSource || '', new Date()
  ]));
  upsertRows_(workoutSheet, 1, data.workouts.map(w => [
    w.id || [w.start,w.end,w.exerciseType].join('|'), w.start || '', w.end || '', w.exerciseType || '', w.title || '', w.durationMinutes || 0,
    w.sourcePackage || '', w.sourceName || '', new Date()
  ]));

  log_(data.deviceId || '', data.rangeStart || '', data.rangeEnd || '', data.days.length, data.workouts.length, 'OK', 'Health Connect импортирован');
  return { ok: true, days: data.days.length, workouts: data.workouts.length };
}

function importFatSecretCsv_(data) {
  const csvText = String(data.csvText || '');
  if (!csvText.trim()) throw new Error('CSV пустой');
  const parsed = parseFatSecretCsv_(csvText);
  if (!parsed.days.length) throw new Error('В CSV не найдены дневные итоги FatSecret');

  const tracker = ensureSheet_(SHEET_FOOD_TRACKER, ['Дата','Приём пищи','Белки','Углеводы','Жиры','Ккал','Продукты','Источник','Обновлено']);
  const nutrition = ensureSheet_(SHEET_NUTRITION, ['Дата','Белки','Углеводы','Жиры','Ккал']);

  const dateKeys = {};
  parsed.days.forEach(d => dateKeys[d.key] = true);
  deleteTrackerDates_(tracker, dateKeys);

  if (parsed.meals.length) {
    const rows = parsed.meals.map(m => [m.date, m.meal, m.protein, m.carbs, m.fat, m.kcal, m.foods.join('\n'), 'FatSecret CSV', new Date()]);
    tracker.getRange(tracker.getLastRow() + 1, 1, rows.length, rows[0].length).setValues(rows);
  }

  upsertRows_(nutrition, 1, parsed.days.map(d => [d.date, d.protein, d.carbs, d.fat, d.kcal]));
  log_('FatSecret', parsed.days[0].key, parsed.days[parsed.days.length - 1].key, parsed.days.length, parsed.meals.length, 'OK', 'CSV импортирован: ' + (data.fileName || 'fatsecret.csv'));
  return { ok: true, days: parsed.days.length, meals: parsed.meals.length };
}

function parseFatSecretCsv_(text) {
  const rows = Utilities.parseCsv(text.replace(/^\uFEFF/, ''));
  const days = [];
  const meals = [];
  let currentDate = null;
  let currentMeal = null;

  rows.forEach(row => {
    if (!row || !row.length) return;
    const labelRaw = String(row[0] || '');
    const label = labelRaw.trim();
    const parsedDate = parseReportDate_(label);
    const nums = trailingNumbers_(row);

    if (parsedDate && nums.length >= 7) {
      currentDate = parsedDate;
      currentMeal = null;
      days.push({
        date: parsedDate.date,
        key: parsedDate.key,
        kcal: nums[0],
        fat: nums[1],
        carbs: nums[3],
        protein: nums[6]
      });
      return;
    }

    if (currentDate && ['Завтрак','Обед','Ужин','Перекус/Другое','Breakfast','Lunch','Dinner','Snacks/Other'].indexOf(label) >= 0 && nums.length >= 7) {
      currentMeal = {
        date: currentDate.date,
        meal: normalizeMeal_(label),
        kcal: nums[0],
        fat: nums[1],
        carbs: nums[3],
        protein: nums[6],
        foods: []
      };
      meals.push(currentMeal);
      return;
    }

    if (currentMeal && labelRaw.indexOf('  ') === 0 && label) {
      currentMeal.foods.push(label);
      return;
    }

    if (currentMeal && row.length === 1 && label && currentMeal.foods.length) {
      currentMeal.foods[currentMeal.foods.length - 1] += ' — ' + label;
    }
  });

  const uniqueDays = {};
  days.forEach(d => uniqueDays[d.key] = d);
  return {
    days: Object.keys(uniqueDays).sort().map(k => uniqueDays[k]),
    meals: meals.filter(m => m.kcal || m.protein || m.carbs || m.fat || m.foods.length)
  };
}

function trailingNumbers_(row) {
  if (row.length < 2) return [];
  const values = row.slice(1).map(parseNumber_);
  return values.filter(v => v !== null);
}

function parseNumber_(value) {
  const s = String(value == null ? '' : value).replace(/\s/g, '').replace(',', '.');
  if (!s) return null;
  const n = Number(s);
  return isFinite(n) ? n : null;
}

function parseReportDate_(text) {
  const months = {
    'января':0,'февраля':1,'марта':2,'апреля':3,'мая':4,'июня':5,'июля':6,'августа':7,'сентября':8,'октября':9,'ноября':10,'декабря':11,
    'january':0,'february':1,'march':2,'april':3,'may':4,'june':5,'july':6,'august':7,'september':8,'october':9,'november':10,'december':11
  };
  const lower = text.toLowerCase();
  const m = lower.match(/(?:понедельник|вторник|среда|четверг|пятница|суббота|воскресенье|monday|tuesday|wednesday|thursday|friday|saturday|sunday)?\s*,?\s*([а-яa-z]+)\s+(\d{1,2})\s*,?\s*(\d{4})/i);
  if (!m || months[m[1]] === undefined) return null;
  const d = new Date(Number(m[3]), months[m[1]], Number(m[2]));
  return { date: d, key: Utilities.formatDate(d, TZ, 'yyyy-MM-dd') };
}

function normalizeMeal_(value) {
  const map = { Breakfast:'Завтрак', Lunch:'Обед', Dinner:'Ужин', 'Snacks/Other':'Перекус/Другое' };
  return map[value] || value;
}

function deleteTrackerDates_(sheet, dateKeys) {
  const last = sheet.getLastRow();
  if (last < 2) return;
  const values = sheet.getRange(2, 1, last - 1, 1).getValues();
  for (let i = values.length - 1; i >= 0; i--) {
    const value = values[i][0];
    const key = value instanceof Date ? Utilities.formatDate(value, TZ, 'yyyy-MM-dd') : String(value).slice(0,10);
    if (dateKeys[key]) sheet.deleteRow(i + 2);
  }
}

function upsertRows_(sheet, keyColumn, rows) {
  if (!rows.length) return;
  const last = sheet.getLastRow();
  const existing = {};
  if (last >= 2) {
    sheet.getRange(2, keyColumn, last - 1, 1).getValues().forEach((r, i) => {
      existing[normalizeKey_(r[0])] = i + 2;
    });
  }
  rows.forEach(row => {
    const key = normalizeKey_(row[keyColumn - 1]);
    const target = existing[key];
    if (target) sheet.getRange(target, 1, 1, row.length).setValues([row]);
    else {
      sheet.getRange(sheet.getLastRow() + 1, 1, 1, row.length).setValues([row]);
      existing[key] = sheet.getLastRow();
    }
  });
}

function normalizeKey_(value) {
  if (value instanceof Date) return Utilities.formatDate(value, TZ, 'yyyy-MM-dd');
  return String(value == null ? '' : value);
}

function assertToken_(token) {
  const expected = PropertiesService.getScriptProperties().getProperty('API_TOKEN');
  if (!expected) throw new Error('API_TOKEN не настроен. Запусти setup()');
  if (String(token || '') !== expected) throw new Error('Неверный токен');
}

function ensureSheet_(name, headers) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const sheet = ss.getSheetByName(name) || ss.insertSheet(name);
  if (sheet.getLastRow() === 0) sheet.getRange(1, 1, 1, headers.length).setValues([headers]);
  return sheet;
}

function log_(deviceId, rangeStart, rangeEnd, days, workouts, status, message) {
  const sheet = ensureSheet_(SHEET_LOG, ['Timestamp','DeviceId','RangeStart','RangeEnd','Days','Workouts','Status','Message']);
  sheet.appendRow([new Date(), deviceId, rangeStart, rangeEnd, days, workouts, status, message]);
}

function blank_(value) { return value === null || value === undefined || value === 'null' ? '' : value; }
function json_(object) { return ContentService.createTextOutput(JSON.stringify(object)).setMimeType(ContentService.MimeType.JSON); }
