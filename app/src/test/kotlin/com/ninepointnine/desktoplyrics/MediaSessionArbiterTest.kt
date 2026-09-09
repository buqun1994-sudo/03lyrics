package com.ninepointnine.desktoplyrics

import android.media.AudioAttributes
import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSessionArbiterTest {
    @Test
    fun `preferred source must publish a resumable state before cold selection`() {
        listOf(PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR, null).forEach { state ->
            val arbiter = MediaSessionArbiter()
            arbiter.restorePreferredSource("preferred-source")
            val initializing = candidate("preferred", PlaybackState.STATE_NONE, 0L, 100L)
                .copy(sourceId = "preferred-source", playbackState = state)
            listOf(100L, 2_000L, 5_000L, 10_000L).forEach { now ->
                val waiting = arbiter.evaluate(listOf(initializing), now, OWN_PACKAGE)
                assertEquals(MediaSessionArbitrationAction.KEEP_CURRENT, waiting.action)
                assertEquals(null, waiting.sessionId)
            }
            val playing = initializing.copy(playbackState = PlaybackState.STATE_PLAYING)
            val ready = arbiter.evaluate(listOf(playing), 10_500L, OWN_PACKAGE)
            assertEquals(MediaSessionArbitrationAction.SELECT, ready.action)
            assertEquals("preferred", ready.sessionId)
        }
    }

    @Test
    fun `cleared stopped source is not selected again through its saved preference`() {
        val arbiter = runningArbiter()
        val playing = candidate("source", PlaybackState.STATE_PLAYING, 1_000L, 100L)
        arbiter.evaluate(listOf(playing), 100L, OWN_PACKAGE)
        val stopped = playing.copy(playbackState = PlaybackState.STATE_STOPPED)
        assertEquals(MediaSessionArbitrationAction.CLEAR, arbiter.evaluate(listOf(stopped), 200L, OWN_PACKAGE).action)
        val repeated = arbiter.evaluate(listOf(stopped), 300L, OWN_PACKAGE)
        assertEquals(MediaSessionArbitrationAction.KEEP_CURRENT, repeated.action)
        assertEquals(null, repeated.sessionId)
    }

    @Test
    fun `incumbent playing source is not replaced by a stale challenger`() {
        val arbiter = runningArbiter()
        val aqtPlaying = candidate("aqt", PlaybackState.STATE_PLAYING, 1_000L, 100L)
        val bluetoothPaused = candidate("bluetooth", PlaybackState.STATE_PAUSED, 64_000L, 10L)

        assertEquals(
            MediaSessionArbitrationAction.SELECT,
            arbiter.evaluate(listOf(aqtPlaying, bluetoothPaused), 0L, OWN_PACKAGE).action
        )

        val staleBluetoothPlaying = bluetoothPaused.copy(playbackState = PlaybackState.STATE_PLAYING)
        val first = arbiter.evaluate(
            listOf(aqtPlaying.copy(reportedPositionMs = 1_500L, positionUpdateTimeMs = 200L), staleBluetoothPlaying),
            100L,
            OWN_PACKAGE
        )
        assertEquals(MediaSessionArbitrationAction.KEEP_CURRENT, first.action)
        assertEquals("aqt", first.sessionId)

        val second = arbiter.evaluate(
            listOf(aqtPlaying.copy(reportedPositionMs = 1_500L, positionUpdateTimeMs = 200L), staleBluetoothPlaying),
            400L,
            OWN_PACKAGE
        )
        assertEquals(MediaSessionArbitrationAction.KEEP_CURRENT, second.action)
        assertEquals("aqt", second.sessionId)
    }

    @Test
    fun `stale playing incumbent yields to a challenger with confirmed progress`() {
        val arbiter = runningArbiter()
        val bluetooth = candidate("bluetooth", PlaybackState.STATE_PLAYING, 64_000L, 100L)
        assertEquals(
            "bluetooth",
            arbiter.evaluate(listOf(bluetooth), 0L, OWN_PACKAGE).sessionId
        )

        val aqt = candidate("aqt", PlaybackState.STATE_PLAYING, 1_000L, 200L)
        val armed = arbiter.evaluate(listOf(bluetooth, aqt), 100L, OWN_PACKAGE)
        assertEquals(MediaSessionArbitrationAction.KEEP_CURRENT, armed.action)
        assertEquals("bluetooth", armed.sessionId)

        val confirmed = arbiter.evaluate(
            listOf(
                bluetooth,
                aqt.copy(reportedPositionMs = 1_400L, positionUpdateTimeMs = 500L)
            ),
            400L,
            OWN_PACKAGE
        )
        assertEquals(MediaSessionArbitrationAction.SELECT, confirmed.action)
        assertEquals("aqt", confirmed.sessionId)
    }

    @Test
    fun `paused supplemental browser with advancing position can replace a stale active session`() {
        val arbiter = runningArbiter()
        val incumbent = candidate("aqt", PlaybackState.STATE_PLAYING, 1_000L, 100L)
        val cloudMusic = candidate(
            "cloud-music",
            PlaybackState.STATE_PAUSED,
            8_000L,
            90L,
            activeInSystemList = false
        ).copy(sourceId = "com.tencent.wecarflow/com.mychery.cloudmusic.service.CloudMusicService")

        assertEquals(
            MediaSessionArbitrationAction.SELECT,
            arbiter.evaluate(listOf(incumbent, cloudMusic), 0L, OWN_PACKAGE).action
        )

        val armed = arbiter.evaluate(
            listOf(
                incumbent.copy(reportedPositionMs = 1_500L, positionUpdateTimeMs = 200L),
                cloudMusic.copy(reportedPositionMs = 8_300L, positionUpdateTimeMs = 180L)
            ),
            100L,
            OWN_PACKAGE
        )
        assertEquals(MediaSessionArbitrationAction.KEEP_CURRENT, armed.action)
        assertEquals("aqt", armed.sessionId)

        val confirmed = arbiter.evaluate(
            listOf(
                incumbent.copy(reportedPositionMs = 2_000L, positionUpdateTimeMs = 300L),
                cloudMusic.copy(reportedPositionMs = 8_700L, positionUpdateTimeMs = 280L)
            ),
            400L,
            OWN_PACKAGE
        )
        assertEquals(MediaSessionArbitrationAction.SELECT, confirmed.action)
        assertEquals("cloud-music", confirmed.sessionId)
    }

    @Test
    fun `paused incumbent remains selected while it leaves the active list`() {
        val arbiter = runningArbiter()
        val playing = candidate("aqt", PlaybackState.STATE_PLAYING, 1_000L, 100L)
        arbiter.evaluate(listOf(playing), 0L, OWN_PACKAGE)

        val pausedAndUnlisted = playing.copy(
            playbackState = PlaybackState.STATE_PAUSED,
            activeInSystemList = false
        )
        val decision = arbiter.evaluate(listOf(pausedAndUnlisted), 100L, OWN_PACKAGE)

        assertEquals(MediaSessionArbitrationAction.KEEP_CURRENT, decision.action)
        assertEquals("aqt", decision.sessionId)
    }

    @Test
    fun `handoff commits only after challenger progress becomes fresh`() {
        val arbiter = runningArbiter()
        val aqtPaused = candidate(
            "aqt",
            PlaybackState.STATE_PAUSED,
            3_000L,
            300L,
            activeInSystemList = false
        )
        val bluetoothPlaying = candidate("bluetooth", PlaybackState.STATE_PLAYING, 1_000L, 100L)
        arbiter.evaluate(listOf(aqtPaused), 0L, OWN_PACKAGE)
        arbiter.evaluate(listOf(aqtPaused), 1_500L, OWN_PACKAGE)

        val armed = arbiter.evaluate(listOf(aqtPaused, bluetoothPlaying), 1_600L, OWN_PACKAGE)
        assertEquals(MediaSessionArbitrationAction.KEEP_CURRENT, armed.action)
        assertEquals("aqt", armed.sessionId)

        val confirmed = arbiter.evaluate(
            listOf(aqtPaused, bluetoothPlaying.copy(reportedPositionMs = 1_400L, positionUpdateTimeMs = 400L)),
            1_900L,
            OWN_PACKAGE
        )
        assertEquals(MediaSessionArbitrationAction.SELECT, confirmed.action)
        assertEquals("bluetooth", confirmed.sessionId)
    }

    @Test
    fun `cold start does not choose among multiple paused sessions`() {
        val arbiter = MediaSessionArbiter()
        val aqt = candidate("aqt", PlaybackState.STATE_PAUSED, 2_000L, 0L)
        val bluetooth = candidate("bluetooth", PlaybackState.STATE_PAUSED, 4_000L, 0L)

        assertEquals(
            MediaSessionArbitrationAction.KEEP_CURRENT,
            arbiter.evaluate(listOf(aqt, bluetooth), 0L, OWN_PACKAGE).action
        )
        val settled = arbiter.evaluate(listOf(aqt, bluetooth), 1_500L, OWN_PACKAGE)
        assertEquals(MediaSessionArbitrationAction.KEEP_CURRENT, settled.action)
        assertEquals(null, settled.sessionId)
    }

    @Test
    fun `cold start selects the most recently updated paused source`() {
        val arbiter = MediaSessionArbiter()
        val aqt = candidate("aqt", PlaybackState.STATE_PAUSED, 2_000L, 300L)
        val bluetooth = candidate("bluetooth", PlaybackState.STATE_PAUSED, 4_000L, 100L)
        arbiter.evaluate(listOf(aqt, bluetooth), 400L, OWN_PACKAGE)

        val settled = arbiter.evaluate(
            listOf(aqt, bluetooth),
            400L + MediaSessionArbiter.COLD_START_SETTLE_MS,
            OWN_PACKAGE
        )

        assertEquals(MediaSessionArbitrationAction.SELECT, settled.action)
        assertEquals("aqt", settled.sessionId)
    }

    @Test
    fun `cold start restores a preferred paused source before stale playback`() {
        val arbiter = MediaSessionArbiter()
        arbiter.restorePreferredSource("aqt-source")
        val aqt = candidate("aqt", PlaybackState.STATE_PAUSED, 2_000L, 100L)
            .copy(sourceId = "aqt-source", activeInSystemList = false)
        val bluetooth = candidate("bluetooth", PlaybackState.STATE_PLAYING, 64_000L, 10L)
            .copy(sourceId = "bluetooth-source")

        val decision = arbiter.evaluate(listOf(bluetooth, aqt), 100L, OWN_PACKAGE)

        assertEquals(MediaSessionArbitrationAction.SELECT, decision.action)
        assertEquals("aqt", decision.sessionId)
    }

    @Test
    fun `cold start without history waits then selects the most recently updated active source`() {
        val arbiter = MediaSessionArbiter()
        val bluetooth = candidate("bluetooth", PlaybackState.STATE_PLAYING, 64_000L, 1_000L)
        val aqt = candidate("aqt", PlaybackState.STATE_PLAYING, 2_000L, 3_000L)

        val waiting = arbiter.evaluate(listOf(bluetooth, aqt), 3_100L, OWN_PACKAGE)
        assertEquals(MediaSessionArbitrationAction.KEEP_CURRENT, waiting.action)
        assertEquals(MediaSessionArbiter.COLD_START_SETTLE_MS, waiting.recheckAfterMs)

        val selected = arbiter.evaluate(
            listOf(bluetooth, aqt),
            3_100L + MediaSessionArbiter.COLD_START_SETTLE_MS,
            OWN_PACKAGE
        )
        assertEquals(MediaSessionArbitrationAction.SELECT, selected.action)
        assertEquals("aqt", selected.sessionId)
    }

    @Test
    fun `cold start waits for preferred browser source before choosing another player`() {
        val arbiter = MediaSessionArbiter()
        arbiter.restorePreferredSource("aqt-source")
        val bluetooth = candidate("bluetooth", PlaybackState.STATE_PLAYING, 64_000L, 10L)

        val waiting = arbiter.evaluate(listOf(bluetooth), 0L, OWN_PACKAGE)
        assertEquals(MediaSessionArbitrationAction.KEEP_CURRENT, waiting.action)
        assertEquals(null, waiting.sessionId)

        val fallback = arbiter.evaluate(
            listOf(bluetooth.copy(positionUpdateTimeMs = 4_000L)),
            MediaSessionArbiter.PREFERRED_SOURCE_SETTLE_MS,
            OWN_PACKAGE
        )
        assertEquals(MediaSessionArbitrationAction.SELECT, fallback.action)
        assertEquals("bluetooth", fallback.sessionId)
    }

    @Test
    fun `destroyed incumbent can be forgotten and replaced by fresh playback`() {
        val arbiter = runningArbiter()
        val aqt = candidate("aqt", PlaybackState.STATE_PLAYING, 1_000L, 100L)
        val bluetooth = candidate("bluetooth", PlaybackState.STATE_PLAYING, 500L, 50L)
        arbiter.evaluate(listOf(aqt), 0L, OWN_PACKAGE)
        arbiter.forgetSession("aqt")

        val decision = arbiter.evaluate(
            listOf(bluetooth.copy(positionUpdateTimeMs = 200L)),
            100L,
            OWN_PACKAGE
        )
        assertEquals(MediaSessionArbitrationAction.SELECT, decision.action)
        assertEquals("bluetooth", decision.sessionId)
    }

    @Test
    fun `stopped incumbent is cleared instead of being treated as absent`() {
        val arbiter = runningArbiter()
        val aqt = candidate("aqt", PlaybackState.STATE_PLAYING, 1_000L, 100L)
        arbiter.evaluate(listOf(aqt), 0L, OWN_PACKAGE)

        val stopped = arbiter.evaluate(
            listOf(aqt.copy(playbackState = PlaybackState.STATE_STOPPED)),
            100L,
            OWN_PACKAGE
        )

        assertEquals(MediaSessionArbitrationAction.CLEAR, stopped.action)
        assertEquals(null, stopped.sessionId)
    }

    @Test
    fun `unknown incumbent state is retained until a concrete state arrives`() {
        val arbiter = runningArbiter()
        val aqt = candidate("aqt", PlaybackState.STATE_PLAYING, 1_000L, 100L)
        arbiter.evaluate(listOf(aqt), 0L, OWN_PACKAGE)

        val decision = arbiter.evaluate(
            listOf(aqt.copy(playbackState = null)),
            100L,
            OWN_PACKAGE
        )

        assertEquals(MediaSessionArbitrationAction.KEEP_CURRENT, decision.action)
        assertEquals("aqt", decision.sessionId)
    }

    @Test
    fun `arbiter reuses media admission and rejects speech challengers`() {
        val arbiter = runningArbiter()
        val aqtPaused = candidate("aqt", PlaybackState.STATE_PAUSED, 1_000L, 100L)
        arbiter.evaluate(listOf(aqtPaused), 0L, OWN_PACKAGE)
        val speech = candidate("speech", PlaybackState.STATE_PLAYING, 500L, 200L).copy(
            packageName = "com.example.voice",
            audioContentType = AudioAttributes.CONTENT_TYPE_SPEECH
        )

        val decision = arbiter.evaluate(listOf(aqtPaused, speech), 100L, OWN_PACKAGE)

        assertEquals(MediaSessionArbitrationAction.KEEP_CURRENT, decision.action)
        assertEquals("aqt", decision.sessionId)
    }

    private fun candidate(
        sessionId: String,
        playbackState: Int,
        positionMs: Long,
        positionUpdateTimeMs: Long,
        activeInSystemList: Boolean = true
    ) = MediaSessionCandidate(
        index = if (sessionId == "aqt") 0 else 1,
        sessionId = sessionId,
        packageName = if (sessionId == "bluetooth") "com.android.bluetooth" else "com.tencent.wecarflow",
        playbackState = playbackState,
        audioUsage = AudioAttributes.USAGE_MEDIA,
        audioContentType = AudioAttributes.CONTENT_TYPE_UNKNOWN,
        playbackActions = PlaybackState.ACTION_PLAY_PAUSE,
        hasTitle = true,
        activeInSystemList = activeInSystemList,
        reportedPositionMs = positionMs,
        positionUpdateTimeMs = positionUpdateTimeMs
    )

    private fun runningArbiter() = MediaSessionArbiter(
        coldStartSettleMs = 0L,
        preferredSourceSettleMs = 0L
    )

    companion object {
        private const val OWN_PACKAGE = "com.ninepointnine.desktoplyrics"
    }
}
