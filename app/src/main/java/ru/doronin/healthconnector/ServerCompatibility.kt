package ru.doronin.healthconnector

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Prevents a v4 client from uploading raw data to a legacy Apps Script endpoint. */
object ServerCompatibility {
    private const val REQUIRED_SCHEMA = 4

    suspend fun requireArchiveV4(endpoint: String) = withContext(Dispatchers.IO) {
        val safeEndpoint = EndpointSecurity.requireHttps(endpoint)
        val connection = URL(safeEndpoint).openConnection() as HttpURLConnection
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
                "Версия Google Apps Script устарела (сервер v$schema, приложение требует v$REQUIRED_SCHEMA). Опубликуй новую версию веб-приложения в Apps Script: Развернуть → Управление развертываниями → Изменить → Новая версия → Развернуть. Синхронизация не начиналась."
            }
        } finally {
            connection.disconnect()
        }
    }
}
