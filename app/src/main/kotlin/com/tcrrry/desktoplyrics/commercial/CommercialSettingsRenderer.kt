package com.tcrrry.desktoplyrics.commercial

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tcrrry.desktoplyrics.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class CommercialViewActions(
    val onOpenEntitlement: () -> Unit,
    val onCheckout: () -> Unit,
    val onRetryEntitlement: () -> Unit,
    val onDiscountCodeChanged: (String) -> Unit,
    val onApplyDiscount: () -> Unit,
    val onPaymentMethodChanged: (PaymentMethod) -> Unit,
    val onPay: () -> Unit,
    val onRestore: () -> Unit
)

class CommercialSettingsRenderer(
    private val root: View,
    private val actions: CommercialViewActions
) {
    private val context: Context = root.context
    private val entitlementBadge: TextView = root.findViewById(R.id.settings_entitlement_badge)
    private val summaryContainer: View = root.findViewById(R.id.commercial_summary_container)
    private val summary: View = root.findViewById(R.id.commercial_summary)
    private val summaryChevron: ImageView = root.findViewById(R.id.commercial_summary_chevron)
    private val summaryStatus: TextView = root.findViewById(R.id.commercial_summary_status)
    private val summaryOfferDetails: View =
        root.findViewById(R.id.commercial_summary_offer_details)
    private val summaryOriginalPrice: TextView =
        root.findViewById(R.id.commercial_summary_original_price)
    private val summaryDiscount: TextView = root.findViewById(R.id.commercial_summary_discount)
    private val summaryFinalPrice: TextView =
        root.findViewById(R.id.commercial_summary_final_price)
    private val contentScroll: ScrollView = root.findViewById(R.id.settings_content_scroll)
    private val displayContent: View = root.findViewById(R.id.settings_display_content)
    private val systemContent: View = root.findViewById(R.id.settings_system_content)
    private val cacheContent: View = root.findViewById(R.id.settings_cache_content)
    private val searchContent: View = root.findViewById(R.id.settings_search_content)

    private val entitlementPage: View = root.findViewById(R.id.commercial_entitlement_page)
    private val orderPage: View = root.findViewById(R.id.commercial_order_page)
    private val qrPage: View = root.findViewById(R.id.commercial_qr_page)
    private val entitlementStatusGroup: View =
        root.findViewById(R.id.commercial_entitlement_status_group)
    private val entitlementTitle: TextView = root.findViewById(R.id.commercial_entitlement_title)
    private val largeStatus: TextView = root.findViewById(R.id.commercial_large_status)
    private val largePriceArea: View = root.findViewById(R.id.commercial_large_price_area)
    private val proState: View = root.findViewById(R.id.commercial_pro_state)
    private val proStateTitle: TextView = root.findViewById(R.id.commercial_pro_state_title)
    private val proStateStatus: TextView = root.findViewById(R.id.commercial_pro_state_status)
    private val marketingOriginalPrice: TextView =
        root.findViewById(R.id.commercial_marketing_original_price)
    private val marketingDiscount: TextView = root.findViewById(R.id.commercial_marketing_discount)
    private val marketingFinalPrice: TextView =
        root.findViewById(R.id.commercial_marketing_final_price)
    private val largeCheckoutAction: TextView =
        root.findViewById(R.id.commercial_large_checkout_action)
    private val restoreAction: TextView = root.findViewById(R.id.commercial_restore_action)
    private val restoreStatus: TextView = root.findViewById(R.id.commercial_restore_status)

    private val orderBack: ImageView = root.findViewById(R.id.commercial_order_back)
    private val productValue: TextView = root.findViewById(R.id.commercial_order_product_value)
    private val originalPriceValue: TextView =
        root.findViewById(R.id.commercial_order_original_price_value)
    private val discountInput: EditText = root.findViewById(R.id.commercial_discount_input)
    private val applyDiscount: TextView = root.findViewById(R.id.commercial_apply_discount)
    private val discountMessage: TextView = root.findViewById(R.id.commercial_discount_message)
    private val discountValue: TextView = root.findViewById(R.id.commercial_order_discount_value)
    private val finalPriceValue: TextView =
        root.findViewById(R.id.commercial_order_final_price_value)
    private val wechatOption: TextView = root.findViewById(R.id.commercial_payment_wechat)
    private val alipayOption: TextView = root.findViewById(R.id.commercial_payment_alipay)
    private val orderMessage: TextView = root.findViewById(R.id.commercial_order_message)
    private val payAction: TextView = root.findViewById(R.id.commercial_pay_action)

    private val qrImage: ImageView = root.findViewById(R.id.commercial_qr_image)
    private val qrPlaceholder: TextView = root.findViewById(R.id.commercial_qr_placeholder)
    private val paymentAmount: TextView = root.findViewById(R.id.commercial_payment_amount)
    private val orderExpiry: TextView = root.findViewById(R.id.commercial_order_expiry)
    private val paymentStatus: TextView = root.findViewById(R.id.commercial_payment_status)

    private var latestState = CommercialUiState()
    private var suppressDiscountTextChange = false
    private var renderedPage: CommercialPage? = null
    private var renderedQrValue: String? = null
    private var summaryVisibleForSection = true
    private var accentColor = color(R.color.settings_accent)
    private var accentTextColor = color(R.color.commercial_action_text)

    init {
        summary.setOnClickListener { actions.onOpenEntitlement() }
        largeCheckoutAction.setOnClickListener { view ->
            view.post {
                if (latestState.entitlement is EntitlementState.Error) {
                    actions.onRetryEntitlement()
                } else {
                    actions.onCheckout()
                }
            }
        }
        restoreAction.setOnClickListener { actions.onRestore() }
        orderBack.setOnClickListener { actions.onOpenEntitlement() }
        applyDiscount.setOnClickListener { actions.onApplyDiscount() }
        wechatOption.setOnClickListener {
            actions.onPaymentMethodChanged(PaymentMethod.WECHAT)
        }
        alipayOption.setOnClickListener {
            actions.onPaymentMethodChanged(PaymentMethod.ALIPAY)
        }
        payAction.setOnClickListener { actions.onPay() }
        discountInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (!suppressDiscountTextChange) {
                    actions.onDiscountCodeChanged(s?.toString().orEmpty())
                }
            }
        })
        originalPriceValue.paintFlags = originalPriceValue.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        summaryOriginalPrice.paintFlags =
            summaryOriginalPrice.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        marketingOriginalPrice.paintFlags =
            marketingOriginalPrice.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
    }

    fun render(state: CommercialUiState) {
        latestState = state
        renderEntitlement(state)
        renderQuote(state)
        renderRecovery(state.recovery, state.entitlement)
        renderPage(state)
        renderPaymentMethod(state)
    }

    fun updateAccent(accentColor: Int, accentTextColor: Int) {
        this.accentColor = accentColor
        this.accentTextColor = accentTextColor
        summaryChevron.imageTintList = ColorStateList.valueOf(accentColor)
        summaryDiscount.backgroundTintList = ColorStateList.valueOf(accentColor)
        summaryDiscount.setTextColor(accentTextColor)
        marketingDiscount.backgroundTintList = ColorStateList.valueOf(accentColor)
        marketingDiscount.setTextColor(accentTextColor)
        listOf(largeCheckoutAction, payAction).forEach { action ->
            action.backgroundTintList = ColorStateList.valueOf(accentColor)
            action.setTextColor(accentTextColor)
        }
        renderPaymentMethod(latestState)
    }

    fun setSummaryVisibleForSection(visible: Boolean) {
        summaryVisibleForSection = visible
        renderSummaryVisibility(latestState.entitlement)
    }

    private fun renderEntitlement(state: CommercialUiState) {
        val entitlement = state.entitlement
        renderSummaryVisibility(entitlement)
        val showProState = entitlement is EntitlementState.Pro ||
            state.checkout is CheckoutState.Paid
        entitlementStatusGroup.visibility = if (showProState) View.GONE else View.VISIBLE
        proState.visibility = if (showProState) View.VISIBLE else View.GONE
        proStateTitle.setText(
            if (state.checkout is CheckoutState.Paid) R.string.commercial_payment_success_title
            else R.string.commercial_pro_title
        )
        proStateStatus.setText(R.string.commercial_pro_active)
        val remaining = (entitlement as? EntitlementState.Trial)?.let {
            formatRemaining(it.remainingMillis)
        }
        val summaryText = when (entitlement) {
            EntitlementState.Checking -> context.getString(R.string.commercial_checking)
            is EntitlementState.Trial -> context.getString(
                R.string.commercial_trial_summary,
                requireNotNull(remaining)
            )
            EntitlementState.Expired -> context.getString(R.string.commercial_expired)
            EntitlementState.Pro -> context.getString(R.string.commercial_pro_active)
            is EntitlementState.Error -> errorText(entitlement.reason)
        }
        summaryStatus.text = summaryText
        summary.contentDescription = context.getString(
            R.string.commercial_accessibility_ad,
            summaryText
        )
        entitlementBadge.contentDescription = context.getString(
            R.string.commercial_accessibility_status,
            summaryText
        )

        when (entitlement) {
            is EntitlementState.Trial -> setBadge(
                R.string.commercial_badge_trial,
                R.color.commercial_trial_badge,
                R.color.commercial_trial_text
            )
            EntitlementState.Expired -> setBadge(
                R.string.commercial_badge_expired,
                R.color.commercial_expired_badge,
                R.color.commercial_expired_text
            )
            EntitlementState.Pro -> setBadge(
                R.string.commercial_badge_pro,
                R.color.commercial_pro_badge,
                R.color.commercial_pro_text
            )
            EntitlementState.Checking -> setBadge(
                R.string.commercial_badge_checking,
                R.color.commercial_expired_badge,
                R.color.commercial_expired_text
            )
            is EntitlementState.Error -> setBadge(
                R.string.commercial_badge_error,
                R.color.commercial_expired_badge,
                R.color.commercial_error
            )
        }

        val entitlementDetail = when (entitlement) {
            is EntitlementState.Trial -> context.getString(
                R.string.commercial_trial_remaining,
                requireNotNull(remaining)
            )
            else -> null
        }
        entitlementTitle.text = when {
            state.checkout is CheckoutState.Paid -> {
                context.getString(R.string.commercial_payment_success_title)
            }
            entitlement is EntitlementState.Trial -> {
                context.getString(R.string.commercial_trial_active)
            }
            entitlement is EntitlementState.Pro -> context.getString(R.string.commercial_pro_title)
            else -> summaryText
        }
        largeStatus.visibility = if (!showProState && entitlementDetail != null) {
            View.VISIBLE
        } else {
            View.GONE
        }
        largeStatus.text = entitlementDetail.orEmpty()

        val canPurchase = state.quote != null && entitlement !is EntitlementState.Pro &&
            entitlement !is EntitlementState.Checking && entitlement !is EntitlementState.Error
        largePriceArea.visibility = if (canPurchase) View.VISIBLE else View.GONE
        placeEntitlementStatusGroup(hasMarketingContent = canPurchase)
        largeCheckoutAction.visibility = if (canPurchase || entitlement is EntitlementState.Error) {
            View.VISIBLE
        } else {
            View.GONE
        }
        largeCheckoutAction.setText(
            if (entitlement is EntitlementState.Error) R.string.commercial_retry
            else R.string.commercial_checkout
        )
    }

    private fun placeEntitlementStatusGroup(hasMarketingContent: Boolean) {
        val params = entitlementStatusGroup.layoutParams as? FrameLayout.LayoutParams ?: return
        val centered = !hasMarketingContent
        val height = if (centered) {
            ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val gravity = if (centered) {
            Gravity.CENTER
        } else {
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        if (params.gravity != gravity || params.height != height || params.topMargin != 0) {
            params.gravity = gravity
            params.height = height
            params.topMargin = 0
            entitlementStatusGroup.layoutParams = params
        }
    }

    private fun renderQuote(state: CommercialUiState) {
        val quote = state.quote
        if (quote == null) {
            summaryOfferDetails.visibility = View.GONE
            summaryDiscount.visibility = View.GONE
            discountMessage.visibility = View.GONE
            return
        }
        val offerDiscount = quote.discountLabel?.let {
            context.getString(R.string.commercial_offer_discount, it)
        } ?: context.getString(R.string.commercial_offer_no_discount)
        summaryOfferDetails.visibility = View.VISIBLE
        summaryDiscount.visibility = View.VISIBLE
        summaryOriginalPrice.text = quote.originalPrice.text
        summaryDiscount.text = offerDiscount
        summaryFinalPrice.text = quote.finalPrice.text
        marketingOriginalPrice.text = quote.originalPrice.text
        marketingDiscount.text = offerDiscount
        marketingFinalPrice.text = quote.finalPrice.text
        productValue.text = normalizeCommercialDisplayText(quote.productName)
        originalPriceValue.text = quote.originalPrice.text
        discountValue.text = quote.discountLabel ?: context.getString(R.string.commercial_no_discount)
        finalPriceValue.text = quote.finalPrice.text

        if (discountInput.text.toString() != state.discountCode && !discountInput.hasFocus()) {
            suppressDiscountTextChange = true
            discountInput.setText(state.discountCode)
            discountInput.setSelection(discountInput.text.length)
            suppressDiscountTextChange = false
        }

        val message = when {
            state.quoteRefreshing -> R.string.commercial_quote_refreshing
            quote.discountResolution == DiscountResolution.INVALID -> {
                R.string.commercial_discount_invalid
            }
            quote.discountResolution == DiscountResolution.EXPIRED -> {
                R.string.commercial_discount_expired
            }
            quote.discountResolution == DiscountResolution.UNAVAILABLE -> {
                R.string.commercial_discount_unavailable
            }
            else -> null
        }
        discountMessage.visibility = if (message == null) View.GONE else View.VISIBLE
        message?.let(discountMessage::setText)
    }

    private fun renderRecovery(recovery: RecoveryState, entitlement: EntitlementState) {
        val pro = entitlement is EntitlementState.Pro
        restoreAction.visibility = if (pro) View.GONE else View.VISIBLE
        restoreAction.isEnabled = recovery !is RecoveryState.Restoring
        restoreAction.alpha = if (restoreAction.isEnabled) 1f else 0.55f
        restoreAction.setText(R.string.commercial_restore_purchase)
        val status = when (recovery) {
            RecoveryState.Idle -> null
            RecoveryState.Restoring -> R.string.commercial_restore_running
            RecoveryState.Success -> R.string.commercial_restore_success
            RecoveryState.NotFound -> R.string.commercial_restore_not_found
            RecoveryState.NetworkFailure -> R.string.commercial_restore_network_error
            is RecoveryState.Failure -> when (recovery.reason) {
                CommercialFailure.STORAGE -> R.string.commercial_storage_error
                CommercialFailure.DEVICE_MISMATCH -> R.string.commercial_restore_device_mismatch
                else -> R.string.commercial_restore_network_error
            }
        }
        restoreStatus.visibility = if (status == null) View.GONE else View.VISIBLE
        status?.let(restoreStatus::setText)
    }

    private fun renderPage(state: CommercialUiState) {
        val nextPage = CommercialPagePolicy.pageFor(state.checkout)
        val showEntitlement = nextPage == CommercialPage.ENTITLEMENT
        val showQr = nextPage == CommercialPage.QR
        entitlementPage.visibility = if (showEntitlement) View.VISIBLE else View.GONE
        qrPage.visibility = if (showQr) View.VISIBLE else View.GONE
        orderPage.visibility = if (!showEntitlement && !showQr) View.VISIBLE else View.GONE
        if (!showQr) {
            renderedQrValue = null
            qrImage.tag = null
        }
        if (nextPage != renderedPage) {
            renderedPage = nextPage
            contentScroll.post { contentScroll.scrollTo(0, 0) }
        }

        when (val checkout = state.checkout) {
            CheckoutState.Hidden, is CheckoutState.Paid -> Unit
            CheckoutState.Details -> renderOrderMessage(
                message = when (state.quoteNotice) {
                    QuoteNotice.PRICE_CHANGED -> R.string.commercial_quote_changed
                    QuoteNotice.EXPIRED_REFRESHED -> R.string.commercial_quote_expired_refreshed
                    null -> null
                },
                enabled = !state.quoteRefreshing,
                isError = state.quoteNotice != null
            )
            CheckoutState.CreatingPayment -> renderOrderMessage(
                R.string.commercial_payment_creating,
                enabled = false
            )
            is CheckoutState.AwaitingPayment -> renderQr(checkout)
            CheckoutState.Expired -> renderOrderMessage(
                R.string.commercial_payment_expired,
                enabled = true,
                isError = true
            )
            is CheckoutState.Error -> renderOrderMessage(
                R.string.commercial_payment_error,
                enabled = true,
                isError = true
            )
        }
    }

    private fun renderOrderMessage(message: Int?, enabled: Boolean, isError: Boolean = false) {
        discountInput.isEnabled = enabled
        applyDiscount.isEnabled = enabled
        wechatOption.isEnabled = enabled
        alipayOption.isEnabled = enabled
        payAction.isEnabled = enabled
        payAction.alpha = if (enabled) 1f else 0.55f
        payAction.setText(
            when {
                !enabled && latestState.quoteRefreshing -> R.string.commercial_quote_refreshing
                !enabled -> R.string.commercial_payment_creating
                message == R.string.commercial_payment_expired || isError -> {
                    R.string.commercial_payment_recreate
                }
                else -> R.string.commercial_pay
            }
        )
        orderMessage.visibility = if (message == null) View.GONE else View.VISIBLE
        message?.let(orderMessage::setText)
        orderMessage.setTextColor(
            color(if (isError) R.color.commercial_error else R.color.settings_text_secondary)
        )
    }

    private fun renderQr(awaiting: CheckoutState.AwaitingPayment) {
        val session = awaiting.session
        paymentAmount.text = context.getString(
            R.string.commercial_payment_amount,
            session.finalAmount.text
        )
        orderExpiry.text = context.getString(
            R.string.commercial_order_valid_until,
            formatOrderExpiry(session.expiresAtEpochMs)
        )
        paymentStatus.setText(
            if (awaiting.transientFailure == CommercialFailure.NETWORK) {
                R.string.commercial_payment_network_waiting
            } else {
                R.string.commercial_payment_pending
            }
        )
        paymentStatus.setTextColor(
            color(
                if (awaiting.transientFailure == null) R.color.settings_text_primary
                else R.color.commercial_error
            )
        )
        if (renderedQrValue != session.qrCode.value) {
            renderedQrValue = session.qrCode.value
            qrImage.setImageDrawable(null)
            qrImage.visibility = View.INVISIBLE
            qrPlaceholder.visibility = View.VISIBLE
            qrPlaceholder.setText(R.string.commercial_qr_placeholder)
            CommercialVariantUi.renderPaymentQr(qrImage, session) { loaded ->
                if (renderedQrValue == session.qrCode.value) {
                    qrImage.visibility = if (loaded) View.VISIBLE else View.INVISIBLE
                    qrPlaceholder.visibility = if (loaded) View.GONE else View.VISIBLE
                    if (!loaded) qrPlaceholder.setText(R.string.commercial_qr_load_error)
                }
            }
        }
    }

    private fun renderPaymentMethod(state: CommercialUiState) {
        val available = state.quote?.availablePaymentMethods.orEmpty()
        val controlsEnabled = state.checkout == CheckoutState.Details && !state.quoteRefreshing
        setPaymentOption(
            wechatOption,
            selected = state.selectedPaymentMethod == PaymentMethod.WECHAT,
            enabled = controlsEnabled && PaymentMethod.WECHAT in available
        )
        setPaymentOption(
            alipayOption,
            selected = state.selectedPaymentMethod == PaymentMethod.ALIPAY,
            enabled = controlsEnabled && PaymentMethod.ALIPAY in available
        )
    }

    private fun setPaymentOption(option: TextView, selected: Boolean, enabled: Boolean) {
        option.isEnabled = enabled
        option.alpha = if (enabled) 1f else 0.45f
        option.setBackgroundResource(if (selected) R.drawable.bg_settings_segment_selected else 0)
        option.backgroundTintList = if (selected) {
            ColorStateList.valueOf(accentColor)
        } else {
            null
        }
        option.setTextColor(
            if (selected) accentTextColor else color(R.color.settings_text_option)
        )
        option.typeface = Typeface.create(
            if (selected) "sans-serif-medium" else "sans-serif",
            Typeface.NORMAL
        )
    }

    private fun setBadge(text: Int, backgroundColor: Int, textColor: Int) {
        entitlementBadge.setText(text)
        entitlementBadge.setBackgroundResource(R.drawable.bg_commercial_corner_badge)
        entitlementBadge.backgroundTintList = ColorStateList.valueOf(color(backgroundColor))
        entitlementBadge.setTextColor(color(textColor))
    }

    private fun renderSummaryVisibility(entitlement: EntitlementState) {
        val summaryVisible =
            summaryVisibleForSection && CommercialAdPolicy.isVisible(entitlement)
        summaryContainer.visibility = if (summaryVisible) View.VISIBLE else View.GONE
        val topPadding = context.resources.getDimensionPixelSize(
            if (summaryVisible) R.dimen.commercial_content_padding_top
            else R.dimen.commercial_content_padding_top_no_summary
        )
        listOf(displayContent, systemContent, cacheContent, searchContent).forEach { content ->
            content.setPaddingRelative(
                content.paddingStart,
                topPadding,
                content.paddingEnd,
                content.paddingBottom
            )
        }
    }

    private fun errorText(failure: CommercialFailure): String = context.getString(
        when (failure) {
            CommercialFailure.CONFIGURATION_MISSING -> R.string.commercial_configuration_missing
            CommercialFailure.STORAGE -> R.string.commercial_storage_error
            CommercialFailure.CLOCK_ROLLBACK -> R.string.commercial_clock_error
            CommercialFailure.ENTITLEMENT_REVOKED -> R.string.commercial_entitlement_revoked
            else -> R.string.commercial_query_error_summary
        }
    )

    private fun formatRemaining(remainingMillis: Long): String {
        val safe = remainingMillis.coerceAtLeast(0)
        val days = TimeUnit.MILLISECONDS.toDays(safe)
        val hours = TimeUnit.MILLISECONDS.toHours(safe) % 24
        val minutes = TimeUnit.MILLISECONDS.toMinutes(safe) % 60
        return when {
            days > 0 -> context.getString(R.string.commercial_remaining_days_hours, days, hours)
            hours > 0 -> context.getString(R.string.commercial_remaining_hours_minutes, hours, minutes)
            else -> context.getString(R.string.commercial_remaining_minutes, maxOf(1, minutes))
        }
    }

    private fun formatOrderExpiry(epochMs: Long): String = SimpleDateFormat(
        "MM-dd HH:mm",
        Locale.CHINA
    ).format(Date(epochMs))

    private fun color(resource: Int): Int = ContextCompat.getColor(context, resource)

}

internal fun normalizeCommercialDisplayText(value: String): String =
    value.replace(COMMERCIAL_PRO_DISPLAY_TOKEN_REGEX, "Pro")

private val COMMERCIAL_PRO_DISPLAY_TOKEN_REGEX =
    Regex("(?<![A-Za-z0-9])PRO(?![A-Za-z0-9])")
