package ru.doronin.healthconnector

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.time.LocalDate
import java.time.format.DateTimeParseException

class MiScaleSettingsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var enabled: Switch
    private lateinit var height: EditText
    private lateinit var birthDate: EditText
    private lateinit var sex: Spinner
    private lateinit var boundInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Умные весы"
        setContentView(buildContent())
    }

    private fun buildContent(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        root.addView(TextView(this).apply {
            text = "Xiaomi Mi Body Composition Scale 2"
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Модель XMTZC05HM. HealthConnector слушает BLE-пакеты весов без Mi Fitness и сохраняет измерения в Dashboard. Для жира, воды и мышц нужны рост, дата рождения и пол."
            textSize = 14f
            alpha = 0.72f
            setPadding(0, dp(8), 0, dp(12))
        })

        enabled = Switch(this).apply {
            text = "Считывать весы автоматически"
            isChecked = prefs.getBoolean(MiScaleScanner.PREF_ENABLED, true)
        }
        root.addView(enabled)

        root.addView(label("Рост, см"))
        height = EditText(this).apply {
            hint = "Например, 180"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(prefs.getString(MiScaleProfile.PREF_HEIGHT_CM, ""))
        }
        root.addView(height)

        root.addView(label("Дата рождения"))
        birthDate = EditText(this).apply {
            hint = "ГГГГ-ММ-ДД"
            inputType = InputType.TYPE_CLASS_DATETIME
            setText(prefs.getString(MiScaleProfile.PREF_BIRTH_DATE, ""))
        }
        root.addView(birthDate)

        root.addView(label("Пол для формул состава тела"))
        sex = Spinner(this)
        val values = listOf("Выберите", "Мужской", "Женский")
        sex.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)
        sex.setSelection(
            when (prefs.getString(MiScaleProfile.PREF_SEX, "")) {
                "male" -> 1
                "female" -> 2
                else -> 0
            }
        )
        root.addView(sex)

        boundInfo = TextView(this).apply {
            textSize = 13f
            alpha = 0.75f
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(boundInfo)
        refreshBoundInfo()

        root.addView(Button(this).apply {
            text = "Сбросить привязку весов"
            setOnClickListener {
                prefs.edit().remove(MiScaleScanner.PREF_BOUND_ADDRESS).apply()
                refreshBoundInfo()
                Toast.makeText(this@MiScaleSettingsActivity, "Привязка сброшена. Следующее измерение привяжет весы заново.", Toast.LENGTH_LONG).show()
            }
        })

        root.addView(TextView(this).apply {
            text = "После сохранения встаньте на весы босиком. Первое стабильное измерение автоматически привяжет ближайшие совместимые весы; импеданс нужен для расчёта состава тела."
            textSize = 13f
            alpha = 0.72f
            setPadding(0, dp(12), 0, dp(12))
        })

        root.addView(Button(this).apply {
            text = "Сохранить и включить"
            setOnClickListener { saveAndContinue() }
        })
        root.addView(Button(this).apply {
            text = "Пока пропустить"
            setOnClickListener { continueToMain() }
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun label(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    private fun saveAndContinue() {
        val heightValue = height.text.toString().trim().replace(',', '.').toDoubleOrNull()
        if (enabled.isChecked && (heightValue == null || heightValue !in 100.0..220.0)) {
            height.error = "Укажи рост от 100 до 220 см"
            return
        }
        val birth = if (enabled.isChecked) {
            try { LocalDate.parse(birthDate.text.toString().trim()) }
            catch (_: DateTimeParseException) {
                birthDate.error = "Формат: ГГГГ-ММ-ДД"
                return
            }
        } else null
        if (enabled.isChecked && (birth == null || birth.isAfter(LocalDate.now().minusYears(13)))) {
            birthDate.error = "Проверь дату рождения"
            return
        }
        val sexValue = when (sex.selectedItemPosition) {
            1 -> "male"
            2 -> "female"
            else -> null
        }
        if (enabled.isChecked && sexValue == null) {
            Toast.makeText(this, "Выбери пол для расчёта состава тела", Toast.LENGTH_LONG).show()
            return
        }

        val edit = prefs.edit()
            .putBoolean(MiScaleScanner.PREF_ENABLED, enabled.isChecked)
            .putBoolean(MiScaleScanner.PREF_SETUP_PROMPTED, true)
        if (heightValue != null) edit.putString(MiScaleProfile.PREF_HEIGHT_CM, heightValue.toString())
        if (birth != null) edit.putString(MiScaleProfile.PREF_BIRTH_DATE, birth.toString())
        if (sexValue != null) edit.putString(MiScaleProfile.PREF_SEX, sexValue)
        edit.apply()

        if (enabled.isChecked) MiScaleScanner.start(this) else MiScaleScanner.stop(this)
        Toast.makeText(this, if (enabled.isChecked) "Весы включены. Встаньте на них для привязки." else "Считывание весов выключено", Toast.LENGTH_LONG).show()
        continueToMain()
    }

    private fun refreshBoundInfo() {
        val address = prefs.getString(MiScaleScanner.PREF_BOUND_ADDRESS, "").orEmpty()
        boundInfo.text = if (address.isBlank()) {
            "Весы ещё не привязаны"
        } else {
            "Привязаны весы: $address"
        }
    }

    private fun continueToMain() {
        if (intent.getBooleanExtra(EXTRA_OPEN_MAIN_AFTER, false)) {
            startActivity(Intent(this, StreamingMainActivity::class.java))
        }
        finish()
    }

    companion object {
        const val EXTRA_OPEN_MAIN_AFTER = "open_main_after"
    }
}
