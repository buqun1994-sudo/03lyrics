package com.ninepointnine.desktoplyrics

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject

/** One passive observation window; Browser probing follows a separate baseline. */
internal class MediaContractDiagnosticCollector(
    private val context: Context,
    private val handler: Handler
) {
    private data class Observed(
        val id: String,
        val controller: MediaController,
        val callback: MediaController.Callback
    )

    private val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val component = ComponentName(context, MediaListenerService::class.java)
    private val startedAtMs = SystemClock.elapsedRealtime()
    private val startedAtEpochMs = System.currentTimeMillis()
    private val trace = MediaDiagnosticTrace(startedAtMs)
    private var active = emptyList<MediaController>()
    private val observed = linkedMapOf<MediaSession.Token, Observed>()
    private val sessionIds = linkedMapOf<MediaSession.Token, String>()
    private var nextId = 0
    private var closed = false
    private var probing = false
    private var targetSeen = false
    private val targetStates = linkedSetOf<Int>()
    private val marks = mutableListOf<JSONObject>()
    private var baseline = JSONObject()
    private val registry = PublicMediaBrowserSessionRegistry(
        context, handler,
        object : PublicMediaBrowserSessionRegistry.Listener {
            override fun onSessionsChanged(sessions: List<PublicMediaBrowserSession>) {
                if (!closed) synchronizeControllers("browser_sessions")
            }

            override fun onStateChanged(
                descriptor: PublicMediaBrowserServiceDescriptor,
                state: PublicMediaBrowserConnectionState
            ) {
                record("browser_connection", JSONObject()
                    .put("component", descriptor.sourceKey)
                    .put("state", state.name)
                    .put("durationUnit", descriptor.durationUnit.name))
            }
        }
    )
    private val activeListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        if (!closed) {
            active = sessions.orEmpty()
            synchronizeControllers("active_sessions")
            if (probing) refreshBrowsers()
        }
    }
    private val audioListener = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            record("audio_activity", audioSnapshot().put("configurationCount", configs?.size ?: 0))
        }
    }
    private val beginProbes = Runnable {
        if (!closed) {
            probing = true
            record("browser_probe_phase_started", JSONObject())
            refreshBrowsers()
        }
    }

    fun start() {
        // Capture the untouched state before attaching any Browser clients.
        active = manager.getActiveSessions(component).orEmpty()
        baseline = JSONObject()
            .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("audio", audioSnapshot())
            .put("activeSessions", JSONArray(active.map { controller ->
                sessionSnapshot(controller, sessionId(controller))
            }))
        record("baseline", baseline)
        manager.addOnActiveSessionsChangedListener(activeListener, component, handler)
        active = manager.getActiveSessions(component).orEmpty()
        synchronizeControllers("active_sessions_initial")
        runCatching { audio.registerAudioPlaybackCallback(audioListener, handler) }
            .onFailure { recordError("audio_callback_registration", it) }
        handler.postDelayed(beginProbes, BASELINE_WINDOW_MS)
    }

    fun mark(value: String) {
        if (closed || value !in MARKERS || marks.size >= 64) return
        val mark = JSONObject()
            .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("marker", value)
            .put("evidence", "user_reported")
        marks += mark
        record("user_marker", mark)
        observed.values.toList().forEach { capture("state_at_user_marker", it) }
    }

    fun finish(reason: String): JSONObject {
        val finalSessions = observed.values.map { sessionSnapshot(it.controller, it.id) }
        val finalAudio = audioSnapshot()
        closed = true
        handler.removeCallbacks(beginProbes)
        val cleanupErrors = JSONArray()
        fun cleanup(action: () -> Unit) {
            runCatching(action).onFailure { cleanupErrors.put(it.javaClass.simpleName) }
        }
        cleanup { manager.removeOnActiveSessionsChangedListener(activeListener) }
        cleanup { audio.unregisterAudioPlaybackCallback(audioListener) }
        observed.values.forEach { entry -> cleanup { entry.controller.unregisterCallback(entry.callback) } }
        cleanup { registry.disconnect() }
        observed.clear()
        val finishedAtMs = SystemClock.elapsedRealtime()
        return JSONObject()
            .put("schemaVersion", 2)
            .put("kind", "netease_media_events")
            .put("startedAtEpochMs", startedAtEpochMs)
            .put("startedAtElapsedRealtimeMs", startedAtMs)
            .put("finishedAtElapsedRealtimeMs", finishedAtMs)
            .put("durationMs", finishedAtMs - startedAtMs)
            .put("endReason", reason)
            .put("captureCompleted", reason == "deadline" || reason == "user_finished")
            .put("app", JSONObject().put("packageName", context.packageName)
                .put("versionName", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE))
            .put("device", JSONObject().put("model", Build.MODEL).put("sdkInt", Build.VERSION.SDK_INT))
            .put("target", packageSummary(TARGET_PACKAGE))
            .put("notificationListenerAccessGranted", hasAccess(context))
            .put("baseline", baseline)
            .put("trace", trace.snapshot())
            .put("userMarkers", JSONArray(marks))
            .put("finalSessions", JSONArray(finalSessions))
            .put("finalAudio", finalAudio)
            .put("cleanupErrors", cleanupErrors)
            .put("observations", JSONObject()
                .put("targetSeen", targetSeen)
                .put("targetStates", JSONArray(targetStates.toList()))
                .put("targetPlayingObserved", PlaybackState.STATE_PLAYING in targetStates)
                .put("targetPausedObserved", PlaybackState.STATE_PAUSED in targetStates)
                .put("pauseCauseAvailableFromPublicApi", false)
                .put("globalAudioAttributedToPackage", false))
    }

    private fun refreshBrowsers() {
        val descriptors = PublicMediaBrowserServiceResolver.discover(
            context.packageManager, setOf(context.packageName)
        )
        val eligible = active.mapTo(mutableSetOf(TARGET_PACKAGE)) { it.packageName }
        val selected = PublicMediaBrowserServiceResolver.select(descriptors, eligiblePackages = eligible)
        record("browser_inventory", JSONObject()
            .put("discovered", JSONArray(descriptors.map { it.sourceKey }))
            .put("selectedForObservation", JSONArray(selected.map { it.sourceKey }))
            .put("omittedByBudget", JSONArray((descriptors - selected.toSet()).map { it.sourceKey })))
        registry.refresh(eligible, null, bluetoothRoutePresent = false, discoverAllSources = true)
    }

    private fun synchronizeControllers(kind: String) {
        val controllers = (active + registry.currentSessions.map { it.controller })
            .distinctBy { it.sessionToken }
        val tokens = controllers.mapTo(mutableSetOf()) { it.sessionToken }
        observed.keys.filterNot(tokens::contains).forEach { token ->
            val entry = observed.remove(token) ?: return@forEach
            capture("session_removed", entry)
            runCatching { entry.controller.unregisterCallback(entry.callback) }
        }
        controllers.forEach { controller ->
            if (controller.sessionToken !in observed) {
                val id = sessionId(controller)
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        if (controller.packageName == TARGET_PACKAGE) state?.state?.let(targetStates::add)
                        changed("playback_callback", "callbackPlayback", playbackSnapshot(state))
                    }
                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        changed("metadata_callback", "callbackMetadata", JSONObject()
                            .put("present", metadata != null)
                            .put("title", metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.take(160))
                            .put("artist", metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.take(160))
                            .put("mediaId", metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.take(160))
                            .put("durationMs", metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)))
                    }
                    override fun onAudioInfoChanged(info: MediaController.PlaybackInfo?) = changed("audio_info_callback")
                    override fun onSessionEvent(event: String, extras: Bundle?) {
                        record("session_event", JSONObject().put("session", id).put("name", event.take(128)))
                    }
                    override fun onSessionDestroyed() {
                        if (closed) return
                        record("session_destroyed", JSONObject().put("session", id))
                        registry.onSessionDestroyed(controller.sessionToken)
                        active = active.filterNot { it.sessionToken == controller.sessionToken }
                        synchronizeControllers("session_destroyed_refresh")
                    }
                    private fun changed(event: String, callbackKey: String? = null, value: JSONObject? = null) {
                        if (!closed) observed[controller.sessionToken]?.let { entry ->
                            val snapshot = sessionSnapshot(entry.controller, entry.id)
                            if (callbackKey != null) snapshot.put(callbackKey, value)
                            record(event, snapshot)
                        }
                    }
                }
                val entry = Observed(id, controller, callback)
                observed[controller.sessionToken] = entry
                runCatching { controller.registerCallback(callback, handler) }
                    .onFailure { recordError("controller_callback_registration", it) }
                capture("session_observed", entry)
            }
        }
        record(kind, JSONObject().put("sessions", JSONArray(observed.values.map { entry ->
            JSONObject().put("session", entry.id).put("packageName", entry.controller.packageName)
                .put("activeInSystemList", active.any { it.sessionToken == entry.controller.sessionToken })
        })))
    }

    private fun capture(kind: String, entry: Observed) {
        record(kind, sessionSnapshot(entry.controller, entry.id))
    }

    private fun sessionSnapshot(controller: MediaController, id: String): JSONObject = runCatching {
        val metadata = controller.metadata
        val playback = controller.playbackState
        val audioInfo = controller.playbackInfo
        val description = metadata?.description
        val browser = registry.currentSessions.firstOrNull { it.controller.sessionToken == controller.sessionToken }
        val unit = browser?.descriptor?.durationUnit ?: MediaSessionDurationUnit.MILLISECONDS
        fun field(key: String) = metadata?.getString(key).orEmpty().take(160)
        val fields = MediaSessionMetadataFields(
            title = field(MediaMetadata.METADATA_KEY_TITLE),
            artist = field(MediaMetadata.METADATA_KEY_ARTIST),
            album = field(MediaMetadata.METADATA_KEY_ALBUM),
            displayTitle = field(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            displaySubtitle = field(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            displayDescription = field(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION),
            descriptionTitle = description?.title?.toString().orEmpty().take(160),
            descriptionSubtitle = description?.subtitle?.toString().orEmpty().take(160),
            descriptionDescription = description?.description?.toString().orEmpty().take(160),
            albumArtist = field(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            author = field(MediaMetadata.METADATA_KEY_AUTHOR),
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            durationUnit = unit,
            reportedPositionMs = playback?.position ?: -1L,
            mediaId = field(MediaMetadata.METADATA_KEY_MEDIA_ID).ifBlank { description?.mediaId.orEmpty().take(160) }
        )
        val normalized = MediaSessionMetadataPolicy.normalize(fields)
        if (controller.packageName == TARGET_PACKAGE) {
            targetSeen = true
            playback?.state?.let(targetStates::add)
        }
        val now = SystemClock.elapsedRealtime()
        JSONObject().put("session", id).put("packageName", controller.packageName)
            .put("observedAtElapsedRealtimeMs", now)
            .put("activeInSystemList", active.any { it.sessionToken == controller.sessionToken })
            .put("browserComponent", browser?.descriptor?.sourceKey ?: JSONObject.NULL)
            .put("audio", JSONObject()
                .put("usage", audioInfo?.audioAttributes?.usage ?: JSONObject.NULL)
                .put("contentType", audioInfo?.audioAttributes?.contentType ?: JSONObject.NULL)
                .put("playbackType", audioInfo?.playbackType ?: JSONObject.NULL))
            .put("playback", playbackSnapshot(playback))
            .put("metadata", JSONObject()
                .put("present", metadata != null).put("mediaId", fields.mediaId)
                .put("title", fields.title).put("artist", fields.artist).put("album", fields.album)
                .put("displayTitle", fields.displayTitle).put("displaySubtitle", fields.displaySubtitle)
                .put("descriptionTitle", fields.descriptionTitle)
                .put("descriptionSubtitle", fields.descriptionSubtitle)
                .put("rawDuration", fields.durationMs).put("durationUnit", unit.name)
                .put("normalized", JSONObject().put("track", normalized.track).put("artist", normalized.artist)
                    .put("album", normalized.album).put("durationMs", normalized.durationMs)))
    }.getOrElse { JSONObject().put("session", id).put("readError", it.javaClass.simpleName) }

    private fun playbackSnapshot(playback: PlaybackState?): JSONObject {
        val now = SystemClock.elapsedRealtime()
        val update = playback?.lastPositionUpdateTime ?: 0L
        return JSONObject()
            .put("state", playback?.state ?: JSONObject.NULL)
            .put("positionMs", playback?.position ?: JSONObject.NULL)
            .put("speed", playback?.playbackSpeed?.takeIf { it.isFinite() } ?: JSONObject.NULL)
            .put("updateElapsedRealtimeMs", update)
            .put("updateAgeMs", if (update in 1..now) now - update else JSONObject.NULL)
            .put("actions", playback?.actions ?: 0L)
            .put("errorMessage", playback?.errorMessage?.toString()?.take(160) ?: JSONObject.NULL)
    }

    private fun sessionId(controller: MediaController): String =
        sessionIds.getOrPut(controller.sessionToken) { "session_" + ++nextId }

    private fun audioSnapshot(): JSONObject = runCatching {
        JSONObject().put("isMusicActive", audio.isMusicActive).put("scope", "global_unattributed")
    }.getOrElse { JSONObject().put("isMusicActive", JSONObject.NULL).put("readError", it.javaClass.simpleName) }

    private fun packageSummary(packageName: String): JSONObject = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        JSONObject().put("packageName", packageName).put("installed", true)
            .put("versionName", info.versionName)
            .put("versionCode", if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
    }.getOrElse { JSONObject().put("packageName", packageName).put("installed", false) }

    private fun record(kind: String, data: JSONObject) {
        if (!closed) trace.record(SystemClock.elapsedRealtime(), kind, data)
    }

    private fun recordError(operation: String, error: Throwable) = record(
        "observation_error", JSONObject().put("operation", operation).put("error", error.javaClass.simpleName)
    )

    companion object {
        const val TARGET_PACKAGE = "com.netease.cloudmusic.iot"
        const val BASELINE_WINDOW_MS = 2_000L
        val MARKERS = setOf("play_pressed", "pause_pressed", "next_pressed", "sound_heard", "no_sound", "target_opened")
        fun hasAccess(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
