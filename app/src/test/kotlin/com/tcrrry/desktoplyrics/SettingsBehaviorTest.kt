package com.tcrrry.desktoplyrics

import com.tcrrry.desktoplyrics.commercial.CommercialAccessDecision
import com.tcrrry.desktoplyrics.commercial.CommercialAccessDenial
import com.tcrrry.desktoplyrics.commercial.CommercialAccessRefreshResult
import com.tcrrry.desktoplyrics.commercial.CommercialFailure
import com.tcrrry.desktoplyrics.commercial.CommercialTier
import com.tcrrry.desktoplyrics.commercial.EntitlementState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
    fun `commercial access is the second startup gate`() {
        assertEquals(
            LyricsStartupOutcome.RUNNING,
            LyricsCommercialGatePolicy.decide(
                LyricsStartupOutcome.RUNNING,
                CommercialAccessDecision.Allowed(CommercialTier.TRIAL, 20_000L)
            )
        )
        val denied = LyricsCommercialGatePolicy.decide(
            LyricsStartupOutcome.RUNNING,
            CommercialAccessDecision.Denied(CommercialAccessDenial.LICENSE_EXPIRED)
        )
        assertEquals(LyricsStartupOutcome.COMMERCIAL_RECOVERY, denied)
        assertFalse(denied.clearsAutoStart)
    }

    @Test
    fun `system recovery wins before commercial evaluation`() {
        val result = LyricsCommercialGatePolicy.decide(
            LyricsStartupOutcome.RECOVERY,
            CommercialAccessDecision.Allowed(CommercialTier.PRO, null)
        )

        assertEquals(LyricsStartupOutcome.RECOVERY, result)
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
    fun `explicit commercial revocation always enters recovery without clearing auto start`() {
        val decision = LyricsStartupPolicy.decide(
            action = LyricsOverlayService.ACTION_COMMERCIAL_ACCESS_REVOKED,
            overlayAccess = true,
            notificationAccess = true
        )

        assertEquals(LyricsStartupOutcome.COMMERCIAL_RECOVERY, decision)
        assertFalse(decision.clearsAutoStart)
    }

    @Test
    fun `commercial cloud refresh runs once for a new service lifecycle`() {
        assertTrue(
            LyricsCommercialStartupRefreshPolicy.shouldRefresh(
                LyricsOverlayService.ACTION_START,
                alreadyStarted = false
            )
        )
        assertTrue(
            LyricsCommercialStartupRefreshPolicy.shouldRefresh(
                LyricsOverlayService.ACTION_SETTINGS_OPENED,
                alreadyStarted = false
            )
        )
        assertFalse(
            LyricsCommercialStartupRefreshPolicy.shouldRefresh(
                LyricsOverlayService.ACTION_START,
                alreadyStarted = true
            )
        )
        assertFalse(
            LyricsCommercialStartupRefreshPolicy.shouldRefresh(
                LyricsOverlayService.ACTION_COMMERCIAL_ACCESS_CHANGED,
                alreadyStarted = false
            )
        )
        assertFalse(
            LyricsCommercialStartupRefreshPolicy.shouldRefresh(
                LyricsOverlayService.ACTION_COMMERCIAL_ACCESS_REVOKED,
                alreadyStarted = false
            )
        )
        assertFalse(
            LyricsCommercialStartupRefreshPolicy.shouldRefresh(
                LyricsOverlayService.ACTION_STOP,
                alreadyStarted = false
            )
        )
    }

    @Test
    fun `startup cloud refresh only overrides local access for authoritative denial`() {
        val localPro = CommercialAccessDecision.Allowed(CommercialTier.PRO, 20_000L)

        assertEquals(
            localPro,
            LyricsCommercialStartupRefreshPolicy.reconcile(
                CommercialAccessRefreshResult.Failure(CommercialFailure.NETWORK),
                localPro
            )
        )
        assertEquals(
            CommercialAccessDecision.Denied(CommercialAccessDenial.ENTITLEMENT_REVOKED),
            LyricsCommercialStartupRefreshPolicy.reconcile(
                CommercialAccessRefreshResult.Failure(
                    CommercialFailure.ENTITLEMENT_REVOKED
                ),
                localPro
            )
        )
        assertEquals(
            CommercialAccessDecision.Denied(CommercialAccessDenial.LICENSE_EXPIRED),
            LyricsCommercialStartupRefreshPolicy.reconcile(
                CommercialAccessRefreshResult.Ready(EntitlementState.Expired),
                localPro
            )
        )
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
    fun `commercial recovery releases all lyrics runtime owners before a later rebuild`() {
        var appDirectory = File(requireNotNull(System.getProperty("user.dir")))
        while (!File(appDirectory, "src/main").isDirectory) {
            appDirectory = requireNotNull(appDirectory.parentFile)
        }
        val service = File(
            appDirectory,
            "src/main/kotlin/com/tcrrry/desktoplyrics/LyricsOverlayService.kt"
        ).readText()

        assertTrue(service.contains("lyricsResolutionCoordinator?.close()"))
        assertTrue(service.contains("lyricsResolutionCoordinator = null"))
        assertTrue(service.contains("lyricsRepository?.close()"))
        assertTrue(service.contains("lyricsRepository = null"))
        assertTrue(service.contains("lyricsCache?.close()"))
        assertTrue(service.contains("lyricsCache = null"))
        assertTrue(service.contains("lyricsScope = null"))
        assertFalse(service.contains("private lateinit var lyricsRepository"))
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
