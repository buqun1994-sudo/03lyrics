package com.ninepointnine.desktoplyrics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * The small amount of platform-specific knowledge needed to recover a
 * Bluetooth MediaSession that the system active-session list did not expose.
 * Selection, metadata parsing, and recording generations remain outside this
 * adapter.
 */
internal enum class BluetoothMediaBrowserProfile(
    val durationUnit: MediaSessionDurationUnit
) {
    ANDROID_9_A2DP(MediaSessionDurationUnit.MILLISECONDS),
    ANDROID_10_PLUS(MediaSessionDurationUnit.SECONDS),
    UNKNOWN(MediaSessionDurationUnit.UNKNOWN)
}

internal data class BluetoothMediaBrowserServiceDescriptor(
    val componentName: ComponentName,
    val profile: BluetoothMediaBrowserProfile
)

internal data class BluetoothMediaBrowserSession(
    val controller: MediaController,
    val profile: BluetoothMediaBrowserProfile
)

internal enum class BluetoothMediaBrowserBridgeState {
    IDLE,
    CONNECTING,
    SUSPENDED,
    CONNECTED,
    RETRY_WAIT,
    UNAVAILABLE
}

/** Public service discovery is kept separate so it can be tested without a car. */
internal object BluetoothMediaBrowserServiceResolver {
    const val ACTION = "android.media.browse.MediaBrowserService"
    const val BLUETOOTH_PACKAGE = "com.android.bluetooth"

    fun discover(packageManager: PackageManager): List<BluetoothMediaBrowserServiceDescriptor> {
        val services = runCatching {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(
                Intent(ACTION).setPackage(BLUETOOTH_PACKAGE),
                0
            )
        }.getOrElse { return emptyList() }
        return services.mapNotNull { resolve(it.serviceInfo) }
    }

    fun resolve(serviceInfo: ServiceInfo?): BluetoothMediaBrowserServiceDescriptor? {
        if (serviceInfo == null || !serviceInfo.exported) return null
        if (serviceInfo.packageName != BLUETOOTH_PACKAGE) return null
        val serviceName = serviceInfo.name?.trim().orEmpty()
        if (serviceName.isEmpty()) return null
        val profile = profileForServiceName(serviceName) ?: return null
        return BluetoothMediaBrowserServiceDescriptor(
            componentName = ComponentName(serviceInfo.packageName, serviceName),
            profile = profile
        )
    }

    fun profileForServiceName(serviceName: String): BluetoothMediaBrowserProfile? {
        val simpleName = serviceName.substringAfterLast('.').lowercase(Locale.ROOT)
        return when {
            simpleName == "a2dpmediabrowserservice" ->
                BluetoothMediaBrowserProfile.ANDROID_9_A2DP
            simpleName == "bluetoothmediabrowserservice" ->
                BluetoothMediaBrowserProfile.ANDROID_10_PLUS
            else -> null
        }
    }

    fun choose(
        descriptors: List<BluetoothMediaBrowserServiceDescriptor>,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): BluetoothMediaBrowserServiceDescriptor? {
        val preferred = if (sdkInt >= 29) {
            BluetoothMediaBrowserProfile.ANDROID_10_PLUS
        } else {
            BluetoothMediaBrowserProfile.ANDROID_9_A2DP
        }
        return descriptors
            // Kotlin's stable sort preserves PackageManager order for ties;
            // this keeps the resolver deterministic without depending on
            // ComponentName implementation details in the JVM test runtime.
            .sortedBy { if (it.profile == preferred) 0 else 1 }
            .firstOrNull()
    }
}

/** Pure decisions shared by the Android adapter and JVM tests. */
internal object BluetoothMediaBrowserBridgePolicy {
    const val CONNECT_TIMEOUT_MS = 3_000L
    const val RETRY_DELAY_MS = 1_000L
    const val MAX_RETRIES = 1

    fun shouldConnect(
        bluetoothRoutePresent: Boolean,
        systemControllerPackages: Collection<String>
    ): Boolean = bluetoothRoutePresent &&
        systemControllerPackages.none {
            it == BluetoothMediaBrowserServiceResolver.BLUETOOTH_PACKAGE
        }

    fun shouldRetry(retryCount: Int, requested: Boolean): Boolean =
        requested && retryCount < MAX_RETRIES

    @Suppress("DEPRECATION", "InlinedApi")
    fun isBluetoothOutputType(type: Int, sdkInt: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> true
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> sdkInt >= 31
        else -> false
    }

    /** Keeps the MediaSessionManager order while appending a non-duplicate fallback. */
    fun <T> mergeKeys(systemKeys: List<T>, browserKey: T?): List<T> {
        val seen = LinkedHashSet<T>()
        val merged = ArrayList<T>(systemKeys.size + if (browserKey == null) 0 else 1)
        systemKeys.forEach { key ->
            if (seen.add(key)) merged += key
        }
        if (browserKey != null && seen.add(browserKey)) merged += browserKey
        return merged
    }
}

