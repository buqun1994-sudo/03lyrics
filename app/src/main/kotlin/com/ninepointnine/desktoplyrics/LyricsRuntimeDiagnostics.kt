package com.ninepointnine.desktoplyrics

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Main-thread observation only. Outside an explicit lease, event payloads are not built. */
internal object LyricsRuntimeDiagnostics {
    private val handler = Handler(Looper.getMainLooper())
    private var reader: (() -> JSONObject)? = null
    private var trace: MediaDiagnosticTrace? = null
    private var lease = ""
    private var deadlineMs = 0L
    private var finishedAtMs = 0L
    private var endReason = ""
    private var initial = JSONObject()
    private var final = JSONObject()
    private var timeout: Runnable? = null
    private val sessionIds = linkedMapOf<String, String>()

    fun sessionId(token: String?): Any = token?.let {
        sessionIds.getOrPut(it) { "session_" + (sessionIds.size + 1) }
    } ?: JSONObject.NULL

    fun attach(snapshot: () -> JSONObject) {
        reader = snapshot
        record("service_started", snapshot)
    }

    fun detach() {
        record("service_stopped") { JSONObject() }
        reader = null
    }

    fun record(kind: String, data: () -> JSONObject) {
        if (trace == null || endReason.isNotEmpty() || SystemClock.elapsedRealtime() > deadlineMs) return
        runCatching { trace?.record(SystemClock.elapsedRealtime(), kind, data()) }
    }

    fun begin(): JSONObject {
        timeout?.let(handler::removeCallbacks)
        lease = UUID.randomUUID().toString()
        val now = SystemClock.elapsedRealtime()
        deadlineMs = now + 60_000L
        finishedAtMs = 0L
        endReason = ""
        sessionIds.clear()
        initial = current()
        final = JSONObject()
        trace = MediaDiagnosticTrace(now)
        val currentLease = lease
        timeout = Runnable { finish(currentLease, "deadline") }.also {
            handler.postDelayed(it, 60_000L)
        }
        return JSONObject().put("lease", lease).put("initial", initial)
    }

    fun finish(requestedLease: String, reason: String = "requested"): JSONObject {
        if (requestedLease != lease || trace == null) {
            return JSONObject().put("status", "lease_unavailable")
        }
        if (endReason.isEmpty()) {
            finishedAtMs = SystemClock.elapsedRealtime()
            final = current()
            endReason = reason
            timeout?.let(handler::removeCallbacks)
            timeout = null
        }
        return requireNotNull(trace).snapshot()
            .put("status", "complete")
            .put("endReason", endReason)
            .put("finishedAtElapsedRealtimeMs", finishedAtMs)
            .put("initial", initial)
            .put("final", final)
    }

    private fun current(): JSONObject = runCatching {
        reader?.invoke() ?: JSONObject().put("serviceRunning", false)
    }.getOrElse { JSONObject().put("snapshotError", it.javaClass.simpleName) }
}

/** call() performs its own permission check; ContentProvider does not enforce readPermission there. */
class MediaDiagnosticsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val app = requireNotNull(context)
        app.enforceCallingOrSelfPermission(
            app.packageName + ".permission.MEDIA_DIAGNOSTICS",
            "Same-signature media diagnostics only"
        )
        require(method == "begin" || method == "finish")
        val task = FutureTask {
            if (method == "begin") LyricsRuntimeDiagnostics.begin()
            else LyricsRuntimeDiagnostics.finish(arg.orEmpty())
        }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run()
        else Handler(Looper.getMainLooper()).post(task)
        return try {
            Bundle().apply { putString("report", task.get(2, TimeUnit.SECONDS).toString()) }
        } finally {
            task.cancel(false)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
