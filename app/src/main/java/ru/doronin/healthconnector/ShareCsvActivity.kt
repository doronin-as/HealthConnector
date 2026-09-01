package ru.doronin.healthconnector

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

class ShareCsvActivity : AppCompatActivity() {

    private lateinit var statusView: TextView

    private data class MealBlock(
        val header: MutableList<String>,
        val body: MutableList<MutableList<String>>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusView = TextView(this).apply {
            text = "Получаю отчёт FatSecret…"
            textSize = 18f
            setPadding(48, 64, 48, 64)
        }
        setContentView(statusView)

        val sharedUri = extractSharedUri(intent)
        val sharedText = extractSharedText(intent)
        if (sharedUri == null && sharedText == null) {
            failAndOpenMain("Не удалось получить отчёт FatSecret")
            return
        }

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val endpoint = prefs.getString("endpoint", "").orEmpty().trim()
        val token = SecureTokenStore(this).getToken().trim()
        if (endpoint.isBlank() || token.isBlank()) {
            failAndOpenMain("Сначала настрой URL Apps Script и токен в Health Connector")
            return
        }
        val safeEndpoint = runCatching { EndpointSecurity.requireHttps(endpoint) }
            .getOrElse {
                failAndOpenMain("Для Apps Script нужен HTTPS URL")
                return
            }

        lifecycleScope.launch {
            runCatching {
                statusView.text = "Читаю отчёт FatSecret…"
                val rawCsvText = withContext(Dispatchers.IO) {
                    when {
                        sharedUri != null -> contentResolver.openInputStream(sharedUri)
                            ?.bufferedReader(Charsets.UTF_8)
                            ?.use { it.readText() }
                            ?: error("Не удалось прочитать переданный файл")
                        sharedText != null -> sharedText
                        else -> error("Отчёт не найден")
                    }
                }
                require(rawCsvText.isNotBlank()) { "Отчёт пустой" }
                require(rawCsvText.length <= 5_000_000) { "Отчёт слишком большой: максимум 5 МБ" }

                val normalized = normalizeFatSecretCsvForServer(rawCsvText)
                val csvText = normalized.first
                val normalizedMealLabels = normalized.second

                val fileName = sharedUri?.let(::queryFileName)
                    ?: intent?.getStringExtra(Intent.EXTRA_TITLE)?.takeIf { it.isNotBlank() }
                    ?: "fatsecret.csv"

                statusView.text = if (normalizedMealLabels > 0) {
                    "Объединяю дополнительные приёмы пищи…"
                } else {
                    "Отправляю в dashboard…"
                }

                val body = JSONObject().apply {
                    put("token", token)
                    put("action", "fatsecretCsv")
                    put("fileName", fileName)
                    put("csvText", csvText)
                    put("uploadedAt", Instant.now().toString())
                    put("sourceMimeType", intent?.type.orEmpty())
                    put("normalizedMealLabels", normalizedMealLabels)
                }
                postBody(safeEndpoint, body)
            }.onSuccess { response ->
                val days = response?.optInt("days", 0) ?: 0
                val meals = response?.optInt("meals", 0) ?: 0
                val message = "Готово: дней $days, приёмов пищи $meals"
                statusView.text = message
                Toast.makeText(this@ShareCsvActivity, message, Toast.LENGTH_LONG).show()
                statusView.postDelayed({ finish() }, 1600)
            }.onFailure { error ->
                failAndOpenMain("Ошибка импорта FatSecret: ${error.message}")
            }
        }
    }

