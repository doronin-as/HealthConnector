package ru.doronin.healthconnector

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** Authenticated control plane for the server-side Fitbit Web API connector. */
object FitbitCloudApi {
    data class Status(
        val configured: Boolean,
        val authorized: Boolean,
        val redirectUri: String?,
        val userId: String?,
        val scope: String?,
        val tokenExpiresAt: String?,
        val lastSyncAt: String?,
        val lastStatus: String?
    )

    data class ConfigureResult(
        val status: Status,
        val authorizationUrl: String,
        val redirectUri: String
    )

    data class RepairResult(
        val date: String,
        val attempted: Boolean,
        val repaired: Boolean,
        val sleepSessions: Int,
        val reason: String?,
        val message: String?
    )

    suspend fun status(endpoint: String, token: String): Status =
        postWithRetry(endpoint, JSONObject().apply {
            put("action", "fitbitCloudStatusV1")
            put("schemaVersion", 1)
            put("token", token)
        }).optJSONObject("status").toStatus()

    suspend fun configure(
        endpoint: String,
        token: String,
        clientId: String,
        clientSecret: String
    ): ConfigureResult {
        require(clientId.isNotBlank()) { "Укажи Fitbit Client ID" }
        require(clientSecret.isNotBlank()) { "Укажи Fitbit Client Secret" }
        val json = postWithRetry(endpoint, JSONObject().apply {
            put("action", "fitbitCloudConfigureV1")
            put("schemaVersion", 1)
            put("token", token)
            put("clientId", clientId.trim())
            put("clientSecret", clientSecret.trim())
        })
        val authorizationUrl = json.optString("authorizationUrl").trim()
        val redirectUri = json.optString("redirectUri").trim()
        require(authorizationUrl.startsWith("https://")) { "Сервер не вернул Fitbit authorization URL" }
        require(redirectUri.startsWith("https://")) { "Сервер не вернул redirect URI" }
        return ConfigureResult(
            status = json.optJSONObject("status").toStatus(),
            authorizationUrl = authorizationUrl,
            redirectUri = redirectUri
        )
    }

    suspend fun authorizationUrl(endpoint: String, token: String): String {
        val json = postWithRetry(endpoint, JSONObject().apply {
            put("action", "fitbitCloudAuthorizationUrlV1")
            put("schemaVersion", 1)
            put("token", token)
        })
        return json.optString("authorizationUrl").trim()
            .takeIf { it.startsWith("https://") }
            ?: error("Сервер не вернул Fitbit authorization URL")
    }

    suspend fun repairSleep(endpoint: String, token: String, date: LocalDate): RepairResult {
        val json = postWithRetry(endpoint, JSONObject().apply {
            put("action", "fitbitRepairSleepV1")
            put("schemaVersion", 1)
            put("token", token)
            put("date", date.toString())
        }, attempts = 2)
        return RepairResult(
            date = json.optString("date", date.toString()),
            attempted = json.optBoolean("attempted", false),
            repaired = json.optBoolean("repaired", false),
            sleepSessions = json.optInt("sleepSessions", 0),
            reason = json.optString("reason").trim().takeIf { it.isNotEmpty() },
            message = json.optString("message").trim().takeIf { it.isNotEmpty() }
        )
    }

    private suspend fun postWithRetry(
        endpoint: String,
        payload: JSONObject,
        attempts: Int = 3
    ): JSONObject = withContext(Dispatchers.IO) {
        val safeEndpoint = EndpointSecurity.requireHttps(endpoint.trim())
        var last: Throwable? = null
        repeat(attempts.coerceAtLeast(1)) { attempt ->
            try {
                return@withContext post(safeEndpoint, payload)
            } catch (error: RetryableFitbitCloudException) {
                last = error
                if (attempt + 1 < attempts) delay((700L shl attempt).coerceAtMost(4_000L))
            }
        }
        throw last ?: IllegalStateException("Fitbit Cloud не ответил")
    }

    private fun post(endpoint: String, payload: JSONObject): JSONObject {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 45_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload.toString()) }

            val code = connection.responseCode
            val text = runCatching {
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }.getOrDefault("")

            if (code !in 200..299) {
                if (code == 404 || code == 408 || code == 429 || code >= 500) {
                    throw RetryableFitbitCloudException("Fitbit Cloud: HTTP $code")
                }
                error("Fitbit Cloud: HTTP $code")
            }

            val json = runCatching { JSONObject(text) }.getOrElse {
                throw RetryableFitbitCloudException("Fitbit Cloud вернул некорректный JSON")
            }
            if (!json.optBoolean("ok", true)) {
                val message = json.optString("message", json.optString("error", "Ошибка Fitbit Cloud"))
                if (json.optString("errorCode") == "LOCK_BUSY") {
                    throw RetryableFitbitCloudException(message)
                }
                error(message)
            }
            json
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject?.toStatus(): Status {
        val json = this ?: JSONObject()
        return Status(
            configured = json.optBoolean("configured", false),
            authorized = json.optBoolean("authorized", false),
            redirectUri = json.stringOrNull("redirectUri"),
            userId = json.stringOrNull("userId"),
            scope = json.stringOrNull("scope"),
            tokenExpiresAt = json.stringOrNull("tokenExpiresAt"),
            lastSyncAt = json.stringOrNull("lastSyncAt"),
            lastStatus = json.stringOrNull("lastStatus")
        )
    }

    private fun JSONObject.stringOrNull(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() }

    private class RetryableFitbitCloudException(message: String) : RuntimeException(message)
}
