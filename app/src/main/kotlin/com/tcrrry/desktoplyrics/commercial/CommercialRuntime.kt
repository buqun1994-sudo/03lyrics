package com.tcrrry.desktoplyrics.commercial

import android.content.Context

data class CommercialRuntime(
    val gateway: DeviceCommercialGateway,
    val accessGate: CommercialAccessGate
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
        val licenseRepository = CommercialLicenseRepository(
            store = store,
            identityProvider = identity,
            trust = trust
        )
        return CommercialRuntime(
            gateway = CloudDeviceCommercialGateway(
                api = api,
                identityProvider = identity,
                store = store,
                trialRepository = FirstOpenTrialRepository(store),
                licenseRepository = licenseRepository,
                clientVersion = clientVersion
            ),
            accessGate = CommercialAccessGate(licenseRepository::accessDecision)
        )
    }

    fun unavailable(): CommercialRuntime = CommercialRuntime(
        gateway = UnavailableDeviceCommercialGateway,
        accessGate = FailClosedCommercialAccessGate(
            CommercialAccessDenial.CONFIGURATION_MISSING
        )
    )
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
