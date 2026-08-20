package com.ninepointnine.desktoplyrics.commercial

import java.nio.ByteBuffer

enum class SecureCommercialRecord(val storageKey: String) {
    LICENSE("license"),
    ACCESS_REVOCATION("access_revocation"),
    DEVICE_TOKEN("device_token"),
    POLL_TOKEN("poll_token"),
    PURCHASE_SESSION("purchase_session"),
    TRIAL_CLOCK("trial_clock"),
    LICENSE_CLOCK("license_clock"),
    LICENSE_REFRESH_RETRY_AT("license_refresh_retry_at"),
    DEVICE_KEY_VERSION("device_key_version"),
    DEVICE_KEY_ALIAS("device_key_alias"),
    PENDING_DEVICE_KEY_ALIAS("pending_device_key_alias")
}

sealed interface SecureStoreReadResult {
    data class Value(val bytes: ByteArray) : SecureStoreReadResult
    data object Missing : SecureStoreReadResult
    data object Failure : SecureStoreReadResult
}

interface SecureCommercialStore {
    fun read(record: SecureCommercialRecord): SecureStoreReadResult
    fun write(record: SecureCommercialRecord, bytes: ByteArray): Boolean
    fun delete(record: SecureCommercialRecord): Boolean
}

enum class CommercialAccessDenial {
    CONFIGURATION_MISSING,
    NO_LICENSE,
    ENTITLEMENT_REVOKED,
    LICENSE_EXPIRED,
    INVALID_LICENSE,
    DEVICE_MISMATCH,
    CLOCK_ROLLBACK,
    STORAGE_FAILURE,
    QUERY_FAILURE
}

sealed interface CommercialAccessDecision {
    data class Allowed(
        val tier: CommercialTier,
        val expiresAtEpochMs: Long?,
        val refreshAfterEpochMs: Long? = null,
        /** Signed trial boundary, when the tier is TRIAL. */
        val trialEndsAtEpochMs: Long? = null,
        /** Raw signed Pro offline boundary, retained for diagnostics. */
        val offlineGraceUntilEpochMs: Long? = null
    ) : CommercialAccessDecision

    data class Denied(
        val reason: CommercialAccessDenial,
        /** Retained only for a locally verified expired license diagnostic. */
        val trialEndsAtEpochMs: Long? = null,
        val expiresAtEpochMs: Long? = null,
        val offlineGraceUntilEpochMs: Long? = null
    ) : CommercialAccessDecision
}

fun interface CommercialAccessGate {
    fun evaluate(nowEpochMs: Long): CommercialAccessDecision
}

class FailClosedCommercialAccessGate(
    private val reason: CommercialAccessDenial = CommercialAccessDenial.CONFIGURATION_MISSING
) : CommercialAccessGate {
    override fun evaluate(nowEpochMs: Long): CommercialAccessDecision =
        CommercialAccessDecision.Denied(reason)
}