    /**
     * The FatSecret diary can expose optional slots such as "До Завтрака",
     * "После Завтрака", "До Обеда", "До Ужина" and "После Ужина".
     * The live dashboard schema has one generic snack bucket, so all optional
     * slots are folded into "Перекус/Другое". If more than one such slot (or
     * the regular snack bucket) contains food on the same day, their nutrition
     * totals and food rows are merged before the CSV reaches Apps Script.
     */
    private fun normalizeFatSecretCsvForServer(csvText: String): Pair<String, Int> {
        val rows = parseCsvRows(csvText)
        val reportStart = rows.indexOfFirst {
            it.firstOrNull()?.trim() == "# Report Details"
        }
        if (reportStart < 0) return csvText to 0

        val output = mutableListOf<MutableList<String>>()
        for (index in 0..reportStart) output.add(rows[index].toMutableList())

        var changes = 0
        var dayHeader: MutableList<String>? = null
        val dayExtras = mutableListOf<MutableList<String>>()
        val dayMeals = linkedMapOf<String, MealBlock>()

        fun flushDay() {
            val header = dayHeader ?: return
            output.add(header)
            output.addAll(dayExtras.map { it.toMutableList() })
            dayMeals.values.forEach { block ->
                output.add(block.header)
                output.addAll(block.body)
            }
            dayHeader = null
            dayExtras.clear()
            dayMeals.clear()
        }

        var i = reportStart + 1
        while (i < rows.size) {
            val row = rows[i]
            val label = row.firstOrNull().orEmpty().trim()

            if (isReportTotalLabel(label)) {
                flushDay()
                while (i < rows.size) {
                    output.add(rows[i].toMutableList())
                    i++
                }
                break
            }

            if (isFatSecretDateLabel(label)) {
                flushDay()
                dayHeader = row.toMutableList()
                i++
                continue
            }

            val canonicalMeal = if (dayHeader != null) canonicalMealLabel(label) else null
            if (canonicalMeal != null) {
                val header = row.toMutableList()
                if (header.isEmpty()) header.add(canonicalMeal) else header[0] = canonicalMeal
                if (canonicalMeal != label) changes++

                val body = mutableListOf<MutableList<String>>()
                i++
                while (i < rows.size) {
                    val nextRow = rows[i]
                    val nextLabel = nextRow.firstOrNull().orEmpty().trim()
                    if (
                        isReportTotalLabel(nextLabel) ||
                        isFatSecretDateLabel(nextLabel) ||
                        canonicalMealLabel(nextLabel) != null
                    ) {
                        break
                    }
                    if (!isSubtotalLabel(nextLabel)) {
                        body.add(nextRow.toMutableList())
                    }
                    i++
                }

                val existing = dayMeals[canonicalMeal]
                if (existing == null) {
                    dayMeals[canonicalMeal] = MealBlock(header, body)
                } else {
                    mergeMealHeaders(existing.header, header)
                    existing.body.addAll(body)
                    changes++
                }
                continue
            }

            if (dayHeader == null) {
                output.add(row.toMutableList())
            } else if (!isSubtotalLabel(label)) {
                dayExtras.add(row.toMutableList())
            }
            i++
        }

        if (i >= rows.size) flushDay()
        return serializeCsvRows(output) to changes
    }

    private fun canonicalMealLabel(label: String): String? {
        val compact = label
            .trim()
            .lowercase()
            .replace(Regex("""\s*/\s*"""), "/")
            .replace(Regex("""\s+"""), " ")

        return when (compact) {
            "завтрак", "breakfast" -> "Завтрак"
            "обед", "lunch" -> "Обед"
            "полдник", "afternoon snack" -> "Полдник"
            "ужин", "dinner" -> "Ужин"

            "перекус/другое",
            "перекусы/другое",
            "закуски/другое",
            "снэки/другое",
            "snacks/other",
            "snack/other",
            "snacks & other",
            "snack & other",
            "до завтрака",
            "после завтрака",
            "до обеда",
            "до ужина",
            "после ужина",
            "before breakfast",
            "after breakfast",
            "before lunch",
            "before dinner",
            "after dinner" -> "Перекус/Другое"

            else -> null
        }
    }

    private fun mergeMealHeaders(target: MutableList<String>, incoming: List<String>) {
        val nutritionColumns = intArrayOf(1, 2, 4, 7)
        nutritionColumns.forEach { index ->
            val incomingValue = incoming.getOrNull(index)?.let(::parseCsvNumber) ?: return@forEach
            while (target.size <= index) target.add("")
            val currentValue = parseCsvNumber(target[index]) ?: 0.0
            target[index] = formatCsvNumber(currentValue + incomingValue)
        }
    }

