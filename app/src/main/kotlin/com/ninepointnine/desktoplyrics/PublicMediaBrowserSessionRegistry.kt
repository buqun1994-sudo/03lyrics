package com.ninepointnine.desktoplyrics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper

internal data class PublicMediaBrowserServiceDescriptor(
    val packageName: String,
    val serviceName: String,
    val durationUnit: MediaSessionDurationUnit = MediaSessionDurationUnit.MILLISECONDS
) {
    val sourceKey: String
        get() {
            val shortServiceName = if (serviceName.startsWith("$packageName.")) {
                ".${serviceName.removePrefix("$packageName.")}"
            } else {
                serviceName
            }
            return "$packageName/$shortServiceName"
        }

    val componentName: ComponentName
        get() = ComponentName(packageName, serviceName)
}

internal data class PublicMediaBrowserSession(
    val controller: MediaController,
    val descriptor: PublicMediaBrowserServiceDescriptor
)

internal enum class PublicMediaBrowserConnectionState {
    CONNECTING,
    CONNECTED,
    RETRY_WAIT,
    SUSPENDED,
    UNAVAILABLE
}

/** Discovers exported standard MediaBrowser services without player allowlists. */
internal object PublicMediaBrowserServiceResolver {
    const val ACTION = "android.media.browse.MediaBrowserService"
    const val BLUETOOTH_PACKAGE = "com.android.bluetooth"
    const val MAX_SERVICES = 8

    fun discover(
        packageManager: PackageManager,
        excludedPackages: Set<String>
    ): List<PublicMediaBrowserServiceDescriptor> {
        val services = runCatching {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(Intent(ACTION), 0)
        }.getOrElse { return emptyList() }
        return select(
            services.mapNotNull { resolve(it.serviceInfo, excludedPackages) }
        )
    }

    fun resolve(
        serviceInfo: ServiceInfo?,
        excludedPackages: Set<String> = emptySet()
    ): PublicMediaBrowserServiceDescriptor? {
        if (serviceInfo == null || !serviceInfo.exported) return null
        val packageName = serviceInfo.packageName?.trim().orEmpty()
        val serviceName = serviceInfo.name?.trim().orEmpty()
        if (packageName.isEmpty() || serviceName.isEmpty()) return null
        if (packageName in excludedPackages) return null
        return PublicMediaBrowserServiceDescriptor(
            packageName = packageName,
            serviceName = serviceName,
            durationUnit = durationUnitFor(packageName, serviceName)
        )
    }

    fun select(
        descriptors: List<PublicMediaBrowserServiceDescriptor>,
        limit: Int = MAX_SERVICES
    ): List<PublicMediaBrowserServiceDescriptor> = descriptors
        .distinctBy(PublicMediaBrowserServiceDescriptor::sourceKey)
        .take(limit.coerceAtLeast(0))

    fun durationUnitFor(
        packageName: String,
        serviceName: String,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): MediaSessionDurationUnit = when {
        packageName != BLUETOOTH_PACKAGE -> MediaSessionDurationUnit.MILLISECONDS
        serviceName.substringAfterLast('.').equals(
            "A2dpMediaBrowserService",
            ignoreCase = true
        ) -> MediaSessionDurationUnit.MILLISECONDS
        sdkInt >= 29 && serviceName.substringAfterLast('.').equals(
            "BluetoothMediaBrowserService",
            ignoreCase = true
        ) -> MediaSessionDurationUnit.SECONDS
        else -> MediaSessionDurationUnit.UNKNOWN
    }
}

internal object PublicMediaBrowserRegistryPolicy {
    const val CONNECT_TIMEOUT_MS = 3_000L
    const val RETRY_DELAY_MS = 1_000L
    const val REPROBE_DELAY_MS = 30_000L
    const val COLD_DISCOVERY_WINDOW_MS = 3_500L
    const val MAX_RETRIES = 1

    fun shouldRetry(retryCount: Int): Boolean = retryCount < MAX_RETRIES

    fun shouldInclude(
        descriptor: PublicMediaBrowserServiceDescriptor,
        eligiblePackages: Set<String>,
        preferredSourceId: String?,
        bluetoothRoutePresent: Boolean,
        discoverAllSources: Boolean
    ): Boolean = when {
        descriptor.packageName == PublicMediaBrowserServiceResolver.BLUETOOTH_PACKAGE ->
            bluetoothRoutePresent
        discoverAllSources -> true
        else -> descriptor.packageName in eligiblePackages ||
            descriptor.sourceKey == preferredSourceId
    }

    @Suppress("DEPRECATION", "InlinedApi")
    fun isBluetoothOutputType(type: Int, sdkInt: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> true
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> sdkInt >= 31
        else -> false
    }
}

internal interface PublicMediaBrowserClient {
    interface Callback {
        fun onConnected(controller: MediaController)
        fun onConnectionSuspended()
        fun onConnectionFailed()
    }

