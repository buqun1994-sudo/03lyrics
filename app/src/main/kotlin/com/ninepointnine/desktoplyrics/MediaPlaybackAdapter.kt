package com.ninepointnine.desktoplyrics

import android.media.session.MediaController
import android.media.session.PlaybackState

/**
 * Adapts the selected public MediaSession into one recording state and one
 * monotonic timeline. Session discovery and arbitration remain outside this
 * class; they submit only the selected controller and public playback data.
 */
internal class MediaPlaybackAdapter(
    private val recordingFacts: MediaRecordingFactsStore = MediaRecordingFactsStore(),
    private val nowElapsedRealtime: () -> Long
) {
    private val recordingTracker = MediaRecordingStateTracker()
    private val timelineTracker = MediaSessionTimelineTracker(nowElapsedRealtime)
    private var avrcpPlaybackState: Int? = null

    var currentRecordingState: MediaRecordingState? = null
        private set
    var publishedDurationMs: Long = 0L
        private set

    fun updateRecording(
        sourceIdentity: String?,
        incoming: MediaRecordingMetadata?
    ): MediaRecordingState? {
        publishedDurationMs = incoming?.durationMs ?: 0L
        val complete = if (sourceIdentity != null && incoming != null) {
            recordingFacts.resolve(sourceIdentity, incoming)
        } else {
            incoming
        }
        val next = recordingTracker.update(sourceIdentity, complete)
        if (next == null || next.recordingChanged) avrcpPlaybackState = null
        currentRecordingState = next
        return next
    }

    fun updateTimeline(
        playbackState: Int?,
        reportedPositionMs: Long,
        playbackSpeed: Float,
        publisherPositionTime: Long,
        transport: MediaSessionTransport
    ): MediaPlaybackFrame {
        val state = if (transport == MediaSessionTransport.BLUETOOTH_AVRCP) {
            avrcpPlaybackState ?: playbackState
        } else {
            playbackState
        }
        val recording = currentRecordingState
            ?: return MediaPlaybackFrame(state, MediaSessionTimeline(0L, 0.0, false))
        // A zero from an incomplete snapshot is not evidence of a seek to the start.
        val position = if (reportedPositionMs == 0L &&
            publishedDurationMs < MediaRecordingStateTracker.MINIMUM_QUERY_DURATION_MS
        ) PlaybackState.PLAYBACK_POSITION_UNKNOWN else reportedPositionMs
        return MediaPlaybackFrame(
            playbackState = state,
            timeline = timelineTracker.update(
                trackKey = "recording:${recording.recordingGeneration}",
                playbackState = state,
                reportedPositionMs = position,
                playbackSpeed = playbackSpeed,
                publisherPositionTime = publisherPositionTime,
                durationMs = recording.metadata.durationMs,
                transport = transport
            )
        )
    }

    fun restoreTimeline(
        positionMs: Long,
        transport: MediaSessionTransport
    ): MediaSessionTimeline? {
        val recording = currentRecordingState ?: return null
        return timelineTracker.restorePosition(
            trackKey = "recording:${recording.recordingGeneration}",
            positionMs = positionMs,
            durationMs = recording.metadata.durationMs,
            transport = transport
        )
    }

    fun onAvrcpEvent(event: AvrcpPlaybackEvent): Boolean {
        val recording = currentRecordingState ?: return false
        val duration = recording.metadata.durationMs
        if (event.durationMs != null && duration >= MediaRecordingStateTracker.MINIMUM_QUERY_DURATION_MS &&
            kotlin.math.abs(event.durationMs - duration) > MediaPlaybackCheckpointPolicy.DURATION_TOLERANCE_MS
        ) return false
        event.playbackState?.let { avrcpPlaybackState = it }
        event.positionMs?.let {
            timelineTracker.onAvrcpPosition(it, trackKey = "recording:${recording.recordingGeneration}")
        }
        return true
    }

    fun deferNextReportedPosition() = timelineTracker.deferNextReportedPosition()

    fun resetTimeline() {
        avrcpPlaybackState = null
        timelineTracker.reset()
    }

    fun reset() {
        recordingTracker.clear()
        currentRecordingState = null
        publishedDurationMs = 0L
        resetTimeline()
    }

    companion object {
        fun transportFor(controller: MediaController): MediaSessionTransport =
            if (controller.packageName == PublicMediaBrowserServiceResolver.BLUETOOTH_PACKAGE) {
                MediaSessionTransport.BLUETOOTH_AVRCP
            } else {
                MediaSessionTransport.STANDARD
            }

    }
}

