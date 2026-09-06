package com.ninepointnine.desktoplyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `cache lookup agrees with recording identity when display separators change`() {
        val tracker = MediaRecordingStateTracker()
        val original = MediaRecordingMetadata("Song (Live)", "Artist A / Artist B", "Live Album", 208_000L)
        val first = requireNotNull(tracker.update("source", original))
        val restored = requireNotNull(tracker.update(
            "source", original.copy(artist = "Artist A/Artist B")
        ))
        assertEquals(first.recordingGeneration, restored.recordingGeneration)
        assertFalse(restored.recordingChanged)
        assertFalse(restored.queryChanged)
        assertTrue(
            LyricsCache.key(original.track, original.artist, original.album, original.durationMs) in
                LyricsCache.lookupKeys(
                    restored.metadata.track, restored.metadata.artist,
                    restored.metadata.album, restored.metadata.durationMs
                )
        )
    }

    @Test
    fun `separates a duration mismatch larger than the recording tolerance`() {
        val wrongVersionKey = LyricsCache.key("World", "Artist", "Album", 255_546L)
        val lookupKeys = LyricsCache.lookupKeys("World", "Artist", "Album", 258_763L)

        assertFalse(wrongVersionKey in lookupKeys)
        assertFalse(LyricsCandidateSelector.hasMatchingDuration(258_763L, 255_546L))
    }

    @Test
    fun `recording index preserves version words and field boundaries`() {
        val live = LyricsCache.key("Song (Live)", "Artist", "Album", 208_000L)
        assertTrue(live == LyricsCache.key(" song-live ", "ARTIST", " Album ", 208_000L))
        assertFalse(live == LyricsCache.key("Song", "Artist", "Album", 208_000L))
        assertFalse(live == LyricsCache.key("Song (Remix)", "Artist", "Album", 208_000L))
        assertFalse(live == LyricsCache.key("Song", "Live Artist", "Album", 208_000L))
    }

    @Test
    fun `migration retains exact legacy binding only when the whole proof can be replayed`() {
        val result = migrationResult()
        val proof = LyricsSelectionProof(LYRICS_MATCHER_POLICY_VERSION, listOf(result.candidateSnapshot()))
        val payload = result.toJson().put("selectionProof", proof.toJson())
        val key = LyricsCache.legacyKey("Song", "Artist", "Album", result.durationMs)
        assertEquals(
            LyricsCache.key("Song", "Artist", "Album", result.durationMs),
            LyricsCache.legacyAutomaticRecordingKey(key, payload.toString())
        )
        assertNull(LyricsCache.legacyAutomaticRecordingKey("different-binding", payload.toString()))
        payload.put("candidateArtist", "Different Artist")
        assertNull(LyricsCache.legacyAutomaticRecordingKey(key, payload.toString()))
    }

    @Test
    fun `migration rejects missing old malformed and mismatched proofs`() {
        val result = migrationResult()
        val key = LyricsCache.legacyKey("Song", "Artist", "Album", result.durationMs)
        assertNull(LyricsCache.legacyAutomaticRecordingKey(key, "not-json"))
        assertNull(LyricsCache.legacyAutomaticRecordingKey(key, result.toJson().toString()))
        val oldProof = LyricsSelectionProof(LYRICS_MATCHER_POLICY_VERSION - 1, listOf(result.candidateSnapshot()))
        assertNull(LyricsCache.legacyAutomaticRecordingKey(key, result.toJson().put("selectionProof", oldProof.toJson()).toString()))
        val wrongProof = LyricsSelectionProof(LYRICS_MATCHER_POLICY_VERSION, listOf(result.copy(sourceId = "other").candidateSnapshot()))
        assertNull(LyricsCache.legacyAutomaticRecordingKey(key, result.toJson().put("selectionProof", wrongProof.toJson()).toString()))
    }

    @Test
    fun `new payload proves the original playback identity and duration as well as the candidate`() {
        val result = migrationResult()
        val proof = LyricsSelectionProof(LYRICS_MATCHER_POLICY_VERSION, listOf(result.candidateSnapshot()))
        val identity = LyricsPlaybackIdentity("Song", "Artist", "Album", result.durationMs + 1_000L)
        val payload = result.toJson().put("selectionProof", proof.toJson()).put("playbackIdentity", identity.toJson())
        val key = LyricsCache.legacyKey(identity.track, identity.artist, identity.album, result.durationMs)
        assertEquals(
            LyricsCache.key(identity.track, identity.artist, identity.album, result.durationMs),
            LyricsCache.legacyAutomaticRecordingKey(key, payload.toString())
        )
        payload.put("playbackIdentity", identity.copy(durationMs = result.durationMs + 10_000L).toJson())
        assertNull(LyricsCache.legacyAutomaticRecordingKey(key, payload.toString()))
    }

    @Test
    fun `legacy display variants reindex under the same recording after original binding validation`() {
        val result = migrationResult().copy(candidateArtist = "Artist A / Artist B")
        val proof = LyricsSelectionProof(LYRICS_MATCHER_POLICY_VERSION, listOf(result.candidateSnapshot()))
        val original = LyricsPlaybackIdentity(result.candidateTrack, result.candidateArtist, result.candidateAlbum, result.durationMs)
        val variant = original.copy(artist = "Artist A/Artist B")
        val oldKey = LyricsCache.legacyKey(original.track, original.artist, original.album, original.durationMs)
        val variantKey = LyricsCache.legacyKey(variant.track, variant.artist, variant.album, variant.durationMs)
        val canonical = LyricsCache.key(original.track, original.artist, original.album, original.durationMs)
        assertFalse(oldKey == variantKey)
        listOf(original to oldKey, variant to variantKey).forEach { (identity, address) ->
            val manual = result.toJson().put("playbackIdentity", identity.toJson()).toString()
            val automatic = result.toJson().put("playbackIdentity", identity.toJson())
                .put("selectionProof", proof.toJson()).toString()
            assertEquals(canonical, LyricsCache.legacyManualRecordingKey(address, manual))
            assertEquals(canonical, LyricsCache.legacyAutomaticRecordingKey(address, automatic))
            assertNull(LyricsCache.legacyManualRecordingKey("unrelated-row", manual))
            assertNull(LyricsCache.legacyAutomaticRecordingKey("unrelated-row", automatic))
        }
    }

    private fun migrationResult() = LyricsResult(
        lyrics = "[00:01.00]First line",
        durationMs = 200_000L,
        source = "test",
        sourceId = "song",
        candidateTrack = "Song",
        candidateArtist = "Artist",
        candidateAlbum = "Album",
        lyricsKind = LyricsKind.SYNCHRONIZED
    )

    @Test
    fun `refreshes a legacy cache entry once to discover source translation support`() {
        val result = LyricsResult(
            lyrics = "[00:01.00]Original lyric",
            durationMs = 200_000L,
            lyricsKind = LyricsKind.SYNCHRONIZED
        )
        val legacyEntry = LyricsCache.Entry(
            result = result,
            proof = LyricsSelectionProof(
                matcherPolicyVersion = LYRICS_MATCHER_POLICY_VERSION,
                supportingCandidates = listOf(result.candidateSnapshot())
            ),
            updatedAtMs = 10_000L,
            translationResolved = false
        )
        val currentEntry = legacyEntry.copy(translationResolved = true)

        assertTrue(legacyEntry.needsRefresh(10_001L))
        assertFalse(currentEntry.needsRefresh(10_001L))
    }
}
