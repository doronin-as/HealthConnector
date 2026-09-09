from pathlib import Path
import re

ROOT = Path('.')


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding='utf-8')


def write(path: str, text: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding='utf-8')


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    if old not in text:
        raise SystemExit(f'Expected text not found in {path}: {old[:120]!r}')
    write(path, text.replace(old, new, 1))


def remove_private_permission_helper(path: str) -> None:
    text = read(path)
    pattern = re.compile(
        r'\n    private fun isPermissionFailure\(error: Throwable\): Boolean \{.*?\n    \}\n',
        re.S,
    )
    text, count = pattern.subn('\n', text, count=1)
    if count != 1:
        raise SystemExit(f'isPermissionFailure helper not found exactly once in {path}')
    text = text.replace(
        'isPermissionFailure(error)',
        'HealthConnectErrorUtils.isPermissionFailure(error)',
    )
    write(path, text)


# ---------------------------------------------------------------------------
# 1/2. Shared FatSecret normalizer + manual-import parity + regression tests.
# ---------------------------------------------------------------------------
normalizer = r'''package ru.doronin.healthconnector

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
'''
write('app/src/main/java/ru/doronin/healthconnector/FatSecretCsvNormalizer.kt', normalizer)

share_path = 'app/src/main/java/ru/doronin/healthconnector/ShareCsvActivity.kt'
share = read(share_path)
share = share.replace('import java.math.BigDecimal\n', '', 1)
meal_block = '''    private data class MealBlock(\n        val header: MutableList<String>,\n        val body: MutableList<MutableList<String>>\n    )\n\n'''
if meal_block not in share:
    raise SystemExit('ShareCsvActivity MealBlock not found')
share = share.replace(meal_block, '', 1)
old_call = '''                val normalized = normalizeFatSecretCsvForServer(rawCsvText)\n                val csvText = normalized.first\n                val normalizedMealLabels = normalized.second\n'''
new_call = '''                val normalized = FatSecretCsvNormalizer.normalize(rawCsvText)\n                val csvText = normalized.csvText\n                val normalizedMealLabels = normalized.normalizedMealLabels\n'''
if old_call not in share:
    raise SystemExit('ShareCsvActivity normalizer call not found')
share = share.replace(old_call, new_call, 1)
normalizer_start = share.find('    /**\n     * The FatSecret diary can expose optional slots')
extract_start = share.find('    private fun extractSharedText', normalizer_start)
if normalizer_start < 0 or extract_start < 0:
    raise SystemExit('ShareCsvActivity embedded normalizer boundaries not found')
share = share[:normalizer_start] + share[extract_start:]
companion_start = share.rfind('\n    companion object {')
if companion_start < 0:
    raise SystemExit('ShareCsvActivity companion object not found')
share = share[:companion_start] + '\n}'
write(share_path, share)

streaming_path = 'app/src/main/java/ru/doronin/healthconnector/StreamingMainActivity.kt'
streaming = read(streaming_path)
old_manual = '''                require(csvText.isNotBlank()) { "CSV пустой" }\n                require(csvText.length <= 5_000_000) { "CSV слишком большой: максимум 5 МБ" }\n\n                binding.status.text = "Отправляю CSV в Google Таблицу…"\n                val body = JSONObject().apply {\n                    put("token", settings.token)\n                    put("action", "fatsecretCsv")\n                    put("fileName", fileName)\n                    put("csvText", csvText)\n                    put("uploadedAt", Instant.now().toString())\n                }\n'''
new_manual = '''                require(csvText.isNotBlank()) { "CSV пустой" }\n                require(csvText.length <= 5_000_000) { "CSV слишком большой: максимум 5 МБ" }\n\n                val normalized = FatSecretCsvNormalizer.normalize(csvText)\n                binding.status.text = if (normalized.normalizedMealLabels > 0) {\n                    "Объединяю дополнительные приёмы пищи…"\n                } else {\n                    "Отправляю CSV в Google Таблицу…"\n                }\n                val body = JSONObject().apply {\n                    put("token", settings.token)\n                    put("action", "fatsecretCsv")\n                    put("fileName", fileName)\n                    put("csvText", normalized.csvText)\n                    put("uploadedAt", Instant.now().toString())\n                    put("sourceMimeType", contentResolver.getType(uri).orEmpty())\n                    put("normalizedMealLabels", normalized.normalizedMealLabels)\n                }\n'''
if old_manual not in streaming:
    raise SystemExit('Manual FatSecret import block not found')
streaming = streaming.replace(old_manual, new_manual, 1)
write(streaming_path, streaming)

normalizer_test = r'''package ru.doronin.healthconnector

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
    fun `non FatSecret payload is left untouched`() {
        val source = "a,b\n1,2\n"
        val result = FatSecretCsvNormalizer.normalize(source)
        assertEquals(source, result.csvText)
        assertEquals(0, result.normalizedMealLabels)
        assertFalse(result.csvText.contains("Перекус/Другое"))
    }
}
'''
write('app/src/test/java/ru/doronin/healthconnector/FatSecretCsvNormalizerTest.kt', normalizer_test)


