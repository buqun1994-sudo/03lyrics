package com.ninepointnine.desktoplyrics.commercial

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature

data class PurchasePollProofInput(
    val purchaseReference: String,
    val pollToken: String,
    val timestampEpochSeconds: Long,
    val nonceBase64Url: String
)

object CommercialSignatureMessages {
    fun challenge(challenge: ByteArray): ByteArray = challenge.copyOf()

    fun purchasePoll(input: PurchasePollProofInput): ByteArray = buildString {
        append(input.purchaseReference)
        append('\n')
        append(input.pollToken)
        append('\n')
        append(input.timestampEpochSeconds)
        append('\n')
        append(input.nonceBase64Url)
    }.toByteArray(StandardCharsets.UTF_8)
}

object CommercialDigests {
    fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

    fun sha256Hex(value: ByteArray): String = sha256(value).joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    fun deviceFingerprint(
        androidId: String,
        packageName: String,
        packageSignatureSha256: String
    ): String {
        val source = buildString {
            append(androidId)
            append('\u0000')
            append(packageName)
            append('\u0000')
            append(packageSignatureSha256)
        }
        return sha256Hex(source.toByteArray(StandardCharsets.UTF_8))
    }
}

data class LicenseClaims(
    val version: Int,
    val licenseId: String,
    val keyId: String,
    val productId: String,
    val devicePublicKeySha256: String,
    val deviceKeyVersion: Int,
    val tier: CommercialTier,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val offlineGraceUntilEpochMs: Long,
    val trialEndsAtEpochMs: Long?
) {
    /** The signed terminal boundary for the tier, never a client-derived value. */
    fun finalAccessUntilEpochMs(): Long = when (tier) {
        CommercialTier.TRIAL -> requireNotNull(trialEndsAtEpochMs)
        CommercialTier.PRO -> offlineGraceUntilEpochMs
    }
}

fun interface LicenseClaimsParser {
    fun parse(rawPayload: ByteArray): LicenseClaims
}

data class SignedLicenseEnvelope(
    val rawPayload: ByteArray,
    val signature: ByteArray,
    val keyId: String
)

enum class LicenseVerificationFailure {
    SIGNATURE,
    PAYLOAD,
    VERSION,
    KEY_ID,
    PRODUCT,
    DEVICE,
    DEVICE_KEY_VERSION,
    NOT_YET_VALID,
    TIME_BOUNDARY,
    EXPIRED
}

enum class LicenseValidityWindow {
    ACTIVE,
    TRIAL,
    OFFLINE_GRACE
}

sealed interface LicenseVerificationResult {
    data class Valid(
        val claims: LicenseClaims,
        val window: LicenseValidityWindow
    ) : LicenseVerificationResult

    data class Invalid(val reason: LicenseVerificationFailure) : LicenseVerificationResult
}

class LicenseVerifier(
    private val trustedPublicKey: PublicKey,
    private val expectedKeyId: String,
    private val expectedProductId: String,
    private val expectedDevicePublicKeySha256: String,
    private val expectedDeviceKeyVersion: Int?,
    private val parser: LicenseClaimsParser
) {
    /**
     * Returns claims after only the cryptographic envelope and payload checks.
     *
     * This is intentionally narrower than [verify] and is used only after a
     * full verification has classified a license as expired, so the signed
     * boundary can be reported without treating an expired license as valid.
     */
    internal fun readSignedClaims(envelope: SignedLicenseEnvelope): LicenseClaims? {
        if (!hasValidSignature(envelope)) return null
        return runCatching { parser.parse(envelope.rawPayload) }.getOrNull()
    }

    fun verify(
        envelope: SignedLicenseEnvelope,
        nowEpochMs: Long
    ): LicenseVerificationResult {
        if (!hasValidSignature(envelope)) {
            return LicenseVerificationResult.Invalid(LicenseVerificationFailure.SIGNATURE)
        }

        val claims = runCatching { parser.parse(envelope.rawPayload) }
            .getOrElse {
                return LicenseVerificationResult.Invalid(LicenseVerificationFailure.PAYLOAD)
            }
        if (claims.version != SUPPORTED_LICENSE_VERSION) {
            return LicenseVerificationResult.Invalid(LicenseVerificationFailure.VERSION)
        }
        if (envelope.keyId != expectedKeyId || claims.keyId != expectedKeyId ||
            envelope.keyId != claims.keyId
        ) {
            return LicenseVerificationResult.Invalid(LicenseVerificationFailure.KEY_ID)
        }
        if (claims.productId != expectedProductId) {
            return LicenseVerificationResult.Invalid(LicenseVerificationFailure.PRODUCT)
        }
        if (!constantTimeHexEquals(
                claims.devicePublicKeySha256,
                expectedDevicePublicKeySha256
            )
        ) {
            return LicenseVerificationResult.Invalid(LicenseVerificationFailure.DEVICE)
        }
        if (claims.deviceKeyVersion <= 0 ||
            expectedDeviceKeyVersion?.let { it != claims.deviceKeyVersion } == true
        ) {
            return LicenseVerificationResult.Invalid(
                LicenseVerificationFailure.DEVICE_KEY_VERSION
            )
        }
        if (claims.issuedAtEpochMs > nowEpochMs + LICENSE_CLOCK_SKEW_MS) {
            return LicenseVerificationResult.Invalid(LicenseVerificationFailure.NOT_YET_VALID)
        }
        if (claims.expiresAtEpochMs <= claims.issuedAtEpochMs ||
            claims.offlineGraceUntilEpochMs < claims.expiresAtEpochMs
        ) {
            return LicenseVerificationResult.Invalid(LicenseVerificationFailure.TIME_BOUNDARY)
        }
        val trialBoundaryValid = when (claims.tier) {
            CommercialTier.TRIAL -> claims.trialEndsAtEpochMs?.let {
                it >= claims.expiresAtEpochMs && it > claims.issuedAtEpochMs
            } == true
            CommercialTier.PRO -> claims.trialEndsAtEpochMs == null
        }
        if (!trialBoundaryValid) {
            return LicenseVerificationResult.Invalid(LicenseVerificationFailure.TIME_BOUNDARY)
        }
        val finalAccessUntil = claims.finalAccessUntilEpochMs()
        if (nowEpochMs >= finalAccessUntil) {
            return LicenseVerificationResult.Invalid(LicenseVerificationFailure.EXPIRED)
        }
        return LicenseVerificationResult.Valid(
            claims = claims,
            window = when {
                nowEpochMs < claims.expiresAtEpochMs -> LicenseValidityWindow.ACTIVE
                claims.tier == CommercialTier.TRIAL -> LicenseValidityWindow.TRIAL
                else -> LicenseValidityWindow.OFFLINE_GRACE
            }
        )
    }

    private fun hasValidSignature(envelope: SignedLicenseEnvelope): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(trustedPublicKey)
            update(envelope.rawPayload)
            verify(envelope.signature)
        }
    }.getOrDefault(false)

    private fun constantTimeHexEquals(first: String, second: String): Boolean {
        val firstBytes = first.lowercase().toByteArray(StandardCharsets.US_ASCII)
        val secondBytes = second.lowercase().toByteArray(StandardCharsets.US_ASCII)
        return MessageDigest.isEqual(firstBytes, secondBytes)
    }

    companion object {
        const val SUPPORTED_LICENSE_VERSION = 1
        const val LICENSE_CLOCK_SKEW_MS = 5 * 60 * 1000L
    }
}

