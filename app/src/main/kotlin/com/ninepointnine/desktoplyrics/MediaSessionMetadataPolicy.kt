package com.ninepointnine.desktoplyrics

import kotlin.math.abs

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
    val durationMs: Long = 0L
)

internal data class MediaRecordingMetadata(
    val track: String,
    val artist: String,
    val album: String,
    val durationMs: Long
) {
    val hasTrack: Boolean get() = track.isNotBlank()
}

/**
 * Normalizes public MediaSession metadata before it enters lyrics state.
 * Android's media description is the controller-facing contract; individual
 * raw fields are only fallbacks because publishers may reuse them for dynamic
 * display text.
 */
internal object MediaSessionMetadataPolicy {
    fun normalize(fields: MediaSessionMetadataFields): MediaRecordingMetadata =
        MediaRecordingMetadata(
            track = firstText(
                fields.descriptionTitle,
                fields.displayTitle,
                fields.title
            ),
            artist = firstText(
                fields.descriptionSubtitle,
                fields.artist,
                fields.albumArtist,
                fields.author,
                fields.displaySubtitle
            ),
            album = firstText(
                fields.album,
                fields.descriptionDescription,
                fields.displayDescription
            ),
            durationMs = fields.durationMs.coerceAtLeast(0L)
        )

    private fun firstText(vararg values: String): String = values
        .asSequence()
        .map(String::trim)
        .firstOrNull(String::isNotEmpty)
        .orEmpty()
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
        }
    )

    private fun queryMaterialChanged(
        current: MediaRecordingMetadata,
        incoming: MediaRecordingMetadata
    ): Boolean {
        if (normalizeText(current.track) != normalizeText(incoming.track)) return true
        if (normalizeText(current.artist) != normalizeText(incoming.artist)) return true
        if (normalizeText(current.album) != normalizeText(incoming.album)) return true
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
