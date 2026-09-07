package com.ninepointnine.desktoplyrics

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DiagnosticReportWriterTest {
    @Test
    fun `bounded unicode report survives compression and read back`() {
        withDirectory { directory ->
            val trace = MediaDiagnosticTrace(1)
            repeat(600) { index ->
                trace.record(index.toLong() + 1, "playback", JSONObject().put("title", "一样的月光").put("position", index))
            }
            val file = DiagnosticReportWriter.write(directory, JSONObject().put("schemaVersion", 2).put("trace", trace.snapshot()))
            DiagnosticReportWriter.verify(file)
            ZipFile(file).use { zip ->
                val report = JSONObject(zip.getInputStream(zip.getEntry("report.json")).bufferedReader().readText())
                assertTrue(report.getJSONObject("trace").getBoolean("truncated"))
                assertEquals(256, report.getJSONObject("trace").getInt("retainedEventCount"))
                assertEquals("一样的月光", report.getJSONObject("trace").getJSONArray("events")
                    .getJSONObject(0).getJSONObject("data").getString("title"))
            }
            assertEquals(file, DiagnosticReportWriter.latest(directory))
            assertTrue(directory.listFiles().orEmpty().none { it.extension == "tmp" })
        }
    }

    @Test
    fun `retention only removes old diagnostic archives`() {
        withDirectory { directory ->
            val unrelated = File(directory, "user.zip").apply { writeText("keep") }
            repeat(5) { index ->
                DiagnosticReportWriter.write(directory, JSONObject().put("iteration", index)).setLastModified(100L + index)
            }
            assertTrue(unrelated.exists())
            assertEquals(3, directory.listFiles().orEmpty().count { it.name.startsWith("03lyrics-netease-") })
        }
    }

    @Test
    fun `valid JSON with a mismatched integrity manifest is rejected`() {
        withDirectory { directory ->
            val file = File(directory, "damaged.zip")
            ZipOutputStream(file.outputStream()).use { zip ->
                listOf("report.json" to "{}", "integrity.json" to
                    JSONObject().put("bytes", 2).put("sha256", "wrong").put("file", "report.json").toString()
                ).forEach { (name, data) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data.toByteArray())
                    zip.closeEntry()
                }
            }
            assertTrue(runCatching { DiagnosticReportWriter.verify(file) }.isFailure)
        }
    }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("netease-report-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
