package com.tcrrry.desktoplyrics

import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Resolves lyrics directly from public music services. No request is routed through
 * a Lobsta/Tcrrry-owned server. MediaSession artwork remains the preferred cover;
 * QQ Music and NetEase artwork are only used when the player did not publish one.
 */
class DirectLyricsRepository {
    data class Result(
        val lyrics: String = "",
        val durationMs: Long = 0L,
        val cover: String = "",
        val source: String = "",
        val sourceId: String = "",
        val score: Int = 0,
        val candidateTrack: String = "",
        val candidateArtist: String = "",
        val candidateAlbum: String = "",
        val lyricsKind: LyricsKind = LyricsKind.NONE
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("lyrics", lyrics)
            .put("duration", durationMs)
            .put("cover", cover)
            .put("source", source)
            .put("sourceId", sourceId)
            .put("candidateTrack", candidateTrack)
            .put("candidateArtist", candidateArtist)
            .put("candidateAlbum", candidateAlbum)
    }

    private val executor = Executors.newFixedThreadPool(6) { runnable ->
        Thread(runnable, "direct-lyrics").apply { isDaemon = true }
    }

    fun resolveLyrics(
        track: String,
        artist: String,
        album: String = "",
        durationMs: Long = 0L
    ): Result {
        val query = LyricsLookup(track, artist, album, durationMs)
        val completion = ExecutorCompletionService<Result?>(executor)
        val futures = listOf(
            completion.submit(Callable { queryLrcLib(query) }),
            completion.submit(Callable { queryQqMusic(query, includeLyrics = true) }),
            completion.submit(Callable { queryNetEase(query, includeLyrics = true) })
        )
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LYRICS_DEADLINE_MS)
        val candidates = mutableListOf<Result>()

