package ru.doronin.healthconnector.water

/** Pure formatting helpers (unit-tested). */
object WaterFormat {
    /** 1250 → "1,25", 2000 → "2", 750 → "0,75", 1300 → "1,3". */
    fun liters(ml: Int): String {
        val whole = ml / 1000
        val fraction = (ml % 1000) / 10
        if (fraction == 0) return whole.toString()
        val digits = fraction.toString().padStart(2, '0').trimEnd('0')
        return "$whole,$digits"
    }

    fun progressPercent(totalMl: Int, goalMl: Int): Int =
        if (goalMl <= 0) 0 else ((totalMl.toLong() * 100) / goalMl).toInt().coerceIn(0, 100)

    fun summary(totalMl: Int, goalMl: Int): String = "${liters(totalMl)} / ${liters(goalMl)} л"
}
