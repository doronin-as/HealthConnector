package ru.doronin.healthconnector

/**
 * Canonical Health Connect data-origin registry.
 *
 * Package names are matched exactly for source selection. This prevents an
 * unrelated app whose package merely contains words like "fitbit", "fitness"
 * or "wearable" from accidentally becoming the preferred health source.
 */
internal object HealthSourceCatalog {
    const val FITBIT_PACKAGE = "com.fitbit.FitbitMobile"
    const val GOOGLE_FIT_PACKAGE = "com.google.android.apps.fitness"
    const val MI_FITNESS_PACKAGE = "com.xiaomi.wearable"

    data class KnownOrigin(
        val packageName: String,
        val displayName: String,
        val priority: Int
    )

    val KNOWN_ORIGINS: List<KnownOrigin> = listOf(
        KnownOrigin(FITBIT_PACKAGE, "Fitbit", 0),
        KnownOrigin(GOOGLE_FIT_PACKAGE, "Google Fit", 1),
        KnownOrigin(MI_FITNESS_PACKAGE, "Mi Fitness", 2)
    )

    fun priority(packageName: String): Int {
        val value = packageName.trim()
        return KNOWN_ORIGINS.firstOrNull { it.packageName == value }?.priority ?: 100
    }

    fun displayName(packageName: String): String {
        val value = packageName.trim()
        if (value.isBlank()) return "Неизвестный источник"
        KNOWN_ORIGINS.firstOrNull { it.packageName == value }?.let { return it.displayName }

        // Naming fallbacks are UI-only and do not affect source priority.
        val lower = value.lowercase()
        return when {
            lower.contains("shealth") || (lower.contains("samsung") && lower.contains("health")) -> "Samsung Health"
            lower.contains("garmin") -> "Garmin Connect"
            lower.contains("fitbit") -> "Fitbit"
            lower.contains("xiaomi") || lower.contains("mifitness") || lower.contains("mi.health") -> "Mi Fitness"
            lower.contains("google") && lower.contains("fitness") -> "Google Fit"
            else -> value
        }
    }
}
