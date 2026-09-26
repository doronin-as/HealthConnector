package ru.doronin.healthconnector

import org.junit.Assert.assertEquals
import org.junit.Test

class HealthSourceCatalogTest {
    @Test
    fun canonicalPackagesHaveStablePriority() {
        assertEquals(0, HealthSourceCatalog.priority("com.fitbit.FitbitMobile"))
        assertEquals(1, HealthSourceCatalog.priority("com.google.android.apps.fitness"))
        assertEquals(2, HealthSourceCatalog.priority("com.xiaomi.wearable"))
    }

    @Test
    fun substringLookalikesDoNotBecomePreferredSources() {
        assertEquals(100, HealthSourceCatalog.priority("com.example.fitbit.clone"))
        assertEquals(100, HealthSourceCatalog.priority("com.example.google.fitness.bridge"))
        assertEquals(100, HealthSourceCatalog.priority("com.example.xiaomi.wearable.bridge"))
    }

    @Test
    fun canonicalPackagesHaveStableDisplayNames() {
        assertEquals("Fitbit", HealthSourceCatalog.displayName("com.fitbit.FitbitMobile"))
        assertEquals("Google Fit", HealthSourceCatalog.displayName("com.google.android.apps.fitness"))
        assertEquals("Mi Fitness", HealthSourceCatalog.displayName("com.xiaomi.wearable"))
    }
}
