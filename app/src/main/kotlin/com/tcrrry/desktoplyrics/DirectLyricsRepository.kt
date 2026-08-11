package com.tcrrry.desktoplyrics

import org.json.JSONArray
import org.json.JSONObject
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
        val score: Int = 0
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("lyrics", lyrics)
            .put("duration", durationMs)
            .put("cover", cover)
            .put("source", source)
    }

    private val executor = Executors.newFixedThreadPool(6) { runnable ->
        Thread(runnable, "direct-lyrics").apply { isDaemon = true }
    }

    fun resolveLyrics(track: String, artist: String): Result {
        val completion = ExecutorCompletionService<Result?>(executor)
        val futures = listOf(
            completion.submit(Callable { queryLrcLib(track, artist) }),
            completion.submit(Callable { queryQqMusic(track, artist, includeLyrics = true) }),
            completion.submit(Callable { queryNetEase(track, artist, includeLyrics = true) })
        )
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LYRICS_DEADLINE_MS)
        var best: Result? = null

        try {
            repeat(futures.size) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return@repeat
                val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: return@repeat
                val candidate = runCatching { future.get() }.getOrNull()
                    ?.takeIf { it.lyrics.isNotBlank() && it.score >= MIN_ACCEPTABLE_SCORE }
                if (candidate != null && (best == null || candidate.score > best!!.score)) {
                    best = candidate
                }
                if (candidate != null && candidate.score >= EXACT_MATCH_SCORE) {
                    return candidate
                }
            }
        } finally {
            futures.forEach { it.cancel(true) }
        }
        return best ?: Result()
    }

    fun resolveCover(track: String, artist: String): String {
        val completion = ExecutorCompletionService<Result?>(executor)
        val futures = listOf(
            completion.submit(Callable { queryQqMusic(track, artist, includeLyrics = false) }),
            completion.submit(Callable { queryNetEase(track, artist, includeLyrics = false) })
        )
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COVER_DEADLINE_MS)
        var best: Result? = null

        try {
            repeat(futures.size) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return@repeat
                val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: return@repeat
                val candidate = runCatching { future.get() }.getOrNull()
                    ?.takeIf { it.cover.isNotBlank() && it.score >= MIN_ACCEPTABLE_SCORE }
                if (candidate != null && (best == null || candidate.score > best!!.score)) {
                    best = candidate
                }
                if (candidate != null && candidate.score >= EXACT_MATCH_SCORE) {
                    return candidate.cover
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

    private fun queryLrcLib(track: String, artist: String): Result? {
        val url = "https://lrclib.net/api/search?track_name=${encode(track)}&artist_name=${encode(artist)}"
        val list = JSONArray(getText(url, mapOf("Accept" to "application/json")))
        var best: Result? = null
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val lyrics = item.optString("syncedLyrics").ifBlank { item.optString("plainLyrics") }
            if (lyrics.isBlank()) continue
            val synced = item.optString("syncedLyrics").isNotBlank()
            val score = matchScore(
                track,
                artist,
                item.optString("trackName"),
                item.optString("artistName")
            ) + if (synced) 5 else 0
            val result = Result(
                lyrics = lyrics,
                durationMs = (item.optDouble("duration", 0.0) * 1000.0).toLong(),
                source = "LRCLIB",
                score = score
            )
            if (best == null || result.score > best.score) best = result
        }
        return best
    }

    private fun queryQqMusic(track: String, artist: String, includeLyrics: Boolean): Result? {
        val query = "$track $artist".trim()
        val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp" +
            "?format=json&p=1&n=8&w=${encode(query)}"
        val headers = mapOf(
            "Accept" to "application/json",
            "Referer" to "https://y.qq.com/",
            "User-Agent" to USER_AGENT
        )
        val root = JSONObject(getText(searchUrl, headers))
        val songs = root.optJSONObject("data")
            ?.optJSONObject("song")
            ?.optJSONArray("list") ?: return null
        val song = bestJsonMatch(songs, track, artist) { item ->
            val singers = item.optJSONArray("singer").joinNames("name")
            Triple(item.optString("songname").ifBlank { item.optString("songorig") }, singers, item)
        } ?: return null

        val title = song.optString("songname").ifBlank { song.optString("songorig") }
        val singer = song.optJSONArray("singer").joinNames("name")
        val score = matchScore(track, artist, title, singer)
        val albumMid = song.optString("albummid")
        val albumId = song.optLong("albumid", 0L)
        val cover = when {
            albumMid.isNotBlank() && !albumMid.all(Char::isDigit) ->
                "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albumMid.jpg"
            albumId > 0L ->
                "https://y.gtimg.cn/music/photo/album_500/${albumId % 100}/500_albumpic_${albumId}_0.jpg"
            else -> ""
        }
        if (!includeLyrics) return Result(cover = cover, source = "QQ音乐", score = score)

        val songMid = song.optString("songmid")
        if (songMid.isBlank()) return null
        val lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
            "?songmid=${encode(songMid)}&format=json&nobase64=1"
        val lyricRoot = parseJsonFlexible(getBytes(lyricUrl, headers)) ?: return null
        val lyrics = unescapeHtml(lyricRoot.optString("lyric"))
        if (lyrics.isBlank()) return null
        return Result(
            lyrics = lyrics,
            durationMs = song.optLong("interval", 0L) * 1000L,
            cover = cover,
            source = "QQ音乐",
            score = score + 5
        )
    }

    private fun queryNetEase(track: String, artist: String, includeLyrics: Boolean): Result? {
        val query = "$track $artist".trim()
        val searchUrl = "https://music.163.com/api/search/get/web" +
            "?type=1&limit=8&s=${encode(query)}"
        val headers = mapOf(
            "Accept" to "application/json",
            "Referer" to "https://music.163.com/",
            "User-Agent" to USER_AGENT
        )
        val root = JSONObject(getText(searchUrl, headers))
        val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return null
        val song = bestJsonMatch(songs, track, artist) { item ->
            val artists = (item.optJSONArray("artists") ?: item.optJSONArray("ar")).joinNames("name")
            Triple(item.optString("name"), artists, item)
        } ?: return null

        val title = song.optString("name")
        val singer = (song.optJSONArray("artists") ?: song.optJSONArray("ar")).joinNames("name")
        val score = matchScore(track, artist, title, singer)
        val album = song.optJSONObject("album") ?: song.optJSONObject("al")
        var cover = album?.optString("picUrl").orEmpty()
        val songId = song.optLong("id", 0L)

        if (cover.isBlank() && songId > 0L) {
            val detailUrl = "https://music.163.com/api/song/detail/?id=$songId&ids=[$songId]"
            val detail = runCatching { JSONObject(getText(detailUrl, headers)) }.getOrNull()
            val detailSong = detail?.optJSONArray("songs")?.optJSONObject(0)
            cover = (detailSong?.optJSONObject("album") ?: detailSong?.optJSONObject("al"))
                ?.optString("picUrl").orEmpty()
        }
        if (!includeLyrics) return Result(cover = cover, source = "网易云音乐", score = score)
        if (songId <= 0L) return null

        val lyricUrl = "https://music.163.com/api/song/lyric?os=pc&id=$songId&lv=-1&kv=-1&tv=-1"
        val lyricRoot = JSONObject(getText(lyricUrl, headers))
        val lyrics = lyricRoot.optJSONObject("lrc")?.optString("lyric").orEmpty()
        if (lyrics.isBlank()) return null
        return Result(
            lyrics = lyrics,
            durationMs = song.optLong("duration", song.optLong("dt", 0L)),
            cover = cover,
            source = "网易云音乐",
            score = score + 5
        )
    }

    private fun bestJsonMatch(
        array: JSONArray,
        track: String,
        artist: String,
        fields: (JSONObject) -> Triple<String, String, JSONObject>
    ): JSONObject? {
        var best: JSONObject? = null
        var bestScore = Int.MIN_VALUE
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val (title, singer, value) = fields(item)
            val score = matchScore(track, artist, title, singer)
            if (score > bestScore) {
                best = value
                bestScore = score
            }
        }
        return best?.takeIf { bestScore >= MIN_ACCEPTABLE_SCORE }
    }

    private fun matchScore(track: String, artist: String, candidateTrack: String, candidateArtist: String): Int {
        val wantedTrack = normalize(track)
        val foundTrack = normalize(candidateTrack)
        val wantedArtist = normalize(artist)
        val foundArtist = normalize(candidateArtist)
        if (wantedTrack.isBlank() || foundTrack.isBlank()) return 0

        val titleScore = when {
            wantedTrack == foundTrack -> 80
            wantedTrack.length >= 4 && (wantedTrack in foundTrack || foundTrack in wantedTrack) -> 62
            commonPrefixRatio(wantedTrack, foundTrack) >= 0.72 -> 50
            else -> 0
        }
        val artistScore = when {
            wantedArtist.isBlank() -> 12
            wantedArtist == foundArtist -> 20
            wantedArtist.length >= 2 && (wantedArtist in foundArtist || foundArtist in wantedArtist) -> 17
            else -> 0
        }
        return titleScore + artistScore
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[（(\\[].*?(live|remaster|版|伴奏|纯音乐|翻唱).*?[）)\\]]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun commonPrefixRatio(first: String, second: String): Double {
        val limit = minOf(first.length, second.length)
        var same = 0
        while (same < limit && first[same] == second[same]) same++
        return same.toDouble() / maxOf(first.length, second.length).toDouble()
    }

    private fun JSONArray?.joinNames(key: String): String {
        if (this == null) return ""
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.optString(key)?.takeIf { it.isNotBlank() }?.let(::add)
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

    companion object {
        private const val CONNECT_TIMEOUT_MS = 1_500
        private const val READ_TIMEOUT_MS = 2_200
        private const val LYRICS_DEADLINE_MS = 3_800L
        private const val COVER_DEADLINE_MS = 3_000L
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MIN_ACCEPTABLE_SCORE = 50
        private const val EXACT_MATCH_SCORE = 95
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
    }
}
