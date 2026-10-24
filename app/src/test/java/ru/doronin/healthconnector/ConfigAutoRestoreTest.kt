package ru.doronin.healthconnector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.doronin.healthconnector.ConfigAutoRestore.Candidate
import ru.doronin.healthconnector.ConfigAutoRestore.TokenAction

class ConfigAutoRestoreTest {
    private val endpoint = "https://script.google.com/macros/s/abc/exec"

    private fun modernJson(url: String = endpoint, days: Int = 7) =
        """{"format":"HealthConnectorConfig","version":2,"endpoint":"$url","days":$days,"backgroundSync":false}"""

    private fun candidate(label: String, modifiedAt: Long, text: String?) =
        Candidate(label, modifiedAt) { text }

    // --- newest valid JSON wins ---

    @Test
    fun newestValidCandidateWins() {
        val selected = ConfigAutoRestore.selectNewestValid(
            listOf(
                candidate("old", 1_000, modernJson("https://old.example/exec", days = 3)),
                candidate("new", 3_000, modernJson("https://new.example/exec", days = 14)),
                candidate("mid", 2_000, modernJson("https://mid.example/exec", days = 5))
            )
        )!!

        assertEquals("new", selected.first.label)
        assertEquals("https://new.example/exec", selected.second.endpoint)
        assertEquals(14, selected.second.days)
    }

    @Test
    fun newerInvalidCandidatesFallBackToNewestValidOne() {
        val selected = ConfigAutoRestore.selectNewestValid(
            listOf(
                candidate("valid-old", 1_000, modernJson()),
                candidate("malformed", 5_000, "{not json"),
                candidate("unreadable", 4_000, null),
                Candidate("throws", 3_500) { throw SecurityException("denied") },
                candidate("http", 3_000, modernJson("http://insecure.example/exec")),
                candidate("other-app", 2_000, """{"format":"SomethingElse","version":1}""")
            )
        )!!

        assertEquals("valid-old", selected.first.label)
    }

    @Test
    fun noValidCandidateSelectsNothing() {
        assertNull(
            ConfigAutoRestore.selectNewestValid(
                listOf(
                    candidate("empty", 2_000, ""),
                    candidate("array", 1_000, "[]")
                )
            )
        )
        assertNull(ConfigAutoRestore.selectNewestValid(emptyList()))
    }

    // --- invalid / malformed / wrong-format JSON ---

    @Test(expected = Exception::class)
    fun malformedJsonIsRejected() {
        ConfigAutoRestore.parse("""{"endpoint": "https://x.example/exec"""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongFormatWithoutEndpointIsRejected() {
        ConfigAutoRestore.parse("""{"format":"GoogleFitExport","data":{"steps":100}}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun configFormatWithoutEndpointIsRejected() {
        ConfigAutoRestore.parse("""{"format":"HealthConnectorConfig","version":2}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun plainHttpEndpointIsRejected() {
        ConfigAutoRestore.parse(modernJson("http://script.google.com/macros/s/abc/exec"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedFileIsRejected() {
        val padding = "x".repeat(600 * 1024)
        ConfigAutoRestore.parse("""{"endpoint":"$endpoint","note":"$padding"}""")
    }

    @Test
    fun outOfRangeSyncWindowIsClamped() {
        assertEquals(30, ConfigAutoRestore.parse(modernJson(days = 365)).days)
        assertEquals(1, ConfigAutoRestore.parse(modernJson(days = -4)).days)
    }

    @Test
    fun modernExportParsesWithoutToken() {
        val parsed = ConfigAutoRestore.parse(modernJson())

        assertEquals(endpoint, parsed.endpoint)
        assertEquals(7, parsed.days)
        assertFalse(parsed.backgroundSync)
        assertEquals(2, parsed.version)
        assertNull(parsed.token)
    }

    @Test
    fun nestedConfigObjectIsFound() {
        val parsed = ConfigAutoRestore.parse(
            """{"exportedAt":"2026-09-01","settings":{"appsScriptUrl":"$endpoint","syncDays":"10","autoSync":"да"}}"""
        )

        assertEquals(endpoint, parsed.endpoint)
        assertEquals(10, parsed.days)
        assertTrue(parsed.backgroundSync)
    }

    // --- legacy token migration ---

    @Test
    fun legacyTokenIsParsedAndMigratedToSecureStore() {
        val parsed = ConfigAutoRestore.parse("""{"endpoint":"$endpoint","token":"  secret-123  "}""")

        assertEquals("secret-123", parsed.token)
        assertEquals(TokenAction.STORE, ConfigAutoRestore.tokenAction(parsed, previousEndpoint = ""))
        assertEquals(TokenAction.STORE, ConfigAutoRestore.tokenAction(parsed, previousEndpoint = endpoint))
    }

    @Test
    fun legacyTokenAliasesAreAccepted() {
        assertEquals("a", ConfigAutoRestore.parse("""{"endpoint":"$endpoint","apiToken":"a"}""").token)
        assertEquals("b", ConfigAutoRestore.parse("""{"endpoint":"$endpoint","accessToken":"b"}""").token)
    }

    @Test
    fun blankLegacyTokenIsTreatedAsAbsent() {
        val parsed = ConfigAutoRestore.parse("""{"endpoint":"$endpoint","token":"   "}""")

        assertNull(parsed.token)
        assertEquals(TokenAction.KEEP, ConfigAutoRestore.tokenAction(parsed, previousEndpoint = endpoint))
    }

    @Test
    fun missingTokenKeepsStoredTokenForSameEndpoint() {
        val parsed = ConfigAutoRestore.parse(modernJson())

        assertEquals(TokenAction.KEEP, ConfigAutoRestore.tokenAction(parsed, previousEndpoint = ""))
        assertEquals(TokenAction.KEEP, ConfigAutoRestore.tokenAction(parsed, previousEndpoint = endpoint))
    }

    @Test
    fun missingTokenClearsStoredTokenWhenEndpointChanges() {
        val parsed = ConfigAutoRestore.parse(modernJson())

        assertEquals(
            TokenAction.CLEAR,
            ConfigAutoRestore.tokenAction(parsed, previousEndpoint = "https://other.example/exec")
        )
    }

    // --- existing configuration is never overwritten automatically ---

    @Test
    fun configuredInstallationIsNotAutoRestored() {
        assertTrue(ConfigAutoRestore.isAlreadyConfigured(endpoint, "token"))
    }

    @Test
    fun incompleteInstallationIsEligibleForAutoRestore() {
        assertFalse(ConfigAutoRestore.isAlreadyConfigured("", ""))
        assertFalse(ConfigAutoRestore.isAlreadyConfigured(endpoint, ""))
        assertFalse(ConfigAutoRestore.isAlreadyConfigured("", "token"))
        assertFalse(ConfigAutoRestore.isAlreadyConfigured("   ", "   "))
    }
}