    private fun parseCsvNumber(value: String): Double? {
        val normalized = value
            .trim()
            .replace("\u00A0", "")
            .replace(" ", "")
            .replace(',', '.')
        if (normalized.isBlank()) return null
        return normalized.toDoubleOrNull()
    }

    private fun formatCsvNumber(value: Double): String =
        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun isFatSecretDateLabel(label: String): Boolean = FATSECRET_DATE_REGEX.containsMatchIn(label)

    private fun isSubtotalLabel(label: String): Boolean {
        val value = label.trim().lowercase()
        return value == "итого" || value == "daily total"
    }

    private fun isReportTotalLabel(label: String): Boolean {
        val value = label.trim().lowercase()
        return value == "всего" || value == "total"
    }

    private fun parseCsvRows(text: String): MutableList<MutableList<String>> {
        val source = text.removePrefix("\uFEFF")
        val rows = mutableListOf<MutableList<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0

        fun finishField() {
            row.add(field.toString())
            field.setLength(0)
        }

        fun finishRow() {
            finishField()
            rows.add(row)
            row = mutableListOf()
        }

        while (i < source.length) {
            val ch = source[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < source.length && source[i + 1] == '"') {
                        field.append('"')
                        i += 2
                        continue
                    }
                    inQuotes = false
                } else {
                    field.append(ch)
                }
                i++
                continue
            }

            when (ch) {
                '"' -> inQuotes = true
                ',' -> finishField()
                '\r' -> {
                    if (i + 1 < source.length && source[i + 1] == '\n') i++
                    finishRow()
                }
                '\n' -> finishRow()
                else -> field.append(ch)
            }
            i++
        }

        if (field.isNotEmpty() || row.isNotEmpty()) finishRow()
        return rows
    }

    private fun serializeCsvRows(rows: List<List<String>>): String = rows.joinToString("\r\n") { row ->
        row.joinToString(",") { field ->
            val needsQuotes = field.contains(',') || field.contains('"') ||
                field.contains('\r') || field.contains('\n') ||
                field.startsWith(' ') || field.endsWith(' ')
            if (needsQuotes) "\"${field.replace("\"", "\"\"")}\"" else field
        }
    }

    private fun extractSharedText(intent: Intent?): String? {
        intent ?: return null

        intent.getStringExtra(Intent.EXTRA_TEXT)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        intent.getStringExtra(Intent.EXTRA_HTML_TEXT)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        intent.clipData?.let { clips ->
            for (index in 0 until clips.itemCount) {
                val item = clips.getItemAt(index)
                item.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
                item.htmlText?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }

        return null
    }

    private fun extractSharedUri(intent: Intent?): Uri? {
        intent ?: return null

        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val multiple = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            }
            multiple?.firstOrNull()?.let { return it }
        }

        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val stream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            if (stream != null) return stream
        }

        intent.clipData?.let { clips ->
            for (index in 0 until clips.itemCount) {
                clips.getItemAt(index).uri?.let { return it }
            }
        }

        return intent.data
    }

    private fun queryFileName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    private fun failAndOpenMain(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        startActivity(Intent(this, StreamingMainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private suspend fun postBody(endpoint: String, body: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        val safeEndpoint = EndpointSecurity.requireHttps(endpoint)
        val connection = URL(safeEndpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) error("HTTP $code")
        if (response.isBlank()) return@withContext null
        val json = runCatching { JSONObject(response) }.getOrNull()
        if (json?.optBoolean("ok", true) == false) {
            error(json.optString("message", json.optString("error", "Ошибка сервера")))
        }
        json
    }

    companion object {
        private val FATSECRET_DATE_REGEX = Regex(
            "(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря|" +
                "january|february|march|april|may|june|july|august|september|october|november|december)" +
                "\\s+\\d{1,2}\\s*,?\\s*\\d{4}",
            RegexOption.IGNORE_CASE
        )
    }
}