        try {
            repeat(futures.size) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return@repeat
                val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: return@repeat
                val candidate = runCatching { future.get() }
                    .onFailure { Log.w(LOG_TAG, "Lyrics source query failed", it) }
                    .getOrNull()
                if (candidate != null) candidates += candidate
            }
        } finally {
            futures.forEach { it.cancel(true) }
        }
        val selected = LyricsCandidateSelector.select(
            query,
            candidates,
            minimumKind = LyricsKind.SYNCHRONIZED
        )
        Log.i(
            LOG_TAG,
            "Lyrics candidates=${candidates.joinToString { "${it.source}:${it.lyricsKind}:${it.score}" }} " +
                "selected=${selected?.source ?: "none"}"
        )
        return selected ?: Result()
    }

    fun resolveCover(track: String, artist: String): String {
        val query = LyricsLookup(track, artist)
        val completion = ExecutorCompletionService<Result?>(executor)
        val futures = listOf(
            completion.submit(Callable { queryQqMusic(query, includeLyrics = false) }),
            completion.submit(Callable { queryNetEase(query, includeLyrics = false) })
        )
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COVER_DEADLINE_MS)
        var best: Result? = null

        try {
            repeat(futures.size) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return@repeat
                val candidate = completion.poll(remaining, TimeUnit.NANOSECONDS)
                    ?.let { runCatching { it.get() }.getOrNull() }
                    ?.takeIf { it.cover.isNotBlank() && it.score >= MIN_ACCEPTABLE_SCORE }
                if (candidate != null && (best == null || candidate.score > best?.score ?: Int.MIN_VALUE)) {
                    best = candidate
                }
            }
        } finally {
            futures.forEach { it.cancel(true) }
        }
        return best?.cover.orEmpty()
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun queryLrcLib(query: LyricsLookup): Result? {
        val url = "https://lrclib.net/api/search?track_name=${encode(query.track)}&artist_name=${encode(query.artist)}"
        val list = JSONArray(getText(url, mapOf("Accept" to "application/json")))
        val candidates = mutableListOf<Result>()
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val syncedLyrics = item.contentString("syncedLyrics")
            val lyrics = syncedLyrics.ifBlank { item.contentString("plainLyrics") }
            val metadata = LyricsCandidateMetadata(
                track = item.contentString("trackName"),
                artist = item.contentString("artistName"),
                album = item.contentString("albumName"),
                durationMs = (item.optDouble("duration", 0.0) * 1000.0).toLong()
            )
            val match = LyricsCandidateSelector.assess(query, metadata)
            if (!match.accepted || cleanLyrics(lyrics).isBlank()) continue
            candidates += Result(
                lyrics = lyrics,
                durationMs = metadata.durationMs,
                source = "LRCLIB",
                sourceId = item.contentString("id"),
                score = match.score,
                candidateTrack = metadata.track,
                candidateArtist = metadata.artist,
                candidateAlbum = metadata.album,
                lyricsKind = classifyLyrics(lyrics)
            )
        }
        return LyricsCandidateSelector.select(
            query,
            candidates,
            minimumKind = LyricsKind.SYNCHRONIZED
        )
    }

    private fun queryQqMusic(query: LyricsLookup, includeLyrics: Boolean): Result? {
        val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp" +
            "?format=json&p=1&n=8&w=${encode("${query.track} ${query.artist}".trim())}"
        val headers = mapOf(
            "Accept" to "application/json",
            "Referer" to "https://y.qq.com/",
            "User-Agent" to USER_AGENT
        )
        val root = JSONObject(getText(searchUrl, headers))
        val songs = root.optJSONObject("data")
            ?.optJSONObject("song")
            ?.optJSONArray("list") ?: return null
        val match = bestJsonMatch(songs, query) { item ->
            LyricsCandidateMetadata(
                track = item.contentString("songname").ifBlank { item.contentString("songorig") },
                artist = item.optJSONArray("singer").joinNames("name"),
                album = item.contentString("albumname"),
                durationMs = item.optLong("interval", 0L) * 1000L
            )
        } ?: return null

        val song = match.item
        val metadata = match.metadata
        val albumMid = song.contentString("albummid")
        val albumId = song.optLong("albumid", 0L)
        val cover = when {
            albumMid.isNotBlank() && !albumMid.all(Char::isDigit) ->
                "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albumMid.jpg"
            albumId > 0L ->
                "https://y.gtimg.cn/music/photo/album_500/${albumId % 100}/500_albumpic_${albumId}_0.jpg"
            else -> ""
        }
        if (!includeLyrics) {
            return Result(
                cover = cover,
                source = "QQ音乐",
                score = match.assessment.score,
                candidateTrack = metadata.track,
                candidateArtist = metadata.artist,
                candidateAlbum = metadata.album
            )
        }

        val songMid = song.contentString("songmid")
        if (songMid.isBlank()) return null
        val lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
            "?songmid=${encode(songMid)}&format=json&nobase64=1"
        val lyricRoot = parseJsonFlexible(getBytes(lyricUrl, headers)) ?: return null
        val lyrics = cleanLyrics(unescapeHtml(lyricRoot.contentString("lyric")))
        if (lyrics.isBlank()) return null
        return Result(
            lyrics = lyrics,
            durationMs = metadata.durationMs,
            cover = cover,
            source = "QQ音乐",
            sourceId = songMid,
            score = match.assessment.score,
            candidateTrack = metadata.track,
            candidateArtist = metadata.artist,
            candidateAlbum = metadata.album,
            lyricsKind = classifyLyrics(lyrics)
        )
    }

    private fun queryNetEase(query: LyricsLookup, includeLyrics: Boolean): Result? {
        val searchUrl = "https://music.163.com/api/search/get/web" +
            "?type=1&limit=8&s=${encode("${query.track} ${query.artist}".trim())}"
        val headers = mapOf(
            "Accept" to "application/json",
            "Referer" to "https://music.163.com/",
            "User-Agent" to USER_AGENT
        )
        val root = JSONObject(getText(searchUrl, headers))
        val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return null
        val match = bestJsonMatch(songs, query) { item ->
            val album = item.optJSONObject("album") ?: item.optJSONObject("al")
            LyricsCandidateMetadata(
                track = item.contentString("name"),
                artist = (item.optJSONArray("artists") ?: item.optJSONArray("ar")).joinNames("name"),
                album = album.contentString("name"),
                durationMs = item.optLong("duration", item.optLong("dt", 0L))
            )
        } ?: return null

        val song = match.item
        val metadata = match.metadata
        val album = song.optJSONObject("album") ?: song.optJSONObject("al")
        var cover = album.contentString("picUrl")
        val songId = song.optLong("id", 0L)

        if (cover.isBlank() && songId > 0L) {
            val detailUrl = "https://music.163.com/api/song/detail/?id=$songId&ids=[$songId]"
            val detail = runCatching { JSONObject(getText(detailUrl, headers)) }.getOrNull()
            val detailSong = detail?.optJSONArray("songs")?.optJSONObject(0)
            cover = (detailSong?.optJSONObject("album") ?: detailSong?.optJSONObject("al"))
                .contentString("picUrl")
        }
        if (!includeLyrics) {
            return Result(
                cover = cover,
                source = "网易云音乐",
                score = match.assessment.score,
                candidateTrack = metadata.track,
                candidateArtist = metadata.artist,
                candidateAlbum = metadata.album
            )
        }
        if (songId <= 0L) return null

        val lyricUrl = "https://music.163.com/api/song/lyric?os=pc&id=$songId&lv=-1&kv=-1&tv=-1"
        val lyricRoot = JSONObject(getText(lyricUrl, headers))
        val lyrics = cleanLyrics(lyricRoot.optJSONObject("lrc").contentString("lyric"))
        if (lyrics.isBlank()) return null
        return Result(
            lyrics = lyrics,
            durationMs = metadata.durationMs,
            cover = cover,
            source = "网易云音乐",
            sourceId = songId.toString(),
            score = match.assessment.score,
            candidateTrack = metadata.track,
            candidateArtist = metadata.artist,
            candidateAlbum = metadata.album,
            lyricsKind = classifyLyrics(lyrics)
        )
    }

    private fun bestJsonMatch(
        array: JSONArray,
        query: LyricsLookup,
        fields: (JSONObject) -> LyricsCandidateMetadata
    ): JsonMatch? {
        var best: JsonMatch? = null
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val metadata = fields(item)
            val assessment = LyricsCandidateSelector.assess(query, metadata)
            if (!assessment.accepted) continue
            val candidate = JsonMatch(item, metadata, assessment)
            if (best == null || candidate.assessment.score > best.assessment.score) {
                best = candidate
            }
        }
        return best
    }

    private fun JSONArray?.joinNames(key: String): String {
        if (this == null) return ""
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.contentString(key)?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }.joinToString("/")
    }

    private fun getText(url: String, headers: Map<String, String>): String =
        getBytes(url, headers).toString(Charsets.UTF_8)

    private fun getBytes(url: String, headers: Map<String, String>): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.useCaches = true
            headers.forEach(connection::setRequestProperty)
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("HTTP $status")
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) throw IllegalStateException("Response too large")
                    output.write(buffer, 0, read)
                }
                return output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseJsonFlexible(bytes: ByteArray): JSONObject? {
        return runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrElse {
            runCatching { JSONObject(bytes.toString(Charset.forName("GBK"))) }.getOrNull()
        }
    }

    private fun unescapeHtml(value: String): String = value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private data class JsonMatch(
        val item: JSONObject,
        val metadata: LyricsCandidateMetadata,
        val assessment: LyricsMatchAssessment
    )

    companion object {
        private const val LOG_TAG = "DesktopLyrics"
        private const val CONNECT_TIMEOUT_MS = 1_500
        private const val READ_TIMEOUT_MS = 2_200
        private const val LYRICS_DEADLINE_MS = 3_800L
        private const val COVER_DEADLINE_MS = 3_000L
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MIN_ACCEPTABLE_SCORE = 50
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
    }
}

