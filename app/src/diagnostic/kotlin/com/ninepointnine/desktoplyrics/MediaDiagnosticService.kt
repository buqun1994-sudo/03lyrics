package com.ninepointnine.desktoplyrics

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MediaDiagnosticService : Service() {
    private data class RuntimeLease(val packageName: String, val lease: String?, val initial: JSONObject)

    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private var collector: MediaContractDiagnosticCollector? = null
    private var runtimeJob: Job? = null
    private var runtimeLeases = emptyList<RuntimeLease>()
    private var finishing = false
    private val timeout = Runnable { finish("deadline") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (collector == null && !finishing) startCapture()
        }
        if (collector == null && !finishing) stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun startCapture() {
        startedAtMs = SystemClock.elapsedRealtime()
        recording = true
        saving = false
        error = ""
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, getString(R.string.diagnostic_recording), NotificationManager.IMPORTANCE_LOW
        ).apply { setSound(null, null); enableVibration(false) })
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_lyrics)
            .setContentTitle(getString(R.string.diagnostic_recording))
            .setContentText(getString(R.string.diagnostic_notification))
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, DiagnosticActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        collector = MediaContractDiagnosticCollector(this, handler)
        try {
            collector?.start()
            runtimeJob = scope.launch {
                runtimeLeases = withContext(Dispatchers.IO) { beginRuntimeObservation() }
            }
            handler.postDelayed(timeout, (DURATION_MS - (SystemClock.elapsedRealtime() - startedAtMs)).coerceAtLeast(0L))
        } catch (failure: Exception) {
            error = failure.javaClass.simpleName
            finish("observation_start_failed")
        }
    }

    private fun finish(reason: String) {
        val running = collector ?: return
        collector = null
        handler.removeCallbacks(timeout)
        finishing = true
        recording = false
        saving = true
        val report = running.finish(reason)
        scope.launch {
            try {
                runtimeJob?.join()
                withContext(Dispatchers.IO) {
                    report.put("lyricsRuntimes", JSONArray(runtimeLeases.map(::finishRuntimeObservation)))
                    DiagnosticReportWriter.write(DiagnosticReportWriter.directory(this@MediaDiagnosticService), report)
                }
            } catch (failure: Exception) {
                error = failure.javaClass.simpleName
            } finally {
                saving = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun beginRuntimeObservation(): List<RuntimeLease> = RUNTIME_PACKAGES.map { target ->
        val initial = JSONObject().put("packageName", target)
        var lease: String? = null
        try {
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageInfo(target, 0)
            initial.put("versionName", info.versionName)
            if (packageManager.checkSignatures(packageName, target) != PackageManager.SIGNATURE_MATCH) {
                initial.put("status", "signature_mismatch")
            } else {
                val response = contentResolver.call(runtimeUri(target), "begin", null, null)
                    ?.getString("report") ?: error("Runtime interface unavailable")
                val value = JSONObject(response)
                lease = value.getString("lease")
                initial.put("status", "observing").put("initial", value.getJSONObject("initial"))
            }
        } catch (_: PackageManager.NameNotFoundException) {
            initial.put("status", "not_installed")
        } catch (failure: Exception) {
            initial.put("status", "unavailable").put("error", failure.javaClass.simpleName)
        }
        RuntimeLease(target, lease, initial)
    }

    private fun finishRuntimeObservation(value: RuntimeLease): JSONObject {
        if (value.lease == null) return value.initial
        return try {
            val response = contentResolver.call(runtimeUri(value.packageName), "finish", value.lease, null)
                ?.getString("report") ?: error("Runtime response missing")
            val trace = JSONObject(response)
            value.initial.put("status", if (trace.optString("status") == "complete") "complete" else "interrupted")
                .put("trace", trace)
        } catch (failure: Exception) {
            value.initial.put("status", "interrupted").put("finishError", failure.javaClass.simpleName)
        }
    }

    private fun runtimeUri(target: String): Uri = Uri.parse("content://" + target + ".media-diagnostics")

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        collector?.let { running ->
            runCatching {
                val report = running.finish("service_destroyed")
                    .put("lyricsRuntimeGap", "service_destroyed_before_collection")
                DiagnosticReportWriter.write(DiagnosticReportWriter.directory(this), report)
            }
        }
        collector = null
        recording = false
        saving = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val DURATION_MS = 60_000L
        const val ACTION_START = "diagnostic.START"
        private const val CHANNEL = "netease_media_diagnostic"
        private const val NOTIFICATION_ID = 4310
        private val RUNTIME_PACKAGES = listOf(
            "com.ninepointnine.desktoplyrics.test", "com.ninepointnine.desktoplyrics"
        )
        var recording = false
            private set
        var saving = false
            private set
        var startedAtMs = 0L
            private set
        var error = ""
            private set
    }
}
