from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label}: target not found")
    return text.replace(old, new, 1)


# ---- Apps Script ----
path = Path("apps-script/Code.gs")
text = path.read_text()

text = replace_once(
    text,
    """    if (payload.action === 'healthChangesV3') {
      return json_(handleHealthChangesV3_(spreadsheet, logSheet, payload));
    }

    validateHealthPayload_(payload);
""",
    """    if (payload.action === 'healthChangesV3') {
      return json_(handleHealthChangesV3_(spreadsheet, logSheet, payload));
    }
    if (payload.action === 'healthSyncPlanV3') {
      return json_(getHealthSyncPlanV3_(spreadsheet, payload));
    }

    validateHealthPayload_(payload);
""",
    "plan action routing",
)

text = replace_once(
    text,
    """const DAY_HEADERS_V3 = DAY_HEADERS.concat([
  'MainSleepHours', 'NapCount', 'NapMinutes'
]);
""",
    """const DAY_HEADERS_V3 = DAY_HEADERS.concat([
  'MainSleepHours', 'NapCount', 'NapMinutes', 'SyncComplete', 'CompletedAt'
]);
""",
    "sync state columns",
)

text = replace_once(
    text,
    "upsertDayObjectsPartialV3_(daysSheet, payload.days || [], syncedAt, spreadsheet.getSpreadsheetTimeZone());",
    "upsertDayObjectsPartialV3_(daysSheet, payload.days || [], syncedAt, spreadsheet.getSpreadsheetTimeZone(), payload.dayComplete);",
    "v3 day upsert call",
)
text = replace_once(
    text,
    "function upsertDayObjectsPartialV3_(sheet, days, syncedAt, tz) {",
    "function upsertDayObjectsPartialV3_(sheet, days, syncedAt, tz, dayComplete) {",
    "v3 day upsert signature",
)

text = replace_once(
    text,
    """    if (rowNumber) {
      row = sheet.getRange(rowNumber, 1, 1, headers.length).getValues()[0];
    } else {
      rowNumber = sheet.getLastRow() + 1;
      row = Array(headers.length).fill('');
      row[0] = Utilities.parseDate(`${dateKey} 00:00`, tz, 'yyyy-MM-dd HH:mm');
      existing.set(dateKey, rowNumber);
      if (rowNumber > 2) copyRowFormat_(sheet, rowNumber, headers.length);
    }
""",
    """    const rebuildingDay = typeof dayComplete === 'boolean';
    if (rowNumber && !rebuildingDay) {
      row = sheet.getRange(rowNumber, 1, 1, headers.length).getValues()[0];
    } else {
      if (!rowNumber) {
        rowNumber = sheet.getLastRow() + 1;
        existing.set(dateKey, rowNumber);
        if (rowNumber > 2) copyRowFormat_(sheet, rowNumber, headers.length);
      }
      // A checkpoint/final snapshot is a full rebuild of HC_Дни.
      // Clearing first prevents stale values from an older partial day.
      row = Array(headers.length).fill('');
      row[0] = Utilities.parseDate(`${dateKey} 00:00`, tz, 'yyyy-MM-dd HH:mm');
    }
""",
    "full day row rebuild",
)

text = replace_once(
    text,
    """    row[headerIndex.get('SyncedAt')] = syncedAt;
    sheet.getRange(rowNumber, 1, 1, headers.length).setValues([row]);
""",
    """    row[headerIndex.get('SyncedAt')] = syncedAt;
    if (typeof dayComplete === 'boolean') {
      row[headerIndex.get('SyncComplete')] = dayComplete;
      row[headerIndex.get('CompletedAt')] = dayComplete ? syncedAt : '';
    }
    sheet.getRange(rowNumber, 1, 1, headers.length).setValues([row]);
""",
    "sync completion marker",
)

anchor = "function handleHealthChangesV3_(spreadsheet, logSheet, payload) {"
if anchor not in text:
    raise SystemExit("plan helper anchor not found")
