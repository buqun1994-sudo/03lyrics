package com.ninepointnine.desktoplyrics

import android.media.session.PlaybackState
import kotlin.math.max
import kotlin.math.min

internal data class MediaSessionTimeline(
    val positionMs: Long,
    val speed: Double,
    val timelineReady: Boolean
)

internal data class MediaPlaybackCheckpoint(
    val sourceId: String,
    val track: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val positionMs: Long,
    val savedAtEpochMs: Long,
    val mediaId: String = ""
)

/** Keeps restart recovery tied to one logical source and one recording. */
internal object MediaPlaybackCheckpointPolicy {
    internal const val MAX_AGE_MS = 24L * 60L * 60L * 1_000L
    internal const val DURATION_TOLERANCE_MS = 2_000L

    fun isValid(checkpoint: MediaPlaybackCheckpoint, nowEpochMs: Long): Boolean {
        if (checkpoint.sourceId.isBlank() || checkpoint.track.isBlank()) return false
        if (checkpoint.positionMs < 0L ||
            checkpoint.durationMs < MediaRecordingStateTracker.MINIMUM_QUERY_DURATION_MS
        ) return false
        if (checkpoint.durationMs > 0L &&
            checkpoint.positionMs > checkpoint.durationMs + DURATION_TOLERANCE_MS
        ) return false
        val age = nowEpochMs - checkpoint.savedAtEpochMs
        return age in 0L..MAX_AGE_MS
    }

    fun matches(
        checkpoint: MediaPlaybackCheckpoint,
        sourceId: String,
        track: String,
        artist: String,
        album: String,
        durationMs: Long,
        nowEpochMs: Long,
        mediaId: String = ""
    ): Boolean {
        if (!isValid(checkpoint, nowEpochMs)) return false
        if (!checkpoint.sourceId.trim().equals(sourceId.trim(), ignoreCase = true)) return false
        if (checkpoint.mediaId != mediaId &&
            (checkpoint.mediaId.isNotBlank() || mediaId.isNotBlank())
        ) return false
        if (!sameRequiredText(checkpoint.track, track)) return false
        if (!sameOptionalText(checkpoint.artist, artist)) return false
        if (!sameOptionalText(checkpoint.album, album)) return false
        return durationMs >= MediaRecordingStateTracker.MINIMUM_QUERY_DURATION_MS &&
            kotlin.math.abs(checkpoint.durationMs - durationMs) <= DURATION_TOLERANCE_MS
    }

    private fun sameRequiredText(first: String, second: String): Boolean =
        normalizeText(first) == normalizeText(second) && normalizeText(first).isNotEmpty()

    private fun sameOptionalText(first: String, second: String): Boolean =
        normalizeText(first) == normalizeText(second)
}

/**
 * Normalizes publisher progress into a local monotonic timeline.
 * Some car sessions report a zero or invalid lastPositionUpdateTime; in that
 * case progress is extrapolated from the callback time instead of epoch zero.
 */
