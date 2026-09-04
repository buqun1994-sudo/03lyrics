package com.ninepointnine.desktoplyrics

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class DiagnosticReportFiles(
    val json: File,
    val text: File,
)

internal object DiagnosticReportWriter {
    fun write(context: Context, report: JSONObject): DiagnosticReportFiles {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        directory.mkdirs()
        val jsonFile = File(directory, "03lyrics-03cast-diagnostic-$stamp.json")
        val textFile = File(directory, "03lyrics-03cast-diagnostic-$stamp.txt")
        val json = report.toString(2)
        jsonFile.writeText(json, Charsets.UTF_8)
        textFile.writeText(
            buildString {
                appendLine("03歌词 / 03投屏诊断报告")
                appendLine("schemaVersion=${report.optInt("schemaVersion", 1)}")
                appendLine("observedAtEpochMs=${report.optLong("observedAtEpochMs", 0L)}")
                appendLine()
                appendLine(json)
                appendLine()
                appendLine("崩溃日志需要使用主机侧 ADB 伴随脚本采集；APK 无权读取其它应用的 logcat/tombstone。")
            },
            Charsets.UTF_8,
        )
        return DiagnosticReportFiles(jsonFile, textFile)
    }
}
