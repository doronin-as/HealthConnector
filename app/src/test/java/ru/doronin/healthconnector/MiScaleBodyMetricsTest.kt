package ru.doronin.healthconnector

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MiScaleBodyMetricsTest {
    @Test
    fun calculatesPlausibleMetrics() {
        val profile = MiScaleProfile(
            heightCm = 180.0,
            birthDate = LocalDate.of(1995, 1, 1),
            sex = MiScaleSex.MALE
        )
        val result = MiScaleBodyMetrics.calculate(
            weightKg = 90.0,
            impedanceOhm = 500,
            profile = profile,
            measuredDate = LocalDate.of(2026, 9, 13)
        )
        assertNotNull(result)
        result!!
        assertTrue(result.bmi in 10.0..90.0)
        assertTrue(result.bodyFatPercent in 5.0..75.0)
        assertTrue(result.waterPercent in 35.0..75.0)
        assertTrue(result.boneMassKg in 0.5..8.0)
        assertTrue(result.visceralFat in 1.0..50.0)
        assertTrue(result.leanBodyMassKg > 0.0)
    }
}
