package com.tcrrry.desktoplyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBehaviorTest {

    @Test
    fun `authorized startup enters the single lyrics runtime`() {
        val decision = LyricsStartupPolicy.decide(
            action = LyricsOverlayService.ACTION_START,
            overlayAccess = true,
            notificationAccess = true
        )

        assertEquals(LyricsStartupOutcome.RUNNING, decision)
        assertFalse(decision.clearsAutoStart)
    }

    @Test
    fun `missing overlay access enters recovery without clearing auto start`() {
        val decision = LyricsStartupPolicy.decide(
            action = LyricsOverlayService.ACTION_START,
            overlayAccess = false,
            notificationAccess = true
        )

        assertEquals(LyricsStartupOutcome.RECOVERY, decision)
        assertFalse(decision.clearsAutoStart)
    }

    @Test
    fun `missing notification access enters recovery without clearing auto start`() {
        val decision = LyricsStartupPolicy.decide(
            action = LyricsOverlayService.ACTION_START,
            overlayAccess = true,
            notificationAccess = false
        )

        assertEquals(LyricsStartupOutcome.RECOVERY, decision)
        assertFalse(decision.clearsAutoStart)
    }

    @Test
    fun `user stop clears auto start even when authorizations are missing`() {
        val decision = LyricsStartupPolicy.decide(
            action = LyricsOverlayService.ACTION_STOP,
            overlayAccess = false,
            notificationAccess = false
        )

        assertEquals(LyricsStartupOutcome.USER_STOPPED, decision)
        assertTrue(decision.clearsAutoStart)
    }

    @Test
    fun `iCAR switch geometry matches the bound MBSwitch resources`() {
        assertEquals(64f, IcarSwitchGeometry.WIDTH_PX)
        assertEquals(36f, IcarSwitchGeometry.HEIGHT_PX)
        assertEquals(30f, IcarSwitchGeometry.THUMB_OUTER_DIAMETER_PX)
        assertEquals(8f, IcarSwitchGeometry.THUMB_TRANSPARENT_STROKE_PX)
        assertEquals(22f, IcarSwitchGeometry.THUMB_CORE_RADIUS_PX * 2f)
        assertEquals(15f, IcarSwitchGeometry.thumbCenterXPx(false))
        assertEquals(49f, IcarSwitchGeometry.thumbCenterXPx(true))
    }

    @Test
    fun `runtime restart is distinct from user stop`() {
        assertNotEquals(LyricsOverlayService.ACTION_STOP, LyricsOverlayService.ACTION_RESTART)
    }

    @Test
    fun `lyrics translation is enabled by default`() {
        assertTrue(LyricsOverlayService.LYRICS_TRANSLATION_DEFAULT)
    }

    @Test
    fun `translated topbar preserves one and two original line semantics`() {
        assertEquals(72, LyricsTopbarHeightPolicy.requiredHeightDp(1, 100))
        assertEquals(89, LyricsTopbarHeightPolicy.requiredHeightDp(2, 100))
        assertTrue(
            LyricsTopbarHeightPolicy.requiredHeightDp(2, 108) >
                LyricsTopbarHeightPolicy.requiredHeightDp(2, 100)
        )
    }
}
