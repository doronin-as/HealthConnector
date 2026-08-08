/**
 * Health Dashboard Sync — Google Apps Script endpoint.
 * Health Connect + FatSecret CSV import.
 */

const DEFAULT_SPREADSHEET_ID = '1lb8VInEtXyawXUiNxe47iMebWCLKV7rH3hSuDxF4RwM';
const DAYS_SHEET = 'HC_Дни';
const WORKOUTS_SHEET = 'HC_Тренировки';
const LOG_SHEET = 'HC_Журнал';
const DIARY_SHEET = 'Дневник';
const FOOD_TRACKER_SHEET = 'Трекер_питания';
const NUTRITION_SHEET = 'Питание';

const DAY_HEADERS = [
  'Date', 'Steps', 'DistanceKm', 'ActiveCaloriesKcal', 'TotalCaloriesKcal',
  'SleepHours', 'DeepSleepMin', 'LightSleepMin', 'RemSleepMin', 'AwakeMin',
  'AvgHeartRate', 'MinHeartRate', 'MaxHeartRate', 'RestingHeartRate',
  'AvgSpO2', 'MinSpO2', 'WeightKg', 'WorkoutCount', 'WorkoutMinutes',
  'Sources', 'SyncedAt'
];
const WORKOUT_HEADERS = ['Id','Start','End','ExerciseType','Title','DurationMin','SourcePackage','SyncedAt'];
const LOG_HEADERS = ['Timestamp','DeviceId','RangeStart','RangeEnd','Days','Workouts','Status','Message'];
const FOOD_TRACKER_HEADERS = ['Дата','Прием','Белок','Углеводы','Жиры','Калории','Еда','Статус','Комментарий'];
const NUTRITION_HEADERS = ['Дата','Белок','Углеводы','Жиры','Калории'];

// Реальная структура FatSecret Detailed Report:
// 0=label, 1=kcal, 2=fat, 3=saturated fat, 4=carbs, 5=fiber,
// 6=sugar, 7=protein, 8=sodium, 9=cholesterol, 10=potassium.
const FATSECRET_COL = Object.freeze({ KCAL:1, FAT:2, CARBS:4, PROTEIN:7 });
const MEALS = new Set(['Завтрак','Обед','Полдник','Ужин','Перекус/Другое']);
const MEAL_ALIASES = Object.freeze({
  'Breakfast':'Завтрак', 'Lunch':'Обед', 'Afternoon Snack':'Полдник',
  'Dinner':'Ужин', 'Snacks/Other':'Перекус/Другое'
});
const IMPORT_STATUS = 'FatSecret CSV';
const KCAL_TOLERANCE = 2;
const MACRO_TOLERANCE = 0.2;

function setup() {
  const properties = PropertiesService.getScriptProperties();
  if (!properties.getProperty('SPREADSHEET_ID')) properties.setProperty('SPREADSHEET_ID', DEFAULT_SPREADSHEET_ID);
  if (!properties.getProperty('API_TOKEN')) {
    properties.setProperty('API_TOKEN', Utilities.getUuid().replace(/-/g,'') + Utilities.getUuid().replace(/-/g,''));
  }
  const spreadsheet = getSpreadsheet_();
  ensureSheet_(spreadsheet, DAYS_SHEET, DAY_HEADERS);
  ensureSheet_(spreadsheet, WORKOUTS_SHEET, WORKOUT_HEADERS);
  ensureSheet_(spreadsheet, LOG_SHEET, LOG_HEADERS);
  const result = { spreadsheetId:properties.getProperty('SPREADSHEET_ID'), apiToken:properties.getProperty('API_TOKEN') };
  console.log(JSON.stringify(result));
  return result;
}

function doGet() {
  return json_({ok:true, service:'Health Dashboard Sync', time:new Date().toISOString()});
}