internal class MediaSessionTimelineTracker(
    private val nowElapsedRealtime: () -> Long
) {
    private var trackKey = ""
    private var hasObservedTrack = false
    private var basePositionMs = 0L
    private var capturedAtElapsedRealtime = 0L
    private var lastReportedPositionMs = Long.MIN_VALUE
    private var firstUntrustedPositionMs = Long.MIN_VALUE
    private var lastObservedPublisherPositionTime = Long.MIN_VALUE
    private var lastSpeed = 0.0
    private var wasPlaying = false
    private var timelineReady = false
    private var deferNextReportedPosition = false
    private data class AvrcpPosition(val trackKey: String, val positionMs: Long, val capturedAt: Long)
    private var pendingAvrcpPosition: AvrcpPosition? = null
    private var supersededReportedPositionMs: Long? = null
    private var lastAvrcpCapturedAt: Long? = null
    private var ignoreReportedUntilAvrcp = false

    fun onAvrcpPosition(
        positionMs: Long,
        capturedAtRealtime: Long = nowElapsedRealtime(),
        trackKey: String = this.trackKey
    ) {
        if (positionMs < 0L || trackKey.isBlank()) return
        pendingAvrcpPosition = AvrcpPosition(trackKey, positionMs, capturedAtRealtime)
    }

    fun update(
        trackKey: String,
        playbackState: Int?,
        reportedPositionMs: Long,
        playbackSpeed: Float,
        publisherPositionTime: Long,
        durationMs: Long,
        transport: MediaSessionTransport = MediaSessionTransport.STANDARD
    ): MediaSessionTimeline {
        val now = nowElapsedRealtime()
        val hasReportedPosition = reportedPositionMs >= 0L
        val reported = reportedPositionMs.coerceAtLeast(0L)
        val playing = isPlaying(playbackState)
        val speed = effectiveSpeed(playing, playbackState, playbackSpeed)
        val validPublisherTime = publisherPositionTime
            .takeIf { it > 0L && it <= now }
        val avrcpPosition = pendingAvrcpPosition?.takeIf {
            transport == MediaSessionTransport.BLUETOOTH_AVRCP && it.trackKey == trackKey &&
                now - it.capturedAt in 0L..AVRCP_EVENT_MAX_AGE_MS
        }
        pendingAvrcpPosition = null

        if (trackKey != this.trackKey) {
            val firstObservedTrack = !hasObservedTrack
            val trustedInitialPosition = hasReportedPosition &&
                (firstObservedTrack || reported <= INITIAL_POSITION_TRUST_LIMIT_MS) &&
                (transport != MediaSessionTransport.BLUETOOTH_AVRCP || reported > 0L)
            this.trackKey = trackKey
            basePositionMs = clamp(avrcpPosition?.positionMs ?: if (trustedInitialPosition) reported else 0L, durationMs)
            capturedAtElapsedRealtime = avrcpPosition?.capturedAt
                ?: if (trustedInitialPosition) validPublisherTime ?: now else now
            lastReportedPositionMs = if (hasReportedPosition) reported else Long.MIN_VALUE
            firstUntrustedPositionMs = if (hasReportedPosition) reported else Long.MIN_VALUE
            lastObservedPublisherPositionTime = publisherPositionTime
            lastSpeed = speed
            wasPlaying = playing
            timelineReady = trustedInitialPosition || avrcpPosition != null
            hasObservedTrack = true
            deferNextReportedPosition = false
            supersededReportedPositionMs = if (avrcpPosition != null && hasReportedPosition) reported else null
            lastAvrcpCapturedAt = avrcpPosition?.capturedAt
            ignoreReportedUntilAvrcp = false
        } else {
            val currentPosition = positionAt(now)
            val deferPositionFrame = deferNextReportedPosition
            deferNextReportedPosition = false
            if (deferPositionFrame && hasReportedPosition) supersededReportedPositionMs = reported
            if (!deferPositionFrame && hasReportedPosition && reported != supersededReportedPositionMs) {
                supersededReportedPositionMs = null
            }
            val ignoreReportedPosition = deferPositionFrame || ignoreReportedUntilAvrcp ||
                reported == supersededReportedPositionMs ||
                (transport == MediaSessionTransport.BLUETOOTH_AVRCP && lastAvrcpCapturedAt != null &&
                    (validPublisherTime == null || validPublisherTime <= lastAvrcpCapturedAt!!))
            val reportedChanged = !ignoreReportedPosition &&
                hasReportedPosition && reported != lastReportedPositionMs
            val publisherChanged = publisherPositionTime != lastObservedPublisherPositionTime
            val usablePublisherChange = !ignoreReportedPosition && hasReportedPosition &&
                publisherChanged &&
                validPublisherTime != null
            val playbackModeChanged = playing != wasPlaying
            val speedChanged = playing && speed != lastSpeed

            if (avrcpPosition != null) {
                basePositionMs = clamp(avrcpPosition.positionMs, durationMs)
                capturedAtElapsedRealtime = avrcpPosition.capturedAt
                timelineReady = true
                supersededReportedPositionMs = if (hasReportedPosition) reported else null
                lastAvrcpCapturedAt = avrcpPosition.capturedAt
                ignoreReportedUntilAvrcp = false
            } else if (!timelineReady) {
                val freshPosition = !ignoreReportedPosition && hasReportedPosition && when (transport) {
                    MediaSessionTransport.BLUETOOTH_AVRCP ->
                        reported > 0L && reported != firstUntrustedPositionMs
                    MediaSessionTransport.STANDARD ->
                        reported <= INITIAL_POSITION_TRUST_LIMIT_MS ||
                            reported != firstUntrustedPositionMs || usablePublisherChange
                }
                if (freshPosition) {
                    basePositionMs = clamp(reported, durationMs)
                    capturedAtElapsedRealtime = if (usablePublisherChange) {
                        validPublisherTime!!
                    } else {
                        now
                    }
                    timelineReady = true
                }
            } else if (reportedChanged ||
                (usablePublisherChange && transport != MediaSessionTransport.BLUETOOTH_AVRCP)
            ) {
                basePositionMs = clamp(reported, durationMs)
                capturedAtElapsedRealtime = if (usablePublisherChange) validPublisherTime!! else now
            } else if (playbackModeChanged || speedChanged) {
                basePositionMs = currentPosition
                capturedAtElapsedRealtime = now
            }

            if (hasReportedPosition) {
                lastReportedPositionMs = reported
            }
            lastObservedPublisherPositionTime = publisherPositionTime
            lastSpeed = speed
            wasPlaying = playing
        }

        val position = clamp(positionAt(now), durationMs)
        return MediaSessionTimeline(
            positionMs = position,
            speed = speed,
            timelineReady = this.timelineReady
        )
    }

    /**
     * A replacement controller can expose one stale position frame. Keep the
     * established local timeline for that frame, then accept the next concrete
     * publisher position as normal (including a real seek).
     */
    fun deferNextReportedPosition() {
        deferNextReportedPosition = true
    }

    fun restorePosition(
        trackKey: String,
        positionMs: Long,
        durationMs: Long,
        transport: MediaSessionTransport = MediaSessionTransport.STANDARD
    ): MediaSessionTimeline? {
        if (trackKey != this.trackKey || positionMs < 0L) return null
        val now = nowElapsedRealtime()
        basePositionMs = clamp(positionMs, durationMs)
        capturedAtElapsedRealtime = now
        supersededReportedPositionMs = lastReportedPositionMs.takeIf { it >= 0L }
        deferNextReportedPosition = false
        timelineReady = true
        ignoreReportedUntilAvrcp = transport == MediaSessionTransport.BLUETOOTH_AVRCP
        return MediaSessionTimeline(
            positionMs = clamp(positionAt(now), durationMs),
            speed = lastSpeed,
            timelineReady = true
        )
    }

    fun reset() {
        trackKey = ""
        hasObservedTrack = false
        basePositionMs = 0L
        capturedAtElapsedRealtime = 0L
        lastReportedPositionMs = Long.MIN_VALUE
        firstUntrustedPositionMs = Long.MIN_VALUE
        lastObservedPublisherPositionTime = Long.MIN_VALUE
        lastSpeed = 0.0
        wasPlaying = false
        timelineReady = false
        deferNextReportedPosition = false
        pendingAvrcpPosition = null
        supersededReportedPositionMs = null
        lastAvrcpCapturedAt = null
        ignoreReportedUntilAvrcp = false
    }

    private fun positionAt(now: Long): Long {
        if (!timelineReady) return 0L
        if (!wasPlaying) return basePositionMs
        val elapsed = max(0L, now - capturedAtElapsedRealtime)
        return (basePositionMs.toDouble() + elapsed * lastSpeed).coerceAtLeast(0.0).toLong()
    }

    private fun clamp(position: Long, durationMs: Long): Long =
        if (durationMs > 0L) min(position.coerceAtLeast(0L), durationMs) else position.coerceAtLeast(0L)

    private fun isPlaying(state: Int?): Boolean = state == PlaybackState.STATE_PLAYING ||
        state == PlaybackState.STATE_FAST_FORWARDING ||
        state == PlaybackState.STATE_REWINDING

    private fun effectiveSpeed(
        playing: Boolean,
        state: Int?,
        reportedSpeed: Float
    ): Double {
        if (!playing) return 0.0
        if (reportedSpeed.isFinite() && reportedSpeed != 0f) return reportedSpeed.toDouble()
        return if (state == PlaybackState.STATE_REWINDING) -1.0 else 1.0
    }

    private companion object {
        const val INITIAL_POSITION_TRUST_LIMIT_MS = 2_500L
        const val AVRCP_EVENT_MAX_AGE_MS = 1_500L
    }
}
