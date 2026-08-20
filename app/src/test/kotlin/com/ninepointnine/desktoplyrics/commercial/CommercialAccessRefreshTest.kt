package com.ninepointnine.desktoplyrics.commercial

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CommercialAccessRefreshTest {
    @Test
    fun `fresh local license avoids remote refresh until its signed renewal point`() {
        assertFalse(
            CommercialAccessRefreshPolicy.shouldRequestRemote(
                localRefreshAfterEpochMs = 20_000L,
                retryNotBeforeEpochMs = null,
                nowEpochMs = 19_999L
            )
        )
        assertTrue(
            CommercialAccessRefreshPolicy.shouldRequestRemote(
                localRefreshAfterEpochMs = 20_000L,
                retryNotBeforeEpochMs = null,
                nowEpochMs = 20_000L
            )
        )
    }

    @Test
    fun `transient refresh failure permits one retry per day without delaying missing access`() {
        val now = 20_000L
        val retryAt = CommercialAccessRefreshPolicy.nextRetryNotBefore(now)

        assertFalse(
            CommercialAccessRefreshPolicy.shouldRequestRemote(
                localRefreshAfterEpochMs = 10_000L,
                retryNotBeforeEpochMs = retryAt,
                nowEpochMs = retryAt - 1
            )
        )
        assertTrue(
            CommercialAccessRefreshPolicy.shouldRequestRemote(
                localRefreshAfterEpochMs = 10_000L,
                retryNotBeforeEpochMs = retryAt,
                nowEpochMs = retryAt
            )
        )
        assertTrue(
            CommercialAccessRefreshPolicy.shouldRequestRemote(
                localRefreshAfterEpochMs = null,
                retryNotBeforeEpochMs = retryAt,
                nowEpochMs = now
            )
        )
        assertEquals(
            Long.MAX_VALUE,
            CommercialAccessRefreshPolicy.nextRetryNotBefore(Long.MAX_VALUE)
        )
    }

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

    @Test
    fun `coordinator preserves local trial and exposes its signed remaining time on network failure`() =
        runBlocking {
            val now = 10_000L
            val trialEndsAt = 20_000L
            val snapshots = mutableListOf<EntitlementSnapshot>()
            val coordinator = CommercialEntitlementCoordinator(
                gateway = StubGateway(
                    queryResult = EntitlementQueryResult.Failure(CommercialFailure.NETWORK)
                ),
                accessGate = CommercialAccessGate {
                    CommercialAccessDecision.Allowed(
                        tier = CommercialTier.TRIAL,
                        expiresAtEpochMs = trialEndsAt,
                        trialEndsAtEpochMs = trialEndsAt
                    )
                },
                nowEpochMs = { now }
            )
            coordinator.addListener(snapshots::add)

            val result = coordinator.queryEntitlement(forceRemote = true)

            val ready = result as EntitlementQueryResult.Ready
            assertEquals(EntitlementState.Trial(trialEndsAt, 10_000L), ready.snapshot.entitlement)
            assertEquals(ready.snapshot, snapshots.single())
            val diagnostic = coordinator.diagnostic()
            assertEquals(CommercialTier.TRIAL, diagnostic.tier)
            assertEquals(trialEndsAt, diagnostic.trialEndsAtEpochMs)
            assertEquals(10_000L, diagnostic.remainingMillis)
        }

    @Test
    fun `listener failure cannot turn a successful entitlement query into unknown`() = runBlocking {
        val snapshot = EntitlementSnapshot(EntitlementState.Pro, quote = null)
        val coordinator = CommercialEntitlementCoordinator(
            gateway = StubGateway(queryResult = EntitlementQueryResult.Ready(snapshot)),
            accessGate = CommercialAccessGate {
                CommercialAccessDecision.Allowed(CommercialTier.PRO, expiresAtEpochMs = null)
            },
            nowEpochMs = { 1_000L }
        )
        coordinator.addListener { error("render failed") }

        val result = coordinator.queryEntitlement()

        assertEquals(EntitlementQueryResult.Ready(snapshot), result)
        assertEquals(snapshot, coordinator.currentSnapshot())
    }

    @Test
    fun `coordinator propagates cancellation instead of publishing unknown`() = runBlocking {
        val coordinator = CommercialEntitlementCoordinator(
            gateway = object : StubGateway(
                EntitlementQueryResult.Failure(CommercialFailure.UNKNOWN)
            ) {
                override suspend fun queryEntitlement(nowEpochMs: Long): EntitlementQueryResult {
                    throw CancellationException("cancelled")
                }
            },
            accessGate = CommercialAccessGate {
                CommercialAccessDecision.Denied(CommercialAccessDenial.NO_LICENSE)
            }
        )

        try {
            coordinator.queryEntitlement()
            fail("query cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected: a newer query or a closed controller owns cancellation.
        }
    }

    private open class StubGateway(
        var queryResult: EntitlementQueryResult
    ) : DeviceCommercialGateway {
        override suspend fun queryEntitlement(nowEpochMs: Long): EntitlementQueryResult = queryResult

        override suspend fun requestQuote(
            discountCode: String,
            nowEpochMs: Long
        ): QuoteRequestResult = error("unused")

        override suspend fun createPayment(
            quote: ProductQuote,
            method: PaymentMethod,
            nowEpochMs: Long
        ): PaymentCreationResult = error("unused")

        override suspend fun refreshPayment(
            session: PaymentSession,
            nowEpochMs: Long
        ): PaymentStatusResult = error("unused")

        override suspend fun restorePurchase(nowEpochMs: Long): PurchaseRecoveryResult =
            error("unused")
    }
}
