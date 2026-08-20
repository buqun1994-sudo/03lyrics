package com.ninepointnine.desktoplyrics.commercial

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal enum class DebugEntitlementScenario {
    TRIAL,
    EXPIRED,
    QUERY_ERROR,
    REVOKED,
    PRO
}

internal enum class DebugRecoveryScenario {
    SAME_DEVICE,
    DIFFERENT_DEVICE,
    NETWORK_ERROR
}

internal enum class DebugPaymentOutcome {
    PENDING,
    PAID,
    EXPIRED,
    QUOTE_CHANGED,
    ALREADY_OWNED
}

internal data class FixturePricing(
    val listAmount: Int,
    val paymentRatioBps: Int,
    val calculatedAmount: Int,
    val finalAmount: Int,
    val minimumChargeApplied: Boolean,
    val discountLabel: String
)

internal object DebugCommercialFixtureCatalog {
    const val PUBLIC_CAMPAIGN_CODE = "icar 03"
    const val EXPIRED_DISCOUNT_CODE = "fixture expired"
    const val UNAVAILABLE_DISCOUNT_CODE = "fixture unavailable"
    const val LICENSE_KEY_ID = "debug-fixture-key-v2"

    val staging = FixturePricing(
        listAmount = 2,
        paymentRatioBps = 5_000,
        calculatedAmount = 1,
        finalAmount = 1,
        minimumChargeApplied = false,
        discountLabel = "5折"
    )

    val production = FixturePricing(
        listAmount = 4_900,
        paymentRatioBps = 6_000,
        calculatedAmount = 2_940,
        finalAmount = 2_940,
        minimumChargeApplied = false,
        discountLabel = "6折"
    )

    val zeroRatio = FixturePricing(
        listAmount = 4_900,
        paymentRatioBps = 0,
        calculatedAmount = 0,
        finalAmount = 1,
        minimumChargeApplied = true,
        discountLabel = "0折"
    )

    fun displayAmount(cents: Int): String = String.format(
        Locale.US,
        "¥%.2f",
        cents / 100.0
    )
}

internal interface FixtureLicenseSigner {
    val keyId: String
    fun publicKey(): PublicKey
    fun sign(payload: ByteArray): ByteArray
}

