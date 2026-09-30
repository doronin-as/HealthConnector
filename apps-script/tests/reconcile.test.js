// Runs Code.gs in a plain Node VM (no Apps Script services) and checks the pure
// reconciliation helpers. Usage: node apps-script/tests/reconcile.test.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const context = {};
vm.createContext(context);
vm.runInContext(fs.readFileSync(path.join(__dirname, '..', 'Code.gs'), 'utf8'), context);
const run = code => vm.runInContext(code, context);
// Arrays from the VM realm are compared by value.
const same = (actual, expected) => assert.deepStrictEqual(JSON.parse(JSON.stringify(actual)), expected);

const specs = run('STALE_ROW_SPECS_V3');
const staleRowIndexes = run('staleRowIndexesV3_');
const parseWindow = run('parseReconcileWindowV3_');
const deleteRowIndexes = run('deleteRowIndexesV3_');

const dayStart = Date.parse('2026-09-28T21:00:00Z');
const dayEnd = Date.parse('2026-09-29T21:00:00Z');
const current = '2026-09-30T10:00:00.123456Z';
const currentMs = Date.parse(current);
const older = '2026-09-20T08:00:00Z';

function measurement(id, time, start, source, syncedAt) {
  return [id, time, start, '', 'HeartRate', 60, 'bpm', source, 'x', syncedAt];
}

const headers = run('DAY_HEADERS_V3');
const upsertDays = run('upsertDayObjectsPartialV3_');

// One existing HC_Дни row for 2026-09-29, enough of the Sheet API for the day upsert.
function fakeDaySheet(initial) {
  const row = headers.map(header => (header === 'Date' ? '2026-09-29' : ''));
  Object.keys(initial).forEach(header => { row[headers.indexOf(header)] = initial[header]; });
  const rows = [headers.slice(), row];
  return {
    getLastRow: () => rows.length,
    getRange: (r, c, nr, nc) => ({
      getValues: () => rows.slice(r - 1, r - 1 + nr).map(values => values.slice(c - 1, c - 1 + nc)),
      setValues: values => values.forEach((values, i) => {
        values.forEach((value, j) => { rows[r - 1 + i][c - 1 + j] = value; });
      })
    }),
    cell: header => rows[1][headers.indexOf(header)]
  };
}

const tests = {
  'stale phone rows inside the day are swept'() {
    const rows = [
      measurement('kept-current', '2026-09-29T06:00:00Z', '', 'com.fitbit.FitbitMobile', current),
      measurement('deleted', '2026-09-29T07:00:00Z', '', 'com.fitbit.FitbitMobile', older),
      measurement('interval-deleted', '', '2026-09-29T08:00:00Z', 'com.xiaomi.wearable', older)
    ];
    same(staleRowIndexes(rows, specs.measurement, dayStart, dayEnd, currentMs), [1, 2]);
  },
  'rows outside the day window are kept'() {
    const rows = [
      measurement('previous-day', '2026-09-28T20:59:59Z', '', 'com.fitbit.FitbitMobile', older),
      measurement('next-day', '2026-09-29T21:00:00Z', '', 'com.fitbit.FitbitMobile', older)
    ];
    same(staleRowIndexes(rows, specs.measurement, dayStart, dayEnd, currentMs), []);
  },
  'cloud rows and unreadable rows are kept'() {
    const rows = [
      measurement('cloud', '2026-09-29T06:00:00Z', '', 'fitbit.webapi', older),
      measurement('no-time', '', '', 'com.fitbit.FitbitMobile', older),
      measurement('no-synced-at', '2026-09-29T06:00:00Z', '', 'com.fitbit.FitbitMobile', '')
    ];
    same(staleRowIndexes(rows, specs.measurement, dayStart, dayEnd, currentMs), []);
  },
  'SyncedAt rounded by Sheets into a Date still counts as current'() {
    // Built inside the VM so `instanceof Date` matches, as it does in Apps Script.
    const rounded = run(`new Date(${Math.floor(currentMs / 1000) * 1000})`);
    const rows = [measurement('rounded', '2026-09-29T06:00:00Z', '', 'com.fitbit.FitbitMobile', rounded)];
    same(staleRowIndexes(rows, specs.measurement, dayStart, dayEnd, currentMs), []);
  },
  'sleep is dated by its end (wake) time'() {
    const row = (end) => ['s', '2026-09-28T19:00:00Z', end, 480, '', '', 0, 0, 0, 0, 0, '[]',
      'com.fitbit.FitbitMobile', 'Fitbit', older];
    const rows = [row('2026-09-29T03:00:00Z'), row('2026-09-28T20:00:00Z')];
    same(staleRowIndexes(rows, specs.sleep, dayStart, dayEnd, currentMs), [0]);
  },
  'reconcile window is validated'() {
    assert.ok(parseWindow({ start: '2026-09-28T21:00:00Z', end: '2026-09-29T21:00:00Z' }));
    assert.strictEqual(parseWindow({ start: '2026-09-29T21:00:00Z', end: '2026-09-28T21:00:00Z' }), null);
    assert.strictEqual(parseWindow({ start: '2026-09-20T00:00:00Z', end: '2026-09-29T00:00:00Z' }), null);
    assert.strictEqual(parseWindow({ start: 'nope', end: '2026-09-29T00:00:00Z' }), null);
    assert.strictEqual(parseWindow(null), null);
  },
  'ordinary day upsert keeps stored values against empty and zero'() {
    const sheet = fakeDaySheet({ Steps: 8000, WeightKg: 80 });
    upsertDays(sheet, [{ date: '2026-09-29', steps: 0, weightKg: null, availableFields: ['steps', 'weightKg'] }],
      '2026-09-30T10:00:00Z', 'UTC', true, 'COMPLETE', '');
    assert.strictEqual(sheet.cell('Steps'), 8000);
    assert.strictEqual(sheet.cell('WeightKg'), 80);
  },
  'authoritative day upsert writes the real zero and clears emptied fields'() {
    const sheet = fakeDaySheet({ Steps: 8000, WeightKg: 80 });
    upsertDays(sheet, [{
      date: '2026-09-29', steps: 0, weightKg: null,
      availableFields: ['steps', 'weightKg'], clearFields: ['steps', 'weightKg']
    }], '2026-09-30T10:00:00Z', 'UTC', true, 'COMPLETE', '');
    assert.strictEqual(sheet.cell('Steps'), 0);
    assert.strictEqual(sheet.cell('WeightKg'), '');
  },
  'contiguous rows are deleted bottom-up in runs'() {
    const calls = [];
    deleteRowIndexes({ deleteRows: (row, count) => calls.push([row, count]) }, [0, 1, 2, 5, 7, 8]);
    same(calls, [[9, 2], [7, 1], [2, 3]]);
  }
};

let failed = 0;
for (const [name, test] of Object.entries(tests)) {
  try {
    test();
    console.log(`ok - ${name}`);
  } catch (error) {
    failed++;
    console.error(`not ok - ${name}\n${error.stack}`);
  }
}
if (failed) process.exit(1);
