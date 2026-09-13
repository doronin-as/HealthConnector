package ru.doronin.healthconnector

import android.content.Context
import java.time.LocalDate
import java.time.Period
import kotlin.math.roundToInt

enum class MiScaleSex { MALE, FEMALE }

data class MiScaleProfile(val heightCm: Double, val birthDate: LocalDate, val sex: MiScaleSex) {
    fun ageAt(date: LocalDate): Int = Period.between(birthDate, date).years.coerceIn(13, 99)

    companion object {
        const val PREF_HEIGHT_CM = "scale_profile_height_cm"
        const val PREF_BIRTH_DATE = "scale_profile_birth_date"
        const val PREF_SEX = "scale_profile_sex"

        fun fromPrefs(context: Context): MiScaleProfile? {
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val height = prefs.getString(PREF_HEIGHT_CM, null)?.toDoubleOrNull() ?: return null
            val birthDate = prefs.getString(PREF_BIRTH_DATE, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
            val sex = when (prefs.getString(PREF_SEX, null)) {
                "male" -> MiScaleSex.MALE
                "female" -> MiScaleSex.FEMALE
                else -> return null
            }
            if (height !in 100.0..220.0) return null
            return MiScaleProfile(height, birthDate, sex)
        }
    }
}

data class MiScaleBodyMetricsResult(
    val bmi: Double,
    val bodyFatPercent: Double,
    val fatMassKg: Double,
    val waterPercent: Double,
    val waterMassKg: Double,
    val muscleMassKg: Double,
    val musclePercent: Double,
    val leanBodyMassKg: Double,
    val boneMassKg: Double,
    val visceralFat: Double,
    val proteinPercent: Double,
    val basalMetabolicRateKcal: Double
)

/** Best-effort reverse-engineered metrics for Mi Body Composition Scale 2. */
object MiScaleBodyMetrics {
    fun calculate(weightKg: Double, impedanceOhm: Int, profile: MiScaleProfile, measuredDate: LocalDate): MiScaleBodyMetricsResult? {
        val age = profile.ageAt(measuredDate)
        val height = profile.heightCm
        if (weightKg !in 10.0..200.0 || impedanceOhm !in 1..3000 || height !in 100.0..220.0) return null

        val lbm = lbmCoefficient(height, weightKg, impedanceOhm, age)
        val fat = bodyFatPercent(profile.sex, age, height, weightKg, lbm)
        val water = waterPercent(fat)
        val bone = boneMass(profile.sex, lbm)
        val muscle = muscleMass(profile.sex, weightKg, fat, bone)
        val fatMass = weightKg * fat / 100.0
        val waterMass = weightKg * water / 100.0
        val leanMass = (weightKg - fatMass).coerceAtLeast(0.0)
        val musclePct = (muscle / weightKg * 100.0).coerceIn(0.0, 100.0)
        val protein = (musclePct - water).coerceIn(5.0, 32.0)
        val bmi = (weightKg / ((height / 100.0) * (height / 100.0))).coerceIn(10.0, 90.0)
        val visceral = visceralFat(profile.sex, age, height, weightKg)
        val bmr = when (profile.sex) {
            MiScaleSex.MALE -> 10.0 * weightKg + 6.25 * height - 5.0 * age + 5.0
            MiScaleSex.FEMALE -> 10.0 * weightKg + 6.25 * height - 5.0 * age - 161.0
        }.coerceIn(500.0, 5000.0)

        return MiScaleBodyMetricsResult(bmi.r2(), fat.r2(), fatMass.r2(), water.r2(), waterMass.r2(), muscle.r2(), musclePct.r2(), leanMass.r2(), bone.r2(), visceral.r2(), protein.r2(), bmr.r2())
    }

    private fun lbmCoefficient(height: Double, weight: Double, impedance: Int, age: Int): Double {
        var lbm = (height * 9.058 / 100.0) * (height / 100.0)
        lbm += weight * 0.32 + 12.226
        lbm -= impedance * 0.0068
        lbm -= age * 0.0542
        return lbm
    }

    private fun bodyFatPercent(sex: MiScaleSex, age: Int, height: Double, weight: Double, lbm: Double): Double {
        val subtract = when {
            sex == MiScaleSex.FEMALE && age <= 49 -> 9.25
            sex == MiScaleSex.FEMALE && age > 49 -> 7.25
            else -> 0.8
        }
        var coefficient = 1.0
        if (sex == MiScaleSex.MALE && weight < 61.0) coefficient = 0.98
        else if (sex == MiScaleSex.FEMALE && weight > 60.0) { coefficient = 0.96; if (height > 160.0) coefficient *= 1.03 }
        else if (sex == MiScaleSex.FEMALE && weight < 50.0) { coefficient = 1.02; if (height > 160.0) coefficient *= 1.03 }
        var result = (1.0 - (((lbm - subtract) * coefficient) / weight)) * 100.0
        if (result > 63.0) result = 75.0
        return result.coerceIn(5.0, 75.0)
    }

    private fun waterPercent(bodyFatPercent: Double): Double {
        var base = (100.0 - bodyFatPercent) * 0.7
        val coefficient = if (base <= 50.0) 1.02 else 0.98
        if (base * coefficient >= 65.0) base = 75.0
        return (base * coefficient).coerceIn(35.0, 75.0)
    }

    private fun boneMass(sex: MiScaleSex, lbm: Double): Double {
        val base = if (sex == MiScaleSex.FEMALE) 0.245691014 else 0.18016894
        var bone = (base - lbm * 0.05158) * -1.0
        bone += if (bone > 2.2) 0.1 else -0.1
        if (sex == MiScaleSex.FEMALE && bone > 5.1) bone = 8.0
        if (sex == MiScaleSex.MALE && bone > 5.2) bone = 8.0
        return bone.coerceIn(0.5, 8.0)
    }

    private fun muscleMass(sex: MiScaleSex, weight: Double, fat: Double, bone: Double): Double {
        var muscle = weight - ((fat * 0.01) * weight) - bone
        if (sex == MiScaleSex.FEMALE && muscle >= 84.0) muscle = 120.0
        if (sex == MiScaleSex.MALE && muscle >= 93.5) muscle = 120.0
        return muscle.coerceIn(10.0, 120.0)
    }

    private fun visceralFat(sex: MiScaleSex, age: Int, height: Double, weight: Double): Double {
        val value = if (sex == MiScaleSex.FEMALE) {
            if (weight > (13.0 - height * 0.5) * -1.0) {
                val denominator = ((height * 1.45) + (height * 0.1158) * height) - 120.0
                (weight * 500.0 / denominator - 6.0) + age * 0.07
            } else {
                val sub = 0.691 + height * -0.0024 + height * -0.0024
                (((height * 0.027) - sub * weight) * -1.0) + age * 0.07 - age
            }
        } else {
            if (height < weight * 1.6) {
                val sub = ((height * 0.4) - (height * (height * 0.0826))) * -1.0
                (weight * 305.0 / (sub + 48.0)) - 2.9 + age * 0.15
            } else {
                val sub = 0.765 + height * -0.0015
                (((height * 0.143) - weight * sub) * -1.0) + age * 0.15 - 5.0
            }
        }
        return value.coerceIn(1.0, 50.0)
    }

    private fun Double.r2(): Double = (this * 100.0).roundToInt() / 100.0
}
