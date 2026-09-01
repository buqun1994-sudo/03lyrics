package com.ninepointnine.desktoplyrics

import com.ninepointnine.desktoplyrics.commercial.CommercialAccessDecision
import com.ninepointnine.desktoplyrics.commercial.CommercialTier

internal class CommercialRuntimeAccessGuard(
    private val nowEpochMs: () -> Long,
    private val evaluateAccess: (Long) -> CommercialAccessDecision,
    private val scheduleExpiry: (Runnable, Long) -> Unit,
    private val cancelExpiry: (Runnable) -> Unit,
    private val onDenied: (CommercialAccessDecision.Denied) -> Unit,
    private val onTrialLeaseDue: () -> Unit = {}
) {
    private var allowedAccess: CommercialAccessDecision.Allowed? = null
    private var trialLeaseRenewalPending = false
    private var trialLeaseBoundaryTriggered: Long? = null

    /**
     * A trial license is a short lease inside the signed seven-day trial.
     * Crossing the lease boundary must ask the cloud for the next signed
     * lease; it must not be treated as the end of the trial entitlement.
     */
    private val trialLeaseRunnable = object : Runnable {
        override fun run() {
            val currentAccess = allowedAccess ?: return
            val now = nowEpochMs()
            if (!needsTrialLeaseRenewal(currentAccess, now)) return
            triggerTrialLeaseRenewal(currentAccess)
        }
    }

    private val expiryRunnable = Runnable {
        val currentAccess = allowedAccess ?: return@Runnable
        val now = nowEpochMs()
        if (!isCurrent(currentAccess, now)) revalidateAt(now)
    }

    fun authorize(access: CommercialAccessDecision.Allowed) {
        replaceAccess(access, nowEpochMs())
    }

    fun clear() {
        allowedAccess = null
        trialLeaseRenewalPending = false
        trialLeaseBoundaryTriggered = null
        cancelExpiry(trialLeaseRunnable)
        cancelExpiry(expiryRunnable)
    }

    fun hasCurrentAccess(): Boolean {
        val currentAccess = allowedAccess ?: return false
        val now = nowEpochMs()
        if (isCurrent(currentAccess, now)) {
            if (needsTrialLeaseRenewal(currentAccess, now)) {
                triggerTrialLeaseRenewal(currentAccess)
            }
            return true
        }

        revalidateAt(now)
        return allowedAccess?.let { isCurrent(it, nowEpochMs()) } == true
    }

    fun revalidate() {
        val currentAccess = allowedAccess ?: return
        val now = nowEpochMs()
        if (isCurrent(currentAccess, now) &&
            needsTrialLeaseRenewal(currentAccess, now)
        ) {
            triggerTrialLeaseRenewal(currentAccess)
            return
        }
        revalidateAt(now)
    }

    private fun revalidateAt(now: Long) {
        if (allowedAccess == null) return
        when (val access = evaluateAccess(now)) {
            is CommercialAccessDecision.Allowed -> replaceAccess(access, now)
            is CommercialAccessDecision.Denied -> {
                clear()
                onDenied(access)
            }
        }
    }

    private fun replaceAccess(access: CommercialAccessDecision.Allowed, now: Long) {
        allowedAccess = access
        trialLeaseRenewalPending = false
        trialLeaseBoundaryTriggered = null
        cancelExpiry(trialLeaseRunnable)
        cancelExpiry(expiryRunnable)
        val finalBoundary = finalBoundary(access)
        if (access.tier == CommercialTier.TRIAL) {
            val leaseBoundary = access.expiresAtEpochMs
            if (leaseBoundary != null &&
                access.trialEndsAtEpochMs?.let { leaseBoundary < it } == true
            ) {
                scheduleExpiry(
                    trialLeaseRunnable,
                    (leaseBoundary - now).coerceAtLeast(0L)
                )
            }
        }
        finalBoundary?.let { boundary ->
            scheduleExpiry(expiryRunnable, (boundary - now).coerceAtLeast(0L))
        }
    }

    private fun triggerTrialLeaseRenewal(
        access: CommercialAccessDecision.Allowed
    ) {
        val boundary = access.expiresAtEpochMs ?: return
        if (trialLeaseRenewalPending && trialLeaseBoundaryTriggered == boundary) return
        trialLeaseRenewalPending = true
        trialLeaseBoundaryTriggered = boundary
        cancelExpiry(trialLeaseRunnable)
        onTrialLeaseDue()
    }

    private fun needsTrialLeaseRenewal(
        access: CommercialAccessDecision.Allowed,
        now: Long
    ): Boolean {
        if (access.tier != CommercialTier.TRIAL) return false
        val leaseBoundary = access.expiresAtEpochMs ?: return false
        val finalBoundary = access.trialEndsAtEpochMs ?: return false
        return leaseBoundary < finalBoundary && now >= leaseBoundary && now < finalBoundary
    }

    private fun finalBoundary(access: CommercialAccessDecision.Allowed): Long? =
        if (access.tier == CommercialTier.TRIAL) {
            access.trialEndsAtEpochMs ?: access.expiresAtEpochMs
        } else {
            access.expiresAtEpochMs
        }

    private fun isCurrent(
        access: CommercialAccessDecision.Allowed,
        now: Long
    ): Boolean = finalBoundary(access)?.let { now < it } ?: true
}
