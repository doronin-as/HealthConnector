package ru.doronin.healthconnector

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Prevents a v3 client from sending partial-day payloads to the legacy v2 Apps Script handler. */
object ServerCompatibility {
    private const val REQUIRED_SCHEMA = 3

    suspend fun requireV3(endpoint: String) = withContext(Dispatchers.IO) {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Проверка Google Sheets: HTTP $code")
            val json = runCatching { JSONObject(text) }
                .getOrElse { error("Apps Script вернул некорректный ответ") }
            val schema = json.optInt("schemaVersion", 0)
            require(schema >= REQUIRED_SCHEMA) {
                "Apps Script устарел (schema v$schema). Сначала разверни серверную часть schema v$REQUIRED_SCHEMA; данные не отправлены."
            }
        } finally {
            connection.disconnect()
        }
    }
}
