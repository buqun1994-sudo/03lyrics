package com.ninepointnine.desktoplyrics.commercial

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.util.Base64

class DeviceCommerceJsonApiTest {
    @Test
    fun `entitlement check posts the check proof to the lifecycle endpoint`() {
        var captured: DeviceCommerceHttpRequest? = null
        val api = DeviceCommerceJsonApi { request ->
            captured = request
            response(
                JSONObject()
                    .put("ok", true)
                    .put("status", "active")
                    .put("deviceKeyVersion", 3)
            )
        }

        val result = api.checkEntitlement(
            DeviceChallengeProof(
                challengeId = "dch_test",
                challengeBase64 = "challenge",
                signatureBase64 = "signature"
            )
        )

        assertEquals(
            DeviceCommerceApiResult.Success(DeviceEntitlementCheckPayload("active", 3)),
            result
        )
        val request = requireNotNull(captured)
        assertEquals("POST", request.method)
        assertEquals(
            "/v1/products/03lyrics/device-access/license/check",
            request.path
        )
        val body = JSONObject(requireNotNull(request.body))
        assertEquals("dch_test", body.getString("challengeId"))
        assertEquals("challenge", body.getString("challengeBase64"))
        assertEquals("signature", body.getString("signatureBase64"))
    }

    @Test
    fun `entitlement check accepts exactly the four public states`() {
        listOf("active", "revoked", "not_started", "device_key_mismatch").forEach { status ->
            val api = DeviceCommerceJsonApi {
                response(JSONObject().put("ok", true).put("status", status))
            }

            val result = api.checkEntitlement(proof())

            assertEquals(
                DeviceCommerceApiResult.Success(DeviceEntitlementCheckPayload(status, null)),
                result
            )
        }
    }

    @Test
    fun `unknown entitlement check state is a protocol failure`() {
        val api = DeviceCommerceJsonApi {
            response(JSONObject().put("ok", true).put("status", "unexpected"))
        }

        val result = api.checkEntitlement(proof())

        assertTrue(result is DeviceCommerceApiResult.Failure)
        assertEquals(
            DeviceCommerceApiFailureKind.PROTOCOL,
            (result as DeviceCommerceApiResult.Failure).failure.kind
        )
    }

    @Test
    fun `permanent license payload preserves explicit null time fields`() {
        val payload = baseLicensePayload()
            .put("access", "pro")
            .put("validity", "permanent")
            .put("expiresAt", JSONObject.NULL)
            .put("offlineGraceUntil", JSONObject.NULL)
            .put("trialEndsAt", JSONObject.NULL)

        val claims = DeviceCommerceLicenseClaimsParser.parse(
            payload.toString().toByteArray(Charsets.UTF_8)
        )

        assertEquals(LicenseValidity.PERMANENT, claims.validity)
        assertNull(claims.expiresAtEpochMs)
        assertNull(claims.offlineGraceUntilEpochMs)
        assertNull(claims.trialEndsAtEpochMs)
    }

    @Test
    fun `permanent license requires all three explicit null boundaries`() {
        val payload = baseLicensePayload()
            .put("access", "pro")
            .put("validity", "permanent")
            .put("expiresAt", JSONObject.NULL)
            .put("offlineGraceUntil", JSONObject.NULL)

        try {
            DeviceCommerceLicenseClaimsParser.parse(
                payload.toString().toByteArray(Charsets.UTF_8)
            )
            fail("missing trialEndsAt must be rejected for permanent licenses")
        } catch (_: IllegalArgumentException) {
            // Expected protocol rejection.
        }
    }

    @Test
    fun `license without validity is rejected before launch`() {
        val payload = baseLicensePayload()
            .put("access", "pro")
            .put("expiresAt", JSONObject.NULL)
            .put("offlineGraceUntil", JSONObject.NULL)
            .put("trialEndsAt", JSONObject.NULL)

        try {
            DeviceCommerceLicenseClaimsParser.parse(
                payload.toString().toByteArray(Charsets.UTF_8)
            )
            fail("missing validity must be rejected")
        } catch (_: Exception) {
            // Expected protocol rejection.
        }
    }

    @Test
    fun `permanent license requires explicit null expiry and grace boundaries`() {
        val payload = baseLicensePayload()
            .put("access", "pro")
            .put("validity", "permanent")
            .put("offlineGraceUntil", JSONObject.NULL)

        try {
            DeviceCommerceLicenseClaimsParser.parse(
                payload.toString().toByteArray(Charsets.UTF_8)
            )
            fail("missing expiresAt must be rejected for permanent licenses")
        } catch (_: IllegalArgumentException) {
            // Expected protocol rejection.
        }
    }

    private fun proof() = DeviceChallengeProof(
        challengeId = "dch_test",
        challengeBase64 = "challenge",
        signatureBase64 = "signature"
    )

    private fun baseLicensePayload() = JSONObject()
        .put("version", 1)
        .put("licenseId", "lic_test")
        .put("keyId", "key_test")
        .put("productId", DeviceCommerceProductContract.PRODUCT_ID)
        .put("devicePublicKeySha256", "a".repeat(64))
        .put("deviceKeyVersion", 1)
        .put("issuedAt", Instant.ofEpochMilli(1_000L).toString())

    private fun response(body: JSONObject): DeviceCommerceHttpResponse =
        DeviceCommerceHttpResponse(statusCode = 200, body = body.toString())
}