helper = r'''
function getHealthSyncPlanV3_(spreadsheet, payload) {
  const sheet = ensureSheet_(spreadsheet, DAYS_SHEET, DAY_HEADERS_V3);
  const tz = spreadsheet.getSpreadsheetTimeZone();
  const today = Utilities.formatDate(new Date(), tz, 'yyyy-MM-dd');
  const fallbackDays = Math.max(1, Math.min(30, Number(payload.fallbackDays || 7)));
  const fallbackStart = addDaysToDateKeyV3_(today, -(fallbackDays - 1), tz);

  if (sheet.getLastRow() < 2) {
    return {
      ok: true,
      schemaVersion: 3,
      startDate: fallbackStart,
      latestDate: '',
      today,
      reason: 'empty-table',
      message: `HC_Дни пуст: начинаю с ${fallbackStart}`
    };
  }

  const headers = sheet.getRange(1, 1, 1, DAY_HEADERS_V3.length).getDisplayValues()[0];
  const dateIndex = headers.indexOf('Date');
  const completeIndex = headers.indexOf('SyncComplete');
  const values = sheet.getRange(2, 1, sheet.getLastRow() - 1, DAY_HEADERS_V3.length).getValues();
  const rows = [];

  values.forEach(row => {
    const date = normalizeDateWithTz_(row[dateIndex], tz);
    if (!date || date > today) return;
    rows.push({
      date,
      complete: completeIndex >= 0 ? booleanCellV3_(row[completeIndex]) : null
    });
  });

  if (!rows.length) {
    return {
      ok: true,
      schemaVersion: 3,
      startDate: fallbackStart,
      latestDate: '',
      today,
      reason: 'no-valid-dates',
      message: `В HC_Дни нет корректных дат: начинаю с ${fallbackStart}`
    };
  }

  rows.sort((a, b) => a.date.localeCompare(b.date));
  const byDate = new Map();
  rows.forEach(row => byDate.set(row.date, row));
  const dates = Array.from(byDate.keys()).sort();
  const firstDate = dates[0];
  const latestDate = dates[dates.length - 1];

  // Interrupted syncs explicitly leave SyncComplete=false.
  let startDate = dates.find(date => byDate.get(date).complete === false) || '';
  let reason = startDate ? 'incomplete-day' : '';

  // A missing date inside existing history is also a repair point.
  if (!startDate) {
    let cursor = firstDate;
    while (cursor <= latestDate) {
      if (!byDate.has(cursor)) {
        startDate = cursor;
        reason = 'gap';
        break;
      }
      cursor = addDaysToDateKeyV3_(cursor, 1, tz);
    }
  }

  // Legacy rows do not have SyncComplete. Re-read the latest legacy day once.
  if (!startDate) {
    const latest = byDate.get(latestDate);
    if (latestDate === today) {
      startDate = today;
      reason = 'current-day';
    } else if (latest.complete === true) {
      startDate = addDaysToDateKeyV3_(latestDate, 1, tz);
      reason = 'after-last-complete';
    } else {
      startDate = latestDate;
      reason = 'legacy-latest-day';
    }
  }

  if (startDate > today) startDate = today;
  return {
    ok: true,
    schemaVersion: 3,
    startDate,
    latestDate,
    today,
    reason,
    message: `Dashboard: последняя дата ${latestDate}; синхронизация с ${startDate}`
  };
}

function booleanCellV3_(value) {
  if (value === true) return true;
  if (value === false) return false;
  const text = String(value == null ? '' : value).trim().toLowerCase();
  if (text === 'true' || text === '1' || text === 'да') return true;
  if (text === 'false' || text === '0' || text === 'нет') return false;
  return null;
}

function addDaysToDateKeyV3_(dateKey, days, tz) {
  const date = Utilities.parseDate(`${dateKey} 12:00`, tz, 'yyyy-MM-dd HH:mm');
  date.setDate(date.getDate() + Number(days || 0));
  return Utilities.formatDate(date, tz, 'yyyy-MM-dd');
}

'''
text = text.replace(anchor, helper + anchor, 1)
path.write_text(text)


# ---- Android client ----
path = Path("app/src/main/java/ru/doronin/healthconnector/HealthSyncStreamer.kt")
text = path.read_text()

text = replace_once(
    text,
    """        val safeDays = days.coerceIn(1, 30)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val requestedDates = (safeDays - 1 downTo 0).map { today.minusDays(it.toLong()) }

        onProgress(\"Проверяю версию Google Sheets…\")
        ServerCompatibility.requireV3(endpoint)
        onProgress(\"Проверяю изменения Health Connect…\")
""",
    """        val safeDays = days.coerceIn(1, 30)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val fallbackStart = today.minusDays((safeDays - 1).toLong())

        onProgress(\"Проверяю версию Google Sheets…\")
        ServerCompatibility.requireV3(endpoint)
        onProgress(\"Проверяю состояние Dashboard…\")
        val plan = runCatching { fetchSyncPlan(endpoint, token, safeDays) }
            .getOrElse { error ->
                SyncDiagnostics.server(
                    context,
                    \"healthSyncPlanV3 недоступен: ${error.message ?: error.javaClass.simpleName}; использую локальный диапазон\",
                    \"WARNING\"
                )
                SyncPlan(fallbackStart, \"fallback\", \"План Dashboard недоступен\")
            }
        val oldestReadable = today.minusDays(29)
        val tableStart = plan.startDate.coerceAtLeast(oldestReadable).coerceAtMost(today)
        // Background remains bounded; manual sync can repair an older incomplete day.
        val requestedStart = if (includeHistoricalChanges) tableStart else tableStart.coerceAtLeast(fallbackStart)
        val requestedDates = buildList {
            var cursor = requestedStart
            while (!cursor.isAfter(today)) {
                add(cursor)
                cursor = cursor.plusDays(1)
            }
        }
        onProgress(\"${plan.message} · к обработке ${requestedDates.size} дн.\")
        onProgress(\"Проверяю изменения Health Connect…\")
""",
    "client table plan",
)

