package com.ninepointnine.desktoplyrics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/** Horizontal iCAR-style hue card: continuous gradient and a white selected frame. */
internal class IcarColorCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var color: Int = Color.rgb(92, 102, 191)
        set(value) {
            field = value
            if (!tracking) position = nearestPosition(value)
            invalidate()
        }
    /** Lightweight preview callback; callers should debounce expensive work. */
    var onColorPreview: ((Int) -> Unit)? = null
    var onColorCommit: ((Int) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val card = RectF()
    private val selector = RectF()
    private var position = 0.72f
    private var tracking = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = 2f
        card.set(inset, inset, width - inset, height - inset)
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, HUES, null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(card, 10f, 10f, paint)
        paint.shader = null
        val x = position * width
        selector.set((x - 22f).coerceAtLeast(2f), 8f, (x + 22f).coerceAtMost(width - 2f), height - 8f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = Color.WHITE
        canvas.drawRoundRect(selector, 7f, 7f, paint)
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                updateFromTouch(event.x)
                tracking = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onColorCommit?.invoke(color)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            else -> return false
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromTouch(x: Float) {
        position = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        // Do not run nearest-position search while dragging. The card only
        // needs its local preview frame until the debounced commit arrives.
        color = colorAt(position)
        onColorPreview?.invoke(color)
    }

    private fun colorAt(fraction: Float): Int {
        val scaled = fraction.coerceIn(0f, 1f) * (HUES.size - 1)
        val index = scaled.toInt().coerceIn(0, HUES.size - 2)
        val amount = scaled - index
        val first = HUES[index]
        val second = HUES[index + 1]
        fun channel(shift: Int) = (((first shr shift) and 0xff) * (1f - amount) +
            ((second shr shift) and 0xff) * amount).roundToInt()
        return Color.rgb(channel(16), channel(8), channel(0))
    }

    private fun nearestPosition(value: Int): Float {
        var best = 0f
        var bestDistance = Int.MAX_VALUE
        repeat(361) { step ->
            val candidate = colorAt(step / 360f)
            val distance = colorDistance(candidate, value)
            if (distance < bestDistance) { bestDistance = distance; best = step / 360f }
        }
        return best
    }

    private fun colorDistance(first: Int, second: Int): Int {
        val dr = ((first shr 16) and 0xff) - ((second shr 16) and 0xff)
        val dg = ((first shr 8) and 0xff) - ((second shr 8) and 0xff)
        val db = (first and 0xff) - (second and 0xff)
        return dr * dr + dg * dg + db * db
    }

    companion object {
        private val HUES = intArrayOf(
            Color.rgb(20, 30, 255), Color.rgb(0, 150, 255), Color.rgb(0, 255, 220),
            Color.rgb(40, 255, 40), Color.rgb(220, 255, 0), Color.rgb(255, 150, 0),
            Color.RED, Color.rgb(255, 0, 180), Color.rgb(120, 0, 255), Color.rgb(20, 30, 255)
        )
    }
}
