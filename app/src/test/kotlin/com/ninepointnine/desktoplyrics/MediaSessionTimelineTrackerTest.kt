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

    @Test
    fun `a new track does not trust the previous track position`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker { now }
        tracker.update("first", PlaybackState.STATE_PLAYING, 5_000L, 1f, 0L, 20_000L)

        now = 2_000L
        val firstFrame = tracker.update(
            "second",
            PlaybackState.STATE_PLAYING,
            18_000L,
            1f,
            0L,
            20_000L
        )
        assertEquals(0L, firstFrame.positionMs)
        assertEquals(false, firstFrame.timelineReady)

        now = 2_500L
        val progressed = tracker.update(
            "second",
            PlaybackState.STATE_PLAYING,
            18_400L,
            1f,
            0L,
            20_000L
        )
        assertEquals(true, progressed.timelineReady)
        assertEquals(18_400L, progressed.positionMs)
    }

    @Test
    fun `restored position remains anchored while publisher position is unknown`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker { now }
        tracker.update("track", PlaybackState.STATE_PLAYING, 4_000L, 1f, 0L, 20_000L)

        now = 2_000L
        assertEquals(9_000L, tracker.restorePosition("track", 9_000L, 20_000L)?.positionMs)

        now = 2_500L
        val unknown = tracker.update(
            "track",
            PlaybackState.STATE_PLAYING,
            PlaybackState.PLAYBACK_POSITION_UNKNOWN,
            1f,
            0L,
            20_000L
        )
        assertEquals(9_500L, unknown.positionMs)
        assertEquals(true, unknown.timelineReady)

        now = 2_600L
        assertEquals(
            9_600L,
            tracker.update("track", PlaybackState.STATE_PLAYING, 9_600L, 1f, 0L, 20_000L).positionMs
        )
    }

    @Test
    fun `controller replacement ignores one stale position then accepts the next real position`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker { now }
        tracker.update("track", PlaybackState.STATE_PLAYING, 1_000L, 1f, 0L, 20_000L)

        now = 3_000L
        tracker.deferNextReportedPosition()
        val stale = tracker.update(
            "track",
            PlaybackState.STATE_PLAYING,
            0L,
            1f,
            0L,
            20_000L
        )
        assertEquals(3_000L, stale.positionMs)
        assertEquals(true, stale.timelineReady)

        now = 3_500L
        val real = tracker.update(
            "track",
            PlaybackState.STATE_PLAYING,
            3_500L,
            1f,
            0L,
            20_000L
        )
        assertEquals(3_500L, real.positionMs)
    }

    @Test
    fun `controller replacement consumes an unknown first frame without swallowing the next position`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker { now }
        tracker.update("track", PlaybackState.STATE_PLAYING, 1_000L, 1f, 0L, 20_000L)

        now = 3_000L
        tracker.deferNextReportedPosition()
        assertEquals(
            3_000L,
            tracker.update(
                "track",
                PlaybackState.STATE_PLAYING,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                1f,
                0L,
                20_000L
            ).positionMs
        )

        now = 3_500L
        assertEquals(
            3_500L,
            tracker.update("track", PlaybackState.STATE_PLAYING, 3_500L, 1f, 0L, 20_000L).positionMs
        )
    }

    @Test
    fun `checkpoint policy accepts same recording within duration tolerance`() {
        val checkpoint = MediaPlaybackCheckpoint(
            sourceId = "com.tencent.wecarflow",
            track = "Song",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000L,
            positionMs = 32_000L,
            savedAtEpochMs = 1_000_000L
        )

        assertEquals(
            true,
            MediaPlaybackCheckpointPolicy.matches(
                checkpoint,
                sourceId = "com.tencent.wecarflow",
                track = " song ",
                artist = "Artist",
                album = "Album",
                durationMs = 181_500L,
                nowEpochMs = 1_000_000L + 60_000L
            )
        )
        assertEquals(
            false,
            MediaPlaybackCheckpointPolicy.matches(
                checkpoint,
                sourceId = "com.tencent.wecarflow",
                track = "Other",
                artist = "Artist",
                album = "Album",
                durationMs = 180_000L,
                nowEpochMs = 1_000_000L + 60_000L
            )
        )
    }

    @Test
    fun `checkpoint policy rejects stale and incompatible checkpoints`() {
        val checkpoint = MediaPlaybackCheckpoint(
            sourceId = "source",
            track = "Song",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000L,
            positionMs = 32_000L,
            savedAtEpochMs = 1_000_000L
        )
        assertEquals(
            false,
            MediaPlaybackCheckpointPolicy.isValid(
                checkpoint,
                1_000_000L + MediaPlaybackCheckpointPolicy.MAX_AGE_MS + 1L
            )
        )
        assertEquals(
            false,
            MediaPlaybackCheckpointPolicy.matches(
                checkpoint,
                sourceId = "source",
                track = "Song",
                artist = "Artist",
                album = "Album",
                durationMs = 184_001L,
                nowEpochMs = 1_000_000L
            )
        )
    }
}
