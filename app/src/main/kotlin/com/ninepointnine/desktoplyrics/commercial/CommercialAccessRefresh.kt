package com.ninepointnine.desktoplyrics.commercial

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

internal object CommercialAccessRefreshPolicy {
    const val RETRY_COOLDOWN_MS = 24L * 60 * 60 * 1000

    fun shouldRequestRemote(
        localRefreshAfterEpochMs: Long?,
        retryNotBeforeEpochMs: Long?,
        nowEpochMs: Long
    ): Boolean {
        if (localRefreshAfterEpochMs == null) return true
        if (nowEpochMs < localRefreshAfterEpochMs) return false
        return retryNotBeforeEpochMs == null || nowEpochMs >= retryNotBeforeEpochMs
    }

    fun nextRetryNotBefore(nowEpochMs: Long): Long =
        if (nowEpochMs > Long.MAX_VALUE - RETRY_COOLDOWN_MS) {
            Long.MAX_VALUE
        } else {
            nowEpochMs + RETRY_COOLDOWN_MS
        }
}

internal class SingleFlightCommercialAccessRefresh<T>(
    private val operation: suspend (Long) -> T,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val lock = Any()
    private var inFlight: Deferred<T>? = null

    suspend fun refresh(nowEpochMs: Long): T {
        val request = synchronized(lock) {
            inFlight ?: scope.async { operation(nowEpochMs) }.also { created ->
                inFlight = created
                created.invokeOnCompletion {
                    synchronized(lock) {
                        if (inFlight === created) inFlight = null
                    }
                }
            }
        }
        return request.await()
    }
}
