package com.ninepointnine.desktoplyrics

import kotlin.math.abs

internal enum class IcarDockPanelStatus {
    UNKNOWN,
    COLLAPSED,
    EXPANDED
}

internal data class IcarDockPanelState(
    val status: IcarDockPanelStatus,
    val expandedTopPx: Int? = null
) {
    init {
        require(status == IcarDockPanelStatus.EXPANDED || expandedTopPx == null)
        require(status != IcarDockPanelStatus.EXPANDED || expandedTopPx != null)
    }

    companion object {
        val UNKNOWN = IcarDockPanelState(IcarDockPanelStatus.UNKNOWN)
        val COLLAPSED = IcarDockPanelState(IcarDockPanelStatus.COLLAPSED)

        fun expanded(topPx: Int): IcarDockPanelState = IcarDockPanelState(
            status = IcarDockPanelStatus.EXPANDED,
            expandedTopPx = topPx
        )
    }
}

internal data class IcarDockWindowState(
    val left: IcarDockPanelState,
    val center: IcarDockPanelState,
    val right: IcarDockPanelState
) {
    fun panelFor(
        position: WallpaperLyricsPosition,
        srPanelOccupancy: IcarSrPanelOccupancy
    ): IcarDockPanelState = when {
        position == WallpaperLyricsPosition.RIGHT -> right
        srPanelOccupancy == IcarSrPanelOccupancy.CLEAR -> left
        else -> center
    }

    companion object {
        val UNKNOWN = IcarDockWindowState(
            left = IcarDockPanelState.UNKNOWN,
            center = IcarDockPanelState.UNKNOWN,
            right = IcarDockPanelState.UNKNOWN
        )
    }
}

internal data class IcarObservedWindow(
    val packageName: String,
    val leftPx: Int,
    val topPx: Int,
    val rightPx: Int,
    val bottomPx: Int
) {
    val widthPx: Int
        get() = (rightPx - leftPx).coerceAtLeast(0)

    val heightPx: Int
        get() = (bottomPx - topPx).coerceAtLeast(0)
}

internal object IcarSrPanelObservationSpec {
    const val SETTLE_RECHECK_MS = 320L

    fun motionThresholdPx(screenWidthPx: Int): Int =
        maxOf(MIN_MOTION_THRESHOLD_PX, screenWidthPx / WIDTH_DIVISOR)

    private const val MIN_MOTION_THRESHOLD_PX = 4
    private const val WIDTH_DIVISOR = 240
}

/** Converts the verified SR handle movement into a one-shot direction hint. */
internal class IcarSrPanelMotionTracker {
    private var previousCenterXPx: Int? = null
    private var activeMotion = IcarSrPanelOccupancy.UNKNOWN

    fun update(
        screenWidthPx: Int,
        handlerLeftPx: Int?,
        handlerRightPx: Int?
    ): IcarSrPanelOccupancy {
        if (screenWidthPx <= 0 || handlerLeftPx == null || handlerRightPx == null ||
            handlerRightPx <= handlerLeftPx
        ) {
            previousCenterXPx = null
            activeMotion = IcarSrPanelOccupancy.UNKNOWN
            return IcarSrPanelOccupancy.UNKNOWN
        }

        val centerXPx = handlerLeftPx + (handlerRightPx - handlerLeftPx) / 2
        val previousCenterX = previousCenterXPx
        previousCenterXPx = centerXPx
        val motionThresholdPx = IcarSrPanelObservationSpec.motionThresholdPx(screenWidthPx)
        activeMotion = when {
            previousCenterX != null && centerXPx - previousCenterX >= motionThresholdPx -> {
                IcarSrPanelOccupancy.OCCUPIED
            }
            previousCenterX != null && previousCenterX - centerXPx >= motionThresholdPx -> {
                IcarSrPanelOccupancy.CLEAR
            }
            else -> activeMotion
        }
        return activeMotion
    }

    fun settle(handlerLeftPx: Int?, handlerRightPx: Int?): IcarSrPanelOccupancy {
        previousCenterXPx = if (
            handlerLeftPx != null && handlerRightPx != null && handlerRightPx > handlerLeftPx
        ) {
            handlerLeftPx + (handlerRightPx - handlerLeftPx) / 2
        } else {
            null
        }
        activeMotion = IcarSrPanelOccupancy.UNKNOWN
        return activeMotion
    }

