package com.tcrrry.desktoplyrics

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

internal class LrcLibLyricsSource(
    private val transport: HttpLyricsTransport = HttpLyricsTransport()
) : LyricsExactAndFallbackSource {
    override val sourceName: String = SOURCE_LRCLIB

    override fun exact(
        query: LyricsLookup,
        deadlineNanos: Long,
        cancellation: LyricsCancellationSignal
    ): LyricsResult? {
        val terms = LyricsCandidateSelector.lrcLibExactTerms(query)
        val url = buildString {
            append("https://lrclib.net/api/get")
            append("?track_name=${encode(terms.track)}")
            append("&artist_name=${encode(terms.artist)}")
            if (terms.album.isNotBlank()) append("&album_name=${encode(terms.album)}")
            append("&duration=${encode((terms.durationMs / 1000.0).toString())}")
        }
        val item = try {
            JSONObject(transport.getText(url, JSON_HEADERS, deadlineNanos, cancellation))
        } catch (error: LyricsHttpStatusException) {
            if (error.statusCode == HttpURLConnection.HTTP_NOT_FOUND) return null
            throw error
        }
        return result(item)
    }

    override fun fallback(
        query: LyricsLookup,
        deadlineNanos: Long,
        cancellation: LyricsCancellationSignal
    ): List<LyricsResult> {
        val terms = LyricsCandidateSelector.searchTerms(query)
        val url = "https://lrclib.net/api/search" +
            "?track_name=${encode(terms.track)}&artist_name=${encode(terms.artist)}"
        val list = JSONArray(transport.getText(url, JSON_HEADERS, deadlineNanos, cancellation))
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                result(item)?.let(::add)
            }
        }
    }

    override fun loadLyrics(
        candidate: LyricsResult,
        deadlineNanos: Long,
        cancellation: LyricsCancellationSignal
    ): LyricsResult? {
        cancellation.throwIfCancelled()
        return candidate.takeIf { classifyLyrics(it.lyrics) == LyricsKind.SYNCHRONIZED }
    }

    private fun result(item: JSONObject): LyricsResult? {
        val lyrics = cleanLyrics(item.contentString("syncedLyrics"))
        if (classifyLyrics(lyrics) != LyricsKind.SYNCHRONIZED) return null
        return LyricsResult(
            lyrics = lyrics,
            durationMs = (item.optDouble("duration", 0.0) * 1000.0).toLong(),
            source = sourceName,
            sourceId = item.contentString("id"),
            candidateTrack = item.contentString("trackName"),
            candidateArtist = item.contentString("artistName"),
            candidateAlbum = item.contentString("albumName"),
            lyricsKind = LyricsKind.SYNCHRONIZED
        )
    }
}

