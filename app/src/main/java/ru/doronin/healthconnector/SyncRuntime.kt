package ru.doronin.healthconnector

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object SyncRunGate {
    private val mutex = Mutex()

    @Volatile
    var currentOrigin: String? = null
        private set

    suspend fun <T> runManual(onWaiting: (String?) -> Unit, block: suspend () -> T): T? {
        // A second manual tap must not queue another full sync or overwrite the
        // progress text of the manual sync that is already running.
        if (mutex.isLocked && currentOrigin == "manual") return null
        if (mutex.isLocked) onWaiting(currentOrigin)
        mutex.lock()
        currentOrigin = "manual"
        return try {
            block()
        } finally {
            currentOrigin = null
            mutex.unlock()
        }
    }

    suspend fun <T> tryRunBackground(block: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        currentOrigin = "background"
        return try {
            block()
        } finally {
            currentOrigin = null
            mutex.unlock()
        }
    }
}

object SyncDiagnostics {
    private const val PREFS = "settings"
    private const val KEY_EVENTS = "sync_diagnostic_events_v1"
    private const val MAX_EVENTS = 120

    @Volatile private var activeRunId: String = ""
    @Volatile private var activeOrigin: String = ""

    fun begin(context: Context, origin: String): String {
        val runId = UUID.randomUUID().toString().take(8)
        activeRunId = runId
        activeOrigin = origin
        record(context, runId, origin, "START", "INFO", "Запуск синхронизации")
        return runId
    }

    fun progress(context: Context, runId: String, origin: String, message: String) {
        record(context, runId, origin, stageFor(message), "INFO", message)
    }

    fun server(context: Context, message: String, level: String = "INFO") {
        record(
            context,
            activeRunId.ifBlank { "server" },
            activeOrigin.ifBlank { "server" },
            "GOOGLE_SHEETS",
            level,
            message
        )
    }

    fun finish(context: Context, runId: String, origin: String, message: String) {
        record(context, runId, origin, "DONE", "OK", message)
        if (activeRunId == runId) {
            activeRunId = ""
            activeOrigin = ""
        }
    }

    fun failure(context: Context, runId: String, origin: String, error: Throwable) {
        val message = error.message ?: error.javaClass.simpleName
        record(context, runId, origin, failureStage(error), "ERROR", "${error.javaClass.simpleName}: $message")
        if (activeRunId == runId) {
            activeRunId = ""
            activeOrigin = ""
        }
    }

    fun skipped(context: Context, origin: String, message: String) {
        record(context, "skip", origin, "LOCK", "WARNING", message)
    }

    fun render(context: Context, section: String): String {
        val events = readEvents(context)
        val filtered = when (section) {
            "server" -> events.filter {
                it.optString("stage").startsWith("GOOGLE") ||
                    it.optString("stage").startsWith("NETWORK") ||
                    it.optString("stage").startsWith("SERVER")
            }
            else -> events.filter { it.optString("origin") == section }
        }.takeLast(35)

        if (filtered.isEmpty()) return "Записей пока нет."
        val formatter = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())
        return filtered.joinToString("\n") { item ->
            val time = formatter.format(Date(item.optLong("time")))
            val level = when (item.optString("level")) {
                "ERROR" -> "✕"
                "WARNING" -> "!"
                "OK" -> "✓"
                else -> "•"
            }
            "$level $time  ${label(item.optString("stage"))}\n${item.optString("message")}"
        }
    }

    @Synchronized
    private fun record(
        context: Context,
        runId: String,
        origin: String,
        stage: String,
        level: String,
        message: String
    ) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val items = readEvents(context).toMutableList()
        items += JSONObject().apply {
            put("time", System.currentTimeMillis())
            put("runId", runId)
            put("origin", origin)
            put("stage", stage)
            put("level", level)
            put("message", message.take(700))
        }
        while (items.size > MAX_EVENTS) items.removeAt(0)
        prefs.edit().putString(KEY_EVENTS, JSONArray(items).toString()).apply()
    }

    private fun readEvents(context: Context): List<JSONObject> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EVENTS, "[]").orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) array.optJSONObject(index)?.let(::add)
        }
    }

    private fun stageFor(message: String): String = when {
        message.startsWith("Проверяю версию") -> "SERVER_PREFLIGHT"
        message.startsWith("Проверяю изменения") -> "HEALTH_CONNECT_CHANGES"
        message.startsWith("Читаю") -> "HEALTH_CONNECT_READ"
        message.startsWith("Отправляю") -> "GOOGLE_SHEETS_UPLOAD"
        else -> "APP"
    }

    private fun failureStage(error: Throwable): String {
        val value = (error.message ?: "").lowercase(Locale.ROOT)
        return when {
            "блокиров" in value || "lock" in value -> "SERVER_LOCK"
            "timeout" in value || "timed out" in value || "тайм-аут" in value -> "NETWORK_TIMEOUT"
            "permission" in value || "разреш" in value -> "HEALTH_CONNECT_PERMISSION"
            "http" in value || "google" in value || "sheets" in value -> "GOOGLE_SHEETS"
            else -> "APP_ERROR"
        }
    }

    private fun label(stage: String): String = when (stage) {
        "START" -> "Запуск"
        "LOCK" -> "Блокировка запусков"
        "SERVER_PREFLIGHT" -> "Проверка Apps Script"
        "HEALTH_CONNECT_CHANGES" -> "Изменения Health Connect"
        "HEALTH_CONNECT_READ" -> "Чтение Health Connect"
        "HEALTH_CONNECT_PERMISSION" -> "Разрешения Health Connect"
        "GOOGLE_SHEETS_UPLOAD" -> "Отправка в Google Sheets"
        "GOOGLE_SHEETS" -> "Google Sheets / сеть"
        "SERVER_LOCK" -> "Блокировка Apps Script"
        "NETWORK_TIMEOUT" -> "Сетевой тайм-аут"
        "DONE" -> "Завершение"
        "APP_ERROR" -> "Ошибка приложения"
        else -> "Приложение"
    }
}

fun addDiagnosticDropdown(
    context: Context,
    parent: LinearLayout,
    title: String,
    detailsProvider: () -> String
) {
    val header = TextView(context).apply {
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 18, 0, 18)
    }
    val details = TextView(context).apply {
        textSize = 12f
        alpha = 0.76f
        visibility = View.GONE
        setPadding(0, 0, 0, 18)
        setTextIsSelectable(true)
    }
    fun updateHeader() {
        header.text = (if (details.visibility == View.VISIBLE) "▼ " else "▶ ") + title
    }
    updateHeader()
    header.setOnClickListener {
        details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (details.visibility == View.VISIBLE) details.text = detailsProvider()
        updateHeader()
    }
    parent.addView(header)
    parent.addView(details)
}
