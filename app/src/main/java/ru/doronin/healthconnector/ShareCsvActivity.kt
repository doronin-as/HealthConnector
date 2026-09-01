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
                val csvText = withContext(Dispatchers.IO) {
                    when {
                        sharedUri != null -> contentResolver.openInputStream(sharedUri)
                            ?.bufferedReader(Charsets.UTF_8)
                            ?.use { it.readText() }
                            ?: error("Не удалось прочитать переданный файл")
                        sharedText != null -> sharedText
                        else -> error("Отчёт не найден")
                    }
                }
                require(csvText.isNotBlank()) { "Отчёт пустой" }
                require(csvText.length <= 5_000_000) { "Отчёт слишком большой: максимум 5 МБ" }

                val fileName = sharedUri?.let(::queryFileName)
                    ?: intent?.getStringExtra(Intent.EXTRA_TITLE)?.takeIf { it.isNotBlank() }
                    ?: "fatsecret.csv"

                statusView.text = "Отправляю в dashboard…"
                val body = JSONObject().apply {
                    put("token", token)
                    put("action", "fatsecretCsv")
                    put("fileName", fileName)
                    put("csvText", csvText)
                    put("uploadedAt", Instant.now().toString())
                    put("sourceMimeType", intent?.type.orEmpty())
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
}
