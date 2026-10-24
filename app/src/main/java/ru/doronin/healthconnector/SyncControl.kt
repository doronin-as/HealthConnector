package ru.doronin.healthconnector

import android.content.Context
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import ru.doronin.healthconnector.local.ServerApi
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Thrown when the server rejects a request because the user aborted all syncs. */
class SyncAbortedException(message: String = "Синхронизация остановлена") : CancellationException(message)

/**
 * One place that knows every running sync in this process and can stop all of them:
 * in-app manual sync, WorkManager syncs, the upload queue, and — through the server —
 * syncs of other app instances (for example the stable and the TEST build side by side).
 */
object SyncControl {
    private val jobs = ConcurrentHashMap<String, Job>()

    val activeOrigins: Set<String> get() = jobs.keys.toSet()

    /** Runs [block] as a tracked, abortable sync job. */
    suspend fun <T> tracked(origin: String, block: suspend () -> T): T = coroutineScope {
        val job = coroutineContext[Job]
        if (job != null) jobs[origin] = job
        try {
            block()
        } finally {
            if (job != null) jobs.remove(origin, job)
        }
    }

    data class AbortReport(val stoppedHere: List<String>, val serverMessage: String)

    /**
     * Stops everything. Data already confirmed by the server stays; unfinished days stay
     * marked incomplete and are repaired by the next run. Health Connect change tokens are
     * committed only after a full run, so nothing is skipped.
     */
    suspend fun abortAll(context: Context, pauseServerJobs: Boolean? = null): AbortReport {
        val stopped = jobs.keys.toList()
        jobs.values.forEach { it.cancel(SyncAbortedException("Остановлено пользователем")) }
        val app = context.applicationContext
        val work = WorkManager.getInstance(app)
        work.cancelUniqueWork(ManualSyncScheduler.UNIQUE_WORK_NAME)
        work.cancelUniqueWork(ru.doronin.healthconnector.local.OutboxWorker.UNIQUE)
        BackgroundSyncScheduler.restartAfterAbort(app)

        val serverMessage = try {
            val body = JSONObject().put("schemaVersion", 1)
            if (pauseServerJobs != null) body.put("pauseServerJobs", pauseServerJobs)
            ServerApi.post(app, "abortSyncV1", body).optString("message", "Сервер принял остановку")
        } catch (error: Throwable) {
            if (error is CancellationException && error !is SyncAbortedException) throw error
            "Сервер не ответил: ${error.message ?: error.javaClass.simpleName}"
        }
        SyncDiagnostics.skipped(app, "control", "Остановлено пользователем: ${stopped.ifEmpty { listOf("нет активных") }.joinToString()}")
        return AbortReport(stopped, serverMessage)
    }

    suspend fun serverStatus(context: Context): JSONObject =
        ServerApi.post(context.applicationContext, "serverStatusV1", JSONObject().put("schemaVersion", 1))

    /** Human-readable summary of [serverStatus]. */
    fun describeStatus(status: JSONObject): String = buildString {
        val lock = status.optJSONObject("lock")
        append("Замок сервера: ").append(lock?.optString("text").orEmpty().ifBlank { "—" })
        if (lock?.optJSONObject("holder")?.optBoolean("stale") == true) {
            append(" (похоже, запрос оборвался; замок освободится сам)")
        }
        val migration = status.optJSONObject("measurementMigration")
        if (migration?.optBoolean("blocksWrites") == true) append("\n⚠ Идёт перезапись HC_Измерения — запись временно заблокирована")
        else if (!migration?.optString("phase").isNullOrBlank()) append("\nМиграция HC_Измерения: ${migration?.optString("phase")}")
        val triggers = status.optJSONArray("triggers")
        if (triggers != null && triggers.length() > 0) {
            val names = (0 until triggers.length()).map { triggers.optJSONObject(it)?.optString("handler").orEmpty() }
            append("\nФоновые задачи сервера: ").append(names.joinToString())
        } else append("\nФоновых задач на сервере нет")
        if (status.optBoolean("serverJobsPaused")) append(" · на паузе")
        if (status.has("measurementRows") && !status.isNull("measurementRows")) {
            append("\nСтрок в HC_Измерения: ").append(status.optInt("measurementRows"))
            if (status.optBoolean("rawHighVolumeStored", true).not()) append(" (поминутные данные больше не пишутся)")
        }
        status.optJSONObject("rawArchive")?.let { archive ->
            val state = archive.optString("state")
            val moved = archive.optLong("archivedRows")
            val left = archive.optLong("remaining", -1)
            when (state) {
                "done" -> append("\nАвтоархивация HC_Измерения: завершена, перенесено $moved строк")
                "running", "scheduled" -> append("\nАвтоархивация HC_Измерения: идёт · перенесено $moved" + if (left >= 0) " · осталось $left" else "")
                "blocked" -> append("\n⚠ Автоархивация остановлена: ${archive.optString("message")}")
                "error" -> append("\n⚠ Автоархивация: ошибка «${archive.optString("lastError")}», повторится через 5 мин")
            }
            archive.optJSONObject("lastWrite")?.let { last ->
                if (!last.optBoolean("ok", true)) append("\n⚠ Последняя запись в Drive не удалась: ${last.optString("error")}")
            }
        }
        status.optString("abortBefore").takeIf { it.isNotBlank() }?.let { append("\nПоследняя остановка: ").append(it) }
    }
}

/** HTTP whose socket is closed as soon as the calling coroutine is cancelled. */
internal object CancellableHttp {
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "hc-http").apply { isDaemon = true }
    }

    suspend fun <T> withConnection(url: String, block: (HttpURLConnection) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val active = AtomicReference<HttpURLConnection?>(null)
            val future = executor.submit {
                try {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    active.set(connection)
                    try {
                        val result = block(connection)
                        if (continuation.isActive) continuation.resumeWith(Result.success(result))
                    } finally {
                        active.set(null)
                        connection.disconnect()
                    }
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                }
            }
            continuation.invokeOnCancellation {
                active.getAndSet(null)?.disconnect()
                future.cancel(true)
            }
        }
}
