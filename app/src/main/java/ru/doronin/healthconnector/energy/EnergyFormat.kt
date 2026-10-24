package ru.doronin.healthconnector.energy

import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Pure presentation and scheduling helpers for the energy balance (unit-tested). */
object EnergyFormat {
    private val ru = Locale("ru")
    private val dateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", ru)

    fun date(iso: String?): String? =
        iso?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it).format(dateFormat) }.getOrNull() }

    fun kg(value: Double): String = String.format(ru, "%.1f", value)

    fun rate(kgPerWeek: Double): String {
        val sign = if (kgPerWeek > 0.004) "+" else if (kgPerWeek < -0.004) "−" else ""
        return sign + String.format(ru, "%.2f", kotlin.math.abs(kgPerWeek)) + " кг/нед"
    }

    fun confidence(code: String): String = when (code) {
        "high" -> "надёжная"
        "medium" -> "примерная"
        "low" -> "грубая"
        else -> "нет"
    }

    /** Lines of the dashboard card / weekly notification. */
    fun summary(result: JSONObject): List<String> {
        if (result.optString("status") != "ok") {
            return listOf(result.optString("message", "Недостаточно данных для расчёта"))
        }
        val lines = mutableListOf<String>()
        lines += "Расход ≈ ${result.optInt("expenditureKcal")} ккал/день (оценка ${confidence(result.optString("confidence"))})"
        lines += "Норма: ${result.optInt("targetIntakeKcal")} ккал/день для ${rate(-result.optDouble("targetRateKgWeek"))}"
        if (!result.isNull("planDifferenceKcal")) {
            val diff = result.optInt("planDifferenceKcal")
            if (kotlin.math.abs(diff) >= 50) {
                lines += "Текущий план ${result.optInt("currentPlanKcal")} ккал — " +
                    (if (diff < 0) "уменьшить на ${-diff}" else "можно увеличить на $diff")
            }
        }
        lines += "Вес по тренду ${kg(result.optDouble("trendWeightKg"))} кг · факт ${rate(result.optDouble("actualRateKgWeek"))} · " +
            "в среднем ${result.optInt("avgIntakeKcal")} ккал"
        val goal = result.optDouble("goalWeightKg", Double.NaN)
        if (!goal.isNaN()) {
            date(result.optString("goalDateAtTarget"))?.let { lines += "${kg(goal)} кг — к $it при норме" }
            date(result.optString("goalDateAtActual"))?.let { lines += "при нынешнем темпе — к $it" }
        }
        result.optJSONArray("notes")?.let { notes ->
            for (i in 0 until notes.length()) lines += "• " + notes.optString(i)
        }
        return lines
    }

    /** Next Monday 09:00 strictly after [now]. */
    fun nextWeeklyReport(now: ZonedDateTime, at: LocalTime = LocalTime.of(9, 0)): ZonedDateTime {
        val thisMonday = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val candidate = ZonedDateTime.of(thisMonday, at, now.zone)
        return if (candidate.isAfter(now)) candidate else candidate.plusWeeks(1)
    }
}
