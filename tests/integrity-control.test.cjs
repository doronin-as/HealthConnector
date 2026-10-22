const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');

function load(props = new Map(), extra = {}) {
  const context = vm.createContext(Object.assign({
    console, Date, Map, Set, Object, Math, Number, String, Array, JSON,
    PropertiesService: {getScriptProperties: () => ({
      getProperty: k => props.has(k) ? props.get(k) : null,
      setProperty: (k, v) => props.set(k, String(v)),
      deleteProperty: k => props.delete(k)
    })},
    Utilities: {
      formatDate: (d, tz) => new Intl.DateTimeFormat('en-CA', {timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit'}).format(d),
      // Only 'yyyy-MM-dd HH:mm' is used by the code under test.
      parseDate: text => new Date(text.replace(' ', 'T') + ':00Z')
    }
  }, extra));
  vm.runInContext(fs.readFileSync('apps-script/Code.gs', 'utf8'), context);
  return context;
}

const full = {Steps: 12000, DistanceKm: 9, ActiveCaloriesKcal: 400, TotalCaloriesKcal: 2900, SleepHours: 7.5,
  AvgHeartRate: 75, RestingHeartRate: 55, AvgSpO2: 96, AvgHrvRmssdMs: 40, AvgRespiratoryRate: 13, SyncComplete: true};

test('expected metrics follow what the bracelet actually records', () => {
  const c = load();
  const rows = Array.from({length: 10}, () => ({values: Object.assign({}, full)}));
  const expected = c.expectedMetricsV1_(rows);
  assert.ok(expected.includes('Steps') && expected.includes('AvgHrvRmssdMs'));
  assert.ok(!expected.includes('AvgSkinTempDeltaC'));          // never recorded → not expected
  assert.deepEqual(Array.from(c.expectedMetricsV1_(rows.slice(0, 3))), []); // too little history
});

test('missing or implausibly low steps make a day incomplete', () => {
  const c = load();
  const expected = ['Steps', 'AvgHeartRate', 'SleepHours'];
  let e = c.evaluateDayQualityV1_(Object.assign({}, full, {Steps: 120}), expected);
  assert.equal(e.ok, false); assert.deepEqual(Array.from(e.missing), ['шаги']);
  e = c.evaluateDayQualityV1_(Object.assign({}, full, {AvgHeartRate: ''}), expected);
  assert.deepEqual(Array.from(e.missing), ['пульс']);
  assert.equal(c.evaluateDayQualityV1_(full, expected).ok, true);
  assert.match(c.qualityTextV1_({ok: false, missing: ['пульс'], complete: true, checks: 3}), /в источнике нет данных/);
});

function daySheet(rows) {
  const headers = Array.from(vm.runInContext('DAY_HEADERS_V3', load()));
  const data = [headers].concat(rows.map(r => headers.map(h => r[h] ?? '')));
  return {
    data,
    getLastRow: () => data.length, getLastColumn: () => headers.length,
    getRange(row, col, n = 1, w = 1) {
      return {
        getValues: () => data.slice(row - 1, row - 1 + n).map(r => r.slice(col - 1, col - 1 + w)),
        getDisplayValues() { return this.getValues().map(r => r.map(String)); },
        setValues(values) { values.forEach((r, i) => r.forEach((v, j) => { data[row - 1 + i][col - 1 + j] = v; })); }
      };
    }
  };
}

test('repair plan lists gaps and incomplete days, stops after 3 checks', () => {
  const c = load();
  const days = [];
  for (let d = 1; d <= 21; d++) {
    const date = `2026-10-${String(d).padStart(2, '0')}`;
    if (d >= 11 && d <= 20) continue;                       // the real hole of October 2026
    days.push(Object.assign({}, full, {Date: date}));
  }
  days[3].AvgHeartRate = '';                                // 2026-10-04: no heart rate yet
  days[2].Steps = 50; days[2].QualityChecks = 3;            // 2026-10-03: confirmed empty
  const report = c.dayQualityReportV1_(daySheet(days), 'Europe/Moscow', '2026-10-22');
  const repair = Array.from(report.repairDates);
  assert.ok(repair.includes('2026-10-04'));
  assert.ok(!repair.includes('2026-10-03'));
  for (let d = 11; d <= 20; d++) assert.ok(repair.includes(`2026-10-${d}`));
  assert.ok(!repair.includes('2026-10-21'));
  assert.ok(report.incompleteDays.some(x => x.date === '2026-10-03'));
});

test('quality refresh counts checks and resets when the day becomes OK', () => {
  const now = new Date('2026-10-22T12:00:00Z');
  const RealDate = Date;
  class FixedDate extends RealDate { constructor(...a) { super(...(a.length ? a : [now])); } static now() { return now.getTime(); } }
  const c = load(new Map(), {Date: FixedDate});
  const rows = Array.from({length: 8}, (_, i) => Object.assign({}, full, {Date: `2026-10-${String(10 + i).padStart(2, '0')}`}));
  rows[7].AvgHeartRate = '';
  const sheet = daySheet(rows);
  const q = sheet.data[0].indexOf('Quality');
  c.refreshDayQualityV1_(sheet, ['2026-10-17'], 'Europe/Moscow');
  assert.match(sheet.data[8][q], /нет: пульс \(проверка 1 из 3\)/);
  assert.equal(sheet.data[8][q + 1], 1);
  sheet.data[8][sheet.data[0].indexOf('AvgHeartRate')] = 70;
  c.refreshDayQualityV1_(sheet, ['2026-10-17'], 'Europe/Moscow');
  assert.equal(sheet.data[8][q], 'OK'); assert.equal(sheet.data[8][q + 1], 0);
});

test('abort rejects runs started before it, not later ones or other clients', () => {
  const props = new Map();
  const c = load(props);
  const before = Date.now() - 1000;
  const r = c.abortSyncV1_({pauseServerJobs: true});
  assert.equal(r.ok, true); assert.equal(r.serverJobsPaused, true);
  assert.equal(c.isAbortedRunV1_({runStartedAt: before}), true);
  assert.equal(c.isAbortedRunV1_({runStartedAt: Date.now() + 1000}), false);
  assert.equal(c.isAbortedRunV1_({}), false);          // outbox, scale, FatSecret
  c.abortSyncV1_({pauseServerJobs: false});
  assert.equal(c.serverJobsPausedV1_(), false);
});

test('paused server jobs skip work, except a copyback that blocks writes', () => {
  const props = new Map([['SERVER_JOBS_PAUSED_V1', '1']]);
  const c = load(props);
  assert.equal(c.compactOldMeasurementsV1().status, 'paused');
  assert.equal(c.migrateMeasurementsTo5mV1().status, 'paused');
  props.set('MEAS_5M_PHASE', 'copyback');
  c.LockService = {getScriptLock: () => ({tryLock: () => false})};
  c.ScriptApp = {getProjectTriggers: () => [], newTrigger: () => ({timeBased: () => ({after: () => ({create() {}})})})};
  assert.notEqual(c.migrateMeasurementsTo5mV1().status, 'paused');
});

test('status reports lock holder without taking the lock for long', () => {
  const props = new Map();
  const c = load(props, {
    LockService: {getScriptLock: () => ({tryLock: () => false, releaseLock() {}})},
    ScriptApp: {getProjectTriggers: () => [{getHandlerFunction: () => 'fitbitScheduledSyncV1', getEventType: () => 'CLOCK'}]}
  });
  c.getSpreadsheet_ = () => ({getSheetByName: () => ({getLastRow: () => 400001})});
  c.markLockHolderV1_('healthSyncV3', 'run-1', 'Pixel');
  const s = c.serverStatusV1_();
  assert.equal(s.lock.busy, true);
  assert.match(s.lock.text, /healthSyncV3 с Pixel/);
  assert.equal(s.triggers[0].handler, 'fitbitScheduledSyncV1');
  assert.equal(s.rawHighVolumeStored, false);
});