# ---------------------------------------------------------------------------
# 1. Google Sheets formula injection + token comparison hardening.
# 5. Remove dead Apps Script diary functions.
# ---------------------------------------------------------------------------
code_path = 'apps-script/Code.gs'
code = read(code_path)
old_token = '''    const expectedToken = PropertiesService.getScriptProperties().getProperty('API_TOKEN');\n    if (!expectedToken || payload.token !== expectedToken) {\n      throw new Error('Неверный API-токен');\n    }\n'''
new_token = '''    const expectedToken = PropertiesService.getScriptProperties().getProperty('API_TOKEN');\n    if (!expectedToken || !constantTimeTokenEquals_(payload.token, expectedToken)) {\n      throw new Error('Неверный API-токен');\n    }\n'''
if old_token not in code:
    raise SystemExit('Code.gs token comparison block not found')
code = code.replace(old_token, new_token, 1)

old_food = "      meal.foods.join(' | '),\n"
new_food = "      sheetSafeExternalText_(meal.foods.join(' | ')),\n"
if old_food not in code:
    raise SystemExit('Code.gs food write not found')
code = code.replace(old_food, new_food, 1)

# Remove dead functions by stable function-name boundaries.
start = code.find('function syncDiaryFoodSnack_(spreadsheet, days) {')
end = code.find('function copyRowFormat_(sheet, targetRow, width) {', start)
if start < 0 or end < 0:
    raise SystemExit('syncDiaryFoodSnack_ boundaries not found')
code = code[:start] + code[end:]

start = code.find('function syncDiary_(spreadsheet, days) {')
end = code.find('function findHeader_(sheet) {', start)
if start < 0 or end < 0:
    raise SystemExit('syncDiary_ boundaries not found')
code = code[:start] + code[end:]

helper_marker = 'function validateHealthPayload_(payload) {'
helper_pos = code.find(helper_marker)
if helper_pos < 0:
    raise SystemExit('Code.gs helper insertion point not found')
security_helpers = r'''function constantTimeTokenEquals_(actual, expected) {
  const actualText = String(actual == null ? '' : actual);
  const expectedText = String(expected == null ? '' : expected);
  if (!expectedText || actualText.length !== expectedText.length) return false;

  // Compare fixed-size HMAC digests without an early exit. The expected token
  // itself never becomes an observable comparison prefix.
  const marker = 'HealthConnector API token verification v1';
  const actualDigest = Utilities.computeHmacSha256Signature(marker, actualText);
  const expectedDigest = Utilities.computeHmacSha256Signature(marker, expectedText);
  let diff = 0;
  for (let i = 0; i < expectedDigest.length; i++) {
    diff |= (actualDigest[i] & 0xff) ^ (expectedDigest[i] & 0xff);
  }
  return diff === 0;
}

/**
 * Google Sheets interprets external text beginning with =, +, - or @ as a
 * formula. Prefixing an apostrophe forces literal text while keeping the
 * displayed value readable. Apply this at the final write boundary.
 */
function sheetSafeExternalText_(value) {
  const text = String(value == null ? '' : value);
  return /^[=+\-@]/.test(text) ? `'${text}` : text;
}

'''
code = code[:helper_pos] + security_helpers + code[helper_pos:]
write(code_path, code)


# ---------------------------------------------------------------------------
# 3. Real non-debuggable release build signed with the stable certificate.
# ---------------------------------------------------------------------------
gradle_path = 'app/build.gradle.kts'
gradle = read(gradle_path)
gradle = gradle.replace('create("stableDebug")', 'create("stableRelease")', 1)
gradle = gradle.replace('versionCode = 21', 'versionCode = 22', 1)
gradle = gradle.replace('versionName = "1.6.7"', 'versionName = "1.6.8"', 1)
old_build_types = '''    buildTypes {\n        getByName("debug") {\n            signingConfigs.findByName("stableDebug")?.let { signingConfig = it }\n        }\n    }\n'''
new_build_types = '''    buildTypes {\n        getByName("release") {\n            isDebuggable = false\n            isMinifyEnabled = true\n            signingConfigs.findByName("stableRelease")?.let { signingConfig = it }\n            proguardFiles(\n                getDefaultProguardFile("proguard-android-optimize.txt"),\n                "proguard-rules.pro"\n            )\n        }\n    }\n'''
if old_build_types not in gradle:
    raise SystemExit('Gradle debug buildTypes block not found')
gradle = gradle.replace(old_build_types, new_build_types, 1)
if 'testImplementation("junit:junit:4.13.2")' not in gradle:
    gradle = gradle.replace(
        '    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")\n',
        '    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")\n    testImplementation("junit:junit:4.13.2")\n',
        1,
    )
