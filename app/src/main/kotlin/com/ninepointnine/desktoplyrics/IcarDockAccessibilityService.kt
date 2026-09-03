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

/** Read-only adapter for verified iCAR Launcher SR motion and Dock geometry. */
class IcarDockAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val srMotionTracker = IcarSrPanelMotionTracker()
    private var connected = false
    private var lastPublishedState: IcarDockWindowState? = null
    private var lastPublishedSrMotion: IcarSrPanelOccupancy? = null

    private val reconcileRunnable = Runnable { reconcileWindows(settleSrMotion = false) }
    private val settleRunnable = Runnable { reconcileWindows(settleSrMotion = true) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        publish(IcarDockWindowState.UNKNOWN)
        publishSrMotion(IcarSrPanelOccupancy.UNKNOWN)
        scheduleReconcile()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val observedEvent = event ?: return
        val packageName = observedEvent.packageName?.toString()
        if (packageName != null && packageName != IcarDockWindowClassifier.LAUNCHER_PACKAGE) {
            return
        }
        if (!IcarDockAccessibilityEventPolicy.shouldInspect(
                eventType = observedEvent.eventType,
                contentChangeTypes = observedEvent.contentChangeTypes
            )
        ) {
            return
        }
        scheduleReconcile()
    }

    override fun onInterrupt() {
        cancelPendingReconciliations()
        publish(IcarDockWindowState.UNKNOWN)
        resetSrMotion()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        cancelPendingReconciliations()
        publish(IcarDockWindowState.UNKNOWN)
        resetSrMotion()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        cancelPendingReconciliations()
        publish(IcarDockWindowState.UNKNOWN)
        resetSrMotion()
        super.onDestroy()
    }

    private fun scheduleReconcile() {
        if (!connected) return
        mainHandler.removeCallbacks(reconcileRunnable)
        mainHandler.post(reconcileRunnable)
        mainHandler.removeCallbacks(settleRunnable)
        mainHandler.postDelayed(settleRunnable, IcarSrPanelObservationSpec.SETTLE_RECHECK_MS)
    }

    private fun cancelPendingReconciliations() {
        mainHandler.removeCallbacks(reconcileRunnable)
        mainHandler.removeCallbacks(settleRunnable)
    }

    private fun reconcileWindows(settleSrMotion: Boolean) {
        if (!connected) return
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        val snapshot = captureLauncherSnapshot()
        publish(
            IcarDockWindowClassifier.classify(
                screenWidthPx = metrics.widthPixels,
                screenHeightPx = metrics.heightPixels,
                windows = snapshot.windows
            )
        )
        publishSrMotion(
            if (settleSrMotion) {
                srMotionTracker.settle(
                    handlerLeftPx = snapshot.srHandlerBounds?.left,
                    handlerRightPx = snapshot.srHandlerBounds?.right
                )
            } else {
                srMotionTracker.update(
                    screenWidthPx = metrics.widthPixels,
                    handlerLeftPx = snapshot.srHandlerBounds?.left,
                    handlerRightPx = snapshot.srHandlerBounds?.right
                )
            }
        )
    }

    @Suppress("DEPRECATION")
    private fun captureLauncherSnapshot(): LauncherSnapshot {
        val windowInfos = runCatching { windows }.getOrNull()
            ?: return LauncherSnapshot(emptyList(), null)
        var srHandlerBounds: Rect? = null
        val observedWindows = windowInfos.mapNotNull { window ->
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
                        if (packageName == IcarDockWindowClassifier.LAUNCHER_PACKAGE &&
                            srHandlerBounds == null && root != null
                        ) {
                            srHandlerBounds = findSrHandlerBounds(root)
                        }
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
        return LauncherSnapshot(observedWindows, srHandlerBounds)
    }

    @Suppress("DEPRECATION")
    private fun findSrHandlerBounds(root: android.view.accessibility.AccessibilityNodeInfo): Rect? {
        val nodes = runCatching {
            root.findAccessibilityNodeInfosByViewId(SR_HANDLER_VIEW_ID)
        }.getOrNull().orEmpty()
        var result: Rect? = null
        try {
            nodes.forEach { node ->
                if (result == null) {
                    val bounds = Rect()
                    runCatching { node.getBoundsInScreen(bounds) }
                    if (bounds.width() > 0 && bounds.height() > 0) result = bounds
                }
            }
        } finally {
            nodes.forEach { node -> runCatching { node.recycle() } }
        }
        return result
    }

    private fun publish(state: IcarDockWindowState) {
        if (state == lastPublishedState) return
        lastPublishedState = state
        IcarDockStateRegistry.update(state)
        Log.i(
            LOG_TAG,
            "iCAR Dock left=${state.left.status}/${state.left.expandedTopPx} " +
                "center=${state.center.status}/${state.center.expandedTopPx} " +
                "right=${state.right.status}/${state.right.expandedTopPx}"
        )
    }

    private fun publishSrMotion(state: IcarSrPanelOccupancy) {
        if (state == lastPublishedSrMotion) return
        lastPublishedSrMotion = state
        IcarSrPanelMotionRegistry.update(state)
        Log.i(LOG_TAG, "iCAR SR motion=$state")
    }

    private fun resetSrMotion() {
        srMotionTracker.reset()
        publishSrMotion(IcarSrPanelOccupancy.UNKNOWN)
    }

    private data class LauncherSnapshot(
        val windows: List<IcarObservedWindow>,
        val srHandlerBounds: Rect?
    )

    companion object {
        private const val SR_HANDLER_VIEW_ID =
            "com.mengbo.launcher3:id/adas_handler_view"
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