internal interface BluetoothMediaBrowserClient {
    interface Callback {
        fun onConnected(session: BluetoothMediaBrowserSession)
        fun onConnectionSuspended()
        fun onConnectionFailed()
    }

    fun connect()
    fun disconnect()
}

internal interface BluetoothMediaBrowserScheduler {
    fun postDelayed(runnable: Runnable, delayMillis: Long)
    fun removeCallbacks(runnable: Runnable)
}

private class HandlerBluetoothMediaBrowserScheduler(
    private val handler: Handler
) : BluetoothMediaBrowserScheduler {
    override fun postDelayed(runnable: Runnable, delayMillis: Long) {
        handler.postDelayed(runnable, delayMillis)
    }

    override fun removeCallbacks(runnable: Runnable) {
        handler.removeCallbacks(runnable)
    }
}

internal fun interface BluetoothMediaBrowserClientFactory {
    fun create(
        context: Context,
        descriptor: BluetoothMediaBrowserServiceDescriptor,
        callback: BluetoothMediaBrowserClient.Callback
    ): BluetoothMediaBrowserClient
}

/**
 * Owns one public Browser connection and bounds retries. Every callback is
 * tagged with the connection generation so a late platform callback cannot
 * resurrect a route that has already been removed.
 */
internal class BluetoothMediaBrowserSessionBridge(
    private val context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val listener: Listener,
    private val serviceResolver: () -> BluetoothMediaBrowserServiceDescriptor? = {
        BluetoothMediaBrowserServiceResolver.choose(
            BluetoothMediaBrowserServiceResolver.discover(context.packageManager)
        )
    },
    private val clientFactory: BluetoothMediaBrowserClientFactory =
        BluetoothMediaBrowserClientFactory(::PlatformBluetoothMediaBrowserClient),
    private val scheduler: BluetoothMediaBrowserScheduler =
        HandlerBluetoothMediaBrowserScheduler(mainHandler)
) {
    interface Listener {
        fun onSessionChanged(session: BluetoothMediaBrowserSession?)
        fun onConnectionSuspended()
        fun onStateChanged(
            state: BluetoothMediaBrowserBridgeState,
            descriptor: BluetoothMediaBrowserServiceDescriptor?
        )
    }

    private var requested = false
    private var retryCount = 0
    private var connectionGeneration = 0L
    private var descriptor: BluetoothMediaBrowserServiceDescriptor? = null
    private var client: BluetoothMediaBrowserClient? = null
    private var session: BluetoothMediaBrowserSession? = null
    private var state = BluetoothMediaBrowserBridgeState.IDLE
    private var lastShouldConnect: Boolean? = null

    private var timeoutRunnable: Runnable? = null
    private var retryRunnable: Runnable? = null

    val currentSession: BluetoothMediaBrowserSession?
        get() = session

    val currentState: BluetoothMediaBrowserBridgeState
        get() = state

    /** Resolves the public service profile without opening a second connection. */
    fun resolveProfile(): BluetoothMediaBrowserProfile? =
        runCatching { serviceResolver()?.profile }.getOrNull()

    fun refresh(
        bluetoothRoutePresent: Boolean,
        systemControllerPackages: Collection<String>
    ) {
        val shouldConnect = BluetoothMediaBrowserBridgePolicy.shouldConnect(
            bluetoothRoutePresent = bluetoothRoutePresent,
            systemControllerPackages = systemControllerPackages
        )
        if (!shouldConnect) {
            lastShouldConnect = false
            if (requested || client != null || session != null) disconnect()
            return
        }

        if (lastShouldConnect != true) {
            lastShouldConnect = true
            if (state == BluetoothMediaBrowserBridgeState.UNAVAILABLE) {
                setState(BluetoothMediaBrowserBridgeState.IDLE, null)
            }
        }
        if (!requested) {
            requested = true
            retryCount = 0
        }
        when {
            state == BluetoothMediaBrowserBridgeState.SUSPENDED -> {
                disposeClient(clearSession = false)
                startConnection()
            }
            client != null -> Unit
            state == BluetoothMediaBrowserBridgeState.RETRY_WAIT -> Unit
            state == BluetoothMediaBrowserBridgeState.UNAVAILABLE -> {
                // A service may be published after boot. Probe again only
                // when the previous discovery had no descriptor; a known
                // descriptor remains bounded after its one retry.
                if (descriptor == null) {
                    val discovered = runCatching { serviceResolver() }.getOrNull()
                    if (discovered != null) {
                        descriptor = discovered
                        retryCount = 0
                        setState(BluetoothMediaBrowserBridgeState.IDLE, discovered)
                        startConnection()
                    }
                }
            }
            else -> startConnection()
        }
    }

    fun onSessionDestroyed() {
        if (!requested) return
        disconnect()
    }

    fun disconnect() {
        requested = false
        lastShouldConnect = false
        retryCount = 0
        disposeClient(clearSession = true)
        setState(BluetoothMediaBrowserBridgeState.IDLE, null)
    }

    private fun startConnection() {
        if (!requested) return
        val nextDescriptor = runCatching { serviceResolver() }
            .getOrNull()
        if (nextDescriptor == null) {
            descriptor = null
            disposeClient(clearSession = true)
            setState(BluetoothMediaBrowserBridgeState.UNAVAILABLE, null)
            return
        }

        descriptor = nextDescriptor
        val generation = ++connectionGeneration
        val callback = object : BluetoothMediaBrowserClient.Callback {
            override fun onConnected(session: BluetoothMediaBrowserSession) {
                if (!isCurrent(generation)) return
                if (session.controller.packageName !=
                    BluetoothMediaBrowserServiceResolver.BLUETOOTH_PACKAGE
                ) {
                    handleConnectionFailure(generation)
                    return
                }
                removeTimeout()
                retryCount = 0
                this@BluetoothMediaBrowserSessionBridge.session = session
                setState(BluetoothMediaBrowserBridgeState.CONNECTED, descriptor)
                listener.onSessionChanged(session)
            }

            override fun onConnectionSuspended() {
                if (!isCurrent(generation)) return
                setState(BluetoothMediaBrowserBridgeState.SUSPENDED, descriptor)
                listener.onConnectionSuspended()
            }

            override fun onConnectionFailed() {
                if (!isCurrent(generation)) return
                handleConnectionFailure(generation)
            }
        }
        val nextClient = runCatching {
            clientFactory.create(context, nextDescriptor, callback)
        }.getOrNull()
        if (nextClient == null) {
            handleConnectionFailure(generation)
            return
        }
        client = nextClient
        setState(BluetoothMediaBrowserBridgeState.CONNECTING, nextDescriptor)
        val timeout = Runnable {
            if (isCurrent(generation) && state == BluetoothMediaBrowserBridgeState.CONNECTING) {
                handleConnectionFailure(generation)
            }
        }
        timeoutRunnable = timeout
        scheduler.postDelayed(timeout, BluetoothMediaBrowserBridgePolicy.CONNECT_TIMEOUT_MS)
        runCatching { nextClient.connect() }
            .onFailure { handleConnectionFailure(generation) }
    }

    private fun handleConnectionFailure(generation: Long) {
        if (!isCurrent(generation)) return
        removeTimeout()
        disposeClient(clearSession = true)
        if (BluetoothMediaBrowserBridgePolicy.shouldRetry(retryCount, requested)) {
            retryCount += 1
            setState(BluetoothMediaBrowserBridgeState.RETRY_WAIT, descriptor)
            val retryGeneration = connectionGeneration
            val retry = Runnable {
                if (requested && state == BluetoothMediaBrowserBridgeState.RETRY_WAIT &&
                    connectionGeneration == retryGeneration
                ) {
                    startConnection()
                }
            }
            retryRunnable = retry
            scheduler.postDelayed(retry, BluetoothMediaBrowserBridgePolicy.RETRY_DELAY_MS)
        } else {
            setState(BluetoothMediaBrowserBridgeState.UNAVAILABLE, descriptor)
        }
    }

    private fun disposeClient(clearSession: Boolean): Boolean {
        connectionGeneration += 1L
        removeTimeout()
        removeRetry()
        client?.let { runCatching { it.disconnect() } }
        client = null
        var hadSession = false
        if (clearSession) {
            hadSession = session != null
            session = null
            if (hadSession) listener.onSessionChanged(null)
        }
        return hadSession
    }

    private fun removeTimeout() {
        timeoutRunnable?.let(scheduler::removeCallbacks)
        timeoutRunnable = null
    }

    private fun removeRetry() {
        retryRunnable?.let(scheduler::removeCallbacks)
        retryRunnable = null
    }

    private fun isCurrent(generation: Long): Boolean =
        requested && generation == connectionGeneration

    private fun setState(
        next: BluetoothMediaBrowserBridgeState,
        nextDescriptor: BluetoothMediaBrowserServiceDescriptor?
    ) {
        state = next
        listener.onStateChanged(next, nextDescriptor)
    }
}

private class PlatformBluetoothMediaBrowserClient(
    context: Context,
    descriptor: BluetoothMediaBrowserServiceDescriptor,
    private val callback: BluetoothMediaBrowserClient.Callback
) : BluetoothMediaBrowserClient {
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
                        callback.onConnected(
                            BluetoothMediaBrowserSession(controller, descriptor.profile)
                        )
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
