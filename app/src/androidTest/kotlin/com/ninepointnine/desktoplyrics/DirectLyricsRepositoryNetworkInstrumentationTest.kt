package com.ninepointnine.desktoplyrics

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectLyricsRepositoryNetworkInstrumentationTest {
    @Test
    fun resolvesCuoCuoCuoFromTheFirstCompletedTrustedSource() {
        val arguments: Bundle = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(NETWORK_SMOKE_ARGUMENT) == "true")

        val query = LyricsLookup(
            track = "错错错 (feat. 陈娟儿)",
            artist = "六哲",
            album = "被伤过的心还可以爱谁",
            durationMs = 289_250L
        )
        val selectorStartedAt = SystemClock.elapsedRealtime()
        val exactTerms = LyricsSearchPlanner.lrcLibExactTerms(query)
        val selectorElapsedMs = SystemClock.elapsedRealtime() - selectorStartedAt
        assertEquals("六哲, 陈娟儿", exactTerms.artist)

        val repository = DirectLyricsRepository()
        try {
            val firstStartedAt = SystemClock.elapsedRealtime()
            val firstResult = repository.resolveFoundLyrics(
                track = query.track,
                artist = query.artist,
                album = query.album,
                durationMs = query.durationMs
            )
            val firstElapsedMs = SystemClock.elapsedRealtime() - firstStartedAt
            val secondStartedAt = SystemClock.elapsedRealtime()
            val secondResult = repository.resolveFoundLyrics(
                track = query.track,
                artist = query.artist,
                album = query.album,
                durationMs = query.durationMs
            )
            val secondElapsedMs = SystemClock.elapsedRealtime() - secondStartedAt
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "stream",
                        "Lyrics network smoke: selector=${selectorElapsedMs}ms, " +
                            "first=${firstElapsedMs}ms/${firstResult.sourceId}, " +
                            "second=${secondElapsedMs}ms/${secondResult.sourceId}\n"
                    )
                }
            )

            assertTrue(firstResult.source in setOf("LRCLIB", "QQ音乐", "网易云音乐"))
            assertTrue(secondResult.source in setOf("LRCLIB", "QQ音乐", "网易云音乐"))
            assertTrue(firstResult.sourceId.isNotBlank())
            assertTrue(secondResult.sourceId.isNotBlank())
            assertEquals(LyricsKind.SYNCHRONIZED, classifyLyrics(firstResult.lyrics))
            assertEquals(LyricsKind.SYNCHRONIZED, classifyLyrics(secondResult.lyrics))
            assertTrue(LyricsCandidateSelector.hasMatchingDuration(query.durationMs, firstResult.durationMs))
            assertTrue(LyricsCandidateSelector.hasMatchingDuration(query.durationMs, secondResult.durationMs))
            assertTrue(LyricsCandidateSelector.matchesVersion(query, firstResult))
            assertTrue(LyricsCandidateSelector.matchesVersion(query, secondResult))
            assertTrue(
                "Cold exact lookup took ${selectorElapsedMs + firstElapsedMs}ms",
                selectorElapsedMs + firstElapsedMs <= COLD_EXACT_PATH_LIMIT_MS
            )
            assertTrue(
                "Lookups returned ${firstResult.sourceId} in ${firstElapsedMs}ms, then " +
                    "${secondResult.sourceId} in ${secondElapsedMs}ms",
                secondElapsedMs <= EXACT_PATH_LIMIT_MS
            )
        } finally {
            repository.close()
        }
    }

    @Test
    fun resolvesMariaThroughLocalizedCatalogEvidence() {
        val arguments: Bundle = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(NETWORK_SMOKE_ARGUMENT) == "true")

        val repository = DirectLyricsRepository()
        try {
            val startedAt = SystemClock.elapsedRealtime()
            val result = repository.resolveFoundLyrics(
                track = "마리아",
                artist = "HWASA",
                album = "María - EP",
                durationMs = 199_000L
            )
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "stream",
                        "Maria network smoke: ${elapsedMs}ms/${result.source}/${result.sourceId}\n"
                    )
                }
            )

            assertTrue(result.source in setOf("QQ音乐", "网易云音乐", "LRCLIB"))
            assertTrue(result.sourceId.isNotBlank())
            assertEquals(LyricsKind.SYNCHRONIZED, classifyLyrics(result.lyrics))
            assertTrue(LyricsCandidateSelector.hasMatchingDuration(199_000L, result.durationMs))
            assertTrue(
                "Localized catalog lookup took ${elapsedMs}ms",
                elapsedMs <= LOCALIZED_CATALOG_PATH_LIMIT_MS
            )
        } finally {
            repository.close()
        }
    }

    @Test
    fun resolvesCityZooWithBilingualArtistAndSingleReleaseSuffix() {
        val arguments: Bundle = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(NETWORK_SMOKE_ARGUMENT) == "true")

        val query = LyricsLookup(
            track = "摩天动物园",
            artist = "邓紫棋",
            album = "摩天动物园 - Single",
            durationMs = 270_676L
        )
        val repository = DirectLyricsRepository()
        try {
            val startedAt = SystemClock.elapsedRealtime()
            val result = repository.resolveFoundLyrics(
                track = query.track,
                artist = query.artist,
                album = query.album,
                durationMs = query.durationMs
            )
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "stream",
                        "City Zoo network smoke: ${elapsedMs}ms/${result.source}/${result.sourceId}\n"
                    )
                }
            )

            assertTrue(result.source in setOf("QQ音乐", "网易云音乐"))
            assertTrue(result.sourceId.isNotBlank())
            assertEquals("摩天动物园", result.candidateTrack)
            assertEquals(LyricsKind.SYNCHRONIZED, classifyLyrics(result.lyrics))
            assertTrue(LyricsCandidateSelector.hasMatchingDuration(query.durationMs, result.durationMs))
            assertTrue(LyricsCandidateSelector.matchesVersion(query, result))
            assertTrue(
                "City Zoo catalog lookup took ${elapsedMs}ms",
                elapsedMs <= LOCALIZED_CATALOG_PATH_LIMIT_MS
            )
        } finally {
            repository.close()
        }
    }

    @Test
    fun resolvesMoyaWithoutSelectingAnotherAoaRecording() {
        val arguments: Bundle = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(NETWORK_SMOKE_ARGUMENT) == "true")

        val query = LyricsLookup(
            track = "MOYA",
            artist = "AOA",
            album = "MOYA - EP",
            durationMs = 220_427L
        )
        val repository = DirectLyricsRepository()
        try {
            val startedAt = SystemClock.elapsedRealtime()
            val result = repository.resolveFoundLyrics(
                track = query.track,
                artist = query.artist,
                album = query.album,
                durationMs = query.durationMs
            )
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "stream",
                        "Moya network smoke: ${elapsedMs}ms/${result.source}/${result.sourceId}/" +
                            "${result.candidateTrack}\n"
                    )
                }
            )

            assertTrue(result.source in setOf("QQ音乐", "网易云音乐", "LRCLIB"))
            assertTrue(result.sourceId.isNotBlank())
            assertTrue(result.sourceId != WRONG_NETEASE_SOURCE_ID)
            assertEquals(LyricsKind.SYNCHRONIZED, classifyLyrics(result.lyrics))
            assertTrue(LyricsCandidateSelector.matchesVersion(query, result))
            assertTrue(
                "MOYA catalog lookup took ${elapsedMs}ms",
                elapsedMs <= LOCALIZED_CATALOG_PATH_LIMIT_MS
            )
        } finally {
            repository.close()
        }
    }

    @Test
    fun resolvesShaiWhenOnlyTheSourceDeclaresTheLiveVersion() {
        val arguments: Bundle = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(NETWORK_SMOKE_ARGUMENT) == "true")

        val query = LyricsLookup(
            track = "晒",
            artist = "Tizzy T & GALI",
            album = "中国说唱巅峰对决 第三期",
            durationMs = 220_264L
        )
        val repository = DirectLyricsRepository()
        try {
            val startedAt = SystemClock.elapsedRealtime()
            val result = repository.resolveFoundLyrics(
                track = query.track,
                artist = query.artist,
                album = query.album,
                durationMs = query.durationMs
            )
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "stream",
                        "Shai network smoke: ${elapsedMs}ms/${result.source}/${result.sourceId}/" +
                            "${result.candidateTrack}\n"
                    )
                }
            )

            assertTrue(result.source in setOf("QQ音乐", "网易云音乐"))
            assertTrue(result.sourceId in setOf("002mymZ00uwkRC", "1962368708"))
            assertEquals(LyricsKind.SYNCHRONIZED, classifyLyrics(result.lyrics))
            assertTrue(LyricsCandidateSelector.hasMatchingDuration(query.durationMs, result.durationMs))
            assertEquals(
                EvidenceLevel.NEAR,
                titleEvidence(titleIdentity(query.track), titleIdentity(result.candidateTrack))
            )
            assertTrue(LyricsCandidateSelector.matchesVersion(query, result))
            assertTrue(
                "Shai catalog lookup took ${elapsedMs}ms",
                elapsedMs <= LOCALIZED_CATALOG_PATH_LIMIT_MS
            )
        } finally {
            repository.close()
        }
    }

    @Test
    fun resolvesQianNianLeiAcrossLiveCatalogReleaseVariants() {
        val arguments: Bundle = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(NETWORK_SMOKE_ARGUMENT) == "true")

        val query = LyricsLookup(
            track = "千年泪",
            artist = "Tank Lu",
            album = "Fighting! 生存之道",
            durationMs = 260_000L
        )
        val repository = DirectLyricsRepository()
        try {
            val startedAt = SystemClock.elapsedRealtime()
            val result = repository.resolveFoundLyrics(
                track = query.track,
                artist = query.artist,
                album = query.album,
                durationMs = query.durationMs
            )
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "stream",
                        "Qian Nian Lei network smoke: ${elapsedMs}ms/${result.source}/" +
                            "${result.sourceId}/${result.candidateArtist}\n"
                    )
                }
            )

            assertEquals("千年泪", result.candidateTrack)
            assertEquals("Tank", result.candidateArtist)
            assertTrue(
                LyricsCandidateSelector.hasMatchingDuration(query.durationMs, result.durationMs)
            )
            assertEquals(LyricsKind.SYNCHRONIZED, classifyLyrics(result.lyrics))
            assertEquals(
                EvidenceReason.CONTIGUOUS_SUBJECT,
                artistMetadataEvidence(
                    query.track,
                    query.artist,
                    result.candidateTrack,
                    result.candidateArtist
                ).reason
            )
            assertTrue(
                "Album expansion took ${elapsedMs}ms",
                elapsedMs <= LOCALIZED_CATALOG_PATH_LIMIT_MS
            )
        } finally {
            repository.close()
        }
    }

    @Test
    fun resolvesTwinkleFromThePrimaryLocalizedSubgroupCandidate() {
        val arguments: Bundle = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(NETWORK_SMOKE_ARGUMENT) == "true")

        val query = LyricsLookup(
            track = "Twinkle",
            artist = "少女时代-太蒂徐",
            album = "'Twinkle' Mini Album",
            durationMs = 206_796L
        )
        val repository = DirectLyricsRepository()
        try {
            val startedAt = SystemClock.elapsedRealtime()
            val result = repository.resolveFoundLyrics(
                track = query.track,
                artist = query.artist,
                album = query.album,
                durationMs = query.durationMs
            )
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "stream",
                        "Twinkle network smoke: ${elapsedMs}ms/${result.source}/${result.sourceId}\n"
                    )
                }
            )

            assertEquals("Twinkle", result.candidateTrack)
            assertTrue(
                LyricsCandidateSelector.hasMatchingDuration(query.durationMs, result.durationMs)
            )
            assertEquals(LyricsKind.SYNCHRONIZED, classifyLyrics(result.lyrics))
            assertEquals(
                EvidenceReason.CONTIGUOUS_SUBJECT,
                artistMetadataEvidence(
                    query.track,
                    query.artist,
                    result.candidateTrack,
                    result.candidateArtist
                ).reason
            )
            assertTrue(
                "Twinkle catalog lookup took ${elapsedMs}ms",
                elapsedMs <= LOCALIZED_CATALOG_PATH_LIMIT_MS
            )
        } finally {
            repository.close()
        }
    }

    private fun DirectLyricsRepository.resolveFoundLyrics(
        track: String,
        artist: String,
        album: String,
        durationMs: Long
    ): LyricsResult {
        val outcome = resolveLyrics(track, artist, album, durationMs)
        return requireNotNull((outcome as? LyricsResolutionOutcome.Found)?.resolved?.result) {
            "Expected synchronized lyrics, got $outcome"
        }
    }

    private companion object {
        const val NETWORK_SMOKE_ARGUMENT = "runLyricsNetworkSmoke"
        const val WRONG_NETEASE_SOURCE_ID = "29719782"
        const val COLD_EXACT_PATH_LIMIT_MS = 2_200L
        const val EXACT_PATH_LIMIT_MS = 2_200L
        const val LOCALIZED_CATALOG_PATH_LIMIT_MS = 4_200L
    }
}
