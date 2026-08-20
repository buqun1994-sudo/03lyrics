package com.ninepointnine.desktoplyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DirectLyricsRepositoryLiveNetworkTest {
    @Test
    fun `resolves Qian Nian Lei across live catalog release variants`() {
        assumeNetworkSmokeEnabled()
        val query = LyricsLookup(
            track = "千年泪",
            artist = "Tank Lu",
            album = "Fighting! 生存之道",
            durationMs = 260_000L
        )

        val result = resolve(query)

        assertEquals("千年泪", result.candidateTrack)
        assertEquals("Tank", result.candidateArtist)
        assertTrue(LyricsCandidateSelector.hasMatchingDuration(query.durationMs, result.durationMs))
        assertEquals(
            EvidenceReason.CONTIGUOUS_SUBJECT,
            artistMetadataEvidence(
                query.track,
                query.artist,
                result.candidateTrack,
                result.candidateArtist
            ).reason
        )
        assertEquals(LyricsKind.SYNCHRONIZED, classifyLyrics(result.lyrics))
    }

    @Test
    fun `resolves Twinkle through the live localized artist candidate`() {
        assumeNetworkSmokeEnabled()
        val query = LyricsLookup(
            track = "Twinkle",
            artist = "少女时代-太蒂徐",
            album = "'Twinkle' Mini Album",
            durationMs = 206_796L
        )

        val result = resolve(query)

        assertEquals("Twinkle", result.candidateTrack)
        assertTrue(LyricsCandidateSelector.hasMatchingDuration(query.durationMs, result.durationMs))
        assertEquals(
            EvidenceReason.CONTIGUOUS_SUBJECT,
            artistMetadataEvidence(
                query.track,
                query.artist,
                result.candidateTrack,
                result.candidateArtist
            ).reason
        )
        assertEquals(LyricsKind.SYNCHRONIZED, classifyLyrics(result.lyrics))
    }

    private fun resolve(query: LyricsLookup): LyricsResult {
        val repository = DirectLyricsRepository(logger = NoOpLyricsRepositoryLogger)
        return try {
            val outcome = repository.resolveLyrics(query, LyricsCancellationSignal())
            requireNotNull((outcome as? LyricsResolutionOutcome.Found)?.resolved?.result) {
                "Expected synchronized lyrics, got $outcome"
            }
        } finally {
            repository.close()
        }
    }

    private fun assumeNetworkSmokeEnabled() {
        assumeTrue(
            System.getProperty(NETWORK_SMOKE_PROPERTY) == "true" ||
                System.getenv(NETWORK_SMOKE_ENVIRONMENT) == "true"
        )
    }

    private object NoOpLyricsRepositoryLogger : LyricsRepositoryLogger {
        override fun info(message: String) = Unit
        override fun warning(message: String, error: Throwable) = Unit
    }

    private companion object {
        const val NETWORK_SMOKE_PROPERTY = "runLyricsNetworkSmoke"
        const val NETWORK_SMOKE_ENVIRONMENT = "RUN_LYRICS_NETWORK_SMOKE"
    }
}
