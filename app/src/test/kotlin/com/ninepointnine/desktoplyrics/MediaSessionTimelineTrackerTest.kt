package com.ninepointnine.desktoplyrics

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSessionTimelineTrackerTest {
    @Test
    fun `bluetooth repeated zero snapshot stays untrusted so checkpoint can restore`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker(nowElapsedRealtime = { now })

        val first = tracker.update(
            "track", PlaybackState.STATE_PLAYING, 0L, 1f, 0L, 240_000L,
            MediaSessionTransport.BLUETOOTH_AVRCP
        )
        assertEquals(false, first.timelineReady)

        now = 1_500L
        val repeated = tracker.update(
            "track", PlaybackState.STATE_PLAYING, 0L, 1f, 0L, 240_000L,
            MediaSessionTransport.BLUETOOTH_AVRCP
        )
        assertEquals(false, repeated.timelineReady)

        now = 2_000L
        assertEquals(
            90_000L,
            tracker.restorePosition("track", 90_000L, 240_000L, MediaSessionTransport.BLUETOOTH_AVRCP)?.positionMs
        )
    }

    @Test
    fun `bluetooth avrcp event confirms resume and seek position`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker(nowElapsedRealtime = { now })
        tracker.update(
            "track", PlaybackState.STATE_PLAYING, 0L, 1f, 0L, 240_000L,
            MediaSessionTransport.BLUETOOTH_AVRCP
        )

        now = 2_000L
        tracker.onAvrcpPosition(123_000L, now)
        val resumed = tracker.update(
            "track", PlaybackState.STATE_PLAYING, 0L, 1f, 0L, 240_000L,
            MediaSessionTransport.BLUETOOTH_AVRCP
        )
        assertEquals(true, resumed.timelineReady)
        assertEquals(123_000L, resumed.positionMs)

        now = 3_000L
        tracker.onAvrcpPosition(35_000L, now)
        assertEquals(
            35_000L,
            tracker.update(
                "track", PlaybackState.STATE_PLAYING, 0L, 1f, 0L, 240_000L,
                MediaSessionTransport.BLUETOOTH_AVRCP
            ).positionMs
        )
    }

    @Test
    fun `bluetooth restored checkpoint ignores stale controller positions until avrcp confirms`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker(nowElapsedRealtime = { now })
        tracker.update(
            "track", PlaybackState.STATE_PLAYING, 0L, 1f, 0L, 240_000L,
            MediaSessionTransport.BLUETOOTH_AVRCP
        )
        assertEquals(
            90_000L,
            tracker.restorePosition("track", 90_000L, 240_000L, MediaSessionTransport.BLUETOOTH_AVRCP)?.positionMs
        )

        now = 2_000L
        val stale = tracker.update(
            "track", PlaybackState.STATE_PLAYING, 1_000L, 1f, 0L, 240_000L,
            MediaSessionTransport.BLUETOOTH_AVRCP
        )
        assertEquals(91_000L, stale.positionMs)

        now = 3_000L
        tracker.onAvrcpPosition(92_000L, now)
        assertEquals(
            92_000L,
            tracker.update(
                "track", PlaybackState.STATE_PLAYING, 1_000L, 1f, 0L, 240_000L,
                MediaSessionTransport.BLUETOOTH_AVRCP
            ).positionMs
        )
    }

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
    fun `repeated zero with fresh publisher timestamps cannot undo an avrcp seek`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker { now }
        tracker.update("track", PlaybackState.STATE_PLAYING, 0L, 1f, now, 240_000L, MediaSessionTransport.BLUETOOTH_AVRCP)
        tracker.onAvrcpPosition(90_000L)
        assertEquals(90_000L, tracker.update("track", PlaybackState.STATE_PLAYING, 0L, 1f, now, 240_000L, MediaSessionTransport.BLUETOOTH_AVRCP).positionMs)
        now += 1_000L
        assertEquals(91_000L, tracker.update("track", PlaybackState.STATE_PLAYING, 0L, 1f, now, 240_000L, MediaSessionTransport.BLUETOOTH_AVRCP).positionMs)
        now += 1_000L
        assertEquals(92_000L, tracker.update("track", PlaybackState.STATE_PLAYING, 0L, 1f, now, 240_000L, MediaSessionTransport.BLUETOOTH_AVRCP).positionMs)
    }

    @Test
    fun `controller positions older than avrcp evidence are ignored but a fresh sample is accepted`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker { now }
        tracker.update("track", PlaybackState.STATE_PLAYING, 0L, 1f, 0L, 240_000L, MediaSessionTransport.BLUETOOTH_AVRCP)
        now = 2_000L
        tracker.onAvrcpPosition(90_000L)
        tracker.update("track", PlaybackState.STATE_PLAYING, 0L, 1f, 0L, 240_000L, MediaSessionTransport.BLUETOOTH_AVRCP)
        now = 3_000L
        assertEquals(91_000L, tracker.update("track", PlaybackState.STATE_PLAYING, 1_000L, 1f, 1_000L, 240_000L, MediaSessionTransport.BLUETOOTH_AVRCP).positionMs)
        now = 4_000L
        assertEquals(45_000L, tracker.update("track", PlaybackState.STATE_PLAYING, 45_000L, 1f, now, 240_000L, MediaSessionTransport.BLUETOOTH_AVRCP).positionMs)
    }

    @Test
    fun `avrcp position received before the first snapshot belongs to the explicit recording`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker { now }
        tracker.onAvrcpPosition(34_000L, trackKey = "first")
        now = 1_035L
        val first = tracker.update("first", PlaybackState.STATE_PLAYING, 0L, 1f, 0L, 240_000L, MediaSessionTransport.BLUETOOTH_AVRCP)
        assertEquals(true, first.timelineReady)
        assertEquals(34_035L, first.positionMs)
    }

    @Test
    fun `old recording avrcp position is not consumed by a new recording`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker { now }
        tracker.update("first", PlaybackState.STATE_PLAYING, 5_000L, 1f, now, 240_000L, MediaSessionTransport.BLUETOOTH_AVRCP)
        tracker.onAvrcpPosition(40_000L)
        now += 35L
        val next = tracker.update("second", PlaybackState.STATE_PLAYING, 40_000L, 1f, 0L, 240_000L, MediaSessionTransport.BLUETOOTH_AVRCP)
        assertEquals(false, next.timelineReady)
        assertEquals(0L, next.positionMs)
    }

    @Test
    fun `future and expired avrcp events cannot create a timeline`() {
        val now = 5_000L
        val tracker = MediaSessionTimelineTracker { now }
        tracker.onAvrcpPosition(30_000L, now + 1L, "track")
        assertEquals(false, tracker.update("track", PlaybackState.STATE_PLAYING, 0L, 1f, 0L, 240_000L, MediaSessionTransport.BLUETOOTH_AVRCP).timelineReady)
        tracker.onAvrcpPosition(30_000L, now - 1_501L, "track")
        assertEquals(false, tracker.update("track", PlaybackState.STATE_PLAYING, 0L, 1f, 0L, 240_000L, MediaSessionTransport.BLUETOOTH_AVRCP).timelineReady)
    }

    @Test
    fun `avrcp pause freezes and a zero seek is valid evidence`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker { now }
        tracker.onAvrcpPosition(34_000L, trackKey = "track")
        tracker.update("track", PlaybackState.STATE_PLAYING, 0L, 1f, 0L, 240_000L, MediaSessionTransport.BLUETOOTH_AVRCP)
        now = 2_000L
        assertEquals(35_000L, tracker.update("track", PlaybackState.STATE_PAUSED, 0L, 0f, 0L, 240_000L, MediaSessionTransport.BLUETOOTH_AVRCP).positionMs)
        now = 8_000L
        assertEquals(35_000L, tracker.update("track", PlaybackState.STATE_PAUSED, 0L, 0f, 0L, 240_000L, MediaSessionTransport.BLUETOOTH_AVRCP).positionMs)
        tracker.onAvrcpPosition(0L)
        val seek = tracker.update("track", PlaybackState.STATE_PLAYING, 0L, 1f, 0L, 240_000L, MediaSessionTransport.BLUETOOTH_AVRCP)
        assertEquals(true, seek.timelineReady)
        assertEquals(0L, seek.positionMs)
    }

    @Test
    fun `unknown positions never advance a provisional clock`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker { now }
        tracker.update("track", PlaybackState.STATE_PLAYING, -1L, 1f, 0L, 240_000L)
        now = 8_000L
        val unknown = tracker.update("track", PlaybackState.STATE_PLAYING, -1L, 1f, 0L, 240_000L)
        assertEquals(false, unknown.timelineReady)
        assertEquals(0L, unknown.positionMs)
        val ready = tracker.update("track", PlaybackState.STATE_PLAYING, 75_000L, 1f, now, 240_000L)
        assertEquals(true, ready.timelineReady)
        assertEquals(75_000L, ready.positionMs)
    }

    @Test
    fun `replacement controller repeated stale frame does not become a seek on the second update`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker { now }
        tracker.update("track", PlaybackState.STATE_PLAYING, 5_000L, 1f, 0L, 240_000L)
        tracker.deferNextReportedPosition()
        now = 2_000L
        tracker.update("track", PlaybackState.STATE_PLAYING, 0L, 1f, 0L, 240_000L)
        now = 3_000L
        assertEquals(7_000L, tracker.update("track", PlaybackState.STATE_PLAYING, 0L, 1f, 0L, 240_000L).positionMs)
    }

    @Test
    fun `positions and extrapolation are clamped without overflow`() {
        var now = 1_000L
        val tracker = MediaSessionTimelineTracker { now }
        assertEquals(200_000L, tracker.update("track", PlaybackState.STATE_PLAYING, Long.MAX_VALUE, Float.MAX_VALUE, 0L, 200_000L).positionMs)
        now = 10_000L
        assertEquals(200_000L, tracker.update("track", PlaybackState.STATE_PLAYING, Long.MAX_VALUE, Float.MAX_VALUE, 0L, 200_000L).positionMs)
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
    fun `checkpoint recovery does not treat missing recording fields as a match`() {
        val checkpoint = MediaPlaybackCheckpoint("source", "Song", "Artist", "Album", 180_000L, 32_000L, 1_000_000L)
        assertEquals(false, MediaPlaybackCheckpointPolicy.matches(checkpoint, "source", "Song", "", "Album", 180_000L, 1_000_001L))
        assertEquals(false, MediaPlaybackCheckpointPolicy.matches(checkpoint, "source", "Song", "Artist", "", 180_000L, 1_000_001L))
        assertEquals(false, MediaPlaybackCheckpointPolicy.matches(checkpoint, "source", "Song", "Artist", "Album", 0L, 1_000_001L))
        assertEquals(false, MediaPlaybackCheckpointPolicy.isValid(checkpoint.copy(durationMs = 0L), 1_000_001L))
    }

    @Test
    fun `checkpoint requires the same public id whenever either side has one`() {
        val checkpoint = MediaPlaybackCheckpoint("source", "Song", "Artist", "Album", 180_000L, 32_000L, 1_000_000L, "item/A")
        listOf("", "item/a", "itemA", "item/B").forEach { mediaId ->
            assertEquals(false, MediaPlaybackCheckpointPolicy.matches(
                checkpoint, "source", "Song", "Artist", "Album", 180_000L, 1_000_001L, mediaId
            ))
        }
        assertEquals(false, MediaPlaybackCheckpointPolicy.matches(
            checkpoint.copy(mediaId = ""), "source", "Song", "Artist", "Album", 180_000L, 1_000_001L, "item/A"
        ))
        assertEquals(true, MediaPlaybackCheckpointPolicy.matches(
            checkpoint, "source", "Song", "Artist", "Album", 180_000L, 1_000_001L, "item/A"
        ))
    }

    @Test
    fun `checkpoint shares recording text normalization but keeps the source boundary`() {
        val checkpoint = MediaPlaybackCheckpoint(
            "source.package", "Song (Live)", "Artist A / Artist B", "Live Album", 180_000L, 32_000L, 1_000_000L
        )
        assertEquals(true, MediaPlaybackCheckpointPolicy.matches(
            checkpoint, "source.package", "Song-Live", "Artist A/Artist B", "LIVE ALBUM", 180_000L, 1_000_001L
        ))
        assertEquals(false, MediaPlaybackCheckpointPolicy.matches(
            checkpoint, "sourcepackage", "Song (Live)", "Artist A / Artist B", "Live Album", 180_000L, 1_000_001L
        ))
        assertEquals(false, MediaPlaybackCheckpointPolicy.matches(
            checkpoint, "source.package", "Song", "Artist A / Artist B", "Live Album", 180_000L, 1_000_001L
        ))
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
