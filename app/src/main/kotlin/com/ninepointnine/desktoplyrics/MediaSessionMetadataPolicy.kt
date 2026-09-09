package com.ninepointnine.desktoplyrics

import kotlin.math.abs

internal enum class MediaSessionTransport {
    STANDARD,
    BLUETOOTH_AVRCP
}

internal enum class MediaSessionDurationUnit {
    MILLISECONDS,
    SECONDS,
    UNKNOWN
}

internal data class MediaSessionMetadataFields(
    val descriptionTitle: String = "",
    val descriptionSubtitle: String = "",
    val descriptionDescription: String = "",
    val displayTitle: String = "",
    val displaySubtitle: String = "",
    val displayDescription: String = "",
    val title: String = "",
    val artist: String = "",
    val albumArtist: String = "",
    val author: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val transport: MediaSessionTransport = MediaSessionTransport.STANDARD,
    val durationUnit: MediaSessionDurationUnit = MediaSessionDurationUnit.MILLISECONDS,
    val reportedPositionMs: Long = -1L,
    val mediaId: String = ""
)

internal data class MediaRecordingMetadata(
    val track: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val mediaId: String = "",
    val embeddedLyrics: String = ""
) {
    val hasTrack: Boolean get() = track.isNotBlank()
}

/**
 * Normalizes public MediaSession metadata before it enters lyrics state.
 * Each field has one semantic meaning regardless of transport: the media
 * display fields are preferred because some car media centers overload raw
 * title with production-credit text; description and raw keys remain fallbacks.
 * Bluetooth transport affects duration units, never title/artist/album meaning.
 */
internal object MediaSessionMetadataPolicy {
    fun normalize(fields: MediaSessionMetadataFields): MediaRecordingMetadata =
        MediaRecordingMetadata(
            track = firstText(
                fields.displayTitle,
                fields.descriptionTitle,
                fields.title
            ),
            artist = firstText(
                fields.displaySubtitle,
                fields.descriptionSubtitle,
                fields.artist,
                fields.albumArtist,
                fields.author
            ),
            album = firstNonLyricsText(
                fields.album,
                fields.descriptionDescription,
                fields.displayDescription
            ),
            durationMs = normalizeDuration(
                rawDuration = fields.durationMs,
                unit = fields.durationUnit,
                reportedPositionMs = fields.reportedPositionMs
            ),
            mediaId = fields.mediaId.takeIf(String::isNotBlank).orEmpty(),
            embeddedLyrics = firstEmbeddedLyrics(
                fields.descriptionDescription,
                fields.displayDescription,
                fields.album
            )
        )

    private fun normalizeDuration(
        rawDuration: Long,
        unit: MediaSessionDurationUnit,
        reportedPositionMs: Long
    ): Long {
        if (rawDuration <= 0L) return 0L
        val effectiveUnit = when {
            unit != MediaSessionDurationUnit.UNKNOWN -> unit
            reportedPositionMs > rawDuration &&
                reportedPositionMs - rawDuration > DURATION_UNIT_EVIDENCE_MARGIN_MS ->
                MediaSessionDurationUnit.SECONDS
            else -> MediaSessionDurationUnit.UNKNOWN
        }
        return when (effectiveUnit) {
            MediaSessionDurationUnit.MILLISECONDS ->
                rawDuration.coerceIn(0L, MAX_NORMALIZED_DURATION_MS)
            MediaSessionDurationUnit.SECONDS ->
                secondsToMilliseconds(rawDuration)
            MediaSessionDurationUnit.UNKNOWN -> 0L
        }
    }

    private fun secondsToMilliseconds(seconds: Long): Long {
        if (seconds <= 0L) return 0L
        val maximumSeconds = MAX_NORMALIZED_DURATION_MS / MILLIS_PER_SECOND
        return seconds.coerceAtMost(maximumSeconds) * MILLIS_PER_SECOND
    }

    private fun firstText(vararg values: String): String = values
        .asSequence()
        .map(String::trim)
        .firstOrNull(String::isNotEmpty)
        .orEmpty()

    private fun firstNonLyricsText(vararg values: String): String = values
        .asSequence()
        .map(String::trim)
        .firstOrNull { it.isNotEmpty() && !isEmbeddedSynchronizedLyrics(it) }
        .orEmpty()

    private fun firstEmbeddedLyrics(vararg values: String): String = values
        .asSequence()
        .map(String::trim)
        .firstOrNull(::isEmbeddedSynchronizedLyrics)
        .orEmpty()

    private const val MILLIS_PER_SECOND = 1_000L
    private const val MAX_NORMALIZED_DURATION_MS = 86_400_000L
    private const val DURATION_UNIT_EVIDENCE_MARGIN_MS = 2_000L
}

