package com.ninepointnine.desktoplyrics.commercial

import android.content.Context
import android.content.Intent
import android.widget.ImageView

object CommercialVariantUi {
    @Suppress("UNUSED_PARAMETER")
    fun handleDebugIntent(
        context: Context,
        intent: Intent,
        controller: CommercialController
    ) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun handleDiagnosticResume(context: Context, intent: Intent) = Unit

    fun renderPaymentQr(
        image: ImageView,
        session: PaymentSession,
        onResult: (Boolean) -> Unit
    ) {
        RemotePaymentQrLoader.render(image, session.qrCode.value, onResult)
    }
}
