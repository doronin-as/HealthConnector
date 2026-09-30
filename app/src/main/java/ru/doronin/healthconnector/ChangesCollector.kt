package ru.doronin.healthconnector

import java.time.LocalDate

internal interface ChangeTokenStore {
    fun get(typeKey: String): String?
    fun put(typeKey: String, token: String)
    fun remove(typeKey: String)
}

internal interface ChangesSource {
    /** Returns a new token, or null when the record type is not readable. */
    suspend fun createToken(typeKey: String): String?

    suspend fun readPage(typeKey: String, token: String): ChangesPage
}

internal data class ChangesPage(
    val affectedDates: Set<LocalDate> = emptySet(),
    val deletedRecordIds: Set<String> = emptySet(),
    val observedChanges: Int = 0,
    val hasMore: Boolean = false,
    val nextToken: String? = null,
    val tokenExpired: Boolean = false
)

data class ChangeSet(
    val affectedDates: Set<LocalDate>,
    val deletedRecordIds: Set<String>,
    val observedChanges: Int,
    /** Record types whose token had expired; their missed changes must be reconciled. */
    val expiredTypes: Set<String> = emptySet()
)

/**
 * Reads the Health Connect changes feed per record type. Kept free of Android types so the
 * token-expiry and permission paths can be unit tested.
 */
internal class ChangesCollector(
    private val store: ChangeTokenStore,
    private val source: ChangesSource
) {
    suspend fun collect(typeKeys: List<String>): ChangeSet {
        val dates = linkedSetOf<LocalDate>()
        val deletedIds = linkedSetOf<String>()
        val expiredTypes = linkedSetOf<String>()
        var observedChanges = 0

        for (typeKey in typeKeys) {
            val token = store.get(typeKey)
            if (token.isNullOrBlank()) {
                source.createToken(typeKey)?.let { store.put(typeKey, it) }
                continue
            }

            try {
                var nextToken: String = token
                var keepReading: Boolean
                do {
                    val page = source.readPage(typeKey, nextToken)
                    if (page.tokenExpired) {
                        // The missed changes cannot be recovered from the feed. Report the type
                        // so the caller re-reads the readable window, then start a fresh token.
                        expiredTypes += typeKey
                        val fresh = source.createToken(typeKey)
                        if (fresh != null) store.put(typeKey, fresh) else store.remove(typeKey)
                        break
                    }
                    dates += page.affectedDates
                    deletedIds += page.deletedRecordIds
                    observedChanges += page.observedChanges
                    keepReading = page.hasMore
                    val responseToken = page.nextToken
                    if (keepReading && responseToken.isNullOrBlank()) {
                        throw IllegalStateException("Health Connect returned hasMore without a continuation token")
                    }
                    if (!responseToken.isNullOrBlank()) nextToken = responseToken
                    if (!keepReading) store.put(typeKey, nextToken)
                } while (keepReading)
            } catch (error: Throwable) {
                if (HealthConnectErrorUtils.isPermissionFailure(error)) {
                    // Keep other record-type tokens working. If permission is granted later,
                    // recreate this token and the normal lookback sync will fill recent history.
                    store.remove(typeKey)
                    continue
                }
                throw IllegalStateException(
                    "Health Connect changes failed for ${typeKey.substringAfterLast('.')}: ${error.message}",
                    error
                )
            }
        }

        return ChangeSet(dates, deletedIds, observedChanges, expiredTypes)
    }
}
