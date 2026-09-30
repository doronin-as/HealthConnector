// Runs Code.gs in a plain Node VM (no Apps Script services) and checks how Google
// Health API data points become HC_Дни fields. Usage: node apps-script/tests/google-health-vitals.test.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const context = {};
vm.createContext(context);
vm.runInContext(fs.readFileSync(path.join(__dirname, '..', 'Code.gs'), 'utf8'), context);
const build = vm.runInContext('googleHealthBuildVitalsDayV1_', context);
const plain = value => JSON.parse(JSON.stringify(value));
const date = '2026-09-29';
const day = obj => ({ year: 2026, month: 9, day: 29, ...obj });

// Full set of points: int64 values arrive as strings.
{
  const { day: result, measurements } = plain(build(date, {
    HEART_RATE: [{ beatsPerMinute: '60' }, { beatsPerMinute: '80' }, { beatsPerMinute: '70' }],
    RESTING_HEART_RATE: [{ date: day(), beatsPerMinute: '55' }],
    HRV_DAILY: [{ date: day(), averageHeartRateVariabilityMilliseconds: 42.5 }],
    HRV_SAMPLES: [
      { rootMeanSquareOfSuccessiveDifferencesMilliseconds: 30 },
      { rootMeanSquareOfSuccessiveDifferencesMilliseconds: 50 }
    ],
    SPO2_DAILY: [{ date: day(), percentage: '96' }],
    SPO2_SAMPLES: [],
    RESPIRATORY_DAILY: [],
    RESPIRATORY_SLEEP: [{ date: day(), respiratoryRateSleepSummaryStatistics: { average: 14.2, minimum: 12, maximum: 17 } }]
  }, '2026-09-30T00:00:00Z'));

  assert.strictEqual(result.averageHeartRate, 70);
  assert.strictEqual(result.minimumHeartRate, 60);
  assert.strictEqual(result.maximumHeartRate, 80);
  assert.strictEqual(result.heartRateSamples, 3);
  assert.strictEqual(result.restingHeartRate, 55);
  // The daily summary is the canonical average; samples give the range.
  assert.strictEqual(result.averageHrvRmssdMs, 42.5);
  assert.strictEqual(result.minimumHrvRmssdMs, 30);
  assert.strictEqual(result.maximumHrvRmssdMs, 50);
  assert.strictEqual(result.hrvSamples, 2);
  assert.strictEqual(result.averageSpO2, 96);
  assert.strictEqual(result.minimumSpO2, 96);
  assert.strictEqual(result.spO2Samples, 1);
  assert.strictEqual(result.averageRespiratoryRate, 14.2);
  assert.strictEqual(result.minimumRespiratoryRate, 12);
  assert.strictEqual(result.maximumRespiratoryRate, 17);
  assert.deepStrictEqual(result.sourcePackages, ['google.health.api']);
  assert.ok(result.availableFields.includes('sourcePackages'));

  // Every field the server will write has an HC_Дни column.
  const mapping = vm.runInContext('DAY_FIELD_TO_HEADER_V3', context);
  result.availableFields.forEach(name => assert.ok(mapping[name], `no HC_Дни column for ${name}`));

  assert.deepStrictEqual(measurements.map(m => m.type).sort(), [
    'HRV_RMSSD_Daily', 'HeartRateDailyAvg', 'RespiratoryRateDaily', 'RestingHeartRate', 'SpO2DailyAvg'
  ]);
  assert.ok(measurements.every(m => m.id.startsWith('google-health|') && m.id.endsWith('|' + date)));
}

// Nothing fetched, or a failed fetch: no metric fields, so nothing is written or cleared.
{
  const { day: result, measurements } = plain(build(date, {}, 'x'));
  assert.deepStrictEqual(result.availableFields, []);
  assert.deepStrictEqual(measurements, []);
}

// Samples only, and a daily respiratory rate without the sleep summary.
{
  const { day: result } = plain(build(date, {
    SPO2_SAMPLES: [{ percentage: '94' }, { percentage: '98' }],
    RESPIRATORY_DAILY: [{ date: day(), breathsPerMinute: '15' }]
  }, 'x'));
  assert.strictEqual(result.averageSpO2, 96);
  assert.strictEqual(result.minimumSpO2, 94);
  assert.strictEqual(result.maximumSpO2, 98);
  assert.strictEqual(result.spO2Samples, 2);
  assert.strictEqual(result.averageRespiratoryRate, 15);
  assert.strictEqual(result.minimumRespiratoryRate, 15);
  assert.strictEqual(result.respiratorySamples, 1);
  assert.strictEqual(result.averageHeartRate, undefined);
}

// Daily points for another date are ignored by the list helper's date check.
{
  const matches = vm.runInContext('googleHealthDateMatchesV1_', context);
  assert.strictEqual(matches({ year: 2026, month: 9, day: 29 }, date), true);
  assert.strictEqual(matches({ year: 2026, month: 9, day: 28 }, date), false);
  assert.strictEqual(matches(undefined, date), true);
}

console.log('google-health-vitals tests passed');