write(gradle_path, gradle)
write(
    'app/proguard-rules.pro',
    '# Project-specific R8 rules. AndroidX dependencies provide their consumer rules.\n'
    '# Keep this file explicit so release minification can be tightened incrementally.\n',
)

android_workflow = r'''name: Build Android APK

on:
  push:
    branches: [ main ]
  pull_request:
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    env:
      HC_SIGNING_KEY_B64: ${{ secrets.HC_SIGNING_KEY_B64 }}
      HC_SIGNING_STORE_FILE: /tmp/healthconnector-release.keystore
      HC_SIGNING_STORE_PASSWORD: ${{ secrets.HC_SIGNING_STORE_PASSWORD }}
      HC_SIGNING_KEY_ALIAS: ${{ secrets.HC_SIGNING_KEY_ALIAS }}
      HC_SIGNING_KEY_PASSWORD: ${{ secrets.HC_SIGNING_KEY_PASSWORD }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.11.1'
      - name: Validate security invariants
        run: |
          cp apps-script/Code.gs /tmp/HealthConnectorCode.js
          node --check /tmp/HealthConnectorCode.js
          grep -F 'schemaVersion: 3' apps-script/Code.gs
          grep -F "payload.action === 'healthSyncV3'" apps-script/Code.gs
          grep -F "payload.action === 'healthChangesV3'" apps-script/Code.gs
          ! grep -F 'function syncDiary_(' apps-script/Code.gs
          ! grep -F 'function syncDiaryFoodSnack_(' apps-script/Code.gs
          grep -F "sheetSafeExternalText_(meal.foods.join(' | '))" apps-script/Code.gs
          grep -F 'constantTimeTokenEquals_(payload.token, expectedToken)' apps-script/Code.gs
          ! grep -F 'apiToken:' apps-script/Code.gs
          ! grep -R --include='*.kt' -F '.putString("token", token)' app/src/main/java
          ! grep -R --include='*.kt' -F 'prefs.getString("token"' app/src/main/java
          ! grep -R --include='*.kt' -F 'HTTP $code: $response' app/src/main/java
          grep -F 'FatSecretCsvNormalizer.normalize(rawCsvText)' app/src/main/java/ru/doronin/healthconnector/ShareCsvActivity.kt
          grep -F 'FatSecretCsvNormalizer.normalize(csvText)' app/src/main/java/ru/doronin/healthconnector/StreamingMainActivity.kt
          ! grep -F 'android.intent.category.BROWSABLE' app/src/main/AndroidManifest.xml
          grep -F 'android:usesCleartextTraffic="false"' app/src/main/AndroidManifest.xml
          grep -F 'isDebuggable = false' app/build.gradle.kts
          grep -F 'isMinifyEnabled = true' app/build.gradle.kts
          grep -F 'stableRelease' app/build.gradle.kts
          test ! -e .github/signing/healthconnector-dev.keystore.b64
      - name: Run unit tests
        run: gradle :app:testReleaseUnitTest --stacktrace
      - name: Restore stable release signing key
        if: env.HC_SIGNING_KEY_B64 != ''
        run: |
          printf '%s' "$HC_SIGNING_KEY_B64" | base64 --decode > "$HC_SIGNING_STORE_FILE"
          chmod 600 "$HC_SIGNING_STORE_FILE"
          keytool -list -v \
            -keystore "$HC_SIGNING_STORE_FILE" \
            -storepass "${HC_SIGNING_STORE_PASSWORD:-android}" \
            -alias "${HC_SIGNING_KEY_ALIAS:-androiddebugkey}" | grep -F "31:9C:0E:19:44:72:7B:A1:12:8E:6A:83:40:BD:68:12:3B:9E:8B:2C:F3:28:B0:44:3E:1E:4A:03:87:A8:5A:80"
      - name: Build release APK
        run: gradle :app:assembleRelease --stacktrace
      - name: Verify stable release APK signature
        if: env.HC_SIGNING_KEY_B64 != ''
        run: |
          APKSIGNER=$(find "$ANDROID_HOME/build-tools" -name apksigner | sort -V | tail -1)
          "$APKSIGNER" verify --print-certs app/build/outputs/apk/release/app-release.apk | tee /tmp/apk-certs.txt
          grep -F "319c0e1944727ba1128e6a8340bd68123b9e8b2cf328b0443e1e4a0387a85a80" /tmp/apk-certs.txt
      - name: Explain unsigned CI build
        if: env.HC_SIGNING_KEY_B64 == ''
        run: echo 'Stable signing secret is not configured; release compile/tests passed but no installable stable artifact will be published.'
      - name: Upload stable release APK
        if: env.HC_SIGNING_KEY_B64 != ''
        uses: actions/upload-artifact@v4
        with:
          name: health-connector-apk
          path: app/build/outputs/apk/release/app-release.apk
'''
write('.github/workflows/android.yml', android_workflow)


