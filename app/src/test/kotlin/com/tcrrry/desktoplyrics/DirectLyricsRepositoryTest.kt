package com.tcrrry.desktoplyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectLyricsRepositoryTest {

    @Test
    fun `accepts localized artist names when title album and duration confirm one recording`() {
        val query = LyricsLookup(
            track = "Twinkle",
            artist = "Localized Artist Name",
            album = "Twinkle Mini Album",
            durationMs = 206_796L
        )

        val selected = LyricsCandidateSelector.selectCandidates(
            query,
            listOf(
                candidate(
                    source = "QQ音乐",
                    sourceId = "qq-twinkle",
                    track = "Twinkle",
                    artist = "TaeTiSeo",
                    album = "Twinkle Mini Album",
                    durationMs = 208_000L
                ),
                candidate(
                    source = "网易云音乐",
                    sourceId = "netease-twinkle",
                    track = "Twinkle",
                    artist = "TaeTiSeo",
                    album = "Twinkle Mini Album",
                    durationMs = 208_720L
                )
            )
        )

        assertEquals(listOf("QQ音乐", "网易云音乐"), selected.map { it.source })
    }

    @Test
    fun `rejects a version when the duration differs by more than two seconds`() {
        val query = LyricsLookup(
            track = "World (Remastered)",
            artist = "Artist",
            album = "Album",
            durationMs = 258_763L
        )

        assertTrue(
            LyricsCandidateSelector.selectCandidates(
                query,
                listOf(
                    candidate(
                        source = "网易云音乐",
                        sourceId = "wrong-duration",
                        track = "World (Remastered)",
                        artist = "Artist",
                        album = "Album",
                        durationMs = 255_546L
                    )
                )
            ).isEmpty()
        )
    }

    @Test
    fun `rejects conflicting version qualifiers even when other metadata matches`() {
        val query = LyricsLookup(
            track = "Number Nine (Japanese Version)",
            artist = "T-ara",
            album = "Summer of Pop",
            durationMs = 228_920L
        )

        assertTrue(
            LyricsCandidateSelector.selectCandidates(
                query,
                listOf(
                    candidate(
                        source = "QQ音乐",
                        sourceId = "korean-version",
                        track = "Number Nine (Korean Version)",
                        artist = "T-ara",
                        album = "Summer of Pop",
                        durationMs = 228_920L
                    )
                )
            ).isEmpty()
        )
    }

    @Test
    fun `rejects a partial title rather than guessing a longer title`() {
        val query = LyricsLookup(
            track = "Example Song Extended",
            artist = "Artist",
            album = "Album",
            durationMs = 200_000L
        )

        assertTrue(
            LyricsCandidateSelector.selectCandidates(
                query,
                listOf(
                    candidate(
                        source = "LRCLIB",
                        sourceId = "partial-title",
                        track = "Example Song",
                        artist = "Artist",
                        album = "Album",
                        durationMs = 200_000L
                    )
                )
            ).isEmpty()
        )
    }

    @Test
    fun `requires a direct artist match when an album cannot confirm the recording`() {
        val query = LyricsLookup(
            track = "A Song",
            artist = "Artist A",
            durationMs = 200_000L
        )
        val matchingArtist = candidate(
            source = "LRCLIB",
            sourceId = "artist-a",
            track = "A Song",
            artist = "Artist A",
            durationMs = 200_000L
        )
        val otherArtist = candidate(
            source = "QQ音乐",
            sourceId = "artist-b",
            track = "A Song",
            artist = "Artist B",
            durationMs = 200_000L
        )

        assertEquals(
            listOf("artist-a"),
            LyricsCandidateSelector.selectCandidates(query, listOf(matchingArtist, otherArtist))
                .map { it.sourceId }
        )
    }

    @Test
    fun `keeps silent when metadata describes more than one release`() {
        val query = LyricsLookup(
            track = "A Song",
            artist = "Artist",
            durationMs = 200_000L
        )

        assertTrue(
            LyricsCandidateSelector.selectCandidates(
                query,
                listOf(
                    candidate(
                        source = "QQ音乐",
                        sourceId = "album-one",
                        track = "A Song",
                        artist = "Artist",
                        album = "Album One",
                        durationMs = 200_000L
                    ),
                    candidate(
                        source = "网易云音乐",
                        sourceId = "album-two",
                        track = "A Song",
                        artist = "Artist",
                        album = "Album Two",
                        durationMs = 200_000L
                    )
                )
            ).isEmpty()
        )
    }

    @Test
    fun `waits for a known duration before confirming a recording`() {
        val query = LyricsLookup(
            track = "A Song",
            artist = "Artist",
            album = "Album",
            durationMs = 0L
        )

        assertFalse(LyricsCandidateSelector.canConfirm(query))
        assertTrue(
            LyricsCandidateSelector.selectCandidates(
                query,
                listOf(
                    candidate(
                        source = "LRCLIB",
                        sourceId = "candidate",
                        track = "A Song",
                        artist = "Artist",
                        album = "Album",
                        durationMs = 200_000L
                    )
                )
            ).isEmpty()
        )
    }

    @Test
    fun `rejects placeholder artist metadata even when the album matches`() {
        val query = LyricsLookup(
            track = "A Song",
            artist = "Unknown",
            album = "Album",
            durationMs = 200_000L
        )

        assertFalse(LyricsCandidateSelector.canConfirm(query))
        assertTrue(
            LyricsCandidateSelector.selectCandidates(
                query,
                listOf(
                    candidate(
                        source = "LRCLIB",
                        sourceId = "candidate",
                        track = "A Song",
                        artist = "Artist",
                        album = "Album",
                        durationMs = 200_000L
                    )
                )
            ).isEmpty()
        )
    }

    @Test
    fun `recognizes only synchronized lyrics`() {
        assertEquals(LyricsKind.NONE, classifyLyrics("null"))
        assertEquals(LyricsKind.PLAIN, classifyLyrics("plain fallback"))
        assertEquals(LyricsKind.SYNCHRONIZED, classifyLyrics("[00:15.44]Timed lyric"))
    }

    private fun candidate(
        source: String,
        sourceId: String,
        track: String,
        artist: String,
        album: String = "",
        durationMs: Long
    ) = DirectLyricsRepository.Result(
        durationMs = durationMs,
        source = source,
        sourceId = sourceId,
        candidateTrack = track,
        candidateArtist = artist,
        candidateAlbum = album
    )
}
