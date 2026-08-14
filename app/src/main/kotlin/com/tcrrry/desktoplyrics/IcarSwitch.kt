package com.tcrrry.desktoplyrics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Checkable
import android.widget.Switch
import androidx.core.content.ContextCompat
import kotlin.math.min

/**
 * Project-owned rendering of the iCAR MBSwitch geometry.
 *
 * The source component renders in a 64 x 36 px box with a 30 px thumb
 * drawable. Its transparent 8 px stroke leaves a 22 px white center.
 */
class IcarSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), Checkable {

    fun interface OnCheckedChangeListener {
        fun onCheckedChanged(view: IcarSwitch, isChecked: Boolean)
    }

    var accentColor: Int = ContextCompat.getColor(context, R.color.settings_accent)
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.settings_switch_thumb_color)
    }
    private val offTrackColor = ContextCompat.getColor(context, R.color.settings_switch_track_off)
    private val trackBounds = RectF()
    private var checked = false
    private var checkedChangeListener: OnCheckedChangeListener? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = resources.getDimensionPixelSize(R.dimen.settings_switch_width)
        val desiredHeight = resources.getDimensionPixelSize(R.dimen.settings_switch_height)
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom
        if (availableWidth <= 0 || availableHeight <= 0) return

        val scale = min(
            availableWidth / IcarSwitchGeometry.WIDTH_PX,
            availableHeight / IcarSwitchGeometry.HEIGHT_PX
        )
        val drawWidth = IcarSwitchGeometry.WIDTH_PX * scale
        val drawHeight = IcarSwitchGeometry.HEIGHT_PX * scale
        val left = paddingLeft + (availableWidth - drawWidth) / 2f
        val top = paddingTop + (availableHeight - drawHeight) / 2f

        trackBounds.set(left, top, left + drawWidth, top + drawHeight)
        trackPaint.color = if (checked) {
            accentColor
        } else {
            offTrackColor
        }
        canvas.drawRoundRect(trackBounds, drawHeight / 2f, drawHeight / 2f, trackPaint)

        val thumbCenterX = left + IcarSwitchGeometry.thumbCenterXPx(checked) * scale
        val thumbCenterY = top + IcarSwitchGeometry.HEIGHT_PX * scale / 2f
        canvas.drawCircle(
            thumbCenterX,
            thumbCenterY,
            IcarSwitchGeometry.THUMB_CORE_RADIUS_PX * scale,
            thumbPaint
        )
    }

    override fun isChecked(): Boolean = checked

    override fun setChecked(value: Boolean) {
        if (checked == value) return
        checked = value
        refreshDrawableState()
        invalidate()
        checkedChangeListener?.onCheckedChanged(this, value)
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    override fun toggle() {
        isChecked = !checked
    }

    override fun performClick(): Boolean {
        val toggled = isEnabled
        if (toggled) toggle()
        return super.performClick() || toggled
    }

    fun setOnCheckedChangeListener(listener: OnCheckedChangeListener?) {
        checkedChangeListener = listener
    }

    override fun getAccessibilityClassName(): CharSequence = Switch::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = accessibilityClassName
        info.isCheckable = true
        info.isChecked = checked
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            info.stateDescription = context.getString(
                if (checked) R.string.accessibility_switch_on else R.string.accessibility_switch_off
            )
        }
    }
}

internal object IcarSwitchGeometry {
    const val WIDTH_PX = 64f
    const val HEIGHT_PX = 36f
    const val THUMB_OUTER_DIAMETER_PX = 30f
    const val THUMB_TRANSPARENT_STROKE_PX = 8f
    const val THUMB_CORE_RADIUS_PX = 11f
    private const val THUMB_CENTER_OFF_X_PX = 15f
    private const val THUMB_CENTER_ON_X_PX = 49f

    fun thumbCenterXPx(checked: Boolean): Float =
        if (checked) THUMB_CENTER_ON_X_PX else THUMB_CENTER_OFF_X_PX
}
