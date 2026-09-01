package com.ninepointnine.desktoplyrics.commercial

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Process-level single-flight coordinator for the read-only entitlement
 * check. The file name and [refresh] alias remain for source compatibility
 * with older tests and callers.
 */
internal class SingleFlightCommercialEntitlementCheck<T>(
    private val operation: suspend (Long) -> T,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val lock = Any()
    private var inFlight: Deferred<T>? = null

    suspend fun check(nowEpochMs: Long): T {
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

    /** Compatibility alias for the former refresh-shaped helper API. */
    suspend fun refresh(nowEpochMs: Long): T = check(nowEpochMs)
}

/** @deprecated Use [SingleFlightCommercialEntitlementCheck]. */
@Deprecated("Use SingleFlightCommercialEntitlementCheck")
internal typealias SingleFlightCommercialAccessRefresh<T> =
    SingleFlightCommercialEntitlementCheck<T>
