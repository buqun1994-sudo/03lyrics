package com.ninepointnine.desktoplyrics

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSessionTimelineTrackerTest {
    @Test
    fun `initial unknown position keeps the timeline unavailable`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker { now }

        val first = tracker.update(
            trackKey = "track",
            playbackState = PlaybackState.STATE_PLAYING,
            reportedPositionMs = PlaybackState.PLAYBACK_POSITION_UNKNOWN,
            playbackSpeed = 1f,
            publisherPositionTime = 0L,
            durationMs = 10_000L
        )
        assertEquals(0L, first.positionMs)
        assertEquals(false, first.timelineReady)

        now = 1_500L
        val second = tracker.update(
            trackKey = "track",
            playbackState = PlaybackState.STATE_PLAYING,
            reportedPositionMs = 1_500L,
            playbackSpeed = 1f,
            publisherPositionTime = 0L,
            durationMs = 10_000L
        )
        assertEquals(1_500L, second.positionMs)
        assertEquals(true, second.timelineReady)
    }

    @Test
    fun `initial valid publisher timestamp is extrapolated immediately`() {
        val tracker = MediaSessionTimelineTracker { 1_000L }

        assertEquals(
            1_100L,
            tracker.update(
                trackKey = "track",
                playbackState = PlaybackState.STATE_PLAYING,
                reportedPositionMs = 1_000L,
                playbackSpeed = 1f,
                publisherPositionTime = 900L,
                durationMs = 10_000L
            ).positionMs
        )
    }

    @Test
    fun `invalid publisher timestamp extrapolates from the callback time`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker { now }

        assertEquals(
            1_000L,
            tracker.update(
                trackKey = "track",
                playbackState = PlaybackState.STATE_PLAYING,
                reportedPositionMs = 1_000L,
                playbackSpeed = 1f,
                publisherPositionTime = 0L,
                durationMs = 10_000L
            ).positionMs
        )

        now = 1_750L
        assertEquals(
            1_750L,
            tracker.update(
                trackKey = "track",
                playbackState = PlaybackState.STATE_PLAYING,
                reportedPositionMs = 1_000L,
                playbackSpeed = 1f,
                publisherPositionTime = 0L,
                durationMs = 10_000L
            ).positionMs
        )
    }

    @Test
    fun `future publisher timestamp falls back to callback time`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker { now }

        assertEquals(
            1_000L,
            tracker.update(
                trackKey = "track",
                playbackState = PlaybackState.STATE_PLAYING,
                reportedPositionMs = 1_000L,
                playbackSpeed = 1f,
                publisherPositionTime = 1_500L,
                durationMs = 10_000L
            ).positionMs
        )

        now = 1_500L
        assertEquals(
            1_500L,
            tracker.update(
                trackKey = "track",
                playbackState = PlaybackState.STATE_PLAYING,
                reportedPositionMs = 1_000L,
                playbackSpeed = 1f,
                publisherPositionTime = 1_500L,
                durationMs = 10_000L
            ).positionMs
        )
    }

    @Test
    fun `valid publisher timestamp anchors the next reported position`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker { now }
        tracker.update("track", PlaybackState.STATE_PLAYING, 1_000L, 1f, 0L, 10_000L)

        now = 2_000L
        assertEquals(
            2_500L,
            tracker.update(
                trackKey = "track",
                playbackState = PlaybackState.STATE_PLAYING,
                reportedPositionMs = 2_000L,
                playbackSpeed = 1f,
                publisherPositionTime = 1_500L,
                durationMs = 10_000L
            ).positionMs
        )
    }

    @Test
    fun `changed position with unchanged publisher time anchors at callback time`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker { now }
        tracker.update("track", PlaybackState.STATE_PLAYING, 1_000L, 1f, 900L, 10_000L)

        now = 2_000L
        assertEquals(
            2_000L,
            tracker.update(
                trackKey = "track",
                playbackState = PlaybackState.STATE_PLAYING,
                reportedPositionMs = 2_000L,
                playbackSpeed = 1f,
                publisherPositionTime = 900L,
                durationMs = 10_000L
            ).positionMs
        )
    }

    @Test
    fun `unknown position does not erase an established local timeline`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker { now }
        tracker.update("track", PlaybackState.STATE_PLAYING, 1_000L, 1f, 0L, 10_000L)

        now = 1_750L
        assertEquals(
            1_750L,
            tracker.update(
                trackKey = "track",
                playbackState = PlaybackState.STATE_PLAYING,
                reportedPositionMs = PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                playbackSpeed = 1f,
                publisherPositionTime = 0L,
                durationMs = 10_000L
            ).positionMs
        )
    }

    @Test
    fun `unknown position with a changed timestamp preserves the local timeline`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker { now }
        tracker.update("track", PlaybackState.STATE_PLAYING, 1_000L, 1f, 900L, 10_000L)

        now = 1_750L
        assertEquals(
            1_850L,
            tracker.update(
                trackKey = "track",
                playbackState = PlaybackState.STATE_PLAYING,
                reportedPositionMs = PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                playbackSpeed = 1f,
                publisherPositionTime = 1_700L,
                durationMs = 10_000L
            ).positionMs
        )
    }

    @Test
    fun `pause freezes the locally extrapolated position`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker { now }
        tracker.update("track", PlaybackState.STATE_PLAYING, 1_000L, 1f, 0L, 10_000L)

        now = 2_000L
        assertEquals(
            2_000L,
            tracker.update("track", PlaybackState.STATE_PAUSED, 1_000L, 0f, 0L, 10_000L).positionMs
        )

        now = 4_000L
        assertEquals(
            2_000L,
            tracker.update("track", PlaybackState.STATE_PAUSED, 1_000L, 0f, 0L, 10_000L).positionMs
        )
    }

    @Test
    fun `seek and duration changes are reflected without stale carry over`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker { now }
        tracker.update("track", PlaybackState.STATE_PLAYING, 1_000L, 1f, 0L, 2_000L)

        now = 1_500L
        assertEquals(
            500L,
            tracker.update("track", PlaybackState.STATE_PLAYING, 500L, 1f, 0L, 2_000L).positionMs
        )

        now = 3_000L
        assertEquals(
            2_000L,
            tracker.update("track", PlaybackState.STATE_PLAYING, 500L, 1f, 0L, 2_000L).positionMs
        )
    }

    @Test
    fun `a new track resets the previous timeline`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker { now }
        tracker.update("first", PlaybackState.STATE_PLAYING, 5_000L, 1f, 0L, 20_000L)

        now = 2_000L
        assertEquals(
            200L,
            tracker.update("second", PlaybackState.STATE_PLAYING, 200L, 1f, 0L, 20_000L).positionMs
        )
    }
}
