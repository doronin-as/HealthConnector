from pathlib import Path

# Client preflight: refuse to send V3 partial payloads to a V2 deployment.
p = Path('app/src/main/java/ru/doronin/healthconnector/HealthSyncStreamer.kt')
s = p.read_text()
needle = '''        val requestedDates = (safeDays - 1 downTo 0).map { today.minusDays(it.toLong()) }

        onProgress("Проверяю изменения Health Connect…")'''
replacement = '''        val requestedDates = (safeDays - 1 downTo 0).map { today.minusDays(it.toLong()) }

        onProgress("Проверяю версию Google Sheets…")
        ServerCompatibility.requireV3(endpoint)
        onProgress("Проверяю изменения Health Connect…")'''
if needle not in s:
    raise SystemExit('HealthSyncStreamer preflight insertion point not found')
s = s.replace(needle, replacement, 1)
p.write_text(s)

# Apps Script V3 routing and read-only Diary policy.
p = Path('apps-script/Code.gs')
s = p.read_text()
if 'schemaVersion: 2' not in s:
    raise SystemExit('Code.gs doGet schema version marker not found')
s = s.replace('schemaVersion: 2', 'schemaVersion: 3', 1)

old_route = '''    if (payload.action === 'fatsecretCsv') {
      return json_(importFatSecretCsv_(spreadsheet, logSheet, payload));
    }

    validateHealthPayload_(payload);
    return json_(importHealthPayload_(spreadsheet, logSheet, payload));'''
new_route = '''    if (payload.action === 'fatsecretCsv') {
      return json_(importFatSecretCsv_(spreadsheet, logSheet, payload));
    }
    if (payload.action === 'healthChangesV3') {
      return json_(handleHealthChangesV3_(spreadsheet, logSheet, payload));
    }

    validateHealthPayload_(payload);
    if (payload.action === 'healthSyncV3') {
      return json_(importHealthPayloadV3_(spreadsheet, logSheet, payload));
    }
    return json_(importHealthPayload_(spreadsheet, logSheet, payload));'''
if old_route not in s:
    raise SystemExit('Code.gs doPost route block not found')
s = s.replace(old_route, new_route, 1)

# No integration code may write directly into Diary anymore.
for call in [
    '  syncDiary_(spreadsheet, payload.days || []);\n',
    '  syncDiaryFoodSnack_(spreadsheet, parsed.days);\n',
]:
    if call not in s:
        raise SystemExit(f'Expected Diary write call not found: {call.strip()}')
    s = s.replace(call, '', 1)

p.write_text(s)
