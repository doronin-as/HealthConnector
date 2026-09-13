package ru.doronin.healthconnector

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
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
 * Unlike the background scanner this intentionally scans without filters for a
 * short period. That lets us prove whether Android can see the scale at all and
 * also gives the user a manual binding escape hatch for firmware variants whose
 * advertisement does not match our known 0x181B/MIBFS signatures.
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
            consume(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
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
            status.text = "Ищу весы… ${remaining}с · BLE-устройств: ${devices.size} · похожих на весы: $likely"
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
            text = "Разбуди весы: встань на них босиком и не сходи до завершения измерения. Здесь используется активный BLE-поиск без фильтра, поэтому мы увидим даже нестандартную рекламу весов."
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
            text = "Если устройство помечено как «Xiaomi Scale вероятно», его можно привязать сразу. Для неизвестного BLE-устройства ручная привязка тоже доступна в режиме «Показать все», но используй её только если адрес/имя точно относятся к весам."
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

    private fun startSearch() {
        stopSearch(timeout = false, restartBackground = false)
        devices.clear()
        renderResults()

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
                    .build(),
                scanCallback
            )
            scanning = true
            searchEndsAt = System.currentTimeMillis() + SEARCH_MS
            prefs.edit()
                .putString(MiScaleScanner.PREF_SCAN_STATE, "Ручной активный поиск весов")
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
                status.text = if (likely > 0) {
                    "Поиск завершён. Найдено похожих на весы: $likely. Выбери устройство ниже."
                } else {
                    "Поиск завершён: совместимые весы не распознаны. Разбуди весы и нажми «Искать заново»; при необходимости включи «Показать все BLE-устройства»."
                }
                renderResults()
            }
        }
        if (restartBackground && prefs.getBoolean(MiScaleScanner.PREF_ENABLED, false)) {
            MiScaleScanner.start(this)
        }
    }

    private fun consume(result: ScanResult) {
        val record = result.scanRecord ?: return
        val address = runCatching { result.device.address }.getOrNull()?.uppercase(Locale.ROOT) ?: return
        val name = record.deviceName
            ?: runCatching { result.device.name }.getOrNull()
            ?: "Без имени"
        val serviceData = record.getServiceData(serviceUuid)
        val advertisesService = record.serviceUuids?.contains(serviceUuid) == true
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
                text = if (devices.isEmpty()) "Пока ничего не найдено…" else "Весы пока не распознаны. Найдено других BLE-устройств: ${devices.size}."
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
