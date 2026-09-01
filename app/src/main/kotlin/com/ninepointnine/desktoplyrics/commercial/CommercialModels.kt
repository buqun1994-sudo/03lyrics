package com.ninepointnine.desktoplyrics.commercial

@JvmInline
value class DisplayMoney(val text: String) {
    init {
        require(text.isNotBlank())
    }
}

object DeviceCommerceProductContract {
    const val PRODUCT_ID = "03lyrics"
    const val SKU = "03lyrics_pro_device_cny"
    const val PACKAGE_NAME = "com.ninepointnine.desktoplyrics"
    const val RUNTIME_IDENTIFIER = "icar03"
    const val LOCALE = "zh-CN"
}

enum class DeviceCommerceEnvironment {
    FIXTURE,
    STAGING,
    PRODUCTION;

    companion object {
        fun parse(value: String): DeviceCommerceEnvironment? = entries.firstOrNull {
            it.name.equals(value.trim(), ignoreCase = true)
        }
    }
}

data class DeviceCommerceConfiguration(
    val environment: DeviceCommerceEnvironment,
    val apiBaseUrl: String,
    val licenseKeyId: String,
    val licensePublicKeyBase64: String
) {
    fun isCompleteForNetwork(): Boolean =
        environment != DeviceCommerceEnvironment.FIXTURE &&
            apiBaseUrl.isNotBlank() &&
            licenseKeyId.isNotBlank() &&
            licensePublicKeyBase64.isNotBlank()
}

enum class CommercialTier {
    TRIAL,
    PRO
}

sealed interface EntitlementState {
    data object Checking : EntitlementState

    data class Trial(
        val expiresAtEpochMs: Long,
        val remainingMillis: Long
    ) : EntitlementState

    data object Expired : EntitlementState

    data object Pro : EntitlementState

    data class Error(val reason: CommercialFailure) : EntitlementState
}

object CommercialAdPolicy {
    fun isVisible(entitlement: EntitlementState): Boolean = entitlement !is EntitlementState.Pro
}

enum class CommercialFailure {
    NETWORK,
    CONFIGURATION_MISSING,
    STORAGE,
    PROTOCOL,
    INVALID_LICENSE,
    DEVICE_MISMATCH,
    CLOCK_ROLLBACK,
    QUOTE_EXPIRED,
    PAYMENT,
    ENTITLEMENT_REVOKED,
    RATE_LIMITED,
    UNKNOWN
}

enum class DiscountResolution {
    NONE,
    VALID,
    INVALID,
    EXPIRED,
    UNAVAILABLE
}

enum class PaymentMethod(val protocolValue: String) {
    WECHAT("wechat_native"),
    ALIPAY("alipay_qr");

    companion object {
        fun fromProtocol(value: String): PaymentMethod? = entries.firstOrNull {
            it.protocolValue == value
        }
    }
}

data class ProductQuote(
    val quoteReference: String,
    val productId: String,
    val sku: String,
    val productName: String,
    val originalAmountCents: Int,
    val originalPrice: DisplayMoney,
    val calculatedAmountCents: Int,
    val finalAmountCents: Int,
    val finalPrice: DisplayMoney,
    val paymentRatioBps: Int,
    val discountLabel: String?,
    val discountCode: String?,
    val discountResolution: DiscountResolution,
    val minimumChargeApplied: Boolean,
    val currency: String,
    val availablePaymentMethods: Set<PaymentMethod>,
    val expiresAtEpochMs: Long
)

enum class PaymentQrFormat {
    IMAGE_URL
}

data class PaymentQrCode(
    val format: PaymentQrFormat,
    val value: String
)

data class PaymentSession(
    val purchaseReference: String,
    val finalAmountCents: Int,
    val finalAmount: DisplayMoney,
    val currency: String,
    val expiresAtEpochMs: Long,
    val pollAfterMillis: Long,
    val qrCode: PaymentQrCode
)

sealed interface CheckoutState {
    data object Hidden : CheckoutState
    data object Details : CheckoutState
    data object CreatingPayment : CheckoutState
    data class AwaitingPayment(
        val session: PaymentSession,
        val transientFailure: CommercialFailure? = null
    ) : CheckoutState
    data class Paid(val finalAmount: DisplayMoney) : CheckoutState
    data object Expired : CheckoutState
    data class Error(val reason: CommercialFailure) : CheckoutState
}

enum class QuoteNotice {
    PRICE_CHANGED,
    EXPIRED_REFRESHED
}

enum class CommercialPage {
    ENTITLEMENT,
    ORDER,
    QR
}

