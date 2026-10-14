package ru.doronin.healthconnector

import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Cancelling a sync also closes its current HTTP connection, not just the next batch. */
internal object CancellableHttp {
    private val executor = Executors.newFixedThreadPool(2)

    suspend fun <T> withConnection(url: String, block: (HttpURLConnection) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val active = AtomicReference<HttpURLConnection?>(null)
            val future = executor.submit {
                try {
                    if (continuation.isActive) {
                        val connection = URL(url).openConnection() as HttpURLConnection
                        active.set(connection)
                        try {
                            if (continuation.isActive) {
                                val result = block(connection)
                                continuation.resumeWith(Result.success(result))
                            }
                        } finally {
                            active.set(null)
                            connection.disconnect()
                        }
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                }
            }
            continuation.invokeOnCancellation {
                future.cancel(true)
                active.getAndSet(null)?.disconnect()
            }
        }
}
