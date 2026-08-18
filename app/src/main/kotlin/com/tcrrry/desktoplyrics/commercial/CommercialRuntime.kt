package com.tcrrry.desktoplyrics.commercial

import android.content.Context

data class CommercialRuntime(
    val gateway: DeviceCommercialGateway,
    val accessGate: CommercialAccessGate,
    val entitlementCoordinator: CommercialEntitlementCoordinator
)

object CommercialRuntimeAssembler {
    fun create(
        context: Context,
        api: DeviceCommerceApi,
        trust: DeviceCommerceLicenseTrust,
        clientVersion: String
    ): CommercialRuntime {
        val appContext = context.applicationContext
        val store = AndroidSecureCommercialStore(appContext)
        val identity = AndroidDeviceIdentityManager(appContext, store)
        return create(
            api = api,
            trust = trust,
            clientVersion = clientVersion,
            store = store,
            identityProvider = identity
        )
    }

    internal fun create(
        api: DeviceCommerceApi,
        trust: DeviceCommerceLicenseTrust,
        clientVersion: String,
        store: SecureCommercialStore,
        identityProvider: DeviceIdentityProvider
    ): CommercialRuntime {
        val licenseRepository = CommercialLicenseRepository(
            store = store,
            identityProvider = identityProvider,
            trust = trust
        )
        val gateway = CloudDeviceCommercialGateway(
                api = api,
                identityProvider = identityProvider,
                store = store,
                trialRepository = FirstOpenTrialRepository(store),
                licenseRepository = licenseRepository,
                clientVersion = clientVersion
            )
        val accessGate = CommercialAccessGate(licenseRepository::accessDecision)
        return CommercialRuntime(
            gateway = gateway,
            accessGate = accessGate,
            entitlementCoordinator = CommercialEntitlementCoordinator(
                gateway = gateway,
                accessGate = accessGate
            )
        )
    }

    fun unavailable(): CommercialRuntime {
        val gateway = UnavailableDeviceCommercialGateway
        val accessGate = FailClosedCommercialAccessGate(
            CommercialAccessDenial.CONFIGURATION_MISSING
        )
        return CommercialRuntime(
            gateway = gateway,
            accessGate = accessGate,
            entitlementCoordinator = CommercialEntitlementCoordinator(gateway, accessGate)
        )
    }
}

private object UnavailableDeviceCommercialGateway : DeviceCommercialGateway {
    override suspend fun queryEntitlement(nowEpochMs: Long): EntitlementQueryResult =
        EntitlementQueryResult.Failure(CommercialFailure.CONFIGURATION_MISSING)

    override suspend fun requestQuote(
        discountCode: String,
        nowEpochMs: Long
    ): QuoteRequestResult = QuoteRequestResult.Failure(CommercialFailure.CONFIGURATION_MISSING)

    override suspend fun createPayment(
        quote: ProductQuote,
        method: PaymentMethod,
        nowEpochMs: Long
    ): PaymentCreationResult = PaymentCreationResult.Failure(
        CommercialFailure.CONFIGURATION_MISSING
    )

    override suspend fun refreshPayment(
        session: PaymentSession,
        nowEpochMs: Long
    ): PaymentStatusResult = PaymentStatusResult.Failure(
        CommercialFailure.CONFIGURATION_MISSING
    )

    override suspend fun restorePurchase(nowEpochMs: Long): PurchaseRecoveryResult =
        PurchaseRecoveryResult.Failure(CommercialFailure.CONFIGURATION_MISSING)
}
