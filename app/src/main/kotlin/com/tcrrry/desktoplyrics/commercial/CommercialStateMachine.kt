package com.tcrrry.desktoplyrics.commercial

sealed interface CommercialAction {
    data object QueryStarted : CommercialAction
    data class QueryCompleted(val snapshot: EntitlementSnapshot) : CommercialAction
    data class QueryFailed(val reason: CommercialFailure) : CommercialAction
    data object EntitlementPageRequested : CommercialAction
    data object CheckoutRequested : CommercialAction
    data class DiscountCodeChanged(val value: String) : CommercialAction
    data object QuoteStarted : CommercialAction
    data class QuoteCompleted(
        val quote: ProductQuote,
        val notice: QuoteNotice? = null
    ) : CommercialAction
    data class QuoteFailed(val reason: CommercialFailure) : CommercialAction
    data class PaymentMethodChanged(val method: PaymentMethod) : CommercialAction
    data object PaymentCreationStarted : CommercialAction
    data class PaymentCreated(val session: PaymentSession) : CommercialAction
    data class PaymentAlreadyOwned(val finalAmount: DisplayMoney) : CommercialAction
    data class PaymentQuoteChanged(val latestQuote: ProductQuote) : CommercialAction
    data class PaymentCreationFailed(val reason: CommercialFailure) : CommercialAction
    data object PaymentPending : CommercialAction
    data object PaymentPaid : CommercialAction
    data object PaymentExpired : CommercialAction
    data class PaymentRefreshFailed(val reason: CommercialFailure) : CommercialAction
    data object RecoveryStarted : CommercialAction
    data class RecoverySucceeded(val entitlement: EntitlementState) : CommercialAction
    data object RecoveryNotFound : CommercialAction
    data object RecoveryNetworkFailed : CommercialAction
    data class RecoveryFailed(val reason: CommercialFailure) : CommercialAction
}

class CommercialStateMachine(initialState: CommercialUiState = CommercialUiState()) {
    var state: CommercialUiState = initialState
        private set

    fun dispatch(action: CommercialAction): CommercialUiState {
        state = reduce(state, action)
        return state
    }

