package com.tcrrry.desktoplyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectLyricsRepositoryTest {

    @Test
    fun `prefers synchronized lyrics for the same release`() {
        val query = LyricsLookup(
            track = "Number Nine (Japanese Version)",
            artist = "T-ara",
            album = "Summer of Pop",
            durationMs = 228_920L
        )
        val result = LyricsCandidateSelector.select(
            query,
            listOf(
                DirectLyricsRepository.Result(
                    lyrics = "Number nine\nplain fallback",
                    durationMs = 228_000L,
                    source = "LRCLIB",
                    candidateTrack = "Number Nine (Japanese Version)",
                    candidateArtist = "T-Ara",
                    candidateAlbum = "Summer of Pop"
                ),
                DirectLyricsRepository.Result(
                    lyrics = "[00:15.44]NUMBER NINE",
                    durationMs = 228_920L,
                    source = "网易云音乐",
                    candidateTrack = "NUMBER NINE (Japanese ver.)",
                    candidateArtist = "T-ARA",
                    candidateAlbum = "Summer of Pop"
                )
            ),
            minimumKind = LyricsKind.SYNCHRONIZED
        )

        assertEquals("网易云音乐", result?.source)
        assertEquals(LyricsKind.SYNCHRONIZED, result?.lyricsKind)
    }

    @Test
    fun `rejects null placeholder lyrics`() {
        assertEquals("", cleanLyrics("null"))
        assertEquals("", cleanLyrics(" undefined "))
        assertNull(
            LyricsCandidateSelector.select(
                LyricsLookup("Number Nine", "T-ara"),
                listOf(
                    DirectLyricsRepository.Result(
                        lyrics = "null",
                        source = "LRCLIB",
                        candidateTrack = "Number Nine",
                        candidateArtist = "T-ara"
                    )
                )
            )
        )
    }

    @Test
    fun `does not replace a requested language version with a different one`() {
        val query = LyricsLookup(
            track = "Number Nine (Japanese Version)",
            artist = "T-ara",
            album = "Summer of Pop",
            durationMs = 228_920L
        )
        val result = LyricsCandidateSelector.select(
            query,
            listOf(
                DirectLyricsRepository.Result(
                    lyrics = "Japanese plain lyrics",
                    durationMs = 228_000L,
                    source = "LRCLIB",
                    candidateTrack = "Number Nine (Japanese Version)",
                    candidateArtist = "T-ara",
                    candidateAlbum = "Summer of Pop"
                ),
                DirectLyricsRepository.Result(
                    lyrics = "[00:15.44]Korean synchronized lyrics",
                    durationMs = 230_000L,
                    source = "QQ音乐",
                    candidateTrack = "Number Nine (Korean Version)",
                    candidateArtist = "T-ara",
                    candidateAlbum = "Again"
                )
            )
        )

        assertEquals("LRCLIB", result?.source)
        assertEquals(LyricsKind.PLAIN, result?.lyricsKind)
    }

    @Test
    fun `topbar rejects plain lyrics without a timeline`() {
        val result = LyricsCandidateSelector.select(
            LyricsLookup("A Song", "An Artist"),
            listOf(
                DirectLyricsRepository.Result(
                    lyrics = "first line\nsecond line",
                    source = "LRCLIB",
                    candidateTrack = "A Song",
                    candidateArtist = "An Artist"
                )
            ),
            minimumKind = LyricsKind.SYNCHRONIZED
        )

        assertNull(result)
    }

    @Test
    fun `rejects a song title that only partially matches video metadata`() {
        val assessment = LyricsCandidateSelector.assess(
            LyricsLookup(
                track = "假如上班可以选海克斯···",
                artist = "云顶猫咪"
            ),
            LyricsCandidateMetadata(
                track = "假如",
                artist = "信乐团",
                album = "挑信",
                durationMs = 264_493L
            )
        )

        assertEquals(false, assessment.accepted)
    }

    @Test
    fun `rejects missing or placeholder artists`() {
        listOf("", "unknown", "unkown", "未知歌手").forEach { artist ->
            val assessment = LyricsCandidateSelector.assess(
                LyricsLookup("A Song", artist),
                LyricsCandidateMetadata("A Song", "An Artist")
            )

            assertEquals(false, assessment.accepted)
        }
    }

    @Test
    fun `rejects an exact title when the artist does not match`() {
        val assessment = LyricsCandidateSelector.assess(
            LyricsLookup("假如", "云顶猫咪"),
            LyricsCandidateMetadata("假如", "信乐团")
        )

        assertEquals(false, assessment.accepted)
    }

    @Test
    fun `accepts an exact normalized title and matching collaboration artist`() {
        val assessment = LyricsCandidateSelector.assess(
            LyricsLookup(
                track = "NUMBER NINE (Japanese Version)",
                artist = "T-ara"
            ),
            LyricsCandidateMetadata(
                track = "Number Nine (Japanese ver.)",
                artist = "T-ARA / QBS"
            )
        )

        assertEquals(true, assessment.accepted)
    }

    @Test
    fun `accepts a live suffix through weighted metadata instead of exact title equality`() {
        val query = LyricsLookup(
            track = "药到病除The Cure",
            artist = "盛宇 & 万妮达",
            album = "中国说唱巅峰对决 第十期",
            durationMs = 210_000L
        )
        val candidate = LyricsCandidateMetadata(
            track = "药到病除 The Cure (Live)",
            artist = "万妮达Vinida Weng/盛宇D-SHINE",
            album = "中国说唱巅峰对决 第10期",
            durationMs = 210_172L
        )

        val assessment = LyricsCandidateSelector.assess(query, candidate)

        assertEquals(true, assessment.accepted)
        assertEquals(true, assessment.score >= 78)
    }

    @Test
    fun `matches reordered collaboration artists with platform display names`() {
        val assessment = LyricsCandidateSelector.assess(
            LyricsLookup(
                track = "空城计之梦刘备 (现场)",
                artist = "KEY.L刘聪, 万妮达 & VAVA",
                album = "中国说唱巅峰对决 第九期(Live)",
                durationMs = 266_000L
            ),
            LyricsCandidateMetadata(
                track = "空城计之梦刘备 (Live)",
                artist = "VaVa娃娃/KEY.L刘聪/万妮达Vinida Weng",
                album = "中国说唱巅峰对决 第9期",
                durationMs = 266_130L
            )
        )

        assertEquals(true, assessment.accepted)
    }

    @Test
    fun `selects the strongest weighted release across providers`() {
        val query = LyricsLookup(
            track = "药到病除The Cure",
            artist = "盛宇 & 万妮达",
            album = "中国说唱巅峰对决 第十期",
            durationMs = 210_000L
        )
        val result = LyricsCandidateSelector.select(
            query,
            listOf(
                synchronizedResult(
                    source = "QQ音乐",
                    track = "药到病除 The Cure (Live)",
                    artist = "万妮达Vinida Weng/盛宇D-SHINE",
                    album = "中国说唱巅峰对决 第10期",
                    durationMs = 210_000L
                ),
                synchronizedResult(
                    source = "网易云音乐",
                    track = "药到病除 The Cure (LIVE版)",
                    artist = "万妮达Vinida Weng/盛宇D-SHINE",
                    album = "中国说唱巅峰对决 第十期",
                    durationMs = 210_172L
                ),
                synchronizedResult(
                    source = "LRCLIB",
                    track = "The Cure",
                    artist = "Lady Gaga",
                    album = "The Cure",
                    durationMs = 211_000L
                )
            ),
            minimumKind = LyricsKind.SYNCHRONIZED
        )

        assertEquals("网易云音乐", result?.source)
        assertEquals(true, (result?.score ?: 0) >= 78)
    }

    @Test
    fun `keeps silent when different releases are nearly tied`() {
        val query = LyricsLookup(
            track = "Example Song",
            artist = "Example Artist"
        )
        val result = LyricsCandidateSelector.select(
            query,
            listOf(
                synchronizedResult(
                    source = "QQ音乐",
                    track = "Example Song (Live)",
                    artist = "Example Artist"
                ),
                synchronizedResult(
                    source = "网易云音乐",
                    track = "Example Song (Acoustic)",
                    artist = "Example Artist"
                )
            ),
            minimumKind = LyricsKind.SYNCHRONIZED
        )

        assertNull(result)
    }

    private fun synchronizedResult(
        source: String,
        track: String,
        artist: String,
        album: String = "",
        durationMs: Long = 0L
    ) = DirectLyricsRepository.Result(
        lyrics = "[00:01.00]lyric",
        source = source,
        candidateTrack = track,
        candidateArtist = artist,
        candidateAlbum = album,
        durationMs = durationMs
    )
}
