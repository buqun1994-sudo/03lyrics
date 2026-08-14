package com.tcrrry.desktoplyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBehaviorTest {

    @Test
    fun `lyrics auto recovery requires both system authorizations`() {
        assertTrue(SettingsAuthorizationPolicy.canRunLyrics(true, true))
        assertFalse(SettingsAuthorizationPolicy.canRunLyrics(true, false))
        assertFalse(SettingsAuthorizationPolicy.canRunLyrics(false, true))
        assertFalse(SettingsAuthorizationPolicy.canRunLyrics(false, false))
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
}
