package com.ninepointnine.desktoplyrics

import org.json.JSONArray
import org.json.JSONObject

/** Retains observed recording facts across incomplete public session snapshots. */
internal class MediaRecordingFactsStore(
    private val read: () -> String? = { null },
    private val write: (String) -> Unit = {}
) {
    private data class Key(val sourceId: String, val mediaId: String)
    private data class Fact(
        val track: String,
        val artist: String,
        val album: String,
        val durationMs: Long
    )

    private val facts by lazy { decode(runCatching(read).getOrNull()) }

    fun resolve(sourceId: String, incoming: MediaRecordingMetadata): MediaRecordingMetadata {
        val key = Key(sourceId.trim(), incoming.mediaId)
        val fact = Fact(
            track = normalizeText(incoming.track),
            artist = normalizeText(incoming.artist),
            album = normalizeText(incoming.album),
            durationMs = incoming.durationMs
        )
        if (key.sourceId.isBlank() || key.mediaId.isBlank() || fact.track.isBlank()) return incoming
        val known = facts[key]
        if (fact.durationMs in VALID_DURATION_MS) {
            if (known != fact) {
                facts.remove(key)
                facts[key] = fact
                while (facts.size > MAX_RECORDINGS) facts.remove(facts.keys.first())
                runCatching { write(encode()) }
            }
            return incoming
        }
        if (incoming.durationMs != 0L || known == null ||
            fact.track != known.track || fact.artist != known.artist || fact.album != known.album
        ) return incoming
        return incoming.copy(durationMs = known.durationMs)
    }

    private fun encode(): String {
        val records = JSONArray()
        facts.forEach { (key, fact) ->
            records.put(JSONObject()
                .put("sourceId", key.sourceId)
                .put("mediaId", key.mediaId)
                .put("track", fact.track)
                .put("artist", fact.artist)
                .put("album", fact.album)
                .put("durationMs", fact.durationMs))
        }
        return JSONObject().put("version", VERSION).put("records", records).toString()
    }

    private fun decode(serialized: String?): LinkedHashMap<Key, Fact> {
        val result = linkedMapOf<Key, Fact>()
        val root = serialized?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return result
        if (root.optInt("version") != VERSION) return result
        val records = root.optJSONArray("records") ?: return result
        for (index in maxOf(0, records.length() - MAX_RECORDINGS) until records.length()) {
            runCatching {
                val record = records.getJSONObject(index)
                val key = Key(record.getString("sourceId"), record.getString("mediaId"))
                val fact = Fact(
                    track = normalizeText(record.getString("track")),
                    artist = normalizeText(record.getString("artist")),
                    album = normalizeText(record.getString("album")),
                    durationMs = record.getLong("durationMs")
                )
                if (key.sourceId.isNotBlank() && key.mediaId.isNotBlank() &&
                    fact.track.isNotBlank() && fact.durationMs in VALID_DURATION_MS
                ) result[key] = fact
            }
        }
        return result
    }

    private companion object {
        const val VERSION = 1
        const val MAX_RECORDINGS = 128
        val VALID_DURATION_MS = MediaRecordingStateTracker.MINIMUM_QUERY_DURATION_MS..86_400_000L
    }
}
