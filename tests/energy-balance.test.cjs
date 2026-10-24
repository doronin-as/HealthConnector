const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');

const context = vm.createContext({console, Date, Map, Set, Object, Math, Number, String, Array, JSON});
vm.runInContext(fs.readFileSync('apps-script/Code.gs', 'utf8'), context);
const compute = context.computeEnergyBalanceV1_;

function day(offset, base = '2026-10-24') {
  const d = new Date(base + 'T12:00:00Z'); d.setUTCDate(d.getUTCDate() + offset); return d.toISOString().slice(0, 10);
}
function intakes(from, to, kcal) {
  const out = []; for (let i = from; i <= to; i++) out.push({date: day(i), kcal: typeof kcal === 'function' ? kcal(i) : kcal}); return out;
}

// Real history of 2026: weigh-ins every 1–2 weeks.
const realWeights = [
  ['2026-09-03', 109.0], ['2026-09-13', 107.9], ['2026-09-27', 107.8], ['2026-10-15', 106.6], ['2026-10-22', 106.5]
].map(([date, kg]) => ({date, kg}));

test('real October 2026 history: expenditure ≈ 2600–2700 kcal, target ≈ 2100 at −0.5 kg/week', () => {
  const r = compute(realWeights, intakes(-55, -1, 2250), {today: '2026-10-24', goalWeightKg: 90, targetRateKgWeek: 0.5, currentPlanKcal: 2255});
  assert.equal(r.status, 'ok');
  assert.ok(r.actualRateKgWeek < -0.3 && r.actualRateKgWeek > -0.45, String(r.actualRateKgWeek));
  assert.ok(r.expenditureKcal >= 2550 && r.expenditureKcal <= 2750, String(r.expenditureKcal));
  assert.ok(Math.abs(r.targetIntakeKcal - (r.expenditureKcal - 550)) <= 10);
  assert.ok(r.goalDateAtTarget > '2027-05-01' && r.goalDateAtTarget < '2027-08-01', r.goalDateAtTarget);
  assert.equal(r.planDifferenceKcal, r.targetIntakeKcal - 2255);
});

test('one odd weigh-in does not tilt the trend (Theil–Sen)', () => {
  const weights = [0, -7, -14, -21, -28, -35].map(o => ({date: day(o), kg: 106 - o * 0.05}));
  weights.push({date: day(-10), kg: 112});                       // wrong entry / clothes
  const r = compute(weights, intakes(-40, -1, 2200), {today: day(0), goalWeightKg: 90});
  assert.ok(Math.abs(r.actualRateKgWeek - (-0.35)) < 0.03, String(r.actualRateKgWeek));
});

test('incomplete food logs are excluded and reported', () => {
  const food = intakes(-40, -1, i => (i % 5 === 0 ? 400 : 2400));
  const r = compute(realWeights, food, {today: '2026-10-24', goalWeightKg: 90});
  assert.equal(r.avgIntakeKcal, 2400);
  assert.ok(r.notes.some(n => /неполной записью/.test(n)));
});

test('guardrails: pace capped at 1%/week and intake floor', () => {
  const r = compute(realWeights, intakes(-55, -1, 2250), {today: '2026-10-24', goalWeightKg: 90, targetRateKgWeek: 3});
  assert.ok(r.targetRateKgWeek <= 1.07 + 1e-9);
  assert.ok(r.targetIntakeKcal >= Math.max(1500, r.expenditureKcal - 1000) - 10);
  assert.ok(r.notes.some(n => /ограничен/.test(n)));
});

test('goal reached → maintenance', () => {
  const weights = [0, -10, -20, -30].map(o => ({date: day(o), kg: 90.2}));
  const r = compute(weights, intakes(-35, -1, 2500), {today: day(0), goalWeightKg: 90});
  assert.equal(r.targetIntakeKcal, r.expenditureKcal);
  assert.equal(r.targetRateKgWeek, 0);
});

test('not enough data is reported, not guessed', () => {
  let r = compute(realWeights.slice(-2), intakes(-20, -1, 2200), {today: '2026-10-24'});
  assert.equal(r.status, 'insufficient'); assert.match(r.message, /взвешиваний/);
  r = compute(realWeights, intakes(-3, -1, 2200), {today: '2026-10-24'});
  assert.equal(r.status, 'insufficient'); assert.match(r.message, /питания/);
});

test('confidence reflects data quality', () => {
  const dense = [0, -7, -14, -21, -28, -35].map(o => ({date: day(o), kg: 106 - o * 0.05}));
  assert.equal(compute(dense, intakes(-40, -1, 2200), {today: day(0)}).confidence, 'high');
  const sparseFood = intakes(-40, -1, i => (i % 2 ? 2200 : 0));
  assert.notEqual(compute(dense, sparseFood, {today: day(0)}).confidence, 'high');
});
