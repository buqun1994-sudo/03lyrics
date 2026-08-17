package com.tcrrry.desktoplyrics

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

internal enum class WallpaperLyricsSpacing(val preferenceValue: String) {
    DENSE("dense"),
    STANDARD("standard"),
    LOOSE("loose");

    companion object {
        fun fromPreference(value: String?): WallpaperLyricsSpacing =
            entries.firstOrNull { it.preferenceValue == value } ?: STANDARD
    }
}

internal enum class WallpaperLyricsFocus(val preferenceValue: String) {
    TOP("top"),
    CENTER("center");

    companion object {
        fun fromPreference(value: String?): WallpaperLyricsFocus =
            entries.firstOrNull { it.preferenceValue == value } ?: CENTER
    }
}

internal enum class LyricsCacheSelection {
    AUTOMATIC,
    MANUAL
}

internal data class LyricsPlaybackIdentity(
    val track: String,
    val artist: String,
    val album: String,
    val durationMs: Long
) {
    val isUsable: Boolean
        get() = track.isNotBlank() && LyricsCandidateSelector.hasKnownDuration(durationMs)

    fun lookup(): LyricsLookup = LyricsLookup(track, artist, album, durationMs)

    fun sameRecordingAs(other: LyricsPlaybackIdentity?): Boolean =
        other != null && LyricsCache.key(track, artist, album, durationMs) ==
            LyricsCache.key(other.track, other.artist, other.album, other.durationMs)

    fun toJson(): JSONObject = JSONObject()
        .put("track", track)
        .put("artist", artist)
        .put("album", album)
        .put("durationMs", durationMs)

    companion object {
        fun fromJson(value: JSONObject?): LyricsPlaybackIdentity? {
            value ?: return null
            return LyricsPlaybackIdentity(
                track = value.optString("track"),
                artist = value.optString("artist"),
                album = value.optString("album"),
                durationMs = value.optLong("durationMs", 0L)
            )
        }
    }
}

internal data class LyricsCacheStats(
    val automaticEntries: Int,
    val manualEntries: Int,
    val totalBytes: Long,
    val maximumAutomaticBytes: Long
) {
    val totalEntries: Int get() = automaticEntries + manualEntries

    val estimatedRemainingTracks: Long
        get() {
            val remainingBytes = (maximumAutomaticBytes - totalBytes).coerceAtLeast(0L)
            val averageEntryBytes = if (totalEntries > 0 && totalBytes > 0L) {
                (totalBytes / totalEntries).coerceAtLeast(1L)
            } else {
                ESTIMATED_EMPTY_CACHE_ENTRY_BYTES
            }
            return remainingBytes / averageEntryBytes
        }

    fun toJson(): JSONObject = JSONObject()
        .put("automaticEntries", automaticEntries)
        .put("manualEntries", manualEntries)
        .put("totalBytes", totalBytes)
        .put("maximumBytes", maximumAutomaticBytes)

    companion object {
        private const val ESTIMATED_EMPTY_CACHE_ENTRY_BYTES = 4L * 1024L

        fun fromJson(value: JSONObject?): LyricsCacheStats {
            value ?: return LyricsCacheStats(0, 0, 0L, LyricsCachePolicy.MAX_BYTES)
            return LyricsCacheStats(
                automaticEntries = value.optInt("automaticEntries", 0).coerceAtLeast(0),
                manualEntries = value.optInt("manualEntries", 0).coerceAtLeast(0),
                totalBytes = value.optLong("totalBytes", 0L).coerceAtLeast(0L),
                maximumAutomaticBytes = value.optLong(
                    "maximumBytes",
                    LyricsCachePolicy.MAX_BYTES
                ).coerceAtLeast(1L)
            )
        }
    }
}

internal data class LyricsCachedTrackInfo(
    val selection: LyricsCacheSelection,
    val result: LyricsResult,
    val updatedAtMs: Long
) {
    fun toJson(): JSONObject = result.candidateSnapshot().toJson()
        .put("selection", selection.name)
        .put("translated", result.translatedLyrics.isNotBlank())
        .put("updatedAtMs", updatedAtMs)

    companion object {
        fun fromJson(value: JSONObject?): LyricsCachedTrackInfo? {
            value ?: return null
            val candidate = LyricsCandidateSnapshot.fromJson(value) ?: return null
            val selection = runCatching {
                LyricsCacheSelection.valueOf(value.optString("selection"))
            }.getOrNull() ?: return null
            return LyricsCachedTrackInfo(
                selection = selection,
                result = candidate.toResult().copy(
                    translatedLyrics = if (value.optBoolean("translated")) "available" else ""
                ),
                updatedAtMs = value.optLong("updatedAtMs", 0L).coerceAtLeast(0L)
            )
        }
    }
}

internal data class LyricsCacheSnapshot(
    val stats: LyricsCacheStats,
    val current: LyricsCachedTrackInfo?
) {
    fun toJson(): JSONObject = JSONObject()
        .put("stats", stats.toJson())
        .put("current", current?.toJson())

    companion object {
        fun fromJson(value: JSONObject?): LyricsCacheSnapshot {
            value ?: return LyricsCacheSnapshot(
                LyricsCacheStats(0, 0, 0L, LyricsCachePolicy.MAX_BYTES),
                null
            )
            return LyricsCacheSnapshot(
                stats = LyricsCacheStats.fromJson(value.optJSONObject("stats")),
                current = LyricsCachedTrackInfo.fromJson(value.optJSONObject("current"))
            )
        }
    }
}