internal class FixtureDeviceCommerceTransport(
    private val licenseSigner: FixtureLicenseSigner,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) : DeviceCommerceTransport {
    @Volatile
    var entitlementScenario = DebugEntitlementScenario.TRIAL
        set(value) {
            field = value
            if (value == DebugEntitlementScenario.TRIAL ||
                value == DebugEntitlementScenario.EXPIRED
            ) {
                identities.values.forEach { it.pro = false }
            }
        }

    @Volatile
    var recoveryScenario = DebugRecoveryScenario.SAME_DEVICE

    @Volatile
    var paymentOutcome = DebugPaymentOutcome.PENDING

    @Volatile
    var campaignAvailable = true

    @Volatile
    var pricing = DebugCommercialFixtureCatalog.staging

    @Volatile
    var recoveryGrantsPro = true

    @Volatile
    var omitFirstPaidDeviceToken = false

    private val challenges = ConcurrentHashMap<String, ChallengeRecord>()
    private val quotes = ConcurrentHashMap<String, QuoteRecord>()
    private val purchases = ConcurrentHashMap<String, PurchaseRecord>()
    private val identities = ConcurrentHashMap<String, IdentityRecord>()
    private val usedPollNonces = ConcurrentHashMap.newKeySet<String>()

    override fun execute(request: DeviceCommerceHttpRequest): DeviceCommerceHttpResponse {
        val action = request.path.substringAfter("/device-access/", missingDelimiterValue = "")
        if (action.isBlank()) return failure(404, "not_found")
        if (entitlementScenario == DebugEntitlementScenario.QUERY_ERROR &&
            action in setOf("challenges", "trial/start", "license/refresh")
        ) {
            throw IOException("fixture network unavailable")
        }
        return when {
            request.method == "POST" && action == "challenges" -> createChallenge(request)
            request.method == "GET" && action == "campaigns/current" -> readCampaign()
            request.method == "POST" && action == "quotes" -> createQuote(request)
            request.method == "POST" && action == "trial/start" -> startTrial(request)
            request.method == "POST" && action == "purchases" -> createPurchase(request)
            request.method == "GET" && action.startsWith("purchases/") -> pollPurchase(request)
            request.method == "POST" && action == "license/refresh" -> refreshLicense(request)
            request.method == "POST" && action == "recover" -> recover(request)
            else -> failure(404, "not_found")
        }
    }

    fun resetIdentityState() {
        challenges.clear()
        quotes.clear()
        purchases.clear()
        identities.clear()
        usedPollNonces.clear()
    }

    private fun createChallenge(request: DeviceCommerceHttpRequest): DeviceCommerceHttpResponse {
        val body = request.jsonBody()
        val purpose = body.optString("purpose")
        if (purpose !in setOf("trial", "purchase", "refresh", "recover")) {
            return failure(400, "invalid_device_identity")
        }
        if (body.optString("packageName") != DeviceCommerceProductContract.PACKAGE_NAME) {
            return failure(403, "app_signature_mismatch")
        }
        val fingerprint = body.optString("deviceFingerprint")
        val signingCert = body.optString("signingCertSha256")
        val publicKeyBase64 = body.optString("devicePublicKeySpkiBase64")
        if (!fingerprint.matches(Regex("[a-f0-9]{64}")) ||
            !signingCert.matches(Regex("[a-f0-9]{64}"))
        ) {
            return failure(400, "invalid_device_identity")
        }
        val publicKey = parsePublicKey(publicKeyBase64)
            ?: return failure(400, "invalid_device_identity")
        val challenge = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val id = "dch_${UUID.randomUUID().toString().replace("-", "")}"
        val existingVersion = identities[fingerprint]?.keyVersion
        challenges[id] = ChallengeRecord(
            id = id,
            purpose = purpose,
            fingerprint = fingerprint,
            publicKeyBase64 = publicKeyBase64,
            publicKey = publicKey,
            challenge = challenge,
            identityKeyVersion = existingVersion,
            expiresAtEpochMs = nowEpochMs() + CHALLENGE_TTL_MS
        )
        return success(
            JSONObject()
                .put("challengeId", id)
                .put("challengeBase64", Base64.getEncoder().encodeToString(challenge))
                .put("expiresAt", utc(nowEpochMs() + CHALLENGE_TTL_MS))
                .put(
                    "product",
                    JSONObject().put("title", "03歌词 PRO 永久权益")
                )
                .put("serverTime", utc(nowEpochMs()))
        )
    }

    private fun readCampaign(): DeviceCommerceHttpResponse = success(
        JSONObject().put(
            "campaign",
            if (campaignAvailable) {
                JSONObject()
                    .put("discountCode", DebugCommercialFixtureCatalog.PUBLIC_CAMPAIGN_CODE)
                    .put("paymentRatioBps", pricing.paymentRatioBps)
                    .put("discountLabel", pricing.discountLabel)
            } else {
                JSONObject.NULL
            }
        )
    )

    private fun createQuote(request: DeviceCommerceHttpRequest): DeviceCommerceHttpResponse {
        val body = request.jsonBody()
        val fingerprint = body.optString("deviceFingerprint")
        if (!fingerprint.matches(Regex("[a-f0-9]{64}"))) {
            return failure(400, "invalid_device_fingerprint")
        }
        val code = body.optString("discountCode")
        val quote = buildQuote(code)
        quotes[quote.id] = quote
        return success(JSONObject().put("quote", quote.json))
    }

    private fun startTrial(request: DeviceCommerceHttpRequest): DeviceCommerceHttpResponse {
        val proof = consumeProof(request, "trial")
        if (proof is ProofResult.Failure) return proof.response
        val challenge = (proof as ProofResult.Success).challenge
        val identityResult = ensureIdentity(challenge)
        if (identityResult is IdentityResult.Failure) return identityResult.response
        val identity = (identityResult as IdentityResult.Success).identity
        if (entitlementScenario == DebugEntitlementScenario.REVOKED) {
            identity.pro = false
        }
        if (identity.pro || entitlementScenario == DebugEntitlementScenario.PRO) {
            identity.pro = true
            return accessSuccess("licensed", identity, CommercialTier.PRO)
        }
        if (identity.trialStartedAtEpochMs == null) {
            val firstOpenedAt = runCatching {
                Instant.parse(request.jsonBody().optString("firstOpenedAt")).toEpochMilli()
            }.getOrNull()?.coerceAtMost(nowEpochMs()) ?: nowEpochMs()
            identity.trialStartedAtEpochMs = firstOpenedAt
            identity.trialEndsAtEpochMs = firstOpenedAt + TRIAL_DURATION_MS
        }
        if (entitlementScenario == DebugEntitlementScenario.EXPIRED) {
            identity.trialStartedAtEpochMs = nowEpochMs() - TRIAL_DURATION_MS - 1
            identity.trialEndsAtEpochMs = nowEpochMs() - 1
        }
        val trialEndsAt = requireNotNull(identity.trialEndsAtEpochMs)
        if (nowEpochMs() >= trialEndsAt) {
            return failure(
                403,
                "trial_expired",
                JSONObject().put("status", "expired").put("trialEndsAt", utc(trialEndsAt))
            )
        }
        return accessSuccess("trial_active", identity, CommercialTier.TRIAL)
    }

    private fun refreshLicense(request: DeviceCommerceHttpRequest): DeviceCommerceHttpResponse {
        val proof = consumeProof(request, "refresh")
        if (proof is ProofResult.Failure) return proof.response
        val challenge = (proof as ProofResult.Success).challenge
        val identity = identities[challenge.fingerprint]
            ?: return failure(404, "access_not_started", JSONObject().put("status", "not_started"))
        if (identity.publicKeyBase64 != challenge.publicKeyBase64) {
            return failure(409, "device_key_mismatch")
        }
        if (entitlementScenario == DebugEntitlementScenario.REVOKED) {
            identity.pro = false
            return failure(403, "entitlement_revoked", JSONObject().put("status", "revoked"))
        }
        if (identity.pro) return accessSuccess("licensed", identity, CommercialTier.PRO)
        return when (entitlementScenario) {
            DebugEntitlementScenario.QUERY_ERROR -> throw IOException("fixture network unavailable")
            DebugEntitlementScenario.PRO -> {
                identity.pro = true
                accessSuccess("licensed", identity, CommercialTier.PRO)
            }
            DebugEntitlementScenario.EXPIRED -> {
                identity.pro = false
                identity.trialEndsAtEpochMs = nowEpochMs() - 1
                failure(
                    403,
                    "trial_expired",
                    JSONObject().put("status", "expired")
                        .put("trialEndsAt", utc(requireNotNull(identity.trialEndsAtEpochMs)))
                )
            }
            DebugEntitlementScenario.REVOKED -> {
                failure(403, "entitlement_revoked", JSONObject().put("status", "revoked"))
            }
            DebugEntitlementScenario.TRIAL -> {
                identity.pro = false
                if (identity.trialStartedAtEpochMs == null) {
                    identity.trialStartedAtEpochMs = nowEpochMs()
                    identity.trialEndsAtEpochMs = nowEpochMs() + TRIAL_DURATION_MS
                }
                val trialEndsAt = requireNotNull(identity.trialEndsAtEpochMs)
                if (trialEndsAt <= nowEpochMs()) {
                    return failure(
                        403,
                        "trial_expired",
                        JSONObject().put("status", "expired")
                            .put("trialEndsAt", utc(trialEndsAt))
                    )
                }
                accessSuccess("trial_active", identity, CommercialTier.TRIAL)
            }
        }
    }

    private fun createPurchase(request: DeviceCommerceHttpRequest): DeviceCommerceHttpResponse {
        val proof = consumeProof(request, "purchase")
        if (proof is ProofResult.Failure) return proof.response
        val challenge = (proof as ProofResult.Success).challenge
        val identityResult = ensureIdentity(challenge)
        if (identityResult is IdentityResult.Failure) return identityResult.response
        val identity = (identityResult as IdentityResult.Success).identity
        val body = request.jsonBody()
        val quote = quotes[body.optString("quoteId")]
            ?: return failure(410, "quote_expired")
        if (quote.expiresAtEpochMs <= nowEpochMs()) return failure(410, "quote_expired")
        val payWay = body.optString("payWay")
        if (payWay !in listOf("wechat_native", "alipay_qr")) {
            return failure(400, "invalid_pay_way")
        }
        if (paymentOutcome == DebugPaymentOutcome.QUOTE_CHANGED) {
            paymentOutcome = DebugPaymentOutcome.PENDING
            val latest = buildQuote(code = "")
            quotes[latest.id] = latest
            return failure(
                409,
                "quote_changed",
                JSONObject().put("latestQuote", latest.json)
            )
        }
        if (paymentOutcome == DebugPaymentOutcome.ALREADY_OWNED || identity.pro) {
            identity.pro = true
            return success(
                JSONObject()
                    .put("status", "already_owned")
                    .put("product", quote.productJson())
                    .put("license", issueLicense(identity, CommercialTier.PRO))
                    .put("serverTime", utc(nowEpochMs()))
            )
        }
        val purchaseId = "dps_${UUID.randomUUID().toString().replace("-", "")}"
        val pollToken = Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        )
        val purchase = PurchaseRecord(
            id = purchaseId,
            pollToken = pollToken,
            identity = identity,
            quote = quote,
            expiresAtEpochMs = nowEpochMs() + PURCHASE_TTL_MS
        )
        purchases[purchaseId] = purchase
        return success(
            JSONObject()
                .put("status", "payment_pending")
                .put("product", quote.productJson())
                .put("purchase", purchase.pendingJson(includeToken = true, includeQr = true))
                .put("serverTime", utc(nowEpochMs()))
        )
    }

    private fun pollPurchase(request: DeviceCommerceHttpRequest): DeviceCommerceHttpResponse {
        val purchaseId = request.path.substringAfterLast('/')
        val purchase = purchases[purchaseId] ?: return failure(401, "invalid_purchase_access")
        val bearer = request.headers["Authorization"].orEmpty().removePrefix("Bearer ")
        if (bearer != purchase.pollToken) return failure(401, "invalid_purchase_access")
        val timestamp = request.headers["X-Device-Timestamp"].orEmpty()
        val nonce = request.headers["X-Device-Nonce"].orEmpty()
        val signature = request.headers["X-Device-Signature"].orEmpty()
        val message = "$purchaseId\n${purchase.pollToken}\n$timestamp\n$nonce"
            .toByteArray(Charsets.UTF_8)
        if (!verifySignature(purchase.identity.publicKey, message, signature)) {
            return failure(401, "invalid_device_signature")
        }
        if (!usedPollNonces.add("$purchaseId:$nonce")) {
            return failure(409, "poll_nonce_replayed")
        }
        if (nowEpochMs() >= purchase.expiresAtEpochMs ||
            paymentOutcome == DebugPaymentOutcome.EXPIRED
        ) {
            return failure(410, "purchase_expired", JSONObject().put("status", "expired"))
        }
        if (paymentOutcome != DebugPaymentOutcome.PAID) {
            return success(
                JSONObject()
                    .put("status", "payment_pending")
                    .put("purchase", purchase.pendingJson(includeToken = false, includeQr = false))
                    .put("serverTime", utc(nowEpochMs()))
            )
        }
        purchase.identity.pro = true
        val response = JSONObject()
            .put("status", "licensed")
            .put("purchase", purchase.paidJson())
            .put("license", issueLicense(purchase.identity, CommercialTier.PRO))
            .put("serverTime", utc(nowEpochMs()))
        if (!purchase.credentialsDelivered) {
            purchase.credentialsDelivered = true
            if (!omitFirstPaidDeviceToken) {
                response.put("deviceToken", "debug-device-token-${purchase.identity.keyVersion}")
            }
        }
        return success(response)
    }

    private fun recover(request: DeviceCommerceHttpRequest): DeviceCommerceHttpResponse {
        if (recoveryScenario == DebugRecoveryScenario.NETWORK_ERROR) {
            throw IOException("fixture recovery unavailable")
        }
        val proof = consumeProof(request, "recover")
        if (proof is ProofResult.Failure) return proof.response
        val challenge = (proof as ProofResult.Success).challenge
        if (recoveryScenario == DebugRecoveryScenario.DIFFERENT_DEVICE) {
            return failure(409, "device_fingerprint_mismatch")
        }
        val existing = identities[challenge.fingerprint]
            ?: return failure(409, "device_fingerprint_mismatch")
        if (challenge.identityKeyVersion != existing.keyVersion) {
            return failure(409, "device_key_mismatch")
        }
        val keyChanged = existing.publicKeyBase64 != challenge.publicKeyBase64
        if (keyChanged) {
            val previousSignature = request.jsonBody().optString("previousKeySignatureBase64")
            if (previousSignature.isNotBlank() && !verifySignature(
                    existing.publicKey,
                    challenge.challenge,
                    previousSignature
                )
            ) {
                return failure(409, "device_key_mismatch")
            }
            existing.publicKeyBase64 = challenge.publicKeyBase64
            existing.publicKey = challenge.publicKey
            existing.keyVersion += 1
        }
        val tier = if (entitlementScenario == DebugEntitlementScenario.REVOKED) {
            CommercialTier.TRIAL
        } else if (recoveryGrantsPro) {
            CommercialTier.PRO
        } else {
            CommercialTier.TRIAL
        }
        existing.pro = tier == CommercialTier.PRO
        val response = JSONObject()
            .put("status", if (tier == CommercialTier.PRO) "licensed" else "trial_active")
            .put("license", issueLicense(existing, tier))
            .put("keyVersion", existing.keyVersion)
            .put("recoveryProof", if (keyChanged) "same_fingerprint" else "current_key")
            .put("serverTime", utc(nowEpochMs()))
        if (tier == CommercialTier.PRO) {
            response.put("deviceToken", "debug-recovered-device-token-${existing.keyVersion}")
        } else {
            response.put("trialStartedAt", utc(requireNotNull(existing.trialStartedAtEpochMs)))
            response.put("trialEndsAt", utc(requireNotNull(existing.trialEndsAtEpochMs)))
        }
        return success(response)
    }

    private fun consumeProof(
        request: DeviceCommerceHttpRequest,
        expectedPurpose: String
    ): ProofResult {
        val body = request.jsonBody()
        val id = body.optString("challengeId")
        val record = challenges[id] ?: return ProofResult.Failure(
            failure(400, "invalid_challenge")
        )
        if (record.purpose != expectedPurpose) {
            return ProofResult.Failure(failure(400, "invalid_challenge"))
        }
        if (record.consumed) return ProofResult.Failure(failure(409, "challenge_replayed"))
        if (record.expiresAtEpochMs <= nowEpochMs()) {
            return ProofResult.Failure(failure(410, "challenge_expired"))
        }
        val challengeBase64 = body.optString("challengeBase64")
        if (challengeBase64 != Base64.getEncoder().encodeToString(record.challenge)) {
            return ProofResult.Failure(failure(400, "invalid_challenge"))
        }
        if (!verifySignature(record.publicKey, record.challenge, body.optString("signatureBase64"))) {
            return ProofResult.Failure(failure(401, "invalid_device_signature"))
        }
        record.consumed = true
        return ProofResult.Success(record)
    }

    private fun ensureIdentity(challenge: ChallengeRecord): IdentityResult {
        val existing = identities[challenge.fingerprint]
        if (existing != null) {
            return if (existing.publicKeyBase64 == challenge.publicKeyBase64) {
                IdentityResult.Success(existing)
            } else {
                IdentityResult.Failure(failure(409, "device_key_mismatch"))
            }
        }
        val identity = IdentityRecord(
            fingerprint = challenge.fingerprint,
            publicKeyBase64 = challenge.publicKeyBase64,
            publicKey = challenge.publicKey,
            keyVersion = 1
        )
        identities[challenge.fingerprint] = identity
        return IdentityResult.Success(identity)
    }

    private fun accessSuccess(
        status: String,
        identity: IdentityRecord,
        tier: CommercialTier
    ): DeviceCommerceHttpResponse {
        val body = JSONObject()
            .put("status", status)
            .put("license", issueLicense(identity, tier))
            .put("serverTime", utc(nowEpochMs()))
        if (tier == CommercialTier.TRIAL) {
            body.put("trialStartedAt", utc(requireNotNull(identity.trialStartedAtEpochMs)))
            body.put("trialEndsAt", utc(requireNotNull(identity.trialEndsAtEpochMs)))
        }
        return success(body)
    }

    private fun issueLicense(identity: IdentityRecord, tier: CommercialTier): JSONObject {
        val now = nowEpochMs()
        val expiresAt = if (tier == CommercialTier.TRIAL) {
            requireNotNull(identity.trialEndsAtEpochMs)
        } else {
            now + PRO_REFRESH_INTERVAL_MS
        }
        val graceUntil = if (tier == CommercialTier.TRIAL) {
            expiresAt
        } else {
            now + PRO_FINAL_ACCESS_WINDOW_MS
        }
        val payload = JSONObject()
            .put("version", 1)
            .put("licenseId", "lic_${UUID.randomUUID().toString().replace("-", "")}")
            .put("keyId", licenseSigner.keyId)
            .put("productId", DeviceCommerceProductContract.PRODUCT_ID)
            .put("access", if (tier == CommercialTier.PRO) "pro" else "trial")
            .put(
                "devicePublicKeySha256",
                CommercialDigests.sha256Hex(Base64.getDecoder().decode(identity.publicKeyBase64))
            )
            .put("deviceKeyVersion", identity.keyVersion)
            .put("issuedAt", utc(now))
            .put("expiresAt", utc(expiresAt))
            .put("offlineGraceUntil", utc(graceUntil))
            .put(
                "trialEndsAt",
                if (tier == CommercialTier.TRIAL) {
                    utc(requireNotNull(identity.trialEndsAtEpochMs))
                } else {
                    JSONObject.NULL
                }
            )
            .toString()
            .toByteArray(Charsets.UTF_8)
        return JSONObject()
            .put("payloadBase64", Base64.getEncoder().encodeToString(payload))
            .put("signatureBase64", Base64.getEncoder().encodeToString(licenseSigner.sign(payload)))
            .put("keyId", licenseSigner.keyId)
    }

    private fun buildQuote(code: String): QuoteRecord {
        val normalized = code.filterNot(Char::isWhitespace).uppercase()
        val publicNormalized = DebugCommercialFixtureCatalog.PUBLIC_CAMPAIGN_CODE
            .filterNot(Char::isWhitespace)
            .uppercase()
        val status: String
        val errorCode: String?
        val applied: Boolean
        when {
            normalized.isBlank() -> {
                status = "none"
                errorCode = null
                applied = false
            }
            normalized == publicNormalized -> {
                status = "applied"
                errorCode = null
                applied = true
            }
            normalized == DebugCommercialFixtureCatalog.EXPIRED_DISCOUNT_CODE
                .filterNot(Char::isWhitespace).uppercase() -> {
                status = "invalid"
                errorCode = "discount_code_expired"
                applied = false
            }
            normalized == DebugCommercialFixtureCatalog.UNAVAILABLE_DISCOUNT_CODE
                .filterNot(Char::isWhitespace).uppercase() -> {
                status = "invalid"
                errorCode = "discount_code_unavailable"
                applied = false
            }
            else -> {
                status = "invalid"
                errorCode = "discount_code_invalid"
                applied = false
            }
        }
        val id = "dqt_${UUID.randomUUID().toString().replace("-", "")}"
        val finalAmount = if (applied) pricing.finalAmount else pricing.listAmount
        val calculatedAmount = if (applied) pricing.calculatedAmount else pricing.listAmount
        val ratio = if (applied) pricing.paymentRatioBps else 10_000
        val expires = nowEpochMs() + QUOTE_TTL_MS
        val json = JSONObject()
            .put("quoteId", id)
            .put("productId", DeviceCommerceProductContract.PRODUCT_ID)
            .put("sku", DeviceCommerceProductContract.SKU)
            .put("title", "03歌词 PRO 永久权益")
            .put("listAmount", pricing.listAmount)
            .put("displayListAmount", DebugCommercialFixtureCatalog.displayAmount(pricing.listAmount))
            .put("discountCode", code.takeIf(String::isNotBlank) ?: JSONObject.NULL)
            .put("discountStatus", status)
            .put(
                "discountError",
                errorCode?.let { JSONObject().put("code", it).put("message", discountMessage(it)) }
                    ?: JSONObject.NULL
            )
            .put("usageType", if (applied) "public_reusable" else "none")
            .put("paymentRatioBps", ratio)
            .put("discountLabel", if (applied) pricing.discountLabel else JSONObject.NULL)
            .put("calculatedAmount", calculatedAmount)
            .put("finalAmount", finalAmount)
            .put("displayFinalAmount", DebugCommercialFixtureCatalog.displayAmount(finalAmount))
            .put("minimumChargeApplied", applied && pricing.minimumChargeApplied)
            .put("currency", "CNY")
            .put("payWays", JSONArray(listOf("wechat_native", "alipay_qr")))
            .put("expiresAt", utc(expires))
        return QuoteRecord(id, json, expires)
    }

    private fun discountMessage(errorCode: String): String = when (errorCode) {
        "discount_code_expired" -> "折扣码已过期"
        "discount_code_unavailable" -> "折扣码暂不可用"
        else -> "折扣码无效"
    }

    private fun QuoteRecord.productJson(): JSONObject = JSONObject()
        .put("productId", DeviceCommerceProductContract.PRODUCT_ID)
        .put("sku", DeviceCommerceProductContract.SKU)
        .put("title", json.getString("title"))
        .put("listAmount", json.getInt("listAmount"))
        .put("displayListAmount", json.getString("displayListAmount"))
        .put("discountStatus", json.getString("discountStatus"))
        .put("paymentRatioBps", json.getInt("paymentRatioBps"))
        .put("discountLabel", json.opt("discountLabel"))
        .put("calculatedAmount", json.getInt("calculatedAmount"))
        .put("finalAmount", json.getInt("finalAmount"))
        .put("displayAmount", json.getString("displayFinalAmount"))
        .put("minimumChargeApplied", json.getBoolean("minimumChargeApplied"))
        .put("currency", json.getString("currency"))
        .put("payWays", json.getJSONArray("payWays"))

    private fun PurchaseRecord.pendingJson(includeToken: Boolean, includeQr: Boolean): JSONObject {
        val body = JSONObject()
            .put("purchaseId", id)
            .put("status", "pending")
            .put("amount", quote.json.getInt("finalAmount"))
            .put("displayAmount", quote.json.getString("displayFinalAmount"))
            .put("currency", "CNY")
            .put("expiresAt", utc(expiresAtEpochMs))
            .put("pollAfterSeconds", 2)
        if (includeToken) body.put("pollToken", pollToken)
        if (includeQr) {
            body.put("qrCodeFormat", "imageUrl")
            body.put("qrCodeValue", "https://fixture.03lyrics.invalid/$id")
        }
        return body
    }

    private fun PurchaseRecord.paidJson(): JSONObject = JSONObject()
        .put("purchaseId", id)
        .put("status", "paid")
        .put("amount", quote.json.getInt("finalAmount"))
        .put("displayAmount", quote.json.getString("displayFinalAmount"))
        .put("currency", "CNY")
        .put("paidAt", utc(nowEpochMs()))

    private fun DeviceCommerceHttpRequest.jsonBody(): JSONObject = JSONObject(body ?: "{}")

    private fun parsePublicKey(value: String): PublicKey? = runCatching {
        val publicKey = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(value))
        )
        val ecPublicKey = publicKey as ECPublicKey
        require(ecPublicKey.params.curve.field.fieldSize == 256)
        require(ecPublicKey.params.order.bitLength() == 256)
        publicKey
    }.getOrNull()

    private fun verifySignature(
        publicKey: PublicKey,
        message: ByteArray,
        signatureBase64: String
    ): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(message)
            verify(Base64.getDecoder().decode(signatureBase64))
        }
    }.getOrDefault(false)

    private fun success(value: JSONObject): DeviceCommerceHttpResponse =
        DeviceCommerceHttpResponse(200, value.put("ok", true).toString())

    private fun failure(
        status: Int,
        error: String,
        value: JSONObject = JSONObject()
    ): DeviceCommerceHttpResponse = DeviceCommerceHttpResponse(
        status,
        value.put("ok", false).put("error", error).toString()
    )

    private fun utc(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

    private data class ChallengeRecord(
        val id: String,
        val purpose: String,
        val fingerprint: String,
        val publicKeyBase64: String,
        val publicKey: PublicKey,
        val challenge: ByteArray,
        val identityKeyVersion: Int?,
        val expiresAtEpochMs: Long,
        var consumed: Boolean = false
    )

    private data class IdentityRecord(
        val fingerprint: String,
        var publicKeyBase64: String,
        var publicKey: PublicKey,
        var keyVersion: Int,
        var pro: Boolean = false,
        var trialStartedAtEpochMs: Long? = null,
        var trialEndsAtEpochMs: Long? = null
    )

    private data class QuoteRecord(
        val id: String,
        val json: JSONObject,
        val expiresAtEpochMs: Long
    )

    private data class PurchaseRecord(
        val id: String,
        val pollToken: String,
        val identity: IdentityRecord,
        val quote: QuoteRecord,
        val expiresAtEpochMs: Long,
        var credentialsDelivered: Boolean = false
    )

    private sealed interface ProofResult {
        data class Success(val challenge: ChallengeRecord) : ProofResult
        data class Failure(val response: DeviceCommerceHttpResponse) : ProofResult
    }

    private sealed interface IdentityResult {
        data class Success(val identity: IdentityRecord) : IdentityResult
        data class Failure(val response: DeviceCommerceHttpResponse) : IdentityResult
    }

    private companion object {
        const val CHALLENGE_TTL_MS = 5 * 60 * 1000L
        const val QUOTE_TTL_MS = 5 * 60 * 1000L
        const val PURCHASE_TTL_MS = 10 * 60 * 1000L
        const val TRIAL_DURATION_MS = 7L * 24 * 60 * 60 * 1000
        const val PRO_REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
        const val PRO_FINAL_ACCESS_WINDOW_MS = 90L * 24 * 60 * 60 * 1000
    }
}
