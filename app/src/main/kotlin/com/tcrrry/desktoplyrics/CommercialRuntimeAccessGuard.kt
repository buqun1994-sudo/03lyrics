package com.tcrrry.desktoplyrics

import com.tcrrry.desktoplyrics.commercial.CommercialAccessDecision

internal class CommercialRuntimeAccessGuard(
    private val nowEpochMs: () -> Long,
    private val evaluateAccess: (Long) -> CommercialAccessDecision,
    private val scheduleExpiry: (Runnable, Long) -> Unit,
    private val cancelExpiry: (Runnable) -> Unit,
    private val onDenied: (CommercialAccessDecision.Denied) -> Unit,
    private val onRefreshDue: () -> Unit = {}
) {
    private var allowedAccess: CommercialAccessDecision.Allowed? = null
    private var triggeredRefreshBoundary: Long? = null

    private val refreshRunnable = object : Runnable {
        override fun run() {
            val currentAccess = allowedAccess ?: return
            val boundary = currentAccess.refreshAfterEpochMs ?: return
            val now = nowEpochMs()
            if (now < boundary) {
                scheduleExpiry(this, boundary - now)
                return
            }
            if (!isCurrent(currentAccess, now) || triggeredRefreshBoundary == boundary) {
                return
            }
            triggeredRefreshBoundary = boundary
            onRefreshDue()
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
        triggeredRefreshBoundary = null
        cancelExpiry(refreshRunnable)
        cancelExpiry(expiryRunnable)
    }

    fun hasCurrentAccess(): Boolean {
        val currentAccess = allowedAccess ?: return false
        val now = nowEpochMs()
        if (isCurrent(currentAccess, now)) return true

        revalidateAt(now)
        return allowedAccess?.let { isCurrent(it, nowEpochMs()) } == true
    }

    fun revalidate() {
        if (allowedAccess == null) return
        revalidateAt(nowEpochMs())
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
        cancelExpiry(refreshRunnable)
        cancelExpiry(expiryRunnable)
        access.refreshAfterEpochMs?.let { boundary ->
            if (triggeredRefreshBoundary != boundary || boundary > now) {
                scheduleExpiry(refreshRunnable, (boundary - now).coerceAtLeast(0L))
            }
        }
        access.expiresAtEpochMs?.let { boundary ->
            scheduleExpiry(expiryRunnable, (boundary - now).coerceAtLeast(0L))
        }
    }

    private fun isCurrent(
        access: CommercialAccessDecision.Allowed,
        now: Long
    ): Boolean = access.expiresAtEpochMs?.let { now < it } ?: true
}
