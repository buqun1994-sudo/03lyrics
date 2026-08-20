package com.ninepointnine.desktoplyrics.commercial

import android.content.Context
import com.ninepointnine.desktoplyrics.BuildConfig

object CommercialRuntimeFactory {
    @Volatile
    private var runtime: CommercialRuntime? = null

    fun gateway(context: Context): DeviceCommercialGateway = runtime(context).gateway

    fun accessGate(context: Context): CommercialAccessGate = runtime(context).accessGate

    fun entitlementCoordinator(context: Context): CommercialEntitlementCoordinator =
        runtime(context).entitlementCoordinator

    private fun runtime(context: Context): CommercialRuntime = runtime
        ?: synchronized(this) {
            runtime ?: create(context.applicationContext).also { runtime = it }
        }

    private fun create(context: Context): CommercialRuntime {
        if (!AndroidOfficialPackageIntegrity.isTrusted(
                context,
                BuildConfig.DEVICE_COMMERCE_EXPECTED_SIGNING_CERT_SHA256
            )
        ) {
            return CommercialRuntimeAssembler.unavailable()
        }
        val configuration = DeviceCommerceConfiguration(
            environment = DeviceCommerceEnvironment.parse(
                BuildConfig.DEVICE_COMMERCE_ENVIRONMENT
            ) ?: return CommercialRuntimeAssembler.unavailable(),
            apiBaseUrl = BuildConfig.DEVICE_COMMERCE_API_BASE_URL,
            licenseKeyId = BuildConfig.DEVICE_COMMERCE_LICENSE_KEY_ID,
            licensePublicKeyBase64 = BuildConfig.DEVICE_COMMERCE_LICENSE_PUBLIC_KEY_BASE64
        )
        if (configuration.environment != DeviceCommerceEnvironment.PRODUCTION ||
            !configuration.isCompleteForNetwork()
        ) {
            return CommercialRuntimeAssembler.unavailable()
        }
        val trust = DeviceCommerceLicenseTrustParser.parse(
            configuration.licenseKeyId,
            configuration.licensePublicKeyBase64
        ) ?: return CommercialRuntimeAssembler.unavailable()
        return runCatching {
            CommercialRuntimeAssembler.create(
                context = context,
                api = DeviceCommerceJsonApi(
                    UrlConnectionDeviceCommerceTransport(configuration.apiBaseUrl)
                ),
                trust = trust,
                clientVersion = BuildConfig.VERSION_NAME
            )
        }.getOrElse { CommercialRuntimeAssembler.unavailable() }
    }
}