    fun reset() {
        previousCenterXPx = null
        activeMotion = IcarSrPanelOccupancy.UNKNOWN
    }
}

internal object IcarSrPanelOccupancyPolicy {
    fun resolve(
        stableOccupancy: IcarSrPanelOccupancy,
        motionOccupancy: IcarSrPanelOccupancy
    ): IcarSrPanelOccupancy = if (motionOccupancy != IcarSrPanelOccupancy.UNKNOWN) {
        motionOccupancy
    } else {
        stableOccupancy
    }
}

/** Classifies the verified left, center and right Launcher Dock window geometry. */
internal object IcarDockWindowClassifier {
    const val LAUNCHER_PACKAGE = "com.mengbo.launcher3"

    fun classify(
        screenWidthPx: Int,
        screenHeightPx: Int,
        windows: List<IcarObservedWindow>
    ): IcarDockWindowState {
        if (screenWidthPx <= 0 || screenHeightPx <= 0) {
            return IcarDockWindowState.UNKNOWN
        }

        val tolerancePx = maxOf(8, screenWidthPx / 160)
        val launcherWindows = windows.filter { it.packageName == LAUNCHER_PACKAGE }
        val hasLauncherRoot = launcherWindows.any { window ->
            window.leftPx <= tolerancePx &&
                window.topPx <= tolerancePx &&
                window.rightPx >= screenWidthPx - tolerancePx &&
                window.bottomPx >= screenHeightPx - tolerancePx
        }
        if (!hasLauncherRoot) return IcarDockWindowState.UNKNOWN

        return IcarDockWindowState(
            left = classifyPanel(screenWidthPx, screenHeightPx, launcherWindows, DockSlot.LEFT),
            center = classifyPanel(screenWidthPx, screenHeightPx, launcherWindows, DockSlot.CENTER),
            right = classifyPanel(screenWidthPx, screenHeightPx, launcherWindows, DockSlot.RIGHT)
        )
    }

    private fun classifyPanel(
        screenWidthPx: Int,
        screenHeightPx: Int,
        launcherWindows: List<IcarObservedWindow>,
        slot: DockSlot
    ): IcarDockPanelState {
        val tolerancePx = maxOf(8, screenWidthPx / 160)
        val panels = launcherWindows.filter { window ->
            val minPanelWidthPx = screenWidthPx * 25 / 100
            val maxPanelWidthPx = screenWidthPx * 40 / 100
            val anchoredToSlot = when (slot) {
                DockSlot.LEFT -> abs(window.leftPx) <= tolerancePx
                DockSlot.CENTER -> {
                    abs(window.leftPx + window.rightPx - screenWidthPx) <= tolerancePx * 2
                }
                DockSlot.RIGHT -> abs(window.rightPx - screenWidthPx) <= tolerancePx
            }
            anchoredToSlot && window.widthPx in minPanelWidthPx..maxPanelWidthPx &&
                abs(window.bottomPx - screenHeightPx) <= tolerancePx
        }
        if (panels.isEmpty()) return IcarDockPanelState.UNKNOWN

        val verifiedCollapsedHeightPx = screenHeightPx * COLLAPSED_HEIGHT_DESIGN_PX /
            DESIGN_HEIGHT_PX
        val collapsedPanel = panels.minByOrNull { window ->
            abs(window.heightPx - verifiedCollapsedHeightPx)
        } ?: return IcarDockPanelState.UNKNOWN
        val additionalPanels = panels.filterNot { it === collapsedPanel }
        if (additionalPanels.isNotEmpty()) {
            val expandedTopPx = additionalPanels.minOf(IcarObservedWindow::topPx)
                .coerceIn(0, screenHeightPx)
            return IcarDockPanelState.expanded(expandedTopPx)
        }

        val expandedHeightThresholdPx = screenHeightPx * EXPANDED_HEIGHT_THRESHOLD_PERCENT / 100
        return if (collapsedPanel.heightPx >= expandedHeightThresholdPx) {
            IcarDockPanelState.expanded(collapsedPanel.topPx.coerceIn(0, screenHeightPx))
        } else {
            IcarDockPanelState.COLLAPSED
        }
    }

