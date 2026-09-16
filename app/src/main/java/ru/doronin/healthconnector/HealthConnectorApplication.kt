package ru.doronin.healthconnector

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import ru.doronin.healthconnector.floors.FloorDayChartView
import ru.doronin.healthconnector.floors.PhoneFloorSettingsActivity
import ru.doronin.healthconnector.floors.PhoneFloorStore
import ru.doronin.healthconnector.source.PhoneSensorDataSource
import java.util.Locale

class HealthConnectorApplication : Application(), Application.ActivityLifecycleCallbacks {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is StreamingMainActivity) return
        if (PhoneFloorStore.isEnabled(activity) && PhoneSensorDataSource.status(activity).available) {
            PhoneSensorDataSource.start(activity)
        }
        injectFloorDashboardCard(activity)
        injectPhoneSensorCard(activity)
    }

    private fun injectFloorDashboardCard(activity: StreamingMainActivity) {
        val dashboardPage = activity.findViewById<ViewGroup>(R.id.dashboardPage) ?: return
        val container = dashboardPage.getChildAt(0) as? LinearLayout ?: return
        container.findViewWithTag<View>(DASHBOARD_CARD_TAG)?.let(container::removeView)

        val snapshot = PhoneFloorStore.snapshot(activity)
        val history = PhoneFloorStore.history(activity)
        val sourceStatus = PhoneSensorDataSource.status(activity)
        val elevation = String.format(Locale.getDefault(), "%.0f", snapshot.elevationMeters)
        val lastDetection = PhoneFloorStore.formatLastDetection(snapshot)

        val card = MaterialCardView(activity).apply {
            tag = DASHBOARD_CARD_TAG
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
        content.addView(TextView(activity).apply {
            text = "Этажи сегодня"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(TextView(activity).apply {
            text = "${snapshot.floors} этажей  ·  +$elevation м"
            textSize = 28f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(activity, 8), 0, 0)
        })
        content.addView(TextView(activity).apply {
            text = buildString {
                append(if (snapshot.enabled) "Подсчёт включён" else "Подсчёт выключен")
                append(" · ").append(sourceStatus.summary)
                lastDetection?.let { append("\nПоследний подъём: ").append(it) }
                snapshot.lastHealthConnectError?.let {
                    append("\nHealth Connect: ").append(it)
                }
            }
            textSize = 12f
            alpha = 0.7f
            setPadding(0, dp(activity, 4), 0, dp(activity, 8))
        })
        content.addView(FloorDayChartView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(activity, 170)
            )
            setPadding(dp(activity, 2), 0, dp(activity, 2), dp(activity, 2))
            setData(snapshot.date, history)
        })
        content.addView(Button(activity).apply {
            text = if (snapshot.enabled) "Настройки подсчёта" else "Включить подсчёт этажей"
            setOnClickListener {
                activity.startActivity(Intent(activity, PhoneFloorSettingsActivity::class.java))
            }
        })
        card.addView(content)

        val insertAt = minOf(4, container.childCount)
        container.addView(card, insertAt)
    }

    private fun injectPhoneSensorCard(activity: StreamingMainActivity) {
        val settingsPage = activity.findViewById<ViewGroup>(R.id.settingsPage) ?: return
        val container = settingsPage.getChildAt(0) as? LinearLayout ?: return
        container.findViewWithTag<View>(SETTINGS_CARD_TAG)?.let(container::removeView)

        val snapshot = PhoneFloorStore.snapshot(activity)
        val sourceStatus = PhoneSensorDataSource.status(activity)
        val elevation = String.format(Locale.getDefault(), "%.0f", snapshot.elevationMeters)

        val card = MaterialCardView(activity).apply {
            tag = SETTINGS_CARD_TAG
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
            setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 18))
        }
        content.addView(TextView(activity).apply {
            text = "Датчики телефона"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(TextView(activity).apply {
            text = buildString {
                append(sourceStatus.summary)
                append("\nСегодня: ${snapshot.floors} этажей · +$elevation м")
                if (sourceStatus.missingRequirements.isNotEmpty()) {
                    append("\nНужно: ${sourceStatus.missingRequirements.joinToString()}")
                }
            }
            textSize = 13f
            alpha = 0.72f
            setPadding(0, dp(activity, 6), 0, dp(activity, 10))
        })
        content.addView(Button(activity).apply {
            text = "Настроить этажи"
            setOnClickListener {
                activity.startActivity(Intent(activity, PhoneFloorSettingsActivity::class.java))
            }
        })
        card.addView(content)
        container.addView(card)
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        private const val SETTINGS_CARD_TAG = "phone_sensor_source_card"
        private const val DASHBOARD_CARD_TAG = "phone_floor_dashboard_card"
    }
}
