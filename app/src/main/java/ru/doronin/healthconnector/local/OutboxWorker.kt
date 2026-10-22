package ru.doronin.healthconnector.local

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Delivers [LocalStore] outbox items to Apps Script. Items of one kind go in one request;
 * an item is removed only after the server confirmed its id.
 */
class OutboxWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = LocalStore.get(applicationContext)
        var retryLater = false
        repeat(MAX_ROUNDS) {
            val pending = store.pendingOutbox(BATCH_SIZE)
            if (pending.isEmpty()) return if (retryLater) Result.retry() else Result.success()
            for ((kind, items) in pending.groupBy { it.kind }) {
                val action = ACTIONS[kind]
                if (action == null) {
                    items.forEach { store.completeOutbox(it.seq) }
                    continue
                }
                try {
                    val body = JSONObject().put("schemaVersion", 1).put("entries", JSONArray().apply {
                        items.forEach { put(JSONObject(it.payload)) }
                    })
                    val response = ServerApi.post(applicationContext, action, body)
                    val accepted = response.optJSONArray("accepted")?.let { array ->
                        (0 until array.length()).map { array.optString(it) }.toSet()
                    } ?: emptySet()
                    items.forEach { item ->
                        if (item.entityId in accepted) store.completeOutbox(item.seq)
                        else store.failOutbox(item.seq, "сервер не подтвердил запись")
                    }
                    if (items.any { it.entityId !in accepted }) retryLater = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: ServerApi.NotConfigured) {
                    return Result.retry()
                } catch (error: ServerApi.Retryable) {
                    items.forEach { store.failOutbox(it.seq, error.message.orEmpty()) }
                    return Result.retry()
                } catch (error: Throwable) {
                    // A permanent server-side rejection (e.g. old Apps Script without this
                    // action). Keep the data and retry with backoff; the watchdog reports it.
                    items.forEach { store.failOutbox(it.seq, error.message ?: error.javaClass.simpleName) }
                    return Result.retry()
                }
            }
            if (retryLater) return Result.retry()
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE = "local_outbox_upload"
        private const val BATCH_SIZE = 50
        private const val MAX_ROUNDS = 10
        private val ACTIONS = mapOf(
            LocalStore.KIND_WATER to "hydrationV1",
            LocalStore.KIND_WELLBEING to "wellbeingV1"
        )

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // APPEND_OR_REPLACE: an item saved while a run is in progress still gets a run after it.
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
