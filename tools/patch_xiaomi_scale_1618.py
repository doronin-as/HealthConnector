from pathlib import Path

# ---- Main activity: add a small settings card for the scale ----
p = Path('app/src/main/java/ru/doronin/healthconnector/StreamingMainActivity.kt')
s = p.read_text()
marker = '        setupVersionBadge()\n'
insert = '        setupVersionBadge()\n        setupMiScaleSettingsCard()\n'
if 'setupMiScaleSettingsCard()' not in s:
    if marker not in s:
        raise SystemExit('setupVersionBadge marker not found')
    s = s.replace(marker, insert, 1)

method_marker = '    private fun setupDiagnosticsControls() {'
method = '''    private fun setupMiScaleSettingsCard() {
        val container = binding.settingsPage.getChildAt(0) as? LinearLayout ?: return
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            strokeWidth = dp(1)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        content.addView(TextView(this).apply {
            text = "Умные весы Xiaomi"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val enabled = prefs.getBoolean(MiScaleScanner.PREF_ENABLED, false)
        val address = prefs.getString(MiScaleScanner.PREF_BOUND_ADDRESS, "").orEmpty()
        val lastWeight = prefs.getString(MiScaleUploadWorker.PREF_LAST_WEIGHT, "").orEmpty()
        content.addView(TextView(this).apply {
            text = buildString {
                append(if (enabled) "Автосчитывание включено" else "Автосчитывание выключено")
                if (address.isNotBlank()) append(" · ").append(address)
                if (lastWeight.isNotBlank()) append("\\nПоследний вес: ").append(lastWeight).append(" кг")
            }
            textSize = 13f
            alpha = 0.72f
            setPadding(0, dp(6), 0, dp(10))
        })
        content.addView(android.widget.Button(this).apply {
            text = "Настроить весы"
            setOnClickListener {
                startActivity(android.content.Intent(this@StreamingMainActivity, MiScaleSettingsActivity::class.java))
            }
        })
        card.addView(content)
        container.addView(card)
    }

'''
if method not in s:
    if method_marker not in s:
        raise SystemExit('diagnostics marker not found')
    s = s.replace(method_marker, method + method_marker, 1)
p.write_text(s)

# ---- Apps Script: dedicated body-composition table + weight update in HC_Дни ----
p = Path('apps-script/Code.gs')
s = p.read_text()

const_marker = "const MEASUREMENTS_SHEET = 'HC_Измерения';\n"
const_insert = """const MEASUREMENTS_SHEET = 'HC_Измерения';
const BODY_COMPOSITION_SHEET = 'HC_Состав_тела';
const BODY_COMPOSITION_HEADERS = [
  'Id', 'Timestamp', 'Date', 'DeviceAddress', 'Model', 'WeightKg', 'ImpedanceOhm',
  'BMI', 'BodyFatPct', 'FatMassKg', 'WaterPct', 'WaterMassKg', 'MuscleMassKg',
  'MusclePct', 'LeanBodyMassKg', 'BoneMassKg', 'VisceralFat', 'ProteinPct',
  'BmrKcal', 'SyncedAt'
];
"""
if 'BODY_COMPOSITION_SHEET' not in s:
    if const_marker not in s:
        raise SystemExit('measurement const marker not found')
    s = s.replace(const_marker, const_insert, 1)

setup_marker = '  ensureSheet_(spreadsheet, MEASUREMENTS_SHEET, MEASUREMENT_HEADERS);\n'
setup_insert = setup_marker + '  ensureSheet_(spreadsheet, BODY_COMPOSITION_SHEET, BODY_COMPOSITION_HEADERS);\n'
if 'ensureSheet_(spreadsheet, BODY_COMPOSITION_SHEET' not in s:
    if setup_marker not in s:
        raise SystemExit('setup measurement marker not found')
    s = s.replace(setup_marker, setup_insert, 1)

post_marker = "    if (payload.action === 'healthSyncPlanV3') {\n      return json_(getHealthSyncPlanV3_(spreadsheet, payload));\n    }\n"
post_insert = post_marker + "    if (payload.action === 'bodyCompositionV1') {\n      return json_(importBodyCompositionV1_(spreadsheet, logSheet, payload));\n    }\n"
if "payload.action === 'bodyCompositionV1'" not in s:
    if post_marker not in s:
        raise SystemExit('doPost plan marker not found')
    s = s.replace(post_marker, post_insert, 1)

function_marker = 'function validateHealthPayload_(payload) {'
body_function = '''function importBodyCompositionV1_(spreadsheet, logSheet, payload) {
  const m = payload && payload.measurement;
  if (!m || typeof m !== 'object') throw new Error('Нет measurement');
  const id = String(m.id || '');
  const date = String(m.date || '');
  const weight = Number(m.weightKg);
  if (!id) throw new Error('Нет id измерения весов');
  if (!/^\\d{4}-\\d{2}-\\d{2}$/.test(date)) throw new Error('Некорректная дата измерения весов');
  if (!Number.isFinite(weight) || weight < 5 || weight > 250) throw new Error('Некорректный вес');

  const syncedAt = new Date().toISOString();
  const sheet = ensureSheet_(spreadsheet, BODY_COMPOSITION_SHEET, BODY_COMPOSITION_HEADERS);
  const row = [[
    id,
    nullable_(m.measuredAt),
    date,
    sheetSafeExternalText_(m.deviceAddress || ''),
    sheetSafeExternalText_(m.model || 'XMTZC05HM'),
    weight,
    nullable_(m.impedanceOhm),
    nullable_(m.bmi),
    nullable_(m.bodyFatPercent),
    nullable_(m.fatMassKg),
    nullable_(m.waterPercent),
    nullable_(m.waterMassKg),
    nullable_(m.muscleMassKg),
    nullable_(m.musclePercent),
    nullable_(m.leanBodyMassKg),
    nullable_(m.boneMassKg),
    nullable_(m.visceralFat),
    nullable_(m.proteinPercent),
    nullable_(m.basalMetabolicRateKcal),
    syncedAt
  ]];
  upsertByKey_(sheet, row, 1);

  // Keep the existing Dashboard weight field current without marking the entire day complete.
  const daysSheet = ensureSheet_(spreadsheet, DAYS_SHEET, DAY_HEADERS_V3);
  upsertDayObjectsPartialV3_(daysSheet, [{
    date: date,
    weightKg: weight,
    sourcePackages: ['Xiaomi Scale XMTZC05HM']
  }], syncedAt, spreadsheet.getSpreadsheetTimeZone());

  // Also expose the raw weight in the generic measurements table.
  const measurementsSheet = ensureSheet_(spreadsheet, MEASUREMENTS_SHEET, MEASUREMENT_HEADERS);
  upsertByKey_(measurementsSheet, [[
    'scale-weight|' + id,
    nullable_(m.measuredAt),
    '', '', 'Weight', weight, 'kg',
    'xiaomi.scale.xmtzc05hm', 'Xiaomi Mi Body Composition Scale 2', syncedAt
  ]], 1);

  logSheet.appendRow([
    new Date(), sheetSafeExternalText_(m.deviceAddress || ''), date, date, 1, 0, 'OK',
    'Весы XMTZC05HM: ' + weight + ' кг' + (m.bodyFatPercent != null ? '; жир ' + m.bodyFatPercent + '%' : '')
  ]);

  return { ok: true, id: id, date: date, weightKg: weight, message: 'Измерение весов сохранено' };
}

'''
if 'function importBodyCompositionV1_' not in s:
    if function_marker not in s:
        raise SystemExit('validate marker not found')
    s = s.replace(function_marker, body_function + function_marker, 1)

p.write_text(s)
