package com.ninepointnine.desktoplyrics

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IcarWindowAvoidancePolicyTest {
    @Test
    fun `collapsed right Dock is clear`() {
        val state = classify(
            launcherRoot(),
            rightDockCollapsed()
        )

        assertEquals(IcarDockPanelStatus.COLLAPSED, state.right.status)
        assertNull(state.right.expandedTopPx)
    }

    @Test
    fun `expanded right Dock reports its real top edge`() {
        val state = classify(
            launcherRoot(),
            rightDockCollapsed(),
            launcherWindow(left = 1275, top = 586, right = 1920, bottom = 1080)
        )

        assertEquals(IcarDockPanelStatus.EXPANDED, state.right.status)
        assertEquals(586, state.right.expandedTopPx)
    }

    @Test
    fun `left and center Dock expansion are classified independently`() {
        val state = classify(
            launcherRoot(),
            rightDockCollapsed(),
            launcherWindow(left = 0, top = 586, right = 645, bottom = 1080),
            launcherWindow(left = 645, top = 586, right = 1275, bottom = 1080)
        )

        assertEquals(IcarDockPanelStatus.EXPANDED, state.left.status)
        assertEquals(586, state.left.expandedTopPx)
        assertEquals(IcarDockPanelStatus.EXPANDED, state.center.status)
        assertEquals(586, state.center.expandedTopPx)
        assertEquals(IcarDockPanelStatus.COLLAPSED, state.right.status)
    }

    @Test
    fun `closing right Dock stays expanded until its additional window disappears`() {
        val state = classify(
            launcherRoot(),
            rightDockCollapsed(),
            launcherWindow(left = 1275, top = 880, right = 1920, bottom = 1080)
        )

        assertEquals(IcarDockPanelStatus.EXPANDED, state.right.status)
        assertEquals(880, state.right.expandedTopPx)
    }

    @Test
    fun `single large right Dock window is still recognized`() {
        val state = classify(
            launcherRoot(),
            launcherWindow(left = 1275, top = 586, right = 1920, bottom = 1080)
        )

        assertEquals(IcarDockPanelStatus.EXPANDED, state.right.status)
        assertEquals(586, state.right.expandedTopPx)
    }

    @Test
    fun `snapshot without verified Launcher root is unknown`() {
        val state = classify(rightDockCollapsed())

        assertEquals(IcarDockPanelStatus.UNKNOWN, state.left.status)
        assertEquals(IcarDockPanelStatus.UNKNOWN, state.center.status)
        assertEquals(IcarDockPanelStatus.UNKNOWN, state.right.status)
    }

    @Test
    fun `right Dock classification scales with the display`() {
        val windows = listOf(
            launcherWindow(left = 0, top = 0, right = 1280, bottom = 720),
            launcherWindow(left = 850, top = 610, right = 1280, bottom = 720),
            launcherWindow(left = 850, top = 391, right = 1280, bottom = 720)
        )

        val state = IcarDockWindowClassifier.classify(
            screenWidthPx = 1280,
            screenHeightPx = 720,
            windows = windows
        )

        assertEquals(IcarDockPanelStatus.EXPANDED, state.right.status)
        assertEquals(391, state.right.expandedTopPx)
    }

    @Test
    fun `climate page status maps expanded and transition to occupied`() {
        assertEquals(
            IcarClimatePageOccupancy.OCCUPIED,
            wallpaperState(IcarDisplayStateMonitor.CLIMATE_PAGE_EXPANDED).climatePageOccupancy
        )
        assertEquals(
            IcarClimatePageOccupancy.OCCUPIED,
            wallpaperState(IcarDisplayStateMonitor.CLIMATE_PAGE_EXPANDING).climatePageOccupancy
        )
        assertEquals(
            IcarClimatePageOccupancy.CLEAR,
            wallpaperState(IcarDisplayStateMonitor.CLIMATE_PAGE_COLLAPSED).climatePageOccupancy
        )
        assertEquals(
            IcarClimatePageOccupancy.UNKNOWN,
            wallpaperState(IcarDisplayStateMonitor.STATE_UNKNOWN).climatePageOccupancy
        )
    }

    @Test
    fun `climate page has priority and hides every lyric surface`() {
        val desktop = presentation(
            displayState = wallpaperState(IcarDisplayStateMonitor.CLIMATE_PAGE_EXPANDED),
            dockState = IcarDockPanelState.COLLAPSED
        )
        val topbar = presentation(
            displayState = wallpaperState(
                climateStatus = IcarDisplayStateMonitor.CLIMATE_PAGE_EXPANDING,
                windowMode = IcarDisplayStateMonitor.WINDOW_MODE_STANDARD_WINDOW
            ),
            dockState = IcarDockPanelState.expanded(586)
        )

        assertEquals(LyricsOverlayVisibility.HIDDEN, desktop.visibility)
        assertEquals(LyricsOverlayVisibility.HIDDEN, topbar.visibility)
        assertEquals(LyricsSurfaceMode.TOPBAR, topbar.surfaceMode)
    }

    @Test
    fun `unknown climate status fails closed to hidden`() {
        val state = presentation(
            displayState = wallpaperState(IcarDisplayStateMonitor.STATE_UNKNOWN),
            dockState = IcarDockPanelState.COLLAPSED
        )

        assertEquals(LyricsOverlayVisibility.HIDDEN, state.visibility)
    }

    @Test
    fun `full display lease hides lyrics without changing ordinary window policy`() {
        val fullscreen = presentation(
            displayState = wallpaperState(IcarDisplayStateMonitor.CLIMATE_PAGE_COLLAPSED),
            dockState = IcarDockPanelState.COLLAPSED,
            externalSurfaceOccupancy = IcarExternalSurfaceOccupancy(fullDisplayOccupied = true)
        )
        val released = presentation(
            displayState = wallpaperState(IcarDisplayStateMonitor.CLIMATE_PAGE_COLLAPSED),
            dockState = IcarDockPanelState.COLLAPSED
        )

        assertEquals(LyricsOverlayVisibility.HIDDEN, fullscreen.visibility)
        assertEquals(LyricsSurfaceMode.DESKTOP, fullscreen.surfaceMode)
        assertEquals(LyricsOverlayVisibility.VISIBLE, released.visibility)
        assertEquals(LyricsSurfaceMode.DESKTOP, released.surfaceMode)
    }

    @Test
    fun `expanded right Dock clips only wallpaper lyrics`() {
        val desktop = presentation(
            displayState = wallpaperState(IcarDisplayStateMonitor.CLIMATE_PAGE_COLLAPSED),
            dockState = IcarDockPanelState.expanded(586)
        )
        val topbar = presentation(
            displayState = wallpaperState(
                climateStatus = IcarDisplayStateMonitor.CLIMATE_PAGE_COLLAPSED,
                windowMode = IcarDisplayStateMonitor.WINDOW_MODE_STANDARD_WINDOW
            ),
            dockState = IcarDockPanelState.expanded(586)
        )

        assertEquals(LyricsSurfaceMode.DESKTOP, desktop.surfaceMode)
        assertEquals(LyricsOverlayVisibility.VISIBLE, desktop.visibility)
        assertEquals(586, desktop.desktopBottomLimitPx)
        assertEquals(LyricsSurfaceMode.TOPBAR, topbar.surfaceMode)
        assertEquals(LyricsOverlayVisibility.VISIBLE, topbar.visibility)
        assertNull(topbar.desktopBottomLimitPx)
    }

    @Test
    fun `wallpaper clips against the Dock under its effective position`() {
        val leftClipped = presentation(
            displayState = wallpaperState(IcarDisplayStateMonitor.CLIMATE_PAGE_COLLAPSED),
            dockState = IcarDockPanelState.expanded(700),
            wallpaperPosition = WallpaperLyricsPosition.LEFT,
            leftDockState = IcarDockPanelState.expanded(586)
        )
        val leftIgnoresRight = presentation(
            displayState = wallpaperState(IcarDisplayStateMonitor.CLIMATE_PAGE_COLLAPSED),
            dockState = IcarDockPanelState.expanded(586),
            wallpaperPosition = WallpaperLyricsPosition.LEFT,
            leftDockState = IcarDockPanelState.COLLAPSED
        )
        val shiftedLeftClippedByCenter = presentation(
            displayState = wallpaperState(
                climateStatus = IcarDisplayStateMonitor.CLIMATE_PAGE_COLLAPSED,
                windowMode = IcarDisplayStateMonitor.WINDOW_MODE_ADAS_CARD
            ),
            dockState = IcarDockPanelState.expanded(700),
            wallpaperPosition = WallpaperLyricsPosition.LEFT,
            leftDockState = IcarDockPanelState.expanded(650),
            centerDockState = IcarDockPanelState.expanded(586)
        )
        val shiftedLeftIgnoresSideDocks = presentation(
            displayState = wallpaperState(
                climateStatus = IcarDisplayStateMonitor.CLIMATE_PAGE_COLLAPSED,
                windowMode = IcarDisplayStateMonitor.WINDOW_MODE_ADAS_CARD
            ),
            dockState = IcarDockPanelState.expanded(700),
            wallpaperPosition = WallpaperLyricsPosition.LEFT,
            leftDockState = IcarDockPanelState.expanded(650),
            centerDockState = IcarDockPanelState.COLLAPSED
        )

        assertEquals(586, leftClipped.desktopBottomLimitPx)
        assertNull(leftIgnoresRight.desktopBottomLimitPx)
        assertEquals(586, shiftedLeftClippedByCenter.desktopBottomLimitPx)
        assertNull(shiftedLeftIgnoresSideDocks.desktopBottomLimitPx)
    }

    @Test
    fun `wallpaper positions are mirrored and SR shifts the left position right`() {
        assertEquals(
            30,
            IcarWallpaperPositionPolicy.leftPx(
                screenWidthPx = 1920,
                surfaceWidthPx = 1230,
                edgeInsetPx = 30,
                position = WallpaperLyricsPosition.LEFT,
                srPanelOccupancy = IcarSrPanelOccupancy.CLEAR
            )
        )
        assertEquals(
            660,
            IcarWallpaperPositionPolicy.leftPx(
                screenWidthPx = 1920,
                surfaceWidthPx = 1230,
                edgeInsetPx = 30,
                position = WallpaperLyricsPosition.LEFT,
                srPanelOccupancy = IcarSrPanelOccupancy.OCCUPIED
            )
        )
        assertEquals(
            660,
            IcarWallpaperPositionPolicy.leftPx(
                screenWidthPx = 1920,
                surfaceWidthPx = 1230,
                edgeInsetPx = 30,
                position = WallpaperLyricsPosition.RIGHT,
                srPanelOccupancy = IcarSrPanelOccupancy.CLEAR
            )
        )
    }

    @Test
    fun `SR horizontal shift uses the fixed fast out motion contract`() {
        assertEquals(250L, IcarWallpaperHorizontalMotionSpec.DURATION_MS)
        assertEquals(0.2f, IcarWallpaperHorizontalMotionSpec.CONTROL_X1)
        assertEquals(0.8f, IcarWallpaperHorizontalMotionSpec.CONTROL_Y1)
        assertEquals(0.2f, IcarWallpaperHorizontalMotionSpec.CONTROL_X2)
        assertEquals(1f, IcarWallpaperHorizontalMotionSpec.CONTROL_Y2)
    }

    @Test
    fun `window mode exposes SR occupancy independently from standard windows`() {
        fun srOccupancy(windowMode: Int) = wallpaperState(
            climateStatus = IcarDisplayStateMonitor.CLIMATE_PAGE_COLLAPSED,
            windowMode = windowMode
        ).srPanelOccupancy

        assertEquals(IcarSrPanelOccupancy.CLEAR, srOccupancy(0))
        assertEquals(IcarSrPanelOccupancy.OCCUPIED, srOccupancy(1))
        assertEquals(IcarSrPanelOccupancy.CLEAR, srOccupancy(2))
        assertEquals(IcarSrPanelOccupancy.OCCUPIED, srOccupancy(3))
        assertEquals(IcarSrPanelOccupancy.UNKNOWN, srOccupancy(99))
    }

    @Test
    fun `SR handle movement publishes direction before its terminal position`() {
        val tracker = IcarSrPanelMotionTracker()

        assertEquals(8, IcarSrPanelObservationSpec.motionThresholdPx(1920))
        assertEquals(320L, IcarSrPanelObservationSpec.SETTLE_RECHECK_MS)
        assertEquals(
            IcarSrPanelOccupancy.UNKNOWN,
            tracker.update(screenWidthPx = 1920, handlerLeftPx = -9, handlerRightPx = 51)
        )
        assertEquals(
            IcarSrPanelOccupancy.UNKNOWN,
            tracker.update(screenWidthPx = 1920, handlerLeftPx = -2, handlerRightPx = 58)
        )
        assertEquals(
            IcarSrPanelOccupancy.OCCUPIED,
            tracker.update(screenWidthPx = 1920, handlerLeftPx = 6, handlerRightPx = 66)
        )
        assertEquals(
            IcarSrPanelOccupancy.OCCUPIED,
            tracker.update(screenWidthPx = 1920, handlerLeftPx = 600, handlerRightPx = 660)
        )
        assertEquals(
            IcarSrPanelOccupancy.UNKNOWN,
            tracker.settle(handlerLeftPx = 600, handlerRightPx = 660)
        )
        assertEquals(
            IcarSrPanelOccupancy.CLEAR,
            tracker.update(screenWidthPx = 1920, handlerLeftPx = 580, handlerRightPx = 640)
        )
    }

    @Test
    fun `SR motion hint leads and stable window mode remains the fallback`() {
        assertEquals(
            IcarSrPanelOccupancy.OCCUPIED,
            IcarSrPanelOccupancyPolicy.resolve(
                stableOccupancy = IcarSrPanelOccupancy.CLEAR,
                motionOccupancy = IcarSrPanelOccupancy.OCCUPIED
            )
        )
        assertEquals(
            IcarSrPanelOccupancy.CLEAR,
            IcarSrPanelOccupancyPolicy.resolve(
                stableOccupancy = IcarSrPanelOccupancy.OCCUPIED,
                motionOccupancy = IcarSrPanelOccupancy.CLEAR
            )
        )
        assertEquals(
            IcarSrPanelOccupancy.OCCUPIED,
            IcarSrPanelOccupancyPolicy.resolve(
                stableOccupancy = IcarSrPanelOccupancy.OCCUPIED,
                motionOccupancy = IcarSrPanelOccupancy.UNKNOWN
            )
        )
    }

    @Test
    fun `early SR motion moves left lyrics and switches clipping to center Dock`() {
        val opening = presentation(
            displayState = wallpaperState(IcarDisplayStateMonitor.CLIMATE_PAGE_COLLAPSED),
            dockState = IcarDockPanelState.COLLAPSED,
            wallpaperPosition = WallpaperLyricsPosition.LEFT,
            leftDockState = IcarDockPanelState.expanded(650),
            centerDockState = IcarDockPanelState.expanded(586),
            srPanelMotionOccupancy = IcarSrPanelOccupancy.OCCUPIED
        )
        val closing = presentation(
            displayState = wallpaperState(
                climateStatus = IcarDisplayStateMonitor.CLIMATE_PAGE_COLLAPSED,
                windowMode = IcarDisplayStateMonitor.WINDOW_MODE_ADAS_CARD
            ),
            dockState = IcarDockPanelState.COLLAPSED,
            wallpaperPosition = WallpaperLyricsPosition.LEFT,
            leftDockState = IcarDockPanelState.expanded(650),
            centerDockState = IcarDockPanelState.expanded(586),
            srPanelMotionOccupancy = IcarSrPanelOccupancy.CLEAR
        )

        assertEquals(IcarSrPanelOccupancy.OCCUPIED, opening.srPanelOccupancy)
        assertEquals(586, opening.desktopBottomLimitPx)
        assertEquals(IcarSrPanelOccupancy.CLEAR, closing.srPanelOccupancy)
        assertEquals(650, closing.desktopBottomLimitPx)
    }

    @Test
    fun `wallpaper clip keeps the original bottom unless the right Dock intersects it`() {
        assertEquals(
            900,
            IcarWallpaperClipPolicy.bottomPx(
                defaultBottomPx = 900,
                dockTopPx = null,
                safeGapPx = 16
            )
        )
        assertEquals(
            570,
            IcarWallpaperClipPolicy.bottomPx(
                defaultBottomPx = 900,
                dockTopPx = 586,
                safeGapPx = 16
            )
        )
        assertEquals(
            864,
            IcarWallpaperClipPolicy.bottomPx(
                defaultBottomPx = 900,
                dockTopPx = 880,
                safeGapPx = 16
            )
        )
    }

    @Test
    fun `wallpaper mask follows the clipped visible height`() {
        assertEquals(
            IcarWallpaperClipPolicy.FULL_RATIO_BASIS_POINTS,
            IcarWallpaperClipPolicy.visibleRatioBasisPoints(
                defaultHeightPx = 810,
                visibleHeightPx = 810
            )
        )
        assertEquals(
            5_926,
            IcarWallpaperClipPolicy.visibleRatioBasisPoints(
                defaultHeightPx = 810,
                visibleHeightPx = 480
            )
        )
        assertEquals(
            0,
            IcarWallpaperClipPolicy.visibleRatioBasisPoints(
                defaultHeightPx = 810,
                visibleHeightPx = -1
            )
        )
    }

    @Test
    fun `unknown Dock availability conservatively uses topbar on wallpaper`() {
        val state = presentation(
            displayState = wallpaperState(IcarDisplayStateMonitor.CLIMATE_PAGE_COLLAPSED),
            dockState = IcarDockPanelState.UNKNOWN
        )

        assertEquals(LyricsSurfaceMode.TOPBAR, state.surfaceMode)
        assertEquals(LyricsOverlayVisibility.VISIBLE, state.visibility)
    }

    @Test
    fun `climate closing resolves the current window presentation immediately`() {
        val closing = presentation(
            displayState = wallpaperState(
                climateStatus = IcarDisplayStateMonitor.CLIMATE_PAGE_COLLAPSED,
                windowMode = IcarDisplayStateMonitor.WINDOW_MODE_STANDARD_WINDOW
            ),
            dockState = IcarDockPanelState.COLLAPSED
        )

        assertEquals(LyricsOverlayVisibility.VISIBLE, closing.visibility)
        assertEquals(LyricsSurfaceMode.TOPBAR, closing.surfaceMode)
    }

    @Test
    fun `state store replays current state and suppresses duplicates`() {
        val store = IcarDockStateStore()
        val changes = mutableListOf<IcarDockWindowState>()
        val collapsed = IcarDockWindowState(
            left = IcarDockPanelState.COLLAPSED,
            center = IcarDockPanelState.COLLAPSED,
            right = IcarDockPanelState.COLLAPSED
        )
        val expanded = collapsed.copy(right = IcarDockPanelState.expanded(586))

        store.update(collapsed)
        store.addListener(changes::add)
        store.update(expanded)
        store.update(expanded)

        assertEquals(listOf(collapsed, expanded), changes)
    }

    @Test
    fun `accessibility events ignore text churn but keep structural transitions`() {
        assertEquals(
            false,
            IcarDockAccessibilityEventPolicy.shouldInspect(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
            )
        )
        assertEquals(
            true,
            IcarDockAccessibilityEventPolicy.shouldInspect(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
            )
        )
        assertEquals(
            true,
            IcarDockAccessibilityEventPolicy.shouldInspect(
                AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED
            )
        )
    }

    private fun classify(vararg windows: IcarObservedWindow): IcarDockWindowState =
        IcarDockWindowClassifier.classify(
            screenWidthPx = 1920,
            screenHeightPx = 1080,
            windows = windows.toList()
        )

    private fun presentation(
        displayState: IcarDisplayState,
        dockState: IcarDockPanelState,
        externalSurfaceOccupancy: IcarExternalSurfaceOccupancy = IcarExternalSurfaceOccupancy(),
        wallpaperPosition: WallpaperLyricsPosition = WallpaperLyricsPosition.RIGHT,
        leftDockState: IcarDockPanelState = IcarDockPanelState.COLLAPSED,
        centerDockState: IcarDockPanelState = IcarDockPanelState.COLLAPSED,
        srPanelMotionOccupancy: IcarSrPanelOccupancy = IcarSrPanelOccupancy.UNKNOWN,
    ): IcarLyricsPresentation = IcarLyricsPresentationPolicy.resolve(
        displayState = displayState,
        wallpaperLyricsEnabled = true,
        localSettingsOpen = false,
        externalSurfaceOccupancy = externalSurfaceOccupancy,
        wallpaperPosition = wallpaperPosition,
        srPanelMotionOccupancy = srPanelMotionOccupancy,
        dockState = IcarDockWindowState(
            left = leftDockState,
            center = centerDockState,
            right = dockState
        )
    )

    private fun wallpaperState(
        climateStatus: Int,
        windowMode: Int = IcarDisplayStateMonitor.WINDOW_MODE_NONE
    ): IcarDisplayState = IcarDisplayState(
        launcherState = IcarDisplayStateMonitor.LAUNCHER_STATE_WALLPAPER,
        windowMode = windowMode,
        climatePageStatus = climateStatus
    )

    private fun launcherRoot(): IcarObservedWindow =
        launcherWindow(left = 0, top = 0, right = 1920, bottom = 1080)

    private fun rightDockCollapsed(): IcarObservedWindow =
        launcherWindow(left = 1275, top = 915, right = 1920, bottom = 1080)

    private fun launcherWindow(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): IcarObservedWindow = IcarObservedWindow(
        packageName = IcarDockWindowClassifier.LAUNCHER_PACKAGE,
        leftPx = left,
        topPx = top,
        rightPx = right,
        bottomPx = bottom
    )
}
