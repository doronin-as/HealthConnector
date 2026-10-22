package ru.doronin.healthconnector.wellbeing

import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/** Quick morning/evening check-in: four 1–5 scales and optional day tags. */
class WellbeingActivity : AppCompatActivity() {
    private val groups = linkedMapOf<String, MaterialButtonToggleGroup>()
    private val chips = linkedMapOf<String, Chip>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Самочувствие"
        NotificationManagerCompat.from(this).cancel(WellbeingReminderWorker.NOTIFICATION_ID)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        content.addView(TextView(this).apply {
            text = "Как вы сейчас?"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        val current = WellbeingRepository.current(this)
        addScale(content, "energy", "Энергия", "1 — нет сил · 5 — полно сил", current?.energy)
        addScale(content, "mood", "Настроение", "1 — плохое · 5 — отличное", current?.mood)
        addScale(content, "soreness", "Мышцы", "1 — ничего не болит · 5 — сильная крепатура", current?.soreness)
        addScale(content, "stress", "Стресс", "1 — спокойно · 5 — сильный", current?.stress)

        content.addView(label("Что было"))
        val chipGroup = ChipGroup(this)
        WellbeingRepository.TAGS.forEach { (key, title) ->
            val chip = Chip(this).apply {
                text = title
                isCheckable = true
                isChecked = current?.tags?.contains(key) == true
            }
            chips[key] = chip
            chipGroup.addView(chip)
        }
        content.addView(chipGroup)

        content.addView(MaterialButton(this).apply {
            text = "Сохранить"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
            setOnClickListener { save() }
        })

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun addScale(parent: LinearLayout, key: String, title: String, hint: String, value: Int?) {
        parent.addView(label(title))
        parent.addView(TextView(this).apply {
            text = hint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.7f
        })
        val group = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        for (score in 1..5) {
            val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = buttonId(key, score)
                text = score.toString()
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            group.addView(button)
        }
        if (value != null) group.check(buttonId(key, value))
        groups[key] = group
        parent.addView(group)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(16), 0, 0)
    }

    private fun score(key: String): Int? {
        val checked = groups[key]?.checkedButtonId ?: return null
        return (1..5).firstOrNull { buttonId(key, it) == checked }
    }

    private fun save() {
        val scores = listOf("energy", "mood", "soreness", "stress").associateWith(::score)
        if (scores.values.all { it == null } && chips.values.none { it.isChecked }) {
            Toast.makeText(this, "Отметьте хотя бы одну шкалу", Toast.LENGTH_SHORT).show()
            return
        }
        WellbeingRepository.save(
            this,
            energy = scores["energy"],
            mood = scores["mood"],
            soreness = scores["soreness"],
            stress = scores["stress"],
            tags = chips.filterValues { it.isChecked }.keys.toList()
        )
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        finish()
    }

    // Stable, positive, unique ids for programmatic buttons.
    private fun buttonId(key: String, score: Int): Int =
        0x5100_0000 + (listOf("energy", "mood", "soreness", "stress").indexOf(key) * 10) + score

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