object CommercialPagePolicy {
    fun pageFor(checkout: CheckoutState): CommercialPage = when (checkout) {
        CheckoutState.Hidden, is CheckoutState.Paid -> CommercialPage.ENTITLEMENT
        is CheckoutState.AwaitingPayment -> CommercialPage.QR
        CheckoutState.Details,
        CheckoutState.CreatingPayment,
        CheckoutState.Expired,
        is CheckoutState.Error -> CommercialPage.ORDER
    }
}

sealed interface RecoveryState {
    data object Idle : RecoveryState
    data object Restoring : RecoveryState
    data object Success : RecoveryState
    data object NotFound : RecoveryState
    data object NetworkFailure : RecoveryState
    data class Failure(val reason: CommercialFailure) : RecoveryState
}

data class EntitlementSnapshot(
    val entitlement: EntitlementState,
    val quote: ProductQuote?,
    val pendingPayment: PaymentSession? = null
)

data class CommercialUiState(
    val entitlement: EntitlementState = EntitlementState.Checking,
    val quote: ProductQuote? = null,
    val discountCode: String = "",
    val selectedPaymentMethod: PaymentMethod = PaymentMethod.WECHAT,
    val checkout: CheckoutState = CheckoutState.Hidden,
    val recovery: RecoveryState = RecoveryState.Idle,
    val queryRefreshing: Boolean = false,
    val quoteRefreshing: Boolean = false,
    val quoteNotice: QuoteNotice? = null
)

sealed interface EntitlementQueryResult {
    data class Ready(val snapshot: EntitlementSnapshot) : EntitlementQueryResult
    data class Failure(val reason: CommercialFailure) : EntitlementQueryResult
}

sealed interface CommercialAccessRefreshResult {
    data class Ready(val entitlement: EntitlementState) : CommercialAccessRefreshResult
    data class Failure(val reason: CommercialFailure) : CommercialAccessRefreshResult
}

sealed interface QuoteRequestResult {
    data class Ready(val quote: ProductQuote) : QuoteRequestResult
    data class Failure(val reason: CommercialFailure) : QuoteRequestResult
}

sealed interface PaymentCreationResult {
    data class Ready(val session: PaymentSession) : PaymentCreationResult
    data object AlreadyOwned : PaymentCreationResult
    data class QuoteChanged(val latestQuote: ProductQuote) : PaymentCreationResult
    data object QuoteExpired : PaymentCreationResult
    data class Failure(val reason: CommercialFailure) : PaymentCreationResult
}

sealed interface PaymentStatusResult {
    data object Pending : PaymentStatusResult
    data object Paid : PaymentStatusResult
    data object Expired : PaymentStatusResult
    data class Failure(val reason: CommercialFailure) : PaymentStatusResult
}

sealed interface PurchaseRecoveryResult {
    data class Success(val entitlement: EntitlementState) : PurchaseRecoveryResult
    data object NotFound : PurchaseRecoveryResult
    data object NetworkFailure : PurchaseRecoveryResult
    data class Failure(val reason: CommercialFailure) : PurchaseRecoveryResult
}

interface DeviceCommercialGateway {
    /**
     * Read-only lifecycle entitlement check. Implementations may update local
     * credentials only when a separate purchase, trial-start, or recovery
     * flow explicitly returns a signed license.
     */
    suspend fun checkEntitlement(nowEpochMs: Long): CommercialAccessRefreshResult =
        refreshAccess(nowEpochMs)

    /**
     * Compatibility entry retained for callers compiled against the original
     * access-refresh API. New lifecycle code must call [checkEntitlement].
     */
    suspend fun refreshAccess(nowEpochMs: Long): CommercialAccessRefreshResult = when (
        val result = queryEntitlement(nowEpochMs)
    ) {
        is EntitlementQueryResult.Ready -> {
            CommercialAccessRefreshResult.Ready(result.snapshot.entitlement)
        }
        is EntitlementQueryResult.Failure -> CommercialAccessRefreshResult.Failure(result.reason)
    }

    suspend fun forceRefreshAccess(nowEpochMs: Long): CommercialAccessRefreshResult =
        checkEntitlement(nowEpochMs)

    suspend fun queryEntitlement(nowEpochMs: Long): EntitlementQueryResult

    suspend fun forceQueryEntitlement(nowEpochMs: Long): EntitlementQueryResult =
        queryEntitlement(nowEpochMs)

    suspend fun requestQuote(discountCode: String, nowEpochMs: Long): QuoteRequestResult

    suspend fun createPayment(
        quote: ProductQuote,
        method: PaymentMethod,
        nowEpochMs: Long
    ): PaymentCreationResult

    suspend fun refreshPayment(
        session: PaymentSession,
        nowEpochMs: Long
    ): PaymentStatusResult

    suspend fun restorePurchase(nowEpochMs: Long): PurchaseRecoveryResult
}
