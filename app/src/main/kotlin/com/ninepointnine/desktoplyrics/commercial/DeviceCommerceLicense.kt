package com.ninepointnine.desktoplyrics.commercial

import org.json.JSONObject
import java.security.KeyFactory
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

data class DeviceCommerceLicenseTrust(
    val keyId: String,
    val publicKey: PublicKey
)

object DeviceCommerceLicenseTrustParser {
    fun parse(keyId: String, publicKeySpkiBase64: String): DeviceCommerceLicenseTrust? =
        runCatching {
            require(keyId.isNotBlank())
            val encoded = Base64.getDecoder().decode(publicKeySpkiBase64)
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
            val ecPublicKey = publicKey as? ECPublicKey ?: error("License key must be EC")
            require(ecPublicKey.params.curve.field.fieldSize == 256)
            require(ecPublicKey.params.order.bitLength() == 256)
            DeviceCommerceLicenseTrust(keyId = keyId, publicKey = publicKey)
        }.getOrNull()
}

object DeviceCommerceLicenseClaimsParser : LicenseClaimsParser {
    override fun parse(rawPayload: ByteArray): LicenseClaims {
        val payload = JSONObject(rawPayload.toString(Charsets.UTF_8))
        val access = payload.getString("access")
        val validity = when (payload.getString("validity")) {
            "permanent" -> LicenseValidity.PERMANENT
            "trial" -> LicenseValidity.TRIAL
            else -> error("Unsupported license validity")
        }
        if (validity == LicenseValidity.PERMANENT) {
            require(payload.has("expiresAt") && payload.isNull("expiresAt"))
            require(
                payload.has("offlineGraceUntil") &&
                    payload.isNull("offlineGraceUntil")
            )
            require(payload.has("trialEndsAt") && payload.isNull("trialEndsAt"))
        }
        return LicenseClaims(
            version = payload.getInt("version"),
            licenseId = payload.requiredString("licenseId"),
            keyId = payload.requiredString("keyId"),
            productId = payload.requiredString("productId"),
            devicePublicKeySha256 = payload.requiredString("devicePublicKeySha256"),
            deviceKeyVersion = payload.getInt("deviceKeyVersion"),
            tier = when (access) {
                "trial" -> CommercialTier.TRIAL
                "pro" -> CommercialTier.PRO
                else -> error("Unsupported license access")
            },
            issuedAtEpochMs = payload.requiredInstant("issuedAt"),
            expiresAtEpochMs = payload.optionalInstant("expiresAt"),
            offlineGraceUntilEpochMs = payload.optionalInstant("offlineGraceUntil"),
            trialEndsAtEpochMs = if (!payload.has("trialEndsAt") || payload.isNull("trialEndsAt")) {
                null
            } else {
                payload.requiredInstant("trialEndsAt")
            },
            validity = validity
        )
    }

    private fun JSONObject.requiredString(name: String): String = getString(name).also {
        require(it.isNotBlank())
    }

    private fun JSONObject.requiredInstant(name: String): Long =
        Instant.parse(requiredString(name)).toEpochMilli()

    private fun JSONObject.optionalInstant(name: String): Long? =
        if (!has(name) || isNull(name)) null else requiredInstant(name)
}
