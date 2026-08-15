package com.tcrrry.desktoplyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsCachePolicyTest {
    @Test
    fun `uses the agreed cache budget and batch cleanup target`() {
        assertEquals(128L * 1024L * 1024L, LyricsCachePolicy.MAX_BYTES)
        assertEquals(LyricsCachePolicy.MAX_BYTES * 9L / 10L, LyricsCachePolicy.TRIM_TARGET_BYTES)
    }

    @Test
    fun `caps frequency and still favors recently used lyrics`() {
        val cappedUseCount = (1..100).fold(0) { count, _ ->
            LyricsCachePolicy.nextUseCount(count)
        }
        val dayMs = 24L * 60L * 60L * 1000L
        val oldFrequent = LyricsCachePolicy.evictionScore(1_000L, 5)
        val recentOccasional = LyricsCachePolicy.evictionScore(1_000L + 60L * dayMs, 1)

        assertEquals(30, cappedUseCount)
        assertTrue(recentOccasional > oldFrequent)
    }

    @Test
    fun `keeps nearby platform duration metadata in the same lookup window`() {
        val storedKey = LyricsCache.key("Twinkle", "Localized Artist", "Twinkle Mini Album", 208_000L)
        val lookupKeys = LyricsCache.lookupKeys(
            "Twinkle",
            "Localized Artist",
            "Twinkle Mini Album",
            206_796L
        )

        assertTrue(storedKey in lookupKeys)
    }

    @Test
    fun `separates a duration mismatch larger than the recording tolerance`() {
        val wrongVersionKey = LyricsCache.key("World", "Artist", "Album", 255_546L)
        val lookupKeys = LyricsCache.lookupKeys("World", "Artist", "Album", 258_763L)

        assertFalse(wrongVersionKey in lookupKeys)
        assertFalse(LyricsCandidateSelector.hasMatchingDuration(258_763L, 255_546L))
    }

    @Test
    fun `refreshes a legacy cache entry once to discover source translation support`() {
        val legacyEntry = LyricsCache.Entry(
            result = DirectLyricsRepository.Result(
                lyrics = "[00:01.00]Original lyric",
                durationMs = 200_000L,
                lyricsKind = LyricsKind.SYNCHRONIZED
            ),
            updatedAtMs = 10_000L,
            translationResolved = false
        )
        val currentEntry = legacyEntry.copy(translationResolved = true)

        assertTrue(legacyEntry.needsRefresh(10_001L))
        assertFalse(currentEntry.needsRefresh(10_001L))
    }
}
