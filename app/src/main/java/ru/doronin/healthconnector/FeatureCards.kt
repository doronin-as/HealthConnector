package ru.doronin.healthconnector

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.doronin.healthconnector.local.LocalStore
import ru.doronin.healthconnector.local.OutboxWorker
import ru.doronin.healthconnector.watchdog.SyncWatchdogWorker
import ru.doronin.healthconnector.water.HydrationPermissionActivity
import ru.doronin.healthconnector.water.WaterFormat
import ru.doronin.healthconnector.water.WaterRepository
import ru.doronin.healthconnector.water.WaterWidgetProvider
import ru.doronin.healthconnector.wellbeing.ReminderSchedule
import ru.doronin.healthconnector.wellbeing.WellbeingActivity
import ru.doronin.healthconnector.wellbeing.WellbeingReminderWorker
import java.text.DateFormat
import java.util.Date

/** Background jobs of the water / wellbeing / watchdog features. Safe to call repeatedly. */
object FeatureScheduler {
    fun apply(context: Context) {
        OutboxWorker.schedule(context)
        WellbeingReminderWorker.schedule(context)
        SyncWatchdogWorker.schedule(context)
        ru.doronin.healthconnector.energy.EnergyReportWorker.schedule(context)
    }
}

/** "Сегодня" card on the dashboard and the settings card for these features. */
object FeatureCards {
    private const val DASHBOARD_TAG = "today_water_wellbeing_card"
    private const val SETTINGS_TAG = "water_wellbeing_settings_card"

    fun inject(activity: StreamingMainActivity) {
        injectDashboard(activity)
        injectSettings(activity)
    }

