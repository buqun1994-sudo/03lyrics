package com.tcrrry.desktoplyrics.commercial

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

internal object OfficialPackageIdentityPolicy {
    fun matches(expectedSha256: String, actualSha256: Collection<String>): Boolean {
        val expected = normalizeSha256(expectedSha256) ?: return false
        return actualSha256.any { actual ->
            val normalizedActual = normalizeSha256(actual) ?: return@any false
            MessageDigest.isEqual(expected.toByteArray(), normalizedActual.toByteArray())
        }
    }

    internal fun normalizeSha256(value: String): String? = value
        .replace(":", "")
        .filterNot(Char::isWhitespace)
        .lowercase()
        .takeIf { normalized ->
            normalized.length == SHA256_HEX_LENGTH && normalized.all { character ->
                character in '0'..'9' || character in 'a'..'f'
            }
        }

    private const val SHA256_HEX_LENGTH = 64
}

internal object AndroidPackageSignatures {
    @Volatile
    private var cachedSha256: Set<String>? = null

    @Suppress("DEPRECATION")
    fun currentSha256(context: Context): Set<String> = cachedSha256 ?: synchronized(this) {
        cachedSha256 ?: readCurrentSha256(context).also { cachedSha256 = it }
    }

    @Suppress("DEPRECATION")
    private fun readCurrentSha256(context: Context): Set<String> {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        } else {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            )
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requireNotNull(packageInfo.signingInfo).apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        }
        return signatures.mapTo(linkedSetOf()) { signature ->
            CommercialDigests.sha256Hex(signature.toByteArray())
        }.also { require(it.isNotEmpty()) }
    }
}

internal object AndroidOfficialPackageIntegrity {
    fun isTrusted(context: Context, expectedSigningCertSha256: String): Boolean = runCatching {
        OfficialPackageIdentityPolicy.matches(
            expectedSha256 = expectedSigningCertSha256,
            actualSha256 = AndroidPackageSignatures.currentSha256(context)
        )
    }.getOrDefault(false)
}
