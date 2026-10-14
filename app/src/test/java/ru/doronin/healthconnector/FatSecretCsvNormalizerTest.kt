package ru.doronin.healthconnector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FatSecretCsvNormalizerTest {

    @Test
    fun `all known optional slots map to generic snack`() {
        val labels = listOf(
            "До Завтрака", "После Завтрака", "До Обеда", "До Ужина", "После Ужина",
            "Before Breakfast", "After Breakfast", "Before Lunch", "Before Dinner", "After Dinner",
            "Перекусы/Другое", "Snacks & Other"
        )
        labels.forEach { label ->
            assertEquals("Перекус/Другое", FatSecretCsvNormalizer.canonicalMealLabel(label))
        }
        assertEquals("Полдник", FatSecretCsvNormalizer.canonicalMealLabel("Полдник"))
    }

    @Test
    fun `manual and share normalizer merge optional slots without losing nutrition or foods`() {
        val source = """
            # FatSecret export
            # Report Details
            "September 1, 2026",150,5,,10,,,7
            До Завтрака,100,2,,6,,,5
            "Yogurt, vanilla",100,2,,6,,,5
            Перекус/Другое,50,3,,4,,,2
            Apple,50,3,,4,,,2
            Всего,150,5,,10,,,7
        """.trimIndent()

        val result = FatSecretCsvNormalizer.normalize(source)
        val rows = FatSecretCsvNormalizer.parseCsvRows(result.csvText)
        val snackHeaders = rows.filter { it.firstOrNull() == "Перекус/Другое" }

        assertEquals(2, result.normalizedMealLabels)
        assertEquals(1, snackHeaders.size)
        assertEquals("150", snackHeaders.single()[1])
        assertEquals("5", snackHeaders.single()[2])
        assertEquals("10", snackHeaders.single()[4])
        assertEquals("7", snackHeaders.single()[7])
        assertTrue(result.csvText.contains("Yogurt, vanilla"))
        assertTrue(result.csvText.contains("Apple"))
    }

    @Test
    fun `quoted fields and escaped quotes survive normalization`() {
        val rows = mutableListOf(
            mutableListOf("a,b", "quoted \"name\"", " tail ")
        )
        val serialized = FatSecretCsvNormalizer.serializeCsvRows(rows)
        assertEquals(rows, FatSecretCsvNormalizer.parseCsvRows(serialized))
    }

    @Test
    fun `formula-like food names reach the server verbatim for sheet escaping`() {
        // The app must not strip, evaluate or pre-escape these: Apps Script's
        // sheetSafeExternalText_ is the single escaping boundary before Sheets.
        val foods = listOf(
            "=HYPERLINK(\"https://evil.example\",\"click\")",
            "+7 (999) 123-45-67",
            "-2+3",
            "@SUM(A1:A9)",
            "=1+1, with comma"
        )
        val foodRows = foods.joinToString("\n") { name ->
            "\"${name.replace("\"", "\"\"")}\",10,1,,1,,,1"
        }
        val source = "# FatSecret export\n# Report Details\n" +
            "\"September 1, 2026\",50,5,,5,,,5\n" +
            "Завтрак,50,5,,5,,,5\n" +
            foodRows + "\n" +
            "Всего,50,5,,5,,,5"

        val result = FatSecretCsvNormalizer.normalize(source)
        val names = FatSecretCsvNormalizer.parseCsvRows(result.csvText).map { it.first() }

        foods.forEach { assertTrue("missing $it", names.contains(it)) }
    }

    @Test
    fun `non FatSecret payload is left untouched`() {
        val source = "a,b\n1,2\n"
        val result = FatSecretCsvNormalizer.normalize(source)
        assertEquals(source, result.csvText)
        assertEquals(0, result.normalizedMealLabels)
        assertFalse(result.csvText.contains("Перекус/Другое"))
    }
}