# ---------------------------------------------------------------------------
# 4. Deduplicate Health Connect permission-failure classification.
# ---------------------------------------------------------------------------
permission_util = r'''package ru.doronin.healthconnector

internal object HealthConnectErrorUtils {
    fun isPermissionFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        repeat(8) {
            val value = current ?: return false
            if (value is SecurityException) return true

            val message = value.message.orEmpty()
            if (
                message.contains("SecurityException", ignoreCase = true) ||
                message.contains("does not have permission", ignoreCase = true) ||
                message.contains("permission to read data", ignoreCase = true) ||
                message.contains("permission denied", ignoreCase = true)
            ) {
                return true
            }
            current = value.cause
        }
        return false
    }
}
'''
write('app/src/main/java/ru/doronin/healthconnector/HealthConnectErrorUtils.kt', permission_util)
for target in (
    'app/src/main/java/ru/doronin/healthconnector/HealthSyncStreamer.kt',
    'app/src/main/java/ru/doronin/healthconnector/HealthChangesTracker.kt',
    'app/src/main/java/ru/doronin/healthconnector/HealthAggregateReader.kt',
):
    remove_private_permission_helper(target)


# ---------------------------------------------------------------------------
# 6. Reuse day-level Health Connect reads when building workout summaries.
#    A cross-midnight workout keeps the old direct-query fallback so data after
#    dayEnd is not silently lost.
# ---------------------------------------------------------------------------
streamer_path = 'app/src/main/java/ru/doronin/healthconnector/HealthSyncStreamer.kt'
streamer = read(streamer_path)
cache_declarations_marker = '''        val powerStats = Stats()\n\n        var sleepSummary = SleepAggregation.empty()\n'''
cache_declarations = '''        val powerStats = Stats()\n\n        // Keep the raw day-level records needed by workout summaries. This avoids\n        // N x record-type Health Connect reads when a day contains several workouts.\n        var dayStepsRecords: List<StepsRecord> = emptyList()\n        var dayDistanceRecords: List<DistanceRecord> = emptyList()\n        var dayActiveCaloriesRecords: List<ActiveCaloriesBurnedRecord> = emptyList()\n        var dayTotalCaloriesRecords: List<TotalCaloriesBurnedRecord> = emptyList()\n        var dayHeartRateRecords: List<HeartRateRecord> = emptyList()\n        var daySpeedRecords: List<SpeedRecord> = emptyList()\n        var dayStepCadenceRecords: List<StepsCadenceRecord> = emptyList()\n        var dayCyclingCadenceRecords: List<CyclingPedalingCadenceRecord> = emptyList()\n        var dayPowerRecords: List<PowerRecord> = emptyList()\n        var dayElevationRecords: List<ElevationGainedRecord> = emptyList()\n        var dayFloorsRecords: List<FloorsClimbedRecord> = emptyList()\n\n        var sleepSummary = SleepAggregation.empty()\n'''
if cache_declarations_marker not in streamer:
    raise SystemExit('Streamer cache declaration marker not found')
streamer = streamer.replace(cache_declarations_marker, cache_declarations, 1)

simple_day_reads = [
    ('StepsRecord', 'dayStepsRecords'),
    ('DistanceRecord', 'dayDistanceRecords'),
    ('ActiveCaloriesBurnedRecord', 'dayActiveCaloriesRecords'),
    ('TotalCaloriesBurnedRecord', 'dayTotalCaloriesRecords'),
    ('ElevationGainedRecord', 'dayElevationRecords'),
    ('FloorsClimbedRecord', 'dayFloorsRecords'),
]
for record_type, variable in simple_day_reads:
    old = f'            val records = safeReadAll<{record_type}>(dayStart, dayEnd)\n'
    new = f'            {variable} = safeReadAll<{record_type}>(dayStart, dayEnd)\n            val records = {variable}\n'
    if old not in streamer:
        raise SystemExit(f'Day read not found for {record_type}')
    streamer = streamer.replace(old, new, 1)

preferred_day_reads = [
    ('HeartRateRecord', 'dayHeartRateRecords'),
    ('SpeedRecord', 'daySpeedRecords'),
    ('StepsCadenceRecord', 'dayStepCadenceRecords'),
    ('CyclingPedalingCadenceRecord', 'dayCyclingCadenceRecords'),
    ('PowerRecord', 'dayPowerRecords'),
]
for record_type, variable in preferred_day_reads:
    old = f'            val records = preferBestSource(safeReadAll<{record_type}>(dayStart, dayEnd))\n'
    new = f'            {variable} = safeReadAll<{record_type}>(dayStart, dayEnd)\n            val records = preferBestSource({variable})\n'
    if old not in streamer:
        raise SystemExit(f'Preferred day read not found for {record_type}')
    streamer = streamer.replace(old, new, 1)