    fun connect()
    fun disconnect()
}

internal fun interface PublicMediaBrowserClientFactory {
    fun create(
        context: Context,
        descriptor: PublicMediaBrowserServiceDescriptor,
        callback: PublicMediaBrowserClient.Callback
    ): PublicMediaBrowserClient
}

internal interface PublicMediaBrowserScheduler {
    fun postDelayed(runnable: Runnable, delayMillis: Long)
    fun removeCallbacks(runnable: Runnable)
}

private class HandlerPublicMediaBrowserScheduler(
    private val handler: Handler
) : PublicMediaBrowserScheduler {
    override fun postDelayed(runnable: Runnable, delayMillis: Long) {
        handler.postDelayed(runnable, delayMillis)
    }

    override fun removeCallbacks(runnable: Runnable) {
        handler.removeCallbacks(runnable)
    }
}

/**
 * Maintains a bounded set of public Browser connections. It exposes controller
 * tokens and source identities only; source arbitration remains in
 * MediaSessionArbiter.
 */
internal class PublicMediaBrowserSessionRegistry(
    private val context: Context,
    mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val listener: Listener,
    private val serviceResolver: () -> List<PublicMediaBrowserServiceDescriptor> = {
        PublicMediaBrowserServiceResolver.discover(
            context.packageManager,
            setOf(context.packageName)
        )
    },
    private val clientFactory: PublicMediaBrowserClientFactory =
        PublicMediaBrowserClientFactory(::PlatformPublicMediaBrowserClient),
    private val scheduler: PublicMediaBrowserScheduler =
        HandlerPublicMediaBrowserScheduler(mainHandler),
    private val elapsedRealtime: () -> Long = android.os.SystemClock::elapsedRealtime
) {
    interface Listener {
        fun onSessionsChanged(sessions: List<PublicMediaBrowserSession>)
        fun onStateChanged(
            descriptor: PublicMediaBrowserServiceDescriptor,
            state: PublicMediaBrowserConnectionState
        )
    }

    private data class Entry(
        val descriptor: PublicMediaBrowserServiceDescriptor,
        var generation: Long = 0L,
        var retryCount: Int = 0,
        var client: PublicMediaBrowserClient? = null,
        var session: PublicMediaBrowserSession? = null,
        var state: PublicMediaBrowserConnectionState? = null,
        var unavailableAtMs: Long = 0L,
        var timeout: Runnable? = null,
        var retry: Runnable? = null
    )

    private val entries = linkedMapOf<String, Entry>()
    private var started = false
    private var publishedSessionSignature: List<String> = emptyList()

    val currentSessions: List<PublicMediaBrowserSession>
        get() = entries.values.mapNotNull(Entry::session)

    val activeConnectionCount: Int
        get() = entries.values.count { entry ->
            entry.client != null || entry.state == PublicMediaBrowserConnectionState.RETRY_WAIT
        }

    fun refresh(
        eligiblePackages: Set<String>,
        preferredSourceId: String?,
        bluetoothRoutePresent: Boolean,
        discoverAllSources: Boolean
    ) {
        started = true
        val descriptors = runCatching { serviceResolver() }.getOrDefault(emptyList())
        val selected = PublicMediaBrowserServiceResolver.select(
            descriptors
                .filter { descriptor ->
                    PublicMediaBrowserRegistryPolicy.shouldInclude(
                        descriptor = descriptor,
                        eligiblePackages = eligiblePackages,
                        preferredSourceId = preferredSourceId,
                        bluetoothRoutePresent = bluetoothRoutePresent,
                        discoverAllSources = discoverAllSources
                    )
                }
                .sortedBy { if (it.sourceKey == preferredSourceId) 0 else 1 }
        )
        val selectedSources = selected.mapTo(linkedSetOf()) { it.sourceKey }

        entries.keys.filterNot(selectedSources::contains).forEach { sourceKey ->
            entries.remove(sourceKey)?.let(::dispose)
        }
        selected.forEach { descriptor ->
            val entry = entries.getOrPut(descriptor.sourceKey) { Entry(descriptor) }
            when {
                entry.client != null -> Unit
                entry.state == PublicMediaBrowserConnectionState.RETRY_WAIT -> Unit
                entry.state == PublicMediaBrowserConnectionState.UNAVAILABLE &&
                    elapsedRealtime() - entry.unavailableAtMs <
                    PublicMediaBrowserRegistryPolicy.REPROBE_DELAY_MS -> Unit
                else -> {
                    entry.retryCount = 0
                    connect(entry)
                }
            }
        }
        publishSessions()
    }

    fun onSessionDestroyed(token: MediaSession.Token) {
        val entry = entries.values.firstOrNull {
            it.session?.controller?.sessionToken == token
        } ?: return
        disposeConnection(entry, clearSession = true)
        entry.retryCount = 0
        if (started) connect(entry)
    }

    fun disconnect() {
        started = false
        entries.values.forEach(::dispose)
        entries.clear()
        publishSessions()
    }

    private fun connect(entry: Entry) {
        if (!started) return
        disposeConnection(entry, clearSession = false)
        val generation = ++entry.generation
        val callback = object : PublicMediaBrowserClient.Callback {
            override fun onConnected(controller: MediaController) {
                if (!isCurrent(entry, generation)) return
                if (controller.packageName != entry.descriptor.packageName) {
                    handleFailure(entry, generation)
                    return
                }
                removeTimeout(entry)
                entry.retryCount = 0
                entry.session = PublicMediaBrowserSession(controller, entry.descriptor)
                setState(entry, PublicMediaBrowserConnectionState.CONNECTED)
                publishSessions()
            }

            override fun onConnectionSuspended() {
                if (!isCurrent(entry, generation)) return
                disposeConnection(entry, clearSession = true)
                setState(entry, PublicMediaBrowserConnectionState.SUSPENDED)
                if (started) connect(entry)
            }

            override fun onConnectionFailed() {
                handleFailure(entry, generation)
            }
        }
        val client = runCatching {
            clientFactory.create(context, entry.descriptor, callback)
        }.getOrNull()
        if (client == null) {
            handleFailure(entry, generation)
            return
        }
        entry.client = client
        setState(entry, PublicMediaBrowserConnectionState.CONNECTING)
        val timeout = Runnable { handleFailure(entry, generation) }
        entry.timeout = timeout
        scheduler.postDelayed(timeout, PublicMediaBrowserRegistryPolicy.CONNECT_TIMEOUT_MS)
        runCatching { client.connect() }
            .onFailure { handleFailure(entry, generation) }
    }

    private fun handleFailure(entry: Entry, generation: Long) {
        if (!isCurrent(entry, generation)) return
        disposeConnection(entry, clearSession = true)
        if (started && PublicMediaBrowserRegistryPolicy.shouldRetry(entry.retryCount)) {
            entry.retryCount += 1
            setState(entry, PublicMediaBrowserConnectionState.RETRY_WAIT)
            val retryGeneration = entry.generation
            val retry = Runnable {
                if (started && entry.generation == retryGeneration &&
                    entry.state == PublicMediaBrowserConnectionState.RETRY_WAIT
                ) {
                    connect(entry)
                }
            }
            entry.retry = retry
            scheduler.postDelayed(retry, PublicMediaBrowserRegistryPolicy.RETRY_DELAY_MS)
        } else {
            entry.unavailableAtMs = elapsedRealtime()
            setState(entry, PublicMediaBrowserConnectionState.UNAVAILABLE)
        }
    }

    private fun dispose(entry: Entry) {
        disposeConnection(entry, clearSession = true)
    }

    private fun disposeConnection(entry: Entry, clearSession: Boolean) {
        entry.generation += 1L
        removeTimeout(entry)
        removeRetry(entry)
        entry.client?.let { runCatching { it.disconnect() } }
        entry.client = null
        if (clearSession && entry.session != null) {
            entry.session = null
            publishSessions()
        }
    }

    private fun removeTimeout(entry: Entry) {
        entry.timeout?.let(scheduler::removeCallbacks)
        entry.timeout = null
    }

    private fun removeRetry(entry: Entry) {
        entry.retry?.let(scheduler::removeCallbacks)
        entry.retry = null
    }

    private fun isCurrent(entry: Entry, generation: Long): Boolean =
        started && entry.generation == generation

    private fun setState(entry: Entry, state: PublicMediaBrowserConnectionState) {
        if (entry.state == state) return
        entry.state = state
        listener.onStateChanged(entry.descriptor, state)
    }

    private fun publishSessions() {
        val sessions = currentSessions
        val signature = sessions.map { session ->
            "${session.descriptor.sourceKey}:${session.controller.sessionToken}"
        }
        if (signature == publishedSessionSignature) return
        publishedSessionSignature = signature
        listener.onSessionsChanged(sessions)
    }
}

private class PlatformPublicMediaBrowserClient(
    context: Context,
    descriptor: PublicMediaBrowserServiceDescriptor,
    private val callback: PublicMediaBrowserClient.Callback
) : PublicMediaBrowserClient {
    private val browser: MediaBrowser by lazy {
        MediaBrowser(
            context,
            descriptor.componentName,
            object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    val token = runCatching { browser.sessionToken }.getOrNull()
                    val controller = token?.let {
                        runCatching { MediaController(context, it) }.getOrNull()
                    }
                    if (controller == null) {
                        callback.onConnectionFailed()
                    } else {
                        callback.onConnected(controller)
                    }
                }

                override fun onConnectionSuspended() {
                    callback.onConnectionSuspended()
                }

                override fun onConnectionFailed() {
                    callback.onConnectionFailed()
                }
            },
            null
        )
    }

    override fun connect() {
        browser.connect()
    }

    override fun disconnect() {
        runCatching { browser.disconnect() }
    }
}
