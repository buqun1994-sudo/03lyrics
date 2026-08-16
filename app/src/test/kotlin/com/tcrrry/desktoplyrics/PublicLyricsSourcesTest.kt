package com.tcrrry.desktoplyrics

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class PublicLyricsSourcesTest {
    @Test
    fun `QQ parses the Tank album search candidate and synchronized body`() {
        val transport = FixtureLyricsTransport(
            textResponse = { qqSearchResponse() },
            byteResponse = {
                JSONObject()
                    .put("code", 0)
                    .put("lyric", "[00:01.00]line")
                    .toString()
                    .toByteArray()
            }
        )
        val source = QqLyricsSource(transport)
        val cancellation = LyricsCancellationSignal()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L)

        val candidates = source.search(
            LyricsCatalogSearchRequest(
                LyricsCatalogSearchKind.TITLE_ALBUM,
                "千年泪 Fighting! 生存之道"
            ),
            deadline,
            cancellation
        )
        val candidate = candidates.single()
        val loaded = requireNotNull(source.loadLyrics(candidate, deadline, cancellation))

        assertTrue(decoded(transport.urls.first()).contains("w=千年泪 Fighting! 生存之道"))
        assertEquals("003uqv3H0ZIitc", candidate.sourceId)
        assertEquals("千年泪", candidate.candidateTrack)
        assertEquals("Tank", candidate.candidateArtist)
        assertEquals("Fighting！生存之道", candidate.candidateAlbum)
        assertEquals(260_000L, candidate.durationMs)
        assertEquals(LyricsKind.SYNCHRONIZED, loaded.lyricsKind)
    }

    @Test
    fun `NetEase parses the localized Twinkle artist and synchronized translation`() {
        val transport = FixtureLyricsTransport(
            textResponse = { url ->
                if (url.contains("/api/song/lyric")) {
                    JSONObject()
                        .put("code", 200)
                        .put("lrc", JSONObject().put("lyric", "[00:01.00]line"))
                        .put("tlyric", JSONObject().put("lyric", "[00:01.00]translation"))
                        .toString()
                } else {
                    netEaseSearchResponse()
                }
            }
        )
        val source = NetEaseLyricsSource(transport)
        val cancellation = LyricsCancellationSignal()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L)

        val candidates = source.search(
            LyricsCatalogSearchRequest(
                LyricsCatalogSearchKind.TITLE_ARTIST,
                "Twinkle 少女时代-太蒂徐"
            ),
            deadline,
            cancellation
        )
        val candidate = candidates.single()
        val loaded = requireNotNull(source.loadLyrics(candidate, deadline, cancellation))

        assertTrue(decoded(transport.urls.first()).contains("s=Twinkle 少女时代-太蒂徐"))
        assertEquals("5376238", candidate.sourceId)
        assertEquals("少女时代-TaeTiSeo", candidate.candidateArtist)
        assertEquals("'Twinkle' Mini Album", candidate.candidateAlbum)
        assertEquals(208_720L, candidate.durationMs)
        assertEquals("[00:01.00]translation", loaded.translatedLyrics)
    }

    private fun qqSearchResponse(): String = JSONObject()
        .put(
            "data",
            JSONObject().put(
                "song",
                JSONObject().put(
                    "list",
                    JSONArray().put(
                        JSONObject()
                            .put("songmid", "003uqv3H0ZIitc")
                            .put("songname", "千年泪")
                            .put("albumname", "Fighting！生存之道")
                            .put("interval", 260)
                            .put("singer", JSONArray().put(JSONObject().put("name", "Tank")))
                    )
                )
            )
        )
        .toString()

    private fun netEaseSearchResponse(): String = JSONObject()
        .put(
            "result",
            JSONObject().put(
                "songs",
                JSONArray().put(
                    JSONObject()
                        .put("id", 5_376_238L)
                        .put("name", "Twinkle")
                        .put("duration", 208_720L)
                        .put(
                            "artists",
                            JSONArray().put(JSONObject().put("name", "少女时代-TaeTiSeo"))
                        )
                        .put("album", JSONObject().put("name", "'Twinkle' Mini Album"))
                )
            )
        )
        .toString()

    private fun decoded(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())

    private class FixtureLyricsTransport(
        private val textResponse: (String) -> String,
        private val byteResponse: ((String) -> ByteArray)? = null
    ) : LyricsTransport {
        val urls = mutableListOf<String>()

        override fun getText(
            url: String,
            headers: Map<String, String>,
            deadlineNanos: Long,
            cancellation: LyricsCancellationSignal
        ): String {
            urls += url
            return textResponse(url)
        }

        override fun getBytes(
            url: String,
            headers: Map<String, String>,
            deadlineNanos: Long,
            cancellation: LyricsCancellationSignal
        ): ByteArray {
            urls += url
            return byteResponse?.invoke(url) ?: textResponse(url).toByteArray()
        }
    }
}