function doPost(e) {
  const lock = LockService.getScriptLock();
  try {
    lock.waitLock(30000);
    const payload = JSON.parse((e.postData && e.postData.contents) || '{}');
    if (!payload || typeof payload !== 'object') throw new Error('Пустой JSON');
    const expectedToken = PropertiesService.getScriptProperties().getProperty('API_TOKEN');
    if (!expectedToken || payload.token !== expectedToken) throw new Error('Неверный API-токен');

    const spreadsheet = getSpreadsheet_();
    const logSheet = ensureSheet_(spreadsheet, LOG_SHEET, LOG_HEADERS);

    // Важно: CSV обрабатывается до health-валидации.
    if (payload.action === 'fatsecretCsv') return json_(importFatSecretCsv_(spreadsheet, logSheet, payload));

    validateHealthPayload_(payload);
    return json_(importHealthPayload_(spreadsheet, logSheet, payload));
  } catch (error) {
    try {
      const spreadsheet = getSpreadsheet_();
      ensureSheet_(spreadsheet, LOG_SHEET, LOG_HEADERS).appendRow([new Date(),'','','',0,0,'ERROR',String(error)]);
    } catch (_) {}
    const message = String(error && error.message || error);
    return json_({ok:false, error:message, message:message});
  } finally {
    try { lock.releaseLock(); } catch (_) {}
  }
}

function validateHealthPayload_(payload) {
  if (!Array.isArray(payload.days)) throw new Error('Поле days должно быть массивом');
  if (!Array.isArray(payload.workouts)) throw new Error('Поле workouts должно быть массивом');
  if (payload.days.length > 31) throw new Error('Слишком большой диапазон дней');
  if (payload.workouts.length > 5000) throw new Error('Слишком много тренировок');
}

function importHealthPayload_(spreadsheet, logSheet, payload) {
  const daysSheet = ensureSheet_(spreadsheet, DAYS_SHEET, DAY_HEADERS);
  const workoutsSheet = ensureSheet_(spreadsheet, WORKOUTS_SHEET, WORKOUT_HEADERS);
  const syncedAt = payload.syncedAt || new Date().toISOString();
  const dayRows = (payload.days || []).map(d => [
    d.date, nullable_(d.steps), nullable_(d.distanceKm), nullable_(d.activeCaloriesKcal), nullable_(d.totalCaloriesKcal),
    nullable_(d.sleepHours), nullable_(d.deepSleepMinutes), nullable_(d.lightSleepMinutes), nullable_(d.remSleepMinutes), nullable_(d.awakeMinutes),
    nullable_(d.averageHeartRate), nullable_(d.minimumHeartRate), nullable_(d.maximumHeartRate), nullable_(d.restingHeartRate),
    nullable_(d.averageSpO2), nullable_(d.minimumSpO2), nullable_(d.weightKg), Number(d.workoutCount || 0), Number(d.workoutMinutes || 0),
    Array.isArray(d.sourcePackages) ? d.sourcePackages.join(', ') : '', syncedAt
  ]);
  const workoutRows = (payload.workouts || []).map(w => [w.id,w.start,w.end,w.exerciseType,nullable_(w.title),Number(w.durationMinutes || 0),nullable_(w.sourcePackage),syncedAt]);
  upsertByKey_(daysSheet, dayRows, 1);
  upsertByKey_(workoutsSheet, workoutRows, 1);
  syncDiary_(spreadsheet, payload.days || []);
  logSheet.appendRow([new Date(),payload.deviceId || '',payload.rangeStart || '',payload.rangeEnd || '',dayRows.length,workoutRows.length,'OK','Синхронизация завершена']);
  return {ok:true, message:`Записано дней: ${dayRows.length}; тренировок: ${workoutRows.length}`};
}

function importFatSecretCsv_(spreadsheet, logSheet, payload) {
  const csvText = String(payload.csvText || '');
  if (!csvText.trim()) throw new Error('CSV пустой');
  const parsed = parseFatSecretCsv_(csvText, spreadsheet);
  if (!parsed.days.length) throw new Error('В секции # Report Details не найдены дневные данные FatSecret');

  const validation = validateFatSecretParsed_(parsed);
  if (!validation.ok) {
    validation.errors.forEach(msg => logSheet.appendRow([new Date(),'FatSecret','','',0,0,'ERROR',msg]));
    return {ok:false, error:'Проверка целостности FatSecret не пройдена', message:validation.errors.join(' | ')};
  }

  const tracker = spreadsheet.getSheetByName(FOOD_TRACKER_SHEET);
  const nutrition = spreadsheet.getSheetByName(NUTRITION_SHEET);
  if (!tracker) throw new Error(`Не найден лист ${FOOD_TRACKER_SHEET}`);
  if (!nutrition) throw new Error(`Не найден лист ${NUTRITION_SHEET}`);

  upsertFatSecretMeals_(tracker, parsed, payload.fileName || 'fatsecret.csv', spreadsheet);
  upsertNutritionDays_(nutrition, parsed.days, spreadsheet);

  validation.okMessages.forEach(msg => logSheet.appendRow([new Date(),'FatSecret','','',1,0,'OK',msg]));
  logSheet.appendRow([new Date(),'FatSecret',parsed.days[0].key,parsed.days[parsed.days.length-1].key,parsed.days.length,parsed.meals.length,'OK',`CSV импортирован: ${payload.fileName || 'fatsecret.csv'}`]);
  return {ok:true, days:parsed.days.length, meals:parsed.meals.length, message:`CSV импортирован. Дней: ${parsed.days.length}; приёмов пищи: ${parsed.meals.length}`};
}

