package com.ninepointnine.desktoplyrics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.resume

/** Captures the public media contracts that feed the lyrics resolver. */
internal class MediaContractDiagnosticCollector(
    private val context: Context,
) {
    private data class PublicBrowserDescriptor(
        val componentName: ComponentName,
        val packageName: String,
        val serviceName: String,
        val exported: Boolean,
        val permission: String?,
        val label: String,
        val versionName: String,
        val versionCode: Long,
    )

    private val sessionManager by lazy {
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }
    private val listenerComponent by lazy {
        ComponentName(context, MediaListenerService::class.java)
    }

    suspend fun collectOnce(): JSONObject {
        val result = JSONObject()
            .put("observedAtEpochMs", System.currentTimeMillis())
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put(
                "notificationListenerAccessGranted",
                NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
            )
        val controllers = runCatching {
            sessionManager.getActiveSessions(listenerComponent).orEmpty()
        }.getOrElse { error ->
            result.put("activeSessionError", errorSummary(error))
            emptyList()
        }
        val sessions = JSONArray()
        controllers.forEachIndexed { index, controller ->
            sessions.put(frameworkController(index, controller))
        }
        val candidates = controllers.mapIndexed { index, controller ->
            val playback = controller.playbackState
            val playbackInfo = runCatching { controller.playbackInfo }.getOrNull()
            MediaSessionCandidate(
                index = index,
                packageName = controller.packageName,
                playbackState = playback?.state,
                audioUsage = playbackInfo?.audioAttributes?.usage,
                audioContentType = playbackInfo?.audioAttributes?.contentType,
                playbackActions = playback?.actions ?: 0L,
                hasTitle = normalizedMetadata(controller).hasTrack,
            )
        }
        val selectedIndex = MediaSessionSelectionPolicy.select(
            candidates = candidates,
            currentIndex = null,
            ownPackageName = context.packageName,
        )
        val activePackages = controllers.map(MediaController::getPackageName).toSet()
        val publicBrowserDescriptors = publicBrowserDescriptors()
        result
            .put("notificationListenerComponent", listenerComponent.flattenToShortString())
            .put("activeSessionCount", controllers.size)
            .put("activeSessions", sessions)
            .put("activeSessionPackages", JSONArray(activePackages.toList()))
            .put("selection", JSONObject()
                .put("selectedIndex", selectedIndex ?: JSONObject.NULL)
                .put("selectedPackage", selectedIndex?.let { controllers[it].packageName } ?: JSONObject.NULL)
                .put("candidateMatrix", candidates.map(::candidateSummary).let(::JSONArray)))
            .put("bluetoothRoute", bluetoothRoute())
            .put("bluetoothBrowserServices", browserServices())
            .put("publicMediaBrowserServices", publicBrowserServices(publicBrowserDescriptors))
            .put("browserProbes", probeBrowsers(publicBrowserDescriptors, activePackages))
        return result
    }

    private fun frameworkController(index: Int, controller: MediaController): JSONObject {
        val metadata = controller.metadata
        val playback = controller.playbackState
        val playbackInfo = runCatching { controller.playbackInfo }.getOrNull()
        val rawDuration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        return JSONObject()
            .put("index", index)
            .put("packageName", controller.packageName)
            .put("sessionKey", sessionKey(controller.packageName, controller.sessionToken))
            .put("sessionTokenPresent", true)
            .put("playback", playbackSummary(
                playback?.state,
                playback?.position,
                playback?.playbackSpeed,
                playback?.lastPositionUpdateTime,
                playback?.actions,
            ))
            .put("audio", audioSummary(playbackInfo?.audioAttributes))
            .put("metadata", frameworkMetadata(metadata, controller.packageName, playback?.position ?: -1L))
            .put("durationInterpretation", durationInterpretation(
                rawDuration,
                playback?.position ?: -1L,
                controller.packageName,
            ))
    }

    private fun frameworkMetadata(
        metadata: MediaMetadata?,
        packageName: String,
        positionMs: Long,
    ): JSONObject {
        if (metadata == null) return JSONObject().put("present", false)
        val description = runCatching { metadata.description }.getOrNull()
        val raw = MediaSessionMetadataFields(
            descriptionTitle = description?.title?.toString().orEmpty(),
            descriptionSubtitle = description?.subtitle?.toString().orEmpty(),
            descriptionDescription = description?.description?.toString().orEmpty(),
            displayTitle = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty(),
            displaySubtitle = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty(),
            displayDescription = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION).orEmpty(),
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            albumArtist = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
            author = metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR).orEmpty(),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
            durationUnit = durationUnitFor(packageName),
            reportedPositionMs = positionMs,
        )
        val normalized = MediaSessionMetadataPolicy.normalize(raw)
        return JSONObject()
            .put("present", true)
            .put("title", safeText(raw.title))
            .put("artist", safeText(raw.artist))
            .put("album", safeText(raw.album))
            .put("displayTitle", safeText(raw.displayTitle))
            .put("displaySubtitle", safeText(raw.displaySubtitle))
            .put("description", descriptionSummary(description))
            .put("rawDuration", raw.durationMs)
            .put("metadataKeys", JSONArray(METADATA_KEYS.filter(metadata::containsKey)))
            .put("normalized", JSONObject()
                .put("track", safeText(normalized.track))
                .put("artist", safeText(normalized.artist))
                .put("album", safeText(normalized.album))
                .put("durationMs", normalized.durationMs)
                .put("hasTrack", normalized.hasTrack))
    }

    private fun normalizedMetadata(controller: MediaController): MediaRecordingMetadata {
        val metadata = controller.metadata
        val description = runCatching { metadata?.description }.getOrNull()
        return MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                descriptionTitle = description?.title?.toString().orEmpty(),
                descriptionSubtitle = description?.subtitle?.toString().orEmpty(),
                descriptionDescription = description?.description?.toString().orEmpty(),
                displayTitle = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty(),
                displaySubtitle = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty(),
                displayDescription = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION).orEmpty(),
                title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                albumArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
                author = metadata?.getString(MediaMetadata.METADATA_KEY_AUTHOR).orEmpty(),
                album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
                durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                durationUnit = durationUnitFor(controller.packageName),
                reportedPositionMs = controller.playbackState?.position ?: -1L,
            ),
        )
    }

    private fun browserServices(): JSONArray = JSONArray().also { array ->
        runCatching {
            BluetoothMediaBrowserServiceResolver.discover(context.packageManager)
                .forEach { descriptor ->
                    array.put(JSONObject()
                        .put("component", descriptor.componentName.flattenToShortString())
                        .put("profile", descriptor.profile.name)
                        .put("durationUnitUsedByApp", descriptor.profile.durationUnit.name))
                }
        }.onFailure { array.put(JSONObject().put("error", errorSummary(it))) }
    }

    private fun bluetoothRoute(): JSONObject = runCatching {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val outputs = JSONArray()
        var bluetooth = false
        audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).forEach { device ->
            val isBluetooth = BluetoothMediaBrowserBridgePolicy.isBluetoothOutputType(
                device.type,
                Build.VERSION.SDK_INT,
            )
            bluetooth = bluetooth || isBluetooth
            outputs.put(JSONObject()
                .put("type", device.type)
                .put("typeName", deviceTypeName(device.type))
                .put("bluetooth", isBluetooth)
                .put("id", device.id)
                .put("productNamePresent", !device.productName.isNullOrBlank()))
        }
        JSONObject().put("present", bluetooth).put("outputs", outputs)
    }.getOrElse { JSONObject().put("error", errorSummary(it)) }

    private fun publicBrowserDescriptors(): List<PublicBrowserDescriptor> = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.queryIntentServices(
            Intent(MediaBrowser.SERVICE_INTERFACE),
            PackageManager.GET_META_DATA,
        ).mapNotNull { resolvePublicBrowserDescriptor(it.serviceInfo) }
    }.getOrDefault(emptyList())
        .distinctBy { it.componentName }

    private fun resolvePublicBrowserDescriptor(serviceInfo: ServiceInfo?): PublicBrowserDescriptor? {
        if (serviceInfo == null) return null
        val serviceName = serviceInfo.name?.trim().orEmpty()
        val packageName = serviceInfo.packageName?.trim().orEmpty()
        if (serviceName.isEmpty() || packageName.isEmpty()) return null
        val packageInfo = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }.getOrNull()
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode ?: 0L
        } else {
            packageInfo?.versionCode?.toLong() ?: 0L
        }
        return PublicBrowserDescriptor(
            componentName = ComponentName(packageName, serviceName),
            packageName = packageName,
            serviceName = serviceName,
            exported = serviceInfo.exported,
            permission = serviceInfo.permission,
            label = serviceInfo.applicationInfo?.loadLabel(context.packageManager)?.toString().orEmpty(),
            versionName = packageInfo?.versionName.orEmpty(),
            versionCode = versionCode,
        )
    }

    private fun publicBrowserServices(descriptors: List<PublicBrowserDescriptor>): JSONArray =
        JSONArray().also { services ->
            descriptors.forEach { descriptor ->
                services.put(publicBrowserSummary(descriptor))
            }
        }

    private fun publicBrowserSummary(descriptor: PublicBrowserDescriptor): JSONObject =
        JSONObject()
            .put("component", descriptor.componentName.flattenToShortString())
            .put("packageName", descriptor.packageName)
            .put("serviceName", descriptor.serviceName)
            .put("exported", descriptor.exported)
            .put("permission", descriptor.permission ?: JSONObject.NULL)
            .put("label", safeText(descriptor.label))
            .put("versionName", descriptor.versionName)
            .put("versionCode", descriptor.versionCode)
            .put("probeEligible", descriptor.exported)

    private suspend fun probeBrowsers(
        descriptors: List<PublicBrowserDescriptor>,
        activePackages: Set<String>,
    ): JSONArray {
        val ordered = descriptors
            .filter(PublicBrowserDescriptor::exported)
            .sortedWith(
                compareByDescending<PublicBrowserDescriptor> { it.packageName in activePackages }
                    .thenBy { it.componentName.flattenToShortString() },
            )
            .take(MAX_PUBLIC_BROWSER_SERVICES)
        return JSONArray().also { probes ->
            ordered.forEach { descriptor ->
                probes.put(probeFrameworkBrowser(descriptor))
                probes.put(probeCompatBrowser(descriptor))
            }
        }
    }

    private suspend fun probeFrameworkBrowser(
        descriptor: PublicBrowserDescriptor,
    ): JSONObject = withTimeoutOrNull(PUBLIC_BROWSER_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            var browser: MediaBrowser? = null
            fun finish(value: JSONObject) {
                runCatching { browser?.disconnect() }
                if (continuation.isActive) continuation.resume(value)
            }
            val callback = object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    val value = runCatching {
                        val current = browser ?: error("browser_null")
                        val controller = MediaController(context, current.sessionToken)
                        browserResult("framework", descriptor, "connected")
                            .put("root", current.root)
                            .put("session", frameworkController(0, controller))
                    }.getOrElse { error ->
                        browserResult("framework", descriptor, "connected_but_controller_failed", error)
                    }
                    finish(value)
                }

                override fun onConnectionSuspended() =
                    finish(browserResult("framework", descriptor, "suspended"))

                override fun onConnectionFailed() =
                    finish(browserResult("framework", descriptor, "failed"))
            }
            browser = MediaBrowser(context, descriptor.componentName, callback, Bundle())
            continuation.invokeOnCancellation { runCatching { browser?.disconnect() } }
            runCatching { browser?.connect() }
                .onFailure { error -> finish(browserResult("framework", descriptor, "connect_exception", error)) }
        }
    } ?: browserResult("framework", descriptor, "timeout")

    private suspend fun probeCompatBrowser(
        descriptor: PublicBrowserDescriptor,
    ): JSONObject = withTimeoutOrNull(PUBLIC_BROWSER_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            var browser: MediaBrowserCompat? = null
            fun finish(value: JSONObject) {
                runCatching { browser?.disconnect() }
                if (continuation.isActive) continuation.resume(value)
            }
            val callback = object : MediaBrowserCompat.ConnectionCallback() {
                override fun onConnected() {
                    val value = runCatching {
                        val current = browser ?: error("browser_null")
                        val controller = MediaControllerCompat(context, current.sessionToken)
                        compatControllerSummary(descriptor, controller)
                    }.getOrElse { error ->
                        browserResult("androidx", descriptor, "connected_but_controller_failed", error)
                    }
                    finish(value)
                }

                override fun onConnectionSuspended() =
                    finish(browserResult("androidx", descriptor, "suspended"))

                override fun onConnectionFailed() =
                    finish(browserResult("androidx", descriptor, "failed"))
            }
            browser = MediaBrowserCompat(context, descriptor.componentName, callback, Bundle())
            continuation.invokeOnCancellation { runCatching { browser?.disconnect() } }
            runCatching { browser?.connect() }
                .onFailure { error -> finish(browserResult("androidx", descriptor, "connect_exception", error)) }
        }
    } ?: browserResult("androidx", descriptor, "timeout")

    private fun compatControllerSummary(
        descriptor: PublicBrowserDescriptor,
        controller: MediaControllerCompat,
    ): JSONObject {
        val metadata = controller.metadata
        val playback = controller.playbackState
        val rawDuration = metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0L
        val description = metadata?.description
        return JSONObject()
            .put("client", "androidx")
            .put("component", descriptor.componentName.flattenToShortString())
            .put("state", "connected")
            .put("packageName", controller.packageName)
            .put("sessionKey", sessionKey(controller.packageName, controller.sessionToken))
            .put("playback", playbackSummary(
                playback?.state,
                playback?.position,
                playback?.playbackSpeed,
                playback?.lastPositionUpdateTime,
                playback?.actions ?: 0L,
            ))
            .put("metadata", JSONObject()
                .put("present", metadata != null)
                .put("title", safeText(metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE).orEmpty()))
                .put("artist", safeText(metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST).orEmpty()))
                .put("album", safeText(metadata?.getString(MediaMetadataCompat.METADATA_KEY_ALBUM).orEmpty()))
                .put("description", descriptionSummary(description))
                .put("rawDuration", rawDuration))
            .put("durationInterpretation", durationInterpretation(
                rawDuration,
                playback?.position ?: -1L,
                controller.packageName,
            ))
    }

    private fun browserResult(
        client: String,
        descriptor: PublicBrowserDescriptor,
        state: String,
        error: Throwable? = null,
    ): JSONObject = JSONObject()
        .put("client", client)
        .put("component", descriptor.componentName.flattenToShortString())
        .put("packageName", descriptor.packageName)
        .put("serviceName", descriptor.serviceName)
        .put("state", state)
        .apply { error?.let { put("error", errorSummary(it)) } }

    private fun candidateSummary(candidate: MediaSessionCandidate): JSONObject = JSONObject()
        .put("index", candidate.index)
        .put("packageName", candidate.packageName)
        .put("state", candidate.playbackState ?: JSONObject.NULL)
        .put("stateName", playbackStateName(candidate.playbackState))
        .put("audioUsage", candidate.audioUsage ?: JSONObject.NULL)
        .put("audioContentType", candidate.audioContentType ?: JSONObject.NULL)
        .put("actions", candidate.playbackActions)
        .put("hasTitle", candidate.hasTitle)
        .put("isPlaying", candidate.isPlaying)
        .put("isPaused", candidate.isPaused)

    private fun sessionKey(packageName: String, token: Any): String =
        sha256("$packageName|$token")

    private fun playbackSummary(
        state: Int?,
        position: Long?,
        speed: Float?,
        updateTime: Long?,
        actions: Long?,
    ): JSONObject = JSONObject()
        .put("state", state ?: JSONObject.NULL)
        .put("stateName", playbackStateName(state))
        .put("positionMs", position ?: JSONObject.NULL)
        .put("speed", speed ?: JSONObject.NULL)
        .put("lastPositionUpdateTime", updateTime ?: JSONObject.NULL)
        .put("actions", actions ?: 0L)

    private fun audioSummary(attributes: AudioAttributes?): JSONObject = JSONObject()
        .put("present", attributes != null)
        .put("usage", attributes?.usage ?: JSONObject.NULL)
        .put("contentType", attributes?.contentType ?: JSONObject.NULL)
        .put("flags", attributes?.flags ?: JSONObject.NULL)

    private fun descriptionSummary(value: android.media.MediaDescription?): JSONObject =
        JSONObject()
            .put("titlePresent", !value?.title.isNullOrBlank())
            .put("subtitlePresent", !value?.subtitle.isNullOrBlank())
            .put("descriptionPresent", !value?.description.isNullOrBlank())
            .put("titleLength", value?.title?.length ?: 0)
            .put("subtitleLength", value?.subtitle?.length ?: 0)
            .put("descriptionLength", value?.description?.length ?: 0)
            .put("descriptionSha256", sha256(value?.description?.toString().orEmpty()))

    private fun descriptionSummary(value: android.support.v4.media.MediaDescriptionCompat?): JSONObject =
        JSONObject()
            .put("titlePresent", !value?.title.isNullOrBlank())
            .put("subtitlePresent", !value?.subtitle.isNullOrBlank())
            .put("descriptionPresent", !value?.description.isNullOrBlank())
            .put("titleLength", value?.title?.length ?: 0)
            .put("subtitleLength", value?.subtitle?.length ?: 0)
            .put("descriptionLength", value?.description?.length ?: 0)
            .put("descriptionSha256", sha256(value?.description?.toString().orEmpty()))

    private fun durationInterpretation(raw: Long, positionMs: Long, packageName: String): JSONObject {
        val secondsMs = if (raw in 1L..86_400L) raw * 1_000L else 0L
        return JSONObject()
            .put("raw", raw)
            .put("asMilliseconds", raw.coerceIn(0L, 86_400_000L))
            .put("asSecondsToMilliseconds", secondsMs)
            .put("belowOneSecondThreshold", raw in 1L..999L)
            .put("appProfile", durationUnitFor(packageName).name.lowercase(Locale.ROOT))
            .put("positionGreaterThanRaw", positionMs > raw && positionMs >= 0L)
            .put("lyricsMinimumQueryDurationMs", 1_000L)
    }

    private fun durationUnitFor(packageName: String): MediaSessionDurationUnit =
        if (packageName != BluetoothMediaBrowserServiceResolver.BLUETOOTH_PACKAGE) {
            MediaSessionDurationUnit.MILLISECONDS
        } else if (Build.VERSION.SDK_INT >= 29) {
            BluetoothMediaBrowserProfile.ANDROID_10_PLUS.durationUnit
        } else {
            BluetoothMediaBrowserProfile.ANDROID_9_A2DP.durationUnit
        }

    private fun playbackStateName(value: Int?): String = when (value) {
        PlaybackStateCompat.STATE_NONE -> "none"
        PlaybackStateCompat.STATE_STOPPED -> "stopped"
        PlaybackStateCompat.STATE_PAUSED -> "paused"
        PlaybackStateCompat.STATE_PLAYING -> "playing"
        PlaybackStateCompat.STATE_FAST_FORWARDING -> "fast_forwarding"
        PlaybackStateCompat.STATE_REWINDING -> "rewinding"
        PlaybackStateCompat.STATE_BUFFERING -> "buffering"
        PlaybackStateCompat.STATE_ERROR -> "error"
        PlaybackStateCompat.STATE_CONNECTING -> "connecting"
        PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS -> "skipping_previous"
        PlaybackStateCompat.STATE_SKIPPING_TO_NEXT -> "skipping_next"
        PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM -> "skipping_queue"
        null -> "missing"
        else -> "unknown_$value"
    }

    @Suppress("DEPRECATION", "InlinedApi")
    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth_a2dp"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "builtin_speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "builtin_earpiece"
        else -> "type_$type"
    }

    private fun safeText(value: String): String = value.trim().take(256)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }

    private fun errorSummary(error: Throwable): String =
        "${error.javaClass.name}:${error.message.orEmpty()}".take(400)

    private companion object {
        const val PUBLIC_BROWSER_TIMEOUT_MS = 1_500L
        const val MAX_PUBLIC_BROWSER_SERVICES = 8
        val METADATA_KEYS = listOf(
            MediaMetadata.METADATA_KEY_TITLE,
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_AUTHOR,
            MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
            MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION,
            MediaMetadata.METADATA_KEY_DURATION,
        )
    }
}
