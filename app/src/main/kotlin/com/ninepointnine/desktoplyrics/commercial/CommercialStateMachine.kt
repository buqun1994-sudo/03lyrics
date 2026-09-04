package com.ninepointnine.desktoplyrics.commercial

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
            quoteNotice = null,
            // A new lifecycle may restore a persisted pending payment. Any
            // explicit navigation after this action owns the eventual result.
            navigationIntent = null
        )

        is CommercialAction.QueryCompleted -> {
            val quote = action.snapshot.quote ?: current.quote.takeIf {
                current.navigationIntent == CommercialNavigationIntent.ORDER &&
                    action.snapshot.entitlement !is EntitlementState.Pro &&
                    action.snapshot.entitlement !is EntitlementState.Error
            }
            val authoritativePageOwner = if (
                action.snapshot.entitlement is EntitlementState.Pro ||
                action.snapshot.entitlement is EntitlementState.Error
            ) {
                CommercialNavigationIntent.ENTITLEMENT
            } else {
                current.navigationIntent
            }
            current.copy(
                entitlement = action.snapshot.entitlement,
                quote = quote,
                discountCode = quote?.discountCode.orEmpty(),
                selectedPaymentMethod = quote.defaultPaymentMethod(),
                checkout = checkoutAfterQuery(current, action.snapshot),
                recovery = RecoveryState.Idle,
                queryRefreshing = false,
                quoteRefreshing = false,
                quoteNotice = null,
                navigationIntent = authoritativePageOwner
            )
        }

        is CommercialAction.QueryFailed -> {
            if (action.reason == CommercialFailure.ENTITLEMENT_REVOKED) {
                current.copy(
                    entitlement = EntitlementState.Error(action.reason),
                    quote = null,
                    checkout = CheckoutState.Hidden,
                    queryRefreshing = false,
                    quoteRefreshing = false,
                    quoteNotice = null,
                    navigationIntent = CommercialNavigationIntent.ENTITLEMENT
                )
            } else if (action.reason in TRANSIENT_QUERY_FAILURES &&
                current.navigationIntent != null
            ) {
                // A transient lifecycle failure does not invalidate the
                // user's current page or the locally rendered quote.
                current.copy(queryRefreshing = false)
            } else {
                current.copy(
                    entitlement = EntitlementState.Error(action.reason),
                    quote = null,
                    checkout = CheckoutState.Hidden,
                    queryRefreshing = false,
                    quoteRefreshing = false,
                    quoteNotice = null,
                    navigationIntent = null
                )
            }
        }

        CommercialAction.EntitlementPageRequested -> current.copy(
            checkout = CheckoutState.Hidden,
            selectedPaymentMethod = current.quote.defaultPaymentMethod(),
            quoteRefreshing = false,
            navigationIntent = CommercialNavigationIntent.ENTITLEMENT
        )

        CommercialAction.CheckoutRequested -> {
            if (current.entitlement is EntitlementState.Pro || current.quote == null) current
            else current.copy(
                checkout = CheckoutState.Details,
                selectedPaymentMethod = current.quote.defaultPaymentMethod(),
                quoteNotice = null,
                navigationIntent = CommercialNavigationIntent.ORDER
            )
        }

        is CommercialAction.DiscountCodeChanged -> current.copy(
            discountCode = action.value,
            quoteNotice = null
        )

        CommercialAction.QuoteStarted -> current.copy(
            quoteRefreshing = true,
            quoteNotice = null,
            navigationIntent = CommercialNavigationIntent.ORDER
        )

        is CommercialAction.QuoteCompleted -> {
            val ownsOrder = current.ownsOrderOperation()
            current.copy(
                quote = action.quote,
                discountCode = action.quote.discountCode ?: current.discountCode,
                selectedPaymentMethod = action.quote.defaultPaymentMethod(),
                checkout = if (ownsOrder) CheckoutState.Details else current.checkout,
                quoteRefreshing = false,
                quoteNotice = if (ownsOrder) action.notice else null,
                navigationIntent = if (ownsOrder) {
                    CommercialNavigationIntent.ORDER
                } else {
                    current.navigationIntent
                }
            )
        }

        is CommercialAction.QuoteFailed -> {
            if (current.quote == null && current.ownsOrderOperation()) {
                current.copy(
                    checkout = CheckoutState.Hidden,
                    quoteRefreshing = false,
                    quoteNotice = null,
                    navigationIntent = CommercialNavigationIntent.ENTITLEMENT
                )
            } else if (current.ownsOrderOperation()) {
                current.copy(
                    checkout = CheckoutState.Error(action.reason),
                    quoteRefreshing = false,
                    quoteNotice = null,
                    navigationIntent = CommercialNavigationIntent.ORDER
                )
            } else {
                current.copy(quoteRefreshing = false, quoteNotice = null)
            }
        }

        is CommercialAction.PaymentMethodChanged -> {
            if (action.method in current.quote?.availablePaymentMethods.orEmpty()) {
                current.copy(selectedPaymentMethod = action.method)
            } else {
                current
            }
        }

        CommercialAction.PaymentCreationStarted -> current.copy(
            checkout = CheckoutState.CreatingPayment,
            quoteNotice = null,
            navigationIntent = CommercialNavigationIntent.ORDER
        )

        is CommercialAction.PaymentCreated -> {
            if (!current.ownsOrderOperation()) {
                current
            } else {
                current.copy(
                    checkout = CheckoutState.AwaitingPayment(action.session),
                    navigationIntent = CommercialNavigationIntent.QR
                )
            }
        }

        is CommercialAction.PaymentAlreadyOwned -> current.copy(
            entitlement = EntitlementState.Pro,
            checkout = CheckoutState.Paid(action.finalAmount),
            recovery = RecoveryState.Idle,
            quoteNotice = null,
            navigationIntent = CommercialNavigationIntent.ENTITLEMENT
        )

        is CommercialAction.PaymentQuoteChanged -> {
            val ownsOrder = current.ownsOrderOperation()
            current.copy(
                quote = action.latestQuote,
                discountCode = action.latestQuote.discountCode ?: current.discountCode,
                selectedPaymentMethod = action.latestQuote.defaultPaymentMethod(),
                checkout = if (ownsOrder) CheckoutState.Details else current.checkout,
                quoteNotice = if (ownsOrder) QuoteNotice.PRICE_CHANGED else null,
                navigationIntent = current.navigationIntent
            )
        }

        is CommercialAction.PaymentCreationFailed -> {
            if (current.ownsOrderOperation()) {
                current.copy(
                    checkout = CheckoutState.Error(action.reason),
                    quoteNotice = null,
                    navigationIntent = CommercialNavigationIntent.ORDER
                )
            } else {
                current
            }
        }

        CommercialAction.PaymentPending -> {
            val awaiting = current.checkout as? CheckoutState.AwaitingPayment
            if (awaiting == null || !current.ownsQrOperation()) {
                current
            } else {
                current.copy(
                    checkout = awaiting.copy(transientFailure = null),
                    navigationIntent = CommercialNavigationIntent.QR
                )
            }
        }

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
                quoteNotice = null,
                navigationIntent = CommercialNavigationIntent.ENTITLEMENT
            )
        }

        CommercialAction.PaymentExpired -> {
            if (current.checkout is CheckoutState.AwaitingPayment && current.ownsQrOperation()) {
                current.copy(
                    checkout = CheckoutState.Expired,
                    navigationIntent = CommercialNavigationIntent.ORDER
                )
            } else {
                current
            }
        }

        is CommercialAction.PaymentRefreshFailed -> {
            val awaiting = current.checkout as? CheckoutState.AwaitingPayment
            if (action.reason == CommercialFailure.ENTITLEMENT_REVOKED) {
                current.copy(
                    entitlement = EntitlementState.Error(action.reason),
                    checkout = CheckoutState.Hidden,
                    quote = null,
                    quoteRefreshing = false,
                    quoteNotice = null,
                    navigationIntent = CommercialNavigationIntent.ENTITLEMENT
                )
            } else if (awaiting == null || !current.ownsQrOperation()) {
                current
            } else if (action.reason in TRANSIENT_POLL_FAILURES) {
                current.copy(
                    checkout = awaiting.copy(transientFailure = action.reason),
                    navigationIntent = CommercialNavigationIntent.QR
                )
            } else {
                current.copy(
                    checkout = CheckoutState.Error(action.reason),
                    navigationIntent = CommercialNavigationIntent.ORDER
                )
            }
        }

        CommercialAction.RecoveryStarted -> current.copy(
            recovery = RecoveryState.Restoring,
            navigationIntent = CommercialNavigationIntent.ENTITLEMENT
        )

        is CommercialAction.RecoverySucceeded -> current.copy(
            entitlement = action.entitlement,
            checkout = CheckoutState.Hidden,
            recovery = RecoveryState.Success,
            quote = if (action.entitlement is EntitlementState.Pro) null else current.quote,
            navigationIntent = CommercialNavigationIntent.ENTITLEMENT
        )

        CommercialAction.RecoveryNotFound -> current.copy(recovery = RecoveryState.NotFound)

        CommercialAction.RecoveryNetworkFailed -> current.copy(
            recovery = RecoveryState.NetworkFailure
        )

        is CommercialAction.RecoveryFailed -> current.copy(
            recovery = RecoveryState.Failure(action.reason)
        )
    }

    private fun checkoutAfterQuery(
        current: CommercialUiState,
        snapshot: EntitlementSnapshot
    ): CheckoutState {
        if (snapshot.entitlement is EntitlementState.Pro ||
            snapshot.entitlement is EntitlementState.Error
        ) {
            return CheckoutState.Hidden
        }
        return when (current.navigationIntent) {
            CommercialNavigationIntent.ENTITLEMENT -> CheckoutState.Hidden
            CommercialNavigationIntent.ORDER -> {
                when {
                    CommercialPagePolicy.pageFor(current.checkout) == CommercialPage.ORDER -> {
                        current.checkout
                    }
                    snapshot.quote != null || current.quote != null -> CheckoutState.Details
                    else -> CheckoutState.Hidden
                }
            }
            CommercialNavigationIntent.QR -> {
                (current.checkout as? CheckoutState.AwaitingPayment)
                    ?: snapshot.pendingPayment?.let(CheckoutState::AwaitingPayment)
                    ?: CheckoutState.Hidden
            }
            null -> snapshot.pendingPayment?.let(CheckoutState::AwaitingPayment)
                ?: CheckoutState.Hidden
        }
    }

    private fun ProductQuote?.defaultPaymentMethod(): PaymentMethod = when {
        this == null -> PaymentMethod.WECHAT
        PaymentMethod.WECHAT in availablePaymentMethods -> PaymentMethod.WECHAT
        else -> availablePaymentMethods.firstOrNull() ?: PaymentMethod.WECHAT
    }

    private fun CommercialUiState.ownsOrderOperation(): Boolean =
        navigationIntent == CommercialNavigationIntent.ORDER ||
            (navigationIntent == null && CommercialPagePolicy.pageFor(checkout) == CommercialPage.ORDER)

    private fun CommercialUiState.ownsQrOperation(): Boolean =
        navigationIntent == CommercialNavigationIntent.QR ||
            (navigationIntent == null && checkout is CheckoutState.AwaitingPayment)

    private companion object {
        val TRANSIENT_QUERY_FAILURES = setOf(
            CommercialFailure.NETWORK,
            CommercialFailure.RATE_LIMITED
        )
        val TRANSIENT_POLL_FAILURES = setOf(
            CommercialFailure.NETWORK,
            CommercialFailure.RATE_LIMITED
        )
    }
}
