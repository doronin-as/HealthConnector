package ru.doronin.healthconnector

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Reads the lightweight Dashboard/profile/device snapshot exposed by Apps Script. */
object DashboardApi {
    data class Profile(
        val heightCm: Double?,
        val birthDate: String?,
        val sex: String?,
        val foundFields: Set<String>
    )

    data class Summary(
        val date: String?,
        val steps: Long?,
        val weightKg: Double?,
        val sleepHours: Double?,
        val mainSleepHours: Double?,
        val restingHeartRate: Double?,
        val averageHeartRate: Double?,
        val averageSpO2: Double?,
        val activeCaloriesKcal: Double?,
        val workoutCount: Int?,
        val syncedAt: String?
    )

    data class Device(
        val type: String,
        val name: String,
        val packageName: String?,
        val address: String?,
        val lastSeen: String?
    )

    data class Snapshot(
        val profile: Profile,
        val summary: Summary,
        val devices: List<Device>,
        val dashboardUrl: String?,
        val fetchedAt: String?
    )

    suspend fun fetch(endpoint: String, token: String): Snapshot = withContext(Dispatchers.IO) {
        val safeEndpoint = EndpointSecurity.requireHttps(endpoint.trim())
        val payload = JSONObject().apply {
            put("action", "dashboardSnapshotV1")
            put("schemaVersion", 1)
            put("token", token.trim())
        }

        var lastError: Throwable? = null
        repeat(4) { attempt ->
            try {
                val json = post(safeEndpoint, payload)
                return@withContext parse(json)
            } catch (error: RetryableDashboardException) {
                lastError = error
                if (attempt < 3) delay((750L shl attempt).coerceAtMost(6_000L))
            }
        }
        throw lastError ?: IllegalStateException("Dashboard не ответил")
    }

    /** Applies only values that the sheet actually contains; local fallbacks are preserved. */
    fun applyScaleProfile(prefs: SharedPreferences, profile: Profile): Set<String> {
        val applied = linkedSetOf<String>()
        val edit = prefs.edit()
        profile.heightCm?.takeIf { it in 100.0..220.0 }?.let {
            edit.putString(MiScaleProfile.PREF_HEIGHT_CM, it.toString())
            applied += "рост"
        }
        profile.birthDate?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }?.let {
            edit.putString(MiScaleProfile.PREF_BIRTH_DATE, it)
            applied += "дата рождения"
        }
        profile.sex?.takeIf { it == "male" || it == "female" }?.let {
            edit.putString(MiScaleProfile.PREF_SEX, it)
            applied += "пол"
        }
        if (applied.isNotEmpty()) {
            edit.putLong(PREF_PROFILE_SYNC_AT, System.currentTimeMillis())
            edit.apply()
        }
        return applied
    }

    private fun post(endpoint: String, payload: JSONObject): JSONObject {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 35_000
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
                    throw RetryableDashboardException("Dashboard: HTTP $code")
                }
                error("Dashboard: HTTP $code")
            }
            val json = runCatching { JSONObject(text) }.getOrElse {
                throw RetryableDashboardException("Dashboard вернул некорректный JSON")
            }
            if (!json.optBoolean("ok", true)) {
                val message = json.optString("message", json.optString("error", "Ошибка Dashboard"))
                if (json.optString("errorCode") == "LOCK_BUSY") throw RetryableDashboardException(message)
                error(message)
            }
            json
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(json: JSONObject): Snapshot {
        val p = json.optJSONObject("profile") ?: JSONObject()
        val profile = Profile(
            heightCm = p.doubleOrNull("heightCm"),
            birthDate = p.stringOrNull("birthDate"),
            sex = p.stringOrNull("sex"),
            foundFields = p.optJSONArray("foundFields").toStringSet()
        )
        val s = json.optJSONObject("summary") ?: JSONObject()
        val summary = Summary(
            date = s.stringOrNull("date"),
            steps = s.longOrNull("steps"),
            weightKg = s.doubleOrNull("weightKg"),
            sleepHours = s.doubleOrNull("sleepHours"),
            mainSleepHours = s.doubleOrNull("mainSleepHours"),
            restingHeartRate = s.doubleOrNull("restingHeartRate"),
            averageHeartRate = s.doubleOrNull("averageHeartRate"),
            averageSpO2 = s.doubleOrNull("averageSpO2"),
            activeCaloriesKcal = s.doubleOrNull("activeCaloriesKcal"),
            workoutCount = s.intOrNull("workoutCount"),
            syncedAt = s.stringOrNull("syncedAt")
        )
        val devices = buildList {
            val array = json.optJSONArray("devices") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    Device(
                        type = item.optString("type", "Источник"),
                        name = item.optString("name", "Неизвестное устройство"),
                        packageName = item.stringOrNull("packageName"),
                        address = item.stringOrNull("address"),
                        lastSeen = item.stringOrNull("lastSeen")
                    )
                )
            }
        }
        return Snapshot(
            profile = profile,
            summary = summary,
            devices = devices,
            dashboardUrl = json.stringOrNull("dashboardUrl"),
            fetchedAt = json.stringOrNull("fetchedAt")
        )
    }

    private fun JSONObject.stringOrNull(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() }

    private fun JSONObject.doubleOrNull(name: String): Double? =
        if (!has(name) || isNull(name)) null else optDouble(name).takeIf { !it.isNaN() && it.isFinite() }

    private fun JSONObject.longOrNull(name: String): Long? =
        if (!has(name) || isNull(name)) null else runCatching { getLong(name) }.getOrNull()

    private fun JSONObject.intOrNull(name: String): Int? =
        if (!has(name) || isNull(name)) null else runCatching { getInt(name) }.getOrNull()

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return buildSet {
            for (index in 0 until length()) optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private class RetryableDashboardException(message: String) : RuntimeException(message)

    const val PREF_PROFILE_SYNC_AT = "dashboard_profile_sync_at"
}
