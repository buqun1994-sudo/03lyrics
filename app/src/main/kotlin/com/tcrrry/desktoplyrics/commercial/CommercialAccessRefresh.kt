package com.tcrrry.desktoplyrics.commercial

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

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
