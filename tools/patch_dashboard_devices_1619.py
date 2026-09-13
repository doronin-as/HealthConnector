from pathlib import Path

# ---------------- StreamingMainActivity ----------------
p = Path('app/src/main/java/ru/doronin/healthconnector/StreamingMainActivity.kt')
s = p.read_text()

fields_marker = '    private var backgroundSwitch: SwitchMaterial? = null\n'
fields_insert = '''    private var backgroundSwitch: SwitchMaterial? = null
    private var dashboardStatusText: TextView? = null
    private var dashboardSummaryText: TextView? = null
    private var dashboardDevicesText: TextView? = null
    private var dashboardProfileText: TextView? = null
    private var dashboardGoogleUrl: String? = null
'''
if 'dashboardSummaryText' not in s:
    if fields_marker not in s:
        raise SystemExit('MainActivity fields marker not found')
    s = s.replace(fields_marker, fields_insert, 1)

content_marker = '        setContentView(binding.root)\n\n        setupVersionBadge()\n'
content_insert = '        setContentView(binding.root)\n\n        setupDashboardPage()\n        setupVersionBadge()\n'
if 'setupDashboardPage()\n        setupVersionBadge()' not in s:
    if content_marker not in s:
        raise SystemExit('MainActivity setContentView marker not found')
    s = s.replace(content_marker, content_insert, 1)

observer_marker = '        setupManualSyncObserver()\n    }\n'
observer_insert = '        setupManualSyncObserver()\n        refreshDashboardSnapshot()\n    }\n'
if 'setupManualSyncObserver()\n        refreshDashboardSnapshot()' not in s:
    if observer_marker not in s:
        raise SystemExit('MainActivity observer marker not found')
    s = s.replace(observer_marker, observer_insert, 1)

success_marker = '''                    androidx.work.WorkInfo.State.SUCCEEDED -> {
                        val days = work.outputData.getInt(ManualSyncWorker.KEY_DAYS, -1)
                        val measurements = work.outputData.getInt(ManualSyncWorker.KEY_MEASUREMENTS, -1)
                        if (days >= 0) binding.status.text = "Синхронизация завершена: дней $days, измерений $measurements"
                    }
'''
success_insert = '''                    androidx.work.WorkInfo.State.SUCCEEDED -> {
                        val days = work.outputData.getInt(ManualSyncWorker.KEY_DAYS, -1)
                        val measurements = work.outputData.getInt(ManualSyncWorker.KEY_MEASUREMENTS, -1)
                        if (days >= 0) binding.status.text = "Синхронизация завершена: дней $days, измерений $measurements"
                        refreshDashboardSnapshot(showStatus = false)
                    }
'''
if 'refreshDashboardSnapshot(showStatus = false)' not in s:
    if success_marker not in s:
        raise SystemExit('MainActivity success marker not found')
    s = s.replace(success_marker, success_insert, 1)

resume_marker = '''    override fun onResume() {
        super.onResume()
        refreshBackgroundInfo()
    }
'''
resume_insert = '''    override fun onResume() {
        super.onResume()
        refreshBackgroundInfo()
        refreshDashboardSnapshot(showStatus = false)
    }
'''
if resume_insert not in s:
    if resume_marker not in s:
        raise SystemExit('MainActivity onResume marker not found')
    s = s.replace(resume_marker, resume_insert, 1)

scale_button_marker = '''            setOnClickListener {
                startActivity(android.content.Intent(this@StreamingMainActivity, MiScaleSettingsActivity::class.java))
            }
'''
scale_button_insert = '''            setOnClickListener {
                saveSettingsFromForm()
                startActivity(android.content.Intent(this@StreamingMainActivity, MiScaleSettingsActivity::class.java))
            }
'''
if 'saveSettingsFromForm()\n                startActivity(android.content.Intent(this@StreamingMainActivity, MiScaleSettingsActivity::class.java))' not in s:
    if scale_button_marker not in s:
        raise SystemExit('MainActivity scale button marker not found')
    s = s.replace(scale_button_marker, scale_button_insert, 1)

