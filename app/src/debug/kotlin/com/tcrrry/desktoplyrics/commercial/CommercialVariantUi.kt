package com.tcrrry.desktoplyrics.commercial

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.widget.ImageView

object CommercialVariantUi {
    fun handleDebugIntent(
        context: Context,
        intent: Intent,
        controller: CommercialController
    ) {
        val runtime = CommercialRuntimeFactory.debugRuntime(context)
        intent.getStringExtra(EXTRA_ENTITLEMENT_SCENARIO)?.let { value ->
            val scenario = when (value.lowercase()) {
                "trial" -> DebugEntitlementScenario.TRIAL
                "expired" -> DebugEntitlementScenario.EXPIRED
                "error" -> DebugEntitlementScenario.QUERY_ERROR
                "revoked" -> DebugEntitlementScenario.REVOKED
                "pro" -> DebugEntitlementScenario.PRO
                else -> null
            }
            scenario?.let {
                runtime.selectEntitlementScenario(it)
                controller.reloadEntitlement()
            }
        }
        intent.getStringExtra(EXTRA_RECOVERY_SCENARIO)?.let { value ->
            runtime.recoveryScenario = when (value.lowercase()) {
                "different" -> DebugRecoveryScenario.DIFFERENT_DEVICE
                "network" -> DebugRecoveryScenario.NETWORK_ERROR
                else -> DebugRecoveryScenario.SAME_DEVICE
            }
        }
        intent.getStringExtra(EXTRA_PAYMENT_OUTCOME)?.let { value ->
            runtime.paymentOutcome = when (value.lowercase()) {
                "paid" -> DebugPaymentOutcome.PAID
                "expired" -> DebugPaymentOutcome.EXPIRED
                "changed" -> DebugPaymentOutcome.QUOTE_CHANGED
                "owned" -> DebugPaymentOutcome.ALREADY_OWNED
                else -> DebugPaymentOutcome.PENDING
            }
            controller.refreshPayment()
        }
    }

    fun renderPaymentQr(
        image: ImageView,
        session: PaymentSession,
        onResult: (Boolean) -> Unit
    ) {
        if (session.qrCode.value.startsWith(FIXTURE_QR_PREFIX)) {
            image.setImageDrawable(DebugFixtureQrDrawable(session.purchaseReference))
            onResult(true)
        } else {
            RemotePaymentQrLoader.render(image, session.qrCode.value, onResult)
        }
    }

    private const val EXTRA_ENTITLEMENT_SCENARIO = "commercial_debug_entitlement"
    private const val EXTRA_RECOVERY_SCENARIO = "commercial_debug_recovery"
    private const val EXTRA_PAYMENT_OUTCOME = "commercial_debug_payment"
    private const val FIXTURE_QR_PREFIX = "https://fixture.03lyrics.invalid/"
}

private class DebugFixtureQrDrawable(seed: String) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val patternSeed = seed.hashCode()

    override fun draw(canvas: Canvas) {
        val target = bounds
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawRect(target, paint)

        val margin = target.width() * 0.08f
        val qrBottom = target.top + target.height() * 0.78f
        val size = minOf(target.width() - 2 * margin, qrBottom - target.top - margin)
        val cell = size / GRID
        paint.color = Color.BLACK
        for (row in 0 until GRID) {
            for (column in 0 until GRID) {
                if (finderCell(row, column) || pseudoCell(row, column)) {
                    val left = target.left + margin + column * cell
                    val top = target.top + margin + row * cell
                    canvas.drawRect(left, top, left + cell, top + cell, paint)
                }
            }
        }
        paint.color = Color.rgb(196, 59, 66)
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.create(
            "sans-serif-medium",
            android.graphics.Typeface.NORMAL
        )
        paint.textSize = target.height() * 0.085f
        canvas.drawText(
            "DEBUG FIXTURE",
            target.exactCenterX(),
            target.bottom - target.height() * 0.07f,
            paint
        )
    }

    private fun finderCell(row: Int, column: Int): Boolean =
        inFinder(row, column, 0, 0) ||
            inFinder(row, column, 0, GRID - 7) ||
            inFinder(row, column, GRID - 7, 0)

    private fun inFinder(row: Int, column: Int, top: Int, left: Int): Boolean {
        if (row !in top until top + 7 || column !in left until left + 7) return false
        val localRow = row - top
        val localColumn = column - left
        return localRow == 0 || localRow == 6 || localColumn == 0 || localColumn == 6 ||
            (localRow in 2..4 && localColumn in 2..4)
    }

    private fun pseudoCell(row: Int, column: Int): Boolean {
        if (finderCell(row, column)) return false
        val mixed = patternSeed xor (row * 0x45d9f3b) xor (column * 0x119de1f3)
        return mixed.countOneBits() % 3 == 0
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    override fun getIntrinsicWidth(): Int = 512
    override fun getIntrinsicHeight(): Int = 512

    private companion object {
        const val GRID = 29
    }
}
