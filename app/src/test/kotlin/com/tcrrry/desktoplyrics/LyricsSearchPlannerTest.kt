package com.tcrrry.desktoplyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsSearchPlannerTest {
    @Test
    fun `builds a bounded album and artist anchor plan for Tank Lu`() {
        val requests = LyricsSearchPlanner.catalogRequests(
            LyricsLookup(
                track = "千年泪",
                artist = "Tank Lu",
                album = "Fighting! 生存之道",
                durationMs = 260_000L
            )
        )

        assertEquals(
            listOf(
                LyricsCatalogSearchRequest(
                    LyricsCatalogSearchKind.TITLE_ARTIST,
                    "千年泪 Tank Lu"
                ),
                LyricsCatalogSearchRequest(
                    LyricsCatalogSearchKind.TITLE_ALBUM,
                    "千年泪 Fighting! 生存之道"
                ),
                LyricsCatalogSearchRequest(
                    LyricsCatalogSearchKind.TITLE_ARTIST_ANCHOR,
                    "千年泪 tank"
                )
            ),
            requests
        )
    }

    @Test
    fun `keeps the localized subgroup anchor behind exact Twinkle metadata`() {
        val requests = LyricsSearchPlanner.catalogRequests(
            LyricsLookup(
                track = "Twinkle",
                artist = "少女时代-太蒂徐",
                album = "'Twinkle' Mini Album",
                durationMs = 206_796L
            )
        )

        assertEquals(
            listOf(
                LyricsCatalogSearchKind.TITLE_ARTIST,
                LyricsCatalogSearchKind.TITLE_ALBUM,
                LyricsCatalogSearchKind.TITLE_ARTIST_ANCHOR
            ),
            requests.map(LyricsCatalogSearchRequest::kind)
        )
        assertEquals("Twinkle 少女时代", requests.last().text)
    }

    @Test
    fun `deduplicates a single artist anchor and ends with a title query`() {
        val requests = LyricsSearchPlanner.catalogRequests(
            LyricsLookup(track = "A Song", artist = "Artist", durationMs = 200_000L)
        )

        assertEquals(
            listOf(
                LyricsCatalogSearchRequest(
                    LyricsCatalogSearchKind.TITLE_ARTIST,
                    "A Song Artist"
                ),
                LyricsCatalogSearchRequest(
                    LyricsCatalogSearchKind.TITLE_ONLY,
                    "A Song"
                )
            ),
            requests
        )
    }
}
