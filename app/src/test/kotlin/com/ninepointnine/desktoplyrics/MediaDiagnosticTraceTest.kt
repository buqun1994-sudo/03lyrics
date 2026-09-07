package com.ninepointnine.desktoplyrics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDiagnosticTraceTest {
    @Test
    fun `events capture values and preserve sequence after eviction`() {
        val trace = MediaDiagnosticTrace(100, maxEvents = 2)
        val value = JSONObject().put("state", "paused")
        trace.record(101, "baseline", value)
        value.put("state", "playing")
        trace.record(102, "playback", value)
        value.put("state", "stopped")
        trace.record(103, "playback", value)
        val report = trace.snapshot()
        assertEquals(3, report.getInt("recordedEventCount"))
        assertEquals(1, report.getInt("droppedEventCount"))
        assertTrue(report.getBoolean("truncated"))
        val events = report.getJSONArray("events")
        assertEquals(2, events.getJSONObject(0).getInt("sequence"))
        assertEquals(2, events.getJSONObject(0).getInt("offsetMs"))
        assertEquals("playing", events.getJSONObject(0).getJSONObject("data").getString("state"))
    }

    @Test
    fun `byte budget measures utf8 and oversized events are reported`() {
        val trace = MediaDiagnosticTrace(0, maxBytes = 300)
        trace.record(0, "test", JSONObject().put("value", "\u4e2d".repeat(200)))
        trace.record(1, "test", JSONObject().put("state", "paused"))
        val report = trace.snapshot()
        assertEquals(1, report.getInt("retainedEventCount"))
        assertEquals(1, report.getInt("droppedEventCount"))
        assertTrue(report.getInt("retainedEventBytes") <= 300)
        val clean = MediaDiagnosticTrace(0)
        assertFalse(clean.snapshot().getBoolean("truncated"))
    }
}
