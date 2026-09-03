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
    val reportedPositionMs: Long = -1L
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
 * Android's media description is normally the controller-facing contract.
 * Bluetooth AVRCP sessions may expose either the ordinary independent fields
 * or a bridge-specific projection where ARTIST serializes
 * "recording title-artist". The capability decision is kept at this boundary,
 * before any recording, cache, settings, or WebView consumer can observe it.
 */
internal object MediaSessionMetadataPolicy {
    fun normalize(fields: MediaSessionMetadataFields): MediaRecordingMetadata {
        val identity = when (fields.transport) {
            MediaSessionTransport.STANDARD -> normalizeStandardIdentity(
                fields,
                firstText(
                    fields.descriptionTitle,
                    fields.displayTitle,
                    fields.title
                )
            )
            MediaSessionTransport.BLUETOOTH_AVRCP -> normalizeBluetoothIdentity(fields)
        }
        return MediaRecordingMetadata(
            track = identity.track,
            artist = identity.artist,
            album = when (fields.transport) {
                MediaSessionTransport.STANDARD -> firstText(
                    fields.album,
                    fields.descriptionDescription,
                    fields.displayDescription
                )
                // The verified AVRCP projection has a dedicated raw ALBUM
                // key. Display descriptions are not an identity fallback.
                MediaSessionTransport.BLUETOOTH_AVRCP -> firstText(fields.album)
            },
            durationMs = normalizeDuration(
                rawDuration = fields.durationMs,
                unit = fields.durationUnit,
                reportedPositionMs = fields.reportedPositionMs
            )
        )
    }

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

    private fun normalizeStandardIdentity(
        fields: MediaSessionMetadataFields,
        directTrack: String
    ): RecordingIdentityFields = RecordingIdentityFields(
        track = directTrack,
        artist = firstText(
            fields.descriptionSubtitle,
            fields.artist,
            fields.albumArtist,
            fields.author,
            fields.displaySubtitle
        )
    )

    private fun normalizeBluetoothIdentity(fields: MediaSessionMetadataFields): RecordingIdentityFields {
        val rawArtist = fields.artist.trim()
        val decision = resolveBluetoothCapability(fields, rawArtist)
        return when (decision.capability) {
            BluetoothMetadataCapability.INDEPENDENT_FIELDS ->
                normalizeBluetoothStandardIdentity(fields, rawArtist)
            BluetoothMetadataCapability.COMPOSITE_ARTIST ->
                compositeIdentity(fields, requireNotNull(decision.composite))
            BluetoothMetadataCapability.UNKNOWN -> {
                // A missing or ambiguous boundary is an incomplete
                // publication. Never promote a possibly changing TITLE.
                RecordingIdentityFields(
                    track = "",
                    artist = if (rawArtist.isBlank()) {
                        firstText(fields.albumArtist)
                    } else {
                        rawArtist
                    }
                )
            }
        }
    }

    private fun resolveBluetoothCapability(
        fields: MediaSessionMetadataFields,
        rawArtist: String
    ): BluetoothCapabilityDecision {
        if (rawArtist.isBlank()) {
            return BluetoothCapabilityDecision(BluetoothMetadataCapability.UNKNOWN)
        }

        // ALBUM_ARTIST is an independent semantic key. If it reproduces the
        // complete raw ARTIST value, that is conclusive evidence that the
        // hyphen belongs to the artist name rather than a title/artist wire
        // encoding. Resolve this before trying any composite grammar.
        if (hasIndependentBluetoothArtist(rawArtist, fields.albumArtist)) {
            return BluetoothCapabilityDecision(BluetoothMetadataCapability.INDEPENDENT_FIELDS)
        }

        // A bridge-specific composite, when present, is decoded only from the
        // raw ARTIST key. MediaDescription subtitles are derived display
        // projections and may carry unrelated text; they must never become a
        // second parser input.
        val composite = splitBluetoothComposite(
            value = rawArtist,
            stableArtistHints = listOf(fields.albumArtist)
        )
        return if (composite == null) {
            BluetoothCapabilityDecision(BluetoothMetadataCapability.UNKNOWN)
        } else {
            BluetoothCapabilityDecision(
                capability = BluetoothMetadataCapability.COMPOSITE_ARTIST,
                composite = composite
            )
        }
    }