old_workout_loop = '''        for (record in workoutRecords) {\n            workouts.put(buildWorkoutJson(record, sources))\n        }\n'''
new_workout_loop = '''        val workoutDayRecords = WorkoutDayRecords(\n            steps = dayStepsRecords,\n            distance = dayDistanceRecords,\n            activeCalories = dayActiveCaloriesRecords,\n            totalCalories = dayTotalCaloriesRecords,\n            heartRate = dayHeartRateRecords,\n            speed = daySpeedRecords,\n            stepCadence = dayStepCadenceRecords,\n            cyclingCadence = dayCyclingCadenceRecords,\n            power = dayPowerRecords,\n            elevation = dayElevationRecords,\n            floors = dayFloorsRecords\n        )\n        for (record in workoutRecords) {\n            workouts.put(buildWorkoutJson(record, workoutDayRecords, dayEnd, sources))\n        }\n'''
if old_workout_loop not in streamer:
    raise SystemExit('Workout loop not found')
streamer = streamer.replace(old_workout_loop, new_workout_loop, 1)

build_start = streamer.find('    private suspend fun buildWorkoutJson(')
build_end = streamer.find('    private suspend fun postDeletedRecordIds(', build_start)
if build_start < 0 or build_end < 0:
    raise SystemExit('buildWorkoutJson boundaries not found')
new_build_workout = r'''    private suspend fun buildWorkoutJson(
        record: ExerciseSessionRecord,
        dayRecords: WorkoutDayRecords,
        dayEnd: Instant,
        sources: MutableSet<String>
    ): JSONObject {
        val start = record.startTime
        val end = record.endTime
        val crossesDayBoundary = end > dayEnd

        // For normal same-day sessions use records already fetched by syncDay. A rare
        // session crossing midnight falls back to a direct range read to preserve the
        // original behavior for the part that is outside the cached day.
        val steps = run {
            val candidate = if (crossesDayBoundary) safeReadAll<StepsRecord>(start, end)
            else dayRecords.steps.filter { it.startTime < end && it.endTime > start }
            val r = preferBestSource(candidate); addSources(r, sources); r.sumOf { it.count }
        }
        val distance = run {
            val candidate = if (crossesDayBoundary) safeReadAll<DistanceRecord>(start, end)
            else dayRecords.distance.filter { it.startTime < end && it.endTime > start }
            val r = preferBestSource(candidate); addSources(r, sources); r.sumOf { it.distance.inKilometers }
        }
        val activeCalories = run {
            val candidate = if (crossesDayBoundary) safeReadAll<ActiveCaloriesBurnedRecord>(start, end)
            else dayRecords.activeCalories.filter { it.startTime < end && it.endTime > start }
            val r = preferBestSource(candidate); addSources(r, sources); r.sumOf { it.energy.inKilocalories }
        }
        val totalCalories = run {
            val candidate = if (crossesDayBoundary) safeReadAll<TotalCaloriesBurnedRecord>(start, end)
            else dayRecords.totalCalories.filter { it.startTime < end && it.endTime > start }
            val r = preferBestSource(candidate); addSources(r, sources); r.sumOf { it.energy.inKilocalories }
        }
        val heart = run {
            val stats = Stats()
            val candidate = if (crossesDayBoundary) safeReadAll<HeartRateRecord>(start, end)
            else dayRecords.heartRate.filter { it.startTime < end && it.endTime > start }
            val r = preferBestSource(candidate); addSources(r, sources)
            for (item in r) for (sample in item.samples) if (sample.time >= start && sample.time <= end) stats.add(sample.beatsPerMinute.toDouble())
            stats
        }
        val speed = run {
            val stats = Stats()
            val candidate = if (crossesDayBoundary) safeReadAll<SpeedRecord>(start, end)
            else dayRecords.speed.filter { it.startTime < end && it.endTime > start }
            val r = preferBestSource(candidate); addSources(r, sources)
            for (item in r) for (sample in item.samples) if (sample.time >= start && sample.time <= end) stats.add(sample.speed.inKilometersPerHour)
            stats
        }
        val stepCadence = run {
            val stats = Stats()
            val candidate = if (crossesDayBoundary) safeReadAll<StepsCadenceRecord>(start, end)
            else dayRecords.stepCadence.filter { it.startTime < end && it.endTime > start }
            val r = preferBestSource(candidate); addSources(r, sources)
            for (item in r) for (sample in item.samples) if (sample.time >= start && sample.time <= end) stats.add(sample.rate)
            stats
        }
        val cyclingCadence = run {
            val stats = Stats()
            val candidate = if (crossesDayBoundary) safeReadAll<CyclingPedalingCadenceRecord>(start, end)
            else dayRecords.cyclingCadence.filter { it.startTime < end && it.endTime > start }
            val r = preferBestSource(candidate); addSources(r, sources)
            for (item in r) for (sample in item.samples) if (sample.time >= start && sample.time <= end) stats.add(sample.revolutionsPerMinute)
            stats
        }
        val power = run {
            val stats = Stats()
            val candidate = if (crossesDayBoundary) safeReadAll<PowerRecord>(start, end)
            else dayRecords.power.filter { it.startTime < end && it.endTime > start }
            val r = preferBestSource(candidate); addSources(r, sources)
            for (item in r) for (sample in item.samples) if (sample.time >= start && sample.time <= end) stats.add(sample.power.inWatts)
            stats
        }
        val elevation = run {
            val candidate = if (crossesDayBoundary) safeReadAll<ElevationGainedRecord>(start, end)
            else dayRecords.elevation.filter { it.startTime < end && it.endTime > start }
            val r = preferBestSource(candidate); addSources(r, sources); r.sumOf { it.elevation.inMeters }
        }
        val floors = run {
            val candidate = if (crossesDayBoundary) safeReadAll<FloorsClimbedRecord>(start, end)
            else dayRecords.floors.filter { it.startTime < end && it.endTime > start }
            val r = preferBestSource(candidate); addSources(r, sources); r.sumOf { it.floors }
        }

        return JSONObject().apply {
            put("id", stableRecordId(record, "workout|$start|$end|${record.exerciseType}"))
            put("start", start.toString())
            put("end", end.toString())
            put("exerciseType", record.exerciseType)
            put("title", record.title ?: "")
            put("notes", record.notes ?: "")
            put("durationMinutes", Duration.between(start, end).toMinutes())
            put("distanceKm", distance)
            put("steps", steps)
            put("activeCaloriesKcal", activeCalories)
            put("totalCaloriesKcal", totalCalories)
            putNullable("averageHeartRate", heart.average())
            putNullable("minimumHeartRate", heart.min)
            putNullable("maximumHeartRate", heart.max)
            putNullable("averageSpeedKmh", speed.average())
            putNullable("maximumSpeedKmh", speed.max)
            putNullable("averageStepCadence", stepCadence.average())
            putNullable("maximumStepCadence", stepCadence.max)
            putNullable("averageCyclingCadence", cyclingCadence.average())
            putNullable("maximumCyclingCadence", cyclingCadence.max)
            putNullable("averagePowerW", power.average())
            putNullable("maximumPowerW", power.max)
            put("elevationGainedM", elevation)
            put("floorsClimbed", floors)
            put("segmentsJson", JSONArray(record.segments.map { it.toString() }).toString())
            put("lapsJson", JSONArray(record.laps.map { it.toString() }).toString())
            put("routeState", record.exerciseRouteResult.javaClass.simpleName)
            put("sourcePackage", record.sourcePackage())
            put("sourceName", sourceName(record.sourcePackage()))
        }
    }

'''
streamer = streamer[:build_start] + new_build_workout + streamer[build_end:]