text = replace_once(
    text,
    """        val oldestReadable = today.minusDays(29)
        val dates = linkedSetOf<LocalDate>().apply {
""",
    """        val dates = linkedSetOf<LocalDate>().apply {
""",
    "remove duplicate oldestReadable",
)

text = replace_once(
    text,
    """                measurements = JSONArray(),
                sources = sources
            )
            onProgress(\"[$date] ✓ Ранняя сводка сохранена · теперь сырые измерения\")
""",
    """                measurements = JSONArray(),
                sources = sources,
                dayComplete = false
            )
            onProgress(\"[$date] ✓ Ранняя сводка сохранена · день помечен незавершённым\")
""",
    "checkpoint incomplete marker",
)

text = replace_once(
    text,
    """            measurements = JSONArray(),
            sources = sources
        )
        onProgress(\"[$date] ✓ День полностью синхронизирован · ${batcher.totalCount} изм. · ${workoutRecords.size} трен.\")
""",
    """            measurements = JSONArray(),
            sources = sources,
            dayComplete = true
        )
        onProgress(\"[$date] ✓ День полностью синхронизирован · ${batcher.totalCount} изм. · ${workoutRecords.size} трен.\")
""",
    "final complete marker",
)

anchor = "    private suspend fun postDeletedRecordIds(\n"
if anchor not in text:
    raise SystemExit("client plan helper anchor not found")
helper = '''    private suspend fun fetchSyncPlan(
        endpoint: String,
        token: String,
        fallbackDays: Int
    ): SyncPlan {
        val body = JSONObject().apply {
            put("token", token)
            put("action", "healthSyncPlanV3")
            put("schemaVersion", 3)
            put("fallbackDays", fallbackDays)
        }
        val response = postBody(endpoint, body)
            ?: error("Dashboard не вернул план синхронизации")
        val startDate = runCatching { LocalDate.parse(response.optString("startDate")) }
            .getOrElse { error("Dashboard вернул некорректную дату старта") }
        return SyncPlan(
            startDate = startDate,
            reason = response.optString("reason", "table"),
            message = response.optString("message", "Dashboard: синхронизация с $startDate")
        )
    }

'''
text = text.replace(anchor, helper + anchor, 1)

text = replace_once(
    text,
    """        measurements: JSONArray,
        sources: Set<String>
    ): JSONObject? {
""",
    """        measurements: JSONArray,
        sources: Set<String>,
        dayComplete: Boolean? = null
    ): JSONObject? {
""",
    "post payload signature",
)

text = replace_once(
    text,
    """            put(\"measurements\", measurements)
            put(\"sources\", JSONArray().apply {
""",
    """            put(\"measurements\", measurements)
            if (dayComplete != null) put(\"dayComplete\", dayComplete)
            put(\"sources\", JSONArray().apply {
""",
    "post payload completion field",
)

data_anchor = """    data class SyncResult(
        val days: Int,
        val workouts: Int,
        val measurements: Int,
        val sources: Int
    )
"""
text = replace_once(
    text,
    data_anchor,
    data_anchor
    + """

    private data class SyncPlan(
        val startDate: LocalDate,
        val reason: String,
        val message: String
    )
""",
    "sync plan data class",
)
path.write_text(text)


# ---- Version ----
path = Path("app/build.gradle.kts")
text = path.read_text()
text = replace_once(text, "versionCode = 27", "versionCode = 28", "version code")
text = replace_once(text, 'versionName = "1.6.13"', 'versionName = "1.6.14"', "version name")
path.write_text(text)


# ---- CI contract checks ----
path = Path(".github/workflows/android.yml")
text = path.read_text()
needle = "          grep -F \"payload.action === 'healthChangesV3'\" apps-script/Code.gs\n"
text = replace_once(
    text,
    needle,
    needle
    + "          grep -F \"payload.action === 'healthSyncPlanV3'\" apps-script/Code.gs\n"
    + "          grep -F \"'SyncComplete', 'CompletedAt'\" apps-script/Code.gs\n"
    + "          grep -F 'dayComplete = true' app/src/main/java/ru/doronin/healthconnector/HealthSyncStreamer.kt\n",
    "ci contract checks",
)
path.write_text(text)