import_success_marker = '''        }.onSuccess {
            binding.status.text = "Настройки восстановлены"
            refreshBackgroundInfo()
        }.onFailure {
'''
import_success_insert = '''        }.onSuccess {
            binding.status.text = "Настройки восстановлены"
            refreshBackgroundInfo()
            refreshDashboardSnapshot()
        }.onFailure {
'''
if 'binding.status.text = "Настройки восстановлены"\n            refreshBackgroundInfo()\n            refreshDashboardSnapshot()' not in s:
    if import_success_marker not in s:
        raise SystemExit('MainActivity import success marker not found')
    s = s.replace(import_success_marker, import_success_insert, 1)

method_marker = '    private fun setupMiScaleSettingsCard() {'
dashboard_methods = r'''    private fun setupDashboardPage() {
        val frame = binding.syncPage.parent as? android.widget.FrameLayout ?: return
        if (findViewById<android.view.View?>(R.id.dashboardPage) != null) return

        val scroll = android.widget.ScrollView(this).apply {
            id = R.id.dashboardPage
            isFillViewport = true
            visibility = android.view.View.GONE
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        scroll.addView(container)

        container.addView(TextView(this).apply {
            text = "Дашборд"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        container.addView(TextView(this).apply {
            text = "Последние данные из Google Dashboard, профиль и активные источники."
            textSize = 14f
            alpha = 0.72f
            setPadding(0, dp(4), 0, dp(12))
        })

        dashboardStatusText = TextView(this).apply {
            text = "Загружаю Dashboard…"
            textSize = 13f
            alpha = 0.78f
            setPadding(0, 0, 0, dp(8))
        }.also(container::addView)

        container.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = "Обновить Dashboard"
            setOnClickListener {
                saveSettingsFromForm()
                refreshDashboardSnapshot()
            }
        })

        dashboardSummaryText = TextView(this).apply {
            text = "Нет данных"
            textSize = 15f
            setLineSpacing(0f, 1.18f)
        }
        container.addView(makeDashboardCard("Последние показатели", dashboardSummaryText!!))

        dashboardDevicesText = TextView(this).apply {
            text = "Ищу устройства и источники…"
            textSize = 14f
            setLineSpacing(0f, 1.18f)
        }
        container.addView(makeDashboardCard("Устройства и источники", dashboardDevicesText!!))

        dashboardProfileText = TextView(this).apply {
            text = "Профиль ещё не загружен"
            textSize = 14f
            setLineSpacing(0f, 1.18f)
        }
        container.addView(makeDashboardCard("Автоподхват профиля", dashboardProfileText!!))

        container.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = "Открыть Google Dashboard"
            setOnClickListener {
                val url = dashboardGoogleUrl
                if (url.isNullOrBlank()) {
                    refreshDashboardSnapshot()
                } else {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                }
            }
        })

        frame.addView(scroll, 0)
    }

    private fun makeDashboardCard(title: String, body: TextView): MaterialCardView =
        MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            strokeWidth = dp(1)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            addView(LinearLayout(this@StreamingMainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(18))
                addView(TextView(this@StreamingMainActivity).apply {
                    text = title
                    textSize = 18f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                body.setPadding(0, dp(8), 0, 0)
                addView(body)
            })
        }

    private fun refreshDashboardSnapshot(showStatus: Boolean = true) {
        if (!::binding.isInitialized || dashboardStatusText == null) return
        val endpoint = binding.endpoint.text?.toString()?.trim().orEmpty().ifBlank {
            prefs.getString("endpoint", "").orEmpty().trim()
        }
        val token = secureTokenStore.getToken().trim()
        if (endpoint.isBlank() || token.isBlank()) {
            dashboardStatusText?.text = "Для Dashboard сначала укажи URL Apps Script и токен в настройках."
            return
        }
        if (showStatus) dashboardStatusText?.text = "Обновляю данные из Google Dashboard…"

        lifecycleScope.launch {
            runCatching { DashboardApi.fetch(endpoint, token) }
                .onSuccess { snapshot ->
                    val applied = DashboardApi.applyScaleProfile(prefs, snapshot.profile)
                    dashboardGoogleUrl = snapshot.dashboardUrl
                    dashboardStatusText?.text = buildString {
                        append("Dashboard подключён")
                        snapshot.fetchedAt?.let { append(" · ").append(it.take(19).replace('T', ' ')) }
                        if (applied.isNotEmpty()) append("\nПрофиль весов обновлён автоматически: ").append(applied.joinToString())
                    }
                    renderDashboardSummary(snapshot.summary)
                    renderDashboardDevices(snapshot.devices)
                    renderDashboardProfile(snapshot.profile)
                }
                .onFailure { error ->
                    dashboardStatusText?.text = "Ошибка Dashboard: ${error.message ?: error.javaClass.simpleName}"
                }
        }
    }

    private fun renderDashboardSummary(summary: DashboardApi.Summary) {
        fun number(value: Double?, digits: Int = 1): String = when {
            value == null -> "—"
            digits == 0 -> String.format(java.util.Locale.getDefault(), "%.0f", value)
            else -> String.format(java.util.Locale.getDefault(), "%.1f", value)
        }
        val sleep = summary.mainSleepHours ?: summary.sleepHours
        dashboardSummaryText?.text = buildString {
            append("Дата: ").append(summary.date ?: "—")
            append("\nВес: ").append(number(summary.weightKg)).append(if (summary.weightKg != null) " кг" else "")
            append("\nШаги: ").append(summary.steps?.toString() ?: "—")
            append("\nСон: ").append(number(sleep)).append(if (sleep != null) " ч" else "")
            append("\nПульс покоя: ").append(number(summary.restingHeartRate, 0)).append(if (summary.restingHeartRate != null) " уд/мин" else "")
            append("\nСредний пульс: ").append(number(summary.averageHeartRate, 0)).append(if (summary.averageHeartRate != null) " уд/мин" else "")
            append("\nSpO₂: ").append(number(summary.averageSpO2)).append(if (summary.averageSpO2 != null) "%" else "")
            append("\nАктивные калории: ").append(number(summary.activeCaloriesKcal, 0)).append(if (summary.activeCaloriesKcal != null) " ккал" else "")
            append("\nТренировки: ").append(summary.workoutCount?.toString() ?: "—")
        }
    }

    private fun renderDashboardDevices(remote: List<DashboardApi.Device>) {
        val lines = mutableListOf<String>()
        val scaleEnabled = prefs.getBoolean(MiScaleScanner.PREF_ENABLED, false)
        val scaleAddress = prefs.getString(MiScaleScanner.PREF_BOUND_ADDRESS, "").orEmpty()
        val scaleWeight = prefs.getString(MiScaleUploadWorker.PREF_LAST_WEIGHT, "").orEmpty()
        lines += buildString {
            append("Xiaomi Mi Body Composition Scale 2 · XMTZC05HM")
            append(if (scaleEnabled) " · автосчитывание включено" else " · выключено")
            if (scaleAddress.isNotBlank()) append("\n  Bluetooth: ").append(scaleAddress)
            if (scaleWeight.isNotBlank()) append(" · последний вес ").append(scaleWeight).append(" кг")
        }
        lines += if (HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE) {
            "Health Connect · доступен"
        } else {
            "Health Connect · недоступен"
        }
        remote.distinctBy { listOf(it.type, it.name, it.packageName, it.address) }.forEach { device ->
            lines += buildString {
                append(device.name)
                if (device.type.isNotBlank()) append(" · ").append(device.type)
                device.packageName?.let { append("\n  ").append(it) }
                device.address?.let { append(" · ").append(it) }
                device.lastSeen?.let { append("\n  Последние данные: ").append(it) }
            }
        }
        dashboardDevicesText?.text = lines.distinct().joinToString("\n\n")
    }

    private fun renderDashboardProfile(profile: DashboardApi.Profile) {
        val localSex = prefs.getString(MiScaleProfile.PREF_SEX, "").orEmpty()
        dashboardProfileText?.text = buildString {
            append("Лист «Профиль»: ")
            append(if (profile.heightCm != null) "рост ✓" else "рост —")
            append(" · ")
            append(if (profile.birthDate != null) "дата рождения ✓" else "дата рождения —")
            append(" · ")
            append(if (profile.sex != null) "пол ✓" else "пол — не найден")
            append("\nРост и дата рождения автоматически передаются модулю весов при каждом обновлении Dashboard.")
            if (profile.sex == null && localSex.isNotBlank()) {
                append(" Для пола используется сохранённое в приложении значение.")
            } else if (profile.sex == null) {
                append(" Пол нужно один раз выбрать в настройках весов или добавить строку «Пол» в лист «Профиль».")
            }
        }
    }

'''
if 'private fun setupDashboardPage()' not in s:
    if method_marker not in s:
        raise SystemExit('MainActivity scale settings method marker not found')
    s = s.replace(method_marker, dashboard_methods + method_marker, 1)