workout_data_marker = '''    private data class DayResult(\n        val workouts: Int,\n        val measurements: Int,\n        val sources: Set<String>\n    )\n'''
workout_data_class = '''    private data class WorkoutDayRecords(\n        val steps: List<StepsRecord>,\n        val distance: List<DistanceRecord>,\n        val activeCalories: List<ActiveCaloriesBurnedRecord>,\n        val totalCalories: List<TotalCaloriesBurnedRecord>,\n        val heartRate: List<HeartRateRecord>,\n        val speed: List<SpeedRecord>,\n        val stepCadence: List<StepsCadenceRecord>,\n        val cyclingCadence: List<CyclingPedalingCadenceRecord>,\n        val power: List<PowerRecord>,\n        val elevation: List<ElevationGainedRecord>,\n        val floors: List<FloorsClimbedRecord>\n    )\n\n    private data class DayResult(\n        val workouts: Int,\n        val measurements: Int,\n        val sources: Set<String>\n    )\n'''
if workout_data_marker not in streamer:
    raise SystemExit('DayResult marker not found')
streamer = streamer.replace(workout_data_marker, workout_data_class, 1)

# Health Connect can return an empty string as the terminal page token on some
# platform versions; treating it as another page can create a retry/timeout loop.
streamer = streamer.replace('        } while (pageToken != null)\n', '        } while (!pageToken.isNullOrEmpty())\n', 1)
write(streamer_path, streamer)


# ---------------------------------------------------------------------------
# 7. Do not block the caller/UI thread on every diagnostics event.
# ---------------------------------------------------------------------------
replace_once(
    'app/src/main/java/ru/doronin/healthconnector/SyncRuntime.kt',
    '        prefs.edit().putString(KEY_EVENTS, JSONArray(items).toString()).commit()\n',
    '        prefs.edit().putString(KEY_EVENTS, JSONArray(items).toString()).apply()\n',
)


