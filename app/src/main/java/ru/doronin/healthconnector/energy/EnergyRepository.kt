package ru.doronin.healthconnector.energy

import android.content.Context
import org.json.JSONObject
import ru.doronin.healthconnector.local.ServerApi

/** Asks the server for the adaptive energy balance and caches the last answer. */
object EnergyRepository {
    const val PREF_GOAL_KG = "energy_goal_kg"          // Float; 0 = take from Профиль
    const val PREF_RATE = "energy_target_rate"          // Float, kg per week
    private const val PREF_CACHE = "energy_last_result"
    private const val PREF_CACHE_AT = "energy_last_result_at"
    const val DEFAULT_RATE = 0.5f
    val RATES = listOf(0.25f, 0.5f, 0.75f, 1.0f)

    data class Cached(val result: JSONObject, val atMs: Long)

    fun rate(context: Context): Float =
        prefs(context).getFloat(PREF_RATE, DEFAULT_RATE).takeIf { it in 0.1f..1.5f } ?: DEFAULT_RATE

    fun goal(context: Context): Float = prefs(context).getFloat(PREF_GOAL_KG, 0f)

    fun cached(context: Context): Cached? {
        val text = prefs(context).getString(PREF_CACHE, null) ?: return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return Cached(json, prefs(context).getLong(PREF_CACHE_AT, 0L))
    }

    suspend fun refresh(context: Context): JSONObject {
        val body = JSONObject().put("schemaVersion", 1).put("targetRateKgWeek", rate(context).toDouble())
        goal(context).takeIf { it > 0 }?.let { body.put("goalWeightKg", it.toDouble()) }
        val result = ServerApi.post(context, "energyBalanceV1", body)
        prefs(context).edit()
            .putString(PREF_CACHE, result.toString())
            .putLong(PREF_CACHE_AT, System.currentTimeMillis())
            .apply()
        return result
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
}