internal data class LyricsLookup(
    val track: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L
)

internal data class LyricsCandidateMetadata(
    val track: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L
)

enum class LyricsKind(val priority: Int) {
    NONE(0),
    PLAIN(1),
    SYNCHRONIZED(2)
}

internal data class LyricsMatchAssessment(
    val accepted: Boolean,
    val tier: Int,
    val score: Int
)

internal object LyricsCandidateSelector {
    private const val MIN_TITLE_SIMILARITY = 0.55
    private const val MIN_ARTIST_SIMILARITY = 0.45
    private const val MIN_CANDIDATE_SCORE = 68
    private const val MIN_SELECTION_SCORE = 78
    private const val MIN_WINNER_MARGIN = 6
    private const val SOURCE_CONSENSUS_BONUS = 4

    fun select(
        query: LyricsLookup,
        candidates: Iterable<DirectLyricsRepository.Result>,
        minimumKind: LyricsKind = LyricsKind.PLAIN
    ): DirectLyricsRepository.Result? {
        val ranked = candidates.mapNotNull { candidate ->
            val lyrics = cleanLyrics(candidate.lyrics)
            val kind = classifyLyrics(lyrics)
            if (kind.priority < minimumKind.priority) return@mapNotNull null
            val metadata = LyricsCandidateMetadata(
                track = candidate.candidateTrack,
                artist = candidate.candidateArtist,
                album = candidate.candidateAlbum,
                durationMs = candidate.durationMs
            )
            val assessment = assess(query, metadata)
            if (!assessment.accepted) return@mapNotNull null
            RankedCandidate(
                candidate.copy(lyrics = lyrics, lyricsKind = kind, score = assessment.score),
                assessment
            )
        }.toList()
        val supported = ranked.map { rankedCandidate ->
            val supportingSources = ranked.asSequence()
                .filter { it !== rankedCandidate }
                .filter { it.result.source.isNotBlank() }
                .filter { it.result.source != rankedCandidate.result.source }
                .filter { sameRecording(rankedCandidate.metadata, it.metadata) }
                .map { it.result.source }
                .distinct()
                .count()
            val consensusBonus = supportingSources.coerceAtMost(2) * SOURCE_CONSENSUS_BONUS
            rankedCandidate.copy(
                result = rankedCandidate.result.copy(score = rankedCandidate.assessment.score + consensusBonus),
                finalScore = rankedCandidate.assessment.score + consensusBonus
            )
        }.filter { it.finalScore >= MIN_SELECTION_SCORE }
            .sortedWith(
                compareByDescending<RankedCandidate> { it.finalScore }
                    .thenByDescending { it.result.lyricsKind.priority }
                    .thenBy { it.result.source }
            )

        val winner = supported.firstOrNull() ?: return null
        val nearestDifferentRecording = supported.drop(1)
            .firstOrNull { !sameRecording(winner.metadata, it.metadata) }
        if (nearestDifferentRecording != null &&
            winner.finalScore - nearestDifferentRecording.finalScore < MIN_WINNER_MARGIN
        ) {
            return null
        }
        return winner.result
    }

