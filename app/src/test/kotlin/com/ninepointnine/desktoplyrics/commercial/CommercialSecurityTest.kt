package com.ninepointnine.desktoplyrics.commercial

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class CommercialSecurityTest {
    @Test
    fun `challenge signature message is exactly the decoded challenge bytes`() {
        val challenge = byteArrayOf(0, 1, 2, 3, -1)

        assertArrayEquals(challenge, CommercialSignatureMessages.challenge(challenge))
        assertFalse(challenge === CommercialSignatureMessages.challenge(challenge))
    }

    @Test
    fun `purchase poll message matches the four line protocol`() {
        val message = CommercialSignatureMessages.purchasePoll(
            PurchasePollProofInput(
                purchaseReference = "dps_purchase",
                pollToken = "poll_token",
                timestampEpochSeconds = 1_234L,
                nonceBase64Url = "nonce_value"
            )
        )

        assertEquals(
            "dps_purchase\npoll_token\n1234\nnonce_value",
            message.toString(Charsets.UTF_8)
        )
    }

    @Test
    fun `p256 raw challenge signature verifies with exported spki key`() {
        val keyPair = generateKeyPair()
        val challenge = "challenge".toByteArray()
        val signed = sign(keyPair, challenge)

        val verified = Signature.getInstance("SHA256withECDSA").run {
            initVerify(keyPair.public)
            update(challenge)
            verify(signed)
        }

        assertTrue(verified)
        assertEquals(64, CommercialDigests.sha256Hex(keyPair.public.encoded).length)
    }

    @Test
    fun `device fingerprint hashes only android id`() {
        assertEquals(
            "df9356f532e1bbc39c579ecee7dc082cd4f9ea46810ffc28b9f68aa0e4b3655a",
            CommercialDigests.deviceFingerprint(androidId = "android-id")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `device fingerprint rejects missing android id`() {
        CommercialDigests.deviceFingerprint(androidId = "")
    }

    @Test
    fun `license verifies signature before parsing payload`() {
        val trusted = generateKeyPair()
        var parserCalls = 0
        val verifier = verifier(trusted) {
            parserCalls += 1
            validClaims()
        }
        val payload = "fixture-payload".toByteArray()
        val invalidSignature = sign(generateKeyPair(), payload)

        val result = verifier.verify(
            SignedLicenseEnvelope(payload, invalidSignature, KEY_ID),
            NOW
        )

        assertEquals(
            LicenseVerificationResult.Invalid(LicenseVerificationFailure.SIGNATURE),
            result
        )
        assertEquals(0, parserCalls)
    }

    @Test
    fun `license rejects product device key version and time boundary mismatches`() {
        val trusted = generateKeyPair()
        val payload = "signed".toByteArray()
        val envelope = SignedLicenseEnvelope(payload, sign(trusted, payload), KEY_ID)

        assertInvalid(trusted, envelope, LicenseVerificationFailure.PRODUCT) {
            validClaims().copy(productId = "other")
        }
        assertInvalid(trusted, envelope, LicenseVerificationFailure.DEVICE) {
            validClaims().copy(devicePublicKeySha256 = "different")
        }
        assertInvalid(trusted, envelope, LicenseVerificationFailure.DEVICE_KEY_VERSION) {
            validClaims().copy(deviceKeyVersion = 2)
        }
        assertEquals(
            LicenseVerificationResult.Invalid(LicenseVerificationFailure.KEY_ID),
            verifier(trusted) { validClaims() }.verify(
                envelope.copy(keyId = "other-key"),
                NOW
            )
        )
        assertInvalid(trusted, envelope, LicenseVerificationFailure.TIME_BOUNDARY) {
            validClaims().copy(offlineGraceUntilEpochMs = NOW)
        }
        assertInvalid(trusted, envelope, LicenseVerificationFailure.NOT_YET_VALID) {
            validClaims().copy(
                issuedAtEpochMs = NOW + LicenseVerifier.LICENSE_CLOCK_SKEW_MS + 1
            )
        }
    }

    @Test
    fun `permanent license requires explicit null boundaries and never expires`() {
        val trusted = generateKeyPair()
        val payload = "signed".toByteArray()
        val envelope = SignedLicenseEnvelope(payload, sign(trusted, payload), KEY_ID)
        val claims = validClaims()
        val verifier = verifier(trusted) { claims }

        val active = verifier.verify(envelope, NOW) as LicenseVerificationResult.Valid
        assertEquals(LicenseValidityWindow.ACTIVE, active.window)
        assertEquals(null, active.claims.finalAccessUntilEpochMs())
        assertTrue(verifier.verify(envelope, Long.MAX_VALUE) is LicenseVerificationResult.Valid)
    }

    @Test
    fun `trial license uses its 24 hour lease while retaining the seven day entitlement end`() {
        val trusted = generateKeyPair()
        val payload = "signed-trial".toByteArray()
        val envelope = SignedLicenseEnvelope(payload, sign(trusted, payload), KEY_ID)
        val claims = validClaims().copy(
            tier = CommercialTier.TRIAL,
            validity = LicenseValidity.TRIAL,
            issuedAtEpochMs = NOW - 20_000,
            expiresAtEpochMs = NOW + 10_000,
            offlineGraceUntilEpochMs = NOW + 10_000,
            trialEndsAtEpochMs = NOW + 20_000
        )

        val result = verifier(trusted) { claims }.verify(envelope, NOW)

        val valid = result as LicenseVerificationResult.Valid
        assertEquals(LicenseValidityWindow.TRIAL, valid.window)
        assertEquals(claims, valid.claims)
        assertEquals(NOW + 10_000, claims.finalAccessUntilEpochMs())
        assertEquals(
            LicenseVerificationResult.Invalid(LicenseVerificationFailure.EXPIRED),
            verifier(trusted) { claims }.verify(envelope, NOW + 10_000)
        )
    }

    @Test
    fun `trial license rejects a lease longer than 24 hours`() {
        val trusted = generateKeyPair()
        val payload = "long-trial".toByteArray()
        val envelope = SignedLicenseEnvelope(payload, sign(trusted, payload), KEY_ID)
        val claims = validClaims().copy(
            tier = CommercialTier.TRIAL,
            validity = LicenseValidity.TRIAL,
            expiresAtEpochMs = NOW + LicenseVerifier.TRIAL_LICENSE_MAX_DURATION_MS + 1,
            offlineGraceUntilEpochMs = NOW + LicenseVerifier.TRIAL_LICENSE_MAX_DURATION_MS + 1,
            trialEndsAtEpochMs = NOW + LicenseVerifier.TRIAL_LICENSE_MAX_DURATION_MS + 1
        )

        assertEquals(
            LicenseVerificationResult.Invalid(LicenseVerificationFailure.TIME_BOUNDARY),
            verifier(trusted) { claims }.verify(envelope, NOW)
        )
    }

    @Test
    fun `signed envelope round trips raw payload signature and key id`() {
        val envelope = SignedLicenseEnvelope(
            rawPayload = byteArrayOf(0, 1, 2, 0, 3),
            signature = byteArrayOf(9, 8, 7),
            keyId = KEY_ID
        )

        val decoded = SignedLicenseEnvelopeCodec.decode(SignedLicenseEnvelopeCodec.encode(envelope))

        assertArrayEquals(envelope.rawPayload, decoded.rawPayload)
        assertArrayEquals(envelope.signature, decoded.signature)
        assertEquals(KEY_ID, decoded.keyId)
    }

    @Test
    fun `trial detects material clock rollback without extending expiry`() {
        val clock = TrialClockState(startedAtEpochMs = 1_000_000L, lastObservedEpochMs = 2_000_000L)

        assertEquals(
            TrialEvaluation.ClockRollback,
            TrialPolicy.evaluate(
                clock,
                nowEpochMs = 2_000_000L - TrialPolicy.CLOCK_ROLLBACK_TOLERANCE_MS - 1,
                durationMillis = 2_000_000L
            )
        )
        val active = TrialPolicy.evaluate(
            clock,
            nowEpochMs = 2_000_000L,
            durationMillis = 2_000_000L
        ) as TrialEvaluation.Active
        assertEquals(3_000_000L, active.expiresAtEpochMs)
        assertEquals(1_000_000L, active.remainingMillis)
    }

    private fun assertInvalid(
        keyPair: java.security.KeyPair,
        envelope: SignedLicenseEnvelope,
        expected: LicenseVerificationFailure,
        claims: () -> LicenseClaims
    ) {
        assertEquals(
            LicenseVerificationResult.Invalid(expected),
            verifier(keyPair, LicenseClaimsParser { claims() }).verify(envelope, NOW)
        )
    }

    private fun verifier(
        keyPair: java.security.KeyPair,
        parser: LicenseClaimsParser
    ) = LicenseVerifier(
        trustedPublicKey = keyPair.public,
        expectedKeyId = KEY_ID,
        expectedProductId = PRODUCT_ID,
        expectedDevicePublicKeySha256 = DEVICE_KEY,
        expectedDeviceKeyVersion = 1,
        parser = parser
    )

    private fun validClaims() = LicenseClaims(
        version = 1,
        licenseId = "license",
        keyId = KEY_ID,
        productId = PRODUCT_ID,
        devicePublicKeySha256 = DEVICE_KEY,
        deviceKeyVersion = 1,
        tier = CommercialTier.PRO,
        issuedAtEpochMs = NOW - 1_000,
        expiresAtEpochMs = null,
        offlineGraceUntilEpochMs = null,
        trialEndsAtEpochMs = null,
        validity = LicenseValidity.PERMANENT
    )

    private fun generateKeyPair() = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private fun sign(keyPair: java.security.KeyPair, payload: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }

    private companion object {
        const val NOW = 10_000_000L
        const val KEY_ID = "key"
        const val PRODUCT_ID = "product"
        const val DEVICE_KEY = "device"
    }
}
