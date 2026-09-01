package com.ninepointnine.desktoplyrics.commercial

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class CommercialAccessTest {
    @Test
    fun `storage read failure fails closed`() {
        val gate = VerifiedLicenseAccessGate(
            store = FakeStore(readFailure = true),
            verifier = verifier()
        )

        assertEquals(
            CommercialAccessDecision.Denied(CommercialAccessDenial.STORAGE_FAILURE),
            gate.evaluate(NOW)
        )
    }

    @Test
    fun `revocation marker takes precedence over any stored license`() {
        val store = FakeStore().apply {
            values[SecureCommercialRecord.ACCESS_REVOCATION] = byteArrayOf(1)
            values[SecureCommercialRecord.LICENSE] = byteArrayOf(1)
        }

        val result = VerifiedLicenseAccessGate(store, verifier()).evaluate(NOW)

        assertEquals(
            CommercialAccessDecision.Denied(CommercialAccessDenial.ENTITLEMENT_REVOKED),
            result
        )
    }

    @Test
    fun `license clock rollback fails closed`() {
        val store = FakeStore().apply {
            values[SecureCommercialRecord.LICENSE_CLOCK] =
                SecureCommercialRecordCodec.encodeLong(
                    NOW + TrialPolicy.CLOCK_ROLLBACK_TOLERANCE_MS + 1
                )
        }

        val result = VerifiedLicenseAccessGate(store, verifier()).evaluate(NOW)

        assertEquals(
            CommercialAccessDecision.Denied(CommercialAccessDenial.CLOCK_ROLLBACK),
            result
        )
    }

    @Test
    fun `permanent signed pro license exposes no local expiry or renewal boundary`() {
        val keys = generateKeyPair()
        val payload = "permanent-payload".toByteArray()
        val envelope = SignedLicenseEnvelope(payload, sign(keys, payload), KEY_ID)
        val store = FakeStore().apply {
            values[SecureCommercialRecord.LICENSE] = SignedLicenseEnvelopeCodec.encode(envelope)
        }
        val verifier = LicenseVerifier(
            trustedPublicKey = keys.public,
            expectedKeyId = KEY_ID,
            expectedProductId = PRODUCT_ID,
            expectedDevicePublicKeySha256 = DEVICE_KEY,
            expectedDeviceKeyVersion = 1,
            parser = LicenseClaimsParser { validClaims() }
        )

        assertEquals(
            CommercialAccessDecision.Allowed(
                tier = CommercialTier.PRO,
                expiresAtEpochMs = null,
                offlineGraceUntilEpochMs = null
            ),
            VerifiedLicenseAccessGate(store, verifier).evaluate(NOW)
        )
    }

    private fun verifier(): LicenseVerifier {
        val keys = generateKeyPair()
        return LicenseVerifier(
            trustedPublicKey = keys.public,
            expectedKeyId = KEY_ID,
            expectedProductId = PRODUCT_ID,
            expectedDevicePublicKeySha256 = DEVICE_KEY,
            expectedDeviceKeyVersion = 1,
            parser = LicenseClaimsParser { validClaims() }
        )
    }

    private fun validClaims() = LicenseClaims(
        version = 1,
        licenseId = "license",
        keyId = KEY_ID,
        productId = PRODUCT_ID,
        devicePublicKeySha256 = DEVICE_KEY,
        deviceKeyVersion = 1,
        tier = CommercialTier.PRO,
        issuedAtEpochMs = NOW - 10_000,
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

    private class FakeStore(private val readFailure: Boolean = false) : SecureCommercialStore {
        val values = mutableMapOf<SecureCommercialRecord, ByteArray>()

        override fun read(record: SecureCommercialRecord): SecureStoreReadResult {
            if (readFailure) return SecureStoreReadResult.Failure
            return values[record]?.let(SecureStoreReadResult::Value)
                ?: SecureStoreReadResult.Missing
        }

        override fun write(record: SecureCommercialRecord, bytes: ByteArray): Boolean {
            values[record] = bytes
            return true
        }

        override fun delete(record: SecureCommercialRecord): Boolean {
            values.remove(record)
            return true
        }
    }

    private companion object {
        const val NOW = 10_000_000L
        const val KEY_ID = "key"
        const val PRODUCT_ID = "product"
        const val DEVICE_KEY = "device"
    }
}