    fun assess(query: LyricsLookup, candidate: LyricsCandidateMetadata): LyricsMatchAssessment {
        val wantedTitle = titleFingerprint(query.track)
        val foundTitle = titleFingerprint(candidate.track)
        if (wantedTitle.base.isBlank() || foundTitle.base.isBlank()) {
            return LyricsMatchAssessment(accepted = false, tier = 0, score = 0)
        }
        val wantedArtists = artistNames(query.artist)
        val foundArtists = artistNames(candidate.artist)
        if (!isValidArtist(query.artist) || !isValidArtist(candidate.artist) ||
            wantedArtists.isEmpty() || foundArtists.isEmpty()
        ) {
            return LyricsMatchAssessment(accepted = false, tier = 0, score = 0)
        }

        val titleSimilarity = textSimilarity(wantedTitle.base, foundTitle.base)
        val artistSimilarity = artistSimilarity(wantedArtists, foundArtists)
        if (titleSimilarity < MIN_TITLE_SIMILARITY || artistSimilarity < MIN_ARTIST_SIMILARITY) {
            return LyricsMatchAssessment(accepted = false, tier = 0, score = 0)
        }

        val fullTitleSimilarity = textSimilarity(wantedTitle.full, foundTitle.full)
        val qualifierScore = qualifierScore(wantedTitle.qualifier, foundTitle.qualifier)
        val titleScore = (titleSimilarity * 54.0).toInt() +
            (fullTitleSimilarity * 14.0).toInt() + qualifierScore
        val artistScore = (artistSimilarity * 24.0).toInt()
        val albumScore = albumScore(query.album, candidate.album)
        val durationScore = durationScore(query.durationMs, candidate.durationMs)
        val score = titleScore + artistScore + albumScore + durationScore
        return LyricsMatchAssessment(
            accepted = score >= MIN_CANDIDATE_SCORE,
            tier = when {
                score >= 96 -> 3
                score >= MIN_SELECTION_SCORE -> 2
                else -> 1
            },
            score = score
        )
    }

