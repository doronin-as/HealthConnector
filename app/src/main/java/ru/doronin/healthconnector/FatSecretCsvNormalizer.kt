package ru.doronin.healthconnector

import java.math.BigDecimal

/**
 * Pure FatSecret CSV normalization shared by ShareCsvActivity and the manual
 * file picker. Keeping this code Android-free makes the fragile parser and
 * meal-slot merge rules directly unit-testable.
 */
object FatSecretCsvNormalizer {

    data class Result(
        val csvText: String,
        val normalizedMealLabels: Int
    )

    private data class MealBlock(
        val header: MutableList<String>,
        val body: MutableList<MutableList<String>>
    )

    /**
     * Optional FatSecret slots are folded into the dashboard's generic snack
     * bucket. If several source slots map to the same canonical meal, their
     * nutrition header values and food rows are merged before upload.
     */
    fun normalize(csvText: String): Result {
        val rows = parseCsvRows(csvText)
        val reportStart = rows.indexOfFirst {
            it.firstOrNull()?.trim() == "# Report Details"
        }
        if (reportStart < 0) return Result(csvText, 0)

        val output = mutableListOf<MutableList<String>>()
        for (index in 0..reportStart) output.add(rows[index].toMutableList())

        var changes = 0
        var dayHeader: MutableList<String>? = null
        val dayExtras = mutableListOf<MutableList<String>>()
        val dayMeals = linkedMapOf<String, MealBlock>()

        fun flushDay() {
            val header = dayHeader ?: return
            output.add(header)
            output.addAll(dayExtras.map { it.toMutableList() })
            dayMeals.values.forEach { block ->
                output.add(block.header)
                output.addAll(block.body)
            }
            dayHeader = null
            dayExtras.clear()
            dayMeals.clear()
        }

        var i = reportStart + 1
        while (i < rows.size) {
            val row = rows[i]
            val label = row.firstOrNull().orEmpty().trim()

            if (isReportTotalLabel(label)) {
                flushDay()
                while (i < rows.size) {
                    output.add(rows[i].toMutableList())
                    i++
                }
                break
            }

            if (isFatSecretDateLabel(label)) {
                flushDay()
                dayHeader = row.toMutableList()
                i++
                continue
            }

            val canonicalMeal = if (dayHeader != null) canonicalMealLabel(label) else null
            if (canonicalMeal != null) {
                val header = row.toMutableList()
                if (header.isEmpty()) header.add(canonicalMeal) else header[0] = canonicalMeal
                if (canonicalMeal != label) changes++

                val body = mutableListOf<MutableList<String>>()
                i++
                while (i < rows.size) {
                    val nextRow = rows[i]
                    val nextLabel = nextRow.firstOrNull().orEmpty().trim()
                    if (
                        isReportTotalLabel(nextLabel) ||
                        isFatSecretDateLabel(nextLabel) ||
                        canonicalMealLabel(nextLabel) != null
                    ) {
                        break
                    }
                    if (!isSubtotalLabel(nextLabel)) {
                        body.add(nextRow.toMutableList())
                    }
                    i++
                }

                val existing = dayMeals[canonicalMeal]
                if (existing == null) {
                    dayMeals[canonicalMeal] = MealBlock(header, body)
                } else {
                    mergeMealHeaders(existing.header, header)
                    existing.body.addAll(body)
                    changes++
                }
                continue
            }

            if (dayHeader == null) {
                output.add(row.toMutableList())
            } else if (!isSubtotalLabel(label)) {
                dayExtras.add(row.toMutableList())
            }
            i++
        }

        if (i >= rows.size) flushDay()
        return Result(serializeCsvRows(output), changes)
    }

    internal fun canonicalMealLabel(label: String): String? {
        val compact = label
            .trim()
            .lowercase()
            .replace(Regex("""\s*/\s*"""), "/")
            .replace(Regex("""\s+"""), " ")

        return when (compact) {
            "завтрак", "breakfast" -> "Завтрак"
            "обед", "lunch" -> "Обед"
            "полдник", "afternoon snack" -> "Полдник"
            "ужин", "dinner" -> "Ужин"

            "перекус/другое",
            "перекусы/другое",
            "закуски/другое",
            "снэки/другое",
            "snacks/other",
            "snack/other",
            "snacks & other",
            "snack & other",
            "до завтрака",
            "после завтрака",
            "до обеда",
            "до ужина",
            "после ужина",
            "before breakfast",
            "after breakfast",
            "before lunch",
            "before dinner",
            "after dinner" -> "Перекус/Другое"

            else -> null
        }
    }

    internal fun mergeMealHeaders(target: MutableList<String>, incoming: List<String>) {
        val nutritionColumns = intArrayOf(1, 2, 4, 7)
        nutritionColumns.forEach { index ->
            val incomingValue = incoming.getOrNull(index)?.let(::parseCsvNumber) ?: return@forEach
            while (target.size <= index) target.add("")
            val currentValue = parseCsvNumber(target[index]) ?: 0.0
            target[index] = formatCsvNumber(currentValue + incomingValue)
        }
    }

    private fun parseCsvNumber(value: String): Double? {
        val normalized = value
            .trim()
            .replace("\u00A0", "")
            .replace(" ", "")
            .replace(',', '.')
        if (normalized.isBlank()) return null
        return normalized.toDoubleOrNull()
    }

    private fun formatCsvNumber(value: Double): String =
        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun isFatSecretDateLabel(label: String): Boolean = FATSECRET_DATE_REGEX.containsMatchIn(label)

    private fun isSubtotalLabel(label: String): Boolean {
        val value = label.trim().lowercase()
        return value == "итого" || value == "daily total"
    }

    private fun isReportTotalLabel(label: String): Boolean {
        val value = label.trim().lowercase()
        return value == "всего" || value == "total"
    }

    internal fun parseCsvRows(text: String): MutableList<MutableList<String>> {
        val source = text.removePrefix("\uFEFF")
        val rows = mutableListOf<MutableList<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0

        fun finishField() {
            row.add(field.toString())
            field.setLength(0)
        }

        fun finishRow() {
            finishField()
            rows.add(row)
            row = mutableListOf()
        }

        while (i < source.length) {
            val ch = source[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < source.length && source[i + 1] == '"') {
                        field.append('"')
                        i += 2
                        continue
                    }
                    inQuotes = false
                } else {
                    field.append(ch)
                }
                i++
                continue
            }

            when (ch) {
                '"' -> inQuotes = true
                ',' -> finishField()
                '\r' -> {
                    if (i + 1 < source.length && source[i + 1] == '\n') i++
                    finishRow()
                }
                '\n' -> finishRow()
                else -> field.append(ch)
            }
            i++
        }

        if (field.isNotEmpty() || row.isNotEmpty()) finishRow()
        return rows
    }

    internal fun serializeCsvRows(rows: List<List<String>>): String = rows.joinToString("\r\n") { row ->
        row.joinToString(",") { field ->
            val needsQuotes = field.contains(',') || field.contains('"') ||
                field.contains('\r') || field.contains('\n') ||
                field.startsWith(' ') || field.endsWith(' ')
            if (needsQuotes) "\"${field.replace("\"", "\"\"")}\"" else field
        }
    }

    private val FATSECRET_DATE_REGEX = Regex(
        "(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря|" +
            "january|february|march|april|may|june|july|august|september|october|november|december)" +
            "\\s+\\d{1,2}\\s*,?\\s*\\d{4}",
        RegexOption.IGNORE_CASE
    )
}
