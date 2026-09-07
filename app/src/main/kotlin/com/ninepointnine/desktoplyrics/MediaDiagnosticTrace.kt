package com.ninepointnine.desktoplyrics

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/** Bounded, immutable JSON events shared by external and in-process diagnostics. */
internal class MediaDiagnosticTrace(
    val startedAtMs: Long,
    private val maxEvents: Int = 256,
    private val maxBytes: Int = 192 * 1024
) {
    private data class Entry(val json: String, val bytes: Int)
    private val entries = ArrayDeque<Entry>()
    private var bytes = 0
    private var recorded = 0L
    private var dropped = 0L

    init {
        require(maxEvents > 0 && maxBytes > 0)
    }

    fun record(atMs: Long, kind: String, data: JSONObject) {
        val encoded = JSONObject()
            .put("sequence", ++recorded)
            .put("elapsedRealtimeMs", atMs)
            .put("offsetMs", atMs - startedAtMs)
            .put("kind", kind)
            .put("data", data)
            .toString()
        val entry = Entry(encoded, encoded.toByteArray(Charsets.UTF_8).size)
        if (entry.bytes > maxBytes) {
            dropped++
            return
        }
        while (entries.size >= maxEvents || bytes + entry.bytes > maxBytes) {
            bytes -= entries.removeFirst().bytes
            dropped++
        }
        entries.addLast(entry)
        bytes += entry.bytes
    }

    fun snapshot(): JSONObject = JSONObject()
        .put("startedAtElapsedRealtimeMs", startedAtMs)
        .put("recordedEventCount", recorded)
        .put("retainedEventCount", entries.size)
        .put("droppedEventCount", dropped)
        .put("truncated", dropped != 0L)
        .put("retainedEventBytes", bytes)
        .put("events", JSONArray(entries.map { JSONObject(it.json) }))
}
