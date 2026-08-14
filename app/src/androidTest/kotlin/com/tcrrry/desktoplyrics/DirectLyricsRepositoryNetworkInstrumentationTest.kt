package com.tcrrry.desktoplyrics

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
    fun resolvesCuoCuoCuoThroughTheExactLrcLibPath() {
        val arguments: Bundle = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(NETWORK_SMOKE_ARGUMENT) == "true")

        val selectorStartedAt = SystemClock.elapsedRealtime()
        val exactTerms = LyricsCandidateSelector.lrcLibExactTerms(
            LyricsLookup(
                track = "错错错 (feat. 陈娟儿)",
                artist = "六哲",
                album = "被伤过的心还可以爱谁",
                durationMs = 289_250L
            )
        )
        val selectorElapsedMs = SystemClock.elapsedRealtime() - selectorStartedAt
        assertEquals("六哲, 陈娟儿", exactTerms.artist)

        val repository = DirectLyricsRepository()
        try {
            val firstStartedAt = SystemClock.elapsedRealtime()
            val firstResult = repository.resolveLyrics(
                track = "错错错 (feat. 陈娟儿)",
                artist = "六哲",
                album = "被伤过的心还可以爱谁",
                durationMs = 289_250L
            )
            val firstElapsedMs = SystemClock.elapsedRealtime() - firstStartedAt
            val secondStartedAt = SystemClock.elapsedRealtime()
            val secondResult = repository.resolveLyrics(
                track = "错错错 (feat. 陈娟儿)",
                artist = "六哲",
                album = "被伤过的心还可以爱谁",
                durationMs = 289_250L
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

            assertEquals("LRCLIB", firstResult.source)
            assertEquals("LRCLIB", secondResult.source)
            assertTrue(firstResult.sourceId.isNotBlank())
            assertTrue(secondResult.sourceId.isNotBlank())
            assertEquals(LyricsKind.SYNCHRONIZED, classifyLyrics(firstResult.lyrics))
            assertEquals(LyricsKind.SYNCHRONIZED, classifyLyrics(secondResult.lyrics))
            assertTrue(LyricsCandidateSelector.hasMatchingDuration(289_250L, firstResult.durationMs))
            assertTrue(LyricsCandidateSelector.hasMatchingDuration(289_250L, secondResult.durationMs))
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
            val result = repository.resolveLyrics(
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
            val result = repository.resolveLyrics(
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

    private companion object {
        const val NETWORK_SMOKE_ARGUMENT = "runLyricsNetworkSmoke"
        const val WRONG_NETEASE_SOURCE_ID = "29719782"
        const val COLD_EXACT_PATH_LIMIT_MS = 2_200L
        const val EXACT_PATH_LIMIT_MS = 2_200L
        const val LOCALIZED_CATALOG_PATH_LIMIT_MS = 4_200L
    }
}
