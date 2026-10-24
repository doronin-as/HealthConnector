package ru.doronin.healthconnector.energy

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch
import ru.doronin.healthconnector.R
import ru.doronin.healthconnector.StreamingMainActivity
import java.text.DateFormat
import java.util.Date

/** Dashboard card: real expenditure, calorie target for the chosen pace, goal forecast. */
object EnergyBalanceCard {
    private const val TAG = "energy_balance_card"
    private const val STALE_MS = 6 * 3_600_000L
    @Volatile private var refreshing = false

    fun inject(activity: StreamingMainActivity) {
        val page = activity.findViewById<ViewGroup>(R.id.dashboardPage) ?: return
        val container = page.getChildAt(0) as? LinearLayout ?: return
        container.findViewWithTag<View>(TAG)?.let(container::removeView)

        val cached = EnergyRepository.cached(activity)
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
            text = "Энергобаланс"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val result = cached?.result
        if (result != null && result.optString("status") == "ok") {
            content.addView(TextView(activity).apply {
                text = "${result.optInt("targetIntakeKcal")} ккал/день"
                textSize = 26f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(activity, 6), 0, 0)
            })
        }
        content.addView(TextView(activity).apply {
            text = when {
                result != null -> EnergyFormat.summary(result).joinToString("\n")
                refreshing -> "Считаю по питанию и взвешиваниям…"
                else -> "Нет расчёта. Нажмите «Пересчитать»."
            }
            textSize = 13f
            setPadding(0, dp(activity, 6), 0, dp(activity, 4))
        })
        if (cached != null) {
            content.addView(TextView(activity).apply {
                text = "Расчёт: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(cached.atMs)) +
                    " · расход = среднее потребление − изменение веса × 7700 ккал/кг"
                textSize = 11f
                alpha = 0.6f
            })
        }

        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Пересчитать"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(activity, 6) }
            setOnClickListener { refresh(activity, force = true) }
        })
        row.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Цель и темп"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showSettings(activity) }
        })
        content.addView(row)
        card.addView(content)
        container.addView(card, minOf(2, container.childCount))

        if (cached == null || System.currentTimeMillis() - cached.atMs > STALE_MS) refresh(activity, force = false)
    }

    private fun refresh(activity: StreamingMainActivity, force: Boolean) {
        if (refreshing && !force) return
        refreshing = true
        activity.lifecycleScope.launch {
            val error = runCatching { EnergyRepository.refresh(activity) }.exceptionOrNull()
            refreshing = false
            if (error != null && force) {
                android.widget.Toast.makeText(activity, "Не удалось пересчитать: ${error.message ?: error.javaClass.simpleName}", android.widget.Toast.LENGTH_LONG).show()
            }
            inject(activity)
        }
    }

    private fun showSettings(activity: StreamingMainActivity) {
        val prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), 0)
        }
        layout.addView(TextView(activity).apply { text = "Целевой вес, кг (пусто — из листа «Профиль»)" })
        val goal = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            EnergyRepository.goal(activity).takeIf { it > 0 }?.let { setText(EnergyFormat.kg(it.toDouble()).replace(',', '.')) }
        }
        layout.addView(goal)
        layout.addView(TextView(activity).apply { text = "Темп снижения веса"; setPadding(0, dp(activity, 12), 0, 0) })
        val rates = EnergyRepository.RATES
        val current = EnergyRepository.rate(activity)
        val group = android.widget.RadioGroup(activity)
        rates.forEachIndexed { index, rate ->
            group.addView(android.widget.RadioButton(activity).apply {
                id = 0x5200_0000 + index
                text = EnergyFormat.rate(-rate.toDouble()) + when (rate) {
                    0.25f -> " — мягко"; 0.5f -> " — рекомендуемо"; 0.75f -> " — быстрее"; else -> " — максимум"
                }
                isChecked = rate == current
            })
        }
        layout.addView(group)
        val weekly = MaterialSwitch(activity).apply {
            text = "Сводка по понедельникам в 9:00"
            isChecked = EnergyReportWorker.isEnabled(activity)
        }
        layout.addView(weekly)

        AlertDialog.Builder(activity)
            .setTitle("Цель и темп")
            .setView(layout)
            .setPositiveButton("Сохранить") { _, _ ->
                val goalKg = goal.text.toString().replace(',', '.').toFloatOrNull()?.takeIf { it in 40f..250f } ?: 0f
                val rate = rates.getOrNull(group.checkedRadioButtonId - 0x5200_0000) ?: current
                prefs.edit()
                    .putFloat(EnergyRepository.PREF_GOAL_KG, goalKg)
                    .putFloat(EnergyRepository.PREF_RATE, rate)
                    .putBoolean(EnergyReportWorker.PREF_ENABLED, weekly.isChecked)
                    .apply()
                EnergyReportWorker.schedule(activity)
                refresh(activity, force = true)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun dp(activity: StreamingMainActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
