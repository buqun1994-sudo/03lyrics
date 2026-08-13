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

    private companion object {
        const val NETWORK_SMOKE_ARGUMENT = "runLyricsNetworkSmoke"
        const val COLD_EXACT_PATH_LIMIT_MS = 2_200L
        const val EXACT_PATH_LIMIT_MS = 2_200L
    }
}
