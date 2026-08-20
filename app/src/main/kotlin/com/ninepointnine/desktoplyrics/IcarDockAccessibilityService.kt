package com.ninepointnine.desktoplyrics

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/** Read-only adapter for the verified iCAR Launcher Dock window geometry. */
class IcarDockAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var connected = false
    private var lastPublishedState: IcarRightDockWindowState? = null

    private val reconcileRunnable = Runnable(::reconcileWindows)
    private val settleRunnable = Runnable(::reconcileWindows)

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        publish(IcarRightDockWindowState.UNKNOWN)
        scheduleReconcile(immediate = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val observedEvent = event ?: return
        val packageName = observedEvent.packageName?.toString()
        if (packageName != null && packageName != IcarRightDockWindowClassifier.LAUNCHER_PACKAGE) {
            return
        }
        if (!IcarDockAccessibilityEventPolicy.shouldInspect(
                eventType = observedEvent.eventType,
                contentChangeTypes = observedEvent.contentChangeTypes
            )
        ) {
            return
        }
        scheduleReconcile(
            immediate = observedEvent.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                observedEvent.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        )
    }

    override fun onInterrupt() {
        cancelPendingReconciliations()
        publish(IcarRightDockWindowState.UNKNOWN)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        cancelPendingReconciliations()
        publish(IcarRightDockWindowState.UNKNOWN)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        cancelPendingReconciliations()
        publish(IcarRightDockWindowState.UNKNOWN)
        super.onDestroy()
    }

    private fun scheduleReconcile(immediate: Boolean) {
        if (!connected) return
        mainHandler.removeCallbacks(reconcileRunnable)
        mainHandler.postDelayed(
            reconcileRunnable,
            if (immediate) 0L else EVENT_DEBOUNCE_MS
        )
        mainHandler.removeCallbacks(settleRunnable)
        mainHandler.postDelayed(settleRunnable, ANIMATION_SETTLE_RECHECK_MS)
    }

    private fun cancelPendingReconciliations() {
        mainHandler.removeCallbacks(reconcileRunnable)
        mainHandler.removeCallbacks(settleRunnable)
    }

    private fun reconcileWindows() {
        if (!connected) return
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        val observedWindows = captureWindows()
        publish(
            IcarRightDockWindowClassifier.classify(
                screenWidthPx = metrics.widthPixels,
                screenHeightPx = metrics.heightPixels,
                windows = observedWindows
            )
        )
    }

    @Suppress("DEPRECATION")
    private fun captureWindows(): List<IcarObservedWindow> {
        val windowInfos = runCatching { windows }.getOrNull() ?: return emptyList()
        return windowInfos.mapNotNull { window ->
            try {
                val bounds = Rect()
                runCatching { window.getBoundsInScreen(bounds) }
                    .getOrElse { return@mapNotNull null }
                val root = runCatching { window.root }.getOrNull()
                try {
                    val packageName = root?.packageName?.toString().orEmpty()
                    if (packageName.isBlank()) {
                        null
                    } else {
                        IcarObservedWindow(
                            packageName = packageName,
                            leftPx = bounds.left,
                            topPx = bounds.top,
                            rightPx = bounds.right,
                            bottomPx = bounds.bottom
                        )
                    }
                } finally {
                    root?.recycle()
                }
            } finally {
                window.recycle()
            }
        }
    }

    private fun publish(state: IcarRightDockWindowState) {
        if (state == lastPublishedState) return
        lastPublishedState = state
        IcarRightDockStateRegistry.update(state)
        Log.i(
            LOG_TAG,
            "iCAR right Dock status=${state.status} top=${state.expandedTopPx}"
        )
    }

    companion object {
        private const val EVENT_DEBOUNCE_MS = 80L
        private const val ANIMATION_SETTLE_RECHECK_MS = 320L
        private const val LOG_TAG = "DesktopLyrics"
    }
}

internal object IcarDockAccessibilityEventPolicy {
    fun shouldInspect(eventType: Int, contentChangeTypes: Int): Boolean = when (eventType) {
        AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> true
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
            contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED ||
                contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE != 0
        }
        else -> false
    }
}
