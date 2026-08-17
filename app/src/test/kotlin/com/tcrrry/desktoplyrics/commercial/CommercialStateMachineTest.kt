package com.tcrrry.desktoplyrics.commercial

import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommercialStateMachineTest {
    private val discountedQuote = quote()

    @Test
    fun `revoked access is signaled before the revoked state is rendered`() {
        val events = mutableListOf<String>()
        val controller = CommercialController(
            gateway = object : DeviceCommercialGateway {
                override suspend fun queryEntitlement(nowEpochMs: Long) =
                    EntitlementQueryResult.Failure(CommercialFailure.ENTITLEMENT_REVOKED)

                override suspend fun requestQuote(discountCode: String, nowEpochMs: Long) =
                    error("unused")

                override suspend fun createPayment(
                    quote: ProductQuote,
                    method: PaymentMethod,
                    nowEpochMs: Long
                ) = error("unused")

                override suspend fun refreshPayment(
                    session: PaymentSession,
                    nowEpochMs: Long
                ) = error("unused")

                override suspend fun restorePurchase(nowEpochMs: Long) = error("unused")
            },
            onStateChanged = { state ->
                val error = state.entitlement as? EntitlementState.Error
                if (error?.reason == CommercialFailure.ENTITLEMENT_REVOKED) {
                    events += "render_revoked"
                }
            },
            onAccessMayHaveChanged = { update -> events += "access_$update" },
            mainDispatcher = Dispatchers.Unconfined,
            workDispatcher = Dispatchers.Unconfined
        )

        controller.reloadEntitlement()
        controller.close()

        assertEquals(listOf("access_REVOKED", "render_revoked"), events)
    }

    @Test
    fun `trial binds gateway quote without calculating price`() {
        val machine = CommercialStateMachine()
        val state = machine.dispatch(
            CommercialAction.QueryCompleted(
                EntitlementSnapshot(
                    EntitlementState.Trial(10_000L, 5_000L),
                    discountedQuote
                )
            )
        )

        assertTrue(state.entitlement is EntitlementState.Trial)
        assertEquals("original", state.quote?.originalPrice?.text)
        assertEquals("discounted", state.quote?.finalPrice?.text)
        assertEquals("VALID", state.discountCode)
    }

    @Test
    fun `stable pro remains pro while a refresh is in progress`() {
        val machine = CommercialStateMachine(
            CommercialUiState(entitlement = EntitlementState.Pro)
        )

        val refreshing = machine.dispatch(CommercialAction.QueryStarted)

        assertEquals(EntitlementState.Pro, refreshing.entitlement)
        assertTrue(refreshing.queryRefreshing)
        assertFalse(CommercialAdPolicy.isVisible(refreshing.entitlement))
    }

    @Test
    fun `invalid discount keeps gateway supplied original price and remains payable`() {
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Expired,
                quote = discountedQuote,
                checkout = CheckoutState.Details
            )
        )
        val originalQuote = discountedQuote.copy(
            finalAmountCents = discountedQuote.originalAmountCents,
            finalPrice = DisplayMoney("original"),
            discountLabel = null,
            discountCode = "BAD",
            discountResolution = DiscountResolution.INVALID
        )

        val state = machine.dispatch(CommercialAction.QuoteCompleted(originalQuote))

        assertEquals(DiscountResolution.INVALID, state.quote?.discountResolution)
        assertEquals("original", state.quote?.finalPrice?.text)
        assertTrue(state.checkout is CheckoutState.Details)
    }

    @Test
    fun `editing a discount code does not mutate the last server quote`() {
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Expired,
                quote = discountedQuote,
                checkout = CheckoutState.Details
            )
        )

        val state = machine.dispatch(CommercialAction.DiscountCodeChanged("NEW"))

        assertEquals(discountedQuote, state.quote)
        assertEquals("NEW", state.discountCode)
    }

    @Test
    fun `quote changed displays latest quote and requires another payment action`() {
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Expired,
                quote = discountedQuote,
                checkout = CheckoutState.CreatingPayment
            )
        )
        val latest = discountedQuote.copy(
            quoteReference = "latest",
            finalAmountCents = 200,
            finalPrice = DisplayMoney("latest price")
        )

        val state = machine.dispatch(CommercialAction.PaymentQuoteChanged(latest))

        assertEquals(latest, state.quote)
        assertEquals(QuoteNotice.PRICE_CHANGED, state.quoteNotice)
        assertEquals(CheckoutState.Details, state.checkout)
    }

    @Test
    fun `payment progresses from details through pending to pro`() {
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Expired,
                quote = discountedQuote,
                checkout = CheckoutState.Details
            )
        )
        val session = paymentSession()

        machine.dispatch(CommercialAction.PaymentCreationStarted)
        assertTrue(machine.state.checkout is CheckoutState.CreatingPayment)
        machine.dispatch(CommercialAction.PaymentCreated(session))
        assertTrue(machine.state.checkout is CheckoutState.AwaitingPayment)
        machine.dispatch(CommercialAction.PaymentPaid)

        assertTrue(machine.state.entitlement is EntitlementState.Pro)
        assertTrue(machine.state.checkout is CheckoutState.Paid)
        assertEquals(
            CommercialPage.ENTITLEMENT,
            CommercialPagePolicy.pageFor(machine.state.checkout)
        )
    }

    @Test
    fun `transient polling network failure keeps the qr page and session`() {
        val session = paymentSession()
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Expired,
                quote = discountedQuote,
                checkout = CheckoutState.AwaitingPayment(session)
            )
        )

        val state = machine.dispatch(
            CommercialAction.PaymentRefreshFailed(CommercialFailure.NETWORK)
        )

        val awaiting = state.checkout as CheckoutState.AwaitingPayment
        assertEquals(session, awaiting.session)
        assertEquals(CommercialFailure.NETWORK, awaiting.transientFailure)
        assertEquals(CommercialPage.QR, CommercialPagePolicy.pageFor(state.checkout))
    }

    @Test
    fun `commercial pages replace each other instead of accumulating content`() {
        val session = paymentSession()

        assertEquals(CommercialPage.ENTITLEMENT, CommercialPagePolicy.pageFor(CheckoutState.Hidden))
        assertEquals(CommercialPage.ORDER, CommercialPagePolicy.pageFor(CheckoutState.Details))
        assertEquals(
            CommercialPage.QR,
            CommercialPagePolicy.pageFor(CheckoutState.AwaitingPayment(session))
        )
        assertEquals(
            CommercialPage.ENTITLEMENT,
            CommercialPagePolicy.pageFor(CheckoutState.Paid(DisplayMoney("amount")))
        )
    }

    @Test
    fun `advertisement disappears after pro becomes active`() {
        assertTrue(CommercialAdPolicy.isVisible(EntitlementState.Trial(10_000L, 5_000L)))
        assertTrue(CommercialAdPolicy.isVisible(EntitlementState.Expired))
        assertFalse(CommercialAdPolicy.isVisible(EntitlementState.Pro))
    }

    @Test
    fun `opening the advertisement returns to entitlement without opening checkout`() {
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Trial(10_000L, 5_000L),
                quote = discountedQuote,
                checkout = CheckoutState.AwaitingPayment(paymentSession())
            )
        )

        machine.dispatch(CommercialAction.EntitlementPageRequested)

        assertEquals(CheckoutState.Hidden, machine.state.checkout)
        assertEquals(
            CommercialPage.ENTITLEMENT,
            CommercialPagePolicy.pageFor(machine.state.checkout)
        )
    }

    @Test
    fun `fresh entitlement query clears stale payment success presentation`() {
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Pro,
                checkout = CheckoutState.Paid(DisplayMoney("amount")),
                recovery = RecoveryState.Success
            )
        )

        machine.dispatch(CommercialAction.QueryStarted)
        machine.dispatch(
            CommercialAction.QueryCompleted(
                EntitlementSnapshot(EntitlementState.Expired, discountedQuote)
            )
        )

        assertEquals(CheckoutState.Hidden, machine.state.checkout)
        assertEquals(EntitlementState.Expired, machine.state.entitlement)
        assertEquals(RecoveryState.Idle, machine.state.recovery)
    }

    @Test
    fun `expired payment is distinct from entitlement query failure`() {
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Trial(10_000L, 5_000L),
                quote = discountedQuote,
                checkout = CheckoutState.AwaitingPayment(paymentSession())
            )
        )
        machine.dispatch(CommercialAction.PaymentExpired)
        assertTrue(machine.state.checkout is CheckoutState.Expired)

        machine.dispatch(CommercialAction.QueryFailed(CommercialFailure.NETWORK))
        assertTrue(machine.state.entitlement is EntitlementState.Error)
        assertFalse(machine.state.entitlement is EntitlementState.Expired)
        assertNull(machine.state.quote)
    }

    @Test
    fun `recovery covers success not found and network failure`() {
        val machine = CommercialStateMachine(
            CommercialUiState(entitlement = EntitlementState.Expired)
        )

        machine.dispatch(CommercialAction.RecoveryStarted)
        assertEquals(RecoveryState.Restoring, machine.state.recovery)
        machine.dispatch(CommercialAction.RecoveryNotFound)
        assertEquals(RecoveryState.NotFound, machine.state.recovery)
        machine.dispatch(CommercialAction.RecoveryNetworkFailed)
        assertEquals(RecoveryState.NetworkFailure, machine.state.recovery)
        machine.dispatch(CommercialAction.RecoverySucceeded(EntitlementState.Pro))
        assertEquals(RecoveryState.Success, machine.state.recovery)
        assertTrue(machine.state.entitlement is EntitlementState.Pro)
    }

    private fun quote() = ProductQuote(
        quoteReference = "quote",
        productId = DeviceCommerceProductContract.PRODUCT_ID,
        sku = DeviceCommerceProductContract.SKU,
        productName = "PRO",
        originalAmountCents = 200,
        originalPrice = DisplayMoney("original"),
        calculatedAmountCents = 100,
        finalAmountCents = 100,
        finalPrice = DisplayMoney("discounted"),
        paymentRatioBps = 5_000,
        discountLabel = "discount",
        discountCode = "VALID",
        discountResolution = DiscountResolution.VALID,
        minimumChargeApplied = false,
        currency = "CNY",
        availablePaymentMethods = setOf(PaymentMethod.WECHAT, PaymentMethod.ALIPAY),
        expiresAtEpochMs = 20_000L
    )

    private fun paymentSession() = PaymentSession(
        purchaseReference = "purchase",
        finalAmountCents = 100,
        finalAmount = DisplayMoney("discounted"),
        currency = "CNY",
        expiresAtEpochMs = 10_000L,
        pollAfterMillis = 2_000L,
        qrCode = PaymentQrCode(PaymentQrFormat.IMAGE_URL, "https://example.test/qr")
    )
}
