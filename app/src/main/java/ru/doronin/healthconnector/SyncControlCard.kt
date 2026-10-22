package ru.doronin.healthconnector

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

/** Sync page card: stop everything, see what holds the server, choose the steps source. */
object SyncControlCard {
    private const val TAG = "sync_control_card"

    private val SOURCES = listOf(
        "" to "Авто: сводка Health Connect, при завышении — браслет",
        HealthSourceCatalog.FITBIT_PACKAGE to "Всегда Fitbit",
        HealthSourceCatalog.MI_FITNESS_PACKAGE to "Всегда Mi Fitness",
        HealthSourceCatalog.GOOGLE_FIT_PACKAGE to "Всегда Google Fit"
    )

    fun inject(activity: StreamingMainActivity) {
        val page = activity.findViewById<ViewGroup>(R.id.syncPage) ?: return
        val container = page.getChildAt(0) as? LinearLayout ?: return
        container.findViewWithTag<View>(TAG)?.let(container::removeView)
        val prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)

        val card = MaterialCardView(activity).apply {
            tag = TAG
            radius = dp(activity, 20).toFloat()
            strokeWidth = dp(activity, 1)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 12) }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), dp(activity, 12))
        }
        content.addView(TextView(activity).apply {
            text = "Управление синхронизацией"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val status = TextView(activity).apply {
            textSize = 13f
            alpha = 0.8f
            setPadding(0, dp(activity, 6), 0, dp(activity, 6))
            text = SyncControl.activeOrigins.let { if (it.isEmpty()) "На телефоне ничего не выполняется" else "Сейчас идёт: ${it.joinToString()}" }
        }
        content.addView(status)

        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(MaterialButton(activity).apply {
            text = "Остановить всё"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(activity, 6) }
            setOnClickListener {
                isEnabled = false
                status.text = "Останавливаю…"
                activity.lifecycleScope.launch {
                    val report = SyncControl.abortAll(activity)
                    status.text = buildString {
                        append(if (report.stoppedHere.isEmpty()) "На телефоне активных синхронизаций не было" else "Остановлено: ${report.stoppedHere.joinToString()}")
                        append("\n").append(report.serverMessage)
                        append("\nФоновая синхронизация продолжится по расписанию через 6 ч.")
                    }
                    isEnabled = true
                }
            }
        })
        row.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Что на сервере"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                status.text = "Спрашиваю сервер…"
                activity.lifecycleScope.launch {
                    status.text = runCatching { SyncControl.describeStatus(SyncControl.serverStatus(activity)) }
                        .getOrElse { "Сервер не ответил: ${it.message ?: it.javaClass.simpleName}" }
                }
            }
        })
        content.addView(row)
        content.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Пауза / запуск фоновых задач сервера"
            setOnClickListener {
                AlertDialog.Builder(activity)
                    .setTitle("Фоновые задачи сервера")
                    .setMessage("Миграция и сжатие HC_Измерения, перенос архива, досинхронизация Fitbit. На паузе они не занимают сервер. Перезапись HC_Измерения, если уже началась, всё равно будет доведена до конца.")
                    .setPositiveButton("Поставить на паузу") { _, _ -> setServerJobs(activity, status, true) }
                    .setNegativeButton("Запустить") { _, _ -> setServerJobs(activity, status, false) }
                    .setNeutralButton("Отмена", null)
                    .show()
            }
        })

        // Steps source
        val current = prefs.getString(HealthSyncStreamer.PREF_ACTIVITY_SOURCE_OVERRIDE, "").orEmpty()
        content.addView(TextView(activity).apply {
            text = "Источник шагов, дистанции и калорий: " + (SOURCES.firstOrNull { it.first == current }?.second ?: current)
            textSize = 13f
            setPadding(0, dp(activity, 12), 0, 0)
        })
        content.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Выбрать источник"
            setOnClickListener {
                AlertDialog.Builder(activity)
                    .setTitle("Источник активности")
                    .setSingleChoiceItems(SOURCES.map { it.second }.toTypedArray(), SOURCES.indexOfFirst { it.first == current }.coerceAtLeast(0)) { dialog, which ->
                        prefs.edit().putString(HealthSyncStreamer.PREF_ACTIVITY_SOURCE_OVERRIDE, SOURCES[which].first).apply()
                        dialog.dismiss()
                        inject(activity)
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        })
        content.addView(TextView(activity).apply {
            text = "В HC_Дни видно шаги каждого источника (StepsBySource), выбранный источник и проверку полноты дня (Quality). Новый выбор применяется при следующей синхронизации."
            textSize = 12f
            alpha = 0.65f
        })

        card.addView(content)
        container.addView(card, minOf(1, container.childCount))
    }

    private fun setServerJobs(activity: StreamingMainActivity, status: TextView, pause: Boolean) {
        status.text = if (pause) "Ставлю задачи сервера на паузу…" else "Запускаю задачи сервера…"
        activity.lifecycleScope.launch {
            status.text = runCatching {
                ru.doronin.healthconnector.local.ServerApi.post(
                    activity, "abortSyncV1",
                    org.json.JSONObject().put("schemaVersion", 1).put("pauseServerJobs", pause).put("abortRuns", false)
                )
                if (pause) "Фоновые задачи сервера на паузе"
                else "Фоновые задачи сервера снова работают"
            }.getOrElse { "Сервер не ответил: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    private fun dp(activity: StreamingMainActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
