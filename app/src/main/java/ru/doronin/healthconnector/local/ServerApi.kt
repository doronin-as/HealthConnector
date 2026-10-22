package ru.doronin.healthconnector.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.doronin.healthconnector.EndpointSecurity
import ru.doronin.healthconnector.SecureTokenStore
import java.net.HttpURLConnection
import java.net.URL

/** Small HTTP client for the Apps Script endpoint used by background features. */
object ServerApi {
    class NotConfigured : Exception("Не настроен адрес Google Sheets или API-токен")

    /** Thrown for answers worth retrying later (network, busy server, 5xx). */
    class Retryable(message: String) : Exception(message)

    data class Credentials(val endpoint: String, val token: String)

    fun credentials(context: Context): Credentials {
        val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val endpoint = prefs.getString("endpoint", "").orEmpty().trim()
        val token = SecureTokenStore(context.applicationContext).getToken().trim()
        if (endpoint.isBlank() || token.isBlank()) throw NotConfigured()
        return Credentials(EndpointSecurity.requireHttps(endpoint), token)
    }

    suspend fun post(context: Context, action: String, body: JSONObject): JSONObject {
        val credentials = credentials(context)
        body.put("token", credentials.token)
        body.put("action", action)
        return withContext(Dispatchers.IO) {
            val connection = URL(credentials.endpoint).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 20_000
                connection.readTimeout = 60_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
                val code = try {
                    connection.responseCode
                } catch (error: java.io.IOException) {
                    throw Retryable(error.message ?: "сеть недоступна")
                }
                val text = runCatching {
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                }.getOrDefault("")
                if (code !in 200..299) {
                    val message = "HTTP $code"
                    if (code == 404 || code == 408 || code == 429 || code >= 500) throw Retryable(message)
                    error(message)
                }
                val json = runCatching { JSONObject(text) }.getOrElse { throw Retryable("сервер вернул не JSON") }
                if (!json.optBoolean("ok", true)) {
                    val message = json.optString("message", json.optString("error", "ошибка сервера"))
                    if (json.optString("errorCode") == "LOCK_BUSY") throw Retryable(message)
                    error(message)
                }
                json
            } catch (error: java.io.IOException) {
                throw Retryable(error.message ?: error.javaClass.simpleName)
            } finally {
                connection.disconnect()
            }
        }
    }
}
