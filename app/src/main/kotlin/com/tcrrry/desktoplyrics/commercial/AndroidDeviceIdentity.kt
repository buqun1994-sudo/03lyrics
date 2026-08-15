package com.tcrrry.desktoplyrics.commercial

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

enum class DeviceAttestationStatus {
    AVAILABLE,
    UNAVAILABLE
}

data class DeviceCommercialIdentity(
    val publicKeySpkiBase64: String,
    val publicKeySha256: String,
    val deviceFingerprintSha256: String,
    val signingCertSha256: String,
    val attestationStatus: DeviceAttestationStatus
)

interface RecoveryDeviceIdentitySession {
    val identity: DeviceCommercialIdentity
    fun signChallenge(challenge: ByteArray): ByteArray
    fun signWithPreviousKeyIfAvailable(challenge: ByteArray): ByteArray?
    fun commit(): Boolean
    fun abort()
}

interface DeviceIdentityProvider {
    fun loadOrCreate(): DeviceCommercialIdentity
    fun signChallenge(challenge: ByteArray): ByteArray
    fun signPurchasePoll(input: PurchasePollProofInput): ByteArray
    fun beginRecovery(rotateKnownKey: Boolean): RecoveryDeviceIdentitySession
}

class AndroidDeviceIdentityManager(
    context: Context,
    private val secureStore: SecureCommercialStore = AndroidSecureCommercialStore(context),
    private val baseKeyAlias: String = DEVICE_KEY_ALIAS
) : DeviceIdentityProvider {
    private val appContext = context.applicationContext
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    @Synchronized
    @SuppressLint("HardwareIds")
    override fun loadOrCreate(): DeviceCommercialIdentity = identityForAlias(activeAlias())

    @Synchronized
    override fun signChallenge(challenge: ByteArray): ByteArray = sign(
        alias = activeAlias(),
        message = CommercialSignatureMessages.challenge(challenge)
    )

    @Synchronized
    override fun signPurchasePoll(input: PurchasePollProofInput): ByteArray = sign(
        alias = activeAlias(),
        message = CommercialSignatureMessages.purchasePoll(input)
    )

    @Synchronized
    override fun beginRecovery(rotateKnownKey: Boolean): RecoveryDeviceIdentitySession {
        val previousAlias = activeAlias()
        ensureKeyPair(previousAlias)
        pendingRecoveryAlias(previousAlias)?.let { pendingAlias ->
            return AndroidRecoverySession(
                currentAlias = pendingAlias,
                previousAlias = previousAlias.takeIf { it != pendingAlias },
                pendingAlias = pendingAlias
            )
        }
        if (!rotateKnownKey) {
            return AndroidRecoverySession(
                currentAlias = previousAlias,
                previousAlias = null,
                pendingAlias = null
            )
        }

        val pendingAlias = "$baseKeyAlias.recovery.${UUID.randomUUID()}"
        ensureKeyPair(pendingAlias)
        if (!secureStore.write(
                SecureCommercialRecord.PENDING_DEVICE_KEY_ALIAS,
                pendingAlias.toByteArray(Charsets.UTF_8)
            )
        ) {
            runCatching { keyStore.deleteEntry(pendingAlias) }
            error("Unable to persist the pending commercial recovery key")
        }
        return AndroidRecoverySession(
            currentAlias = pendingAlias,
            previousAlias = previousAlias,
            pendingAlias = pendingAlias
        )
    }

    private inner class AndroidRecoverySession(
        private val currentAlias: String,
        private val previousAlias: String?,
        private val pendingAlias: String?
    ) : RecoveryDeviceIdentitySession {
        private var completed = false

        override val identity: DeviceCommercialIdentity
            get() = synchronized(this@AndroidDeviceIdentityManager) {
                identityForAlias(currentAlias)
            }

        override fun signChallenge(challenge: ByteArray): ByteArray =
            synchronized(this@AndroidDeviceIdentityManager) {
                sign(currentAlias, CommercialSignatureMessages.challenge(challenge))
            }

        override fun signWithPreviousKeyIfAvailable(challenge: ByteArray): ByteArray? =
            synchronized(this@AndroidDeviceIdentityManager) {
                previousAlias?.takeIf(::hasPrivateKey)?.let { alias ->
                    sign(alias, CommercialSignatureMessages.challenge(challenge))
                }
            }

        override fun commit(): Boolean = synchronized(this@AndroidDeviceIdentityManager) {
            if (completed) return@synchronized true
            if (pendingAlias == null) {
                completed = true
                return@synchronized true
            }
            if (!secureStore.write(
                    SecureCommercialRecord.DEVICE_KEY_ALIAS,
                    pendingAlias.toByteArray(Charsets.UTF_8)
                )
            ) {
                return@synchronized false
            }
            if (!secureStore.delete(SecureCommercialRecord.PENDING_DEVICE_KEY_ALIAS)) {
                return@synchronized false
            }
            previousAlias?.takeIf { it != pendingAlias }?.let { oldAlias ->
                runCatching { keyStore.deleteEntry(oldAlias) }
            }
            completed = true
            true
        }

        override fun abort() = synchronized(this@AndroidDeviceIdentityManager) {
            // A response can be lost after the server commits a key rotation. Keep the
            // pending key so the next recover request can safely resume with the same key.
        }
    }

    private fun pendingRecoveryAlias(activeAlias: String): String? = when (
        val stored = secureStore.read(SecureCommercialRecord.PENDING_DEVICE_KEY_ALIAS)
    ) {
        is SecureStoreReadResult.Value -> {
            val alias = stored.bytes.toString(Charsets.UTF_8)
            validateAlias(alias)
            if (alias == activeAlias) {
                secureStore.delete(SecureCommercialRecord.PENDING_DEVICE_KEY_ALIAS)
                null
            } else if (hasPrivateKey(alias) && keyStore.getCertificate(alias)?.publicKey != null) {
                alias
            } else {
                secureStore.delete(SecureCommercialRecord.PENDING_DEVICE_KEY_ALIAS)
                runCatching { keyStore.deleteEntry(alias) }
                null
            }
        }
        SecureStoreReadResult.Missing -> null
        SecureStoreReadResult.Failure -> error("Unable to read the pending recovery key alias")
    }

    @SuppressLint("HardwareIds")
    private fun identityForAlias(alias: String): DeviceCommercialIdentity {
        val publicKey = ensureKeyPair(alias).second
        val spki = publicKey.encoded
        val signingCertSha256 = packageSignatureSha256()
        return DeviceCommercialIdentity(
            publicKeySpkiBase64 = Base64.getEncoder().encodeToString(spki),
            publicKeySha256 = CommercialDigests.sha256Hex(spki),
            deviceFingerprintSha256 = CommercialDigests.deviceFingerprint(
                androidId = Settings.Secure.getString(
                    appContext.contentResolver,
                    Settings.Secure.ANDROID_ID
                ).orEmpty(),
                packageName = appContext.packageName,
                packageSignatureSha256 = signingCertSha256
            ),
            signingCertSha256 = signingCertSha256,
            attestationStatus = if (keyStore.getCertificateChain(alias)?.size.orZero() > 1) {
                DeviceAttestationStatus.AVAILABLE
            } else {
                DeviceAttestationStatus.UNAVAILABLE
            }
        )
    }

    private fun activeAlias(): String = when (
        val stored = secureStore.read(SecureCommercialRecord.DEVICE_KEY_ALIAS)
    ) {
        is SecureStoreReadResult.Value -> stored.bytes.toString(Charsets.UTF_8).also { alias ->
            validateAlias(alias)
        }
        SecureStoreReadResult.Missing -> baseKeyAlias
        SecureStoreReadResult.Failure -> error("Unable to read the active commercial key alias")
    }

    private fun validateAlias(alias: String) {
        require(alias == baseKeyAlias || alias.startsWith("$baseKeyAlias.recovery."))
        require(alias.length <= MAX_ALIAS_LENGTH)
    }

    private fun sign(alias: String, message: ByteArray): ByteArray {
        val privateKey = ensureKeyPair(alias).first
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(message)
            sign()
        }
    }

    private fun hasPrivateKey(alias: String): Boolean =
        keyStore.getKey(alias, null) is PrivateKey

    private fun ensureKeyPair(alias: String): Pair<PrivateKey, PublicKey> {
        val existingPrivate = keyStore.getKey(alias, null) as? PrivateKey
        val existingPublic = keyStore.getCertificate(alias)?.publicKey
        if (existingPrivate != null && existingPublic != null) {
            return existingPrivate to existingPublic
        }

        val generatedWithProbe = runCatching {
            generateKey(
                alias = alias,
                attestationChallenge = ByteArray(32).also(SecureRandom()::nextBytes)
            )
        }.getOrNull()
        if (generatedWithProbe == null) {
            runCatching { keyStore.deleteEntry(alias) }
            generateKey(alias = alias, attestationChallenge = null)
        }
        val privateKey = keyStore.getKey(alias, null) as PrivateKey
        val publicKey = requireNotNull(keyStore.getCertificate(alias)?.publicKey)
        return privateKey to publicKey
    }

    private fun generateKey(alias: String, attestationChallenge: ByteArray?) {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
        if (attestationChallenge != null) {
            builder.setAttestationChallenge(attestationChallenge)
        }
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE).run {
            initialize(builder.build())
            generateKeyPair()
        }
    }

    @Suppress("DEPRECATION")
    private fun packageSignatureSha256(): String {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        } else {
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.GET_SIGNATURES
            )
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requireNotNull(packageInfo.signingInfo).apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        }
        return signatures
            .map { signature -> CommercialDigests.sha256Hex(signature.toByteArray()) }
            .sorted()
            .first()
    }

    private fun Int?.orZero(): Int = this ?: 0

    companion object {
        const val DEVICE_KEY_ALIAS = "03lyrics_device_key_v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val MAX_ALIAS_LENGTH = 160
    }
}
