const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');

// Minimal in-memory Sheets: a sheet is a 2D array, row 1 = headers.
function memorySheet(name, rows = []) {
  const data = rows.map(r => r.slice());
  const formulas = new Map();
  const sheet = {
    name, data, formulas,
    getName: () => name,
    getLastRow: () => data.length,
    getLastColumn: () => Math.max(0, ...data.map(r => r.length)),
    getMaxColumns: () => 30, getMaxRows: () => 1000,
    insertColumnsAfter() {}, setFrozenRows() {},
    getRange(row, col, numRows = 1, numCols = 1) {
      return {
        getValues: () => Array.from({length: numRows}, (_, i) => Array.from({length: numCols}, (_, j) => (data[row - 1 + i] || [])[col - 1 + j] ?? '')),
        getDisplayValues() { return this.getValues().map(r => r.map(v => v instanceof Date ? v.toISOString() : String(v))); },
        setValues(values) { values.forEach((r, i) => { const t = (data[row - 1 + i] ||= []); r.forEach((v, j) => { t[col - 1 + j] = v; }); }); },
        setValue(v) { this.setValues([[v]]); },
        getFormula: () => formulas.get(`${row}:${col}`) || '',
        setFontWeight() { return this; }
      };
    }
  };
  return sheet;
}

function load(sheets) {
  const context = vm.createContext({
    console, Date, Map, Set, Object, Math, Number, String, Array, JSON,
    PropertiesService: {getScriptProperties: () => ({getProperty: () => null})},
    Utilities: {formatDate: (d, tz) => new Intl.DateTimeFormat('en-CA', {timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit'}).format(d)}
  });
  vm.runInContext(fs.readFileSync('apps-script/Code.gs', 'utf8'), context);
  const spreadsheet = {
    getSpreadsheetTimeZone: () => 'Europe/Moscow',
    getSheetByName: n => sheets[n] || null,
    insertSheet: n => (sheets[n] = memorySheet(n))
  };
  return {context, spreadsheet};
}

const diaryHeaders = ['Дата', '', 'Вес утро', 'Ккал', 'Вода, л', 'Шаги', 'Самочувствие 1-5'];

test('water: upsert by id, undo excluded, total written to Дневник in liters', () => {
  const diary = memorySheet('Дневник', [diaryHeaders, ['21.10.2026'], ['22.10.2026']]);
  const sheets = {'Дневник': diary};
  const {context, spreadsheet} = load(sheets);
  const send = entries => context.importHydrationV1_(spreadsheet, {entries});
  send([{id: 'water-a', date: '2026-10-22', time: 't', ml: 250, deleted: false},
        {id: 'water-b', date: '2026-10-22', time: 't', ml: 500, deleted: false}]);
  let r = send([{id: 'water-a', date: '2026-10-22', time: 't', ml: 250, deleted: false}]); // retry: no duplicate
  assert.equal(sheets['HC_Вода'].data.length, 3);
  assert.equal(r.totalsMl['2026-10-22'], 750);
  assert.equal(diary.data[2][4], 0.75);
  r = send([{id: 'water-b', date: '2026-10-22', time: 't', ml: 500, deleted: true}]); // undo
  assert.deepEqual(r.accepted, ['water-b']);
  assert.equal(diary.data[2][4], 0.25);
  assert.equal(r.diary['2026-10-22'], 'written');
});

test('water: formula cells and missing diary rows are left alone', () => {
  const diary = memorySheet('Дневник', [diaryHeaders, ['22.10.2026', '', '', '', '=SUM(X1)']]);
  diary.formulas.set('2:5', '=SUM(X1)');
  const sheets = {'Дневник': diary};
  const {context, spreadsheet} = load(sheets);
  const r = context.importHydrationV1_(spreadsheet, {entries: [
    {id: 'w1', date: '2026-10-22', ml: 300}, {id: 'w2', date: '2026-10-25', ml: 300}]});
  assert.equal(diary.data[1][4], '=SUM(X1)');
  assert.equal(r.diary['2026-10-22'], 'formula');
  assert.equal(r.diary['2026-10-25'], 'no-row');
  assert.equal(diary.data.length, 2);
});

test('water: invalid volume rejects the batch; invalid ids are skipped', () => {
  const {context, spreadsheet} = load({});
  assert.throws(() => context.importHydrationV1_(spreadsheet, {entries: [{id: 'w', date: '2026-10-22', ml: 99999}]}), /объём/);
  const r = context.importHydrationV1_(spreadsheet, {entries: [{id: 'bad id!', date: '2026-10-22', ml: 200}]});
  assert.equal(r.accepted.length, 0);
});

test('wellbeing: daily score = mean of (energy, mood) over check-ins; tags translated; formula-injection safe', () => {
  const diary = memorySheet('Дневник', [diaryHeaders, ['22.10.2026']]);
  const sheets = {'Дневник': diary};
  const {context, spreadsheet} = load(sheets);
  context.importWellbeingV1_(spreadsheet, {entries: [
    {id: 'wellbeing-2026-10-22-morning', date: '2026-10-22', slot: 'morning', energy: 4, mood: 5, soreness: 2, stress: null, tags: ['alcohol', 'evil'], note: '=HYPERLINK("x")'},
    {id: 'wellbeing-2026-10-22-evening', date: '2026-10-22', slot: 'evening', energy: 3, mood: 3, stress: 9}]});
  const rows = sheets['HC_Самочувствие'].data;
  assert.equal(rows.length, 3);
  assert.equal(rows[1][8], 'Алкоголь');
  assert.notEqual(String(rows[1][9])[0], '=');
  assert.equal(rows[2][7], '');            // stress 9 rejected
  assert.equal(diary.data[1][6], 3.8);      // (4.5 + 3) / 2 = 3.75 → 3.8
});
