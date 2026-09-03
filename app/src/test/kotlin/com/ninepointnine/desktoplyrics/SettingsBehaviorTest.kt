package com.ninepointnine.desktoplyrics

import com.ninepointnine.desktoplyrics.commercial.CommercialAccessDecision
import com.ninepointnine.desktoplyrics.commercial.CommercialAccessDenial
import com.ninepointnine.desktoplyrics.commercial.CommercialAccessRefreshResult
import com.ninepointnine.desktoplyrics.commercial.CommercialFailure
import com.ninepointnine.desktoplyrics.commercial.CommercialTier
import com.ninepointnine.desktoplyrics.commercial.EntitlementState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    fun `commercial cloud entitlement check runs once for a new service lifecycle`() {
        assertTrue(
            LyricsCommercialStartupCheckPolicy.shouldCheck(
                LyricsOverlayService.ACTION_START,
                alreadyStarted = false
            )
        )
        assertTrue(
            LyricsCommercialStartupCheckPolicy.shouldCheck(
                LyricsOverlayService.ACTION_SETTINGS_OPENED,
                alreadyStarted = false
            )
        )
        assertFalse(
            LyricsCommercialStartupCheckPolicy.shouldCheck(
                LyricsOverlayService.ACTION_START,
                alreadyStarted = true
            )
        )
        assertFalse(
            LyricsCommercialStartupCheckPolicy.shouldCheck(
                LyricsOverlayService.ACTION_COMMERCIAL_ACCESS_CHANGED,
                alreadyStarted = false
            )
        )
        assertFalse(
            LyricsCommercialStartupCheckPolicy.shouldCheck(
                LyricsOverlayService.ACTION_COMMERCIAL_ACCESS_REVOKED,
                alreadyStarted = false
            )
        )
        assertFalse(
            LyricsCommercialStartupCheckPolicy.shouldCheck(
                LyricsOverlayService.ACTION_STOP,
                alreadyStarted = false
            )
        )
    }

    @Test
    fun `startup cloud check only overrides local access for authoritative denial`() {
        val localPro = CommercialAccessDecision.Allowed(CommercialTier.PRO, 20_000L)

        assertEquals(
            localPro,
            LyricsCommercialStartupCheckPolicy.reconcile(
                CommercialAccessRefreshResult.Failure(CommercialFailure.NETWORK),
                localPro
            )
        )
        assertEquals(
            CommercialAccessDecision.Denied(CommercialAccessDenial.ENTITLEMENT_REVOKED),
            LyricsCommercialStartupCheckPolicy.reconcile(
                CommercialAccessRefreshResult.Failure(
                    CommercialFailure.ENTITLEMENT_REVOKED
                ),
                localPro
            )
        )
        assertEquals(
            CommercialAccessDecision.Denied(CommercialAccessDenial.LICENSE_EXPIRED),
            LyricsCommercialStartupCheckPolicy.reconcile(
                CommercialAccessRefreshResult.Ready(EntitlementState.Expired),
                localPro
            )
        )
        assertEquals(
            CommercialAccessDecision.Denied(CommercialAccessDenial.DEVICE_MISMATCH),
            LyricsCommercialStartupCheckPolicy.reconcile(
                CommercialAccessRefreshResult.Failure(CommercialFailure.DEVICE_MISMATCH),
                localPro
            )
        )
        assertEquals(
            CommercialAccessDecision.Denied(CommercialAccessDenial.INVALID_LICENSE),
            LyricsCommercialStartupCheckPolicy.reconcile(
                CommercialAccessRefreshResult.Failure(CommercialFailure.INVALID_LICENSE),
                localPro
            )
        )
    }

    @Test
    fun `denied access only waits for cloud check before runtime resources exist`() {
        assertTrue(
            LyricsCommercialStartupCheckPolicy.shouldDeferDeniedAccess(
                waitingForCheck = true,
                runtimeActive = false
            )
        )
        assertFalse(
            LyricsCommercialStartupCheckPolicy.shouldDeferDeniedAccess(
                waitingForCheck = true,
                runtimeActive = true
            )
        )
        assertFalse(
            LyricsCommercialStartupCheckPolicy.shouldDeferDeniedAccess(
                waitingForCheck = false,
                runtimeActive = false
            )
        )
    }

    @Test
    fun `commercial access guard schedules the exact signed access boundary`() {
        val harness = CommercialRuntimeAccessHarness(now = 10_000L)

        harness.guard.authorize(
            CommercialAccessDecision.Allowed(CommercialTier.TRIAL, 15_000L)
        )

        assertEquals(listOf(5_000L), harness.scheduledDelays)
        assertTrue(harness.guard.hasCurrentAccess())
    }

    @Test
    fun `trial lease boundary triggers one online check before the seven day end`() {
        var now = 10_000L
        var leaseChecks = 0
        val scheduled = linkedMapOf<Runnable, Long>()
        val guard = CommercialRuntimeAccessGuard(
            nowEpochMs = { now },
            evaluateAccess = {
                error("trial lease renewal must use the online callback")
            },
            scheduleExpiry = { runnable, delay -> scheduled[runnable] = delay },
            cancelExpiry = { runnable -> scheduled.remove(runnable) },
            onDenied = {},
            onTrialLeaseDue = { leaseChecks += 1 }
        )

        guard.authorize(
            CommercialAccessDecision.Allowed(
                tier = CommercialTier.TRIAL,
                expiresAtEpochMs = 15_000L,
                trialEndsAtEpochMs = 30_000L
            )
        )

        assertEquals(setOf(5_000L, 20_000L), scheduled.values.toSet())
        val leaseRunnable = scheduled.entries.single { it.value == 5_000L }.key
        now = 15_000L
        leaseRunnable.run()
        leaseRunnable.run()

        assertEquals(1, leaseChecks)
        assertTrue(guard.hasCurrentAccess())
        assertEquals(setOf(20_000L), scheduled.values.toSet())
    }

    @Test
    fun `permanent pro does not schedule a trial lease check`() {
        val now = 10_000L
        var leaseChecks = 0
        val scheduled = linkedMapOf<Runnable, Long>()
        val guard = CommercialRuntimeAccessGuard(
            nowEpochMs = { now },
            evaluateAccess = {
                CommercialAccessDecision.Denied(CommercialAccessDenial.LICENSE_EXPIRED)
            },
            scheduleExpiry = { runnable, delay -> scheduled[runnable] = delay },
            cancelExpiry = { runnable -> scheduled.remove(runnable) },
            onDenied = {},
            onTrialLeaseDue = { leaseChecks += 1 }
        )

        guard.authorize(
            CommercialAccessDecision.Allowed(
                tier = CommercialTier.PRO,
                expiresAtEpochMs = null
            )
        )
        assertTrue(scheduled.isEmpty())

        assertEquals(0, leaseChecks)
    }

    @Test
    fun `commercial access guard evaluates the authoritative gate at its boundary`() {
        val harness = CommercialRuntimeAccessHarness(now = 10_000L)
        harness.guard.authorize(
            CommercialAccessDecision.Allowed(CommercialTier.TRIAL, 15_000L)
        )
        harness.nextDecision = CommercialAccessDecision.Allowed(
            CommercialTier.TRIAL,
            25_000L
        )

        harness.now = 15_000L
        harness.fireScheduledExpiry()

        assertEquals(listOf(15_000L), harness.evaluatedAt)
        assertEquals(listOf(5_000L, 10_000L), harness.scheduledDelays)
        assertTrue(harness.guard.hasCurrentAccess())
    }

    @Test
    fun `commercial access denial clears permission before one recovery callback`() {
        val harness = CommercialRuntimeAccessHarness(now = 10_000L)
        harness.guard.authorize(
            CommercialAccessDecision.Allowed(CommercialTier.PRO, null)
        )
        harness.nextDecision = CommercialAccessDecision.Denied(
            CommercialAccessDenial.ENTITLEMENT_REVOKED
        )

        harness.guard.revalidate()
        harness.guard.revalidate()

        assertTrue(harness.accessWasClearedBeforeDenial)
        assertEquals(1, harness.denials.size)
        assertFalse(harness.guard.hasCurrentAccess())
    }

    @Test
    fun `commercial access boundary revalidates and reschedules the prior permission`() {
        val harness = CommercialRuntimeAccessHarness(now = 10_000L)
        harness.guard.authorize(
            CommercialAccessDecision.Allowed(CommercialTier.TRIAL, 15_000L)
        )
        val staleExpiry = requireNotNull(harness.scheduledRunnable)
        harness.now = 12_000L
        harness.nextDecision = CommercialAccessDecision.Allowed(
            CommercialTier.PRO,
            30_000L
        )

        harness.guard.revalidate()
        harness.now = 15_000L
        staleExpiry.run()

        assertEquals(listOf(12_000L), harness.evaluatedAt)
        assertEquals(listOf(5_000L, 18_000L), harness.scheduledDelays)
        assertTrue(harness.scheduledRunnable != null)
        assertTrue(harness.guard.hasCurrentAccess())
    }

    @Test
    fun `commercial access without a final boundary stays active without scheduling`() {
        val harness = CommercialRuntimeAccessHarness(now = 10_000L)

        harness.guard.authorize(
            CommercialAccessDecision.Allowed(CommercialTier.PRO, null)
        )
        harness.now = Long.MAX_VALUE

        assertNull(harness.scheduledRunnable)
        assertTrue(harness.guard.hasCurrentAccess())
        assertTrue(harness.evaluatedAt.isEmpty())
    }

    @Test
    fun `clearing commercial access cancels and neutralizes stale expiry`() {
        val harness = CommercialRuntimeAccessHarness(now = 10_000L)
        harness.guard.authorize(
            CommercialAccessDecision.Allowed(CommercialTier.TRIAL, 15_000L)
        )
        val staleExpiry = requireNotNull(harness.scheduledRunnable)

        harness.guard.clear()
        harness.now = 15_000L
        staleExpiry.run()

        assertNull(harness.scheduledRunnable)
        assertTrue(harness.evaluatedAt.isEmpty())
        assertTrue(harness.denials.isEmpty())
        assertFalse(harness.guard.hasCurrentAccess())
    }

    @Test
    fun `commercial access guard synchronously revalidates an expired access decision`() {
        val harness = CommercialRuntimeAccessHarness(now = 10_000L)
        harness.guard.authorize(
            CommercialAccessDecision.Allowed(CommercialTier.TRIAL, 15_000L)
        )
        harness.now = 15_000L
        harness.nextDecision = CommercialAccessDecision.Denied(
            CommercialAccessDenial.LICENSE_EXPIRED
        )

        assertFalse(harness.guard.hasCurrentAccess())

        assertEquals(listOf(15_000L), harness.evaluatedAt)
        assertEquals(1, harness.denials.size)
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
    fun `wallpaper presentation and boot recovery default to enabled`() {
        assertTrue(LyricsOverlayService.AUTO_START_DEFAULT)
        assertTrue(LyricsOverlayService.WALLPAPER_BLUR_DEFAULT)
        assertTrue(LyricsOverlayService.WALLPAPER_SHADOW_DEFAULT)
        assertNotEquals(
            LyricsOverlayService.PREF_TOPBAR_FONT_SCALE_PERCENT,
            LyricsOverlayService.PREF_WALLPAPER_FONT_SCALE_PERCENT
        )
        assertNotEquals(
            LyricsOverlayService.ACTION_SET_TOPBAR_FONT_SCALE,
            LyricsOverlayService.ACTION_SET_WALLPAPER_FONT_SCALE
        )
    }

    @Test
    fun `settings layout exposes the confirmed categories and display controls`() {
        var appDirectory = File(requireNotNull(System.getProperty("user.dir")))
        while (!File(appDirectory, "src/main").isDirectory) {
            appDirectory = requireNotNull(appDirectory.parentFile)
        }
        val activity = File(appDirectory, "src/main/res/layout/activity_main.xml").readText()
        val display = File(
            appDirectory,
            "src/main/res/layout/content_settings_display.xml"
        ).readText()
        val search = File(
            appDirectory,
            "src/main/res/layout/content_settings_search.xml"
        ).readText()
        val cache = File(
            appDirectory,
            "src/main/res/layout/content_settings_cache.xml"
        ).readText()
        val about = File(
            appDirectory,
            "src/main/res/layout/content_settings_about.xml"
        ).readText()
        val mainActivity = File(
            appDirectory,
            "src/main/kotlin/com/ninepointnine/desktoplyrics/MainActivity.kt"
        ).readText()

        assertTrue(activity.contains("@+id/settings_navigation_cache"))
        assertTrue(activity.contains("@+id/settings_navigation_search"))
        assertTrue(activity.contains("@+id/settings_navigation_about"))
        assertTrue(activity.contains("@layout/content_settings_about"))
        assertTrue(display.contains("@+id/topbar_font_size_small"))
        assertTrue(display.contains("@+id/wallpaper_font_size_small"))
        assertTrue(display.contains("@+id/wallpaper_blur_switch"))
        assertTrue(display.contains("@+id/wallpaper_shadow_switch"))
        assertTrue(display.contains("@+id/wallpaper_spacing_dense"))
        assertTrue(display.contains("@+id/wallpaper_focus_top"))
        assertTrue(search.contains("@+id/search_track_input"))
        assertTrue(search.contains("@+id/search_artist_input"))
        assertTrue(search.contains("@+id/search_album_input"))
        assertTrue(search.contains("@+id/search_action_icon"))
        assertTrue(search.contains("@+id/search_action_label"))
        assertTrue(cache.contains("@+id/cache_remaining_estimate_text"))
        assertFalse(cache.contains("@+id/cache_clear_all_action"))
        assertTrue(about.contains("@+id/about_version_item"))
        assertTrue(about.contains("@+id/about_version_value"))
        assertTrue(about.contains("@string/settings_about_version_label"))
        assertTrue(about.contains("@+id/about_terms_qr"))
        assertTrue(about.contains("@string/settings_about_terms"))
        assertTrue(mainActivity.contains("aboutVersionValue.text = BuildConfig.VERSION_NAME"))
        assertFalse(about.contains("about_terms_url"))
        assertFalse(about.contains("settings_about_terms_scan"))
        assertTrue(activity.contains("@+id/settings_message_dialog"))
        assertTrue(activity.contains("@+id/settings_message_dialog_button"))
        assertTrue(mainActivity.contains("lyricsSettingsRenderer.showMessageDialog("))
        assertFalse(mainActivity.contains("Toast.makeText("))
    }

    @Test
    fun `display preferences remain surface specific in the web overlay`() {
        var appDirectory = File(requireNotNull(System.getProperty("user.dir")))
        while (!File(appDirectory, "src/main").isDirectory) {
            appDirectory = requireNotNull(appDirectory.parentFile)
        }
        val overlay = File(
            appDirectory,
            "src/main/assets/lyrics_overlay.html"
        ).readText()
        val service = File(
            appDirectory,
            "src/main/kotlin/com/ninepointnine/desktoplyrics/LyricsOverlayService.kt"
        ).readText()

        assertTrue(overlay.contains("function setDisplayPreferences(value)"))
        assertTrue(overlay.contains("topbarLyricFontScale"))
        assertTrue(overlay.contains("wallpaperLyricFontScale"))
        assertTrue(overlay.contains("desktopFocusRatio=settings.wallpaperFocus==='top' ? .15 : .48"))
        assertTrue(overlay.contains("desktop-all-shadow"))
        assertTrue(overlay.contains("desktop-all-shadow .line.active"))
        assertTrue(overlay.contains("desktop-blur"))
        assertFalse(service.contains("ACTION_CLEAR_ALL_LYRICS_CACHE"))
        assertFalse(service.contains(".putBoolean(PREF_AUTO_START, true)"))
    }

    @Test
    fun `media recording identity remains native owned across settings and web consumers`() {
        var appDirectory = File(requireNotNull(System.getProperty("user.dir")))
        while (!File(appDirectory, "src/main").isDirectory) {
            appDirectory = requireNotNull(appDirectory.parentFile)
        }
        val overlay = File(
            appDirectory,
            "src/main/assets/lyrics_overlay.html"
        ).readText()
        val service = File(
            appDirectory,
            "src/main/kotlin/com/ninepointnine/desktoplyrics/LyricsOverlayService.kt"
        ).readText()
        val renderer = File(
            appDirectory,
            "src/main/kotlin/com/ninepointnine/desktoplyrics/LyricsSettingsRenderer.kt"
        ).readText()

        assertTrue(service.contains("metadata.description"))
        assertTrue(service.contains(".put(\"recordingGeneration\", recordingGeneration)"))
        assertTrue(service.contains(".put(\"queryRevision\", queryRevision)"))
        assertFalse(service.contains("private fun mediaTitle("))
        assertFalse(overlay.contains("function normalizedKey("))
        assertTrue(overlay.contains("String(recordingGeneration),String(queryRevision)"))
        assertFalse(overlay.contains("track,artist,playback.album"))
        assertTrue(
            overlay.contains(
                "if (playback.state !== 'playing' || !playback.timelineReady ||"
            )
        )
        assertTrue(
            overlay.contains(
                "if (!playback.timelineReady || renderedLyricsKey !== playback.key ||"
            )
        )
        assertTrue(renderer.contains("recordingGeneration == populatedRecordingGeneration"))
        assertFalse(renderer.contains("populatedPlaybackKey"))
    }

    @Test
    fun `commercial recovery releases all lyrics runtime owners before a later rebuild`() {
        var appDirectory = File(requireNotNull(System.getProperty("user.dir")))
        while (!File(appDirectory, "src/main").isDirectory) {
            appDirectory = requireNotNull(appDirectory.parentFile)
        }
        val service = File(
            appDirectory,
            "src/main/kotlin/com/ninepointnine/desktoplyrics/LyricsOverlayService.kt"
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

    private class CommercialRuntimeAccessHarness(
        var now: Long
    ) {
        var nextDecision: CommercialAccessDecision = CommercialAccessDecision.Allowed(
            CommercialTier.TRIAL,
            now + 10_000L
        )
        val evaluatedAt = mutableListOf<Long>()
        val scheduledDelays = mutableListOf<Long>()
        val denials = mutableListOf<CommercialAccessDecision.Denied>()
        var scheduledRunnable: Runnable? = null
        var accessWasClearedBeforeDenial = false
        val guard: CommercialRuntimeAccessGuard

        init {
            lateinit var initializedGuard: CommercialRuntimeAccessGuard
            initializedGuard = CommercialRuntimeAccessGuard(
                nowEpochMs = { now },
                evaluateAccess = { evaluationTime ->
                    evaluatedAt += evaluationTime
                    nextDecision
                },
                scheduleExpiry = { runnable, delayMillis ->
                    scheduledRunnable = runnable
                    scheduledDelays += delayMillis
                },
                cancelExpiry = { runnable ->
                    if (scheduledRunnable === runnable) scheduledRunnable = null
                },
                onDenied = { denial ->
                    accessWasClearedBeforeDenial = !initializedGuard.hasCurrentAccess()
                    denials += denial
                }
            )
            guard = initializedGuard
        }

        fun fireScheduledExpiry() {
            val runnable = requireNotNull(scheduledRunnable)
            scheduledRunnable = null
            runnable.run()
        }
    }
}
