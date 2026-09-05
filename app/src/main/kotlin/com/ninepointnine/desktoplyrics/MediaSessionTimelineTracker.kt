package com.ninepointnine.desktoplyrics

import android.media.session.PlaybackState
import kotlin.math.max
import kotlin.math.min
import java.util.Locale

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
    val savedAtEpochMs: Long
)

/** Keeps restart recovery tied to one logical source and one recording. */
internal object MediaPlaybackCheckpointPolicy {
    internal const val MAX_AGE_MS = 24L * 60L * 60L * 1_000L
    internal const val DURATION_TOLERANCE_MS = 2_000L

    fun isValid(checkpoint: MediaPlaybackCheckpoint, nowEpochMs: Long): Boolean {
        if (checkpoint.sourceId.isBlank() || checkpoint.track.isBlank()) return false
        if (checkpoint.positionMs < 0L || checkpoint.durationMs < 0L) return false
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
        nowEpochMs: Long
    ): Boolean {
        if (!isValid(checkpoint, nowEpochMs)) return false
        if (!sameRequiredText(checkpoint.sourceId, sourceId)) return false
        if (!sameRequiredText(checkpoint.track, track)) return false
        if (!sameOptionalText(checkpoint.artist, artist)) return false
        if (!sameOptionalText(checkpoint.album, album)) return false
        return checkpoint.durationMs <= 0L || durationMs <= 0L ||
            kotlin.math.abs(checkpoint.durationMs - durationMs) <= DURATION_TOLERANCE_MS
    }

    private fun sameRequiredText(first: String, second: String): Boolean =
        normalize(first) == normalize(second) && normalize(first).isNotEmpty()

    private fun sameOptionalText(first: String, second: String): Boolean =
        first.isBlank() || second.isBlank() || normalize(first) == normalize(second)

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
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

    fun update(
        trackKey: String,
        playbackState: Int?,
        reportedPositionMs: Long,
        playbackSpeed: Float,
        publisherPositionTime: Long,
        durationMs: Long
    ): MediaSessionTimeline {
        val now = nowElapsedRealtime()
        val hasReportedPosition = reportedPositionMs >= 0L
        val reported = reportedPositionMs.coerceAtLeast(0L)
        val playing = isPlaying(playbackState)
        val speed = effectiveSpeed(playing, playbackState, playbackSpeed)
        val validPublisherTime = publisherPositionTime
            .takeIf { it > 0L && it <= now }

        if (trackKey != this.trackKey) {
            val firstObservedTrack = !hasObservedTrack
            val trustedInitialPosition = hasReportedPosition &&
                (firstObservedTrack || reported <= INITIAL_POSITION_TRUST_LIMIT_MS)
            this.trackKey = trackKey
            basePositionMs = if (trustedInitialPosition) reported else 0L
            capturedAtElapsedRealtime = if (trustedInitialPosition) {
                validPublisherTime ?: now
            } else {
                now
            }
            lastReportedPositionMs = if (trustedInitialPosition) reported else Long.MIN_VALUE
            firstUntrustedPositionMs = if (trustedInitialPosition) Long.MIN_VALUE else reported
            lastObservedPublisherPositionTime = publisherPositionTime
            lastSpeed = speed
            wasPlaying = playing
            timelineReady = trustedInitialPosition
            hasObservedTrack = true
            deferNextReportedPosition = false
        } else {
            val currentPosition = positionAt(now)
            val deferPositionFrame = deferNextReportedPosition
            deferNextReportedPosition = false
            val ignoreReportedPosition = deferPositionFrame && hasReportedPosition
            val reportedChanged = !ignoreReportedPosition &&
                hasReportedPosition && reported != lastReportedPositionMs
            val publisherChanged = publisherPositionTime != lastObservedPublisherPositionTime
            val usablePublisherChange = !ignoreReportedPosition && hasReportedPosition &&
                publisherChanged &&
                validPublisherTime != null
            val playbackModeChanged = playing != wasPlaying
            val speedChanged = playing && speed != lastSpeed

            if (!timelineReady) {
                val freshPosition = hasReportedPosition &&
                    (reported <= INITIAL_POSITION_TRUST_LIMIT_MS ||
                        (firstUntrustedPositionMs != Long.MIN_VALUE &&
                            reported != firstUntrustedPositionMs))
                if (freshPosition) {
                    basePositionMs = reported
                    capturedAtElapsedRealtime = if (usablePublisherChange) {
                        validPublisherTime!!
                    } else {
                        now
                    }
                    timelineReady = true
                }
            } else if (reportedChanged || usablePublisherChange) {
                basePositionMs = reported
                capturedAtElapsedRealtime = if (usablePublisherChange) validPublisherTime!! else now
            } else if (playbackModeChanged || speedChanged) {
                basePositionMs = currentPosition
                capturedAtElapsedRealtime = now
            }

            if (hasReportedPosition && timelineReady && !ignoreReportedPosition) {
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
        durationMs: Long
    ): MediaSessionTimeline? {
        if (trackKey != this.trackKey || positionMs < 0L) return null
        val now = nowElapsedRealtime()
        basePositionMs = clamp(positionMs, durationMs)
        capturedAtElapsedRealtime = now
        lastReportedPositionMs = basePositionMs
        deferNextReportedPosition = false
        timelineReady = true
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
    }

    private fun positionAt(now: Long): Long {
        if (!wasPlaying) return basePositionMs
        val elapsed = max(0L, now - capturedAtElapsedRealtime)
        return max(0L, basePositionMs + (elapsed * lastSpeed).toLong())
    }

    private fun clamp(position: Long, durationMs: Long): Long =
        if (durationMs > 0L) min(position, durationMs) else position

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
    }
}