class VerifiedLicenseAccessGate(
    private val store: SecureCommercialStore,
    private val verifier: LicenseVerifier
) : CommercialAccessGate {
    override fun evaluate(nowEpochMs: Long): CommercialAccessDecision {
        when (store.read(SecureCommercialRecord.ACCESS_REVOCATION)) {
            is SecureStoreReadResult.Value -> {
                return denied(CommercialAccessDenial.ENTITLEMENT_REVOKED)
            }
            SecureStoreReadResult.Missing -> Unit
            SecureStoreReadResult.Failure -> {
                return denied(CommercialAccessDenial.STORAGE_FAILURE)
            }
        }
        val lastObserved = when (val clock = store.read(SecureCommercialRecord.LICENSE_CLOCK)) {
            is SecureStoreReadResult.Value -> decodeLong(clock.bytes)
                ?: return denied(CommercialAccessDenial.STORAGE_FAILURE)
            SecureStoreReadResult.Missing -> nowEpochMs
            SecureStoreReadResult.Failure -> return denied(CommercialAccessDenial.STORAGE_FAILURE)
        }
        if (nowEpochMs + TrialPolicy.CLOCK_ROLLBACK_TOLERANCE_MS < lastObserved) {
            return denied(CommercialAccessDenial.CLOCK_ROLLBACK)
        }

        val licenseBytes = when (val stored = store.read(SecureCommercialRecord.LICENSE)) {
            is SecureStoreReadResult.Value -> stored.bytes
            SecureStoreReadResult.Missing -> return denied(CommercialAccessDenial.NO_LICENSE)
            SecureStoreReadResult.Failure -> return denied(CommercialAccessDenial.STORAGE_FAILURE)
        }
        val envelope = runCatching { SignedLicenseEnvelopeCodec.decode(licenseBytes) }
            .getOrElse { return denied(CommercialAccessDenial.INVALID_LICENSE) }
        val verified = verifier.verify(envelope, nowEpochMs)
        if (verified is LicenseVerificationResult.Invalid) {
            val expiredClaims = if (
                verified.reason == LicenseVerificationFailure.EXPIRED
            ) {
                verifier.readSignedClaims(envelope)
            } else {
                null
            }
            return denied(
                when (verified.reason) {
                    LicenseVerificationFailure.EXPIRED -> CommercialAccessDenial.LICENSE_EXPIRED
                    LicenseVerificationFailure.DEVICE -> CommercialAccessDenial.DEVICE_MISMATCH
                    else -> CommercialAccessDenial.INVALID_LICENSE
                },
                trialEndsAtEpochMs = expiredClaims?.trialEndsAtEpochMs,
                expiresAtEpochMs = expiredClaims?.expiresAtEpochMs,
                offlineGraceUntilEpochMs = expiredClaims?.offlineGraceUntilEpochMs
            )
        }

        val nextObserved = maxOf(lastObserved, nowEpochMs)
        if (!store.write(SecureCommercialRecord.LICENSE_CLOCK, encodeLong(nextObserved))) {
            return denied(CommercialAccessDenial.STORAGE_FAILURE)
        }
        val claims = (verified as LicenseVerificationResult.Valid).claims
        return CommercialAccessDecision.Allowed(
            tier = claims.tier,
            expiresAtEpochMs = claims.finalAccessUntilEpochMs(),
            refreshAfterEpochMs = claims.expiresAtEpochMs.takeIf {
                it < claims.finalAccessUntilEpochMs()
            },
            trialEndsAtEpochMs = claims.trialEndsAtEpochMs,
            offlineGraceUntilEpochMs = claims.offlineGraceUntilEpochMs
        )
    }

    private fun denied(
        reason: CommercialAccessDenial,
        trialEndsAtEpochMs: Long? = null,
        expiresAtEpochMs: Long? = null,
        offlineGraceUntilEpochMs: Long? = null
    ) = CommercialAccessDecision.Denied(
        reason = reason,
        trialEndsAtEpochMs = trialEndsAtEpochMs,
        expiresAtEpochMs = expiresAtEpochMs,
        offlineGraceUntilEpochMs = offlineGraceUntilEpochMs
    )
}

class FirstOpenTrialRepository(
    private val store: SecureCommercialStore
) {
    fun ensureStarted(nowEpochMs: Long): TrialClockState? {
        return when (val result = store.read(SecureCommercialRecord.TRIAL_CLOCK)) {
            is SecureStoreReadResult.Value -> decodeClock(result.bytes)
            SecureStoreReadResult.Missing -> TrialClockState(nowEpochMs, nowEpochMs).also { state ->
                if (!store.write(SecureCommercialRecord.TRIAL_CLOCK, encodeClock(state))) {
                    return null
                }
            }
            SecureStoreReadResult.Failure -> null
        }
    }

    fun evaluate(nowEpochMs: Long, durationMillis: Long): TrialEvaluation? {
        val existing = ensureStarted(nowEpochMs) ?: return null
        val evaluation = TrialPolicy.evaluate(existing, nowEpochMs, durationMillis)
        val nextClock = when (evaluation) {
            is TrialEvaluation.Active -> evaluation.nextClockState
            is TrialEvaluation.Expired -> evaluation.nextClockState
            TrialEvaluation.ClockRollback -> return evaluation
        }
        if (!store.write(SecureCommercialRecord.TRIAL_CLOCK, encodeClock(nextClock))) return null
        return evaluation
    }

    private fun encodeClock(state: TrialClockState): ByteArray = ByteBuffer.allocate(16)
        .putLong(state.startedAtEpochMs)
        .putLong(state.lastObservedEpochMs)
        .array()

    private fun decodeClock(bytes: ByteArray): TrialClockState? = runCatching {
        require(bytes.size == 16)
        ByteBuffer.wrap(bytes).let { TrialClockState(it.long, it.long) }
    }.getOrNull()
}

internal object SecureCommercialRecordCodec {
    fun encodeLong(value: Long): ByteArray = ByteBuffer.allocate(8).putLong(value).array()

    fun decodeLong(bytes: ByteArray): Long? = runCatching {
        require(bytes.size == 8)
        ByteBuffer.wrap(bytes).long
    }.getOrNull()

    fun encodeInt(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()

    fun decodeInt(bytes: ByteArray): Int? = runCatching {
        require(bytes.size == 4)
        ByteBuffer.wrap(bytes).int
    }.getOrNull()
}

private fun encodeLong(value: Long): ByteArray = SecureCommercialRecordCodec.encodeLong(value)

private fun decodeLong(bytes: ByteArray): Long? = SecureCommercialRecordCodec.decodeLong(bytes)