function parseFatSecretCsv_(csvText, spreadsheet) {
  const rows = Utilities.parseCsv(csvText.replace(/^\uFEFF/,''));
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
      const n = readFixedNutrition_(row, false, `суточный итог ${parsedDate.key}`);
      currentDay = {date:parsedDate.date,key:parsedDate.key,kcal:n.kcal,fat:n.fat,carbs:n.carbs,protein:n.protein,meals:[]};
      dayMap.set(parsedDate.key, currentDay);
      currentMeal = null;
      currentFood = null;
      continue;
    }

    const mealName = normalizeMeal_(label);
    if (currentDay && mealName && MEALS.has(mealName)) {
      const n = readFixedNutrition_(row, true, `${currentDay.key} ${mealName}`);
      currentMeal = {date:currentDay.date,key:currentDay.key,meal:mealName,kcal:n.kcal,fat:n.fat,carbs:n.carbs,protein:n.protein,foods:[],_foodObjects:[]};
      currentDay.meals.push(currentMeal);
      meals.push(currentMeal);
      currentFood = null;
      continue;
    }

    if (!currentDay || !currentMeal) continue;
    if (isDailySubtotalLabel_(label)) { currentMeal = null; currentFood = null; continue; }

    if (hasNutritionCells_(row)) {
      currentFood = {name:label, amount:''};
      currentMeal._foodObjects.push(currentFood);
    } else if (currentFood) {
      currentFood.amount = currentFood.amount ? `${currentFood.amount} ${label}` : label;
    }
  }

  const days = Array.from(dayMap.values()).sort((a,b) => a.key.localeCompare(b.key));
  meals.forEach(m => {
    m.foods = m._foodObjects.map(f => f.amount ? `${f.name} — ${f.amount}` : f.name);
    delete m._foodObjects;
  });
  return {days, meals};
}

function readFixedNutrition_(row, blankAsZero, context) {
  if (row.length <= FATSECRET_COL.PROTEIN) throw new Error(`${context}: строка короче ожидаемой структуры FatSecret`);
  const values = {
    kcal: parseFixedNumber_(row[FATSECRET_COL.KCAL], blankAsZero),
    fat: parseFixedNumber_(row[FATSECRET_COL.FAT], blankAsZero),
    carbs: parseFixedNumber_(row[FATSECRET_COL.CARBS], blankAsZero),
    protein: parseFixedNumber_(row[FATSECRET_COL.PROTEIN], blankAsZero)
  };
  Object.keys(values).forEach(k => { if (values[k] === null) throw new Error(`${context}: не удалось прочитать ${k} в фиксированной колонке`); });
  return values;
}

function parseFixedNumber_(value, blankAsZero) {
  const text = String(value == null ? '' : value).trim().replace(/\s/g,'').replace(',','.');
  if (!text) return blankAsZero ? 0 : null;
  const n = Number(text);
  return Number.isFinite(n) ? n : null;
}

function hasNutritionCells_(row) {
  return [FATSECRET_COL.KCAL,FATSECRET_COL.FAT,FATSECRET_COL.CARBS,FATSECRET_COL.PROTEIN]
    .some(i => i < row.length && String(row[i] == null ? '' : row[i]).trim() !== '');
}

function normalizeMeal_(label) {
  const value = String(label || '').trim();
  if (MEALS.has(value)) return value;
  return MEAL_ALIASES[value] || null;
}
function isReportTotalLabel_(label) { const v=String(label||'').trim().toLowerCase(); return v==='всего' || v==='total'; }
function isDailySubtotalLabel_(label) { const v=String(label||'').trim().toLowerCase(); return v==='итого' || v==='daily total'; }

