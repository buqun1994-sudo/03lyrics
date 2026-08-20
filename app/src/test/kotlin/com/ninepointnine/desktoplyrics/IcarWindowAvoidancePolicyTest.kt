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

        assertEquals(IcarRightDockStatus.COLLAPSED, state.status)
        assertNull(state.expandedTopPx)
    }

    @Test
    fun `expanded right Dock reports its real top edge`() {
        val state = classify(
            launcherRoot(),
            rightDockCollapsed(),
            launcherWindow(left = 1275, top = 586, right = 1920, bottom = 1080)
        )

        assertEquals(IcarRightDockStatus.EXPANDED, state.status)
        assertEquals(586, state.expandedTopPx)
    }

    @Test
    fun `left and center Dock expansion do not affect the right Dock`() {
        val state = classify(
            launcherRoot(),
            rightDockCollapsed(),
            launcherWindow(left = 0, top = 586, right = 645, bottom = 1080),
            launcherWindow(left = 645, top = 586, right = 1275, bottom = 1080)
        )

        assertEquals(IcarRightDockStatus.COLLAPSED, state.status)
    }

    @Test
    fun `closing right Dock stays expanded until its additional window disappears`() {
        val state = classify(
            launcherRoot(),
            rightDockCollapsed(),
            launcherWindow(left = 1275, top = 880, right = 1920, bottom = 1080)
        )

        assertEquals(IcarRightDockStatus.EXPANDED, state.status)
        assertEquals(880, state.expandedTopPx)
    }

    @Test
    fun `single large right Dock window is still recognized`() {
        val state = classify(
            launcherRoot(),
            launcherWindow(left = 1275, top = 586, right = 1920, bottom = 1080)
        )

        assertEquals(IcarRightDockStatus.EXPANDED, state.status)
        assertEquals(586, state.expandedTopPx)
    }

    @Test
    fun `snapshot without verified Launcher root is unknown`() {
        val state = classify(rightDockCollapsed())

        assertEquals(IcarRightDockStatus.UNKNOWN, state.status)
    }

    @Test
    fun `right Dock classification scales with the display`() {
        val windows = listOf(
            launcherWindow(left = 0, top = 0, right = 1280, bottom = 720),
            launcherWindow(left = 850, top = 610, right = 1280, bottom = 720),
            launcherWindow(left = 850, top = 391, right = 1280, bottom = 720)
        )

        val state = IcarRightDockWindowClassifier.classify(
            screenWidthPx = 1280,
            screenHeightPx = 720,
            windows = windows
        )

        assertEquals(IcarRightDockStatus.EXPANDED, state.status)
        assertEquals(391, state.expandedTopPx)
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
            dockState = IcarRightDockWindowState.COLLAPSED
        )
        val topbar = presentation(
            displayState = wallpaperState(
                climateStatus = IcarDisplayStateMonitor.CLIMATE_PAGE_EXPANDING,
                windowMode = IcarDisplayStateMonitor.WINDOW_MODE_STANDARD_WINDOW
            ),
            dockState = IcarRightDockWindowState.expanded(586)
        )

        assertEquals(LyricsOverlayVisibility.HIDDEN, desktop.visibility)
        assertEquals(LyricsOverlayVisibility.HIDDEN, topbar.visibility)
        assertEquals(LyricsSurfaceMode.TOPBAR, topbar.surfaceMode)
    }

    @Test
    fun `unknown climate status fails closed to hidden`() {
        val state = presentation(
            displayState = wallpaperState(IcarDisplayStateMonitor.STATE_UNKNOWN),
            dockState = IcarRightDockWindowState.COLLAPSED
        )

        assertEquals(LyricsOverlayVisibility.HIDDEN, state.visibility)
    }

    @Test
    fun `full display lease hides lyrics without changing ordinary window policy`() {
        val fullscreen = presentation(
            displayState = wallpaperState(IcarDisplayStateMonitor.CLIMATE_PAGE_COLLAPSED),
            dockState = IcarRightDockWindowState.COLLAPSED,
            externalSurfaceOccupancy = IcarExternalSurfaceOccupancy(fullDisplayOccupied = true)
        )
        val released = presentation(
            displayState = wallpaperState(IcarDisplayStateMonitor.CLIMATE_PAGE_COLLAPSED),
            dockState = IcarRightDockWindowState.COLLAPSED
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
            dockState = IcarRightDockWindowState.expanded(586)
        )
        val topbar = presentation(
            displayState = wallpaperState(
                climateStatus = IcarDisplayStateMonitor.CLIMATE_PAGE_COLLAPSED,
                windowMode = IcarDisplayStateMonitor.WINDOW_MODE_STANDARD_WINDOW
            ),
            dockState = IcarRightDockWindowState.expanded(586)
        )

        assertEquals(LyricsSurfaceMode.DESKTOP, desktop.surfaceMode)
        assertEquals(LyricsOverlayVisibility.VISIBLE, desktop.visibility)
        assertEquals(586, desktop.desktopBottomLimitPx)
        assertEquals(LyricsSurfaceMode.TOPBAR, topbar.surfaceMode)
        assertEquals(LyricsOverlayVisibility.VISIBLE, topbar.visibility)
        assertNull(topbar.desktopBottomLimitPx)
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
            dockState = IcarRightDockWindowState.UNKNOWN
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
            dockState = IcarRightDockWindowState.COLLAPSED
        )

        assertEquals(LyricsOverlayVisibility.VISIBLE, closing.visibility)
        assertEquals(LyricsSurfaceMode.TOPBAR, closing.surfaceMode)
    }

    @Test
    fun `state store replays current state and suppresses duplicates`() {
        val store = IcarRightDockStateStore()
        val changes = mutableListOf<IcarRightDockWindowState>()
        val expanded = IcarRightDockWindowState.expanded(586)

        store.update(IcarRightDockWindowState.COLLAPSED)
        store.addListener(changes::add)
        store.update(expanded)
        store.update(expanded)

        assertEquals(listOf(IcarRightDockWindowState.COLLAPSED, expanded), changes)
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

    private fun classify(vararg windows: IcarObservedWindow): IcarRightDockWindowState =
        IcarRightDockWindowClassifier.classify(
            screenWidthPx = 1920,
            screenHeightPx = 1080,
            windows = windows.toList()
        )

    private fun presentation(
        displayState: IcarDisplayState,
        dockState: IcarRightDockWindowState,
        externalSurfaceOccupancy: IcarExternalSurfaceOccupancy = IcarExternalSurfaceOccupancy(),
    ): IcarLyricsPresentation = IcarLyricsPresentationPolicy.resolve(
        displayState = displayState,
        wallpaperLyricsEnabled = true,
        localSettingsOpen = false,
        externalSurfaceOccupancy = externalSurfaceOccupancy,
        rightDockState = dockState
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
        packageName = IcarRightDockWindowClassifier.LAUNCHER_PACKAGE,
        leftPx = left,
        topPx = top,
        rightPx = right,
        bottomPx = bottom
    )
}