p.write_text(s)

# ---------------- Apps Script ----------------
p = Path('apps-script/Code.gs')
s = p.read_text()
route_marker = "    if (payload.action === 'healthSyncPlanV3') {\n      return json_(getHealthSyncPlanV3_(spreadsheet, payload));\n    }\n"
route_insert = route_marker + "    if (payload.action === 'dashboardSnapshotV1') {\n      return json_(getDashboardSnapshotV1_(spreadsheet));\n    }\n"
if "payload.action === 'dashboardSnapshotV1'" not in s:
    if route_marker not in s:
        raise SystemExit('Apps Script dashboard route marker not found')
    s = s.replace(route_marker, route_insert, 1)

function_marker = 'function importBodyCompositionV1_(spreadsheet, logSheet, payload) {'
dashboard_functions = r'''function getDashboardSnapshotV1_(spreadsheet) {
  const tz = spreadsheet.getSpreadsheetTimeZone();
  return {
    ok: true,
    schemaVersion: 1,
    fetchedAt: new Date().toISOString(),
    dashboardUrl: spreadsheet.getUrl(),
    profile: getDashboardProfileV1_(spreadsheet, tz),
    summary: getDashboardLatestDayV1_(spreadsheet, tz),
    devices: getDashboardDevicesV1_(spreadsheet, tz)
  };
}

function getDashboardProfileV1_(spreadsheet, tz) {
  const sheet = spreadsheet.getSheetByName('Профиль');
  if (!sheet || sheet.getLastRow() < 1) {
    return { heightCm: null, birthDate: null, sex: null, foundFields: [] };
  }
  const rows = sheet.getRange(1, 1, Math.min(sheet.getLastRow(), 200), 2).getValues();
  const map = new Map();
  rows.forEach(row => {
    const key = String(row[0] == null ? '' : row[0]).trim().toLowerCase();
    if (key) map.set(key, row[1]);
  });
  function pick(keys) {
    for (const key of keys) {
      if (map.has(key.toLowerCase())) return map.get(key.toLowerCase());
    }
    return null;
  }
  const height = dashboardNumberV1_(pick(['Рост, см', 'Рост', 'Height, cm', 'Height']));
  const birthDate = dashboardDateV1_(pick(['Дата рождения', 'День рождения', 'Birth date', 'Birthday']), tz);
  const rawSex = String(pick(['Пол', 'Sex', 'Gender']) || '').trim().toLowerCase();
  let sex = null;
  if (['мужской', 'муж', 'м', 'male', 'man'].includes(rawSex)) sex = 'male';
  if (['женский', 'жен', 'ж', 'female', 'woman'].includes(rawSex)) sex = 'female';
  const foundFields = [];
  if (height != null) foundFields.push('heightCm');
  if (birthDate) foundFields.push('birthDate');
  if (sex) foundFields.push('sex');
  return { heightCm: height, birthDate: birthDate, sex: sex, foundFields: foundFields };
}

function getDashboardLatestDayV1_(spreadsheet, tz) {
  const sheet = ensureSheet_(spreadsheet, DAYS_SHEET, DAY_HEADERS_V3);
  if (sheet.getLastRow() < 2) return {};
  const width = Math.min(sheet.getLastColumn(), DAY_HEADERS_V3.length);
  const headers = sheet.getRange(1, 1, 1, width).getDisplayValues()[0];
  const count = Math.min(sheet.getLastRow() - 1, 120);
  const start = sheet.getLastRow() - count + 1;
  const rows = sheet.getRange(start, 1, count, width).getValues();
  const dateIndex = headers.indexOf('Date');
  let best = null;
  let bestDate = '';
  rows.forEach(row => {
    const date = dateIndex >= 0 ? normalizeDateWithTz_(row[dateIndex], tz) : '';
    if (date && date >= bestDate) {
      bestDate = date;
      best = row;
    }
  });
  if (!best) return {};
  function value(name) {
    const index = headers.indexOf(name);
    if (index < 0) return null;
    const raw = best[index];
    return raw === '' || raw === null || raw === undefined ? null : raw;
  }
  return {
    date: bestDate,
    steps: dashboardNumberV1_(value('Steps')),
    weightKg: dashboardNumberV1_(value('WeightKg')),
    sleepHours: dashboardNumberV1_(value('SleepHours')),
    mainSleepHours: dashboardNumberV1_(value('MainSleepHours')),
    restingHeartRate: dashboardNumberV1_(value('RestingHeartRate')),
    averageHeartRate: dashboardNumberV1_(value('AvgHeartRate')),
    averageSpO2: dashboardNumberV1_(value('AvgSpO2')),
    activeCaloriesKcal: dashboardNumberV1_(value('ActiveCaloriesKcal')),
    workoutCount: dashboardNumberV1_(value('WorkoutCount')),
    syncedAt: dashboardIsoV1_(value('SyncedAt'))
  };
}

function getDashboardDevicesV1_(spreadsheet, tz) {
  const devices = new Map();
  dashboardCollectSourcesV1_(spreadsheet.getSheetByName(MEASUREMENTS_SHEET), 'Измерения', devices, 2500);
  dashboardCollectSourcesV1_(spreadsheet.getSheetByName(SLEEP_SHEET), 'Сон', devices, 800);
  dashboardCollectSourcesV1_(spreadsheet.getSheetByName(WORKOUTS_SHEET), 'Тренировки', devices, 800);

  const scale = spreadsheet.getSheetByName(BODY_COMPOSITION_SHEET);
  if (scale && scale.getLastRow() >= 2) {
    const width = Math.min(scale.getLastColumn(), BODY_COMPOSITION_HEADERS.length);
    const headers = scale.getRange(1, 1, 1, width).getDisplayValues()[0];
    const count = Math.min(scale.getLastRow() - 1, 100);
    const rows = scale.getRange(scale.getLastRow() - count + 1, 1, count, width).getValues();
    const addressIndex = headers.indexOf('DeviceAddress');
    const modelIndex = headers.indexOf('Model');
    const seenIndex = headers.indexOf('SyncedAt');
    for (let i = rows.length - 1; i >= 0; i--) {
      const row = rows[i];
      const address = addressIndex >= 0 ? String(row[addressIndex] || '').trim() : '';
      const model = modelIndex >= 0 ? String(row[modelIndex] || '').trim() : '';
      const key = 'scale|' + (address || model || 'XMTZC05HM');
      if (!devices.has(key)) {
        devices.set(key, {
          type: 'Умные весы',
          name: model === 'XMTZC05HM' || !model ? 'Xiaomi Mi Body Composition Scale 2' : model,
          packageName: null,
          address: address || null,
          lastSeen: seenIndex >= 0 ? dashboardIsoV1_(row[seenIndex]) : null
        });
      }
    }
  }

  // If raw tables are still empty, expose source packages from the latest daily row.
  if (devices.size === 0) {
    const days = spreadsheet.getSheetByName(DAYS_SHEET);
    if (days && days.getLastRow() >= 2) {
      const headers = days.getRange(1, 1, 1, days.getLastColumn()).getDisplayValues()[0];
      const sourceIndex = headers.indexOf('Sources');
      if (sourceIndex >= 0) {
        const text = String(days.getRange(days.getLastRow(), sourceIndex + 1).getDisplayValue() || '');
        text.split(',').map(x => x.trim()).filter(Boolean).forEach(pkg => {
          devices.set('source|' + pkg, { type: 'Health Connect источник', name: pkg, packageName: pkg, address: null, lastSeen: null });
        });
      }
    }
  }
  return Array.from(devices.values()).slice(0, 16);
}

function dashboardCollectSourcesV1_(sheet, type, devices, maxRows) {
  if (!sheet || sheet.getLastRow() < 2) return;
  const width = sheet.getLastColumn();
  const headers = sheet.getRange(1, 1, 1, width).getDisplayValues()[0];
  const packageIndex = headers.indexOf('SourcePackage');
  const nameIndex = headers.indexOf('SourceName');
  const timeIndexes = ['SyncedAt', 'Time', 'Start', 'End'].map(name => headers.indexOf(name)).filter(index => index >= 0);
  if (packageIndex < 0 && nameIndex < 0) return;
  const count = Math.min(sheet.getLastRow() - 1, maxRows);
  const rows = sheet.getRange(sheet.getLastRow() - count + 1, 1, count, width).getValues();
  for (let i = rows.length - 1; i >= 0; i--) {
    const row = rows[i];
    const pkg = packageIndex >= 0 ? String(row[packageIndex] || '').trim() : '';
    const name = nameIndex >= 0 ? String(row[nameIndex] || '').trim() : '';
    if (!pkg && !name) continue;
    const key = 'hc|' + pkg + '|' + name;
    if (devices.has(key)) continue;
    let lastSeen = null;
    for (const index of timeIndexes) {
      if (row[index]) { lastSeen = dashboardIsoV1_(row[index]); break; }
    }
    devices.set(key, {
      type: 'Health Connect · ' + type,
      name: name || pkg,
      packageName: pkg || null,
      address: null,
      lastSeen: lastSeen
    });
    if (devices.size >= 16) return;
  }
}

function dashboardNumberV1_(value) {
  if (value === null || value === undefined || value === '') return null;
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  const number = Number(String(value).replace(/\s/g, '').replace(',', '.'));
  return Number.isFinite(number) ? number : null;
}

function dashboardDateV1_(value, tz) {
  if (value instanceof Date && !isNaN(value)) return Utilities.formatDate(value, tz, 'yyyy-MM-dd');
  const text = String(value == null ? '' : value).trim();
  if (/^\d{4}-\d{2}-\d{2}$/.test(text)) return text;
  let match = text.match(/^(\d{1,2})[.\/-](\d{1,2})[.\/-](\d{4})$/);
  if (match) return `${match[3]}-${match[2].padStart(2, '0')}-${match[1].padStart(2, '0')}`;
  return null;
}

function dashboardIsoV1_(value) {
  if (value instanceof Date && !isNaN(value)) return value.toISOString();
  const text = String(value == null ? '' : value).trim();
  return text || null;
}

'''
if 'function getDashboardSnapshotV1_' not in s:
    if function_marker not in s:
        raise SystemExit('Apps Script body composition function marker not found')
    s = s.replace(function_marker, dashboard_functions + function_marker, 1)

p.write_text(s)