function parseReportDate_(text, tz) {
  const months = {'января':1,'февраля':2,'марта':3,'апреля':4,'мая':5,'июня':6,'июля':7,'августа':8,'сентября':9,'октября':10,'ноября':11,'декабря':12,'january':1,'february':2,'march':3,'april':4,'may':5,'june':6,'july':7,'august':8,'september':9,'october':10,'november':11,'december':12};
  const lower = String(text || '').toLowerCase();
  const m = lower.match(/(?:понедельник|вторник|среда|четверг|пятница|суббота|воскресенье|monday|tuesday|wednesday|thursday|friday|saturday|sunday)?\s*,?\s*([а-яa-z]+)\s+(\d{1,2})\s*,?\s*(\d{4})/i);
  if (!m || months[m[1]] === undefined) return null;
  const date = Utilities.parseDate(`${m[2]}.${months[m[1]]}.${m[3]} 00:00`, tz, 'd.M.yyyy HH:mm');
  return {date, key:Utilities.formatDate(date,tz,'yyyy-MM-dd')};
}

function validateFatSecretParsed_(parsed) {
  const errors = [];
  const okMessages = [];
  parsed.days.forEach(day => {
    const prefix = formatKeyRu_(day.key);
    validateNutritionRange_(day, `${prefix} сутки`, errors);
    day.meals.forEach(meal => validateNutritionRange_(meal, `${prefix} ${meal.meal}`, errors));
    const sum = day.meals.reduce((a,m) => ({kcal:a.kcal+m.kcal,fat:a.fat+m.fat,carbs:a.carbs+m.carbs,protein:a.protein+m.protein}), {kcal:0,fat:0,carbs:0,protein:0});
    const parts = [];
    if (Math.abs(sum.kcal-day.kcal)>KCAL_TOLERANCE) parts.push(`kcal meals=${round2_(sum.kcal)}, daily=${round2_(day.kcal)}`);
    if (Math.abs(sum.fat-day.fat)>MACRO_TOLERANCE) parts.push(`fat meals=${round2_(sum.fat)}, daily=${round2_(day.fat)}`);
    if (Math.abs(sum.carbs-day.carbs)>MACRO_TOLERANCE) parts.push(`carbs meals=${round2_(sum.carbs)}, daily=${round2_(day.carbs)}`);
    if (Math.abs(sum.protein-day.protein)>MACRO_TOLERANCE) parts.push(`protein meals=${round2_(sum.protein)}, daily=${round2_(day.protein)}`);
    if (parts.length) errors.push(`${prefix}: ERROR — ${parts.join('; ')} — import rejected`);
    else okMessages.push(`${prefix}: OK — сумма приёмов ${round2_(sum.kcal)} kcal, суточный итог ${round2_(day.kcal)} kcal`);
  });
  return {ok:errors.length===0, errors, okMessages};
}

function validateNutritionRange_(item, context, errors) {
  [['protein',item.protein,0,500],['fat',item.fat,0,500],['carbs',item.carbs,0,1000],['kcal',item.kcal,0,10000]].forEach(([name,value,min,max]) => {
    if (!Number.isFinite(value) || value < min || value > max) errors.push(`${context}: ERROR — ${name}=${value}, допустимо ${min}–${max}`);
  });
}

