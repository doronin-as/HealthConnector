package ru.doronin.healthconnector

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Interactive high-power BLE discovery screen used when the persistent
 * PendingIntent scan cannot bind an XMTZC05HM automatically.
 *
 * The manual finder deliberately owns the BLE scanner while it is open: the
 * persistent PendingIntent scan is stopped first and restarted on exit. This
 * avoids OEM Bluetooth stacks (notably some Xiaomi/MIUI builds) starving a
 * second concurrent scan and returning zero results without an explicit error.
 */
class MiScaleFinderActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val serviceUuid = ParcelUuid.fromString(MiScaleAdvertisementParser.BODY_COMPOSITION_SERVICE_UUID)
    private val devices = linkedMapOf<String, FoundDevice>()

    private lateinit var status: TextView
    private lateinit var resultsContainer: LinearLayout
    private lateinit var showAllButton: Button
    private lateinit var restartButton: Button

    private var scanning = false
    private var showAll = false
    private var searchEndsAt = 0L
    private var rawCallbacks = 0
    private var batchCallbacks = 0
    private var callbacksWithoutRecord = 0
    private var addressReadFailures = 0

    private val delayedForegroundStart = Runnable {
        if (!isFinishing && !isDestroyed) startForegroundScan()
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (MiScaleScanner.hasPermissions(this)) {
            startSearch()
        } else {
            val denied = grants.filterValues { !it }.keys.joinToString()
            status.text = "Нет разрешения Bluetooth${if (denied.isBlank()) "." else ": $denied"}"
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        startSearch()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            rawCallbacks += 1
            consume(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            batchCallbacks += 1
            rawCallbacks += results.size
            results.forEach(::consume)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            prefs.edit()
                .putString(MiScaleScanner.PREF_LAST_ERROR, "manual BLE scan failed: $errorCode")
                .apply()
            status.text = "Ошибка BLE-сканера: код $errorCode. Нажми «Искать заново»."
            renderResults()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!scanning) return
            val remaining = ((searchEndsAt - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1000L
            val likely = devices.values.count { it.isLikelyScale }
            val location = if (isSystemLocationEnabled()) "геолокация вкл." else "ГЕОЛОКАЦИЯ ВЫКЛ."
            status.text = buildString {
                append("Ищу весы… ${remaining}с · BLE-устройств: ${devices.size} · похожих: $likely")
                append("\nСырых BLE-callback: $rawCallbacks · $location")
                if (callbacksWithoutRecord > 0) append(" · без scanRecord: $callbacksWithoutRecord")
                if (addressReadFailures > 0) append(" · адрес недоступен: $addressReadFailures")
                if (rawCallbacks == 0 && remaining <= 20L && !isSystemLocationEnabled()) {
                    append("\n⚠ Android пока не отдаёт BLE-результаты. На некоторых Xiaomi/MIUI для сканирования должна быть включена системная геолокация.")
                }
            }
            renderResults()
            if (remaining <= 0L) {
                stopSearch(timeout = true)
            } else {
                handler.postDelayed(this, 500L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Поиск весов"
        setContentView(buildContent())
        handler.post { ensurePermissionsAndStart() }
    }

    override fun onDestroy() {
        handler.removeCallbacks(delayedForegroundStart)
        stopSearch(timeout = false, restartBackground = true)
        super.onDestroy()
    }

    private fun buildContent(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "Принудительный поиск Xiaomi Scale"
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Разбуди весы: встань на них босиком и не сходи до завершения измерения. Ручной режим временно останавливает фоновый BLE-сканер и запускает отдельный активный поиск без фильтров."
            textSize = 14f
            alpha = 0.76f
            setPadding(0, dp(8), 0, dp(14))
        })

        status = TextView(this).apply {
            text = "Подготавливаю BLE-сканер…"
            textSize = 15f
            setPadding(0, dp(4), 0, dp(10))
        }
        root.addView(status)

        restartButton = Button(this).apply {
            text = "Искать заново (30 секунд)"
            setOnClickListener { ensurePermissionsAndStart() }
        }
        root.addView(restartButton)

        root.addView(Button(this).apply {
            text = "Открыть системный Bluetooth"
            setOnClickListener {
                runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                    .onFailure { Toast.makeText(this@MiScaleFinderActivity, "Не удалось открыть настройки Bluetooth", Toast.LENGTH_LONG).show() }
            }
        })

        root.addView(Button(this).apply {
            text = "Открыть системную геолокацию"
            setOnClickListener {
                runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    .onFailure { Toast.makeText(this@MiScaleFinderActivity, "Не удалось открыть настройки геолокации", Toast.LENGTH_LONG).show() }
            }
        })

        showAllButton = Button(this).apply {
            text = "Показать все BLE-устройства"
            setOnClickListener {
                showAll = !showAll
                text = if (showAll) "Скрыть прочие BLE-устройства" else "Показать все BLE-устройства"
                renderResults()
            }
        }
        root.addView(showAllButton)

        root.addView(TextView(this).apply {
            text = "Найденные устройства"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(6))
        })

        resultsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(resultsContainer)

        root.addView(TextView(this).apply {
            text = "Диагностика различает «Android вообще не вызвал BLE callback» и «callback пришёл, но реклама устройства неполная». Если сырых callback остаётся 0, проблема находится ниже распознавания Xiaomi Scale — на уровне разрешений, системной геолокации или Bluetooth-стека телефона."
            textSize = 12f
            alpha = 0.68f
            setPadding(0, dp(16), 0, 0)
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun ensurePermissionsAndStart() {
        val missing = MiScaleScanner.requiredRuntimePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            status.text = "Нужно разрешение Bluetooth для поиска весов."
            permissionsLauncher.launch(missing.toTypedArray())
            return
        }
        startSearch()
    }

    /**
     * Stop the persistent PendingIntent scan before starting the foreground
     * callback scan. Some OEM stacks accept both registrations but starve the
     * newer one and return zero callbacks.
     */
    private fun startSearch() {
        handler.removeCallbacks(delayedForegroundStart)
        stopSearch(timeout = false, restartBackground = false)
        MiScaleScanner.stop(this)

        devices.clear()
        rawCallbacks = 0
        batchCallbacks = 0
        callbacksWithoutRecord = 0
        addressReadFailures = 0
        renderResults()
        status.text = "Фоновый BLE-сканер остановлен. Запускаю активный поиск…"

        // Give the vendor Bluetooth stack a short moment to release the old
        // PendingIntent scan registration before registering ScanCallback.
        handler.postDelayed(delayedForegroundStart, 500L)
    }

    private fun startForegroundScan() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            status.text = "Телефон не сообщает поддержку Bluetooth LE."
            return
        }

        val manager = getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null) {
            status.text = "Bluetooth-адаптер недоступен на этом устройстве."
            return
        }
        if (!adapter.isEnabled) {
            status.text = "Bluetooth выключен. Включи его в системном окне."
            runCatching {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }.onFailure {
                status.text = "Не удалось открыть включение Bluetooth: ${it.message ?: it.javaClass.simpleName}"
            }
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            status.text = "BLE-сканер недоступен."
            return
        }

        runCatching {
            scanner.startScan(
                emptyList(),
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setReportDelay(0L)
                    .setLegacy(true)
                    .build(),
                scanCallback
            )
            scanning = true
            searchEndsAt = System.currentTimeMillis() + SEARCH_MS
            prefs.edit()
                .putString(MiScaleScanner.PREF_SCAN_STATE, "Ручной активный поиск весов (фон остановлен)")
                .putLong(MiScaleScanner.PREF_SCAN_STARTED_AT, System.currentTimeMillis())
                .remove(MiScaleScanner.PREF_LAST_ERROR)
                .apply()
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }.onFailure { error ->
            status.text = "Не удалось запустить активный BLE-поиск: ${error.message ?: error.javaClass.simpleName}"
            prefs.edit().putString(MiScaleScanner.PREF_LAST_ERROR, error.message ?: error.javaClass.simpleName).apply()
        }
    }

    private fun stopSearch(timeout: Boolean, restartBackground: Boolean = false) {
        handler.removeCallbacks(ticker)
        if (scanning) {
            val scanner = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
            runCatching { scanner?.stopScan(scanCallback) }
            scanning = false
            if (timeout) {
                val likely = devices.values.count { it.isLikelyScale }
                status.text = when {
                    rawCallbacks == 0 -> buildString {
                        append("Поиск завершён: Android не вернул ни одного BLE-callback.")
                        append("\nBluetooth: включён · разрешения: ✓ · системная геолокация: ${if (isSystemLocationEnabled()) "включена" else "ВЫКЛЮЧЕНА"}.")
                        append("\nПопробуй включить геолокацию, затем «Искать заново». Если снова 0 — проверим системный Bluetooth-стек/ограничения MIUI.")
                    }
                    likely > 0 -> "Поиск завершён. Найдено похожих на весы: $likely. Выбери устройство ниже. Сырых callback: $rawCallbacks."
                    else -> "Поиск завершён: Android отдаёт BLE ($rawCallbacks callback), но Xiaomi Scale не распознаны. Включи «Показать все BLE-устройства»."
                }
                renderResults()
            }
        }
        if (restartBackground && prefs.getBoolean(MiScaleScanner.PREF_ENABLED, false)) {
            MiScaleScanner.start(this)
        }
    }

    private fun consume(result: ScanResult) {
        val record = result.scanRecord
        if (record == null) callbacksWithoutRecord += 1

        val address = runCatching { result.device.address }
            .getOrElse {
                addressReadFailures += 1
                return
            }
            .uppercase(Locale.ROOT)

        val name = record?.deviceName
            ?: runCatching { result.device.name }.getOrNull()
            ?: "Без имени"
        val serviceData = record?.getServiceData(serviceUuid)
        val advertisesService = record?.serviceUuids?.contains(serviceUuid) == true
        val normalizedName = name.uppercase(Locale.ROOT)
        val nameLooksLikeScale = normalizedName == "MIBFS" ||
            normalizedName.contains("MI SCALE") ||
            normalizedName.contains("BODY COMPOSITION")
        val likely = serviceData != null || advertisesService || nameLooksLikeScale
        val parsed = serviceData?.let {
            MiScaleAdvertisementParser.parsePayload(it, address)
        }
        val reason = buildList {
            if (serviceData != null) add("Service Data 0x181B")
            if (advertisesService) add("UUID 0x181B")
            if (nameLooksLikeScale) add("имя $name")
            if (parsed != null) add("стабильное измерение")
            if (record == null) add("scanRecord отсутствует")
        }.joinToString(" · ")

        devices[address] = FoundDevice(
            address = address,
            name = name,
            rssi = result.rssi,
            lastSeenAt = System.currentTimeMillis(),
            isLikelyScale = likely,
            reason = reason,
            weightKg = parsed?.weightKg,
            impedanceOhm = parsed?.impedanceOhm
        )

        if (likely) {
            prefs.edit()
                .putLong(MiScaleScanner.PREF_LAST_PACKET_AT, System.currentTimeMillis())
                .putString(MiScaleScanner.PREF_LAST_PACKET_ADDRESS, address)
                .putString(MiScaleScanner.PREF_LAST_PACKET_NAME, name)
                .putInt(MiScaleScanner.PREF_LAST_PACKET_RSSI, result.rssi)
                .apply()
        }
    }

    private fun renderResults() {
        if (!::resultsContainer.isInitialized) return
        resultsContainer.removeAllViews()
        val visible = devices.values
            .filter { it.isLikelyScale || showAll }
            .sortedWith(compareByDescending<FoundDevice> { it.isLikelyScale }.thenByDescending { it.rssi })

        if (visible.isEmpty()) {
            resultsContainer.addView(TextView(this).apply {
                text = when {
                    rawCallbacks == 0 -> "Пока Android не вернул ни одного BLE-callback…"
                    devices.isEmpty() -> "BLE-callback приходят ($rawCallbacks), но пока без доступного адреса устройства."
                    else -> "Весы пока не распознаны. Найдено других BLE-устройств: ${devices.size}."
                }
                alpha = 0.7f
                setPadding(0, 12, 0, 12)
            })
            return
        }

        visible.take(MAX_RENDERED).forEach { device ->
            val block = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 10, 0, 14)
            }
            block.addView(TextView(this).apply {
                text = buildString {
                    append(if (device.isLikelyScale) "⚖ Xiaomi Scale вероятно" else "BLE-устройство")
                    append("\n${device.name} · ${device.address} · ${device.rssi} dBm")
                    if (device.reason.isNotBlank()) append("\n${device.reason}")
                    if (device.weightKg != null) {
                        append("\nВес: ${String.format(Locale.US, "%.2f", device.weightKg)} кг")
                        if (device.impedanceOhm != null) append(" · импеданс ${device.impedanceOhm} Ω")
                    }
                }
                textSize = 14f
            })
            block.addView(Button(this).apply {
                text = if (device.isLikelyScale) "Привязать эти весы" else "Привязать вручную"
                setOnClickListener { bind(device) }
            })
            resultsContainer.addView(block)
        }

        if (visible.size > MAX_RENDERED) {
            resultsContainer.addView(TextView(this).apply {
                text = "Показаны первые $MAX_RENDERED из ${visible.size} устройств (сначала самые вероятные/сильные)."
                alpha = 0.65f
            })
        }
    }

    private fun bind(device: FoundDevice) {
        prefs.edit()
            .putBoolean(MiScaleScanner.PREF_ENABLED, true)
            .putString(MiScaleScanner.PREF_BOUND_ADDRESS, device.address)
            .putString(MiScaleScanner.PREF_SCAN_STATE, "Весы привязаны вручную: ${device.address}")
            .remove(MiScaleScanner.PREF_LAST_ERROR)
            .apply()
        stopSearch(timeout = false, restartBackground = false)
        MiScaleScanner.start(this)
        Toast.makeText(
            this,
            "Привязано: ${device.name} · ${device.address}. Теперь встань на весы для контрольного измерения.",
            Toast.LENGTH_LONG
        ).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun isSystemLocationEnabled(): Boolean = runCatching {
        getSystemService(LocationManager::class.java)?.isLocationEnabled ?: true
    }.getOrDefault(true)

    private data class FoundDevice(
        val address: String,
        val name: String,
        val rssi: Int,
        val lastSeenAt: Long,
        val isLikelyScale: Boolean,
        val reason: String,
        val weightKg: Double?,
        val impedanceOhm: Int?
    )

    companion object {
        private const val SEARCH_MS = 30_000L
        private const val MAX_RENDERED = 30
    }
}
