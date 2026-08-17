package com.tcrrry.desktoplyrics.commercial

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CommercialAccessRefreshTest {
    @Test
    fun `concurrent startup and settings refresh share one request`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val refresh = SingleFlightCommercialAccessRefresh(
            operation = { nowEpochMs ->
                calls += 1
                started.complete(Unit)
                release.await()
                nowEpochMs
            },
            scope = this
        )

        val startup = async { refresh.refresh(100L) }
        started.await()
        val settings = async(start = CoroutineStart.UNDISPATCHED) { refresh.refresh(200L) }

        assertEquals(1, calls)
        release.complete(Unit)
        assertEquals(100L, startup.await())
        assertEquals(100L, settings.await())

        assertEquals(300L, refresh.refresh(300L))
        assertEquals(2, calls)
    }

    @Test
    fun `cancelled service waiter does not cancel the shared cloud request`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val refresh = SingleFlightCommercialAccessRefresh(
            operation = { nowEpochMs ->
                calls += 1
                started.complete(Unit)
                release.await()
                nowEpochMs
            },
            scope = requestScope
        )

        val service = async { refresh.refresh(100L) }
        started.await()
        service.cancelAndJoin()
        val settings = async(start = CoroutineStart.UNDISPATCHED) { refresh.refresh(200L) }

        assertEquals(1, calls)
        release.complete(Unit)
        assertEquals(100L, settings.await())
        requestScope.cancel()
    }
}
