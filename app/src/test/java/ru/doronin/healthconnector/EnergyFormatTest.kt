package ru.doronin.healthconnector

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.doronin.healthconnector.energy.EnergyFormat
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class EnergyFormatTest {
    private val moscow = ZoneId.of("Europe/Moscow")

    @Test
    fun weeklyReportIsNextMondayNine() {
        val saturday = ZonedDateTime.of(2026, 10, 24, 14, 0, 0, 0, moscow)
        assertEquals(ZonedDateTime.of(2026, 10, 26, 9, 0, 0, 0, moscow), EnergyFormat.nextWeeklyReport(saturday))
        val mondayEarly = ZonedDateTime.of(2026, 10, 26, 8, 0, 0, 0, moscow)
        assertEquals(ZonedDateTime.of(2026, 10, 26, 9, 0, 0, 0, moscow), EnergyFormat.nextWeeklyReport(mondayEarly))
        val mondayAt = ZonedDateTime.of(2026, 10, 26, 9, 0, 0, 0, moscow)
        assertEquals(ZonedDateTime.of(2026, 11, 2, 9, 0, 0, 0, moscow), EnergyFormat.nextWeeklyReport(mondayAt, LocalTime.of(9, 0)))
    }

    @Test
    fun rateSigns() {
        assertEquals("−0,35 кг/нед", EnergyFormat.rate(-0.35))
        assertEquals("+0,10 кг/нед", EnergyFormat.rate(0.1))
        assertEquals("0,00 кг/нед", EnergyFormat.rate(0.0))
    }

    @Test
    fun summaryOfRealCalculation() {
        val result = JSONObject()
                .put("status", "ok").put("confidence", "high")
                .put("expenditureKcal", 2640).put("targetIntakeKcal", 2090).put("targetRateKgWeek", 0.5)
                .put("currentPlanKcal", 2255).put("planDifferenceKcal", -165)
                .put("trendWeightKg", 106.4).put("actualRateKgWeek", -0.35).put("avgIntakeKcal", 2250)
                .put("goalWeightKg", 90).put("goalDateAtTarget", "2027-06-11").put("goalDateAtActual", "2027-09-14")
                .put("notes", JSONArray())
        val text = EnergyFormat.summary(result).joinToString("\n")
        assertTrue(text, text.contains("Расход ≈ 2640"))
        assertTrue(text, text.contains("Норма: 2090 ккал/день для −0,50 кг/нед"))
        assertTrue(text, text.contains("уменьшить на 165"))
        assertTrue(text, text.contains("11 июня 2027"))
    }
}
