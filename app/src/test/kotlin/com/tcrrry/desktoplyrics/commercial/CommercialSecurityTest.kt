package com.tcrrry.desktoplyrics.commercial

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
    fun `device fingerprint uses android id package and signing digest with nul separators`() {
        assertEquals(
            "a28fad2e0d11a8c685028bf71b5d6713c0af0fa4fe77fe5a9246e8d1ac78691f",
            CommercialDigests.deviceFingerprint(
                androidId = "android-id",
                packageName = "com.tcrrry.desktoplyrics",
                packageSignatureSha256 = "abcdef"
            )
        )
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
                issuedAtEpochMs = NOW + LicenseVerifier.LICENSE_CLOCK_SKEW_MS + 1,
                expiresAtEpochMs = NOW + LicenseVerifier.LICENSE_CLOCK_SKEW_MS + 2,
                offlineGraceUntilEpochMs = NOW + LicenseVerifier.LICENSE_CLOCK_SKEW_MS + 3
            )
        }
    }

    @Test
    fun `permanent license remains valid in signed offline grace then expires`() {
        val trusted = generateKeyPair()
        val payload = "signed".toByteArray()
        val envelope = SignedLicenseEnvelope(payload, sign(trusted, payload), KEY_ID)
        val claims = validClaims().copy(
            expiresAtEpochMs = NOW - 1,
            offlineGraceUntilEpochMs = NOW + 1
        )
        val verifier = verifier(trusted) { claims }

        val inGrace = verifier.verify(envelope, NOW) as LicenseVerificationResult.Valid
        assertEquals(LicenseValidityWindow.OFFLINE_GRACE, inGrace.window)
        assertEquals(
            LicenseVerificationResult.Invalid(LicenseVerificationFailure.EXPIRED),
            verifier.verify(envelope, NOW + 1)
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
        expiresAtEpochMs = NOW + 1_000,
        offlineGraceUntilEpochMs = NOW + 2_000,
        trialEndsAtEpochMs = null
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