    private fun titleFingerprint(value: String): TitleFingerprint {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val qualifiers = BRACKET_PATTERN.findAll(normalized)
            .map { it.groupValues[1] }
            .map(::normalizeQualifier)
            .filter(String::isNotBlank)
            .toList()
        return TitleFingerprint(
            full = normalizeText(normalized),
            base = normalizeText(normalized.replace(BRACKET_PATTERN, " ")),
            qualifier = qualifiers.joinToString(" ")
        )
    }

    private fun qualifierScore(wanted: String, found: String): Int = when {
        wanted.isBlank() && found.isBlank() -> 4
        wanted.isBlank() || found.isBlank() -> -3
        else -> (textSimilarity(wanted, found) * 36.0).toInt() - 28
    }

    private fun artistNames(value: String): List<String> = value
        .split(ARTIST_SEPARATOR_PATTERN)
        .map(::normalizeText)
        .filter(String::isNotBlank)

    private fun artistSimilarity(wanted: List<String>, found: List<String>): Double {
        if (wanted.isEmpty() || found.isEmpty()) return 0.0
        val recall = wanted.map { wantedName ->
            found.maxOf { foundName -> artistNameSimilarity(wantedName, foundName) }
        }.average()
        val precision = found.map { foundName ->
            wanted.maxOf { wantedName -> artistNameSimilarity(wantedName, foundName) }
        }.average()
        return recall * 0.82 + precision * 0.18
    }

    private fun artistNameSimilarity(wanted: String, found: String): Double = when {
        wanted == found -> 1.0
        minOf(wanted.length, found.length) >= 2 && (wanted in found || found in wanted) -> 0.94
        else -> textSimilarity(wanted, found)
    }

    private fun isValidArtist(value: String): Boolean = normalizeText(value).let {
        it.isNotBlank() && it !in PLACEHOLDER_ARTISTS
    }

    private fun albumScore(wanted: String, found: String): Int {
        val wantedAlbum = normalizeText(wanted)
        val foundAlbum = normalizeText(found)
        if (wantedAlbum.isBlank() || foundAlbum.isBlank()) return 0
        val similarity = textSimilarity(wantedAlbum, foundAlbum)
        return when {
            similarity >= 0.9 -> 12
            similarity >= 0.7 -> 8
            similarity >= 0.5 -> 3
            else -> -4
        }
    }

    private fun durationScore(wanted: Long, found: Long): Int {
        if (wanted <= 0L || found <= 0L) return 0
        return when (abs(wanted - found)) {
            in 0..2_000L -> 12
            in 2_001L..5_000L -> 10
            in 5_001L..12_000L -> 7
            in 12_001L..25_000L -> 2
            in 25_001L..45_000L -> -4
            else -> -10
        }
    }

