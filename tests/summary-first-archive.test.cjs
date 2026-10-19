const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');

const streamer = () => fs.readFileSync('app/src/main/java/ru/doronin/healthconnector/HealthSyncStreamer.kt', 'utf8');
const server = () => fs.readFileSync('apps-script/Code.gs', 'utf8');

test('client sends the daily summary before session and raw file batches', () => {
  const code = streamer();
  const a = code.indexOf('// Stage A: small, complete trend snapshot');
  const b = code.indexOf('// Stage B: detailed sleep/workouts');
  const c = code.indexOf('// Final acknowledgement: a day is complete');
  assert.ok(a > 0 && b > a && c > b);
  assert.ok(code.includes('action = "healthArchiveV4"'));
  assert.ok(code.includes('archiveSpool.bufferedReader'));
  assert.ok(code.includes('requireArchiveReceipt(receipt, 0, 0, rawBatch.length())'));
  assert.ok(!code.includes('MeasurementBuckets.group(samples)'));
});

test('original bounded 2000-measurement batching remains enabled', () => {
  const code = streamer();
  assert.match(code, /MAX_MEASUREMENTS_PER_REQUEST = 2000/);
  assert.match(code, /MeasurementBatcher\(MAX_MEASUREMENTS_PER_REQUEST\)/);
  assert.match(code, /archiveSpool\.appendText\(batch\.toString\(\) \+ "\\n"/);
});

test('client retries transient DNS failures and does not switch to history fallback when offline', () => {
  const code = streamer();
  assert.match(code, /isConnectivityFailure\(error\)\) throw error/);
  assert.match(code, /error is java\.net\.UnknownHostException/);
  assert.match(code, /return isConnectivityFailure\(error\) \|\|/);
});

test('v4 client and server declare matching archive protocol', () => {
  const client = fs.readFileSync('app/src/main/java/ru/doronin/healthconnector/ServerCompatibility.kt', 'utf8');
  assert.match(client, /REQUIRED_SCHEMA = 4/);
  assert.match(client, /requireArchiveV4/);
  const code = server();
  assert.match(code, /archiveProtocol: 'healthArchiveV4'/);
  assert.match(code, /function importHealthArchiveFilesV4_/);
  assert.doesNotMatch(code, /if \(payload\.action === 'healthArchiveV4'\) \{\s*if \(!driveArchiveEnabledV1_\(\)\)/);
});
