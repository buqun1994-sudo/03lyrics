package com.tcrrry.desktoplyrics

import org.json.JSONArray
import org.json.JSONObject

internal const val LYRICS_MATCHER_POLICY_VERSION = 2

internal data class LyricsLookup(
    val track: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L
)

internal data class LyricsSearchTerms(
    val track: String,
    val artist: String
)

internal data class LrcLibExactLookupTerms(
    val track: String,
    val artist: String,
    val album: String,
    val durationMs: Long
)

internal data class LyricsResult(
    val lyrics: String = "",
    val translatedLyrics: String = "",
    val durationMs: Long = 0L,
    val cover: String = "",
    val source: String = "",
    val sourceId: String = "",
    val candidateTrack: String = "",
    val candidateArtist: String = "",
    val candidateAlbum: String = "",
    val lyricsKind: LyricsKind = LyricsKind.NONE
) {
    fun toJson(): JSONObject = JSONObject()
        .put("lyrics", lyrics)
        .put("translatedLyrics", translatedLyrics)
        .put("duration", durationMs)
        .put("cover", cover)
        .put("source", source)
        .put("sourceId", sourceId)
        .put("candidateTrack", candidateTrack)
        .put("candidateArtist", candidateArtist)
        .put("candidateAlbum", candidateAlbum)

    fun candidateSnapshot(): LyricsCandidateSnapshot = LyricsCandidateSnapshot(
        durationMs = durationMs,
        source = source,
        sourceId = sourceId,
        track = candidateTrack,
        artist = candidateArtist,
        album = candidateAlbum
    )
}

internal data class LyricsCandidateSnapshot(
    val durationMs: Long,
    val source: String,
    val sourceId: String,
    val track: String,
    val artist: String,
    val album: String
) {
    fun toResult(): LyricsResult = LyricsResult(
        durationMs = durationMs,
        source = source,
        sourceId = sourceId,
        candidateTrack = track,
        candidateArtist = artist,
        candidateAlbum = album
    )

    fun toJson(): JSONObject = JSONObject()
        .put("duration", durationMs)
        .put("source", source)
        .put("sourceId", sourceId)
        .put("track", track)
        .put("artist", artist)
        .put("album", album)

    companion object {
        fun fromJson(value: JSONObject?): LyricsCandidateSnapshot? {
            value ?: return null
            return LyricsCandidateSnapshot(
                durationMs = value.optLong("duration", 0L),
                source = value.optString("source"),
                sourceId = value.optString("sourceId"),
                track = value.optString("track"),
                artist = value.optString("artist"),
                album = value.optString("album")
            )
        }
    }
}

internal data class LyricsSelectionProof(
    val matcherPolicyVersion: Int,
    val supportingCandidates: List<LyricsCandidateSnapshot>
) {
    fun toJson(): JSONObject = JSONObject()
        .put("matcherPolicyVersion", matcherPolicyVersion)
        .put(
            "supportingCandidates",
            JSONArray().apply { supportingCandidates.forEach { put(it.toJson()) } }
        )

    companion object {
        fun fromJson(value: JSONObject?): LyricsSelectionProof? {
            value ?: return null
            val candidates = value.optJSONArray("supportingCandidates") ?: return null
            if (candidates.length() !in 1..MAX_SUPPORTING_CANDIDATES) return null
            val decoded = buildList {
                for (index in 0 until candidates.length()) {
                    LyricsCandidateSnapshot.fromJson(candidates.optJSONObject(index))?.let(::add)
                }
            }
            if (decoded.size != candidates.length()) return null
            return LyricsSelectionProof(
                matcherPolicyVersion = value.optInt("matcherPolicyVersion", 0),
                supportingCandidates = decoded
            )
        }

        const val MAX_SUPPORTING_CANDIDATES = 2
    }
}

internal data class ResolvedLyrics(
    val result: LyricsResult,
    val proof: LyricsSelectionProof
)

internal data class LyricsCandidateSelection(
    val candidate: LyricsResult,
    val proof: LyricsSelectionProof
)

internal sealed interface LyricsResolutionOutcome {
    data class Found(val resolved: ResolvedLyrics) : LyricsResolutionOutcome
    data object NoMatch : LyricsResolutionOutcome
    data object InvalidMetadata : LyricsResolutionOutcome
    data class RetryableFailure(val reason: LyricsFailureReason) : LyricsResolutionOutcome
    data object Cancelled : LyricsResolutionOutcome
}

internal enum class LyricsFailureReason {
    NETWORK,
    RATE_LIMIT,
    SERVER,
    DEADLINE
}

enum class LyricsKind {
    NONE,
    PLAIN,
    SYNCHRONIZED
}

internal fun cleanLyrics(value: String?): String {
    val text = value?.trim().orEmpty()
    return if (text.equals("null", ignoreCase = true) ||
        text.equals("undefined", ignoreCase = true) ||
        text.equals("[object Object]", ignoreCase = true)
    ) {
        ""
    } else {
        text
    }
}

internal fun classifyLyrics(value: String?): LyricsKind {
    val lyrics = cleanLyrics(value)
    if (lyrics.isBlank()) return LyricsKind.NONE
    return if (TIMESTAMP_PATTERN.containsMatchIn(lyrics)) LyricsKind.SYNCHRONIZED else LyricsKind.PLAIN
}

internal fun synchronizedLyricsOrEmpty(value: String?): String =
    cleanLyrics(value).takeIf { classifyLyrics(it) == LyricsKind.SYNCHRONIZED }.orEmpty()

internal fun JSONObject?.contentString(key: String): String {
    val value = this?.opt(key)
    return cleanLyrics(value?.toString())
}

private val TIMESTAMP_PATTERN = Regex("\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?]")
