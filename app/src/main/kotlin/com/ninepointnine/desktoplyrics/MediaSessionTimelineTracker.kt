package com.ninepointnine.desktoplyrics

import android.media.session.PlaybackState
import kotlin.math.max
import kotlin.math.min

internal data class MediaSessionTimeline(
    val positionMs: Long,
    val speed: Double,
    val timelineReady: Boolean
)

/**
 * Normalizes publisher progress into a local monotonic timeline.
 * Some car sessions report a zero or invalid lastPositionUpdateTime; in that
 * case progress is extrapolated from the callback time instead of epoch zero.
 */
internal class MediaSessionTimelineTracker(
    private val nowElapsedRealtime: () -> Long
) {
    private var trackKey = ""
    private var basePositionMs = 0L
    private var capturedAtElapsedRealtime = 0L
    private var lastReportedPositionMs = Long.MIN_VALUE
    private var lastObservedPublisherPositionTime = Long.MIN_VALUE
    private var lastSpeed = 0.0
    private var wasPlaying = false
    private var timelineReady = false

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
            this.trackKey = trackKey
            basePositionMs = reported
            capturedAtElapsedRealtime = if (hasReportedPosition) {
                validPublisherTime ?: now
            } else {
                now
            }
            lastReportedPositionMs = if (hasReportedPosition) reported else Long.MIN_VALUE
            lastObservedPublisherPositionTime = publisherPositionTime
            lastSpeed = speed
            wasPlaying = playing
            timelineReady = hasReportedPosition
        } else {
            val currentPosition = positionAt(now)
            val reportedChanged = hasReportedPosition && reported != lastReportedPositionMs
            val publisherChanged = publisherPositionTime != lastObservedPublisherPositionTime
            val usablePublisherChange = hasReportedPosition &&
                publisherChanged &&
                validPublisherTime != null
            val playbackModeChanged = playing != wasPlaying
            val speedChanged = playing && speed != lastSpeed

            if (reportedChanged || usablePublisherChange) {
                basePositionMs = reported
                capturedAtElapsedRealtime = if (usablePublisherChange) validPublisherTime!! else now
            } else if (playbackModeChanged || speedChanged) {
                basePositionMs = currentPosition
                capturedAtElapsedRealtime = now
            }

            if (hasReportedPosition) lastReportedPositionMs = reported
            lastObservedPublisherPositionTime = publisherPositionTime
            lastSpeed = speed
            wasPlaying = playing
            if (hasReportedPosition) timelineReady = true
        }

        val position = clamp(positionAt(now), durationMs)
        return MediaSessionTimeline(
            positionMs = position,
            speed = speed,
            timelineReady = this.timelineReady
        )
    }

    fun reset() {
        trackKey = ""
        basePositionMs = 0L
        capturedAtElapsedRealtime = 0L
        lastReportedPositionMs = Long.MIN_VALUE
        lastObservedPublisherPositionTime = Long.MIN_VALUE
        lastSpeed = 0.0
        wasPlaying = false
        timelineReady = false
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
}
