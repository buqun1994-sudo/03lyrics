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
        if (!LyricsCandidateSelector.canConfirm(query)) return Result()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LYRICS_DEADLINE_MS)
        val catalogDeadline = minOf(
            deadline,
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CATALOG_DEADLINE_MS)
        )
        val candidates = collectCatalogCandidates(
            catalogDeadline,
            listOf(
                Callable { searchLrcLib(query, catalogDeadline) },
                Callable { searchQqMusic(query, catalogDeadline) },
                Callable { searchNetEase(query, catalogDeadline) }
            )
        )
        val confirmed = LyricsCandidateSelector.selectCandidates(query, candidates)
        val selected = loadConfirmedLyrics(confirmed, deadline)
        Log.i(
            LOG_TAG,
            "Lyrics catalogCandidates=${candidates.size} confirmed=${confirmed.size} " +
                "selected=${selected?.source ?: "none"}"
        )
        return selected ?: Result()
    }

    fun resolveCover(track: String, artist: String): String {
        val query = LyricsLookup(track, artist)
        if (!LyricsCandidateSelector.canFindCover(query)) return ""
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COVER_DEADLINE_MS)
        val candidates = collectCatalogCandidates(
            deadline,
            listOf(
                Callable { searchQqMusic(query, deadline) },
                Callable { searchNetEase(query, deadline) }
            )
        )
        return LyricsCandidateSelector.selectCoverCandidate(query, candidates)?.cover.orEmpty()
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun collectCatalogCandidates(
        deadlineNanos: Long,
        tasks: List<Callable<List<Result>>>
    ): List<Result> {
        val completion = ExecutorCompletionService<List<Result>>(executor)
        val futures = tasks.map(completion::submit)
        val candidates = mutableListOf<Result>()

        try {
            var completed = 0
            while (completed < futures.size) {
                val remaining = deadlineNanos - System.nanoTime()
                if (remaining <= 0L) break
                val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                completed += 1
                candidates += runCatching { future.get() }
                    .onFailure { Log.w(LOG_TAG, "Lyrics catalog query failed", it) }
                    .getOrDefault(emptyList())
            }
        } finally {
            futures.forEach { it.cancel(true) }
        }
        return candidates
    }

    private fun searchLrcLib(query: LyricsLookup, deadlineNanos: Long): List<Result> {
        val url = "https://lrclib.net/api/search?track_name=${encode(query.track)}&artist_name=${encode(query.artist)}"
        val list = JSONArray(getText(url, mapOf("Accept" to "application/json"), deadlineNanos))
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val lyrics = cleanLyrics(item.contentString("syncedLyrics"))
                if (classifyLyrics(lyrics) != LyricsKind.SYNCHRONIZED) continue
                add(
                    Result(
                        lyrics = lyrics,
                        durationMs = (item.optDouble("duration", 0.0) * 1000.0).toLong(),
                        source = SOURCE_LRCLIB,
                        sourceId = item.contentString("id"),
                        candidateTrack = item.contentString("trackName"),
                        candidateArtist = item.contentString("artistName"),
                        candidateAlbum = item.contentString("albumName"),
                        lyricsKind = LyricsKind.SYNCHRONIZED
                    )
                )
            }
        }
    }

    private fun searchQqMusic(query: LyricsLookup, deadlineNanos: Long): List<Result> {
        val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp" +
            "?format=json&p=1&n=8&w=${encode("${query.track} ${query.artist}".trim())}"
        val root = JSONObject(getText(searchUrl, qqHeaders(), deadlineNanos))
        val songs = root.optJSONObject("data")
            ?.optJSONObject("song")
            ?.optJSONArray("list") ?: return emptyList()
        return buildList {
            for (index in 0 until songs.length()) {
                val song = songs.optJSONObject(index) ?: continue
                val songMid = song.contentString("songmid")
                if (songMid.isBlank()) continue
                add(
                    Result(
                        durationMs = song.optLong("interval", 0L) * 1000L,
                        cover = qqCover(song),
                        source = SOURCE_QQ,
                        sourceId = songMid,
                        candidateTrack = song.contentString("songname").ifBlank {
                            song.contentString("songorig")
                        },
                        candidateArtist = song.optJSONArray("singer").joinNames("name"),
                        candidateAlbum = song.contentString("albumname")
                    )
                )
            }
        }
    }

    private fun searchNetEase(query: LyricsLookup, deadlineNanos: Long): List<Result> {
        val searchUrl = "https://music.163.com/api/search/get/web" +
            "?type=1&limit=8&s=${encode("${query.track} ${query.artist}".trim())}"
        val root = JSONObject(getText(searchUrl, netEaseHeaders(), deadlineNanos))
        val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        return buildList {
            for (index in 0 until songs.length()) {
                val song = songs.optJSONObject(index) ?: continue
                val songId = song.optLong("id", 0L)
                if (songId <= 0L) continue
                val album = song.optJSONObject("album") ?: song.optJSONObject("al")
                add(
                    Result(
                        durationMs = song.optLong("duration", song.optLong("dt", 0L)),
                        cover = album.contentString("picUrl"),
                        source = SOURCE_NETEASE,
                        sourceId = songId.toString(),
                        candidateTrack = song.contentString("name"),
                        candidateArtist = (song.optJSONArray("artists") ?: song.optJSONArray("ar"))
                            .joinNames("name"),
                        candidateAlbum = album.contentString("name")
                    )
                )
            }
        }
    }

    private fun loadConfirmedLyrics(candidates: List<Result>, deadlineNanos: Long): Result? {
        val results = MutableList<Result?>(candidates.size) { null }
        val completion = ExecutorCompletionService<IndexedValue<Result?>>(executor)
        val futures = candidates.mapIndexedNotNull { index, candidate ->
            if (candidate.source == SOURCE_LRCLIB) {
                results[index] = candidate.takeIf {
                    classifyLyrics(it.lyrics) == LyricsKind.SYNCHRONIZED
                }
                null
            } else {
                completion.submit(
                    Callable { IndexedValue(index, loadCandidateLyrics(candidate, deadlineNanos)) }
                )
            }
        }
        try {
            repeat(futures.size) {
                val remaining = deadlineNanos - System.nanoTime()
                if (remaining <= 0L) return@repeat
                val loaded = completion.poll(remaining, TimeUnit.NANOSECONDS)
                    ?.let { future ->
                        runCatching { future.get() }
                            .onFailure { Log.w(LOG_TAG, "Lyrics source lookup failed", it) }
                            .getOrNull()
                    }
                if (loaded != null) results[loaded.index] = loaded.value
            }
        } finally {
            futures.forEach { it.cancel(true) }
        }
        return results.firstOrNull { it != null }
    }

    private fun loadCandidateLyrics(candidate: Result, deadlineNanos: Long): Result? = when (candidate.source) {
        SOURCE_LRCLIB -> candidate.takeIf { classifyLyrics(it.lyrics) == LyricsKind.SYNCHRONIZED }
        SOURCE_QQ -> loadQqLyrics(candidate, deadlineNanos)
        SOURCE_NETEASE -> loadNetEaseLyrics(candidate, deadlineNanos)
        else -> null
    }

    private fun loadQqLyrics(candidate: Result, deadlineNanos: Long): Result? {
        if (candidate.sourceId.isBlank()) return null
        val lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
            "?songmid=${encode(candidate.sourceId)}&format=json&nobase64=1"
        val lyricRoot = parseJsonFlexible(getBytes(lyricUrl, qqHeaders(), deadlineNanos)) ?: return null
        val lyrics = cleanLyrics(unescapeHtml(lyricRoot.contentString("lyric")))
        return candidate.takeIf { classifyLyrics(lyrics) == LyricsKind.SYNCHRONIZED }
            ?.copy(lyrics = lyrics, lyricsKind = LyricsKind.SYNCHRONIZED)
    }

    private fun loadNetEaseLyrics(candidate: Result, deadlineNanos: Long): Result? {
        if (candidate.sourceId.isBlank()) return null
        val lyricUrl = "https://music.163.com/api/song/lyric?os=pc&id=${encode(candidate.sourceId)}" +
            "&lv=-1&kv=-1&tv=-1"
        val lyricRoot = JSONObject(getText(lyricUrl, netEaseHeaders(), deadlineNanos))
        val lyrics = cleanLyrics(lyricRoot.optJSONObject("lrc").contentString("lyric"))
        return candidate.takeIf { classifyLyrics(lyrics) == LyricsKind.SYNCHRONIZED }
            ?.copy(lyrics = lyrics, lyricsKind = LyricsKind.SYNCHRONIZED)
    }

    private fun qqCover(song: JSONObject): String {
        val albumMid = song.contentString("albummid")
        val albumId = song.optLong("albumid", 0L)
        return when {
            albumMid.isNotBlank() && !albumMid.all(Char::isDigit) ->
                "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albumMid.jpg"
            albumId > 0L ->
                "https://y.gtimg.cn/music/photo/album_500/${albumId % 100}/500_albumpic_${albumId}_0.jpg"
            else -> ""
        }
    }

    private fun qqHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Referer" to "https://y.qq.com/",
        "User-Agent" to USER_AGENT
    )

    private fun netEaseHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Referer" to "https://music.163.com/",
        "User-Agent" to USER_AGENT
    )

    private fun JSONArray?.joinNames(key: String): String {
        if (this == null) return ""
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.contentString(key)?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }.joinToString("/")
    }

    private fun getText(
        url: String,
        headers: Map<String, String>,
        deadlineNanos: Long? = null
    ): String = getBytes(url, headers, deadlineNanos).toString(Charsets.UTF_8)

    private fun getBytes(
        url: String,
        headers: Map<String, String>,
        deadlineNanos: Long? = null
    ): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            val remainingMs = remainingTimeoutMs(deadlineNanos)
            connection.requestMethod = "GET"
            connection.connectTimeout = minOf(CONNECT_TIMEOUT_MS, remainingMs)
            connection.readTimeout = minOf(READ_TIMEOUT_MS, remainingMs)
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
                    remainingTimeoutMs(deadlineNanos)
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

    private fun remainingTimeoutMs(deadlineNanos: Long?): Int {
        if (deadlineNanos == null) return READ_TIMEOUT_MS
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0L) throw IllegalStateException("Lyrics lookup deadline reached")
        return TimeUnit.NANOSECONDS.toMillis(remaining)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
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

    companion object {
        private const val LOG_TAG = "DesktopLyrics"
        private const val SOURCE_LRCLIB = "LRCLIB"
        private const val SOURCE_QQ = "QQ音乐"
        private const val SOURCE_NETEASE = "网易云音乐"
        private const val CONNECT_TIMEOUT_MS = 1_500
        private const val READ_TIMEOUT_MS = 2_200
        private const val CATALOG_DEADLINE_MS = 1_800L
        private const val LYRICS_DEADLINE_MS = 3_800L
        private const val COVER_DEADLINE_MS = 3_000L
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
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

enum class LyricsKind {
    NONE,
    PLAIN,
    SYNCHRONIZED
}

internal object LyricsCandidateSelector {
    private const val MINIMUM_DURATION_MS = 1_000L
    internal const val MAX_DURATION_DELTA_MS = 2_000L

    fun canConfirm(query: LyricsLookup): Boolean =
        hasKnownDuration(query.durationMs) &&
            titleIdentity(query.track).isValid &&
            isValidArtist(query.artist)

    fun hasKnownDuration(durationMs: Long): Boolean = durationMs >= MINIMUM_DURATION_MS

    fun hasMatchingDuration(firstDurationMs: Long, secondDurationMs: Long): Boolean =
        hasKnownDuration(firstDurationMs) &&
            hasKnownDuration(secondDurationMs) &&
            abs(firstDurationMs - secondDurationMs) <= MAX_DURATION_DELTA_MS

    fun selectCandidates(
        query: LyricsLookup,
        candidates: Iterable<DirectLyricsRepository.Result>
    ): List<DirectLyricsRepository.Result> {
        if (!canConfirm(query)) return emptyList()
        val matched = candidates.asSequence()
            .filter { matchesVersion(query, it) }
            .distinctBy(::candidateIdentity)
            .toList()
        if (matched.isEmpty()) return emptyList()

        val versions = mutableListOf<MutableList<DirectLyricsRepository.Result>>()
        matched.forEach { candidate ->
            val matchingVersion = versions.firstOrNull { version ->
                version.all { candidatesForSameVersion(it, candidate) }
            }
            if (matchingVersion == null) {
                versions += mutableListOf(candidate)
            } else {
                matchingVersion += candidate
            }
        }
        if (versions.size != 1) return emptyList()
        return versions.single().sortedWith(
            compareBy<DirectLyricsRepository.Result> { abs(query.durationMs - it.durationMs) }
                .thenBy { it.source }
                .thenBy { it.sourceId }
        )
    }

    fun matchesVersion(query: LyricsLookup, candidate: DirectLyricsRepository.Result): Boolean {
        if (!canConfirm(query) || !hasKnownDuration(candidate.durationMs) ||
            !isValidArtist(candidate.candidateArtist)
        ) {
            return false
        }
        if (!titlesMatch(query.track, candidate.candidateTrack)) return false
        if (!hasMatchingDuration(query.durationMs, candidate.durationMs)) return false

        val wantedAlbum = normalizedAlbum(query.album)
        val foundAlbum = normalizedAlbum(candidate.candidateAlbum)
        return if (wantedAlbum.isNotBlank() && foundAlbum.isNotBlank()) {
            wantedAlbum == foundAlbum
        } else {
            directArtistMatch(query.artist, candidate.candidateArtist)
        }
    }

    fun canFindCover(query: LyricsLookup): Boolean =
        titleIdentity(query.track).isValid && isValidArtist(query.artist)

    fun selectCoverCandidate(
        query: LyricsLookup,
        candidates: Iterable<DirectLyricsRepository.Result>
    ): DirectLyricsRepository.Result? {
        if (!canFindCover(query)) return null
        val matched = candidates.asSequence()
            .filter { it.cover.isNotBlank() }
            .filter { titlesMatch(query.track, it.candidateTrack) }
            .filter { directArtistMatch(query.artist, it.candidateArtist) }
            .distinctBy(::candidateIdentity)
            .sortedWith(
                compareBy<DirectLyricsRepository.Result> { it.source }
                    .thenBy { it.sourceId }
            )
            .toList()
        val anchor = matched.firstOrNull() ?: return null
        return anchor.takeIf { matched.all { candidate -> coversSameRelease(anchor, candidate) } }
    }

    private fun candidatesForSameVersion(
        first: DirectLyricsRepository.Result,
        second: DirectLyricsRepository.Result
    ): Boolean {
        if (!titlesMatch(first.candidateTrack, second.candidateTrack)) return false
        if (!hasMatchingDuration(first.durationMs, second.durationMs)) return false
        return sameRelease(first, second)
    }

    private fun coversSameRelease(
        first: DirectLyricsRepository.Result,
        second: DirectLyricsRepository.Result
    ): Boolean =
        titlesMatch(first.candidateTrack, second.candidateTrack) && sameRelease(first, second)

    private fun sameRelease(
        first: DirectLyricsRepository.Result,
        second: DirectLyricsRepository.Result
    ): Boolean {
        val firstAlbum = normalizedAlbum(first.candidateAlbum)
        val secondAlbum = normalizedAlbum(second.candidateAlbum)
        return if (firstAlbum.isNotBlank() && secondAlbum.isNotBlank()) {
            firstAlbum == secondAlbum
        } else {
            directArtistMatch(first.candidateArtist, second.candidateArtist)
        }
    }

    private fun titlesMatch(first: String, second: String): Boolean {
        val firstTitle = titleIdentity(first)
        val secondTitle = titleIdentity(second)
        return firstTitle.isValid && secondTitle.isValid &&
            firstTitle.base == secondTitle.base && firstTitle.qualifier == secondTitle.qualifier
    }

    private fun titleIdentity(value: String): TitleIdentity {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val qualifiers = BRACKET_PATTERN.findAll(normalized)
            .map { it.groupValues[1] }
            .map(::normalizeQualifier)
            .filter(String::isNotBlank)
            .toList()
        return TitleIdentity(
            base = normalizeText(normalized.replace(BRACKET_PATTERN, " ")),
            qualifier = qualifiers.joinToString(" ")
        )
    }

    private fun artistNames(value: String): List<String> = value
        .split(ARTIST_SEPARATOR_PATTERN)
        .map(::normalizeText)
        .filter(String::isNotBlank)

    private fun directArtistMatch(first: String, second: String): Boolean {
        if (!isValidArtist(first) || !isValidArtist(second)) return false
        return artistNames(first).toSet() == artistNames(second).toSet()
    }

    private fun isValidArtist(value: String): Boolean = normalizeText(value).let {
        it.isNotBlank() && it !in PLACEHOLDER_ARTISTS
    }

    private fun normalizeText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun normalizedAlbum(value: String): String = normalizeText(value)
        .takeUnless { it in PLACEHOLDER_ALBUMS }
        .orEmpty()

    private fun normalizeQualifier(value: String): String = normalizeText(
        value.replace(QUALIFIER_DECORATION_PATTERN, " ")
    )

    private fun candidateIdentity(candidate: DirectLyricsRepository.Result): String =
        if (candidate.source.isNotBlank() && candidate.sourceId.isNotBlank()) {
            "${candidate.source}\u0000${candidate.sourceId}"
        } else {
            listOf(
                candidate.candidateTrack,
                candidate.candidateArtist,
                candidate.candidateAlbum,
                candidate.durationMs.toString()
            ).joinToString("\u0000")
        }

    private data class TitleIdentity(
        val base: String,
        val qualifier: String
    ) {
        val isValid: Boolean get() = base.isNotBlank()
    }

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
    private val PLACEHOLDER_ALBUMS = setOf(
        "unknown",
        "unkown",
        "null",
        "undefined",
        "未知",
        "未知专辑"
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
