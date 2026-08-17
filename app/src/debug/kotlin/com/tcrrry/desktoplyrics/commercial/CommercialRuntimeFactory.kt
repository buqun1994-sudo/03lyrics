package com.tcrrry.desktoplyrics.commercial

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.tcrrry.desktoplyrics.BuildConfig
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

object CommercialRuntimeFactory {
    @Volatile
    private var runtime: DebugCommercialRuntime? = null

    fun gateway(context: Context): DeviceCommercialGateway = runtime(context).components.gateway

    fun accessGate(context: Context): CommercialAccessGate = runtime(context).components.accessGate

    internal fun debugRuntime(context: Context): DebugCommercialRuntime = runtime(context)

    internal fun createIsolatedFixtureRuntime(
        store: SecureCommercialStore,
        identityProvider: DeviceIdentityProvider,
        signerKeyAlias: String
    ): DebugCommercialRuntime = DebugCommercialRuntime.createFixture(
        store = store,
        identityProvider = identityProvider,
        signerKeyAlias = signerKeyAlias
    )

    private fun runtime(context: Context): DebugCommercialRuntime = runtime
        ?: synchronized(this) {
            runtime ?: DebugCommercialRuntime.create(context.applicationContext).also { runtime = it }
        }
}

internal class DebugCommercialRuntime private constructor(
    val components: CommercialRuntime,
    private val fixtureTransport: FixtureDeviceCommerceTransport?,
    private val store: SecureCommercialStore
) {
    val gateway: DeviceCommercialGateway
        get() = components.gateway

    val accessGate: CommercialAccessGate
        get() = components.accessGate

    var recoveryScenario: DebugRecoveryScenario
        get() = fixtureTransport?.recoveryScenario ?: DebugRecoveryScenario.NETWORK_ERROR
        set(value) {
            fixtureTransport?.recoveryScenario = value
        }

    var paymentOutcome: DebugPaymentOutcome
        get() = fixtureTransport?.paymentOutcome ?: DebugPaymentOutcome.PENDING
        set(value) {
            fixtureTransport?.paymentOutcome = value
        }

    fun selectEntitlementScenario(scenario: DebugEntitlementScenario) {
        if (scenario == DebugEntitlementScenario.REVOKED) {
            fixtureTransport?.entitlementScenario = scenario
            return
        }
        fixtureTransport?.resetIdentityState()
        fixtureTransport?.entitlementScenario = scenario
        fixtureTransport?.paymentOutcome = DebugPaymentOutcome.PENDING
        store.delete(SecureCommercialRecord.POLL_TOKEN)
        store.delete(SecureCommercialRecord.PURCHASE_SESSION)
        store.delete(SecureCommercialRecord.LICENSE)
        store.delete(SecureCommercialRecord.ACCESS_REVOCATION)
        store.delete(SecureCommercialRecord.LICENSE_CLOCK)
        store.delete(SecureCommercialRecord.DEVICE_TOKEN)
        store.delete(SecureCommercialRecord.DEVICE_KEY_VERSION)
    }

    companion object {
        fun create(context: Context): DebugCommercialRuntime {
            val store = AndroidSecureCommercialStore(context)
            return when (DeviceCommerceEnvironment.parse(BuildConfig.DEVICE_COMMERCE_ENVIRONMENT)) {
                DeviceCommerceEnvironment.FIXTURE -> createFixture(
                    store = store,
                    identityProvider = AndroidDeviceIdentityManager(context, store),
                    signerKeyAlias = DEFAULT_FIXTURE_SIGNER_KEY_ALIAS
                )
                DeviceCommerceEnvironment.STAGING -> {
                    val trust = DeviceCommerceLicenseTrustParser.parse(
                        BuildConfig.DEVICE_COMMERCE_LICENSE_KEY_ID,
                        BuildConfig.DEVICE_COMMERCE_LICENSE_PUBLIC_KEY_BASE64
                    )
                    val components = if (trust != null &&
                        BuildConfig.DEVICE_COMMERCE_API_BASE_URL.isNotBlank() &&
                        AndroidOfficialPackageIntegrity.isTrusted(
                            context,
                            BuildConfig.DEVICE_COMMERCE_EXPECTED_SIGNING_CERT_SHA256
                        )
                    ) {
                        runCatching {
                            CommercialRuntimeAssembler.create(
                                context = context,
                                api = DeviceCommerceJsonApi(
                                    UrlConnectionDeviceCommerceTransport(
                                        BuildConfig.DEVICE_COMMERCE_API_BASE_URL
                                    )
                                ),
                                trust = trust,
                                clientVersion = BuildConfig.VERSION_NAME
                            )
                        }.getOrElse { CommercialRuntimeAssembler.unavailable() }
                    } else {
                        CommercialRuntimeAssembler.unavailable()
                    }
                    DebugCommercialRuntime(components, fixtureTransport = null, store = store)
                }
                DeviceCommerceEnvironment.PRODUCTION,
                null -> DebugCommercialRuntime(
                    CommercialRuntimeAssembler.unavailable(),
                    fixtureTransport = null,
                    store = store
                )
            }
        }

        fun createFixture(
            store: SecureCommercialStore,
            identityProvider: DeviceIdentityProvider,
            signerKeyAlias: String
        ): DebugCommercialRuntime {
            val signer = DebugFixtureLicenseSigner(signerKeyAlias)
            val transport = FixtureDeviceCommerceTransport(signer)
            val components = CommercialRuntimeAssembler.create(
                api = DeviceCommerceJsonApi(transport),
                trust = DeviceCommerceLicenseTrust(signer.keyId, signer.publicKey()),
                clientVersion = BuildConfig.VERSION_NAME,
                store = store,
                identityProvider = identityProvider
            )
            return DebugCommercialRuntime(components, transport, store)
        }
    }
}

private class DebugFixtureLicenseSigner(
    private val keyAlias: String
) : FixtureLicenseSigner {
    private val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    override val keyId: String = DebugCommercialFixtureCatalog.LICENSE_KEY_ID

    @Synchronized
    override fun publicKey(): PublicKey {
        ensureKey()
        return requireNotNull(keyStore.getCertificate(keyAlias)?.publicKey)
    }

    @Synchronized
    override fun sign(payload: ByteArray): ByteArray {
        ensureKey()
        val privateKey = keyStore.getKey(keyAlias, null) as PrivateKey
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(payload)
            sign()
        }
    }

    private fun ensureKey() {
        if (keyStore.containsAlias(keyAlias)) return
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE).run {
            initialize(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generateKeyPair()
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }
}

private const val DEFAULT_FIXTURE_SIGNER_KEY_ALIAS = "03lyrics_debug_fixture_license_signer_v2"
