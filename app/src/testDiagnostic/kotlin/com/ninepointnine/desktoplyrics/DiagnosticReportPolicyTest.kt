package com.ninepointnine.desktoplyrics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportPolicyTest {
    @Test
    fun `elapsed age alone does not hide actual state changes in the bounded trace`() {
        val original = snapshot()
        val later = JSONObject(original.toString()).put("observedAtElapsedRealtimeMs", 9_000)
        later.getJSONObject("playback").put("updateAgeMs", 8_000)
        assertEquals(DiagnosticReportPolicy.fingerprint(original), DiagnosticReportPolicy.fingerprint(later))
        assertEquals(1_000, original.getJSONObject("playback").getInt("updateAgeMs"))

        for ((field, value) in listOf("state" to 3, "positionMs" to 4_000, "updateElapsedRealtimeMs" to 8_000)) {
            val changed = JSONObject(later.toString())
            changed.getJSONObject("playback").put(field, value)
            assertNotEquals(DiagnosticReportPolicy.fingerprint(original), DiagnosticReportPolicy.fingerprint(changed))
        }
        later.getJSONObject("metadata").put("title", "Second song")
        assertNotEquals(DiagnosticReportPolicy.fingerprint(original), DiagnosticReportPolicy.fingerprint(later))
    }

    @Test
    fun `query failure cannot be reported as an uninstalled application`() {
        val failed = DiagnosticReportPolicy.packageFailure("example.player", "SecurityException", false)
        assertTrue(failed.isNull("installed"))
        assertEquals("query_failed", failed.getString("queryStatus"))
        assertEquals("SecurityException", failed.getString("error"))
        val missing = DiagnosticReportPolicy.packageFailure("example.player", "NameNotFoundException", true)
        assertTrue(missing.isNull("installed"))
        assertEquals("not_found_or_not_visible", missing.getString("queryStatus"))
    }

    private fun snapshot() = JSONObject().put("session", "session_1")
        .put("observedAtElapsedRealtimeMs", 2_000)
        .put("playback", JSONObject().put("state", 2).put("positionMs", 3_000)
            .put("updateElapsedRealtimeMs", 1_000).put("updateAgeMs", 1_000))
        .put("metadata", JSONObject().put("title", "First song"))
}