internal data class MediaRecordingState(
    val metadata: MediaRecordingMetadata,
    val recordingGeneration: Long,
    val queryRevision: Long,
    val recordingChanged: Boolean,
    val queryChanged: Boolean
)

/** Owns the stable recording identity and bounded query revisions for one service lifecycle. */
internal class MediaRecordingStateTracker(
    private val durationRevisionThresholdMs: Long = QUERY_DURATION_REVISION_THRESHOLD_MS
) {
    private var sourceIdentity: Any? = null
    private var metadata: MediaRecordingMetadata? = null
    private var queryMetadata: MediaRecordingMetadata? = null
    private var recordingGeneration = 0L
    private var queryRevision = 0L

    fun update(sourceIdentity: Any?, incoming: MediaRecordingMetadata?): MediaRecordingState? {
        if (sourceIdentity == null) {
            clear()
            return null
        }

        val current = metadata
        if (incoming?.hasTrack != true) {
            return current
                ?.takeIf { this.sourceIdentity == sourceIdentity }
                ?.asState(recordingChanged = false, queryChanged = false)
        }

        if (current == null || this.sourceIdentity != sourceIdentity ||
            isDifferentRecording(current, incoming)
        ) {
            this.sourceIdentity = sourceIdentity
            metadata = incoming
            queryMetadata = incoming
            recordingGeneration += 1L
            queryRevision += 1L
            return incoming.asState(recordingChanged = true, queryChanged = true)
        }

        val merged = mergeEnrichment(current, incoming)
        val queryChanged = queryMaterialChanged(queryMetadata ?: current, merged)
        metadata = merged
        if (queryChanged) {
            queryMetadata = merged
            queryRevision += 1L
        }
        return merged.asState(recordingChanged = false, queryChanged = queryChanged)
    }

    fun clear(): Boolean {
        val hadRecording = metadata != null
        sourceIdentity = null
        metadata = null
        queryMetadata = null
        return hadRecording
    }

    private fun isDifferentRecording(
        current: MediaRecordingMetadata,
        incoming: MediaRecordingMetadata
    ): Boolean {
        if (current.mediaId.isNotBlank() && incoming.mediaId.isNotBlank() &&
            current.mediaId != incoming.mediaId
        ) return true
        if (normalizeText(current.track) != normalizeText(incoming.track)) return true
        if (conflicts(current.artist, incoming.artist)) return true
        return conflicts(current.album, incoming.album)
    }

    private fun conflicts(first: String, second: String): Boolean =
        first.isNotBlank() && second.isNotBlank() && normalizeText(first) != normalizeText(second)

    private fun mergeEnrichment(
        current: MediaRecordingMetadata,
        incoming: MediaRecordingMetadata
    ): MediaRecordingMetadata = MediaRecordingMetadata(
        track = incoming.track,
        artist = incoming.artist.ifBlank { current.artist },
        album = incoming.album.ifBlank { current.album },
        durationMs = if (incoming.durationMs > 0L || current.durationMs <= 0L) {
            incoming.durationMs
        } else {
            current.durationMs
        },
        mediaId = incoming.mediaId.ifBlank { current.mediaId },
        embeddedLyrics = incoming.embeddedLyrics.ifBlank { current.embeddedLyrics }
    )

    private fun queryMaterialChanged(
        current: MediaRecordingMetadata,
        incoming: MediaRecordingMetadata
    ): Boolean {
        if (normalizeText(current.track) != normalizeText(incoming.track)) return true
        if (normalizeText(current.artist) != normalizeText(incoming.artist)) return true
        if (normalizeText(current.album) != normalizeText(incoming.album)) return true
        if (current.embeddedLyrics != incoming.embeddedLyrics) return true
        val currentDurationKnown = current.durationMs >= MINIMUM_QUERY_DURATION_MS
        val incomingDurationKnown = incoming.durationMs >= MINIMUM_QUERY_DURATION_MS
        if (currentDurationKnown != incomingDurationKnown) return true
        return currentDurationKnown &&
            abs(current.durationMs - incoming.durationMs) > durationRevisionThresholdMs
    }

    private fun MediaRecordingMetadata.asState(
        recordingChanged: Boolean,
        queryChanged: Boolean
    ): MediaRecordingState = MediaRecordingState(
        metadata = this,
        recordingGeneration = recordingGeneration,
        queryRevision = queryRevision,
        recordingChanged = recordingChanged,
        queryChanged = queryChanged
    )

    companion object {
        internal const val MINIMUM_QUERY_DURATION_MS = 1_000L
        internal const val QUERY_DURATION_REVISION_THRESHOLD_MS = 2_000L
    }
}
