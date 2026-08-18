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
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

class ShareCsvActivity : AppCompatActivity() {

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusView = TextView(this).apply {
            text = "Получаю экспорт FatSecret…"
            textSize = 18f
            setPadding(48, 64, 48, 64)
        }
        setContentView(statusView)

        val uri = extractSharedUri(intent)
        if (uri == null) {
            failAndOpenMain("Не удалось получить файл из меню «Поделиться»")
            return
        }

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val endpoint = prefs.getString("endpoint", "").orEmpty().trim()
        val token = prefs.getString("token", "").orEmpty().trim()
        if (endpoint.isBlank() || token.isBlank()) {
            failAndOpenMain("Сначала настрой URL Apps Script и токен в Health Connector")
            return
        }

        lifecycleScope.launch {
            runCatching {
                statusView.text = "Проверяю экспорт FatSecret…"
                val fileName = queryFileName(uri) ?: "fatsecret.csv"
                val mimeType = contentResolver.getType(uri).orEmpty()
                val csvText = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: error("Не удалось прочитать файл")
                }

                require(csvText.isNotBlank()) { "Файл пустой" }
                require(csvText.length <= 5_000_000) { "Файл слишком большой: максимум 5 МБ" }
                require(isLikelyFatSecretCsv(fileName, mimeType, csvText)) {
                    "Это не похоже на CSV-экспорт FatSecret"
                }

                statusView.text = "Отправляю питание в Health Connector…"
                val body = JSONObject().apply {
                    put("token", token)
                    put("action", "fatsecretCsv")
                    put("fileName", fileName)
                    put("mimeType", mimeType)
                    put("csvText", csvText)
                    put("uploadedAt", Instant.now().toString())
                }
                postBody(endpoint, body)
            }.onSuccess { response ->
                val days = response?.optInt("days", 0) ?: 0
                val meals = response?.optInt("meals", 0) ?: 0
                val message = "Питание импортировано: дней $days, приёмов пищи $meals"
                statusView.text = message
                Toast.makeText(this@ShareCsvActivity, message, Toast.LENGTH_LONG).show()
                statusView.postDelayed({ finish() }, 1600)
            }.onFailure { error ->
                failAndOpenMain("Ошибка импорта: ${error.message}")
            }
        }
    }

    private fun extractSharedUri(intent: Intent?): Uri? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
            }
            Intent.ACTION_VIEW -> intent.data
            else -> intent.data
        }
    }

    private fun queryFileName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    private fun isLikelyFatSecretCsv(fileName: String, mimeType: String, csvText: String): Boolean {
        val lowerName = fileName.lowercase()
        val lowerMime = mimeType.lowercase()
        val hasCsvName = lowerName.endsWith(".csv")
        val hasCsvMime = lowerMime.contains("csv") ||
            lowerMime == "application/vnd.ms-excel" ||
            lowerMime == "application/octet-stream" ||
            lowerMime == "text/plain"
        val hasFatSecretStructure = csvText.contains("# Report Details", ignoreCase = true) ||
            csvText.contains("FatSecret", ignoreCase = true)
        return (hasCsvName || hasCsvMime) && hasFatSecretStructure
    }

    private fun failAndOpenMain(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private suspend fun postBody(endpoint: String, body: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
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
        if (code !in 200..299) error("HTTP $code: $response")
        if (response.isBlank()) return@withContext null
        val json = runCatching { JSONObject(response) }.getOrNull()
        if (json?.optBoolean("ok", true) == false) {
            error(json.optString("message", json.optString("error", response)))
        }
        json
    }
}