    private const val DESIGN_HEIGHT_PX = 1080
    private const val COLLAPSED_HEIGHT_DESIGN_PX = 165
    private const val EXPANDED_HEIGHT_THRESHOLD_PERCENT = 30

    private enum class DockSlot {
        LEFT,
        CENTER,
        RIGHT
    }
}

internal enum class LyricsOverlayVisibility {
    VISIBLE,
    HIDDEN
}

/**
 * Leases from cooperating applications describe which part of the display they
 * occupy. A full-display lease has higher visibility priority than a desktop
 * region lease, while the policy remains the sole owner of the final result.
 */
internal data class IcarExternalSurfaceOccupancy(
    val desktopRegionOccupied: Boolean = false,
    val fullDisplayOccupied: Boolean = false,
)

internal data class IcarLyricsPresentation(
    val surfaceMode: LyricsSurfaceMode,
    val visibility: LyricsOverlayVisibility,
    val desktopBottomLimitPx: Int? = null,
    val desktopPosition: WallpaperLyricsPosition = WallpaperLyricsPosition.RIGHT,
    val srPanelOccupancy: IcarSrPanelOccupancy = IcarSrPanelOccupancy.UNKNOWN
)

internal object IcarWallpaperPositionPolicy {
    fun leftPx(
        screenWidthPx: Int,
        surfaceWidthPx: Int,
        edgeInsetPx: Int,
        position: WallpaperLyricsPosition,
        srPanelOccupancy: IcarSrPanelOccupancy
    ): Int {
        val maximumLeft = (screenWidthPx - surfaceWidthPx).coerceAtLeast(0)
        val leftAligned = edgeInsetPx.coerceIn(0, maximumLeft)
        val rightAligned = (screenWidthPx - surfaceWidthPx - edgeInsetPx)
            .coerceIn(0, maximumLeft)
        return when {
            position == WallpaperLyricsPosition.RIGHT -> rightAligned
            srPanelOccupancy == IcarSrPanelOccupancy.CLEAR -> leftAligned
            else -> rightAligned
        }
    }
}

internal object IcarWallpaperHorizontalMotionSpec {
    const val DURATION_MS = 250L
    const val CONTROL_X1 = 0.2f
    const val CONTROL_Y1 = 0.8f
    const val CONTROL_X2 = 0.2f
    const val CONTROL_Y2 = 1f
}

internal object IcarWallpaperClipPolicy {
    fun bottomPx(
        defaultBottomPx: Int,
        dockTopPx: Int?,
        safeGapPx: Int
    ): Int {
        val top = dockTopPx ?: return defaultBottomPx
        return minOf(defaultBottomPx, (top - safeGapPx.coerceAtLeast(0)).coerceAtLeast(0))
    }

    fun visibleRatioBasisPoints(
        defaultHeightPx: Int,
        visibleHeightPx: Int
    ): Int {
        if (defaultHeightPx <= 0) return FULL_RATIO_BASIS_POINTS
        val visible = visibleHeightPx.coerceIn(0, defaultHeightPx)
        return ((visible.toLong() * FULL_RATIO_BASIS_POINTS + defaultHeightPx / 2) /
            defaultHeightPx).toInt()
    }

    const val FULL_RATIO_BASIS_POINTS = 10_000
}

