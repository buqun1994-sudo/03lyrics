package com.tcrrry.desktoplyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IcarDisplayStateMonitorTest {

    @Test
    fun `wallpaper uses desktop lyrics regardless of every icon state`() {
        val state = IcarDisplayState(
            launcherState = IcarDisplayStateMonitor.LAUNCHER_STATE_WALLPAPER,
            iconVisibility = IcarTopbarIconSlot.entries.associateWith {
                IcarIconVisibility.VISIBLE
            }
        )

        assertEquals(LyricsSurfaceMode.DESKTOP, state.surfaceMode)
    }

    @Test
    fun `map uses topbar lyrics`() {
        val state = IcarDisplayState(
            launcherState = IcarDisplayStateMonitor.LAUNCHER_STATE_MAP
        )

        assertEquals(LyricsSurfaceMode.TOPBAR, state.surfaceMode)
    }

    @Test
    fun `car settings uses conservative topbar lyrics`() {
        val state = IcarDisplayState(
            launcherState = IcarDisplayStateMonitor.LAUNCHER_STATE_CAR_SETTINGS
        )

        assertEquals(LyricsSurfaceMode.TOPBAR, state.surfaceMode)
    }

    @Test
    fun `unknown launcher state fails closed to topbar lyrics`() {
        val state = IcarDisplayState(
            launcherState = IcarDisplayStateMonitor.STATE_UNKNOWN
        )

        assertEquals(LyricsSurfaceMode.TOPBAR, state.surfaceMode)
    }

    @Test
    fun `settings temporarily use topbar even when launcher remains on wallpaper`() {
        val state = IcarDisplayState(
            launcherState = IcarDisplayStateMonitor.LAUNCHER_STATE_WALLPAPER
        )

        assertEquals(
            LyricsSurfaceMode.TOPBAR,
            IcarLyricsSurfacePolicy.effectiveSurfaceMode(
                displayState = state,
                wallpaperLyricsEnabled = true,
                localSettingsOpen = true,
                desktopSurfaceOccupied = false
            )
        )
    }

    @Test
    fun `ADAS card outside the lyric region keeps desktop lyrics`() {
        val state = IcarDisplayState(
            launcherState = IcarDisplayStateMonitor.LAUNCHER_STATE_WALLPAPER,
            windowMode = IcarDisplayStateMonitor.WINDOW_MODE_ADAS_CARD
        )

        assertEquals(IcarDesktopSurfaceOccupancy.CLEAR, state.desktopSurfaceOccupancy)
        assertEquals(
            LyricsSurfaceMode.DESKTOP,
            IcarLyricsSurfacePolicy.effectiveSurfaceMode(
                displayState = state,
                wallpaperLyricsEnabled = true,
                localSettingsOpen = false,
                desktopSurfaceOccupied = false
            )
        )
    }

    @Test
    fun `standard floating window uses topbar while launcher remains on wallpaper`() {
        val state = IcarDisplayState(
            launcherState = IcarDisplayStateMonitor.LAUNCHER_STATE_WALLPAPER,
            windowMode = IcarDisplayStateMonitor.WINDOW_MODE_STANDARD_WINDOW
        )

        assertEquals(IcarDesktopSurfaceOccupancy.OCCUPIED, state.desktopSurfaceOccupancy)
        assertEquals(
            LyricsSurfaceMode.TOPBAR,
            IcarLyricsSurfacePolicy.effectiveSurfaceMode(
                displayState = state,
                wallpaperLyricsEnabled = true,
                localSettingsOpen = false,
                desktopSurfaceOccupied = false
            )
        )
        assertTrue(
            IcarLyricsSurfacePolicy.hasRenderableGeometry(
                width = state.topbarGeometry().widthPx,
                height = 72
            )
        )
    }

    @Test
    fun `ADAS card plus standard window uses topbar lyrics`() {
        val state = IcarDisplayState(
            launcherState = IcarDisplayStateMonitor.LAUNCHER_STATE_WALLPAPER,
            windowMode = IcarDisplayStateMonitor.WINDOW_MODE_ADAS_CARD_AND_STANDARD_WINDOW
        )

        assertEquals(IcarDesktopSurfaceOccupancy.OCCUPIED, state.desktopSurfaceOccupancy)
        assertEquals(
            LyricsSurfaceMode.TOPBAR,
            IcarLyricsSurfacePolicy.effectiveSurfaceMode(
                displayState = state,
                wallpaperLyricsEnabled = true,
                localSettingsOpen = false,
                desktopSurfaceOccupied = false
            )
        )
    }

    @Test
    fun `external occupancy lease overrides a clear system window state`() {
        val state = IcarDisplayState(
            launcherState = IcarDisplayStateMonitor.LAUNCHER_STATE_WALLPAPER,
            windowMode = IcarDisplayStateMonitor.WINDOW_MODE_ADAS_CARD
        )

        assertEquals(
            LyricsSurfaceMode.TOPBAR,
            IcarLyricsSurfacePolicy.effectiveSurfaceMode(
                displayState = state,
                wallpaperLyricsEnabled = true,
                localSettingsOpen = false,
                desktopSurfaceOccupied = true
            )
        )
        assertEquals(
            LyricsSurfaceMode.DESKTOP,
            IcarLyricsSurfacePolicy.effectiveSurfaceMode(
                displayState = state,
                wallpaperLyricsEnabled = true,
                localSettingsOpen = false,
                desktopSurfaceOccupied = false
            )
        )
    }

    @Test
    fun `unknown window mode fails closed to topbar lyrics`() {
        val state = IcarDisplayState(
            launcherState = IcarDisplayStateMonitor.LAUNCHER_STATE_WALLPAPER,
            windowMode = IcarDisplayStateMonitor.STATE_UNKNOWN
        )

        assertEquals(IcarDesktopSurfaceOccupancy.UNKNOWN, state.desktopSurfaceOccupancy)
        assertEquals(
            LyricsSurfaceMode.TOPBAR,
            IcarLyricsSurfacePolicy.effectiveSurfaceMode(
                displayState = state,
                wallpaperLyricsEnabled = true,
                localSettingsOpen = false,
                desktopSurfaceOccupied = false
            )
        )
    }

    @Test
    fun `only invalid geometry can hide the lyric overlay`() {
        assertTrue(IcarLyricsSurfacePolicy.hasRenderableGeometry(width = 560, height = 72))
        assertEquals(false, IcarLyricsSurfacePolicy.hasRenderableGeometry(width = 0, height = 72))
        assertEquals(false, IcarLyricsSurfacePolicy.hasRenderableGeometry(width = 560, height = 0))
    }

    @Test
    fun `wallpaper preference only enables desktop lyrics on wallpaper`() {
        val wallpaper = IcarDisplayState(
            launcherState = IcarDisplayStateMonitor.LAUNCHER_STATE_WALLPAPER
        )
        val map = IcarDisplayState(
            launcherState = IcarDisplayStateMonitor.LAUNCHER_STATE_MAP
        )

        assertEquals(
            LyricsSurfaceMode.DESKTOP,
            IcarLyricsSurfacePolicy.effectiveSurfaceMode(
                displayState = wallpaper,
                wallpaperLyricsEnabled = true,
                localSettingsOpen = false,
                desktopSurfaceOccupied = false
            )
        )
        assertEquals(
            LyricsSurfaceMode.TOPBAR,
            IcarLyricsSurfacePolicy.effectiveSurfaceMode(
                displayState = wallpaper,
                wallpaperLyricsEnabled = false,
                localSettingsOpen = false,
                desktopSurfaceOccupied = false
            )
        )
        assertEquals(
            LyricsSurfaceMode.TOPBAR,
            IcarLyricsSurfacePolicy.effectiveSurfaceMode(
                displayState = map,
                wallpaperLyricsEnabled = true,
                localSettingsOpen = false,
                desktopSurfaceOccupied = false
            )
        )
    }

    @Test
    fun `no dynamic icons retains the verified baseline geometry`() {
        val state = IcarDisplayState(
            launcherState = IcarDisplayStateMonitor.LAUNCHER_STATE_MAP,
            iconVisibility = IcarTopbarIconSlot.entries.associateWith {
                IcarIconVisibility.HIDDEN
            }
        )

        assertEquals(321, state.topbarGeometry().leftPx)
        assertEquals(560, state.topbarGeometry().widthPx)
    }

    @Test
    fun `wireless charging uses the standard dynamic icon slot`() {
        val state = IcarDisplayState(
            launcherState = IcarDisplayStateMonitor.LAUNCHER_STATE_MAP,
            iconVisibility = IcarTopbarIconSlot.entries.associateWith { slot ->
                if (slot == IcarTopbarIconSlot.WIRELESS_CHARGING) {
                    IcarIconVisibility.VISIBLE
                } else {
                    IcarIconVisibility.HIDDEN
                }
            }
        )

        assertEquals(396, state.topbarGeometry().leftPx)
        assertEquals(485, state.topbarGeometry().widthPx)
        assertEquals(881, state.topbarGeometry().rightPx)
    }

    @Test
    fun `shared SD card DVR indicator uses one standard slot`() {
        val state = IcarDisplayState(
            launcherState = IcarDisplayStateMonitor.LAUNCHER_STATE_MAP,
            iconVisibility = IcarTopbarIconSlot.entries.associateWith { slot ->
                if (slot == IcarTopbarIconSlot.SD_CARD_DVR) {
                    IcarIconVisibility.VISIBLE
                } else {
                    IcarIconVisibility.HIDDEN
                }
            }
        )

        assertEquals(396, state.topbarGeometry().leftPx)
        assertEquals(485, state.topbarGeometry().widthPx)
    }

    @Test
    fun `three confirmed dynamic icons move lyrics after the last icon`() {
        val visibleSlots = setOf(
            IcarTopbarIconSlot.PEDESTRIAN_REMINDER,
            IcarTopbarIconSlot.WIRELESS_CHARGING,
            IcarTopbarIconSlot.SD_CARD_DVR
        )
        val state = IcarDisplayState(
            launcherState = IcarDisplayStateMonitor.LAUNCHER_STATE_MAP,
            iconVisibility = IcarTopbarIconSlot.entries.associateWith { slot ->
                if (slot in visibleSlots) {
                    IcarIconVisibility.VISIBLE
                } else {
                    IcarIconVisibility.HIDDEN
                }
            }
        )

        assertEquals(546, state.topbarGeometry().leftPx)
        assertEquals(335, state.topbarGeometry().widthPx)
        assertEquals(881, state.topbarGeometry().rightPx)
    }

    @Test
    fun `USB storage uses one standard dynamic icon slot`() {
        val state = IcarDisplayState(
            launcherState = IcarDisplayStateMonitor.LAUNCHER_STATE_MAP,
            iconVisibility = IcarTopbarIconSlot.entries.associateWith { slot ->
                if (slot == IcarTopbarIconSlot.USB_STORAGE) {
                    IcarIconVisibility.VISIBLE
                } else {
                    IcarIconVisibility.HIDDEN
                }
            }
        )

        assertEquals(396, state.topbarGeometry().leftPx)
        assertEquals(485, state.topbarGeometry().widthPx)
    }

    @Test
    fun `guardian mode uses one standard dynamic icon slot`() {
        val state = IcarDisplayState(
            launcherState = IcarDisplayStateMonitor.LAUNCHER_STATE_MAP,
            iconVisibility = IcarTopbarIconSlot.entries.associateWith { slot ->
                if (slot == IcarTopbarIconSlot.GUARDIAN_MODE) {
                    IcarIconVisibility.VISIBLE
                } else {
                    IcarIconVisibility.HIDDEN
                }
            }
        )

        assertEquals(396, state.topbarGeometry().leftPx)
        assertEquals(485, state.topbarGeometry().widthPx)
    }

    @Test
    fun `guardian visibility changes geometry but never the lyric surface mode`() {
        val hidden = IcarDisplayState(
            launcherState = IcarDisplayStateMonitor.LAUNCHER_STATE_MAP,
            iconVisibility = mapOf(IcarTopbarIconSlot.GUARDIAN_MODE to IcarIconVisibility.HIDDEN)
        )
        val visible = hidden.copy(
            iconVisibility = mapOf(IcarTopbarIconSlot.GUARDIAN_MODE to IcarIconVisibility.VISIBLE)
        )

        assertEquals(LyricsSurfaceMode.TOPBAR, hidden.surfaceMode)
        assertEquals(LyricsSurfaceMode.TOPBAR, visible.surfaceMode)
        assertEquals(321, hidden.topbarGeometry().leftPx)
        assertEquals(396, visible.topbarGeometry().leftPx)
    }

    @Test
    fun `all five confirmed icons retain the minimum usable lyric width`() {
        val state = IcarDisplayState(
            launcherState = IcarDisplayStateMonitor.LAUNCHER_STATE_MAP,
            iconVisibility = IcarTopbarIconSlot.entries.associateWith {
                IcarIconVisibility.VISIBLE
            }
        )

        assertEquals(696, state.topbarGeometry().leftPx)
        assertEquals(185, state.topbarGeometry().widthPx)
        assertTrue(state.topbarGeometry().canShowLyrics)
    }

    @Test
    fun `unknown dynamic icon does not consume lyric width`() {
        val state = IcarDisplayState(
            launcherState = IcarDisplayStateMonitor.LAUNCHER_STATE_MAP,
            iconVisibility = IcarTopbarIconSlot.entries.associateWith { slot ->
                if (slot == IcarTopbarIconSlot.PEDESTRIAN_REMINDER) {
                    IcarIconVisibility.UNKNOWN
                } else {
                    IcarIconVisibility.HIDDEN
                }
            }
        )

        assertEquals(321, state.topbarGeometry().leftPx)
        assertEquals(560, state.topbarGeometry().widthPx)
    }

    @Test
    fun `unknown icons alone never collapse the topbar`() {
        val state = IcarDisplayState(
            launcherState = IcarDisplayStateMonitor.LAUNCHER_STATE_MAP,
            iconVisibility = IcarTopbarIconSlot.entries.associateWith {
                IcarIconVisibility.UNKNOWN
            }
        )

        assertTrue(state.topbarGeometry().canShowLyrics)
        assertEquals(560, state.topbarGeometry().widthPx)
    }
}
