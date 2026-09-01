package com.ninepointnine.desktoplyrics.commercial

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

internal sealed interface LocalLicenseState {
    data class Valid(
        val claims: LicenseClaims,
        val entitlement: EntitlementState
    ) : LocalLicenseState

    data object Missing : LocalLicenseState
    data object Revoked : LocalLicenseState
    /** The signed license is authentic, but its final access window has ended. */
    data class Expired(val claims: LicenseClaims?) : LocalLicenseState
    data class Invalid(val reason: CommercialAccessDenial) : LocalLicenseState
}

class CommercialLicenseRepository(
    private val store: SecureCommercialStore,
    private val identityProvider: DeviceIdentityProvider,
    private val trust: DeviceCommerceLicenseTrust,
    private val parser: LicenseClaimsParser = DeviceCommerceLicenseClaimsParser
) {
    @Volatile
    private var revokedInProcess = false

    internal fun readLocal(nowEpochMs: Long): LocalLicenseState {
        if (revokedInProcess) return LocalLicenseState.Revoked
        when (store.read(SecureCommercialRecord.ACCESS_REVOCATION)) {
            is SecureStoreReadResult.Value -> return LocalLicenseState.Revoked
            SecureStoreReadResult.Missing -> Unit
            SecureStoreReadResult.Failure -> {
                return LocalLicenseState.Invalid(CommercialAccessDenial.STORAGE_FAILURE)
            }
        }
        val lastObserved = when (val clock = store.read(SecureCommercialRecord.LICENSE_CLOCK)) {
            is SecureStoreReadResult.Value -> SecureCommercialRecordCodec.decodeLong(clock.bytes)
                ?: return LocalLicenseState.Invalid(CommercialAccessDenial.STORAGE_FAILURE)
            SecureStoreReadResult.Missing -> nowEpochMs
            SecureStoreReadResult.Failure -> {
                return LocalLicenseState.Invalid(CommercialAccessDenial.STORAGE_FAILURE)
            }
        }
        if (nowEpochMs + TrialPolicy.CLOCK_ROLLBACK_TOLERANCE_MS < lastObserved) {
            return LocalLicenseState.Invalid(CommercialAccessDenial.CLOCK_ROLLBACK)
        }

        val encoded = when (val license = store.read(SecureCommercialRecord.LICENSE)) {
            is SecureStoreReadResult.Value -> license.bytes
            SecureStoreReadResult.Missing -> return LocalLicenseState.Missing
            SecureStoreReadResult.Failure -> {
                return LocalLicenseState.Invalid(CommercialAccessDenial.STORAGE_FAILURE)
            }
        }
        val envelope = runCatching { SignedLicenseEnvelopeCodec.decode(encoded) }
            .getOrElse {
                return LocalLicenseState.Invalid(CommercialAccessDenial.INVALID_LICENSE)
            }
        val identity = runCatching { identityProvider.loadOrCreate() }
            .getOrElse {
                return LocalLicenseState.Invalid(CommercialAccessDenial.STORAGE_FAILURE)
            }
        val knownKeyVersion = when (val version = readKnownKeyVersion()) {
            is KnownKeyVersion.Value -> version.value
            KnownKeyVersion.Missing -> null
            KnownKeyVersion.Failure -> {
                return LocalLicenseState.Invalid(CommercialAccessDenial.STORAGE_FAILURE)
            }
        }
        val verified = verifier(identity, knownKeyVersion).verify(envelope, nowEpochMs)
        if (verified is LicenseVerificationResult.Invalid) {
            if (verified.reason == LicenseVerificationFailure.EXPIRED) {
                return LocalLicenseState.Expired(
                    verifier(identity, knownKeyVersion).readSignedClaims(envelope)
                )
            }
            return LocalLicenseState.Invalid(verified.reason.toAccessDenial())
        }
        val claims = (verified as LicenseVerificationResult.Valid).claims
        if (knownKeyVersion == null && !writeKnownKeyVersion(claims.deviceKeyVersion)) {
            return LocalLicenseState.Invalid(CommercialAccessDenial.STORAGE_FAILURE)
        }
        if (!writeObservedTime(maxOf(lastObserved, nowEpochMs))) {
            return LocalLicenseState.Invalid(CommercialAccessDenial.STORAGE_FAILURE)
        }
        return LocalLicenseState.Valid(claims, claims.toEntitlement(nowEpochMs))
    }

    fun accessDecision(nowEpochMs: Long): CommercialAccessDecision = when (
        val local = readLocal(nowEpochMs)
    ) {
        is LocalLicenseState.Valid -> CommercialAccessDecision.Allowed(
            tier = local.claims.tier,
            expiresAtEpochMs = local.claims.finalAccessUntilEpochMs(),
            trialEndsAtEpochMs = local.claims.trialEndsAtEpochMs,
            offlineGraceUntilEpochMs = local.claims.offlineGraceUntilEpochMs
        )
        LocalLicenseState.Missing -> {
            CommercialAccessDecision.Denied(CommercialAccessDenial.NO_LICENSE)
        }
        LocalLicenseState.Revoked -> {
            CommercialAccessDecision.Denied(CommercialAccessDenial.ENTITLEMENT_REVOKED)
        }
        is LocalLicenseState.Expired -> {
            CommercialAccessDecision.Denied(
                reason = CommercialAccessDenial.LICENSE_EXPIRED,
                trialEndsAtEpochMs = local.claims?.trialEndsAtEpochMs,
                expiresAtEpochMs = local.claims?.expiresAtEpochMs,
                offlineGraceUntilEpochMs = local.claims?.offlineGraceUntilEpochMs
            )
        }
        is LocalLicenseState.Invalid -> CommercialAccessDecision.Denied(local.reason)
    }

    internal fun verify(
        envelope: SignedLicenseEnvelope,
        identity: DeviceCommercialIdentity,
        expectedKeyVersion: Int?,
        nowEpochMs: Long
    ): LicenseVerificationResult = verifier(identity, expectedKeyVersion)
        .verify(envelope, nowEpochMs)

    internal fun persistVerified(
        envelope: SignedLicenseEnvelope,
        identity: DeviceCommercialIdentity,
        expectedKeyVersion: Int?,
        nowEpochMs: Long,
        acceptClaims: (LicenseClaims) -> Boolean = { true }
    ): LicenseClaims? {
        val verified = verify(envelope, identity, expectedKeyVersion, nowEpochMs)
        if (verified !is LicenseVerificationResult.Valid) return null
        val claims = verified.claims
        if (!acceptClaims(claims)) return null
        if (!writeKnownKeyVersion(claims.deviceKeyVersion)) return null
        if (!store.write(
                SecureCommercialRecord.LICENSE,
                SignedLicenseEnvelopeCodec.encode(envelope)
            )
        ) {
            return null
        }
        if (!writeObservedTime(nowEpochMs)) return null
        if (!store.delete(SecureCommercialRecord.ACCESS_REVOCATION)) return null
        store.delete(SecureCommercialRecord.ENTITLEMENT_RECHECK_PENDING)
        revokedInProcess = false
        return claims
    }

    /**
     * Closes the local gate before attempting cleanup.  The revocation marker
     * is removed only when every credential cleanup operation succeeds; a
     * failed delete therefore remains fail-closed across a process restart.
     */
    internal fun revokeLocalAccess(cleanup: () -> Boolean = { true }): Boolean {
        revokedInProcess = true
        val markerWritten = store.write(
            SecureCommercialRecord.ACCESS_REVOCATION,
            REVOCATION_MARKER
        )
        val licenseDeleted = store.delete(SecureCommercialRecord.LICENSE)
        val extrasDeleted = runCatching(cleanup).getOrDefault(false)
        val markerDeleted = if (markerWritten && licenseDeleted && extrasDeleted) {
            store.delete(SecureCommercialRecord.ACCESS_REVOCATION)
        } else {
            false
        }
        return markerWritten && licenseDeleted && extrasDeleted && markerDeleted
    }

    internal fun expireLocalAccess() {
        if (revokedInProcess) return
        val licenseDeleted = store.delete(SecureCommercialRecord.LICENSE)
        val revocationDeleted = licenseDeleted &&
            store.delete(SecureCommercialRecord.ACCESS_REVOCATION)
        if (licenseDeleted && revocationDeleted) {
            revokedInProcess = false
        }
        store.delete(SecureCommercialRecord.ENTITLEMENT_RECHECK_PENDING)
    }

    internal fun readKnownKeyVersion(): KnownKeyVersion = when (
        val result = store.read(SecureCommercialRecord.DEVICE_KEY_VERSION)
    ) {
        is SecureStoreReadResult.Value -> SecureCommercialRecordCodec.decodeInt(result.bytes)
            ?.takeIf { it > 0 }
            ?.let(KnownKeyVersion::Value)
            ?: KnownKeyVersion.Failure
        SecureStoreReadResult.Missing -> KnownKeyVersion.Missing
        SecureStoreReadResult.Failure -> KnownKeyVersion.Failure
    }

    internal fun writeKnownKeyVersion(value: Int): Boolean = value > 0 && store.write(
        SecureCommercialRecord.DEVICE_KEY_VERSION,
        SecureCommercialRecordCodec.encodeInt(value)
    )

    internal fun markRecheckPending(nowEpochMs: Long): Boolean = store.write(
        SecureCommercialRecord.ENTITLEMENT_RECHECK_PENDING,
        SecureCommercialRecordCodec.encodeLong(nowEpochMs)
    )

    internal fun clearRecheckPending(): Boolean =
        store.delete(SecureCommercialRecord.ENTITLEMENT_RECHECK_PENDING)

    internal fun isRecheckPending(): Boolean = when (
        store.read(SecureCommercialRecord.ENTITLEMENT_RECHECK_PENDING)
    ) {
        is SecureStoreReadResult.Value -> true
        SecureStoreReadResult.Missing,
        SecureStoreReadResult.Failure -> false
    }

    private fun writeObservedTime(nowEpochMs: Long): Boolean = store.write(
        SecureCommercialRecord.LICENSE_CLOCK,
        SecureCommercialRecordCodec.encodeLong(nowEpochMs)
    )

    private fun verifier(
        identity: DeviceCommercialIdentity,
        expectedKeyVersion: Int?
    ): LicenseVerifier = LicenseVerifier(
        trustedPublicKey = trust.publicKey,
        expectedKeyId = trust.keyId,
        expectedProductId = DeviceCommerceProductContract.PRODUCT_ID,
        expectedDevicePublicKeySha256 = identity.publicKeySha256,
        expectedDeviceKeyVersion = expectedKeyVersion,
        parser = parser
    )

    private fun LicenseVerificationFailure.toAccessDenial(): CommercialAccessDenial = when (this) {
        LicenseVerificationFailure.EXPIRED -> CommercialAccessDenial.LICENSE_EXPIRED
        LicenseVerificationFailure.DEVICE,
        LicenseVerificationFailure.DEVICE_KEY_VERSION -> CommercialAccessDenial.DEVICE_MISMATCH
        else -> CommercialAccessDenial.INVALID_LICENSE
    }

    private companion object {
        val REVOCATION_MARKER = byteArrayOf(1)
    }

    private fun LicenseClaims.toEntitlement(nowEpochMs: Long): EntitlementState = when (tier) {
        CommercialTier.PRO -> EntitlementState.Pro
        CommercialTier.TRIAL -> {
            val trialEndsAt = requireNotNull(trialEndsAtEpochMs)
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
}

internal sealed interface KnownKeyVersion {
    data class Value(val value: Int) : KnownKeyVersion
    data object Missing : KnownKeyVersion
    data object Failure : KnownKeyVersion
}

class CloudDeviceCommercialGateway(
    private val api: DeviceCommerceApi,
    private val identityProvider: DeviceIdentityProvider,
    private val store: SecureCommercialStore,
    private val trialRepository: FirstOpenTrialRepository,
    private val licenseRepository: CommercialLicenseRepository,
    private val clientVersion: String,
    private val secureRandom: SecureRandom = SecureRandom()
) : DeviceCommercialGateway {
    @Volatile
    private var latestProductTitle: String? = null
    private val entitlementCheck = SingleFlightCommercialEntitlementCheck(::checkAccessOnce)

    override suspend fun checkEntitlement(
        nowEpochMs: Long
    ): CommercialAccessRefreshResult = when (
        val result = checkAccess(nowEpochMs)
    ) {
        is AccessRefreshResult.Ready -> CommercialAccessRefreshResult.Ready(result.entitlement)
        is AccessRefreshResult.Failure -> CommercialAccessRefreshResult.Failure(result.reason)
    }

    /** Compatibility wrapper; lifecycle callers use [checkEntitlement]. */
    override suspend fun refreshAccess(nowEpochMs: Long): CommercialAccessRefreshResult =
        checkEntitlement(nowEpochMs)

    /** Compatibility wrapper; there is no separate force/refresh protocol. */
    override suspend fun forceRefreshAccess(
        nowEpochMs: Long
    ): CommercialAccessRefreshResult = checkEntitlement(nowEpochMs)

    override suspend fun queryEntitlement(nowEpochMs: Long): EntitlementQueryResult =
        // Every public entitlement query is a lifecycle check.  The local
        // verifier still supplies the immediate fallback while this request
        // is in flight, but no caller can silently bypass the cloud status
        // endpoint.
        queryEntitlement(nowEpochMs, forceRemote = true)

    override suspend fun forceQueryEntitlement(nowEpochMs: Long): EntitlementQueryResult =
        queryEntitlement(nowEpochMs, forceRemote = true)

    private suspend fun queryEntitlement(
        nowEpochMs: Long,
        @Suppress("UNUSED_PARAMETER") forceRemote: Boolean
    ): EntitlementQueryResult {
        val accessResult = checkAccess(nowEpochMs)
        val refreshed = when (val result = accessResult) {
            is AccessRefreshResult.Ready -> result
            is AccessRefreshResult.Failure -> {
                return EntitlementQueryResult.Failure(result.reason)
            }
        }
        val entitlement = refreshed.entitlement
        val identity = refreshed.identity
        val pending = if (entitlement is EntitlementState.Pro) {
            clearPendingPayment()
            null
        } else {
            readPendingPayment(nowEpochMs)
        }
        val quote = if (entitlement is EntitlementState.Pro || pending != null) {
            null
        } else {
            requestAutomaticQuote(identity, nowEpochMs)
        }
        return EntitlementQueryResult.Ready(
            EntitlementSnapshot(
                entitlement = entitlement,
                quote = quote,
                pendingPayment = pending
            )
        )
    }

    private suspend fun checkAccess(nowEpochMs: Long): AccessRefreshResult =
        entitlementCheck.check(nowEpochMs)

    private suspend fun checkAccessOnce(nowEpochMs: Long): AccessRefreshResult {
        val local = licenseRepository.readLocal(nowEpochMs)
        val identity = runCatching { identityProvider.loadOrCreate() }.getOrElse {
            return AccessRefreshResult.Failure(CommercialFailure.STORAGE)
        }
        val expectedKeyVersion = when {
            local is LocalLicenseState.Valid -> local.claims.deviceKeyVersion
            local is LocalLicenseState.Expired -> local.claims?.deviceKeyVersion
            else -> when (val known = licenseRepository.readKnownKeyVersion()) {
                is KnownKeyVersion.Value -> known.value
                KnownKeyVersion.Missing -> null
                KnownKeyVersion.Failure -> return AccessRefreshResult.Failure(
                    CommercialFailure.STORAGE
                )
            }
        }
        val localFallbackEntitlement = when (local) {
            is LocalLicenseState.Valid -> local.entitlement
            is LocalLicenseState.Expired -> EntitlementState.Expired
            else -> null
        }
        return when (val check = checkRemoteEntitlement(identity, expectedKeyVersion)) {
            EntitlementCheckStatus.Active -> {
                if (local is LocalLicenseState.Valid) {
                    licenseRepository.clearRecheckPending()
                    AccessRefreshResult.Ready(local.entitlement, identity)
                } else {
                    // A trial lease expires before the fixed seven-day trial.
                    // Renew that lease with the current device key; recovery
                    // is reserved for a missing/mismatched device key and
                    // must not rotate the key on every 24-hour lease.
                    val remote = if (
                        local is LocalLicenseState.Expired &&
                        local.claims?.tier == CommercialTier.TRIAL
                    ) {
                        startRemoteTrial(identity, nowEpochMs)
                    } else {
                        recoverForActiveCheck(nowEpochMs)
                    }
                    resolveRemoteEntitlement(
                        remote,
                        localFallbackEntitlement,
                        identity,
                        nowEpochMs
                    )
                }
            }
            EntitlementCheckStatus.NotStarted -> {
                licenseRepository.clearRecheckPending()
                when (local) {
                    is LocalLicenseState.Valid -> when (local.claims.tier) {
                        CommercialTier.TRIAL -> resolveRemoteEntitlement(
                            startRemoteTrial(identity, nowEpochMs),
                            localFallbackEntitlement,
                            identity,
                            nowEpochMs
                        )
                        CommercialTier.PRO -> {
                            clearCommercialAccess()
                            AccessRefreshResult.Failure(CommercialFailure.ENTITLEMENT_REVOKED)
                        }
                    }
                    LocalLicenseState.Missing -> resolveRemoteEntitlement(
                        startRemoteTrial(identity, nowEpochMs),
                        localFallbackEntitlement,
                        identity,
                        nowEpochMs
                    )
                    is LocalLicenseState.Expired -> when (local.claims?.tier) {
                        CommercialTier.TRIAL,
                        null -> resolveRemoteEntitlement(
                            startRemoteTrial(identity, nowEpochMs),
                            localFallbackEntitlement,
                            identity,
                            nowEpochMs
                        )
                        CommercialTier.PRO -> {
                            clearCommercialAccess()
                            AccessRefreshResult.Failure(CommercialFailure.ENTITLEMENT_REVOKED)
                        }
                    }
                    LocalLicenseState.Revoked -> AccessRefreshResult.Failure(
                        CommercialFailure.ENTITLEMENT_REVOKED
                    )
                    is LocalLicenseState.Invalid -> AccessRefreshResult.Failure(
                        local.reason.toCommercialFailure()
                    )
                }
            }
            EntitlementCheckStatus.Revoked -> {
                clearCommercialAccess()
                AccessRefreshResult.Failure(CommercialFailure.ENTITLEMENT_REVOKED)
            }
            EntitlementCheckStatus.DeviceKeyMismatch -> {
                resolveRemoteEntitlement(
                    recoverForActiveCheck(nowEpochMs),
                    localFallbackEntitlement,
                    identity,
                    nowEpochMs
                )
            }
            is EntitlementCheckStatus.Failure -> {
                licenseRepository.markRecheckPending(nowEpochMs)
                if (localFallbackEntitlement != null && check.allowLocalFallback) {
                    AccessRefreshResult.Ready(localFallbackEntitlement, identity)
                } else {
                    AccessRefreshResult.Failure(check.reason)
                }
            }
        }
    }

    private fun resolveRemoteEntitlement(
        remote: RemoteEntitlement,
        localFallbackEntitlement: EntitlementState?,
        identity: DeviceCommercialIdentity,
        nowEpochMs: Long
    ): AccessRefreshResult = when (remote) {
        is RemoteEntitlement.Ready -> {
            licenseRepository.clearRecheckPending()
            AccessRefreshResult.Ready(remote.entitlement, identity)
        }
        RemoteEntitlement.Expired -> {
            licenseRepository.clearRecheckPending()
            AccessRefreshResult.Ready(EntitlementState.Expired, identity)
        }
        RemoteEntitlement.RecoveryRequired -> AccessRefreshResult.Failure(
            CommercialFailure.DEVICE_MISMATCH
        )
        is RemoteEntitlement.Failure -> {
            if (remote.allowLocalFallback) {
                licenseRepository.markRecheckPending(nowEpochMs)
                localFallbackEntitlement?.let {
                    AccessRefreshResult.Ready(it, identity)
                } ?: AccessRefreshResult.Failure(remote.reason)
            } else {
                AccessRefreshResult.Failure(remote.reason)
            }
        }
    }

    private suspend fun recoverForActiveCheck(nowEpochMs: Long): RemoteEntitlement =
        when (val recovery = restorePurchase(nowEpochMs)) {
            is PurchaseRecoveryResult.Success -> RemoteEntitlement.Ready(recovery.entitlement)
            PurchaseRecoveryResult.NetworkFailure -> RemoteEntitlement.Failure(
                CommercialFailure.NETWORK,
                allowLocalFallback = true
            )
            PurchaseRecoveryResult.NotFound -> RemoteEntitlement.Failure(
                CommercialFailure.DEVICE_MISMATCH,
                allowLocalFallback = false
            )
            is PurchaseRecoveryResult.Failure -> RemoteEntitlement.Failure(
                recovery.reason,
                allowLocalFallback = recovery.reason == CommercialFailure.NETWORK ||
                    recovery.reason == CommercialFailure.RATE_LIMITED
            )
        }

    private fun checkRemoteEntitlement(
        identity: DeviceCommercialIdentity,
        expectedKeyVersion: Int?
    ): EntitlementCheckStatus {
        val proof = createProof(DeviceChallengePurpose.CHECK, identity)
            ?: return EntitlementCheckStatus.Failure(lastProofFailure, allowLocalFallback = true)
        return when (val response = api.checkEntitlement(proof)) {
            is DeviceCommerceApiResult.Success -> {
                val payload = response.value
                if (payload.deviceKeyVersion != null &&
                    expectedKeyVersion != null &&
                    payload.deviceKeyVersion != expectedKeyVersion
                ) {
                    return EntitlementCheckStatus.DeviceKeyMismatch
                }
                when (payload.status) {
                    "active" -> EntitlementCheckStatus.Active
                    "revoked" -> EntitlementCheckStatus.Revoked
                    "not_started" -> EntitlementCheckStatus.NotStarted
                    "device_key_mismatch" -> EntitlementCheckStatus.DeviceKeyMismatch
                    else -> EntitlementCheckStatus.Failure(
                        CommercialFailure.PROTOCOL,
                        allowLocalFallback = true
                    )
                }
            }
            is DeviceCommerceApiResult.Failure -> when (
                response.failure.errorCode ?: response.failure.remoteStatus
            ) {
                "device_key_mismatch" -> EntitlementCheckStatus.DeviceKeyMismatch
                "entitlement_revoked" -> EntitlementCheckStatus.Revoked
                "not_started", "access_not_started" -> EntitlementCheckStatus.NotStarted
                else -> EntitlementCheckStatus.Failure(
                    response.failure.toCommercialFailure(),
                    allowLocalFallback =
                        response.failure.kind != DeviceCommerceApiFailureKind.REMOTE ||
                            response.failure.httpStatus?.let { it in 500..599 } == true ||
                            response.failure.errorCode == "rate_limited" ||
                            response.failure.httpStatus == 404
                )
            }
        }
    }

    override suspend fun requestQuote(
        discountCode: String,
        nowEpochMs: Long
    ): QuoteRequestResult {
        val identity = runCatching { identityProvider.loadOrCreate() }.getOrElse {
            return QuoteRequestResult.Failure(CommercialFailure.STORAGE)
        }
        return when (
            val response = api.createQuote(identity.deviceFingerprintSha256, discountCode)
        ) {
            is DeviceCommerceApiResult.Success -> response.value.toProductQuote()
                ?.let(QuoteRequestResult::Ready)
                ?: QuoteRequestResult.Failure(CommercialFailure.PROTOCOL)
            is DeviceCommerceApiResult.Failure -> {
                QuoteRequestResult.Failure(response.failure.toCommercialFailure())
            }
        }
    }

    override suspend fun createPayment(
        quote: ProductQuote,
        method: PaymentMethod,
        nowEpochMs: Long
    ): PaymentCreationResult {
        if (nowEpochMs >= quote.expiresAtEpochMs) return PaymentCreationResult.QuoteExpired
        if (method !in quote.availablePaymentMethods) {
            return PaymentCreationResult.Failure(CommercialFailure.PROTOCOL)
        }
        val identity = runCatching { identityProvider.loadOrCreate() }.getOrElse {
            return PaymentCreationResult.Failure(CommercialFailure.STORAGE)
        }
        val proof = createProof(DeviceChallengePurpose.PURCHASE, identity)
            ?: return PaymentCreationResult.Failure(lastProofFailure)
        return when (
            val response = api.createPurchase(
                proof = proof,
                quoteId = quote.quoteReference,
                paymentMethod = method,
                clientVersion = clientVersion
            )
        ) {
            is DeviceCommerceApiResult.Failure -> when (response.failure.errorCode) {
                "quote_changed" -> response.failure.latestQuote?.toProductQuote()
                    ?.let(PaymentCreationResult::QuoteChanged)
                    ?: PaymentCreationResult.Failure(CommercialFailure.PROTOCOL)
                "quote_expired" -> PaymentCreationResult.QuoteExpired
                else -> PaymentCreationResult.Failure(
                    response.failure.toCommercialFailure()
                )
            }
            is DeviceCommerceApiResult.Success -> handlePurchaseCreated(
                response.value,
                quote,
                identity,
                nowEpochMs
            )
        }
    }

    override suspend fun refreshPayment(
        session: PaymentSession,
        nowEpochMs: Long
    ): PaymentStatusResult {
        if (nowEpochMs >= session.expiresAtEpochMs) {
            clearPendingPayment()
            return PaymentStatusResult.Expired
        }
        val pollToken = when (val stored = store.read(SecureCommercialRecord.POLL_TOKEN)) {
            is SecureStoreReadResult.Value -> stored.bytes.toString(Charsets.UTF_8)
            SecureStoreReadResult.Missing -> return PaymentStatusResult.Failure(
                CommercialFailure.STORAGE
            )
            SecureStoreReadResult.Failure -> return PaymentStatusResult.Failure(
                CommercialFailure.STORAGE
            )
        }
        val timestampSeconds = nowEpochMs / 1000L
        val nonce = ByteArray(16).also(secureRandom::nextBytes).let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        }
        val signature = runCatching {
            identityProvider.signPurchasePoll(
                PurchasePollProofInput(
                    purchaseReference = session.purchaseReference,
                    pollToken = pollToken,
                    timestampEpochSeconds = timestampSeconds,
                    nonceBase64Url = nonce
                )
            )
        }.getOrElse {
            return PaymentStatusResult.Failure(CommercialFailure.STORAGE)
        }
        return when (
            val response = api.readPurchase(
                purchaseId = session.purchaseReference,
                pollToken = pollToken,
                timestampEpochSeconds = timestampSeconds,
                nonceBase64Url = nonce,
                signatureBase64 = Base64.getEncoder().encodeToString(signature)
            )
        ) {
            is DeviceCommerceApiResult.Failure -> when (response.failure.errorCode) {
                "purchase_expired" -> {
                    clearPendingPayment()
                    PaymentStatusResult.Expired
                }
                "entitlement_revoked" -> {
                    clearCommercialAccess()
                    PaymentStatusResult.Failure(CommercialFailure.ENTITLEMENT_REVOKED)
                }
                else -> PaymentStatusResult.Failure(response.failure.toCommercialFailure())
            }
            is DeviceCommerceApiResult.Success -> handlePurchaseStatus(
                response.value,
                session,
                nowEpochMs
            )
        }
    }

    override suspend fun restorePurchase(nowEpochMs: Long): PurchaseRecoveryResult {
        val knownVersion = when (val known = licenseRepository.readKnownKeyVersion()) {
            is KnownKeyVersion.Value -> known.value
            KnownKeyVersion.Missing -> null
            KnownKeyVersion.Failure -> return PurchaseRecoveryResult.Failure(
                CommercialFailure.STORAGE
            )
        }
        val recovery = runCatching {
            identityProvider.beginRecovery(rotateKnownKey = knownVersion != null)
        }.getOrElse {
            return PurchaseRecoveryResult.Failure(CommercialFailure.STORAGE)
        }
        val challengeResult = api.createChallenge(DeviceChallengePurpose.RECOVER, recovery.identity)
        val challenge = when (challengeResult) {
            is DeviceCommerceApiResult.Success -> challengeResult.value
            is DeviceCommerceApiResult.Failure -> {
                recovery.abort()
                return challengeResult.failure.toRecoveryFailure()
            }
        }
        val proof = runCatching {
            DeviceChallengeProof(
                challengeId = challenge.challengeId,
                challengeBase64 = challenge.challengeBase64,
                signatureBase64 = Base64.getEncoder().encodeToString(
                    recovery.signChallenge(challenge.challengeBytes)
                )
            )
        }.getOrElse {
            recovery.abort()
            return PurchaseRecoveryResult.Failure(CommercialFailure.STORAGE)
        }
        val previousSignature = runCatching {
            recovery.signWithPreviousKeyIfAvailable(challenge.challengeBytes)?.let {
                Base64.getEncoder().encodeToString(it)
            }
        }.getOrElse {
            recovery.abort()
            return PurchaseRecoveryResult.Failure(CommercialFailure.STORAGE)
        }

        return when (val response = api.recover(proof, previousSignature)) {
            is DeviceCommerceApiResult.Failure -> {
                if (response.failure.errorCode == "entitlement_revoked") {
                    clearCommercialAccess()
                    recovery.abort()
                    PurchaseRecoveryResult.Failure(CommercialFailure.ENTITLEMENT_REVOKED)
                } else if (response.failure.errorCode == "trial_expired" &&
                    response.failure.keyVersion != null
                ) {
                    val committed = recovery.commit() && licenseRepository.writeKnownKeyVersion(
                        response.failure.keyVersion
                    )
                    if (!committed) {
                        PurchaseRecoveryResult.Failure(CommercialFailure.STORAGE)
                    } else {
                        licenseRepository.expireLocalAccess()
                        PurchaseRecoveryResult.Success(EntitlementState.Expired)
                    }
                } else {
                    recovery.abort()
                    response.failure.toRecoveryFailure()
                }
            }
            is DeviceCommerceApiResult.Success -> handleRecoverySuccess(
                payload = response.value,
                recovery = recovery,
                nowEpochMs = nowEpochMs
            )
        }
    }

    private suspend fun recoverRemoteEntitlementOnce(nowEpochMs: Long): RemoteEntitlement =
        when (val recovery = restorePurchase(nowEpochMs)) {
            is PurchaseRecoveryResult.Success -> RemoteEntitlement.Ready(recovery.entitlement)
            PurchaseRecoveryResult.NotFound -> {
                val identity = runCatching { identityProvider.loadOrCreate() }.getOrElse {
                    return RemoteEntitlement.Failure(CommercialFailure.STORAGE, false)
                }
                when (val restarted = startRemoteTrial(identity, nowEpochMs)) {
                    RemoteEntitlement.RecoveryRequired -> RemoteEntitlement.Failure(
                        CommercialFailure.DEVICE_MISMATCH,
                        false
                    )
                    else -> restarted
                }
            }
            PurchaseRecoveryResult.NetworkFailure -> RemoteEntitlement.Failure(
                CommercialFailure.NETWORK,
                true
            )
            is PurchaseRecoveryResult.Failure -> RemoteEntitlement.Failure(
                recovery.reason,
                recovery.reason == CommercialFailure.NETWORK ||
                    recovery.reason == CommercialFailure.RATE_LIMITED
            )
        }

    @Volatile
    private var lastProofFailure: CommercialFailure = CommercialFailure.UNKNOWN

    private fun createProof(
        purpose: DeviceChallengePurpose,
        identity: DeviceCommercialIdentity
    ): DeviceChallengeProof? {
        return when (val response = api.createChallenge(purpose, identity)) {
            is DeviceCommerceApiResult.Failure -> {
                lastProofFailure = response.failure.toCommercialFailure()
                null
            }
            is DeviceCommerceApiResult.Success -> {
                response.value.productTitle?.let { latestProductTitle = it }
                val signature = runCatching {
                    identityProvider.signChallenge(response.value.challengeBytes)
                }.getOrElse {
                    lastProofFailure = CommercialFailure.STORAGE
                    return null
                }
                lastProofFailure = CommercialFailure.UNKNOWN
                DeviceChallengeProof(
                    challengeId = response.value.challengeId,
                    challengeBase64 = response.value.challengeBase64,
                    signatureBase64 = Base64.getEncoder().encodeToString(signature)
                )
            }
        }
    }

    private fun startRemoteTrial(
        identity: DeviceCommercialIdentity,
        nowEpochMs: Long
    ): RemoteEntitlement {
        val clock = trialRepository.ensureStarted(nowEpochMs)
            ?: return RemoteEntitlement.Failure(CommercialFailure.STORAGE, false)
        val proof = createProof(DeviceChallengePurpose.TRIAL, identity)
            ?: return RemoteEntitlement.Failure(lastProofFailure, true)
        val expectedKeyVersion = when (val known = licenseRepository.readKnownKeyVersion()) {
            is KnownKeyVersion.Value -> known.value
            KnownKeyVersion.Missing -> 1
            KnownKeyVersion.Failure -> {
                return RemoteEntitlement.Failure(CommercialFailure.STORAGE, false)
            }
        }
        return handleAccessResponse(
            api.startTrial(proof, clock.startedAtEpochMs),
            identity,
            expectedKeyVersion = expectedKeyVersion,
            nowEpochMs = nowEpochMs
        )
    }

    private fun handleAccessResponse(
        response: DeviceCommerceApiResult<DeviceAccessPayload>,
        identity: DeviceCommercialIdentity,
        expectedKeyVersion: Int?,
        nowEpochMs: Long
    ): RemoteEntitlement {
        return when (response) {
            is DeviceCommerceApiResult.Failure -> when (response.failure.errorCode) {
                "device_key_mismatch" -> RemoteEntitlement.RecoveryRequired
                "trial_expired" -> {
                    licenseRepository.expireLocalAccess()
                    RemoteEntitlement.Expired
                }
                "entitlement_revoked" -> {
                    clearCommercialAccess()
                    RemoteEntitlement.Failure(CommercialFailure.ENTITLEMENT_REVOKED, false)
                }
                "access_not_started" -> RemoteEntitlement.Failure(
                    CommercialFailure.UNKNOWN,
                    allowLocalFallback = true
                )
                else -> RemoteEntitlement.Failure(
                    reason = response.failure.toCommercialFailure(),
                    allowLocalFallback =
                        response.failure.kind != DeviceCommerceApiFailureKind.REMOTE ||
                            response.failure.httpStatus?.let { it in 500..599 } == true
                )
            }
            is DeviceCommerceApiResult.Success -> {
                val envelope = response.value.license
                    ?: return RemoteEntitlement.Failure(CommercialFailure.PROTOCOL, true)
                val claims = licenseRepository.persistVerified(
                    envelope = envelope,
                    identity = identity,
                    expectedKeyVersion = expectedKeyVersion,
                    nowEpochMs = nowEpochMs,
                    acceptClaims = { claims ->
                        claims.isTrialCredential() || claims.isPermanentProCredential()
                    }
                ) ?: return RemoteEntitlement.Failure(CommercialFailure.INVALID_LICENSE, true)
                RemoteEntitlement.Ready(claims.toEntitlement(nowEpochMs))
            }
        }
    }

    private fun requestAutomaticQuote(
        identity: DeviceCommercialIdentity,
        nowEpochMs: Long
    ): ProductQuote? {
        val campaign = when (val result = api.readCurrentCampaign()) {
            is DeviceCommerceApiResult.Success -> result.value
            is DeviceCommerceApiResult.Failure -> return null
        }
        return when (
            val result = api.createQuote(
                identity.deviceFingerprintSha256,
                campaign.discountCode.orEmpty()
            )
        ) {
            is DeviceCommerceApiResult.Success -> result.value.toProductQuote()
                ?.takeIf { nowEpochMs < it.expiresAtEpochMs }
            is DeviceCommerceApiResult.Failure -> null
        }
    }

    private suspend fun handlePurchaseCreated(
        response: DevicePurchaseResponse,
        quote: ProductQuote,
        identity: DeviceCommercialIdentity,
        nowEpochMs: Long
    ): PaymentCreationResult {
        return when (response.status) {
            "already_owned" -> {
                val envelope = response.license
                    ?: return PaymentCreationResult.Failure(CommercialFailure.PROTOCOL)
                val expectedVersion =
                    (licenseRepository.readKnownKeyVersion() as? KnownKeyVersion.Value)?.value
                if (licenseRepository.persistVerified(
                    envelope,
                    identity,
                    expectedVersion,
                    nowEpochMs,
                    { claims -> claims.isPermanentProCredential() }
                ) == null) {
                    return PaymentCreationResult.Failure(CommercialFailure.INVALID_LICENSE)
                }
                if (store.read(SecureCommercialRecord.DEVICE_TOKEN) !is SecureStoreReadResult.Value) {
                    return when (val recovery = restorePurchase(nowEpochMs)) {
                        is PurchaseRecoveryResult.Success -> {
                            if (recovery.entitlement is EntitlementState.Pro) {
                                PaymentCreationResult.AlreadyOwned
                            } else {
                                PaymentCreationResult.Failure(CommercialFailure.PROTOCOL)
                            }
                        }
                        PurchaseRecoveryResult.NetworkFailure -> {
                            PaymentCreationResult.Failure(CommercialFailure.NETWORK)
                        }
                        PurchaseRecoveryResult.NotFound -> {
                            PaymentCreationResult.Failure(CommercialFailure.PROTOCOL)
                        }
                        is PurchaseRecoveryResult.Failure -> {
                            PaymentCreationResult.Failure(recovery.reason)
                        }
                    }
                }
                clearPendingPayment()
                PaymentCreationResult.AlreadyOwned
            }
            "payment_pending" -> {
                val purchase = response.purchase
                    ?: return PaymentCreationResult.Failure(CommercialFailure.PROTOCOL)
                val session = purchase.toPaymentSession(quote)
                    ?: return PaymentCreationResult.Failure(CommercialFailure.PROTOCOL)
                val pollToken = purchase.pollToken
                    ?: return PaymentCreationResult.Failure(CommercialFailure.PROTOCOL)
                if (!store.write(
                        SecureCommercialRecord.POLL_TOKEN,
                        pollToken.toByteArray(Charsets.UTF_8)
                    ) || !store.write(
                        SecureCommercialRecord.PURCHASE_SESSION,
                        PaymentSessionCodec.encode(session)
                    )
                ) {
                    clearPendingPayment()
                    PaymentCreationResult.Failure(CommercialFailure.STORAGE)
                } else {
                    PaymentCreationResult.Ready(session)
                }
            }
            else -> PaymentCreationResult.Failure(CommercialFailure.PROTOCOL)
        }
    }

    private suspend fun handlePurchaseStatus(
        response: DevicePurchaseResponse,
        session: PaymentSession,
        nowEpochMs: Long
    ): PaymentStatusResult {
        return when (response.status) {
            "payment_pending" -> {
                val purchase = response.purchase
                    ?: return PaymentStatusResult.Failure(CommercialFailure.PROTOCOL)
            if (purchase.amount != session.finalAmountCents ||
                purchase.displayAmount != session.finalAmount.text ||
                purchase.currency != session.currency
                ) {
                    PaymentStatusResult.Failure(CommercialFailure.PROTOCOL)
                } else {
                    PaymentStatusResult.Pending
                }
            }
            "licensed" -> {
                val envelope = response.license
                    ?: return PaymentStatusResult.Failure(CommercialFailure.PROTOCOL)
                val identity = runCatching { identityProvider.loadOrCreate() }.getOrElse {
                    return PaymentStatusResult.Failure(CommercialFailure.STORAGE)
                }
                response.deviceToken?.let { token ->
                    if (!store.write(
                            SecureCommercialRecord.DEVICE_TOKEN,
                            token.toByteArray(Charsets.UTF_8)
                        )
                    ) {
                        return PaymentStatusResult.Failure(CommercialFailure.STORAGE)
                    }
                }
                val hasDeviceToken = store.read(SecureCommercialRecord.DEVICE_TOKEN) is
                    SecureStoreReadResult.Value
                if (!hasDeviceToken) {
                    return when (val recovery = restorePurchase(nowEpochMs)) {
                        is PurchaseRecoveryResult.Success -> {
                            if (recovery.entitlement is EntitlementState.Pro) {
                                PaymentStatusResult.Paid
                            } else {
                                PaymentStatusResult.Failure(CommercialFailure.PROTOCOL)
                            }
                        }
                        PurchaseRecoveryResult.NetworkFailure -> {
                            PaymentStatusResult.Failure(CommercialFailure.NETWORK)
                        }
                        PurchaseRecoveryResult.NotFound -> {
                            PaymentStatusResult.Failure(CommercialFailure.PROTOCOL)
                        }
                        is PurchaseRecoveryResult.Failure -> {
                            PaymentStatusResult.Failure(recovery.reason)
                        }
                    }
                }
                val expectedVersion =
                    (licenseRepository.readKnownKeyVersion() as? KnownKeyVersion.Value)?.value
                if (licenseRepository.persistVerified(
                    envelope,
                    identity,
                    expectedVersion,
                    nowEpochMs,
                    { claims -> claims.isPermanentProCredential() }
                ) == null) {
                    return PaymentStatusResult.Failure(CommercialFailure.INVALID_LICENSE)
                }
                clearPendingPayment()
                PaymentStatusResult.Paid
            }
            else -> PaymentStatusResult.Failure(CommercialFailure.PROTOCOL)
        }
    }

    private fun handleRecoverySuccess(
        payload: DeviceAccessPayload,
        recovery: RecoveryDeviceIdentitySession,
        nowEpochMs: Long
    ): PurchaseRecoveryResult {
        if (payload.status !in setOf("not_started", "licensed", "trial_active")) {
            recovery.abort()
            return PurchaseRecoveryResult.Failure(CommercialFailure.PROTOCOL)
        }
        val keyVersion = payload.keyVersion
            ?: payload.license?.let { envelope ->
                val verified = licenseRepository.verify(
                    envelope,
                    recovery.identity,
                    expectedKeyVersion = null,
                    nowEpochMs = nowEpochMs
                )
                (verified as? LicenseVerificationResult.Valid)?.claims?.deviceKeyVersion
            }
            ?: run {
                recovery.abort()
                return PurchaseRecoveryResult.Failure(CommercialFailure.PROTOCOL)
            }
        payload.license?.let { envelope ->
            val verified = licenseRepository.verify(
                envelope,
                recovery.identity,
                expectedKeyVersion = keyVersion,
                nowEpochMs = nowEpochMs
            )
            val claims = (verified as? LicenseVerificationResult.Valid)?.claims
            val accepted = when (payload.status) {
                "licensed" -> claims?.isPermanentProCredential() == true
                "trial_active" -> claims?.isTrialCredential() == true
                else -> verified is LicenseVerificationResult.Valid
            }
            if (!accepted) {
                recovery.abort()
                return PurchaseRecoveryResult.Failure(CommercialFailure.INVALID_LICENSE)
            }
        }
        if (!recovery.commit()) {
            return PurchaseRecoveryResult.Failure(CommercialFailure.STORAGE)
        }
        if (!licenseRepository.writeKnownKeyVersion(keyVersion)) {
            return PurchaseRecoveryResult.Failure(CommercialFailure.STORAGE)
        }
        payload.deviceToken?.let { token ->
            if (!store.write(
                    SecureCommercialRecord.DEVICE_TOKEN,
                    token.toByteArray(Charsets.UTF_8)
                )
            ) {
                return PurchaseRecoveryResult.Failure(CommercialFailure.STORAGE)
            }
        }
        val entitlement = when (payload.status) {
            "not_started" -> {
                store.delete(SecureCommercialRecord.LICENSE)
                return PurchaseRecoveryResult.NotFound
            }
            "licensed", "trial_active" -> {
                val envelope = payload.license
                    ?: return PurchaseRecoveryResult.Failure(CommercialFailure.PROTOCOL)
                val claims = licenseRepository.persistVerified(
                    envelope,
                    recovery.identity,
                    keyVersion,
                    nowEpochMs,
                    acceptClaims = when (payload.status) {
                        "licensed" -> { claims -> claims.isPermanentProCredential() }
                        "trial_active" -> { claims -> claims.isTrialCredential() }
                        else -> return PurchaseRecoveryResult.Failure(
                            CommercialFailure.PROTOCOL
                        )
                    }
                ) ?: return PurchaseRecoveryResult.Failure(CommercialFailure.INVALID_LICENSE)
                claims.toEntitlement(nowEpochMs)
            }
            else -> return PurchaseRecoveryResult.Failure(CommercialFailure.PROTOCOL)
        }
        clearPendingPayment()
        return PurchaseRecoveryResult.Success(entitlement)
    }

    private fun DevicePurchasePayload.toPaymentSession(quote: ProductQuote): PaymentSession? {
        if (status != "pending" || amount != quote.finalAmountCents ||
            displayAmount != quote.finalPrice.text || currency != quote.currency ||
            qrCodeFormat != "imageUrl" || qrCodeValue.isNullOrBlank() ||
            expiresAtEpochMs == null || expiresAtEpochMs <= 0
        ) {
            return null
        }
        val qrUri = runCatching { java.net.URI(qrCodeValue) }.getOrNull()
        if (qrUri?.scheme != "https" || qrUri.host.isNullOrBlank()) return null
        return PaymentSession(
            purchaseReference = purchaseId,
            finalAmountCents = amount,
            finalAmount = DisplayMoney(displayAmount),
            currency = currency,
            expiresAtEpochMs = expiresAtEpochMs,
            pollAfterMillis = pollAfterSeconds.coerceIn(1, 10) * 1000L,
            qrCode = PaymentQrCode(PaymentQrFormat.IMAGE_URL, qrCodeValue)
        )
    }

    private fun DeviceQuotePayload.toProductQuote(): ProductQuote? {
        if (productId != DeviceCommerceProductContract.PRODUCT_ID ||
            sku != DeviceCommerceProductContract.SKU ||
            listAmount < 1 || finalAmount < 1 || payWays.isEmpty() ||
            (minimumChargeApplied && (calculatedAmount != 0 || finalAmount != 1)) ||
            (!minimumChargeApplied && finalAmount != calculatedAmount)
        ) {
            return null
        }
        val paymentMethods = payWays.mapNotNull(PaymentMethod::fromProtocol).toSet()
        if (paymentMethods.isEmpty()) return null
        val resolution = when (discountStatus) {
            "applied" -> DiscountResolution.VALID
            "none" -> DiscountResolution.NONE
            "unavailable" -> DiscountResolution.UNAVAILABLE
            "invalid" -> when (discountErrorCode) {
                "discount_code_expired" -> DiscountResolution.EXPIRED
                "discount_code_unavailable" -> DiscountResolution.UNAVAILABLE
                else -> DiscountResolution.INVALID
            }
            else -> return null
        }
        if (resolution != DiscountResolution.VALID &&
            (finalAmount != listAmount || paymentRatioBps != 10_000)
        ) {
            return null
        }
        return ProductQuote(
            quoteReference = quoteId,
            productId = productId,
            sku = sku,
            productName = productTitle.ifBlank {
                latestProductTitle ?: DeviceCommerceProductContract.PRODUCT_ID
            },
            originalAmountCents = listAmount,
            originalPrice = DisplayMoney(displayListAmount),
            calculatedAmountCents = calculatedAmount,
            finalAmountCents = finalAmount,
            finalPrice = DisplayMoney(displayFinalAmount),
            paymentRatioBps = paymentRatioBps,
            discountLabel = discountLabel,
            discountCode = discountCode,
            discountResolution = resolution,
            minimumChargeApplied = minimumChargeApplied,
            currency = currency,
            availablePaymentMethods = paymentMethods,
            expiresAtEpochMs = expiresAtEpochMs
        )
    }

    private fun DeviceCommerceApiFailure.toCommercialFailure(): CommercialFailure = when {
        kind == DeviceCommerceApiFailureKind.NETWORK -> CommercialFailure.NETWORK
        kind == DeviceCommerceApiFailureKind.PROTOCOL -> CommercialFailure.PROTOCOL
        httpStatus?.let { it in 500..599 } == true -> CommercialFailure.NETWORK
        errorCode == "device_fingerprint_mismatch" || errorCode == "device_key_mismatch" -> {
            CommercialFailure.DEVICE_MISMATCH
        }
        errorCode == "entitlement_revoked" -> CommercialFailure.ENTITLEMENT_REVOKED
        errorCode == "quote_expired" -> CommercialFailure.QUOTE_EXPIRED
        errorCode == "rate_limited" || errorCode == "purchase_rate_limited" -> {
            CommercialFailure.RATE_LIMITED
        }
        else -> CommercialFailure.UNKNOWN
    }

    private fun CommercialAccessDenial.toCommercialFailure(): CommercialFailure = when (this) {
        CommercialAccessDenial.CONFIGURATION_MISSING -> CommercialFailure.CONFIGURATION_MISSING
        CommercialAccessDenial.NO_LICENSE -> CommercialFailure.UNKNOWN
        CommercialAccessDenial.ENTITLEMENT_REVOKED -> CommercialFailure.ENTITLEMENT_REVOKED
        CommercialAccessDenial.LICENSE_EXPIRED -> CommercialFailure.UNKNOWN
        CommercialAccessDenial.INVALID_LICENSE -> CommercialFailure.INVALID_LICENSE
        CommercialAccessDenial.DEVICE_MISMATCH -> CommercialFailure.DEVICE_MISMATCH
        CommercialAccessDenial.CLOCK_ROLLBACK -> CommercialFailure.CLOCK_ROLLBACK
        CommercialAccessDenial.STORAGE_FAILURE -> CommercialFailure.STORAGE
        CommercialAccessDenial.QUERY_FAILURE -> CommercialFailure.UNKNOWN
    }

    private fun DeviceCommerceApiFailure.toRecoveryFailure(): PurchaseRecoveryResult = when {
        kind == DeviceCommerceApiFailureKind.NETWORK -> PurchaseRecoveryResult.NetworkFailure
        errorCode == "device_fingerprint_mismatch" || errorCode == "device_key_mismatch" -> {
            PurchaseRecoveryResult.Failure(CommercialFailure.DEVICE_MISMATCH)
        }
        else -> PurchaseRecoveryResult.Failure(toCommercialFailure())
    }

    private fun LicenseClaims.toEntitlement(nowEpochMs: Long): EntitlementState = when (tier) {
        CommercialTier.PRO -> EntitlementState.Pro
        CommercialTier.TRIAL -> requireNotNull(trialEndsAtEpochMs).let { trialEnds ->
            if (nowEpochMs >= trialEnds) EntitlementState.Expired
            else EntitlementState.Trial(trialEnds, trialEnds - nowEpochMs)
        }
    }

    private fun LicenseClaims.isPermanentProCredential(): Boolean =
        tier == CommercialTier.PRO && validity == LicenseValidity.PERMANENT

    private fun LicenseClaims.isTrialCredential(): Boolean =
        tier == CommercialTier.TRIAL && validity == LicenseValidity.TRIAL

    private fun readPendingPayment(nowEpochMs: Long): PaymentSession? {
        val session = when (val stored = store.read(SecureCommercialRecord.PURCHASE_SESSION)) {
            is SecureStoreReadResult.Value -> runCatching {
                PaymentSessionCodec.decode(stored.bytes)
            }.getOrNull()
            SecureStoreReadResult.Missing, SecureStoreReadResult.Failure -> null
        }
        if (session == null || nowEpochMs >= session.expiresAtEpochMs ||
            store.read(SecureCommercialRecord.POLL_TOKEN) !is SecureStoreReadResult.Value
        ) {
            clearPendingPayment()
            return null
        }
        return session
    }

    private fun clearPendingPayment(): Boolean {
        val pollDeleted = store.delete(SecureCommercialRecord.POLL_TOKEN)
        val sessionDeleted = store.delete(SecureCommercialRecord.PURCHASE_SESSION)
        return pollDeleted && sessionDeleted
    }

    private fun clearCommercialAccess() {
        licenseRepository.revokeLocalAccess {
            val tokenDeleted = store.delete(SecureCommercialRecord.DEVICE_TOKEN)
            val pendingDeleted = licenseRepository.clearRecheckPending()
            val paymentDeleted = clearPendingPayment()
            tokenDeleted && pendingDeleted && paymentDeleted
        }
    }

    private sealed interface AccessRefreshResult {
        data class Ready(
            val entitlement: EntitlementState,
            val identity: DeviceCommercialIdentity
        ) : AccessRefreshResult

        data class Failure(val reason: CommercialFailure) : AccessRefreshResult
    }

    private sealed interface RemoteEntitlement {
        data class Ready(val entitlement: EntitlementState) : RemoteEntitlement
        data object Expired : RemoteEntitlement
        data object RecoveryRequired : RemoteEntitlement
        data class Failure(
            val reason: CommercialFailure,
            val allowLocalFallback: Boolean
        ) : RemoteEntitlement
    }

    private sealed interface EntitlementCheckStatus {
        data object Active : EntitlementCheckStatus
        data object Revoked : EntitlementCheckStatus
        data object NotStarted : EntitlementCheckStatus
        data object DeviceKeyMismatch : EntitlementCheckStatus
        data class Failure(
            val reason: CommercialFailure,
            val allowLocalFallback: Boolean
        ) : EntitlementCheckStatus
    }
}

internal object PaymentSessionCodec {
    fun encode(session: PaymentSession): ByteArray = JSONObject()
        .put("purchaseReference", session.purchaseReference)
        .put("finalAmountCents", session.finalAmountCents)
        .put("finalAmount", session.finalAmount.text)
        .put("currency", session.currency)
        .put("expiresAtEpochMs", session.expiresAtEpochMs)
        .put("pollAfterMillis", session.pollAfterMillis)
        .put("qrFormat", session.qrCode.format.name)
        .put("qrValue", session.qrCode.value)
        .toString()
        .toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): PaymentSession {
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        return PaymentSession(
            purchaseReference = json.getString("purchaseReference"),
            finalAmountCents = json.getInt("finalAmountCents"),
            finalAmount = DisplayMoney(json.getString("finalAmount")),
            currency = json.getString("currency"),
            expiresAtEpochMs = json.getLong("expiresAtEpochMs"),
            pollAfterMillis = json.getLong("pollAfterMillis"),
            qrCode = PaymentQrCode(
                format = PaymentQrFormat.valueOf(json.getString("qrFormat")),
                value = json.getString("qrValue")
            )
        )
    }
}
