package com.ninepointnine.desktoplyrics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
    private val trace = MediaDiagnosticTrace(startedAtMs, maxEvents = 512, maxBytes = 384 * 1024)
    private var active = emptyList<MediaController>()
    private val observed = linkedMapOf<MediaSession.Token, Observed>()
    private val sessionIds = linkedMapOf<MediaSession.Token, String>()
    private var nextId = 0
    private var closed = false
    private var probing = false
    private var targetSeen = false
    private val targetStates = linkedSetOf<Int>()
    private val targetPackages = linkedSetOf(TARGET_PACKAGE, "com.netease.cloudmusic")
    private val snapshotSignatures = mutableMapOf<String, String>()
    private val packageSummaries = linkedMapOf<String, JSONObject>()
    private val browserServices = linkedMapOf<String, JSONObject>()
    private var initialApplications = JSONObject()
    private var lastAudio = ""
    private var sampleCount = 0
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
        },
        connectionLimit = BROWSER_LIMIT
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
    private val sample = object : Runnable {
        override fun run() {
            if (closed) return
            runCatching {
                val latest = manager.getActiveSessions(component).orEmpty()
                if (latest.map { it.sessionToken }.toSet() != active.map { it.sessionToken }.toSet()) {
                    active = latest
                    synchronizeControllers("active_sessions_sampled")
                    if (probing) refreshBrowsers()
                }
                observed.values.toList().forEach { capture("sampled_state_change", it, onlyChanges = true) }
                val currentAudio = audioSnapshot()
                if (currentAudio.toString() != lastAudio) record("audio_sample_change", currentAudio)
                lastAudio = currentAudio.toString()
                sampleCount++
                if (sampleCount % 5 == 0) record("sample_checkpoint", JSONObject()
                    .put("sampleCount", sampleCount).put("observedSessions", observed.size)
                    .put("audio", currentAudio))
            }.onFailure { recordError("periodic_snapshot", it) }
            if (!closed) handler.postDelayed(this, SAMPLE_INTERVAL_MS)
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
        lastAudio = baseline.getJSONObject("audio").toString()
        initialApplications = applicationInventory()
        manager.addOnActiveSessionsChangedListener(activeListener, component, handler)
        active = manager.getActiveSessions(component).orEmpty()
        synchronizeControllers("active_sessions_initial")
        runCatching { audio.registerAudioPlaybackCallback(audioListener, handler) }
            .onFailure { recordError("audio_callback_registration", it) }
        handler.postDelayed(beginProbes, BASELINE_WINDOW_MS)
        handler.postDelayed(sample, SAMPLE_INTERVAL_MS)
    }

    fun finish(reason: String): JSONObject {
        val finalSessions = observed.values.map { sessionSnapshot(it.controller, it.id) }
        val finalAudio = audioSnapshot()
        packageSummaries.clear()
        val finalApplications = applicationInventory()
        closed = true
        handler.removeCallbacks(beginProbes)
        handler.removeCallbacks(sample)
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
            .put("schemaVersion", 3)
            .put("kind", "netease_media_events")
            .put("startedAtEpochMs", startedAtEpochMs)
            .put("startedAtElapsedRealtimeMs", startedAtMs)
            .put("finishedAtElapsedRealtimeMs", finishedAtMs)
            .put("durationMs", finishedAtMs - startedAtMs)
            .put("endReason", reason)
            .put("captureCompleted", reason == "deadline")
            .put("app", JSONObject().put("packageName", context.packageName)
                .put("versionName", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE))
            .put("device", JSONObject().put("model", Build.MODEL).put("sdkInt", Build.VERSION.SDK_INT)
                .put("manufacturer", Build.MANUFACTURER).put("brand", Build.BRAND)
                .put("hardware", Build.HARDWARE).put("androidRelease", Build.VERSION.RELEASE))
            .put("target", packageSummary(TARGET_PACKAGE))
            .put("applications", JSONObject().put("initial", initialApplications).put("final", finalApplications))
            .put("browserServices", JSONArray(browserServices.values.toList()))
            .put("capturePolicy", JSONObject().put("mode", "automatic")
                .put("sampleIntervalMs", SAMPLE_INTERVAL_MS).put("sampleCount", sampleCount)
                .put("browserConnectionLimit", BROWSER_LIMIT)
                .put("playbackEvidence", "public_callbacks_and_sampled_changes")
                .put("crossAppTouchEventsAvailable", false).put("audioAttributedToPackage", false)
                .put("privatePlayerLogsAvailable", false))
            .put("notificationListenerAccessGranted", hasAccess(context))
            .put("baseline", baseline)
            .put("trace", trace.snapshot())
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
        descriptors.forEach { descriptor ->
            if (descriptor.sourceKey !in browserServices) {
                val value = JSONObject().put("component", descriptor.sourceKey)
                    .put("application", packageSummary(descriptor.packageName))
                    .put("durationUnit", descriptor.durationUnit.name)
                runCatching {
                    @Suppress("DEPRECATION")
                    val info = context.packageManager.getServiceInfo(descriptor.componentName, 0)
                    value.put("exported", info.exported).put("enabled", info.enabled)
                        .put("permission", info.permission ?: JSONObject.NULL)
                }.onFailure { value.put("queryError", it.javaClass.simpleName) }
                browserServices[descriptor.sourceKey] = value
            }
        }
        val eligible = active.mapTo(targetPackages.toMutableSet()) { it.packageName }
        val selected = PublicMediaBrowserServiceResolver.select(descriptors, limit = BROWSER_LIMIT, eligiblePackages = eligible)
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
            snapshotSignatures.remove(entry.id)
            runCatching { entry.controller.unregisterCallback(entry.callback) }
        }
        controllers.forEach { controller ->
            if (controller.sessionToken !in observed) {
                val id = sessionId(controller)
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        if (controller.packageName in targetPackages) state?.state?.let(targetStates::add)
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
                            snapshotSignatures[entry.id] = DiagnosticReportPolicy.fingerprint(snapshot)
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

    private fun capture(kind: String, entry: Observed, onlyChanges: Boolean = false) {
        val snapshot = sessionSnapshot(entry.controller, entry.id)
        val signature = DiagnosticReportPolicy.fingerprint(snapshot)
        if (!onlyChanges || snapshotSignatures[entry.id] != signature) record(kind, snapshot)
        snapshotSignatures[entry.id] = signature
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
        if (controller.packageName in targetPackages) {
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
                .put("playbackType", audioInfo?.playbackType ?: JSONObject.NULL)
                .put("volumeControl", audioInfo?.volumeControl ?: JSONObject.NULL)
                .put("currentVolume", audioInfo?.currentVolume ?: JSONObject.NULL)
                .put("maxVolume", audioInfo?.maxVolume ?: JSONObject.NULL))
            .put("playback", playbackSnapshot(playback))
            .put("metadata", JSONObject()
                .put("present", metadata != null).put("mediaId", fields.mediaId)
                .put("title", fields.title).put("artist", fields.artist).put("album", fields.album)
                .put("displayTitle", fields.displayTitle).put("displaySubtitle", fields.displaySubtitle)
                .put("displayDescription", fields.displayDescription)
                .put("albumArtist", fields.albumArtist).put("author", fields.author)
                .put("descriptionTitle", fields.descriptionTitle)
                .put("descriptionSubtitle", fields.descriptionSubtitle)
                .put("metadataKeys", JSONArray(metadata?.keySet()?.sorted()?.take(64).orEmpty()))
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
            .put("extrasKeys", JSONArray(playback?.extras?.keySet()?.sorted()?.take(64).orEmpty()))
            .put("errorMessage", playback?.errorMessage?.toString()?.take(160) ?: JSONObject.NULL)
    }

    private fun sessionId(controller: MediaController): String =
        sessionIds.getOrPut(controller.sessionToken) { "session_" + ++nextId }

    private fun audioSnapshot(): JSONObject {
        val result = JSONObject().put("scope", "global_unattributed")
        val errors = JSONArray()
        fun field(name: String, read: () -> Any) {
            runCatching { result.put(name, read()) }.onFailure {
                result.put(name, JSONObject.NULL)
                errors.put(JSONObject().put("field", name).put("error", it.javaClass.simpleName))
            }
        }
        field("isMusicActive") { audio.isMusicActive }
        field("mode") { audio.mode }
        field("musicVolume") { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }
        field("musicVolumeMax") { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
        field("musicMuted") { audio.isStreamMute(AudioManager.STREAM_MUSIC) }
        field("outputs") {
            JSONArray(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).take(32).map {
                JSONObject().put("id", it.id).put("type", it.type)
            })
        }
        field("playbackConfigurations") {
            JSONArray(audio.activePlaybackConfigurations.take(32).map {
                JSONObject().put("usage", it.audioAttributes.usage)
                    .put("contentType", it.audioAttributes.contentType).put("flags", it.audioAttributes.flags)
            })
        }
        return result.put("readErrors", errors)
    }

    private fun packageSummary(packageName: String): JSONObject = packageSummaries.getOrPut(packageName) {
        runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            JSONObject().put("packageName", packageName).put("installed", true).put("queryStatus", "found")
                .put("label", info.applicationInfo?.loadLabel(context.packageManager)?.toString()?.take(96))
                .put("enabled", info.applicationInfo?.enabled).put("versionName", info.versionName)
                .put("versionCode", if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
        }.getOrElse { error -> DiagnosticReportPolicy.packageFailure(
            packageName, error.javaClass.simpleName, error is PackageManager.NameNotFoundException) }
    }

    private fun applicationInventory(): JSONObject {
        val result = JSONObject().put("scope", "visible_launchers_and_observed_media_current_user")
        val launchers = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .mapNotNull { it.activityInfo }.distinctBy { ComponentName(it.packageName, it.name) }
                .sortedWith(compareBy({ it.packageName }, { it.name }))
        }.getOrElse { result.put("launcherQueryError", it.javaClass.simpleName); emptyList() }
        result.put("launcherCount", launchers.size).put("omittedLaunchers", (launchers.size - LAUNCHER_LIMIT).coerceAtLeast(0))
        result.put("launchers", JSONArray(launchers.take(LAUNCHER_LIMIT).map { activity ->
            val application = packageSummary(activity.packageName)
            if (activity.packageName.startsWith("com.netease.cloudmusic") ||
                application.optString("label").contains("网易云")) targetPackages += activity.packageName
            JSONObject().put("component", ComponentName(activity.packageName, activity.name).flattenToShortString())
                .put("application", application)
        }))
        val packages = (targetPackages + observed.values.map { it.controller.packageName } +
            registry.currentSessions.map { it.descriptor.packageName }).sorted()
        return result.put("mediaApplications", JSONArray(packages.map(::packageSummary)))
            .put("neteaseCandidatePackages", JSONArray(targetPackages.toList()))
    }

    private fun record(kind: String, data: JSONObject) {
        if (!closed) trace.record(SystemClock.elapsedRealtime(), kind, data)
    }

    private fun recordError(operation: String, error: Throwable) = record(
        "observation_error", JSONObject().put("operation", operation).put("error", error.javaClass.simpleName)
    )

    companion object {
        const val TARGET_PACKAGE = "com.netease.cloudmusic.iot"
        const val BASELINE_WINDOW_MS = 2_000L
        const val SAMPLE_INTERVAL_MS = 2_000L
        private const val BROWSER_LIMIT = 16
        private const val LAUNCHER_LIMIT = 128
        fun hasAccess(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}

internal object DiagnosticReportPolicy {
    fun fingerprint(snapshot: JSONObject): String = JSONObject(snapshot.toString()).apply {
        remove("observedAtElapsedRealtimeMs")
        optJSONObject("playback")?.remove("updateAgeMs")
    }.toString()

    fun packageFailure(packageName: String, error: String, notFound: Boolean): JSONObject = JSONObject()
        .put("packageName", packageName).put("installed", JSONObject.NULL)
        .put("queryStatus", if (notFound) "not_found_or_not_visible" else "query_failed")
        .put("error", error)
}