internal class QqLyricsSource(
    private val transport: HttpLyricsTransport = HttpLyricsTransport()
) : LyricsCatalogSource {
    override val sourceName: String = SOURCE_QQ

    override fun search(
        query: LyricsLookup,
        deadlineNanos: Long,
        cancellation: LyricsCancellationSignal
    ): List<LyricsResult> {
        val terms = LyricsCandidateSelector.searchTerms(query)
        val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp" +
            "?format=json&p=1&n=8&w=${encode("${terms.track} ${terms.artist}".trim())}"
        val root = JSONObject(transport.getText(searchUrl, headers(), deadlineNanos, cancellation))
        val songs = root.optJSONObject("data")
            ?.optJSONObject("song")
            ?.optJSONArray("list") ?: return emptyList()
        return buildList {
            for (index in 0 until songs.length()) {
                val song = songs.optJSONObject(index) ?: continue
                val songMid = song.contentString("songmid")
                if (songMid.isBlank()) continue
                add(
                    LyricsResult(
                        durationMs = song.optLong("interval", 0L) * 1000L,
                        cover = cover(song),
                        source = sourceName,
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

    override fun loadLyrics(
        candidate: LyricsResult,
        deadlineNanos: Long,
        cancellation: LyricsCancellationSignal
    ): LyricsResult? {
        if (candidate.sourceId.isBlank()) return null
        val lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
            "?songmid=${encode(candidate.sourceId)}&format=json&nobase64=1"
        val lyricRoot = parseJsonFlexible(
            transport.getBytes(lyricUrl, headers(), deadlineNanos, cancellation)
        ) ?: return null
        val lyrics = cleanLyrics(unescapeHtml(lyricRoot.contentString("lyric")))
        val translatedLyrics = synchronizedLyricsOrEmpty(
            unescapeHtml(lyricRoot.contentString("trans"))
        )
        return candidate.takeIf { classifyLyrics(lyrics) == LyricsKind.SYNCHRONIZED }
            ?.copy(
                lyrics = lyrics,
                translatedLyrics = translatedLyrics,
                lyricsKind = LyricsKind.SYNCHRONIZED
            )
    }

    private fun cover(song: JSONObject): String {
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

    private fun headers(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Referer" to "https://y.qq.com/",
        "User-Agent" to USER_AGENT
    )
}

internal class NetEaseLyricsSource(
    private val transport: HttpLyricsTransport = HttpLyricsTransport()
) : LyricsCatalogSource {
    override val sourceName: String = SOURCE_NETEASE

    override fun search(
        query: LyricsLookup,
        deadlineNanos: Long,
        cancellation: LyricsCancellationSignal
    ): List<LyricsResult> {
        val terms = LyricsCandidateSelector.searchTerms(query)
        val searchUrl = "https://music.163.com/api/search/get/web" +
            "?type=1&limit=8&s=${encode("${terms.track} ${terms.artist}".trim())}"
        val root = JSONObject(transport.getText(searchUrl, headers(), deadlineNanos, cancellation))
        val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        return buildList {
            for (index in 0 until songs.length()) {
                val song = songs.optJSONObject(index) ?: continue
                val songId = song.optLong("id", 0L)
                if (songId <= 0L) continue
                val album = song.optJSONObject("album") ?: song.optJSONObject("al")
                add(
                    LyricsResult(
                        durationMs = song.optLong("duration", song.optLong("dt", 0L)),
                        cover = album.contentString("picUrl"),
                        source = sourceName,
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

    override fun loadLyrics(
        candidate: LyricsResult,
        deadlineNanos: Long,
        cancellation: LyricsCancellationSignal
    ): LyricsResult? {
        if (candidate.sourceId.isBlank()) return null
        val lyricUrl = "https://music.163.com/api/song/lyric?os=pc&id=${encode(candidate.sourceId)}" +
            "&lv=-1&kv=-1&tv=-1"
        val lyricRoot = JSONObject(transport.getText(lyricUrl, headers(), deadlineNanos, cancellation))
        val lyrics = cleanLyrics(lyricRoot.optJSONObject("lrc").contentString("lyric"))
        val translatedLyrics = synchronizedLyricsOrEmpty(
            lyricRoot.optJSONObject("tlyric").contentString("lyric")
        )
        return candidate.takeIf { classifyLyrics(lyrics) == LyricsKind.SYNCHRONIZED }
            ?.copy(
                lyrics = lyrics,
                translatedLyrics = translatedLyrics,
                lyricsKind = LyricsKind.SYNCHRONIZED
            )
    }

    private fun headers(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Referer" to "https://music.163.com/",
        "User-Agent" to USER_AGENT
    )
}

internal class HttpLyricsTransport(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    private val maximumResponseBytes: Int = MAX_RESPONSE_BYTES
) {
    fun getText(
        url: String,
        headers: Map<String, String>,
        deadlineNanos: Long,
        cancellation: LyricsCancellationSignal
    ): String = getBytes(url, headers, deadlineNanos, cancellation).toString(Charsets.UTF_8)

    fun getBytes(
        url: String,
        headers: Map<String, String>,
        deadlineNanos: Long,
        cancellation: LyricsCancellationSignal
    ): ByteArray {
        cancellation.throwIfCancelled()
        val connection = URL(url).openConnection() as HttpURLConnection
        val cancellationRegistration = cancellation.register(connection::disconnect)
        try {
            val remainingMs = remainingTimeoutMs(deadlineNanos, cancellation)
            connection.requestMethod = "GET"
            connection.connectTimeout = minOf(connectTimeoutMs, remainingMs)
            connection.readTimeout = minOf(readTimeoutMs, remainingMs)
            connection.instanceFollowRedirects = true
            connection.useCaches = true
            headers.forEach(connection::setRequestProperty)
            val status = connection.responseCode
            if (status !in 200..299) throw httpFailure(status)
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    remainingTimeoutMs(deadlineNanos, cancellation)
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maximumResponseBytes) {
                        throw LyricsSourceException(
                            reason = LyricsFailureReason.NETWORK,
                            retryable = false,
                            message = "Lyrics response exceeds configured limit"
                        )
                    }
                    output.write(buffer, 0, read)
                }
                return output.toByteArray()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: LyricsSourceException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw LyricsSourceException(
                reason = LyricsFailureReason.DEADLINE,
                retryable = true,
                message = "Lyrics source timed out",
                cause = error
            )
        } catch (error: IOException) {
            cancellation.throwIfCancelled()
            throw LyricsSourceException(
                reason = LyricsFailureReason.NETWORK,
                retryable = true,
                message = "Lyrics source network failure",
                cause = error
            )
        } finally {
            cancellationRegistration.close()
            connection.disconnect()
        }
    }

    private fun remainingTimeoutMs(
        deadlineNanos: Long,
        cancellation: LyricsCancellationSignal
    ): Int {
        cancellation.throwIfCancelled()
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0L) {
            throw LyricsSourceException(
                reason = LyricsFailureReason.DEADLINE,
                retryable = true,
                message = "Lyrics lookup deadline reached"
            )
        }
        return TimeUnit.NANOSECONDS.toMillis(remaining)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun httpFailure(statusCode: Int): LyricsHttpStatusException {
        val reason = when {
            statusCode == 429 -> LyricsFailureReason.RATE_LIMIT
            statusCode >= 500 -> LyricsFailureReason.SERVER
            else -> LyricsFailureReason.NETWORK
        }
        return LyricsHttpStatusException(
            statusCode = statusCode,
            reason = reason,
            retryable = statusCode == 408 || statusCode == 429 || statusCode >= 500
        )
    }
}

internal class LyricsHttpStatusException(
    val statusCode: Int,
    reason: LyricsFailureReason,
    retryable: Boolean
) : LyricsSourceException(reason, retryable, "HTTP $statusCode")

private fun JSONArray?.joinNames(key: String): String {
    if (this == null) return ""
    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.contentString(key)?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }.joinToString("/")
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

internal const val SOURCE_LRCLIB = "LRCLIB"
internal const val SOURCE_QQ = "QQ音乐"
internal const val SOURCE_NETEASE = "网易云音乐"

private val JSON_HEADERS = mapOf("Accept" to "application/json")
private const val CONNECT_TIMEOUT_MS = 1_500
private const val READ_TIMEOUT_MS = 2_200
private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