internal data class MediaPlaybackFrame(
    val playbackState: Int?,
    val timeline: MediaSessionTimeline
)

internal data class AvrcpPlaybackEvent(
    val positionMs: Long?,
    val playbackState: Int?,
    val durationMs: Long?
)

/** Decodes the notification and play-status contracts verified on the target firmware. */
internal object AvrcpPlaybackEventDecoder {
    const val POSITION_CHANGED = "android.bluetooth.avrcp-controller.profile.action.PLAYBACK_POS_CHANGED"
    const val POSITION_CHANGED_LEGACY = "android.bluetooth.avrcp-controller.profile.action.PLAYBACK_POS_CHANGEDS"
    const val TRACK_EVENT = "android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT"
    const val PLAY_STATUS = "android.bluetooth.avrcp-controller.profile.action.PLAY_STATUS"
    const val STATUS_CHANGED = "android.bluetooth.avrcp-controller.profile.action.PLAYBACK_STATUS_CHANGED"

    val actions: Set<String> = setOf(
        POSITION_CHANGED, POSITION_CHANGED_LEGACY, TRACK_EVENT, PLAY_STATUS, STATUS_CHANGED
    )

    fun decode(action: String?, extra: (String) -> Any?): AvrcpPlaybackEvent? {
        if (action !in actions) return null
        val playback = extra("android.bluetooth.avrcp-controller.profile.extra.PLAYBACK")
        val position = when (action) {
            PLAY_STATUS, STATUS_CHANGED -> integer(
                extra("android.bluetooth.avrcp-controller.extra.SONG_POS"), MAX_POSITION_MS
            )
            else -> integer(
                extra("android.bluetooth.avrcp-controller.profile.extra.SONG_POS"), MAX_POSITION_MS
            ) ?: integer(
                extra("android.bluetooth.avrcp-controller.extra.PLAY_SONG_POS"), MAX_POSITION_MS
            ) ?: (playback as? PlaybackState)?.position?.takeIf { it in 0L..MAX_POSITION_MS }
        }
        val state = when (action) {
            POSITION_CHANGED, POSITION_CHANGED_LEGACY -> null
            else -> when (playback) {
                is PlaybackState -> playback.state.takeIf { it in 0..PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM }
                else -> integer(playback, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM.toLong())?.toInt()
            } ?: avrcpState(extra("android.bluetooth.avrcp-controller.extra.PLAY_STATUS_ID"))
        }
        val duration = if (action == PLAY_STATUS) {
            integer(extra("android.bluetooth.avrcp-controller.extra.SONG_LEN"), MAX_POSITION_MS)
                ?.takeIf { it >= MediaRecordingStateTracker.MINIMUM_QUERY_DURATION_MS }
        } else {
            null
        }
        if (position == null && state == null) return null
        return AvrcpPlaybackEvent(position, state, duration)
    }

    private fun avrcpState(value: Any?): Int? = when (integer(value, 4L)) {
        0L -> PlaybackState.STATE_STOPPED
        1L -> PlaybackState.STATE_PLAYING
        2L -> PlaybackState.STATE_PAUSED
        3L -> PlaybackState.STATE_FAST_FORWARDING
        4L -> PlaybackState.STATE_REWINDING
        else -> null
    }

    private fun integer(value: Any?, maximum: Long): Long? {
        val number = (value as? Number)?.toDouble()?.takeIf(Double::isFinite) ?: return null
        return number.toLong().takeIf { it in 0L..maximum && it.toDouble() == number }
    }

    private const val MAX_POSITION_MS = 86_400_000L
}
