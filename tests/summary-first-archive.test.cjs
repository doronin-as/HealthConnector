const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');

function setup(enabled = true) {
  const events = [];
  const context = vm.createContext({
    console, Date, Map, Set,
    LockService: { getScriptLock: () => ({ tryLock: () => true, releaseLock() {} }) },
    PropertiesService: { getScriptProperties: () => ({
      getProperty: key => key === 'API_TOKEN' ? 'local-test-token' : ''
    }) }
  });
  vm.runInContext(fs.readFileSync('apps-script/Code.gs', 'utf8'), context);
  // Isolate the authenticated routing contract from the existing spreadsheet
  // implementation. End-to-end Drive digest/readback is covered separately.
  context.constantTimeTokenEquals_ = (actual, expected) => actual === expected;
  context.getSpreadsheet_ = () => ({ getSpreadsheetTimeZone: () => 'Europe/Moscow' });
  context.ensureSheet_ = () => ({ appendRow() {} });
  context.json_ = value => value;
  context.driveArchiveEnabledV1_ = () => enabled;
  context.importHealthPayloadV3_ = (_sheet, _log, payload) => {
    events.push(payload);
    return {
      ok: true, days: payload.days.length, workouts: payload.workouts.length,
      sleepSessions: payload.sleepSessions.length,
      measurements: payload.measurements.length
    };
  };
  const send = body => context.doPost({postData: { contents: JSON.stringify({
    token: 'local-test-token', action: 'healthArchiveV4',
    days: [], workouts: [], sleepSessions: [], measurements: [], ...body
  }) }});
  return { context, events, send };
}

test('server GET advertises v4; client requires v4 before any sync', () => {
  const f = setup(true);
  f.context.googleHealthIsOAuthCallbackV1_ = () => false;
  f.context.googleHealthStatusV1_ = () => ({});
  f.context.fitbitStatusV1_ = () => ({});
  const info = f.context.doGet({ parameter: {} });
  assert.equal(info.schemaVersion, 4);
  assert.equal(info.archiveProtocol, 'healthArchiveV4');
  const client = fs.readFileSync('app/src/main/java/ru/doronin/healthconnector/ServerCompatibility.kt', 'utf8');
  assert.match(client, /REQUIRED_SCHEMA = 4/);
  assert.match(client, /requireArchiveV4/);
});

test('archive V4 never silently falls back to raw Sheets', () => {
  const f = setup(false);
  const reply = f.send({ measurements: [{ id: 'x' }] });
  assert.equal(reply.ok, false);
  assert.equal(reply.errorCode, 'ARCHIVE_DISABLED');
  assert.equal(f.events.length, 0);
});

test('archive V4 rejects accidental daily summary overwrites', () => {
  const f = setup(true);
  const reply = f.send({ days: [{ date: '2026-10-14', steps: 10000 }] });
  assert.equal(reply.ok, false);
  assert.match(reply.message, /не принимает суточные сводки/);
  assert.equal(f.events.length, 0);
});

test('archive V4 acknowledges each kind and preserves details', () => {
  const f = setup(true);
  const payload = {
    rangeStart: '2026-10-14',
    workouts: [{ id: 'workout', segmentsJson: '[{"type":1}]' }],
    sleepSessions: [{ id: 'sleep', stagesJson: '[{"stage":2}]' }],
    measurements: [{ id: 'r|raw|10|0', time: '2026-10-14T01:00:00Z',
      type: 'HeartRate', value: 67, sourcePackage: 'com.example.health' }]
  };
  const reply = f.send(payload);
  assert.equal(reply.ok, true);
  assert.equal(reply.archived, true);
  assert.equal(reply.archiveSchemaVersion, 4);
  assert.equal(reply.days, 0);
  assert.equal(reply.workouts, 1);
  assert.equal(reply.sleepSessions, 1);
  assert.equal(reply.measurements, 1);
  assert.equal(f.events.length, 1);
  assert.equal(f.events[0].days.length, 0);
  assert.deepEqual(JSON.parse(JSON.stringify(f.events[0].measurements)), payload.measurements);
});

test('archive V4 fails closed on an archive read-back exception', () => {
  const f = setup(true);
  f.context.importHealthPayloadV3_ = () => { throw Error('Archive verification failed'); };
  const reply = f.send({ measurements: [{id:'x'}] });
  assert.equal(reply.ok, false);
  assert.match(reply.message, /Archive verification failed/);
});

test('Android sync submits complete summary before raw archive then commits', () => {
  const code = fs.readFileSync('app/src/main/java/ru/doronin/healthconnector/HealthSyncStreamer.kt', 'utf8');
  const a = code.indexOf('// Stage A: small, complete trend snapshot');
  const b = code.indexOf('// Stage B: detailed sleep/workouts');
  const c = code.indexOf('// Final acknowledgement: a day is complete');
  assert.ok(a > 0 && b > a && c > b);
  assert.ok(code.includes('action = "healthArchiveV4"'));
  assert.ok(code.includes('archiveSpool.bufferedReader'));
  assert.ok(code.includes('requireArchiveReceipt(receipt, 0, 0, rawBatch.length())'));
  assert.ok(!code.includes('MeasurementBuckets.group(samples)'));
});
