package com.ninepointnine.desktoplyrics

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal object DiagnosticReportWriter {
    private const val PREFIX = "03lyrics-netease-"

    fun directory(context: Context): File = File(context.filesDir, "diagnostics")

    fun latest(directory: File): File? = reports(directory).maxByOrNull { it.lastModified() }

    fun write(directory: File, report: JSONObject): File {
        check(directory.isDirectory || directory.mkdirs()) { "Report directory unavailable" }
        val json = report.toString().toByteArray(Charsets.UTF_8)
        check(json.size <= 1024 * 1024) { "Report exceeds byte budget" }
        JSONObject(String(json, Charsets.UTF_8))
        val integrity = JSONObject().put("schemaVersion", 1).put("file", "report.json")
            .put("bytes", json.size).put("sha256", sha256(json)).toString().toByteArray(Charsets.UTF_8)
        val destination = File(directory, PREFIX + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".zip")
        val temporary = File(directory, destination.name + ".tmp")
        try {
            FileOutputStream(temporary).use { output ->
                ZipOutputStream(output).use { zip ->
                    listOf("report.json" to json, "integrity.json" to integrity).forEach { (name, bytes) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                    zip.finish()
                    output.fd.sync()
                }
            }
            verify(temporary)
            check(temporary.renameTo(destination)) { "Atomic report publication failed" }
            reports(directory).filterNot { it == destination }.sortedByDescending { it.lastModified() }
                .drop(2).forEach { it.delete() }
            return destination
        } finally {
            temporary.delete()
        }
    }

    fun verify(file: File) {
        ZipFile(file).use { zip ->
            check(zip.size() == 2) { "Unexpected archive contents" }
            val report = zip.getInputStream(requireNotNull(zip.getEntry("report.json"))).use { it.readBytes() }
            val manifest = JSONObject(zip.getInputStream(requireNotNull(zip.getEntry("integrity.json")))
                .bufferedReader(Charsets.UTF_8).use { it.readText() })
            check(report.size == manifest.getInt("bytes") && sha256(report) == manifest.getString("sha256"))
            check(manifest.getString("file") == "report.json")
            JSONObject(String(report, Charsets.UTF_8))
        }
    }

    private fun reports(directory: File): List<File> = directory.listFiles()
        .orEmpty().filter { it.isFile && it.name.startsWith(PREFIX) && it.extension == "zip" }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
