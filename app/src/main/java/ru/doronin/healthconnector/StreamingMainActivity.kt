package ru.doronin.healthconnector

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.doronin.healthconnector.databinding.ActivityMainBinding
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.time.Instant
import java.util.Date

class StreamingMainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val client by lazy { HealthConnectClient.getOrCreate(this) }
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val secureTokenStore by lazy { SecureTokenStore(this) }

    private var backgroundInfo: TextView? = null
    private var backgroundSwitch: SwitchMaterial? = null
    private var dashboardStatusText: TextView? = null
    private var dashboardSummaryText: TextView? = null
    private var dashboardDevicesText: TextView? = null
    private var dashboardProfileText: TextView? = null
    private var dashboardGoogleUrl: String? = null

    private val recordPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(RespiratoryRateRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
        HealthPermission.getReadPermission(SkinTemperatureRecord::class),
        HealthPermission.getReadPermission(ElevationGainedRecord::class),
        HealthPermission.getReadPermission(FloorsClimbedRecord::class),
        HealthPermission.getReadPermission(SpeedRecord::class),
        HealthPermission.getReadPermission(StepsCadenceRecord::class),
        HealthPermission.getReadPermission(CyclingPedalingCadenceRecord::class),
        HealthPermission.getReadPermission(PowerRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class)
    )

    private fun backgroundReadAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE &&
            client.features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    private fun requestedPermissions(): Set<String> = buildSet {
        addAll(recordPermissions)
        if (backgroundReadAvailable()) {
            add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        val requested = requestedPermissions()
        binding.status.text = if (granted.containsAll(requested)) {
            "Все доступные разрешения Health Connect выданы"
        } else {
            val recordsGranted = recordPermissions.count { it in granted }
            val backgroundGranted = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted
            "Выдано разрешений: $recordsGranted/${recordPermissions.size}. " +
                if (backgroundReadAvailable() && !backgroundGranted) {
                    "Фоновое чтение не разрешено."
                } else {
                    "Недоступные показатели останутся пустыми."
                }
        }
        BackgroundSyncScheduler.apply(this)
        refreshBackgroundInfo()
    }

    private val exportConfigLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) exportConfig(uri) }

    private val importConfigLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importConfig(uri) }

    private val importCsvLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importFatSecretCsv(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDashboardPage()
        setupVersionBadge()
        setupMiScaleSettingsCard()

        binding.endpoint.setText(prefs.getString("endpoint", ""))
        binding.token.setText(secureTokenStore.getToken())
        binding.days.setText(prefs.getInt("days", 7).toString())

        setupBackgroundSyncControls()
        setupDiagnosticsControls()
        BackgroundSyncScheduler.apply(this)

        binding.exportConfig.setOnClickListener {
            saveSettingsFromForm()
            exportConfigLauncher.launch("healthconnector-config.json")
        }
        binding.importConfig.setOnClickListener {
            importConfigLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
        }
        binding.importCsv.setOnClickListener {
            saveSettingsFromForm()
            importCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv"))
        }
        binding.permissions.setOnClickListener { permissionLauncher.launch(requestedPermissions()) }
        binding.sync.setOnClickListener {
            val settings = saveSettingsFromForm()
            if (settings.endpoint.isBlank() || settings.token.isBlank()) {
                binding.status.text = "Укажи URL Apps Script и токен"
            } else {
                // Manual sync must read Health Connect while the app is in the
                // foreground. WorkManager is reserved for periodic background
                // syncs that have explicit Background Read capability/grant.
                lifecycleScope.launch { synchronize(settings) }
            }
        }
        setupManualSyncObserver()
        refreshDashboardSnapshot()
    }

    private fun setupManualSyncObserver() {
        androidx.work.WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(ManualSyncScheduler.UNIQUE_WORK_NAME)
            .observe(this) { works ->
                val work = works.lastOrNull() ?: return@observe
                val progress = work.progress.getString(ManualSyncWorker.KEY_PROGRESS).orEmpty()
                when (work.state) {
                    androidx.work.WorkInfo.State.ENQUEUED, androidx.work.WorkInfo.State.BLOCKED ->
                        binding.status.text = "Синхронизация ожидает запуска · можно свернуть приложение"
                    androidx.work.WorkInfo.State.RUNNING ->
                        binding.status.text = if (progress.isBlank()) "Синхронизация выполняется в фоне · можно свернуть приложение" else "$progress\n● Фоновая работа активна"
                    androidx.work.WorkInfo.State.SUCCEEDED -> {
                        val days = work.outputData.getInt(ManualSyncWorker.KEY_DAYS, -1)
                        val measurements = work.outputData.getInt(ManualSyncWorker.KEY_MEASUREMENTS, -1)
                        if (days >= 0) binding.status.text = "Синхронизация завершена: дней $days, измерений $measurements"
                        refreshDashboardSnapshot(showStatus = false)
                    }
                    androidx.work.WorkInfo.State.FAILED -> {
                        val error = work.outputData.getString(ManualSyncWorker.KEY_ERROR) ?: "неизвестная ошибка"
                        binding.status.text = "Ошибка синхронизации: $error"
                    }
                    androidx.work.WorkInfo.State.CANCELLED -> binding.status.text = "Синхронизация отменена системой"
                }
            }
    }

    private fun setupVersionBadge() {
        val header = binding.root.getChildAt(0) as? LinearLayout ?: return
        val isTestBuild = packageName != "ru.doronin.healthconnector.stable"
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        val badgeText = buildString {
            append("v")
            append(versionName)
            if (isTestBuild) append(" · TEST")
        }
        val badge = TextView(this).apply {
            text = badgeText
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorOnSecondaryContainer
                )
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        this@StreamingMainActivity,
                        com.google.android.material.R.attr.colorSecondaryContainer,
                        0
                    )
                )
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            contentDescription = "Версия $badgeText"
        }
        header.addView(
            badge,
            1,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(2)
            }
        )
    }

    override fun onResume() {
        super.onResume()
        refreshBackgroundInfo()
        refreshDashboardSnapshot(showStatus = false)
    }

    private fun setupDashboardPage() {
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

    private fun setupMiScaleSettingsCard() {
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
                if (lastWeight.isNotBlank()) append("\nПоследний вес: ").append(lastWeight).append(" кг")
            }
            textSize = 13f
            alpha = 0.72f
            setPadding(0, dp(6), 0, dp(10))
        })
        content.addView(android.widget.Button(this).apply {
            text = "Настроить весы"
            setOnClickListener {
                saveSettingsFromForm()
                startActivity(android.content.Intent(this@StreamingMainActivity, MiScaleSettingsActivity::class.java))
            }
        })
        card.addView(content)
        container.addView(card)
    }

    private fun setupDiagnosticsControls() {
        val container = binding.syncPage.getChildAt(0) as? LinearLayout ?: return
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
            setPadding(dp(20), dp(16), dp(20), dp(10))
        }
        content.addView(TextView(this).apply {
            text = "Диагностика синхронизации"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "Нажми на раздел: внутри видны этап, время, повторные попытки и точное место ошибки."
            textSize = 13f
            alpha = 0.70f
            setPadding(0, dp(6), 0, dp(4))
        })
        addDiagnosticDropdown(this, content, "Ручная синхронизация") {
            SyncDiagnostics.render(this, "manual")
        }
        addDiagnosticDropdown(this, content, "Фоновая синхронизация") {
            SyncDiagnostics.render(this, "background")
        }
        addDiagnosticDropdown(this, content, "Google Sheets и сеть") {
            SyncDiagnostics.render(this, "server")
        }
        card.addView(content)
        container.addView(card)
    }

    private fun setupBackgroundSyncControls() {
        val settingsScroll = binding.settingsPage
        val container = settingsScroll.getChildAt(0) as? LinearLayout ?: return

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
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        content.addView(TextView(this).apply {
            text = "Фоновая синхронизация"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        content.addView(TextView(this).apply {
            text = "Автоматически обновляет последние ${BackgroundSyncScheduler.BACKGROUND_DAYS} дня примерно каждые ${BackgroundSyncScheduler.INTERVAL_HOURS} часов. Это повторно захватывает ночной сон, досыпы и дневной сон."
            textSize = 14f
            alpha = 0.72f
            setPadding(0, dp(6), 0, 0)
        })

        backgroundSwitch = SwitchMaterial(this).apply {
            text = "Синхронизировать в фоне"
            isChecked = prefs.getBoolean(BackgroundSyncScheduler.PREF_ENABLED, true)
            setPadding(0, dp(12), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(BackgroundSyncScheduler.PREF_ENABLED, checked).apply()
                BackgroundSyncScheduler.apply(this@StreamingMainActivity)
                refreshBackgroundInfo()
            }
        }.also(content::addView)

        backgroundInfo = TextView(this).apply {
            textSize = 12f
            alpha = 0.68f
            setPadding(0, dp(8), 0, 0)
        }.also(content::addView)

        card.addView(content)
        container.addView(card)
        refreshBackgroundInfo()
    }

    private fun refreshBackgroundInfo() {
        val info = backgroundInfo ?: return
        val enabled = prefs.getBoolean(BackgroundSyncScheduler.PREF_ENABLED, true)
        backgroundSwitch?.isChecked = enabled
        if (!enabled) {
            info.text = "Выключено"
            return
        }
        if (!backgroundReadAvailable()) {
            info.text = "На этой версии Health Connect фоновое чтение недоступно. Ручная синхронизация продолжит работать."
            return
        }

        lifecycleScope.launch {
            val backgroundGranted = runCatching {
                HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in
                    client.permissionController.getGrantedPermissions()
            }.getOrDefault(false)

            if (!backgroundGranted) {
                info.text = "Нужно нажать «Управление разрешениями» и разрешить доступ к данным Health Connect в фоне."
                return@launch
            }

            val lastRun = prefs.getLong(BackgroundSyncScheduler.PREF_LAST_RUN, 0L)
            val lastStatus = prefs.getString(BackgroundSyncScheduler.PREF_LAST_STATUS, "Ожидает первого фонового запуска")
                .orEmpty()
            val whenText = if (lastRun > 0L) {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastRun))
            } else {
                "ещё не запускалась"
            }
            info.text = "Последний фоновый запуск: $whenText\n$lastStatus"
        }
    }

    private suspend fun synchronize(settings: Settings) {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            binding.status.text = "Health Connect недоступен"
            return
        }
        if (settings.endpoint.isBlank() || settings.token.isBlank()) {
            binding.status.text = "Укажи URL Apps Script и токен"
            return
        }

        val safeEndpoint = runCatching { EndpointSecurity.requireHttps(settings.endpoint) }
            .getOrElse {
                binding.status.text = it.message ?: "Некорректный URL Apps Script"
                return
            }
        val missingReadPermissions = runCatching { HealthConnectPermissionSet.missingReadPermissions(client) }
            .getOrElse {
                binding.status.text = "Не удалось проверить разрешения Health Connect"
                return
            }
        if (missingReadPermissions.isNotEmpty()) {
            binding.status.text =
                "Не выданы все разрешения Health Connect (${missingReadPermissions.size})"
            return
        }

        runCatching {
            SyncRunGate.runManual(
                onWaiting = { running ->
                    binding.status.text = "Жду завершения ${running ?: "другой"} синхронизации…"
                }
            ) {
                val runId = SyncDiagnostics.begin(this, "manual")
                val syncStartedAt = SystemClock.elapsedRealtime()
                var lastProgressMessage = "Запуск синхронизации…"
                val heartbeat = lifecycleScope.launch {
                    while (isActive) {
                        delay(5_000L)
                        val elapsedSec = (SystemClock.elapsedRealtime() - syncStartedAt) / 1000L
                        binding.status.text = "$lastProgressMessage\n● Процесс идёт · ${elapsedSec} с"
                    }
                }
                try {
                    val streamer = HealthSyncStreamer(this, client)
                    val result = streamer.sync(
                        safeEndpoint,
                        settings.token,
                        settings.days,
                        includeHistoricalChanges = true
                    ) { message ->
                        lastProgressMessage = message
                        val elapsedSec = (SystemClock.elapsedRealtime() - syncStartedAt) / 1000L
                        SyncDiagnostics.progress(this, runId, "manual", message)
                        binding.status.text = "$message\n● Процесс идёт · ${elapsedSec} с"
                    }
                    SyncDiagnostics.finish(
                        this,
                        runId,
                        "manual",
                        "Дней ${result.days}, тренировок ${result.workouts}, измерений ${result.measurements}"
                    )
                    result
                } catch (cancelled: CancellationException) {
                    SyncDiagnostics.skipped(this, "manual", "Ручная синхронизация отменена системой или закрытием экрана")
                    throw cancelled
                } catch (error: Throwable) {
                    SyncDiagnostics.failure(this, runId, "manual", error)
                    throw error
                } finally {
                    heartbeat.cancel()
                }
            }
        }.onSuccess { result ->
            // null means the user tapped sync again while the existing manual run
            // is still active. Keep that run's live progress visible and do nothing.
            if (result == null) return@onSuccess
            binding.status.text =
                "Синхронизация завершена: дней ${result.days}, тренировок ${result.workouts}, " +
                    "измерений ${result.measurements}, источников ${result.sources}"
        }.onFailure { error ->
            binding.status.text = when (error) {
                is OutOfMemoryError -> "Ошибка памяти: данных слишком много даже для дневного пакета. Уменьши диапазон до 1 дня и сообщи мне."
                else -> "Ошибка: ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    private fun saveSettingsFromForm(): Settings {
        val endpoint = binding.endpoint.text.toString().trim()
        val token = binding.token.text.toString().trim()
        val days = binding.days.text.toString().toIntOrNull()?.coerceIn(1, 30) ?: 7
        prefs.edit()
            .putString("endpoint", endpoint)
            .putInt("days", days)
            .remove("token")
            .apply()
        secureTokenStore.setToken(token)
        BackgroundSyncScheduler.apply(this)
        return Settings(endpoint, token, days)
    }

    private fun exportConfig(uri: Uri) {
        runCatching {
            val settings = saveSettingsFromForm()
            val json = JSONObject().apply {
                put("format", "HealthConnectorConfig")
                put("version", 4)
                put("endpoint", settings.endpoint)
                put("days", settings.days)
                put("backgroundSync", prefs.getBoolean(BackgroundSyncScheduler.PREF_ENABLED, true))
                put("tokenIncluded", false)
                put("exportedAt", Instant.now().toString())
            }.toString(2)
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(json) }
                ?: error("Не удалось открыть файл для записи")
        }.onSuccess {
            binding.status.text = "Настройки сохранены без API-токена"
        }.onFailure {
            binding.status.text = "Ошибка сохранения настроек: ${it.message}"
        }
    }

    private fun importConfig(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        lifecycleScope.launch {
            val result = ConfigAutoRestore.importFromUri(this@StreamingMainActivity, uri)
            if (!result.restored) {
                binding.status.text = "Ошибка импорта настроек: ${result.message}"
                return@launch
            }

            val endpoint = prefs.getString("endpoint", "").orEmpty()
            val days = prefs.getInt("days", 7)
            val backgroundSync = prefs.getBoolean(BackgroundSyncScheduler.PREF_ENABLED, true)
            binding.endpoint.setText(endpoint)
            binding.token.setText(secureTokenStore.getToken())
            binding.days.setText(days.toString())
            backgroundSwitch?.isChecked = backgroundSync
            binding.status.text = result.message
            refreshBackgroundInfo()
            refreshDashboardSnapshot()
        }
    }

    private fun importFatSecretCsv(uri: Uri) {
        val settings = saveSettingsFromForm()
        if (settings.endpoint.isBlank() || settings.token.isBlank()) {
            binding.status.text = "Сначала укажи URL Apps Script и токен"
            return
        }

        lifecycleScope.launch {
            binding.status.text = "Читаю CSV FatSecret…"
            runCatching {
                val fileName = queryFileName(uri) ?: "fatsecret.csv"
                val csvText = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: error("Не удалось прочитать CSV")
                }
                require(csvText.isNotBlank()) { "CSV пустой" }
                require(csvText.length <= 5_000_000) { "CSV слишком большой: максимум 5 МБ" }

                val normalized = FatSecretCsvNormalizer.normalize(csvText)
                binding.status.text = if (normalized.normalizedMealLabels > 0) {
                    "Объединяю дополнительные приёмы пищи…"
                } else {
                    "Отправляю CSV в Google Таблицу…"
                }
                val body = JSONObject().apply {
                    put("token", settings.token)
                    put("action", "fatsecretCsv")
                    put("fileName", fileName)
                    put("csvText", normalized.csvText)
                    put("uploadedAt", Instant.now().toString())
                    put("sourceMimeType", contentResolver.getType(uri).orEmpty())
                    put("normalizedMealLabels", normalized.normalizedMealLabels)
                }
                postBody(settings.endpoint, body)
            }.onSuccess { response ->
                val importedDays = response?.optInt("days", 0) ?: 0
                val meals = response?.optInt("meals", 0) ?: 0
                binding.status.text = "CSV импортирован: дней $importedDays, приёмов пищи $meals"
            }.onFailure {
                binding.status.text = "Ошибка импорта CSV: ${it.message}"
            }
        }
    }

    private fun queryFileName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null
        } finally {
            cursor?.close()
        }
    }

    private suspend fun postBody(endpoint: String, body: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        val safeEndpoint = EndpointSecurity.requireHttps(endpoint)
        val connection = URL(safeEndpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) error("HTTP $code")
        if (response.isBlank()) return@withContext null
        val json = runCatching { JSONObject(response) }.getOrNull()
        if (json?.optBoolean("ok", true) == false) error(json.optString("message", response))
        json
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class Settings(
        val endpoint: String,
        val token: String,
        val days: Int
    )
}