    private fun normalizeBluetoothStandardIdentity(
        fields: MediaSessionMetadataFields,
        rawArtist: String
    ): RecordingIdentityFields = RecordingIdentityFields(
        // Raw TITLE/DISPLAY_TITLE are the independent-field capability's
        // semantic channels. A description is only the final fallback because
        // it is a derived projection and can be repurposed by a bridge.
        track = firstText(
            fields.title,
            fields.displayTitle,
            fields.descriptionTitle
        ),
        artist = rawArtist
    )

    private fun compositeIdentity(
        fields: MediaSessionMetadataFields,
        composite: RecordingIdentityFields
    ): RecordingIdentityFields {
        val corroboratedArtist = listOf(
            fields.albumArtist
        ).asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .firstOrNull { value -> sameMetadataText(value, composite.artist) }

        return RecordingIdentityFields(
            // The AVRCP TITLE is a display channel. Even when one lyric line
            // happens to normalize like the decoded title, never copy its
            // spelling into the recording identity.
            track = composite.track,
            artist = corroboratedArtist ?: composite.artist
        )
    }

    private fun hasIndependentBluetoothArtist(
        artist: String,
        albumArtist: String
    ): Boolean {
        if (!BLUETOOTH_COMPOSITE_DASH_PATTERN.containsMatchIn(artist)) return true
        return albumArtist.trim().isNotEmpty() && sameMetadataText(artist, albumArtist)
    }

    private fun splitBluetoothComposite(
        value: String,
        stableArtistHints: List<String>
    ): RecordingIdentityFields? {
        // Normalize only for comparisons and length checks; preserve the
        // publisher's spelling in the fields returned to settings and WebView.
        val normalized = value.trim()
        if (normalized.isBlank()) return null
        val normalizedArtistHints = stableArtistHints
            .asSequence()
            .map(::normalizeText)
            .filter(String::isNotBlank)
            .toSet()
        val candidates = BLUETOOTH_COMPOSITE_DASH_PATTERN.findAll(normalized)
            .mapNotNull { delimiter ->
                val track = normalized.substring(0, delimiter.range.first).trim()
                val artist = normalized.substring(delimiter.range.last + 1).trim()
                val trackLength = normalizeText(track).length
                val artistLength = normalizeText(artist).length
                if (trackLength < MINIMUM_COMPOSITE_PART_LENGTH ||
                    artistLength < MINIMUM_COMPOSITE_PART_LENGTH ||
                    !isValidArtist(artist) ||
                    (hasStrongArtistSeparator(track) &&
                        normalizeText(artist) !in normalizedArtistHints)
                ) {
                    null
                } else {
                    BluetoothCompositeCandidate(
                        track = track,
                        artist = artist,
                        delimiterIndex = delimiter.range.first,
                        trackLength = trackLength,
                        terminalTrackSegmentLength = terminalTrackSegmentLength(track),
                        artistSeparatorCount = artistSeparatorCount(artist),
                        artistFirstSegmentMixedScripts = hasMixedScripts(firstArtistSegment(artist)),
                        artistDashCount = BLUETOOTH_COMPOSITE_DASH_PATTERN
                            .findAll(artist)
                            .count()
                    )
                }
            }
            .toList()
        if (candidates.isEmpty()) return null

        val artistHinted = if (normalizedArtistHints.isEmpty()) {
            emptyList()
        } else {
            candidates.filter { normalizeText(it.artist) in normalizedArtistHints }
        }

        // A lone ASCII hyphen is common inside an artist name (for example
        // "Sia-Furler") and is not enough evidence that the bridge encoded a
        // title. Decode only when an independent hint, an explicit
        // multi-artist separator, or the observed non-Latin catalog shape makes
        // the composite interpretation defensible. Otherwise retain the raw
        // fields instead of inventing a boundary.
        val hasCompositeEvidence = artistHinted.isNotEmpty() ||
            candidates.any {
                it.artistSeparatorCount > 0 ||
                    hasNonLatinLetter(it.track) ||
                    hasNonLatinLetter(it.artist)
            }
        if (!hasCompositeEvidence) return null

        val selected = when {
            artistHinted.size == 1 -> artistHinted.single()
            candidates.any { it.artistSeparatorCount > 0 } -> candidates
                .filter { it.artistSeparatorCount > 0 }
                .maxWithOrNull(
                    compareBy<BluetoothCompositeCandidate> { it.artistSeparatorCount }
                        .thenBy {
                            if (it.artistFirstSegmentMixedScripts) 1 else 0
                        }
                        .thenBy { it.terminalTrackSegmentLength }
                        .thenByDescending { it.delimiterIndex }
                )

            else -> candidates.maxWithOrNull(
                compareBy<BluetoothCompositeCandidate> {
                    if (it.terminalTrackSegmentLength >= MINIMUM_COMPOSITE_PART_LENGTH) 1 else 0
                }
                    // A title may contain dashes; an artist alias is less
                    // likely to be the boundary when the remaining suffix
                    // still contains another dash.
                    .thenBy { if (it.artistDashCount == 0) 1 else 0 }
                    .thenBy { it.trackLength }
                    .thenByDescending { it.delimiterIndex }
            )
        } ?: return null

        return RecordingIdentityFields(selected.track, selected.artist)
    }

