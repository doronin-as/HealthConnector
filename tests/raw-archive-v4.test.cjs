const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const crypto = require('node:crypto');
const zlib = require('node:zlib');

// In-memory Drive + Sheets + Script services, enough for the archive job.
function fixture({rows = 10, maxRows = null, failReadback = false, formulasElsewhere = false} = {}) {
  const props = new Map([['RAW_FILES_V4_FOLDER_ID', 'folder']]);
  const files = [];
  const triggers = [];
  const headers = ['Id','Time','Start','End','Type','Value','Unit','SourcePackage','SourceName','SyncedAt','Samples','Min','Max'];
  const data = [headers].concat(Array.from({length: rows}, (_, i) =>
    [`r${i}`, `2026-10-${String(1 + (i % 20)).padStart(2, '0')}T08:00:00Z`, '', '', 'HeartRate', 60 + i, 'bpm', 'pkg', 'src', 's', 1, 60, 60]));
  let max = maxRows ?? data.length + 500;
  let deletes = 0;
  const sheet = {
    getLastRow: () => data.length, getLastColumn: () => headers.length, getFrozenRows: () => 1,
    getMaxRows: () => max,
    deleteRows(start, count) {
      deletes++;
      if (start <= data.length) data.splice(start - 1, Math.min(count, data.length - start + 1));
      max -= count;
    },
    getRange(row, col, n = 1, w = 1) {
      return {
        getValues: () => data.slice(row - 1, row - 1 + n).map(r => r.slice(col - 1, col - 1 + w)),
        clearContent() { for (let i = 0; i < n; i++) if (data[row - 1 + i]) data.splice(row - 1 + i, 1); }
      };
    }
  };
  const archiveNote = [['HC_Измерения', 'старое'], ['Способ хранения', 'ошибка сервиса'], ['Обновлено', '14.10.2026']];
  const archiveSheet = {
    getLastRow: () => archiveNote.length,
    getRange(row, col, n = 1, w = 1) {
      return {
        getValues: () => archiveNote.slice(row - 1, row - 1 + n).map(r => r.slice(col - 1, col - 1 + w)),
        getFormula: () => '', setValue: v => { archiveNote[row - 1][col - 1] = v; }
      };
    }
  };
  const blob = bytes => ({bytes: Buffer.from(bytes), getDataAsString: () => Buffer.from(bytes).toString()});
  const folder = {
    getUrl: () => 'https://drive.google.com/drive/folders/folder',
    getFilesByName(name) { const m = files.filter(f => f.name === name); let i = 0; return {hasNext: () => i < m.length, next: () => m[i++]}; },
    createFile(b) {
      const f = {name: b.name, getId: () => String(files.length), getBlob: () => {
        if (failReadback) return blob(zlib.gzipSync(Buffer.from('{"corrupt":true}')));
        return b;
      }};
      files.push(f); return f;
    }
  };
  const spreadsheet = {
    getId: () => 'book', getSpreadsheetTimeZone: () => 'Europe/Moscow',
    getSheetByName: n => n === 'HC_Измерения' ? sheet : n === 'Архив' ? archiveSheet : null,
    getSheets: () => formulasElsewhere ? [{
      getName: () => 'Отчёт', getLastRow: () => 1, getLastColumn: () => 1,
      getRange: () => ({getFormulas: () => [['=COUNT(HC_Измерения!F:F)']]}), getCharts: () => []
    }] : []
  };
  const context = vm.createContext({
    console, Date, Map, Set, Object, Math, Number, String, Array, JSON,
    PropertiesService: {getScriptProperties: () => ({
      getProperty: k => props.has(k) ? props.get(k) : null,
      setProperty: (k, v) => props.set(k, String(v)), deleteProperty: k => props.delete(k)
    })},
    LockService: {getScriptLock: () => ({tryLock: () => true, releaseLock() {}})},
    ScriptApp: {
      getProjectTriggers: () => triggers.slice(),
      newTrigger: handler => ({timeBased: () => ({everyMinutes: () => ({create: () => triggers.push({getHandlerFunction: () => handler})})})}),
      deleteTrigger: t => triggers.splice(triggers.indexOf(t), 1)
    },
    DriveApp: {getFolderById: () => folder},
    Utilities: {
      DigestAlgorithm: {SHA_256: 'sha256'}, Charset: {UTF_8: 'utf8'},
      computeDigest: (_, t) => Array.from(crypto.createHash('sha256').update(t).digest()),
      newBlob: t => blob(t), gzip: (b, name) => Object.assign(blob(zlib.gzipSync(b.bytes)), {name}),
      ungzip: b => blob(zlib.gunzipSync(b.bytes)),
      formatDate: (d, tz, f) => f === 'dd.MM.yyyy' ? '23.10.2026'
        : new Intl.DateTimeFormat('en-CA', {timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit'}).format(d)
    }
  });
  vm.runInContext(fs.readFileSync('apps-script/Code.gs', 'utf8'), context);
  context.getSpreadsheet_ = () => spreadsheet;
  const archivedRows = () => files.reduce((n, f) => n + JSON.parse(zlib.gunzipSync(f.getBlob().bytes)).rows.length, 0);
  return {context, spreadsheet, data, files, triggers, props, archiveNote, archivedRows, deletes: () => deletes, maxRows: () => max};
}

test('large HC_Измерения starts the trigger automatically (throttled)', () => {
  const f = fixture({rows: 60000});
  assert.equal(f.context.ensureRawArchiveScheduledV4_(f.spreadsheet, ''), true);
  assert.equal(f.triggers.length, 1);
  assert.equal(f.context.ensureRawArchiveScheduledV4_(f.spreadsheet, ''), false);   // within 10 minutes
  assert.equal(f.triggers.length, 1);
  const small = fixture({rows: 10});
  assert.equal(small.context.ensureRawArchiveScheduledV4_(small.spreadsheet, ''), false);
  assert.equal(small.context.ensureRawArchiveScheduledV4_(small.spreadsheet, 'workbook-full'), true);
});

test('every row lands in verified Drive files before the sheet is emptied; trigger removed', () => {
  const f = fixture({rows: 9001});
  f.context.ensureRawArchiveScheduledV4_(f.spreadsheet, 'manual');
  const result = f.context.archiveRawMeasurementsV4();
  assert.equal(result.status, 'done');
  assert.equal(result.archivedRows, 9001);
  assert.equal(f.archivedRows(), 9001);
  assert.equal(f.data.length, 1);                       // only headers remain
  assert.equal(f.triggers.length, 0);
  assert.ok(f.maxRows() <= 2);                          // surplus rows released
  assert.match(f.archiveNote[1][1], /Перенесено строк: 9001/);
  assert.equal(f.archiveNote[2][1], '23.10.2026');
});

test('read-back failure keeps the rows in the sheet', () => {
  const f = fixture({rows: 50, failReadback: true});
  const result = f.context.archiveRawMeasurementsV4();
  assert.equal(result.status, 'error');
  assert.match(result.lastError, /verification failed/);
  assert.equal(f.data.length, 51);
  const again = fixture({rows: 50});
  again.context.archiveRawMeasurementsV4();
  assert.equal(again.data.length, 1);
});

test('rerun after a crash reuses the same verified file (no duplicates)', () => {
  const f = fixture({rows: 30});
  const rows = f.data.slice(1).map(r => r.slice());
  f.context.archiveRowsToDriveV1_(f.spreadsheet, 'HC_Измерения', vm.runInContext('MEASUREMENT_HEADERS', f.context), rows, 'sheet-archive', {
    getFilesByName: n => ({hasNext: () => false}), createFile: b => { f.files.push({name: b.name, getId: () => 'x', getBlob: () => b}); return f.files[f.files.length - 1]; }
  });
  const before = f.files.length;
  f.context.archiveRawMeasurementsV4();                 // same rows, same files
  assert.equal(f.files.length, before);
  assert.equal(f.data.length, 1);
});

test('reports that read HC_Измерения block the archive', () => {
  const f = fixture({rows: 20, formulasElsewhere: true});
  const result = f.context.archiveRawMeasurementsV4();
  assert.equal(result.status, 'blocked');
  assert.equal(f.data.length, 21);
});

test('paused server jobs also pause the archive', () => {
  const f = fixture({rows: 20});
  f.props.set('SERVER_JOBS_PAUSED_V1', '1');
  assert.equal(f.context.archiveRawMeasurementsV4().status, 'paused');
  assert.equal(f.data.length, 21);
});
