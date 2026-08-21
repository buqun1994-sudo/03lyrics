package com.ninepointnine.desktoplyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsSettingsModelsTest {
    @Test
    fun `runtime state round trip carries summaries without lyrics bodies`() {
        val playback = LyricsPlaybackIdentity("Song", "Artist", "Album", 201_000L)
        val result = LyricsResult(
            lyrics = "[00:01.00]body that must not enter the settings broadcast",
            translatedLyrics = "[00:01.00]translation",
            durationMs = 200_900L,
            source = "QQ音乐",
            sourceId = "candidate-1",
            candidateTrack = "Song",
            candidateArtist = "Artist",
            candidateAlbum = "Album",
            lyricsKind = LyricsKind.SYNCHRONIZED
        )
        val state = LyricsSettingsRuntimeState(
            playback = playback,
            cache = LyricsCacheSnapshot(
                LyricsCacheStats(3, 1, 4_096L, LyricsCachePolicy.MAX_BYTES),
                LyricsCachedTrackInfo(LyricsCacheSelection.MANUAL, result, 123_000L)
            ),
            searchState = LyricsManualSearchState.READY,
            searchCandidates = listOf(
                LyricsManualSearchCandidate("candidate-token", result.candidateSnapshot())
            ),
            recordingGeneration = 42L
        )

        val payload = state.encode()
        val decoded = requireNotNull(LyricsSettingsRuntimeState.decode(payload))

        assertFalse(payload.contains("body that must not enter"))
        assertEquals(playback, decoded.playback)
        assertEquals(4, decoded.cache.stats.totalEntries)
        assertEquals(LyricsCacheSelection.MANUAL, decoded.cache.current?.selection)
        assertTrue(decoded.cache.current?.result?.translatedLyrics?.isNotBlank() == true)
        assertEquals("candidate-token", decoded.searchCandidates.single().token)
        assertEquals(42L, decoded.recordingGeneration)
    }

    @Test
    fun `manual cache entries never expire into automatic refresh`() {
        val entry = LyricsCache.Entry(
            result = LyricsResult(
                lyrics = "[00:01.00]manual",
                lyricsKind = LyricsKind.SYNCHRONIZED
            ),
            proof = null,
            updatedAtMs = 1L,
            selection = LyricsCacheSelection.MANUAL
        )

        assertFalse(entry.needsRefresh(Long.MAX_VALUE))
    }

    @Test
    fun `cache capacity estimate uses observed size and a conservative empty baseline`() {
        val empty = LyricsCacheStats(
            automaticEntries = 0,
            manualEntries = 0,
            totalBytes = 0L,
            maximumAutomaticBytes = 128L * 1024L * 1024L
        )
        val observed = LyricsCacheStats(
            automaticEntries = 3,
            manualEntries = 1,
            totalBytes = 16L * 1024L,
            maximumAutomaticBytes = 32L * 1024L
        )
        val full = LyricsCacheStats(
            automaticEntries = 1,
            manualEntries = 0,
            totalBytes = 32L * 1024L,
            maximumAutomaticBytes = 32L * 1024L
        )

        assertEquals(32_768L, empty.estimatedRemainingTracks)
        assertEquals(4L, observed.estimatedRemainingTracks)
        assertEquals(0L, full.estimatedRemainingTracks)
    }
}