/** Resolves all car-window signals without relying on inverse "restore" actions. */
internal object IcarLyricsPresentationPolicy {
    fun resolve(
        displayState: IcarDisplayState?,
        wallpaperLyricsEnabled: Boolean,
        localSettingsOpen: Boolean,
        externalSurfaceOccupancy: IcarExternalSurfaceOccupancy,
        wallpaperPosition: WallpaperLyricsPosition,
        srPanelMotionOccupancy: IcarSrPanelOccupancy = IcarSrPanelOccupancy.UNKNOWN,
        dockState: IcarDockWindowState
    ): IcarLyricsPresentation {
        val srPanelOccupancy = IcarSrPanelOccupancyPolicy.resolve(
            stableOccupancy = displayState?.srPanelOccupancy ?: IcarSrPanelOccupancy.UNKNOWN,
            motionOccupancy = srPanelMotionOccupancy
        )
        val baseSurface = IcarLyricsSurfacePolicy.effectiveSurfaceMode(
            displayState = displayState,
            wallpaperLyricsEnabled = wallpaperLyricsEnabled,
            localSettingsOpen = localSettingsOpen,
            desktopSurfaceOccupied = externalSurfaceOccupancy.desktopRegionOccupied
        )
        if (externalSurfaceOccupancy.fullDisplayOccupied) {
            return IcarLyricsPresentation(
                surfaceMode = baseSurface,
                visibility = LyricsOverlayVisibility.HIDDEN,
                desktopPosition = wallpaperPosition,
                srPanelOccupancy = srPanelOccupancy
            )
        }
        if (displayState?.climatePageOccupancy != IcarClimatePageOccupancy.CLEAR) {
            return IcarLyricsPresentation(
                surfaceMode = baseSurface,
                visibility = LyricsOverlayVisibility.HIDDEN,
                desktopPosition = wallpaperPosition,
                srPanelOccupancy = srPanelOccupancy
            )
        }
        if (baseSurface != LyricsSurfaceMode.DESKTOP) {
            return IcarLyricsPresentation(
                surfaceMode = baseSurface,
                visibility = LyricsOverlayVisibility.VISIBLE,
                desktopPosition = wallpaperPosition,
                srPanelOccupancy = srPanelOccupancy
            )
        }

        val selectedDock = dockState.panelFor(wallpaperPosition, srPanelOccupancy)
        return when (selectedDock.status) {
            IcarDockPanelStatus.UNKNOWN -> IcarLyricsPresentation(
                surfaceMode = LyricsSurfaceMode.TOPBAR,
                visibility = LyricsOverlayVisibility.VISIBLE,
                desktopPosition = wallpaperPosition,
                srPanelOccupancy = srPanelOccupancy
            )
            IcarDockPanelStatus.COLLAPSED -> IcarLyricsPresentation(
                surfaceMode = LyricsSurfaceMode.DESKTOP,
                visibility = LyricsOverlayVisibility.VISIBLE,
                desktopPosition = wallpaperPosition,
                srPanelOccupancy = srPanelOccupancy
            )
            IcarDockPanelStatus.EXPANDED -> IcarLyricsPresentation(
                surfaceMode = LyricsSurfaceMode.DESKTOP,
                visibility = LyricsOverlayVisibility.VISIBLE,
                desktopBottomLimitPx = selectedDock.expandedTopPx,
                desktopPosition = wallpaperPosition,
                srPanelOccupancy = srPanelOccupancy
            )
        }
    }
}

internal class IcarDockStateStore {
    private val listeners = linkedSetOf<(IcarDockWindowState) -> Unit>()
    private var state = IcarDockWindowState.UNKNOWN

    fun addListener(listener: (IcarDockWindowState) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeListener(listener: (IcarDockWindowState) -> Unit) {
        listeners -= listener
    }

    fun update(nextState: IcarDockWindowState) {
        if (state == nextState) return
        state = nextState
        listeners.toList().forEach { listener -> listener(nextState) }
    }
}

internal object IcarDockStateRegistry {
    private val store = IcarDockStateStore()

    fun addListener(listener: (IcarDockWindowState) -> Unit) = store.addListener(listener)

    fun removeListener(listener: (IcarDockWindowState) -> Unit) = store.removeListener(listener)

    fun update(state: IcarDockWindowState) = store.update(state)
}

internal class IcarSrPanelMotionStore {
    private val listeners = linkedSetOf<(IcarSrPanelOccupancy) -> Unit>()
    private var state = IcarSrPanelOccupancy.UNKNOWN

    fun addListener(listener: (IcarSrPanelOccupancy) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeListener(listener: (IcarSrPanelOccupancy) -> Unit) {
        listeners -= listener
    }

    fun update(nextState: IcarSrPanelOccupancy) {
        if (state == nextState) return
        state = nextState
        listeners.toList().forEach { it(nextState) }
    }
}

internal object IcarSrPanelMotionRegistry {
    private val store = IcarSrPanelMotionStore()

    fun addListener(listener: (IcarSrPanelOccupancy) -> Unit) = store.addListener(listener)

    fun removeListener(listener: (IcarSrPanelOccupancy) -> Unit) = store.removeListener(listener)

    fun update(state: IcarSrPanelOccupancy) = store.update(state)
}