internal enum class LyricsManualSearchState {
    IDLE,
    SEARCHING,
    READY,
    APPLYING,
    EMPTY,
    ERROR,
    NO_CURRENT_TRACK
}

internal data class LyricsSettingsRuntimeState(
    val playback: LyricsPlaybackIdentity?,
    val cache: LyricsCacheSnapshot,
    val searchState: LyricsManualSearchState,
    val searchCandidates: List<LyricsManualSearchCandidate>
) {
    fun encode(): String = JSONObject()
        .put("playback", playback?.toJson())
        .put("cache", cache.toJson())
        .put("searchState", searchState.name)
        .put("searchCandidates", LyricsManualSearchCandidate.encodeList(searchCandidates))
        .toString()

    companion object {
        fun decode(payload: String?): LyricsSettingsRuntimeState? {
            if (payload.isNullOrBlank()) return null
            val value = runCatching { JSONObject(payload) }.getOrNull() ?: return null
            val searchState = runCatching {
                LyricsManualSearchState.valueOf(value.optString("searchState"))
            }.getOrNull() ?: LyricsManualSearchState.IDLE
            return LyricsSettingsRuntimeState(
                playback = LyricsPlaybackIdentity.fromJson(value.optJSONObject("playback")),
                cache = LyricsCacheSnapshot.fromJson(value.optJSONObject("cache")),
                searchState = searchState,
                searchCandidates = LyricsManualSearchCandidate.decodeList(
                    value.optString("searchCandidates")
                )
            )
        }
    }
}

internal data class LyricsManualSearchCandidate(
    val token: String,
    val snapshot: LyricsCandidateSnapshot
) {
    fun toJson(): JSONObject = snapshot.toJson().put("token", token)

    companion object {
        fun fromJson(value: JSONObject?): LyricsManualSearchCandidate? {
            value ?: return null
            val token = value.optString("token")
            if (token.isBlank()) return null
            val snapshot = LyricsCandidateSnapshot.fromJson(value) ?: return null
            return LyricsManualSearchCandidate(token, snapshot)
        }

        fun encodeList(candidates: List<LyricsManualSearchCandidate>): String =
            JSONArray().apply { candidates.forEach { put(it.toJson()) } }.toString()

        fun decodeList(value: String?): List<LyricsManualSearchCandidate> {
            if (value.isNullOrBlank()) return emptyList()
            val array = runCatching { JSONArray(value) }.getOrNull() ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    fromJson(array.optJSONObject(index))?.let(::add)
                }
            }
        }
    }
}

internal object LyricsManualSearchPolicy {
    const val MAX_RESULTS_PER_SOURCE = 8
    const val MAX_RESULTS = 24

    fun rank(query: LyricsLookup, candidates: Iterable<LyricsResult>): List<LyricsResult> =
        candidates.asSequence()
            .filter { it.source.isNotBlank() && it.sourceId.isNotBlank() }
            .distinctBy(::identity)
            .groupBy(LyricsResult::source)
            .values
            .flatMap { sourceCandidates ->
                sourceCandidates.sortedWith(candidateComparator(query))
                    .take(MAX_RESULTS_PER_SOURCE)
            }
            .sortedWith(candidateComparator(query))
            .take(MAX_RESULTS)

    fun token(candidate: LyricsResult): String = "${candidate.source}\u0000${candidate.sourceId}"

    private fun candidateComparator(query: LyricsLookup): Comparator<LyricsResult> =
        compareByDescending<LyricsResult> { metadataScore(query, it) }
            .thenBy { durationDelta(query.durationMs, it.durationMs) }
            .thenBy { it.candidateTrack.lowercase() }
            .thenBy { it.source }

    private fun metadataScore(query: LyricsLookup, candidate: LyricsResult): Int {
        val queryTrack = normalizeText(query.track)
        val queryArtist = normalizeText(query.artist)
        val queryAlbum = normalizeText(query.album)
        val candidateTrack = normalizeText(candidate.candidateTrack)
        val candidateArtist = normalizeText(candidate.candidateArtist)
        val candidateAlbum = normalizeText(candidate.candidateAlbum)
        return listOf(
            queryTrack to candidateTrack,
            queryArtist to candidateArtist,
            queryAlbum to candidateAlbum
        ).fold(0) { score, (expected, actual) ->
            score + when {
                expected.isBlank() -> 0
                expected == actual -> 4
                actual.contains(expected) || expected.contains(actual) -> 2
                else -> 0
            }
        }
    }

    private fun durationDelta(expected: Long, actual: Long): Long =
        if (LyricsCandidateSelector.hasKnownDuration(expected) &&
            LyricsCandidateSelector.hasKnownDuration(actual)
        ) {
            abs(expected - actual)
        } else {
            Long.MAX_VALUE
        }

    private fun identity(candidate: LyricsResult): String =
        "${candidate.source}\u0000${candidate.sourceId}"
}