# ---------------------------------------------------------------------------
# P2 noted in docs: remove browser/file ACTION_VIEW entry point. Share SEND is
# intentionally still exported because that is the FatSecret integration.
# ---------------------------------------------------------------------------
manifest_path = 'app/src/main/AndroidManifest.xml'
manifest = read(manifest_path)
view_filter = '''            <intent-filter>\n                <action android:name="android.intent.action.VIEW" />\n                <category android:name="android.intent.category.DEFAULT" />\n                <category android:name="android.intent.category.BROWSABLE" />\n                <data android:scheme="content" />\n                <data android:scheme="file" />\n                <data android:mimeType="text/*" />\n                <data android:mimeType="application/csv" />\n                <data android:mimeType="application/vnd.ms-excel" />\n                <data android:mimeType="application/octet-stream" />\n            </intent-filter>\n'''
if view_filter not in manifest:
    raise SystemExit('ShareCsvActivity ACTION_VIEW filter not found')
manifest = manifest.replace(view_filter, '', 1)
write(manifest_path, manifest)


# ---------------------------------------------------------------------------
# Docs/version synchronization.
# ---------------------------------------------------------------------------
readme_path = 'README.md'
readme = read(readme_path)
readme = readme.replace('Текущее состояние: **1.6.5** (`versionCode 19`).', 'Текущее состояние: **1.6.8** (`versionCode 22`).', 1)
readme = readme.replace(
    'app/src/main/java/ru/doronin/healthconnector/ShareCsvActivity.kt\n  Приём FatSecret Share Intent, CSV parser, meal-slot normalization/merge, POST.\n',
    'app/src/main/java/ru/doronin/healthconnector/ShareCsvActivity.kt\n  Приём FatSecret Share Intent и POST.\n\napp/src/main/java/ru/doronin/healthconnector/FatSecretCsvNormalizer.kt\n  Общий CSV parser и meal-slot normalization/merge для Share и ручного импорта.\n',
    1,
)
readme = readme.replace(
    'app/src/main/AndroidManifest.xml\n  Health Connect READ permissions, Share/View intent filters.\n',
    'app/src/main/AndroidManifest.xml\n  Health Connect READ permissions и Share intent filters.\n',
    1,
)
readme = readme.replace(
    'Если signing secret отсутствует, CI выполняет compile/security check, но stable APK не публикует.',
    'CI запускает unit-тесты и собирает настоящий `release` (`debuggable=false`, R8 включён). Если signing secret отсутствует, release compile/security check проходит, но stable APK не публикуется.',
    1,
)
readme = readme.replace(
    '## Архитектура проекта\n',
    '''### 1.6.8\n\n- Share и ручной импорт используют один `FatSecretCsvNormalizer`; добавлены unit-тесты meal-slot mapping/merge и CSV quoting.\n- Текст продуктов из внешнего CSV экранируется перед записью в Google Sheets от formula injection (`=`, `+`, `-`, `@`).\n- Стабильный APK переведён с debug на подписанный release build (`debuggable=false`, R8).\n- Удалён `ACTION_VIEW/BROWSABLE` вход для произвольных файлов; остаётся пользовательский Android Share flow.\n- Удалены мёртвые Apps Script diary-функции и дубли `isPermissionFailure()`.\n- Workout summaries переиспользуют дневные Health Connect records вместо повторных запросов на каждую тренировку.\n\n## Архитектура проекта\n''',
    1,
)
readme = readme.replace(
    '1. Добавить regression tests/CSV fixtures для всех основных и дополнительных FatSecret meal slots.\n2. Сохранить обезличенный реальный проблемный CSV как fixture.\n3. Добавить локальную integrity-проверку CSV в Android до отправки на сервер.\n4. При необходимости сохранять исходный `SourceMealSlot`, даже если каноническая категория остаётся `Перекус/Другое`.\n5. Автоматизировать version tags / GitHub Releases и прикладывать проверенный APK к release, а не искать его среди Actions artifacts.\n6. Решить явно, поддерживаем ли несколько файлов в `ACTION_SEND_MULTIPLE` или только один отчёт за импорт.',
    '1. Расширить regression fixtures реальным обезличенным проблемным CSV и английскими вариантами отчёта.\n2. Добавить локальную integrity-проверку CSV в Android до отправки на сервер.\n3. При необходимости сохранять исходный `SourceMealSlot`, даже если каноническая категория остаётся `Перекус/Другое`.\n4. Автоматизировать version tags / GitHub Releases и прикладывать проверенный APK к release, а не искать его среди Actions artifacts.\n5. Решить явно, поддерживаем ли несколько файлов в `ACTION_SEND_MULTIPLE` или только один отчёт за импорт.',
    1,
)
write(readme_path, readme)

