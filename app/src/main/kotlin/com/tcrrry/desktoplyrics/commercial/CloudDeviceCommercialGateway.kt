package com.tcrrry.desktoplyrics.commercial

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
            expiresAtEpochMs = local.claims.offlineGraceUntilEpochMs
        )
        LocalLicenseState.Missing -> {
            CommercialAccessDecision.Denied(CommercialAccessDenial.NO_LICENSE)
        }
        LocalLicenseState.Revoked -> {
            CommercialAccessDecision.Denied(CommercialAccessDenial.ENTITLEMENT_REVOKED)
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
        nowEpochMs: Long
    ): LicenseClaims? {
        val verified = verify(envelope, identity, expectedKeyVersion, nowEpochMs)
        if (verified !is LicenseVerificationResult.Valid) return null
        val claims = verified.claims
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
        revokedInProcess = false
        return claims
    }

    internal fun revokeLocalAccess() {
        revokedInProcess = true
        store.write(SecureCommercialRecord.ACCESS_REVOCATION, REVOCATION_MARKER)
        store.delete(SecureCommercialRecord.LICENSE)
    }

    internal fun expireLocalAccess() {
        val licenseDeleted = store.delete(SecureCommercialRecord.LICENSE)
        val revocationDeleted = licenseDeleted &&
            store.delete(SecureCommercialRecord.ACCESS_REVOCATION)
        if (licenseDeleted && revocationDeleted) {
            revokedInProcess = false
        }
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
    private val accessRefresh = SingleFlightCommercialAccessRefresh(::refreshAccessOnce)

    override suspend fun refreshAccess(nowEpochMs: Long): CommercialAccessRefreshResult = when (
        val result = accessRefresh.refresh(nowEpochMs)
    ) {
        is AccessRefreshResult.Ready -> CommercialAccessRefreshResult.Ready(result.entitlement)
        is AccessRefreshResult.Failure -> CommercialAccessRefreshResult.Failure(result.reason)
    }

    override suspend fun queryEntitlement(nowEpochMs: Long): EntitlementQueryResult {
        val refreshed = when (val result = accessRefresh.refresh(nowEpochMs)) {
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

    private suspend fun refreshAccessOnce(nowEpochMs: Long): AccessRefreshResult {
        val local = licenseRepository.readLocal(nowEpochMs)
        val identity = runCatching { identityProvider.loadOrCreate() }.getOrElse {
            return AccessRefreshResult.Failure(CommercialFailure.STORAGE)
        }
        val hasStoredLicense = when (store.read(SecureCommercialRecord.LICENSE)) {
            is SecureStoreReadResult.Value -> true
            SecureStoreReadResult.Missing -> false
            SecureStoreReadResult.Failure -> {
                return AccessRefreshResult.Failure(CommercialFailure.STORAGE)
            }
        }
        val initialRemote = if (local is LocalLicenseState.Revoked) {
            startRemoteTrial(identity, nowEpochMs)
        } else if (hasStoredLicense) {
            refreshRemoteLicense(
                identity = identity,
                nowEpochMs = nowEpochMs,
                startWhenNotStarted = local !is LocalLicenseState.Valid
            )
        } else {
            startRemoteTrial(identity, nowEpochMs)
        }
        val afterRevocation = restoreOriginalTrialAfterRevocation(
            remote = initialRemote,
            identity = identity,
            nowEpochMs = nowEpochMs
        )
        val remote = if (afterRevocation == RemoteEntitlement.RecoveryRequired) {
            recoverRemoteEntitlementOnce(nowEpochMs)
        } else {
            afterRevocation
        }

        val entitlement = when (remote) {
            is RemoteEntitlement.Ready -> remote.entitlement
            RemoteEntitlement.Expired -> EntitlementState.Expired
            RemoteEntitlement.RecoveryRequired -> {
                return AccessRefreshResult.Failure(CommercialFailure.DEVICE_MISMATCH)
            }
            is RemoteEntitlement.Failure -> {
                val localValid = local as? LocalLicenseState.Valid
                if (localValid != null && remote.allowLocalFallback) {
                    localValid.entitlement
                } else {
                    return AccessRefreshResult.Failure(remote.reason)
                }
            }
        }
        return AccessRefreshResult.Ready(entitlement, identity)
    }

    private fun restoreOriginalTrialAfterRevocation(
        remote: RemoteEntitlement,
        identity: DeviceCommercialIdentity,
        nowEpochMs: Long
    ): RemoteEntitlement {
        val revoked = remote as? RemoteEntitlement.Failure ?: return remote
        if (revoked.reason != CommercialFailure.ENTITLEMENT_REVOKED) return remote
        return when (val trial = startRemoteTrial(identity, nowEpochMs)) {
            is RemoteEntitlement.Ready,
            RemoteEntitlement.Expired -> trial
            else -> revoked
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

    private fun refreshRemoteLicense(
        identity: DeviceCommercialIdentity,
        nowEpochMs: Long,
        startWhenNotStarted: Boolean
    ): RemoteEntitlement {
        val knownVersion = when (val known = licenseRepository.readKnownKeyVersion()) {
            is KnownKeyVersion.Value -> known.value
            KnownKeyVersion.Missing -> null
            KnownKeyVersion.Failure -> {
                return RemoteEntitlement.Failure(CommercialFailure.STORAGE, false)
            }
        }
        val proof = createProof(DeviceChallengePurpose.REFRESH, identity)
            ?: return RemoteEntitlement.Failure(lastProofFailure, true)
        val response = api.refreshLicense(proof)
        if (response is DeviceCommerceApiResult.Failure &&
            response.failure.errorCode == "access_not_started" && startWhenNotStarted
        ) {
            return startRemoteTrial(identity, nowEpochMs)
        }
        return handleAccessResponse(response, identity, knownVersion, nowEpochMs)
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
                    nowEpochMs = nowEpochMs
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
                val claims = licenseRepository.persistVerified(
                    envelope,
                    identity,
                    expectedVersion,
                    nowEpochMs
                ) ?: return PaymentCreationResult.Failure(CommercialFailure.INVALID_LICENSE)
                if (claims.tier != CommercialTier.PRO) {
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
                val claims = licenseRepository.persistVerified(
                    envelope,
                    identity,
                    expectedVersion,
                    nowEpochMs
                ) ?: return PaymentStatusResult.Failure(CommercialFailure.INVALID_LICENSE)
                if (claims.tier != CommercialTier.PRO) {
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
            if (verified !is LicenseVerificationResult.Valid) {
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
                    nowEpochMs
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

    private fun clearPendingPayment() {
        store.delete(SecureCommercialRecord.POLL_TOKEN)
        store.delete(SecureCommercialRecord.PURCHASE_SESSION)
    }

    private fun clearCommercialAccess() {
        licenseRepository.revokeLocalAccess()
        store.delete(SecureCommercialRecord.DEVICE_TOKEN)
        clearPendingPayment()
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
