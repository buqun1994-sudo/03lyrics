package com.tcrrry.desktoplyrics.commercial

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CommercialAccessUpdate {
    RECHECK,
    REVOKED
}

class CommercialController(
    private val gateway: DeviceCommercialGateway,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val onStateChanged: (CommercialUiState) -> Unit,
    private val onAccessMayHaveChanged: (CommercialAccessUpdate) -> Unit,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val stateMachine = CommercialStateMachine()
    private var operation: Job? = null
    private var paymentPolling: Job? = null

    val state: CommercialUiState
        get() = stateMachine.state

    fun start() = reloadEntitlement(forceRemote = false)

    fun close() {
        operation?.cancel()
        paymentPolling?.cancel()
        scope.cancel()
    }

    fun reloadEntitlement() = reloadEntitlement(forceRemote = true)

    private fun reloadEntitlement(forceRemote: Boolean) {
        paymentPolling?.cancel()
        launchOperation(
            startingAction = CommercialAction.QueryStarted,
            failureAction = CommercialAction.QueryFailed(CommercialFailure.UNKNOWN)
        ) {
            val result = if (forceRemote) {
                gateway.forceQueryEntitlement(nowEpochMs())
            } else {
                gateway.queryEntitlement(nowEpochMs())
            }
            when (result) {
                is EntitlementQueryResult.Ready -> {
                    publishFromOperation(CommercialAction.QueryCompleted(result.snapshot))
                    notifyAccessChangedFromOperation(CommercialAccessUpdate.RECHECK)
                    result.snapshot.pendingPayment?.let { session ->
                        withContext(mainDispatcher) { startPaymentPolling(session) }
                    }
                }
                is EntitlementQueryResult.Failure -> {
                    val update = if (result.reason == CommercialFailure.ENTITLEMENT_REVOKED) {
                        CommercialAccessUpdate.REVOKED
                    } else {
                        CommercialAccessUpdate.RECHECK
                    }
                    if (update == CommercialAccessUpdate.REVOKED) {
                        notifyAccessChangedFromOperation(update)
                    }
                    publishFromOperation(CommercialAction.QueryFailed(result.reason))
                    if (update != CommercialAccessUpdate.REVOKED) {
                        notifyAccessChangedFromOperation(update)
                    }
                }
            }
        }
    }

    fun showCheckout() {
        publish(CommercialAction.CheckoutRequested)
    }

    fun showEntitlementPage() {
        publish(CommercialAction.EntitlementPageRequested)
    }

    fun changeDiscountCode(value: String) {
        publish(CommercialAction.DiscountCodeChanged(value))
    }

    fun applyDiscountCode() {
        requestQuote(state.discountCode, notice = null)
    }

    fun selectPaymentMethod(method: PaymentMethod) {
        publish(CommercialAction.PaymentMethodChanged(method))
    }

    fun createPayment() {
        val quote = state.quote ?: return
        val method = state.selectedPaymentMethod
        paymentPolling?.cancel()
        launchOperation(
            startingAction = CommercialAction.PaymentCreationStarted,
            failureAction = CommercialAction.PaymentCreationFailed(CommercialFailure.UNKNOWN)
        ) {
            when (val result = gateway.createPayment(quote, method, nowEpochMs())) {
                is PaymentCreationResult.Ready -> {
                    publishFromOperation(CommercialAction.PaymentCreated(result.session))
                    withContext(mainDispatcher) {
                        startPaymentPolling(result.session)
                    }
                }
                PaymentCreationResult.AlreadyOwned -> {
                    publishFromOperation(
                        CommercialAction.PaymentAlreadyOwned(quote.finalPrice)
                    )
                    notifyAccessChangedFromOperation(CommercialAccessUpdate.RECHECK)
                }
                is PaymentCreationResult.QuoteChanged -> {
                    publishFromOperation(
                        CommercialAction.PaymentQuoteChanged(result.latestQuote)
                    )
                }
                PaymentCreationResult.QuoteExpired -> {
                    refreshExpiredQuote(quote.discountCode.orEmpty())
                }
                is PaymentCreationResult.Failure -> {
                    publishFromOperation(CommercialAction.PaymentCreationFailed(result.reason))
                }
            }
        }
    }

    fun refreshPayment() {
        val session = (state.checkout as? CheckoutState.AwaitingPayment)?.session ?: return
        paymentPolling?.cancel()
        paymentPolling = scope.launch { pollOnceAndContinue(session) }
    }

    fun restorePurchase() {
        paymentPolling?.cancel()
        launchOperation(
            startingAction = CommercialAction.RecoveryStarted,
            failureAction = CommercialAction.RecoveryNetworkFailed
        ) {
            when (val result = gateway.restorePurchase(nowEpochMs())) {
                is PurchaseRecoveryResult.Success -> {
                    publishFromOperation(CommercialAction.RecoverySucceeded(result.entitlement))
                    notifyAccessChangedFromOperation(CommercialAccessUpdate.RECHECK)
                }
                PurchaseRecoveryResult.NotFound -> {
                    publishFromOperation(CommercialAction.RecoveryNotFound)
                }
                PurchaseRecoveryResult.NetworkFailure -> {
                    publishFromOperation(CommercialAction.RecoveryNetworkFailed)
                }
                is PurchaseRecoveryResult.Failure -> {
                    if (result.reason == CommercialFailure.ENTITLEMENT_REVOKED) {
                        notifyAccessChangedFromOperation(CommercialAccessUpdate.REVOKED)
                    }
                    publishFromOperation(CommercialAction.RecoveryFailed(result.reason))
                }
            }
        }
    }

    private fun requestQuote(discountCode: String, notice: QuoteNotice?) {
        launchOperation(
            startingAction = CommercialAction.QuoteStarted,
            failureAction = CommercialAction.QuoteFailed(CommercialFailure.UNKNOWN)
        ) {
            when (val result = gateway.requestQuote(discountCode, nowEpochMs())) {
                is QuoteRequestResult.Ready -> publishFromOperation(
                    CommercialAction.QuoteCompleted(result.quote, notice)
                )
                is QuoteRequestResult.Failure -> publishFromOperation(
                    CommercialAction.QuoteFailed(result.reason)
                )
            }
        }
    }

    private suspend fun refreshExpiredQuote(discountCode: String) {
        when (val result = gateway.requestQuote(discountCode, nowEpochMs())) {
            is QuoteRequestResult.Ready -> publishFromOperation(
                CommercialAction.QuoteCompleted(
                    result.quote,
                    QuoteNotice.EXPIRED_REFRESHED
                )
            )
            is QuoteRequestResult.Failure -> publishFromOperation(
                CommercialAction.QuoteFailed(result.reason)
            )
        }
    }

    private fun startPaymentPolling(session: PaymentSession) {
        paymentPolling?.cancel()
        paymentPolling = scope.launch { runPaymentPollingLoop(session) }
    }

    private suspend fun pollOnceAndContinue(session: PaymentSession) {
        val shouldContinue = pollOnce(session)
        if (shouldContinue) runPaymentPollingLoop(session)
    }

    private suspend fun runPaymentPollingLoop(session: PaymentSession) {
        while (currentCoroutineContext().isActive) {
            val remaining = session.expiresAtEpochMs - nowEpochMs()
            if (remaining <= 0) {
                publish(CommercialAction.PaymentExpired)
                break
            }
            delay(minOf(session.pollAfterMillis, remaining))
            if (!pollOnce(session)) break
        }
    }

    private suspend fun pollOnce(session: PaymentSession): Boolean {
        return when (val result = withContext(workDispatcher) {
            gateway.refreshPayment(session, nowEpochMs())
        }) {
            PaymentStatusResult.Pending -> {
                publish(CommercialAction.PaymentPending)
                true
            }
            PaymentStatusResult.Paid -> {
                publish(CommercialAction.PaymentPaid)
                onAccessMayHaveChanged(CommercialAccessUpdate.RECHECK)
                false
            }
            PaymentStatusResult.Expired -> {
                publish(CommercialAction.PaymentExpired)
                false
            }
            is PaymentStatusResult.Failure -> {
                if (result.reason == CommercialFailure.ENTITLEMENT_REVOKED) {
                    onAccessMayHaveChanged(CommercialAccessUpdate.REVOKED)
                }
                publish(CommercialAction.PaymentRefreshFailed(result.reason))
                result.reason == CommercialFailure.NETWORK ||
                    result.reason == CommercialFailure.RATE_LIMITED
            }
        }
    }

    private fun launchOperation(
        startingAction: CommercialAction?,
        failureAction: CommercialAction,
        block: suspend () -> Unit
    ) {
        operation?.cancel()
        startingAction?.let(::publish)
        operation = scope.launch {
            try {
                withContext(workDispatcher) { block() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publish(failureAction)
            }
        }
    }

    private fun publish(action: CommercialAction) {
        val next = stateMachine.dispatch(action)
        onStateChanged(next)
    }

    private suspend fun publishFromOperation(action: CommercialAction) {
        withContext(mainDispatcher) { publish(action) }
    }

    private suspend fun notifyAccessChangedFromOperation(update: CommercialAccessUpdate) {
        withContext(mainDispatcher) { onAccessMayHaveChanged(update) }
    }
}
