package ru.doronin.healthconnector

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
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
        injectPhoneSensorCard(activity)
    }

    private fun injectPhoneSensorCard(activity: StreamingMainActivity) {
        val settingsPage = activity.findViewById<android.view.ViewGroup>(R.id.settingsPage) ?: return
        val container = settingsPage.getChildAt(0) as? LinearLayout ?: return
        container.findViewWithTag<android.view.View>(CARD_TAG)?.let(container::removeView)

        val snapshot = PhoneFloorStore.snapshot(activity)
        val sourceStatus = PhoneSensorDataSource.status(activity)
        val elevation = String.format(Locale.getDefault(), "%.0f", snapshot.elevationMeters)

        val card = MaterialCardView(activity).apply {
            tag = CARD_TAG
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
        private const val CARD_TAG = "phone_sensor_source_card"
    }
}
