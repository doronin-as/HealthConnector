package ru.doronin.healthconnector

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ChangesCollectorTest {
    private class MemoryStore(initial: Map<String, String> = emptyMap()) : ChangeTokenStore {
        val tokens = initial.toMutableMap()
        override fun get(typeKey: String) = tokens[typeKey]
        override fun put(typeKey: String, token: String) { tokens[typeKey] = token }
        override fun remove(typeKey: String) { tokens.remove(typeKey) }
    }

    private class FakeSource(
        private val pages: Map<String, ChangesPage> = emptyMap(),
        private val deniedTypes: Set<String> = emptySet()
    ) : ChangesSource {
        var created = 0
        override suspend fun createToken(typeKey: String): String? {
            if (typeKey in deniedTypes) return null
            created++
            return "fresh-$typeKey"
        }

        override suspend fun readPage(typeKey: String, token: String): ChangesPage {
            if (typeKey in deniedTypes) throw SecurityException("does not have permission")
            return pages.getValue(token)
        }
    }

    @Test
    fun expiredTokenIsReportedForReconciliationAndReplaced() = runBlocking {
        val store = MemoryStore(mapOf("Steps" to "old"))
        val source = FakeSource(pages = mapOf("old" to ChangesPage(tokenExpired = true)))

        val result = ChangesCollector(store, source).collect(listOf("Steps"))

        assertEquals(setOf("Steps"), result.expiredTypes)
        assertEquals("fresh-Steps", store.tokens["Steps"])
    }

    @Test
    fun validTokenCollectsChangesWithoutReconciliation() = runBlocking {
        val day = LocalDate.of(2026, 9, 29)
        val store = MemoryStore(mapOf("Steps" to "t1"))
        val source = FakeSource(
            pages = mapOf(
                "t1" to ChangesPage(setOf(day), emptySet(), 1, hasMore = true, nextToken = "t2"),
                "t2" to ChangesPage(emptySet(), setOf("deleted-id"), 1, hasMore = false, nextToken = "t3")
            )
        )

        val result = ChangesCollector(store, source).collect(listOf("Steps"))

        assertTrue(result.expiredTypes.isEmpty())
        assertEquals(setOf(day), result.affectedDates)
        assertEquals(setOf("deleted-id"), result.deletedRecordIds)
        assertEquals(2, result.observedChanges)
        assertEquals("t3", store.tokens["Steps"])
    }

    @Test
    fun permissionDenialDropsOnlyThatTypesToken() = runBlocking {
        val store = MemoryStore(mapOf("Steps" to "t1", "HeartRate" to "h1"))
        val source = FakeSource(
            pages = mapOf("t1" to ChangesPage(nextToken = "t2")),
            deniedTypes = setOf("HeartRate")
        )

        val result = ChangesCollector(store, source).collect(listOf("Steps", "HeartRate"))

        assertTrue(result.expiredTypes.isEmpty())
        assertEquals("t2", store.tokens["Steps"])
        assertNull(store.tokens["HeartRate"])
    }

    @Test
    fun firstRunCreatesTokenWithoutReconciliation() = runBlocking {
        val store = MemoryStore()
        val source = FakeSource()

        val result = ChangesCollector(store, source).collect(listOf("Steps"))

        assertTrue(result.expiredTypes.isEmpty())
        assertEquals("fresh-Steps", store.tokens["Steps"])
    }
}
