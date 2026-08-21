package com.ninepointnine.desktoplyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionMetadataPolicyTest {
    @Test
    fun `controller description wins when a raw title carries changing lyric lines`() {
        val tracker = MediaRecordingStateTracker()
        val generations = (1..20).map { line ->
            val metadata = MediaSessionMetadataPolicy.normalize(
                MediaSessionMetadataFields(
                    descriptionTitle = "轻轻地告诉你",
                    descriptionSubtitle = "杨钰莹",
                    title = "第 $line 行歌词",
                    artist = "杨钰莹",
                    album = "毛宁+杨钰莹-上海金秋演唱会",
                    durationMs = 238_000L
                )
            )
            tracker.update("online-session", metadata)?.recordingGeneration
        }

        assertEquals(setOf(1L), generations.toSet())
    }

    @Test
    fun `raw standard fields remain a valid fallback for bluetooth and local players`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                title = "跨时代",
                artist = "周杰伦",
                album = "跨时代",
                durationMs = 234_000L
            )
        )

        assertEquals("跨时代", metadata.track)
        assertEquals("周杰伦", metadata.artist)
        assertEquals("跨时代", metadata.album)
        assertEquals(234_000L, metadata.durationMs)
    }

    @Test
    fun `missing optional fields enrich one recording without creating a new generation`() {
        val tracker = MediaRecordingStateTracker()
        val first = requireNotNull(
            tracker.update(
                "session",
                MediaRecordingMetadata("Song", "", "", 0L)
            )
        )
        val enriched = requireNotNull(
            tracker.update(
                "session",
                MediaRecordingMetadata("Song", "Artist", "Album", 180_000L)
            )
        )

        assertEquals(first.recordingGeneration, enriched.recordingGeneration)
        assertTrue(enriched.queryChanged)
        assertEquals(first.queryRevision + 1L, enriched.queryRevision)
        assertEquals("Artist", enriched.metadata.artist)
        assertEquals("Album", enriched.metadata.album)
    }

    @Test
    fun `known metadata is not degraded by a transient empty callback`() {
        val tracker = MediaRecordingStateTracker()
        val first = requireNotNull(
            tracker.update(
                "session",
                MediaRecordingMetadata("Song", "Artist", "Album", 180_000L)
            )
        )
        val transient = requireNotNull(
            tracker.update(
                "session",
                MediaRecordingMetadata("", "", "", 0L)
            )
        )

        assertEquals(first.recordingGeneration, transient.recordingGeneration)
        assertEquals(first.metadata, transient.metadata)
        assertFalse(transient.queryChanged)
    }

    @Test
    fun `source or real recording changes advance the generation`() {
        val tracker = MediaRecordingStateTracker()
        val first = requireNotNull(
            tracker.update(
                "bluetooth",
                MediaRecordingMetadata("Song", "Artist", "Album", 180_000L)
            )
        )
        val sourceChanged = requireNotNull(
            tracker.update(
                "online",
                MediaRecordingMetadata("Song", "Artist", "Album", 180_000L)
            )
        )
        val trackChanged = requireNotNull(
            tracker.update(
                "online",
                MediaRecordingMetadata("Next Song", "Artist", "Album", 200_000L)
            )
        )

        assertEquals(first.recordingGeneration + 1L, sourceChanged.recordingGeneration)
        assertEquals(sourceChanged.recordingGeneration + 1L, trackChanged.recordingGeneration)
        assertTrue(sourceChanged.recordingChanged)
        assertTrue(trackChanged.recordingChanged)
    }

    @Test
    fun `duration only revises a query when it crosses a meaningful boundary`() {
        val tracker = MediaRecordingStateTracker()
        val first = requireNotNull(
            tracker.update("session", MediaRecordingMetadata("Song", "Artist", "", 180_000L))
        )
        val smallDrift = requireNotNull(
            tracker.update("session", MediaRecordingMetadata("Song", "Artist", "", 181_500L))
        )
        val materialDrift = requireNotNull(
            tracker.update("session", MediaRecordingMetadata("Song", "Artist", "", 184_001L))
        )

        assertEquals(first.queryRevision, smallDrift.queryRevision)
        assertFalse(smallDrift.queryChanged)
        assertEquals(first.queryRevision + 1L, materialDrift.queryRevision)
        assertTrue(materialDrift.queryChanged)
    }

    @Test
    fun `small cumulative duration changes are measured from the last query`() {
        val tracker = MediaRecordingStateTracker()
        val first = requireNotNull(
            tracker.update("session", MediaRecordingMetadata("Song", "Artist", "", 180_000L))
        )
        requireNotNull(
            tracker.update("session", MediaRecordingMetadata("Song", "Artist", "", 181_000L))
        )
        requireNotNull(
            tracker.update("session", MediaRecordingMetadata("Song", "Artist", "", 182_000L))
        )
        val cumulativeDrift = requireNotNull(
            tracker.update("session", MediaRecordingMetadata("Song", "Artist", "", 182_001L))
        )

        assertEquals(first.queryRevision + 1L, cumulativeDrift.queryRevision)
        assertTrue(cumulativeDrift.queryChanged)
    }
}