function upsertFatSecretMeals_(sheet, parsed, fileName, spreadsheet) {
  const tz = spreadsheet.getSpreadsheetTimeZone();
  const importedDateKeys = new Set(parsed.days.map(d => d.key));
  const incomingKeys = new Set(parsed.meals.map(m => `${m.key}|${m.meal}`));
  const lastRow = sheet.getLastRow();
  if (lastRow >= 2) {
    const values = sheet.getRange(2,1,lastRow-1,9).getValues();
    const seen = new Set();
    const rowsToDelete = [];
    values.forEach((row,idx) => {
      const dateKey = normalizeDateWithTz_(row[0],tz);
      const meal = String(row[1] || '').trim();
      const status = String(row[7] || '').trim();
      const key = `${dateKey}|${meal}`;
      if (!importedDateKeys.has(dateKey) || !MEALS.has(meal)) return;
      if (status === IMPORT_STATUS) {
        if (!incomingKeys.has(key) || seen.has(key)) rowsToDelete.push(idx+2);
        else seen.add(key);
      }
    });
    rowsToDelete.sort((a,b)=>b-a).forEach(r => sheet.deleteRow(r));
  }

  const existing = new Map();
  const refreshedLast = sheet.getLastRow();
  if (refreshedLast >= 2) {
    sheet.getRange(2,1,refreshedLast-1,2).getValues().forEach((row,idx) => {
      const dateKey = normalizeDateWithTz_(row[0],tz);
      const meal = String(row[1] || '').trim();
      if (dateKey && meal) existing.set(`${dateKey}|${meal}`, idx+2);
    });
  }

  parsed.meals.forEach(meal => {
    const key = `${meal.key}|${meal.meal}`;
    const values = [[meal.date,meal.meal,meal.protein,meal.carbs,meal.fat,meal.kcal,meal.foods.join(' | '),IMPORT_STATUS,`Импорт: ${fileName}; ${Utilities.formatDate(new Date(),tz,'dd.MM.yyyy HH:mm')}`]];
    const target = existing.get(key);
    if (target) sheet.getRange(target,1,1,9).setValues(values);
    else {
      const newRow = sheet.getLastRow()+1;
      copyRowFormat_(sheet,newRow,9);
      sheet.getRange(newRow,1,1,9).setValues(values);
      existing.set(key,newRow);
    }
  });
}

function upsertNutritionDays_(sheet, days, spreadsheet) {
  const tz = spreadsheet.getSpreadsheetTimeZone();
  const existing = new Map();
  const lastRow = sheet.getLastRow();
  if (lastRow >= 2) {
    sheet.getRange(2,1,lastRow-1,1).getValues().forEach((row,idx) => {
      const key = normalizeDateWithTz_(row[0],tz);
      if (key) existing.set(key,idx+2);
    });
  }
  days.forEach(day => {
    const values = [[day.date,day.protein,day.carbs,day.fat,day.kcal]];
    const target = existing.get(day.key);
    if (target) sheet.getRange(target,1,1,5).setValues(values);
    else {
      const newRow = sheet.getLastRow()+1;
      copyRowFormat_(sheet,newRow,5);
      sheet.getRange(newRow,1,1,5).setValues(values);
      existing.set(day.key,newRow);
    }
  });
}

function copyRowFormat_(sheet,targetRow,width) {
  if (targetRow <= 2 || sheet.getLastRow() < 2) return;
  sheet.getRange(Math.max(2,targetRow-1),1,1,width).copyTo(sheet.getRange(targetRow,1,1,width), SpreadsheetApp.CopyPasteType.PASTE_FORMAT, false);
}
function formatKeyRu_(key) { const m=String(key).match(/^(\d{4})-(\d{2})-(\d{2})$/); return m ? `${m[3]}.${m[2]}.${m[1]}` : key; }
function round2_(value) { return Math.round((Number(value)+Number.EPSILON)*100)/100; }

function getSpreadsheet_() {
  const id = PropertiesService.getScriptProperties().getProperty('SPREADSHEET_ID') || DEFAULT_SPREADSHEET_ID;
  return SpreadsheetApp.openById(id);
}
function ensureSheet_(spreadsheet,name,headers) {
  let sheet=spreadsheet.getSheetByName(name);
  if (!sheet) sheet=spreadsheet.insertSheet(name);
  if (sheet.getLastRow()===0) {
    sheet.getRange(1,1,1,headers.length).setValues([headers]);
    sheet.setFrozenRows(1);
    sheet.getRange(1,1,1,headers.length).setFontWeight('bold');
  }
  return sheet;
}
function upsertByKey_(sheet,rows,keyColumnOneBased) {
  if (!rows.length) return;
  const lastRow=sheet.getLastRow(), keyIndex=keyColumnOneBased-1, existing=new Map();
  if (lastRow>=2) sheet.getRange(2,keyColumnOneBased,lastRow-1,1).getDisplayValues().forEach((row,index)=>{ const key=String(row[0]||'').trim(); if(key) existing.set(key,index+2); });
  const appends=[];
  rows.forEach(row=>{ const key=String(row[keyIndex]||'').trim(); if(!key)return; const r=existing.get(key); if(r)sheet.getRange(r,1,1,row.length).setValues([row]); else appends.push(row); });
  if(appends.length) sheet.getRange(sheet.getLastRow()+1,1,appends.length,appends[0].length).setValues(appends);
}

