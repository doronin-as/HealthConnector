const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');

function load(props = {}) {
  const store = new Map(Object.entries(props));
  const context = vm.createContext({
    console, Date, Map, Set,
    PropertiesService: {getScriptProperties: () => ({getProperty: k => store.get(k) ?? null, setProperty: (k, v) => store.set(k, v)})}
  });
  vm.runInContext(fs.readFileSync('apps-script/Code.gs', 'utf8'), context);
  return context;
}
const row = (id, type) => [id, '2026-10-20T10:00:00Z', '', '', type, 1, 'u', 'pkg', 'src', 's', '', '', ''];

function sheet(lastRow, onUpsert) {
  return {getLastRow: () => lastRow, _upsert: onUpsert};
}

test('small sheet: every measurement is written', () => {
  const c = load(); let written = null;
  c.upsertByKey_ = (_, rows) => { written = rows; };
  const r = c.writeRawMeasurementsGuardedV1_(sheet(1000), [row('a', 'Steps'), row('b', 'Weight')]);
  assert.equal(written.length, 2); assert.equal(r.stored, 2); assert.equal(r.skipped, 0);
});

test('overloaded sheet: high-volume rows skipped, low-volume kept, no error', () => {
  const c = load(); let written = null;
  c.upsertByKey_ = (_, rows) => { written = rows; };
  const r = c.writeRawMeasurementsGuardedV1_(sheet(300000),
    [row('a', 'Steps'), row('b', 'HeartRate'), row('c', 'Weight'), row('d', 'SpO2')]);
  assert.deepEqual(written.map(x => x[0]), ['c', 'd']);
  assert.equal(r.stored, 2); assert.equal(r.skipped, 2); assert.match(r.reason, /переполнен/);
});

test('batch of only high-volume rows on overloaded sheet does not read the sheet', () => {
  const c = load(); let readKeys = false;
  const s = {getLastRow: () => 300000, getRange: () => { readKeys = true; throw Error('should not read'); }};
  const r = c.writeRawMeasurementsGuardedV1_(s, [row('a', 'Distance'), row('b', 'TotalCalories')]);
  assert.equal(readKeys, false); assert.equal(r.skipped, 2);
});

test('explicit keep / skip modes', () => {
  let c = load({SHEETS_RAW_HIGH_VOLUME_V1: 'keep'}); let n = 0;
  c.upsertByKey_ = (_, rows) => { n = rows.length; };
  c.writeRawMeasurementsGuardedV1_(sheet(900000), [row('a', 'Steps')]);
  assert.equal(n, 1);
  c = load({SHEETS_RAW_HIGH_VOLUME_V1: 'skip'}); n = -1;
  c.upsertByKey_ = (_, rows) => { n = rows.length; };
  c.writeRawMeasurementsGuardedV1_(sheet(10), [row('a', 'Steps')]);
  assert.equal(n, 0);
});

test('workbook cell limit is tolerated, other errors still fail', () => {
  const c = load();
  c.upsertByKey_ = () => { throw Error('This action would increase the number of cells in the workbook above the limit of 10000000 cells.'); };
  const r = c.writeRawMeasurementsGuardedV1_(sheet(10), [row('a', 'Weight')]);
  assert.equal(r.stored, 0); assert.equal(r.skipped, 1);
  c.upsertByKey_ = () => { throw Error('Service unavailable'); };
  assert.throws(() => c.writeRawMeasurementsGuardedV1_(sheet(10), [row('a', 'Weight')]), /Service unavailable/);
});

test('V3 import writes the day summary even when raw rows are skipped, and a full log is not fatal', () => {
  const c = load(); let dayWritten = false;
  c.ensureSheet_ = (_, name) => ({name, getLastRow: () => (name === 'HC_Измерения' ? 400000 : 2)});
  c.assertMeasurementsWritableV1_ = () => {};
  c.upsertByKey_ = (s, rows) => { if (s.name === 'HC_Измерения') assert.equal(rows.length, 0); };
  c.upsertDayObjectsPartialV3_ = (_, days) => { dayWritten = days.length === 1; };
  const result = c.importHealthPayloadV3_(
    {getSpreadsheetTimeZone: () => 'Europe/Moscow'},
    {appendRow() { throw Error('limit of 10000000 cells'); }},
    {days: [{date: '2026-10-21'}], workouts: [], sleepSessions: [],
     measurements: [{id: 'r|5m|1', type: 'HeartRate', value: 70}], dayComplete: true});
  assert.equal(dayWritten, true); assert.equal(result.ok, true); assert.equal(result.measurementsSkipped, 1);
});
