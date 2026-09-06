package com.ninepointnine.desktoplyrics

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaRecordingFactsStoreTest {
    private val complete = MediaRecordingMetadata("Song", "Artist A / Artist B", "Album", 180_321L, "item/42")

    @Test
    fun `observed duration survives a new store while current display text is retained`() {
        var serialized: String? = null
        MediaRecordingFactsStore(write = { serialized = it }).resolve("source", complete)
        val restarted = MediaRecordingFactsStore(read = { serialized })
        val incoming = complete.copy(artist = "Artist A/Artist B", durationMs = 0L)
        assertEquals(incoming.copy(durationMs = complete.durationMs), restarted.resolve("source", incoming))
    }

    @Test
    fun `source id and all recording fields must agree before missing duration is recovered`() {
        val store = MediaRecordingFactsStore()
        store.resolve("source", complete)
        listOf(
            complete.copy(mediaId = "item42"),
            complete.copy(mediaId = "ITEM/42"),
            complete.copy(mediaId = ""),
            complete.copy(track = "Song (Live)"),
            complete.copy(artist = "Other Artist"),
            complete.copy(artist = ""),
            complete.copy(album = "Other Album"),
            complete.copy(album = "")
        ).forEach { mismatch ->
            assertEquals(0L, store.resolve("source", mismatch.copy(durationMs = 0L)).durationMs)
        }
        assertEquals(0L, store.resolve("other.source", complete.copy(durationMs = 0L)).durationMs)
    }

    @Test
    fun `positive published duration always wins and a full correction replaces the saved fact`() {
        var serialized: String? = null
        val store = MediaRecordingFactsStore(write = { serialized = it })
        store.resolve("source", complete)
        assertEquals(500L, store.resolve("source", complete.copy(durationMs = 500L)).durationMs)
        val corrected = complete.copy(durationMs = 185_678L)
        assertEquals(corrected, store.resolve("source", corrected))
        assertEquals(
            corrected,
            MediaRecordingFactsStore(read = { serialized }).resolve("source", complete.copy(durationMs = 0L))
        )
    }

    @Test
    fun `incomplete observations and missing public identities never create saved facts`() {
        var writes = 0
        val store = MediaRecordingFactsStore(write = { writes++ })
        store.resolve("", complete)
        store.resolve("source", complete.copy(mediaId = ""))
        store.resolve("source", complete.copy(track = ""))
        listOf(-1L, 0L, 999L, 86_400_001L, Long.MAX_VALUE).forEach { duration ->
            store.resolve("source", complete.copy(durationMs = duration))
        }
        assertEquals(0, writes)
        assertEquals(0L, store.resolve("source", complete.copy(durationMs = 0L)).durationMs)
    }

    @Test
    fun `replayed snapshots and formatting changes do not repeatedly write storage`() {
        var writes = 0
        val store = MediaRecordingFactsStore(write = { writes++ })
        store.resolve("source", complete)
        repeat(20) {
            store.resolve("source", complete.copy(artist = "Artist A/Artist B"))
            store.resolve("source", complete.copy(durationMs = 0L))
        }
        assertEquals(1, writes)
    }

    @Test
    fun `a reused public id cannot bring duration from a different recording`() {
        val store = MediaRecordingFactsStore()
        store.resolve("source", complete)
        val next = complete.copy(track = "Different Song", durationMs = 210_000L)
        assertEquals(0L, store.resolve("source", next.copy(durationMs = 0L)).durationMs)
        store.resolve("source", next)
        assertEquals(0L, store.resolve("source", complete.copy(durationMs = 0L)).durationMs)
        assertEquals(next, store.resolve("source", next.copy(durationMs = 0L)))
    }

    @Test
    fun `persisted facts are bounded and evict the oldest changed recording`() {
        var serialized: String? = null
        val store = MediaRecordingFactsStore(write = { serialized = it })
        repeat(129) { index -> store.resolve("source", complete.copy(mediaId = "item/$index")) }
        assertEquals(128, JSONObject(requireNotNull(serialized)).getJSONArray("records").length())
        val restarted = MediaRecordingFactsStore(read = { serialized })
        assertEquals(0L, restarted.resolve("source", complete.copy(mediaId = "item/0", durationMs = 0L)).durationMs)
        assertEquals(
            complete.durationMs,
            restarted.resolve("source", complete.copy(mediaId = "item/128", durationMs = 0L)).durationMs
        )
    }

    @Test
    fun `damaged or unsupported persisted records do not manufacture recording facts`() {
        val record = JSONObject().put("sourceId", "source").put("mediaId", complete.mediaId)
            .put("track", "song").put("artist", "artistaartistb").put("album", "album")
            .put("durationMs", 0L)
        listOf(null, "not-json", "{}", "{\"version\":2,\"records\":[]}",
            JSONObject().put("version", 1).put("records", JSONArray().put(record).put(JSONObject())).toString()
        ).forEach { serialized ->
            assertEquals(
                0L,
                MediaRecordingFactsStore(read = { serialized }).resolve("source", complete.copy(durationMs = 0L)).durationMs
            )
        }
    }

    @Test
    fun `unavailable persistence cannot prevent live metadata from entering playback`() {
        val store = MediaRecordingFactsStore(
            read = { error("unavailable") },
            write = { error("unavailable") }
        )
        assertEquals(complete, store.resolve("source", complete))
    }
}