    private fun reduce(
        current: CommercialUiState,
        action: CommercialAction
    ): CommercialUiState = when (action) {
        CommercialAction.QueryStarted -> current.copy(
            entitlement = if (current.entitlement is EntitlementState.Error) {
                EntitlementState.Checking
            } else {
                current.entitlement
            },
            recovery = RecoveryState.Idle,
            queryRefreshing = true,
            quoteRefreshing = false,
            quoteNotice = null
        )

        is CommercialAction.QueryCompleted -> {
            val quote = action.snapshot.quote
            current.copy(
                entitlement = action.snapshot.entitlement,
                quote = quote,
                discountCode = quote?.discountCode.orEmpty(),
                selectedPaymentMethod = quote.defaultPaymentMethod(),
                checkout = action.snapshot.pendingPayment?.let {
                    CheckoutState.AwaitingPayment(it)
                }
                    ?: CheckoutState.Hidden,
                recovery = RecoveryState.Idle,
                queryRefreshing = false,
                quoteRefreshing = false,
                quoteNotice = null
            )
        }

        is CommercialAction.QueryFailed -> current.copy(
            entitlement = EntitlementState.Error(action.reason),
            quote = null,
            checkout = CheckoutState.Hidden,
            queryRefreshing = false,
            quoteRefreshing = false,
            quoteNotice = null
        )

        CommercialAction.EntitlementPageRequested -> current.copy(
            checkout = CheckoutState.Hidden,
            selectedPaymentMethod = current.quote.defaultPaymentMethod()
        )

        CommercialAction.CheckoutRequested -> {
            if (current.entitlement is EntitlementState.Pro || current.quote == null) current
            else current.copy(
                checkout = CheckoutState.Details,
                selectedPaymentMethod = current.quote.defaultPaymentMethod(),
                quoteNotice = null
            )
        }

        is CommercialAction.DiscountCodeChanged -> current.copy(
            discountCode = action.value,
            quoteNotice = null
        )

        CommercialAction.QuoteStarted -> current.copy(
            quoteRefreshing = true,
            quoteNotice = null
        )

        is CommercialAction.QuoteCompleted -> current.copy(
            quote = action.quote,
            discountCode = action.quote.discountCode ?: current.discountCode,
            selectedPaymentMethod = action.quote.defaultPaymentMethod(),
            checkout = CheckoutState.Details,
            quoteRefreshing = false,
            quoteNotice = action.notice
        )

        is CommercialAction.QuoteFailed -> current.copy(
            checkout = CheckoutState.Error(action.reason),
            quoteRefreshing = false,
            quoteNotice = null
        )

        is CommercialAction.PaymentMethodChanged -> {
            if (action.method in current.quote?.availablePaymentMethods.orEmpty()) {
                current.copy(selectedPaymentMethod = action.method)
            } else {
                current
            }
        }

        CommercialAction.PaymentCreationStarted -> current.copy(
            checkout = CheckoutState.CreatingPayment,
            quoteNotice = null
        )

        is CommercialAction.PaymentCreated -> current.copy(
            checkout = CheckoutState.AwaitingPayment(action.session)
        )

        is CommercialAction.PaymentAlreadyOwned -> current.copy(
            entitlement = EntitlementState.Pro,
            checkout = CheckoutState.Paid(action.finalAmount),
            recovery = RecoveryState.Idle,
            quoteNotice = null
        )

        is CommercialAction.PaymentQuoteChanged -> current.copy(
            quote = action.latestQuote,
            discountCode = action.latestQuote.discountCode ?: current.discountCode,
            selectedPaymentMethod = action.latestQuote.defaultPaymentMethod(),
            checkout = CheckoutState.Details,
            quoteNotice = QuoteNotice.PRICE_CHANGED
        )

        is CommercialAction.PaymentCreationFailed -> current.copy(
            checkout = CheckoutState.Error(action.reason),
            quoteNotice = null
        )

        CommercialAction.PaymentPending -> current.copy(
            checkout = (current.checkout as? CheckoutState.AwaitingPayment)?.copy(
                transientFailure = null
            ) ?: current.checkout
        )

        CommercialAction.PaymentPaid -> {
            val paidAmount = (current.checkout as? CheckoutState.AwaitingPayment)
                ?.session
                ?.finalAmount
                ?: current.quote?.finalPrice
                ?: DisplayMoney("--")
            current.copy(
                entitlement = EntitlementState.Pro,
                checkout = CheckoutState.Paid(paidAmount),
                recovery = RecoveryState.Idle,
                quoteNotice = null
            )
        }

        CommercialAction.PaymentExpired -> current.copy(checkout = CheckoutState.Expired)

        is CommercialAction.PaymentRefreshFailed -> {
            val awaiting = current.checkout as? CheckoutState.AwaitingPayment
            if (awaiting != null && action.reason in TRANSIENT_POLL_FAILURES) {
                current.copy(checkout = awaiting.copy(transientFailure = action.reason))
            } else {
                current.copy(checkout = CheckoutState.Error(action.reason))
            }
        }

        CommercialAction.RecoveryStarted -> current.copy(recovery = RecoveryState.Restoring)

        is CommercialAction.RecoverySucceeded -> current.copy(
            entitlement = action.entitlement,
            checkout = CheckoutState.Hidden,
            recovery = RecoveryState.Success,
            quote = if (action.entitlement is EntitlementState.Pro) null else current.quote
        )

        CommercialAction.RecoveryNotFound -> current.copy(recovery = RecoveryState.NotFound)

        CommercialAction.RecoveryNetworkFailed -> current.copy(
            recovery = RecoveryState.NetworkFailure
        )

        is CommercialAction.RecoveryFailed -> current.copy(
            recovery = RecoveryState.Failure(action.reason)
        )
    }

    private fun ProductQuote?.defaultPaymentMethod(): PaymentMethod = when {
        this == null -> PaymentMethod.WECHAT
        PaymentMethod.WECHAT in availablePaymentMethods -> PaymentMethod.WECHAT
        else -> availablePaymentMethods.firstOrNull() ?: PaymentMethod.WECHAT
    }

    private companion object {
        val TRANSIENT_POLL_FAILURES = setOf(
            CommercialFailure.NETWORK,
            CommercialFailure.RATE_LIMITED
        )
    }
}
