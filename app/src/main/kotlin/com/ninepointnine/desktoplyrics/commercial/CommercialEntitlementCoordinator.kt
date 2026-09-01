package com.ninepointnine.desktoplyrics.commercial

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The process-wide owner of entitlement reads, rechecks and the last trusted
 * projection. Settings and the lyric service must use this object instead of
 * evaluating the gateway and access gate independently.
 */
class CommercialEntitlementCoordinator(
    private val gateway: DeviceCommercialGateway,
    private val accessGate: CommercialAccessGate,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    private val listeners = CopyOnWriteArrayList<(EntitlementSnapshot) -> Unit>()

    @Volatile
    private var latestSnapshot: EntitlementSnapshot? = null

    @Volatile
    private var latestKey: SnapshotKey? = null

    /**
     * Registers a listener for trusted entitlement snapshots. Listener
     * failures are isolated so a UI side effect cannot turn a successful
     * entitlement query into an UNKNOWN result.
     */
    fun addListener(listener: (EntitlementSnapshot) -> Unit): () -> Unit {
        listeners += listener
        latestSnapshot?.let { snapshot -> notifyListener(listener, snapshot) }
        return { listeners -= listener }
    }

    fun currentSnapshot(nowEpochMs: Long = this.nowEpochMs()): EntitlementSnapshot? {
        evaluate(nowEpochMs)
        return latestSnapshot
    }

    fun evaluate(nowEpochMs: Long = this.nowEpochMs()): CommercialAccessDecision {
        val access = runCatching { accessGate.evaluate(nowEpochMs) }.getOrElse {
            CommercialAccessDecision.Denied(CommercialAccessDenial.STORAGE_FAILURE)
        }
        publishAccess(access, nowEpochMs)
        return access
    }

    suspend fun queryEntitlement(
        nowEpochMs: Long = this.nowEpochMs(),
        forceRemote: Boolean = false
    ): EntitlementQueryResult {
        val result = try {
            if (forceRemote) {
                gateway.forceQueryEntitlement(nowEpochMs)
            } else {
                gateway.queryEntitlement(nowEpochMs)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            EntitlementQueryResult.Failure(CommercialFailure.UNKNOWN)
        }
        currentCoroutineContext().ensureActive()

        if (result is EntitlementQueryResult.Ready) {
            publish(result.snapshot)
            return result
        }

        val failure = result as EntitlementQueryResult.Failure
        if (failure.reason == CommercialFailure.ENTITLEMENT_REVOKED) {
            publishRevoked()
        }
        if (failure.reason.isTransient()) {
            val local = snapshotFromAccess(evaluate(nowEpochMs), nowEpochMs)
            if (local != null) {
                publish(local)
                return EntitlementQueryResult.Ready(local)
            }
        }
        return result
    }

    /**
     * Performs the online, read-only device entitlement check. A successful
     * active response does not replace the locally stored license.
     */
    suspend fun recheckEntitlement(
        nowEpochMs: Long = this.nowEpochMs()
    ): CommercialAccessRefreshResult {
        val result = try {
            gateway.checkEntitlement(nowEpochMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CommercialAccessRefreshResult.Failure(CommercialFailure.UNKNOWN)
        }
        currentCoroutineContext().ensureActive()
        // The gateway persists any newly issued purchase/trial/recovery
        // license before returning. Re-read the local verifier so runtime
        // consumers observe one authoritative gate projection.
        evaluate(nowEpochMs)
        if (result is CommercialAccessRefreshResult.Failure &&
            result.reason == CommercialFailure.ENTITLEMENT_REVOKED
        ) {
            publishRevoked()
        }
        return result
    }

    /**
     * Compatibility wrapper for the pre-check API. It intentionally performs
     * the same read-only check and never invokes license/refresh itself.
     */
    suspend fun refreshAccess(
        nowEpochMs: Long = this.nowEpochMs(),
        @Suppress("UNUSED_PARAMETER") forceRemote: Boolean = true
    ): CommercialAccessRefreshResult {
        return recheckEntitlement(nowEpochMs)
    }

    fun diagnostic(nowEpochMs: Long = this.nowEpochMs()): CommercialEntitlementDiagnostic {
        val decision = evaluate(nowEpochMs)
        val trialEndsAt = when (decision) {
            is CommercialAccessDecision.Allowed -> decision.trialEndsAtEpochMs
            is CommercialAccessDecision.Denied -> decision.trialEndsAtEpochMs
        }
        val tier = (decision as? CommercialAccessDecision.Allowed)?.tier
            ?: trialEndsAt?.let { CommercialTier.TRIAL }
        val remaining = trialEndsAt?.let { it - nowEpochMs }
        return CommercialEntitlementDiagnostic(
            observedAtEpochMs = nowEpochMs,
            decision = decision,
            tier = tier,
            trialEndsAtEpochMs = trialEndsAt,
            remainingMillis = remaining,
            offlineGraceUntilEpochMs = when (decision) {
                is CommercialAccessDecision.Allowed -> decision.offlineGraceUntilEpochMs
                is CommercialAccessDecision.Denied -> decision.offlineGraceUntilEpochMs
            }
        )
    }

    private fun publishAccess(
        access: CommercialAccessDecision,
        nowEpochMs: Long
    ) {
        snapshotFromAccess(access, nowEpochMs)?.let(::publish)
    }

    private fun snapshotFromAccess(
        access: CommercialAccessDecision,
        nowEpochMs: Long
    ): EntitlementSnapshot? {
        val previous = latestSnapshot
        val entitlement = when (access) {
            is CommercialAccessDecision.Allowed -> when (access.tier) {
                CommercialTier.PRO -> EntitlementState.Pro
                CommercialTier.TRIAL -> {
                    val trialEndsAt = access.trialEndsAtEpochMs
                        ?: (previous?.entitlement as? EntitlementState.Trial)
                            ?.expiresAtEpochMs
                        ?: return null
                    if (nowEpochMs >= trialEndsAt) {
                        EntitlementState.Expired
                    } else {
                        EntitlementState.Trial(
                            expiresAtEpochMs = trialEndsAt,
                            remainingMillis = trialEndsAt - nowEpochMs
                        )
                    }
                }
            }
            is CommercialAccessDecision.Denied -> when (access.reason) {
                CommercialAccessDenial.LICENSE_EXPIRED -> EntitlementState.Expired
                CommercialAccessDenial.ENTITLEMENT_REVOKED -> {
                    EntitlementState.Error(CommercialFailure.ENTITLEMENT_REVOKED)
                }
                else -> return null
            }
        }
        val keepPurchaseState = entitlement is EntitlementState.Trial
        return EntitlementSnapshot(
            entitlement = entitlement,
            quote = previous?.quote?.takeIf { keepPurchaseState },
            pendingPayment = previous?.pendingPayment?.takeIf { keepPurchaseState }
        )
    }

    private fun publish(snapshot: EntitlementSnapshot) {
        latestSnapshot = snapshot
        val key = SnapshotKey.from(snapshot)
        if (latestKey == key) return
        latestKey = key
        listeners.forEach { listener -> notifyListener(listener, snapshot) }
    }

    private fun publishRevoked() {
        publish(
            EntitlementSnapshot(
                entitlement = EntitlementState.Error(CommercialFailure.ENTITLEMENT_REVOKED),
                quote = null,
                pendingPayment = null
            )
        )
    }

    private fun notifyListener(
        listener: (EntitlementSnapshot) -> Unit,
        snapshot: EntitlementSnapshot
    ) {
        runCatching { listener(snapshot) }
    }

    private fun CommercialFailure.isTransient(): Boolean = this == CommercialFailure.NETWORK ||
        this == CommercialFailure.RATE_LIMITED

    private data class SnapshotKey(
        val entitlement: String,
        val quoteReference: String?,
        val quoteFinalAmountCents: Int?,
        val pendingPurchaseReference: String?
    ) {
        companion object {
            fun from(snapshot: EntitlementSnapshot): SnapshotKey {
                val entitlement = when (val value = snapshot.entitlement) {
                    EntitlementState.Checking -> "checking"
                    is EntitlementState.Trial -> "trial:${value.expiresAtEpochMs}"
                    EntitlementState.Expired -> "expired"
                    EntitlementState.Pro -> "pro"
                    is EntitlementState.Error -> "error:${value.reason.name}"
                }
                return SnapshotKey(
                    entitlement = entitlement,
                    quoteReference = snapshot.quote?.quoteReference,
                    quoteFinalAmountCents = snapshot.quote?.finalAmountCents,
                    pendingPurchaseReference = snapshot.pendingPayment?.purchaseReference
                )
            }
        }
    }

    companion object {
        /** Compatibility owner for isolated controller tests. */
        internal fun forGateway(
            gateway: DeviceCommercialGateway,
            nowEpochMs: () -> Long
        ): CommercialEntitlementCoordinator = CommercialEntitlementCoordinator(
            gateway = gateway,
            accessGate = FailClosedCommercialAccessGate(),
            nowEpochMs = nowEpochMs
        )
    }
}

data class CommercialEntitlementDiagnostic(
    val observedAtEpochMs: Long,
    val decision: CommercialAccessDecision,
    val tier: CommercialTier?,
    val trialEndsAtEpochMs: Long?,
    val remainingMillis: Long?,
    val offlineGraceUntilEpochMs: Long?
)
