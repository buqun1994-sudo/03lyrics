package com.tcrrry.desktoplyrics

import kotlin.math.abs

internal enum class IcarRightDockStatus {
    UNKNOWN,
    COLLAPSED,
    EXPANDED
}

internal data class IcarRightDockWindowState(
    val status: IcarRightDockStatus,
    val expandedTopPx: Int? = null
) {
    init {
        require(status == IcarRightDockStatus.EXPANDED || expandedTopPx == null)
        require(status != IcarRightDockStatus.EXPANDED || expandedTopPx != null)
    }

    companion object {
        val UNKNOWN = IcarRightDockWindowState(IcarRightDockStatus.UNKNOWN)
        val COLLAPSED = IcarRightDockWindowState(IcarRightDockStatus.COLLAPSED)

        fun expanded(topPx: Int): IcarRightDockWindowState = IcarRightDockWindowState(
            status = IcarRightDockStatus.EXPANDED,
            expandedTopPx = topPx
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

/** Classifies only the verified right-side Launcher Dock window geometry. */
internal object IcarRightDockWindowClassifier {
    const val LAUNCHER_PACKAGE = "com.mengbo.launcher3"

    fun classify(
        screenWidthPx: Int,
        screenHeightPx: Int,
        windows: List<IcarObservedWindow>
    ): IcarRightDockWindowState {
        if (screenWidthPx <= 0 || screenHeightPx <= 0) {
            return IcarRightDockWindowState.UNKNOWN
        }

        val tolerancePx = maxOf(8, screenWidthPx / 160)
        val launcherWindows = windows.filter { it.packageName == LAUNCHER_PACKAGE }
        val hasLauncherRoot = launcherWindows.any { window ->
            window.leftPx <= tolerancePx &&
                window.topPx <= tolerancePx &&
                window.rightPx >= screenWidthPx - tolerancePx &&
                window.bottomPx >= screenHeightPx - tolerancePx
        }
        if (!hasLauncherRoot) return IcarRightDockWindowState.UNKNOWN

        val rightPanels = launcherWindows.filter { window ->
            val minPanelLeftPx = screenWidthPx * 60 / 100
            val maxPanelLeftPx = screenWidthPx * 75 / 100
            val minPanelWidthPx = screenWidthPx * 25 / 100
            val maxPanelWidthPx = screenWidthPx * 40 / 100
            window.leftPx in minPanelLeftPx..maxPanelLeftPx &&
                window.widthPx in minPanelWidthPx..maxPanelWidthPx &&
                abs(window.rightPx - screenWidthPx) <= tolerancePx &&
                abs(window.bottomPx - screenHeightPx) <= tolerancePx
        }
        if (rightPanels.isEmpty()) return IcarRightDockWindowState.UNKNOWN

        val verifiedCollapsedHeightPx = screenHeightPx * COLLAPSED_HEIGHT_DESIGN_PX /
            DESIGN_HEIGHT_PX
        val collapsedPanel = rightPanels.minByOrNull { window ->
            abs(window.heightPx - verifiedCollapsedHeightPx)
        } ?: return IcarRightDockWindowState.UNKNOWN
        val additionalPanels = rightPanels.filterNot { it === collapsedPanel }
        if (additionalPanels.isNotEmpty()) {
            val expandedTopPx = additionalPanels.minOf(IcarObservedWindow::topPx)
                .coerceIn(0, screenHeightPx)
            return IcarRightDockWindowState.expanded(expandedTopPx)
        }

        val expandedHeightThresholdPx = screenHeightPx * EXPANDED_HEIGHT_THRESHOLD_PERCENT / 100
        return if (collapsedPanel.heightPx >= expandedHeightThresholdPx) {
            IcarRightDockWindowState.expanded(collapsedPanel.topPx.coerceIn(0, screenHeightPx))
        } else {
            IcarRightDockWindowState.COLLAPSED
        }
    }

    private const val DESIGN_HEIGHT_PX = 1080
    private const val COLLAPSED_HEIGHT_DESIGN_PX = 165
    private const val EXPANDED_HEIGHT_THRESHOLD_PERCENT = 30
}

internal enum class LyricsOverlayVisibility {
    VISIBLE,
    HIDDEN
}

internal data class IcarLyricsPresentation(
    val surfaceMode: LyricsSurfaceMode,
    val visibility: LyricsOverlayVisibility,
    val desktopBottomLimitPx: Int? = null
)

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
        desktopSurfaceOccupied: Boolean,
        rightDockState: IcarRightDockWindowState
    ): IcarLyricsPresentation {
        val baseSurface = IcarLyricsSurfacePolicy.effectiveSurfaceMode(
            displayState = displayState,
            wallpaperLyricsEnabled = wallpaperLyricsEnabled,
            localSettingsOpen = localSettingsOpen,
            desktopSurfaceOccupied = desktopSurfaceOccupied
        )
        if (displayState?.climatePageOccupancy != IcarClimatePageOccupancy.CLEAR) {
            return IcarLyricsPresentation(
                surfaceMode = baseSurface,
                visibility = LyricsOverlayVisibility.HIDDEN
            )
        }
        if (baseSurface != LyricsSurfaceMode.DESKTOP) {
            return IcarLyricsPresentation(
                surfaceMode = baseSurface,
                visibility = LyricsOverlayVisibility.VISIBLE
            )
        }

        return when (rightDockState.status) {
            IcarRightDockStatus.UNKNOWN -> IcarLyricsPresentation(
                surfaceMode = LyricsSurfaceMode.TOPBAR,
                visibility = LyricsOverlayVisibility.VISIBLE
            )
            IcarRightDockStatus.COLLAPSED -> IcarLyricsPresentation(
                surfaceMode = LyricsSurfaceMode.DESKTOP,
                visibility = LyricsOverlayVisibility.VISIBLE
            )
            IcarRightDockStatus.EXPANDED -> IcarLyricsPresentation(
                surfaceMode = LyricsSurfaceMode.DESKTOP,
                visibility = LyricsOverlayVisibility.VISIBLE,
                desktopBottomLimitPx = rightDockState.expandedTopPx
            )
        }
    }
}

internal class IcarRightDockStateStore {
    private val listeners = linkedSetOf<(IcarRightDockWindowState) -> Unit>()
    private var state = IcarRightDockWindowState.UNKNOWN

    fun addListener(listener: (IcarRightDockWindowState) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeListener(listener: (IcarRightDockWindowState) -> Unit) {
        listeners -= listener
    }

    fun update(nextState: IcarRightDockWindowState) {
        if (state == nextState) return
        state = nextState
        listeners.toList().forEach { listener -> listener(nextState) }
    }
}

internal object IcarRightDockStateRegistry {
    private val store = IcarRightDockStateStore()

    fun addListener(listener: (IcarRightDockWindowState) -> Unit) = store.addListener(listener)

    fun removeListener(listener: (IcarRightDockWindowState) -> Unit) = store.removeListener(listener)

    fun update(state: IcarRightDockWindowState) = store.update(state)
}
