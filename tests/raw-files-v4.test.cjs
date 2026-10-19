const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const crypto = require('node:crypto');
const zlib = require('node:zlib');

function fixture({denyDrive = false} = {}) {
  const props = new Map([['API_TOKEN', 'test-key']]);
  const files = [];
  let folderCreations = 0;
  let rawSheetAccess = 0;
  const blob = bytes => ({
    bytes: Buffer.from(bytes),
    getDataAsString: () => Buffer.from(bytes).toString('utf8')
  });
  const folder = {
    getId: () => 'test-private-folder-id',
    getFilesByName: name => {
      const matching = files.filter(f => f.name === name);
      let i = 0;
      return {hasNext: () => i < matching.length, next: () => matching[i++]};
    },
    createFile: b => {
      const item = {
        name: b.name,
        getId: () => String(files.indexOf(item)),
        getBlob: () => b
      };
      files.push(item);
      return item;
    }
  };
  const context = vm.createContext({
    console, Date, Map, Set,
    LockService: {getScriptLock: () => ({tryLock: () => true, releaseLock() {}})},
    PropertiesService: {getScriptProperties: () => ({
      getProperty: key => props.get(key) || '',
      setProperty: (key, value) => props.set(key, value)
    })},
    DriveApp: {
      getRootFolder: () => {
        if (denyDrive) throw Error('Authorization is required');
        return {getId: () => 'root'};
      },
      createFolder: () => {
        folderCreations++;
        if (denyDrive) throw Error('Authorization is required');
        return folder;
      },
      getFolderById: id => {
        assert.equal(id, 'test-private-folder-id');
        if (denyDrive) throw Error('Authorization is required');
        return folder;
      }
    },
    Utilities: {
      DigestAlgorithm: {SHA_256: 'sha256'}, Charset: {UTF_8: 'utf8'},
      computeDigest: (_algorithm, input) => Array.from(crypto.createHash('sha256').update(input).digest()),
      newBlob: value => blob(value),
      gzip: (value, name) => Object.assign(blob(zlib.gzipSync(value.bytes)), {name}),
      ungzip: value => blob(zlib.gunzipSync(value.bytes)),
      formatDate: (instant, tz) => new Intl.DateTimeFormat('en-CA', {
        timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit'
      }).format(instant)
    }
  });
  vm.runInContext(fs.readFileSync('apps-script/Code.gs', 'utf8'), context);
  const spreadsheet = {getId: () => 'book', getSpreadsheetTimeZone: () => 'Europe/Moscow'};
  context.constantTimeTokenEquals_ = (a,b) => a === b;
  context.getSpreadsheet_ = () => spreadsheet;
  context.ensureSheet_ = (_ss, name) => {
    if (name !== 'HC_Журнал') rawSheetAccess++;
    return {appendRow() {}};
  };
  context.json_ = value => value;
  const send = overrides => context.doPost({postData: {contents: JSON.stringify({
    token: 'test-key', action: 'healthArchiveV4', syncedAt: '2026-10-18T20:00:00Z',
    days: [], workouts: [], sleepSessions: [], measurements: [], ...overrides
  })}});
  return {context, send, files, props,
    folderCreations: () => folderCreations,
    rawSheetAccess: () => rawSheetAccess};
}

test('V4 archives without running Drive legacy migration and never accesses raw sheets', () => {
  const f = fixture();
  const response = f.send({
    workouts: [{id:'w', start:'2026-10-18T10:00:00Z', end:'2026-10-18T11:00:00Z', exerciseType: 1}],
    sleepSessions: [{id:'s', start:'2026-10-17T23:00:00Z', end:'2026-10-18T08:00:00Z', stagesJson:'[1,2]'}],
    measurements: [
      {id:'heart|raw|1|0',time:'2026-10-18T12:00:00Z', type:'HeartRate',value:0,unit:'bpm',samples:1,sourcePackage:'com.health'},
      {id:'heart|raw|2|0',time:'2026-10-18T12:00:01Z', type:'HeartRate',value:72,unit:'bpm',samples:1}
    ]
  });
  assert.equal(response.ok, true);
  assert.equal(response.archived, true);
  assert.equal(response.archiveSchemaVersion, 4);
  assert.equal(response.workouts, 1);
  assert.equal(response.sleepSessions, 1);
  assert.equal(response.measurements, 2);
  assert.equal(f.rawSheetAccess(), 0);
  assert.equal(f.folderCreations(), 1);
  assert.equal(f.props.get('DRIVE_ARCHIVE_ENABLED_V1'), undefined);
  assert.equal(f.files.length, 3);
  const raw = f.files.map(file => JSON.parse(zlib.gunzipSync(file.getBlob().bytes)))
    .find(doc => doc.source === 'HC_Измерения');
  assert.equal(raw.rows.length, 2);
  assert.equal(raw.rows[0][5], 0);
  assert.equal(raw.rows[1][5], 72);
  assert.equal(raw.rows[1][10], 1);
});

test('a repeat of the same batch reuses the verified file and folder', () => {
  const f = fixture();
  const p = {measurements:[{id:'one',time:'2026-10-18T12:00:00Z',type:'HeartRate',value:75}]};
  assert.equal(f.send(p).ok, true);
  assert.equal(f.send(p).ok, true);
  assert.equal(f.files.length, 1);
  assert.equal(f.folderCreations(), 1);
});

test('a missing Google Drive permission fails closed; no bogus archive receipt', () => {
  const f = fixture({denyDrive:true});
  assert.equal(f.context.rawFilesReadyV4_().ready, false);
  const response = f.send({measurements:[{id:'one',time:'2026-10-18T12:00:00Z',value:80}]});
  assert.equal(response.ok, false);
  assert.equal(response.archived, undefined);
  assert.match(response.message, /Authorization is required/);
  assert.equal(f.rawSheetAccess(), 0);
});

test('readiness GET reports capability without creating data', () => {
  const f = fixture();
  f.context.googleHealthIsOAuthCallbackV1_ = () => false;
  f.context.googleHealthStatusV1_ = () => ({});
  f.context.fitbitStatusV1_ = () => ({});
  const status = f.context.doGet({parameter:{}});
  assert.equal(status.schemaVersion,4);
  assert.equal(status.rawStorage.mode, 'on-demand-files');
  assert.equal(status.rawStorage.ready, true);
  assert.equal(f.folderCreations(), 0);
  assert.equal(f.files.length, 0);
});

test('V4 refuses days arrays and leaves daily sheet untouched', () => {
  const f = fixture();
  const response = f.send({days:[{date:'2026-10-18',steps:10000}]});
  assert.equal(response.ok,false);
  assert.match(response.message,/не принимает суточные сводки/);
  assert.equal(f.folderCreations(),0);
  assert.equal(f.rawSheetAccess(),0);
});
