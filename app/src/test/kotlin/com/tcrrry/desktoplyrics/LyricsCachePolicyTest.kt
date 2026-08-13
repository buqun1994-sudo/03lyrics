package com.tcrrry.desktoplyrics

import org.junit.Assert.assertEquals
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
}