    private fun terminalTrackSegmentLength(track: String): Int {
        val lastDash = BLUETOOTH_COMPOSITE_DASH_PATTERN.findAll(track).lastOrNull()
        val segment = if (lastDash == null) {
            track
        } else {
            track.substring(lastDash.range.last + 1)
        }
        return normalizeText(segment).length
    }

    private fun hasStrongArtistSeparator(value: String): Boolean =
        BLUETOOTH_STRONG_ARTIST_SEPARATOR_PATTERN.containsMatchIn(value)

    private fun artistSeparatorCount(value: String): Int =
        BLUETOOTH_ARTIST_SEPARATOR_PATTERN.findAll(value).count()

    private fun firstArtistSegment(value: String): String =
        BLUETOOTH_ARTIST_SEPARATOR_PATTERN.split(value, limit = 2).firstOrNull().orEmpty()

    private fun hasMixedScripts(value: String): Boolean {
        val scripts = value.asSequence()
            .filter(Char::isLetter)
            .map { Character.UnicodeScript.of(it.code) }
            .filterNot {
                it == Character.UnicodeScript.COMMON ||
                    it == Character.UnicodeScript.INHERITED
            }
            .toSet()
        return scripts.size > 1
    }

    private fun hasNonLatinLetter(value: String): Boolean = value.any {
        if (!it.isLetter()) return@any false
        val script = Character.UnicodeScript.of(it.code)
        script != Character.UnicodeScript.LATIN &&
            script != Character.UnicodeScript.COMMON &&
            script != Character.UnicodeScript.INHERITED
    }

    private fun sameMetadataText(first: String, second: String): Boolean =
        normalizeText(first).isNotBlank() && normalizeText(first) == normalizeText(second)

    private fun firstText(vararg values: String): String = values
        .asSequence()
        .map(String::trim)
        .firstOrNull(String::isNotEmpty)
        .orEmpty()

    private data class RecordingIdentityFields(
        val track: String,
        val artist: String
    )

    private enum class BluetoothMetadataCapability {
        INDEPENDENT_FIELDS,
        COMPOSITE_ARTIST,
        UNKNOWN
    }

    private data class BluetoothCapabilityDecision(
        val capability: BluetoothMetadataCapability,
        val composite: RecordingIdentityFields? = null
    )

    private data class BluetoothCompositeCandidate(
        val track: String,
        val artist: String,
        val delimiterIndex: Int,
        val trackLength: Int,
        val terminalTrackSegmentLength: Int,
        val artistSeparatorCount: Int,
        val artistFirstSegmentMixedScripts: Boolean,
        val artistDashCount: Int
    )

    private val BLUETOOTH_COMPOSITE_DASH_PATTERN = Regex("[-‐‑‒–—―－]")
    private val BLUETOOTH_ARTIST_SEPARATOR_PATTERN =
        Regex("[/／&＆、;；+＋|｜\\r\\n]")
    // Commas are valid and common title punctuation. They are deliberately
    // excluded from boundary evidence so a comma inside a title cannot make
    // an early hyphen look like the title/artist separator.
    private val BLUETOOTH_STRONG_ARTIST_SEPARATOR_PATTERN =
        Regex("[/／&＆、;；+＋|｜\\r\\n]")
    private const val MINIMUM_COMPOSITE_PART_LENGTH = 2
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