object SignedLicenseEnvelopeCodec {
    private const val CURRENT_VERSION = 2
    private const val LEGACY_VERSION = 1
    private const val MAX_FIELD_BYTES = 256 * 1024
    private const val MAX_KEY_ID_BYTES = 256

    fun encode(envelope: SignedLicenseEnvelope): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeByte(CURRENT_VERSION)
            data.writeSized(envelope.rawPayload, MAX_FIELD_BYTES)
            data.writeSized(envelope.signature, MAX_FIELD_BYTES)
            data.writeSized(envelope.keyId.toByteArray(StandardCharsets.UTF_8), MAX_KEY_ID_BYTES)
        }
        return output.toByteArray()
    }

    fun decode(encoded: ByteArray): SignedLicenseEnvelope {
        DataInputStream(ByteArrayInputStream(encoded)).use { data ->
            return when (data.readUnsignedByte()) {
                CURRENT_VERSION -> {
                    val payload = data.readSized(MAX_FIELD_BYTES)
                    val signature = data.readSized(MAX_FIELD_BYTES)
                    val keyId = data.readSized(MAX_KEY_ID_BYTES).toString(StandardCharsets.UTF_8)
                    require(data.available() == 0)
                    SignedLicenseEnvelope(payload, signature, keyId)
                }
                LEGACY_VERSION -> {
                    val payload = data.readSized(MAX_FIELD_BYTES)
                    val signature = data.readSized(MAX_FIELD_BYTES)
                    require(data.available() == 0)
                    SignedLicenseEnvelope(payload, signature, keyId = "")
                }
                else -> error("Unsupported signed license envelope version")
            }
        }
    }

    private fun DataOutputStream.writeSized(value: ByteArray, maximum: Int) {
        require(value.size <= maximum)
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.readSized(maximum: Int): ByteArray {
        val size = readInt()
        require(size in 0..maximum)
        return ByteArray(size).also(::readFully)
    }
}

data class TrialClockState(
    val startedAtEpochMs: Long,
    val lastObservedEpochMs: Long
)

sealed interface TrialEvaluation {
    data class Active(
        val expiresAtEpochMs: Long,
        val remainingMillis: Long,
        val nextClockState: TrialClockState
    ) : TrialEvaluation

    data class Expired(val nextClockState: TrialClockState) : TrialEvaluation
    data object ClockRollback : TrialEvaluation
}

object TrialPolicy {
    const val CLOCK_ROLLBACK_TOLERANCE_MS = 5 * 60 * 1000L

    fun evaluate(
        clock: TrialClockState,
        nowEpochMs: Long,
        durationMillis: Long
    ): TrialEvaluation {
        require(durationMillis > 0)
        val trustedFloor = maxOf(clock.startedAtEpochMs, clock.lastObservedEpochMs)
        if (nowEpochMs + CLOCK_ROLLBACK_TOLERANCE_MS < trustedFloor) {
            return TrialEvaluation.ClockRollback
        }
        val nextClock = clock.copy(lastObservedEpochMs = maxOf(trustedFloor, nowEpochMs))
        val expiresAt = clock.startedAtEpochMs + durationMillis
        return if (nowEpochMs >= expiresAt) {
            TrialEvaluation.Expired(nextClock)
        } else {
            TrialEvaluation.Active(
                expiresAtEpochMs = expiresAt,
                remainingMillis = expiresAt - nowEpochMs,
                nextClockState = nextClock
            )
        }
    }
}