function syncDiary_(spreadsheet,days) {
  const sheet=spreadsheet.getSheetByName(DIARY_SHEET);
  if(!sheet||!days.length)return;
  const headerInfo=findHeader_(sheet);
  if(!headerInfo)return;
  const headerRow=headerInfo.row, map=headerInfo.map, dateColumn=findAlias_(map,['дата']);
  if(!dateColumn)return;
  const aliases={steps:['шаги','количество шагов'],distanceKm:['расстояние км','дистанция км','расстояние'],activeCaloriesKcal:['активные калории','активные ккал'],sleepHours:['сон ч','сон часов','продолжительность сна','сон'],restingHeartRate:['пульс покоя','пульс в покое'],averageSpO2:['spo2','сатурация','кислород'],weightKg:['вес кг','вес'],workoutFlag:['тренировка','тренировка да нет']};
  const targetColumns={}; Object.keys(aliases).forEach(k=>targetColumns[k]=findAlias_(map,aliases[k]));
  const lastRow=Math.max(sheet.getLastRow(),headerRow+1), existingDates=new Map(), tz=spreadsheet.getSpreadsheetTimeZone();
  if(lastRow>headerRow) sheet.getRange(headerRow+1,dateColumn,lastRow-headerRow,1).getValues().forEach((row,index)=>{ const key=normalizeDateWithTz_(row[0],tz); if(key)existingDates.set(key,headerRow+1+index); });
  days.forEach(day=>{
    const dateKey=day.date; let row=existingDates.get(dateKey);
    if(!row){ row=sheet.getLastRow()+1; sheet.getRange(row,dateColumn).setValue(Utilities.parseDate(`${dateKey} 00:00`,tz,'yyyy-MM-dd HH:mm')); existingDates.set(dateKey,row); }
    setIfMapped_(sheet,row,targetColumns.steps,day.steps); setIfMapped_(sheet,row,targetColumns.distanceKm,day.distanceKm); setIfMapped_(sheet,row,targetColumns.activeCaloriesKcal,day.activeCaloriesKcal); setIfMapped_(sheet,row,targetColumns.sleepHours,day.sleepHours); setIfMapped_(sheet,row,targetColumns.restingHeartRate,day.restingHeartRate); setIfMapped_(sheet,row,targetColumns.averageSpO2,day.averageSpO2); setIfMapped_(sheet,row,targetColumns.weightKg,day.weightKg);
    if(targetColumns.workoutFlag) sheet.getRange(row,targetColumns.workoutFlag).setValue(Number(day.workoutCount||0)>0?'Да':'Нет');
  });
}
function findHeader_(sheet) {
  const rowsToScan=Math.min(10,Math.max(1,sheet.getLastRow())), colsToScan=Math.min(100,Math.max(1,sheet.getLastColumn())), values=sheet.getRange(1,1,rowsToScan,colsToScan).getDisplayValues();
  for(let r=0;r<values.length;r++){ const map=new Map(); values[r].forEach((value,c)=>{ const n=normalizeHeader_(value); if(n)map.set(n,c+1); }); if(map.has('дата'))return{row:r+1,map}; }
  return null;
}
function findAlias_(map,aliases){ for(const alias of aliases){ const n=normalizeHeader_(alias); if(map.has(n))return map.get(n); } return null; }
function normalizeHeader_(value){ return String(value||'').toLowerCase().replace(/₂/g,'2').replace(/ё/g,'е').replace(/[^a-zа-я0-9]+/g,' ').trim(); }
function normalizeDateWithTz_(value,tz){ if(value instanceof Date&&!isNaN(value))return Utilities.formatDate(value,tz,'yyyy-MM-dd'); const text=String(value||'').trim(); if(/^\d{4}-\d{2}-\d{2}$/.test(text))return text; const m=text.match(/^(\d{1,2})[.\/-](\d{1,2})[.\/-](\d{4})$/); return m?`${m[3]}-${m[2].padStart(2,'0')}-${m[1].padStart(2,'0')}`:''; }
function setIfMapped_(sheet,row,column,value){ if(!column||value===null||value===undefined||value==='')return; sheet.getRange(row,column).setValue(value); }
function nullable_(value){ return value===null||value===undefined?'':value; }
function json_(object){ return ContentService.createTextOutput(JSON.stringify(object)).setMimeType(ContentService.MimeType.JSON); }