changelog_path = 'CHANGELOG.md'
changelog = read(changelog_path)
entry = '''## 1.6.8 — Security hardening and FatSecret import parity\n\n- Prevent Google Sheets formula injection from external FatSecret food text.\n- Use one tested `FatSecretCsvNormalizer` for both Android Share and manual CSV imports.\n- Build and publish a non-debuggable, minified signed release APK instead of `app-debug.apk`.\n- Remove the browser/file `ACTION_VIEW` entry point from `ShareCsvActivity`.\n- Compare API tokens using fixed-size HMAC digests without prefix-dependent early exit.\n- Deduplicate Health Connect permission-failure detection and use asynchronous diagnostics persistence.\n- Reuse already-read day records for same-day workout summaries; keep a direct-read fallback for cross-midnight sessions.\n- Remove unused Apps Script diary synchronization functions and add FatSecret normalizer unit tests.\n- Treat an empty Health Connect pagination token as terminal to avoid platform-specific read loops.\n\n'''
if not changelog.startswith('# Changelog\n\n'):
    raise SystemExit('Unexpected CHANGELOG header')
changelog = '# Changelog\n\n' + entry + changelog[len('# Changelog\n\n'):]
write(changelog_path, changelog)

docs_path = 'docs/FATSECRET_IMPORT.md'
docs = read(docs_path)
docs = docs.replace('> Актуально для **HealthConnector 1.6.5** (`versionCode 19`), 02.09.2026.', '> Актуально для **HealthConnector 1.6.8** (`versionCode 22`), 10.09.2026.', 1)
docs = docs.replace(
    '- `ACTION_SEND_MULTIPLE` + `*/*`;\n- `ACTION_VIEW` для `content://` / `file://` и известных текстовых/CSV MIME-типов.',
    '- `ACTION_SEND_MULTIPLE` + `*/*`.\n\n`ACTION_VIEW` / `BROWSABLE` для файлов удалён в 1.6.8: произвольный `content://`/`file://` больше нельзя передать импортеру через браузерную/VIEW-ссылку. Ручной импорт выполняется через внутренний file picker приложения.',
    1,
)
docs = docs.replace('- `Intent.data`;\n', '', 1)
docs = docs.replace('## 4. Канонизация приёмов пищи в 1.6.5', '## 4. Канонизация приёмов пищи в 1.6.8', 1)
docs = docs.replace('Файл: `app/src/main/java/ru/doronin/healthconnector/ShareCsvActivity.kt`', 'Файл: `app/src/main/java/ru/doronin/healthconnector/FatSecretCsvNormalizer.kt`', 1)
docs = docs.replace(
    'Суммирование выполняется в `mergeMealHeaders()`.',
    'Суммирование выполняется в `FatSecretCsvNormalizer.mergeMealHeaders()`. Один и тот же нормализатор вызывается и из `ShareCsvActivity`, и из ручной кнопки «Импорт CSV», поэтому оба пути имеют одинаковое поведение.',
    1,
)
docs = docs.replace(
    'После успешной проверки:\n\n- `Трекер_питания` — данные по приёмам пищи и список продуктов;',
    'После успешной проверки текстовые поля внешнего CSV дополнительно проходят защиту от Google Sheets formula injection: значения, начинающиеся с `=`, `+`, `-` или `@`, записываются как literal text.\n\nПосле успешной проверки:\n\n- `Трекер_питания` — данные по приёмам пищи и список продуктов;',
    1,
)
docs = docs.replace('app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/release/app-release.apk', 1)
docs = docs.replace(
    'Если secret с ключом подписи отсутствует, workflow выполняет compile/security check, но **не публикует устанавливаемый stable APK**.',
    'Workflow всегда запускает unit-тесты и `assembleRelease`. Стабильная сборка имеет `debuggable=false` и проходит R8/minification. Если secret с ключом подписи отсутствует, workflow выполняет release compile/security check, но **не публикует устанавливаемый stable APK**.',
    1,
)
docs = docs.replace(
    '### P0 — regression tests для FatSecret CSV\n\nСейчас основная защита — серверная проверка целостности и ручной тест. Нужно добавить набор небольших CSV fixtures:',
    '### P0 — расширять regression tests для FatSecret CSV\n\nВ 1.6.8 добавлены unit-тесты общего нормализатора. Следующий шаг — расширить набор небольших CSV fixtures:',
    1,
)
docs = docs.replace('version tag (`v1.6.5`, `v1.6.6`, …)', 'version tag (`v1.6.8`, `v1.6.9`, …)', 1)
docs = docs.replace(
    'app/src/main/java/ru/doronin/healthconnector/ShareCsvActivity.kt\n    Получение FatSecret Share Intent, CSV parsing/normalization/merge, POST.\n\napp/src/main/AndroidManifest.xml\n    Health Connect permissions и Share/View intent filters.',
    'app/src/main/java/ru/doronin/healthconnector/ShareCsvActivity.kt\n    Получение FatSecret Share Intent и POST.\n\napp/src/main/java/ru/doronin/healthconnector/FatSecretCsvNormalizer.kt\n    Общий CSV parsing/normalization/merge для Share и ручного импорта.\n\napp/src/main/AndroidManifest.xml\n    Health Connect permissions и Share intent filters.',
    1,
)
write(docs_path, docs)

print('Hardening patch applied successfully')
