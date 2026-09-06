package com.ninepointnine.desktoplyrics

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPlaybackAdapterTest {
    @Test
    fun `03t proxy raw lyric updates preserve recording cache identity and millisecond progress`() {
        var now = 1_315_200_475L
        val adapter = MediaPlaybackAdapter { now }
        // Text is anonymized; the fields and timing match the returned 03T report.
        val fields = MediaSessionMetadataFields(
            title = "First current lyric",
            artist = "Artist",
            album = "Album",
            displayTitle = "Song",
            displaySubtitle = "Artist",
            durationMs = 205_403L
        )
        val first = requireNotNull(adapter.updateRecording(
            "com.tencent.wecarflow", MediaSessionMetadataPolicy.normalize(fields)
        ))
        assertEquals("Song", first.metadata.track)
        assertEquals("", first.metadata.mediaId)
        assertEquals(205_403L, first.metadata.durationMs)
        assertEquals(109_385L, standardFrame(adapter, 109_385L, now).timeline.positionMs)
        val firstKey = LyricsCache.key(
            first.metadata.track, first.metadata.artist, first.metadata.album, first.metadata.durationMs
        )

        now += 2_413L
        val next = requireNotNull(adapter.updateRecording(
            "com.tencent.wecarflow",
            MediaSessionMetadataPolicy.normalize(fields.copy(title = "Second current lyric"))
        ))
        val frame = standardFrame(adapter, 111_796L, now)

        assertEquals(first.recordingGeneration, next.recordingGeneration)
        assertEquals(first.queryRevision, next.queryRevision)
        assertEquals(firstKey, LyricsCache.key(
            next.metadata.track, next.metadata.artist, next.metadata.album, next.metadata.durationMs
        ))
        assertEquals(true, frame.timeline.timelineReady)
        assertEquals(111_796L, frame.timeline.positionMs)
    }

    @Test
    fun `new adapter restores verified recording facts and one checkpoint without zero frame rollback`() {
        var now = 10_000L
        var serialized: String? = null
        val complete = MediaRecordingMetadata("Song", "Artist", "Album", 180_321L, "item/42")
        val before = MediaPlaybackAdapter(MediaRecordingFactsStore(write = { serialized = it })) { now }
        val original = requireNotNull(before.updateRecording("source", complete)).metadata
        val observed = standardFrame(before, 81_000L, now).timeline
        val checkpoint = MediaPlaybackCheckpoint(
            "source", original.track, original.artist, original.album, original.durationMs,
            observed.positionMs, 1_000_000L, original.mediaId
        )

        val after = MediaPlaybackAdapter(MediaRecordingFactsStore(read = { serialized })) { now }
        val restarted = requireNotNull(after.updateRecording("source", complete.copy(durationMs = 0L)))
        assertEquals(complete, restarted.metadata)
        assertEquals(0L, after.publishedDurationMs)
        assertEquals(false, standardFrame(after, 0L, now).timeline.timelineReady)
        assertEquals(true, MediaPlaybackCheckpointPolicy.matches(
            checkpoint, "source", restarted.metadata.track, restarted.metadata.artist,
            restarted.metadata.album, restarted.metadata.durationMs, 1_000_001L, restarted.metadata.mediaId
        ))
        assertEquals(81_000L, after.restoreTimeline(checkpoint.positionMs, MediaSessionTransport.STANDARD)?.positionMs)
        repeat(3) { index ->
            now += 1_000L
            after.updateRecording("source", complete.copy(durationMs = 0L))
            assertEquals(82_000L + index * 1_000L, standardFrame(after, 0L, now).timeline.positionMs)
        }
        now += 1_000L
        assertEquals(93_000L, standardFrame(after, 93_000L, now).timeline.positionMs)
        assertEquals(restarted.recordingGeneration, after.currentRecordingState?.recordingGeneration)
        assertEquals(restarted.queryRevision, after.currentRecordingState?.queryRevision)
    }

    @Test
    fun `initial incomplete zero remains unknown until actual position arrives`() {
        var now = 10_000L
        val adapter = MediaPlaybackAdapter { now }
        adapter.updateRecording("source", MediaRecordingMetadata("Song", "Artist", "Album", 0L, "id"))
        assertEquals(false, standardFrame(adapter, 0L, now).timeline.timelineReady)
        now += 5_000L
        assertEquals(false, standardFrame(adapter, 0L, now).timeline.timelineReady)
        assertEquals(0L, adapter.currentRecordingState?.metadata?.durationMs)
        val actual = standardFrame(adapter, 30_000L, now).timeline
        assertEquals(true, actual.timelineReady)
        assertEquals(30_000L, actual.positionMs)
    }

    @Test
    fun `partial metadata does not reset an established clock while a fully reported zero is a real seek`() {
        var now = 10_000L
        val adapter = MediaPlaybackAdapter { now }
        val complete = MediaRecordingMetadata("Song", "Artist", "Album", 180_000L, "id")
        adapter.updateRecording("source", complete)
        assertEquals(30_000L, standardFrame(adapter, 30_000L, now).timeline.positionMs)
        now += 1_000L
        adapter.updateRecording("source", complete.copy(durationMs = 0L))
        assertEquals(31_000L, standardFrame(adapter, 0L, now).timeline.positionMs)
        now += 1_000L
        assertEquals(32_000L, standardFrame(adapter, 0L, now, PlaybackState.STATE_PAUSED).timeline.positionMs)
        now += 10_000L
        assertEquals(32_000L, standardFrame(adapter, 0L, now, PlaybackState.STATE_PAUSED).timeline.positionMs)
        adapter.updateRecording("source", complete)
        val seek = standardFrame(adapter, 0L, now).timeline
        assertEquals(true, seek.timelineReady)
        assertEquals(0L, seek.positionMs)
    }

    @Test
    fun `verified avrcp zero remains real evidence even without published duration`() {
        val adapter = MediaPlaybackAdapter { 10_000L }
        adapter.updateRecording("bluetooth", MediaRecordingMetadata("Song", "Artist", "Album", 0L))
        adapter.onAvrcpEvent(requireNotNull(decode(AvrcpPlaybackEventDecoder.STATUS_CHANGED, POSITION to 0L)))
        val frame = adapter.updateTimeline(
            PlaybackState.STATE_PLAYING, 0L, 1f, 10_000L, MediaSessionTransport.BLUETOOTH_AVRCP
        )
        assertEquals(true, frame.timeline.timelineReady)
        assertEquals(0L, frame.timeline.positionMs)
    }

    @Test
    fun `registered actions include the complete verified firmware contract`() {
        assertEquals(
            setOf(
                "android.bluetooth.avrcp-controller.profile.action.PLAYBACK_POS_CHANGED",
                "android.bluetooth.avrcp-controller.profile.action.PLAYBACK_POS_CHANGEDS",
                "android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT",
                "android.bluetooth.avrcp-controller.profile.action.PLAY_STATUS",
                "android.bluetooth.avrcp-controller.profile.action.PLAYBACK_STATUS_CHANGED"
            ),
            AvrcpPlaybackEventDecoder.actions
        )
    }

    @Test
    fun `both legacy position notifications decode their actual numeric fields`() {
        assertEquals(
            AvrcpPlaybackEvent(148_505L, null, null),
            decode(
                AvrcpPlaybackEventDecoder.POSITION_CHANGED_LEGACY,
                PROFILE_POSITION to 148_505L
            )
        )
        assertEquals(
            AvrcpPlaybackEvent(149_592L, null, null),
            decode(
                AvrcpPlaybackEventDecoder.POSITION_CHANGED,
                LEGACY_POSITION to 149_592
            )
        )
    }

    @Test
    fun `play status snapshot decodes position length and Android state together`() {
        assertEquals(
            AvrcpPlaybackEvent(30_000L, PlaybackState.STATE_PLAYING, 199_986L),
            decode(
                AvrcpPlaybackEventDecoder.PLAY_STATUS,
                POSITION to 30_000L,
                DURATION to 199_986L,
                PLAYBACK to PlaybackState.STATE_PLAYING,
                RAW_STATE to 1.toByte()
            )
        )
    }

    @Test
    fun `status changed can carry position without carrying a state`() {
        assertEquals(
            AvrcpPlaybackEvent(48_500L, null, null),
            decode(AvrcpPlaybackEventDecoder.STATUS_CHANGED, POSITION to 48_500L)
        )
    }

    @Test
    fun `raw AVRCP and Android state integers have distinct meanings`() {
        val expected = listOf(
            PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING
        )
        expected.forEachIndexed { raw, state ->
            assertEquals(
                AvrcpPlaybackEvent(null, state, null),
                decode(AvrcpPlaybackEventDecoder.STATUS_CHANGED, RAW_STATE to raw.toByte())
            )
        }
        assertEquals(
            PlaybackState.STATE_STOPPED,
            decode(AvrcpPlaybackEventDecoder.TRACK_EVENT, PLAYBACK to 1)?.playbackState
        )
        assertEquals(null, decode(AvrcpPlaybackEventDecoder.STATUS_CHANGED, RAW_STATE to 0xff))
    }

    @Test
    fun `track metadata and pure state events never manufacture a zero position`() {
        assertEquals(null, decode(AvrcpPlaybackEventDecoder.TRACK_EVENT, "metadata" to "a track"))
        assertEquals(
            AvrcpPlaybackEvent(null, PlaybackState.STATE_PLAYING, null),
            decode(AvrcpPlaybackEventDecoder.TRACK_EVENT, PLAYBACK to PlaybackState.STATE_PLAYING)
        )
        assertEquals(null, decode(null, POSITION to 30_000L))
        assertEquals(null, decode("unrelated.action", POSITION to 30_000L))
        assertEquals(null, decode(AvrcpPlaybackEventDecoder.PLAY_STATUS, DURATION to 200_000L))
    }

    @Test
    fun `invalid and unknown positions are rejected while explicit zero remains valid`() {
        listOf(null, "1000", -1L, 0xffffffffL, 86_400_001L, Long.MAX_VALUE,
            Double.NaN, Double.POSITIVE_INFINITY, 0.5).forEach { value ->
            assertEquals(null, decode(AvrcpPlaybackEventDecoder.STATUS_CHANGED, POSITION to value))
        }
        assertEquals(
            AvrcpPlaybackEvent(0L, null, null),
            decode(AvrcpPlaybackEventDecoder.STATUS_CHANGED, POSITION to 0L)
        )
        assertEquals(
            AvrcpPlaybackEvent(86_400_000L, null, null),
            decode(AvrcpPlaybackEventDecoder.STATUS_CHANGED, POSITION to 86_400_000L)
        )
        assertEquals(
            AvrcpPlaybackEvent(null, PlaybackState.STATE_PAUSED, null),
            decode(
                AvrcpPlaybackEventDecoder.PLAY_STATUS,
                POSITION to 0xffffffffL,
                DURATION to 0xffffffffL,
                PLAYBACK to PlaybackState.STATE_PAUSED
            )
        )
    }

    @Test
    fun `full snapshot replaces the stale controller position and advances at normal speed`() {
        val playback = Playback()
        playback.select("current")
        playback.frame()
        playback.now += 1_000L
        playback.emit(
            AvrcpPlaybackEventDecoder.PLAY_STATUS,
            POSITION to 30_000L, DURATION to 199_986L, PLAYBACK to PlaybackState.STATE_PLAYING
        )
        val confirmed = playback.frame()
        assertEquals(PlaybackState.STATE_PLAYING, confirmed.playbackState)
        assertEquals(true, confirmed.timeline.timelineReady)
        assertEquals(30_000L, confirmed.timeline.positionMs)
        assertEquals(1.0, confirmed.timeline.speed, 0.0)

        playback.now += 1_000L
        assertEquals(31_000L, playback.frame().timeline.positionMs)
        playback.now += 30_000L
        assertEquals(61_000L, playback.frame().timeline.positionMs)
    }

    @Test
    fun `position notification seek is not rolled back by fresh timestamps on an old controller frame`() {
        val playback = Playback()
        playback.select("current")
        playback.frame()
        playback.emit(AvrcpPlaybackEventDecoder.STATUS_CHANGED, POSITION to 90_000L)
        assertEquals(90_000L, playback.frame().timeline.positionMs)
        playback.now += 500L
        playback.emit(AvrcpPlaybackEventDecoder.STATUS_CHANGED, POSITION to 15_000L)
        assertEquals(15_000L, playback.frame().timeline.positionMs)
        playback.now += 1_000L
        assertEquals(16_000L, playback.frame().timeline.positionMs)
        playback.emit(AvrcpPlaybackEventDecoder.STATUS_CHANGED, POSITION to 0L)
        assertEquals(0L, playback.frame().timeline.positionMs)
    }

    @Test
    fun `state notifications pause and resume the same normalized clock`() {
        val playback = Playback()
        playback.select("current")
        playback.emit(AvrcpPlaybackEventDecoder.STATUS_CHANGED, POSITION to 30_000L)
        playback.frame()
        playback.now += 2_000L
        playback.emit(AvrcpPlaybackEventDecoder.STATUS_CHANGED, RAW_STATE to 2.toByte())
        val paused = playback.frame()
        assertEquals(PlaybackState.STATE_PAUSED, paused.playbackState)
        assertEquals(32_000L, paused.timeline.positionMs)
        assertEquals(0.0, paused.timeline.speed, 0.0)
        playback.now += 10_000L
        assertEquals(32_000L, playback.frame().timeline.positionMs)

        playback.emit(AvrcpPlaybackEventDecoder.STATUS_CHANGED, RAW_STATE to 1.toByte())
        playback.frame()
        playback.now += 1_000L
        assertEquals(33_000L, playback.frame().timeline.positionMs)
    }

    @Test
    fun `pure state notification leaves an unknown timeline unavailable`() {
        val playback = Playback()
        playback.select("current")
        playback.emit(AvrcpPlaybackEventDecoder.TRACK_EVENT, PLAYBACK to PlaybackState.STATE_PLAYING)
        assertEquals(false, playback.frame(position = 0L).timeline.timelineReady)
        playback.now += 10_000L
        assertEquals(false, playback.frame(position = 0L).timeline.timelineReady)
        playback.emit(AvrcpPlaybackEventDecoder.STATUS_CHANGED, POSITION to 0L)
        assertEquals(true, playback.frame(position = 0L).timeline.timelineReady)
    }

    @Test
    fun `duration mismatch rejects an old recording snapshot before state or position changes`() {
        val playback = Playback()
        playback.select("current")
        playback.frame(position = 0L)
        assertEquals(
            false,
            playback.emit(
                AvrcpPlaybackEventDecoder.PLAY_STATUS,
                POSITION to 45_000L, DURATION to 201_987L, PLAYBACK to PlaybackState.STATE_PAUSED
            )
        )
        val unchanged = playback.frame(position = 0L)
        assertEquals(false, unchanged.timeline.timelineReady)
        assertEquals(PlaybackState.STATE_PLAYING, unchanged.playbackState)
        assertEquals(
            true,
            playback.emit(
                AvrcpPlaybackEventDecoder.PLAY_STATUS,
                POSITION to 45_000L, DURATION to 201_986L, PLAYBACK to PlaybackState.STATE_PAUSED
            )
        )
        val accepted = playback.frame(position = 0L)
        assertEquals(true, accepted.timeline.timelineReady)
        assertEquals(45_000L, accepted.timeline.positionMs)
        assertEquals(PlaybackState.STATE_PAUSED, accepted.playbackState)
    }

    @Test
    fun `recording change discards a pending position and the old playback state`() {
        val playback = Playback()
        playback.select("first")
        playback.frame()
        playback.emit(
            AvrcpPlaybackEventDecoder.PLAY_STATUS,
            POSITION to 45_000L, DURATION to 199_986L, PLAYBACK to PlaybackState.STATE_PAUSED
        )
        playback.select("second")
        val next = playback.frame()
        assertEquals(false, next.timeline.timelineReady)
        assertEquals(0L, next.timeline.positionMs)
        assertEquals(PlaybackState.STATE_PLAYING, next.playbackState)
        playback.emit(AvrcpPlaybackEventDecoder.POSITION_CHANGED_LEGACY, PROFILE_POSITION to 500L)
        assertEquals(500L, playback.frame().timeline.positionMs)
    }

    @Test
    fun `events without a selected recording are not held for a future song`() {
        val playback = Playback()
        assertEquals(false, playback.emit(AvrcpPlaybackEventDecoder.STATUS_CHANGED, POSITION to 45_000L))
        playback.select("current")
        assertEquals(false, playback.frame(position = 0L).timeline.timelineReady)
        playback.adapter.reset()
        assertEquals(false, playback.emit(AvrcpPlaybackEventDecoder.STATUS_CHANGED, POSITION to 45_000L))
    }

    private class Playback {
        var now = 10_000L
        val adapter = MediaPlaybackAdapter { now }

        fun select(track: String) {
            adapter.updateRecording(
                "com.android.bluetooth", MediaRecordingMetadata(track, "artist", "album", 199_986L)
            )
        }

        fun emit(action: String, vararg extras: Pair<String, Any?>): Boolean =
            adapter.onAvrcpEvent(requireNotNull(decode(action, *extras)))

        fun frame(position: Long = 154_373L): MediaPlaybackFrame = adapter.updateTimeline(
            playbackState = PlaybackState.STATE_PLAYING,
            reportedPositionMs = position,
            playbackSpeed = 0f,
            publisherPositionTime = now,
            transport = MediaSessionTransport.BLUETOOTH_AVRCP
        )
    }

    private fun standardFrame(
        adapter: MediaPlaybackAdapter,
        positionMs: Long,
        now: Long,
        state: Int = PlaybackState.STATE_PLAYING
    ): MediaPlaybackFrame = adapter.updateTimeline(state, positionMs, 1f, now, MediaSessionTransport.STANDARD)

    companion object {
        private const val PROFILE_POSITION = "android.bluetooth.avrcp-controller.profile.extra.SONG_POS"
        private const val LEGACY_POSITION = "android.bluetooth.avrcp-controller.extra.PLAY_SONG_POS"
        private const val POSITION = "android.bluetooth.avrcp-controller.extra.SONG_POS"
        private const val DURATION = "android.bluetooth.avrcp-controller.extra.SONG_LEN"
        private const val PLAYBACK = "android.bluetooth.avrcp-controller.profile.extra.PLAYBACK"
        private const val RAW_STATE = "android.bluetooth.avrcp-controller.extra.PLAY_STATUS_ID"

        private fun decode(action: String?, vararg extras: Pair<String, Any?>): AvrcpPlaybackEvent? {
            val values = extras.toMap()
            return AvrcpPlaybackEventDecoder.decode(action, values::get)
        }
    }
}