    private fun injectDashboard(activity: StreamingMainActivity) {
        val page = activity.findViewById<ViewGroup>(R.id.dashboardPage) ?: return
        val container = page.getChildAt(0) as? LinearLayout ?: return
        container.findViewWithTag<View>(DASHBOARD_TAG)?.let(container::removeView)

        val today = WaterRepository.today(activity)
        val checkIn = ru.doronin.healthconnector.wellbeing.WellbeingRepository.current(activity)
        val (card, content) = card(activity, DASHBOARD_TAG, "Сегодня")

        content.addView(text(activity, "💧 ${WaterFormat.summary(today.totalMl, today.goalMl)}", 24f, bold = true))
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("+250" to 250, "+500" to 500, "↶" to 0).forEach { (label, ml) ->
            row.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(activity, 6) }
                setOnClickListener {
                    activity.lifecycleScope.launch {
                        val undone = withContext(Dispatchers.IO) {
                            if (ml > 0) { WaterRepository.add(activity, ml); null } else WaterRepository.undoLast(activity)
                        }
                        if (ml == 0 && undone == null) Toast.makeText(activity, "Сегодня ещё нет записей", Toast.LENGTH_SHORT).show()
                        WaterWidgetProvider.refreshAll(activity)
                        injectDashboard(activity)
                    }
                }
            })
        }
        content.addView(row)

        val wellbeingText = if (checkIn == null) {
            "🙂 Самочувствие ещё не отмечено"
        } else buildString {
            append("🙂 Самочувствие:")
            checkIn.energy?.let { append(" энергия $it") }
            checkIn.mood?.let { append(" · настроение $it") }
            checkIn.soreness?.let { append(" · мышцы $it") }
            checkIn.stress?.let { append(" · стресс $it") }
        }
        content.addView(text(activity, wellbeingText, 14f).apply { setPadding(0, dp(activity, 12), 0, 0) })
        content.addView(MaterialButton(activity).apply {
            text = if (checkIn == null) "Отметить самочувствие" else "Изменить отметку"
            setOnClickListener { activity.startActivity(Intent(activity, WellbeingActivity::class.java)) }
        })

        val outbox = LocalStore.get(activity).outboxStats()
        if (outbox.count > 0) {
            content.addView(text(activity, "Ждут отправки в таблицу: ${outbox.count}" +
                (if (outbox.lastError.isNotBlank()) " · ${outbox.lastError}" else ""), 12f).apply { alpha = 0.7f })
        }
        container.addView(card, minOf(1, container.childCount))
    }

    private fun injectSettings(activity: StreamingMainActivity) {
        val page = activity.findViewById<ViewGroup>(R.id.settingsPage) ?: return
        val container = page.getChildAt(0) as? LinearLayout ?: return
        container.findViewWithTag<View>(SETTINGS_TAG)?.let(container::removeView)
        val prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val (card, content) = card(activity, SETTINGS_TAG, "Вода, самочувствие, сторож")

        // Water goal
        content.addView(text(activity, "Цель по воде: ${WaterFormat.liters(WaterRepository.goalMl(activity))} л", 14f))
        val goals = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(1500, 2000, 2500, 3000).forEach { ml ->
            goals.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = WaterFormat.liters(ml)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(activity, 4) }
                setOnClickListener {
                    prefs.edit().putInt(WaterRepository.PREF_GOAL_ML, ml).apply()
                    WaterWidgetProvider.refreshAll(activity)
                    inject(activity)
                }
            })
        }
        content.addView(goals)
        val hcStatus = text(activity, "Health Connect: проверяю…", 12f).apply { alpha = 0.7f }
        content.addView(hcStatus)
        activity.lifecycleScope.launch {
            val allowed = runCatching { WaterRepository.canWriteHealthConnect(activity) }.getOrDefault(false)
            hcStatus.text = if (allowed) "Вода записывается и в Health Connect" else "Вода пока не пишется в Health Connect"
        }
        content.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Разрешить запись воды в Health Connect"
            setOnClickListener { activity.startActivity(Intent(activity, HydrationPermissionActivity::class.java)) }
        })

        // Wellbeing reminders
        content.addView(MaterialSwitch(activity).apply {
            text = "Напоминать отметить самочувствие"
            isChecked = WellbeingReminderWorker.isEnabled(activity)
            setPadding(0, dp(activity, 12), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(WellbeingReminderWorker.PREF_ENABLED, checked).apply()
                WellbeingReminderWorker.schedule(activity)
            }
        })
        val times = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            Triple("Утро", WellbeingReminderWorker.PREF_MORNING, WellbeingReminderWorker.DEFAULT_MORNING),
            Triple("Вечер", WellbeingReminderWorker.PREF_EVENING, WellbeingReminderWorker.DEFAULT_EVENING)
        ).forEach { (label, key, fallback) ->
            val time = ReminderSchedule.parse(prefs.getString(key, null), fallback)
            times.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "$label ${time}"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(activity, 6) }
                setOnClickListener {
                    TimePickerDialog(activity, { _, hour, minute ->
                        prefs.edit().putString(key, java.time.LocalTime.of(hour, minute).toString()).apply()
                        WellbeingReminderWorker.schedule(activity)
                        inject(activity)
                    }, time.hour, time.minute, true).show()
                }
            })
        }
        content.addView(times)

        // Watchdog
        val watchdog = activity.getSharedPreferences(SyncWatchdogWorker.PREFS, Context.MODE_PRIVATE)
        val lastCheck = watchdog.getLong(SyncWatchdogWorker.PREF_LAST_CHECK, 0L)
        content.addView(text(activity, buildString {
            append("Сторож синхронизации: каждые 6 ч")
            if (lastCheck > 0) {
                append("\nПоследняя проверка: ")
                append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastCheck)))
                append(" · ").append(watchdog.getString(SyncWatchdogWorker.PREF_LAST_RESULT, "—"))
            }
        }, 12f).apply { alpha = 0.7f; setPadding(0, dp(activity, 12), 0, 0) })
        content.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Проверить сейчас"
            setOnClickListener {
                WorkManager.getInstance(activity).enqueue(OneTimeWorkRequestBuilder<SyncWatchdogWorker>().build())
                Toast.makeText(activity, "Проверка запущена; при проблеме придёт уведомление", Toast.LENGTH_SHORT).show()
            }
        })
        container.addView(card)
    }

    private fun card(activity: Activity, tag: String, title: String): Pair<MaterialCardView, LinearLayout> {
        val card = MaterialCardView(activity).apply {
            this.tag = tag
            radius = dp(activity, 20).toFloat()
            strokeWidth = dp(activity, 1)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 12) }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 18), dp(activity, 18), dp(activity, 18), dp(activity, 14))
        }
        content.addView(text(activity, title, 18f, bold = true).apply { setPadding(0, 0, 0, dp(activity, 6)) })
        card.addView(content)
        return card to content
    }

    private fun text(activity: Activity, value: String, size: Float, bold: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = size
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