    private fun sameRecording(
        first: LyricsCandidateMetadata,
        second: LyricsCandidateMetadata
    ): Boolean {
        val firstTitle = titleFingerprint(first.track)
        val secondTitle = titleFingerprint(second.track)
        val titleSimilarity = textSimilarity(firstTitle.base, secondTitle.base)
        val fullTitleSimilarity = textSimilarity(firstTitle.full, secondTitle.full)
        val artists = artistSimilarity(artistNames(first.artist), artistNames(second.artist))
        if (firstTitle.qualifier.isNotBlank() && secondTitle.qualifier.isNotBlank() &&
            textSimilarity(firstTitle.qualifier, secondTitle.qualifier) < 0.5
        ) {
            return false
        }
        if (fullTitleSimilarity >= 0.92 && artists >= 0.75) return true
        if (titleSimilarity < 0.92 || artists < 0.75) return false

        val durationsAgree = first.durationMs > 0L && second.durationMs > 0L &&
            abs(first.durationMs - second.durationMs) <= 12_000L
        val albumsAgree = first.album.isNotBlank() && second.album.isNotBlank() &&
            textSimilarity(normalizeText(first.album), normalizeText(second.album)) >= 0.72
        return durationsAgree || albumsAgree
    }

    private fun textSimilarity(first: String, second: String): Double {
        if (first == second) return if (first.isBlank()) 0.0 else 1.0
        if (first.isBlank() || second.isBlank()) return 0.0
        val longerLength = maxOf(first.length, second.length)
        return 1.0 - levenshteinDistance(first, second).toDouble() / longerLength.toDouble()
    }

    private fun levenshteinDistance(first: String, second: String): Int {
        if (first.isEmpty()) return second.length
        if (second.isEmpty()) return first.length
        var previous = IntArray(second.length + 1) { it }
        var current = IntArray(second.length + 1)
        for (firstIndex in first.indices) {
            current[0] = firstIndex + 1
            for (secondIndex in second.indices) {
                val substitution = previous[secondIndex] +
                    if (first[firstIndex] == second[secondIndex]) 0 else 1
                current[secondIndex + 1] = minOf(
                    current[secondIndex] + 1,
                    previous[secondIndex + 1] + 1,
                    substitution
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[second.length]
    }

    private fun normalizeText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun normalizeQualifier(value: String): String = normalizeText(
        value.replace(QUALIFIER_DECORATION_PATTERN, " ")
    )

    private data class RankedCandidate(
        val result: DirectLyricsRepository.Result,
        val assessment: LyricsMatchAssessment,
        val finalScore: Int = assessment.score
    ) {
        val metadata = LyricsCandidateMetadata(
            track = result.candidateTrack,
            artist = result.candidateArtist,
            album = result.candidateAlbum,
            durationMs = result.durationMs
        )
    }

    private data class TitleFingerprint(
        val full: String,
        val base: String,
        val qualifier: String
    )

    private val BRACKET_PATTERN = Regex("[（(\\[【{]([^）)\\]】}]{1,80})[）)\\]】}]")
    private val ARTIST_SEPARATOR_PATTERN = Regex(
        "\\s*(?:[,，、/&／;；+＋]|\\bfeat(?:uring)?\\.?\\b|\\bft\\.?\\b|\\bwith\\b|\\band\\b)\\s*",
        RegexOption.IGNORE_CASE
    )
    private val QUALIFIER_DECORATION_PATTERN = Regex(
        "\\bver(?:sion)?\\.?\\b|版本|版",
        RegexOption.IGNORE_CASE
    )
    private val PLACEHOLDER_ARTISTS = setOf(
        "unknown",
        "unkown",
        "null",
        "undefined",
        "未知",
        "未知歌手"
    )
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

private fun JSONObject?.contentString(key: String): String {
    val value = this?.opt(key)
    return cleanLyrics(value?.toString())
}

private val TIMESTAMP_PATTERN = Regex("\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?]")
