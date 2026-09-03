package com.ninepointnine.desktoplyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionMetadataPolicyTest {
    @Test
    fun `standard duration remains milliseconds by default`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(title = "Song", artist = "Artist", durationMs = 214_000L)
        )
        assertEquals(214_000L, metadata.durationMs)
    }

    @Test
    fun `android 10 bluetooth browser duration is converted from seconds`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                title = "Song", artist = "Artist", durationMs = 214L,
                durationUnit = MediaSessionDurationUnit.SECONDS
            )
        )
        assertEquals(214_000L, metadata.durationMs)
    }

    @Test
    fun `duration conversion treats nonpositive values as unknown`() {
        listOf(0L, -1L).forEach { rawDuration ->
            val metadata = MediaSessionMetadataPolicy.normalize(
                MediaSessionMetadataFields(
                    title = "Song", artist = "Artist", durationMs = rawDuration,
                    durationUnit = MediaSessionDurationUnit.SECONDS
                )
            )
            assertEquals(0L, metadata.durationMs)
        }
    }

    @Test
    fun `duration conversion caps milliseconds and seconds without overflow`() {
        val fromSeconds = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                title = "Song", artist = "Artist", durationMs = Long.MAX_VALUE,
                durationUnit = MediaSessionDurationUnit.SECONDS
            )
        )
        val fromMilliseconds = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                title = "Song", artist = "Artist", durationMs = Long.MAX_VALUE,
                durationUnit = MediaSessionDurationUnit.MILLISECONDS
            )
        )
        assertEquals(86_400_000L, fromSeconds.durationMs)
        assertEquals(86_400_000L, fromMilliseconds.durationMs)
    }

    @Test
    fun `position evidence can identify an otherwise unknown seconds duration`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                title = "Song", artist = "Artist", durationMs = 214L,
                durationUnit = MediaSessionDurationUnit.UNKNOWN, reportedPositionMs = 5_000L
            )
        )
        assertEquals(214_000L, metadata.durationMs)
    }

    @Test
    fun `unknown duration unit does not guess at the evidence boundary`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                title = "Song", artist = "Artist", durationMs = 214L,
                durationUnit = MediaSessionDurationUnit.UNKNOWN, reportedPositionMs = 2_214L
            )
        )
        assertEquals(0L, metadata.durationMs)
    }

    @Test
    fun `bluetooth uses the same title artist and album semantics as other sessions`() {
        val standard = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                descriptionTitle = "寂寞不痛", descriptionSubtitle = "A-Lin",
                descriptionDescription = "寂寞不痛", title = "寂寞不痛", artist = "A-Lin",
                album = "寂寞不痛", durationMs = 298_887L,
                transport = MediaSessionTransport.STANDARD
            )
        )
        val bluetooth = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                descriptionTitle = "寂寞不痛", descriptionSubtitle = "A-Lin",
                descriptionDescription = "寂寞不痛", title = "寂寞不痛", artist = "A-Lin",
                album = "寂寞不痛", durationMs = 298_887L,
                transport = MediaSessionTransport.BLUETOOTH_AVRCP
            )
        )
        assertEquals(standard, bluetooth)
        assertEquals("寂寞不痛", bluetooth.track)
        assertEquals("A-Lin", bluetooth.artist)
        assertEquals("寂寞不痛", bluetooth.album)
    }

    @Test
    fun `bluetooth never decodes a composite looking artist field`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                descriptionTitle = "当前标题",
                descriptionSubtitle = "歌曲名-AAAAAA-BBBBBBBBB",
                title = "当前标题",
                artist = "歌曲名-AAAAAA-BBBBBBBBB",
                album = "当前专辑",
                transport = MediaSessionTransport.BLUETOOTH_AVRCP
            )
        )
        assertEquals("当前标题", metadata.track)
        assertEquals("歌曲名-AAAAAA-BBBBBBBBB", metadata.artist)
        assertEquals("当前专辑", metadata.album)
    }

    @Test
    fun `bluetooth preserves every supported dash inside an artist name`() {
        listOf(
            "A-Lin", "SUPER JUNIOR-M", "Sia-Furler", "张三-李四", "Artist‐One",
            "Artist‑Two", "Artist–Three", "Artist—Four", "Artist－Five"
        ).forEach { artist ->
            val metadata = MediaSessionMetadataPolicy.normalize(
                MediaSessionMetadataFields(
                    title = "Song", artist = artist, album = "Album",
                    transport = MediaSessionTransport.BLUETOOTH_AVRCP
                )
            )
            assertEquals("Song", metadata.track)
            assertEquals(artist, metadata.artist)
        }
    }

    @Test
    fun `media description stays the controller facing fallback contract`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                descriptionTitle = "Published Song", descriptionSubtitle = "Published Artist",
                descriptionDescription = "Published Album", displayTitle = "Display Song",
                displaySubtitle = "Display Artist", displayDescription = "Display Album",
                title = "Raw Song", artist = "Raw Artist", album = "Raw Album",
                transport = MediaSessionTransport.BLUETOOTH_AVRCP
            )
        )
        assertEquals("Published Song", metadata.track)
        assertEquals("Published Artist", metadata.artist)
        assertEquals("Raw Album", metadata.album)
    }

    @Test
    fun `controller description wins when a raw title carries changing text`() {
        val tracker = MediaRecordingStateTracker()
        val generations = (1..20).map { line ->
            val metadata = MediaSessionMetadataPolicy.normalize(
                MediaSessionMetadataFields(
                    descriptionTitle = "轻轻地告诉你", descriptionSubtitle = "杨钰莹",
                    title = "第 $line 行文字", artist = "杨钰莹",
                    album = "毛宁+杨钰莹-上海金秋演唱会", durationMs = 238_000L
                )
            )
            tracker.update("online-session", metadata)?.recordingGeneration
        }
        assertEquals(setOf(1L), generations.toSet())
    }

    @Test
    fun `raw standard fields remain a valid fallback for bluetooth and local players`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(title = "跨时代", artist = "周杰伦", album = "跨时代", durationMs = 234_000L)
        )
        assertEquals("跨时代", metadata.track)
        assertEquals("周杰伦", metadata.artist)
        assertEquals("跨时代", metadata.album)
        assertEquals(234_000L, metadata.durationMs)
    }

    @Test
    fun `missing optional fields enrich one recording without creating a new generation`() {
        val tracker = MediaRecordingStateTracker()
        val first = requireNotNull(tracker.update("session", MediaRecordingMetadata("Song", "", "", 0L)))
        val enriched = requireNotNull(
            tracker.update("session", MediaRecordingMetadata("Song", "Artist", "Album", 180_000L))
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
            tracker.update("session", MediaRecordingMetadata("Song", "Artist", "Album", 180_000L))
        )
        val transient = requireNotNull(tracker.update("session", MediaRecordingMetadata("", "", "", 0L)))
        assertEquals(first.recordingGeneration, transient.recordingGeneration)
        assertEquals(first.metadata, transient.metadata)
        assertFalse(transient.queryChanged)
    }

    @Test
    fun `source or real recording changes advance the generation`() {
        val tracker = MediaRecordingStateTracker()
        val first = requireNotNull(
            tracker.update("bluetooth", MediaRecordingMetadata("Song", "Artist", "Album", 180_000L))
        )
        val sourceChanged = requireNotNull(
            tracker.update("online", MediaRecordingMetadata("Song", "Artist", "Album", 180_000L))
        )
        val trackChanged = requireNotNull(
            tracker.update("online", MediaRecordingMetadata("Next Song", "Artist", "Album", 200_000L))
        )
        assertEquals(first.recordingGeneration + 1L, sourceChanged.recordingGeneration)
        assertEquals(sourceChanged.recordingGeneration + 1L, trackChanged.recordingGeneration)
        assertTrue(sourceChanged.recordingChanged)
        assertTrue(trackChanged.recordingChanged)
    }

    @Test
    fun `duration only revises a query when it crosses a meaningful boundary`() {
        val tracker = MediaRecordingStateTracker()
        val first = requireNotNull(tracker.update("session", MediaRecordingMetadata("Song", "Artist", "", 180_000L)))
        val smallDrift = requireNotNull(tracker.update("session", MediaRecordingMetadata("Song", "Artist", "", 181_500L)))
        val materialDrift = requireNotNull(tracker.update("session", MediaRecordingMetadata("Song", "Artist", "", 184_001L)))
        assertEquals(first.queryRevision, smallDrift.queryRevision)
        assertFalse(smallDrift.queryChanged)
        assertEquals(first.queryRevision + 1L, materialDrift.queryRevision)
        assertTrue(materialDrift.queryChanged)
    }

    @Test
    fun `small cumulative duration changes are measured from the last query`() {
        val tracker = MediaRecordingStateTracker()
        val first = requireNotNull(tracker.update("session", MediaRecordingMetadata("Song", "Artist", "", 180_000L)))
        requireNotNull(tracker.update("session", MediaRecordingMetadata("Song", "Artist", "", 181_000L)))
        requireNotNull(tracker.update("session", MediaRecordingMetadata("Song", "Artist", "", 182_000L)))
        val cumulativeDrift = requireNotNull(tracker.update("session", MediaRecordingMetadata("Song", "Artist", "", 182_001L)))
        assertEquals(first.queryRevision + 1L, cumulativeDrift.queryRevision)
        assertTrue(cumulativeDrift.queryChanged)
    }
}
