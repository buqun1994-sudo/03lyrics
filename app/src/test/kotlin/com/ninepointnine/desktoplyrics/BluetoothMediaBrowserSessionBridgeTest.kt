package com.ninepointnine.desktoplyrics

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.os.Handler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION")
class BluetoothMediaBrowserSessionBridgeTest {
    @Test
    fun `resolver maps the Android 9 and Android 10 service profiles`() {
        assertEquals(
            BluetoothMediaBrowserProfile.ANDROID_9_A2DP,
            BluetoothMediaBrowserServiceResolver.profileForServiceName(
                "com.android.bluetooth.A2dpMediaBrowserService"
            )
        )
        assertEquals(
            BluetoothMediaBrowserProfile.ANDROID_10_PLUS,
            BluetoothMediaBrowserServiceResolver.profileForServiceName(
                "com.android.bluetooth.BluetoothMediaBrowserService"
            )
        )
        assertEquals(
            MediaSessionDurationUnit.MILLISECONDS,
            BluetoothMediaBrowserProfile.ANDROID_9_A2DP.durationUnit
        )
        assertEquals(
            MediaSessionDurationUnit.SECONDS,
            BluetoothMediaBrowserProfile.ANDROID_10_PLUS.durationUnit
        )
        assertNull(
            BluetoothMediaBrowserServiceResolver.profileForServiceName(
                "com.android.bluetooth.PrivateMediaService"
            )
        )
    }

    @Test
    fun `resolver accepts only exported bluetooth services`() {
        val accepted = ServiceInfo().apply {
            packageName = "com.android.bluetooth"
            name = "com.android.bluetooth.BluetoothMediaBrowserService"
            exported = true
        }
        val wrongPackage = ServiceInfo().apply {
            packageName = "com.example.player"
            name = "com.android.bluetooth.BluetoothMediaBrowserService"
            exported = true
        }
        val hidden = ServiceInfo().apply {
            packageName = "com.android.bluetooth"
            name = "com.android.bluetooth.BluetoothMediaBrowserService"
            exported = false
        }

        assertEquals(
            BluetoothMediaBrowserProfile.ANDROID_10_PLUS,
            BluetoothMediaBrowserServiceResolver.resolve(accepted)?.profile
        )
        assertNull(BluetoothMediaBrowserServiceResolver.resolve(wrongPackage))
        assertNull(BluetoothMediaBrowserServiceResolver.resolve(hidden))
    }

    @Test
    fun `resolver chooses the platform matching profile and keeps deterministic order`() {
        val android9 = descriptor(
            "com.android.bluetooth.A2dpMediaBrowserService",
            BluetoothMediaBrowserProfile.ANDROID_9_A2DP
        )
        val android10 = descriptor(
            "com.android.bluetooth.BluetoothMediaBrowserService",
            BluetoothMediaBrowserProfile.ANDROID_10_PLUS
        )

        assertEquals(android9, BluetoothMediaBrowserServiceResolver.choose(listOf(android10, android9), 28))
        assertEquals(android10, BluetoothMediaBrowserServiceResolver.choose(listOf(android9, android10), 30))
    }

    @Test
    fun `fallback connection requires a bluetooth route and no system bluetooth controller`() {
        assertTrue(
            BluetoothMediaBrowserBridgePolicy.shouldConnect(
                bluetoothRoutePresent = true,
                systemControllerPackages = listOf("com.tencent.wecarflow")
            )
        )
        assertFalse(
            BluetoothMediaBrowserBridgePolicy.shouldConnect(
                bluetoothRoutePresent = false,
                systemControllerPackages = emptyList()
            )
        )
        assertFalse(
            BluetoothMediaBrowserBridgePolicy.shouldConnect(
                bluetoothRoutePresent = true,
                systemControllerPackages = listOf("com.android.bluetooth")
            )
        )
    }

    @Test
    fun `browser route policy accepts A2DP and BLE only on supported platform`() {
        assertTrue(
            BluetoothMediaBrowserBridgePolicy.isBluetoothOutputType(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                sdkInt = 28
            )
        )
        assertTrue(
            BluetoothMediaBrowserBridgePolicy.isBluetoothOutputType(
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                sdkInt = 31
            )
        )
        assertFalse(
            BluetoothMediaBrowserBridgePolicy.isBluetoothOutputType(
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                sdkInt = 30
            )
        )
        assertFalse(
            BluetoothMediaBrowserBridgePolicy.isBluetoothOutputType(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                sdkInt = 31
            )
        )
    }

    @Test
    fun `controller merge preserves system order and removes duplicate token`() {
        assertEquals(
            listOf("system-a", "system-b", "browser"),
            BluetoothMediaBrowserBridgePolicy.mergeKeys(
                systemKeys = listOf("system-a", "system-b", "system-a"),
                browserKey = "browser"
            )
        )
        assertEquals(
            listOf("system-a"),
            BluetoothMediaBrowserBridgePolicy.mergeKeys(
                systemKeys = listOf("system-a"),
                browserKey = "system-a"
            )
        )
    }

    @Test
    fun `retry policy allows exactly one retry for a live request`() {
        assertTrue(BluetoothMediaBrowserBridgePolicy.shouldRetry(0, requested = true))
        assertFalse(BluetoothMediaBrowserBridgePolicy.shouldRetry(1, requested = true))
        assertFalse(BluetoothMediaBrowserBridgePolicy.shouldRetry(0, requested = false))
        assertEquals(3_000L, BluetoothMediaBrowserBridgePolicy.CONNECT_TIMEOUT_MS)
        assertEquals(1_000L, BluetoothMediaBrowserBridgePolicy.RETRY_DELAY_MS)
    }

    @Test
    fun `bridge times out once, retries once, then becomes unavailable`() {
        val scheduler = FakeScheduler()
        val clients = mutableListOf<FakeClient>()
        val listener = RecordingListener()
        val descriptor = descriptor(
            "com.android.bluetooth.BluetoothMediaBrowserService",
            BluetoothMediaBrowserProfile.ANDROID_10_PLUS
        )
        val bridge = BluetoothMediaBrowserSessionBridge(
            context = ContextWrapper(null),
            mainHandler = Handler(),
            listener = listener,
            serviceResolver = { descriptor },
            clientFactory = BluetoothMediaBrowserClientFactory { _, _, callback ->
                FakeClient(callback).also(clients::add)
            },
            scheduler = scheduler
        )

        bridge.refresh(bluetoothRoutePresent = true, systemControllerPackages = emptyList())
        assertEquals(BluetoothMediaBrowserBridgeState.CONNECTING, bridge.currentState)
        assertEquals(1, clients.size)
        scheduler.advanceBy(BluetoothMediaBrowserBridgePolicy.CONNECT_TIMEOUT_MS)
        assertEquals(BluetoothMediaBrowserBridgeState.RETRY_WAIT, bridge.currentState)
        assertEquals(1, clients[0].disconnectCount)
        bridge.refresh(bluetoothRoutePresent = true, systemControllerPackages = emptyList())
        assertEquals(1, clients.size)
        scheduler.advanceBy(BluetoothMediaBrowserBridgePolicy.RETRY_DELAY_MS)
        assertEquals(BluetoothMediaBrowserBridgeState.CONNECTING, bridge.currentState)
        assertEquals(2, clients.size)
        scheduler.advanceBy(BluetoothMediaBrowserBridgePolicy.CONNECT_TIMEOUT_MS)
        assertEquals(BluetoothMediaBrowserBridgeState.UNAVAILABLE, bridge.currentState)
        assertEquals(1, clients[1].disconnectCount)
        assertEquals(2, clients.sumOf(FakeClient::connectCount))
        assertTrue(listener.states.contains(BluetoothMediaBrowserBridgeState.RETRY_WAIT))
    }

    @Test
    fun `disconnect invalidates late callbacks and route transition permits a new request`() {
        val scheduler = FakeScheduler()
        val clients = mutableListOf<FakeClient>()
        val listener = RecordingListener()
        val descriptor = descriptor(
            "com.android.bluetooth.A2dpMediaBrowserService",
            BluetoothMediaBrowserProfile.ANDROID_9_A2DP
        )
        val bridge = BluetoothMediaBrowserSessionBridge(
            context = ContextWrapper(null),
            mainHandler = Handler(),
            listener = listener,
            serviceResolver = { descriptor },
            clientFactory = BluetoothMediaBrowserClientFactory { _, _, callback ->
                FakeClient(callback).also(clients::add)
            },
            scheduler = scheduler
        )

        bridge.refresh(bluetoothRoutePresent = true, systemControllerPackages = emptyList())
        val first = clients.single()
        bridge.refresh(bluetoothRoutePresent = false, systemControllerPackages = emptyList())
        assertEquals(BluetoothMediaBrowserBridgeState.IDLE, bridge.currentState)
        first.callback.onConnectionFailed()
        scheduler.advanceBy(10_000L)
        assertEquals(1, clients.size)

        bridge.refresh(bluetoothRoutePresent = true, systemControllerPackages = emptyList())
        assertEquals(2, clients.size)
        assertEquals(BluetoothMediaBrowserBridgeState.CONNECTING, bridge.currentState)
    }

    @Test
    fun `suspension keeps the request alive and reconnects on the next refresh`() {
        val scheduler = FakeScheduler()
        val clients = mutableListOf<FakeClient>()
        val listener = RecordingListener()
        val descriptor = descriptor(
            "com.android.bluetooth.BluetoothMediaBrowserService",
            BluetoothMediaBrowserProfile.ANDROID_10_PLUS
        )
        val bridge = BluetoothMediaBrowserSessionBridge(
            context = ContextWrapper(null),
            mainHandler = Handler(),
            listener = listener,
            serviceResolver = { descriptor },
            clientFactory = BluetoothMediaBrowserClientFactory { _, _, callback ->
                FakeClient(callback).also(clients::add)
            },
            scheduler = scheduler
        )

        bridge.refresh(bluetoothRoutePresent = true, systemControllerPackages = emptyList())
        clients.single().callback.onConnectionSuspended()
        assertEquals(BluetoothMediaBrowserBridgeState.SUSPENDED, bridge.currentState)
        bridge.refresh(bluetoothRoutePresent = true, systemControllerPackages = emptyList())
        assertEquals(2, clients.size)
        assertEquals(BluetoothMediaBrowserBridgeState.CONNECTING, bridge.currentState)
    }

    private fun descriptor(
        className: String,
        profile: BluetoothMediaBrowserProfile
    ): BluetoothMediaBrowserServiceDescriptor =
        BluetoothMediaBrowserServiceDescriptor(
            componentName = ComponentName("com.android.bluetooth", className),
            profile = profile
        )

    private class RecordingListener : BluetoothMediaBrowserSessionBridge.Listener {
        val states = mutableListOf<BluetoothMediaBrowserBridgeState>()

        override fun onSessionChanged(session: BluetoothMediaBrowserSession?) = Unit

        override fun onConnectionSuspended() = Unit

        override fun onStateChanged(
            state: BluetoothMediaBrowserBridgeState,
            descriptor: BluetoothMediaBrowserServiceDescriptor?
        ) {
            states += state
        }
    }

    private class FakeClient(
        val callback: BluetoothMediaBrowserClient.Callback
    ) : BluetoothMediaBrowserClient {
        var connectCount = 0
        var disconnectCount = 0

        override fun connect() {
            connectCount += 1
        }

        override fun disconnect() {
            disconnectCount += 1
        }
    }

    private class FakeScheduler : BluetoothMediaBrowserScheduler {
        private data class Task(val dueAt: Long, val runnable: Runnable)

        private val tasks = mutableListOf<Task>()
        private var now = 0L

        override fun postDelayed(runnable: Runnable, delayMillis: Long) {
            tasks += Task(now + delayMillis, runnable)
        }

        override fun removeCallbacks(runnable: Runnable) {
            tasks.removeAll { it.runnable === runnable }
        }

        fun advanceBy(deltaMillis: Long) {
            now += deltaMillis
            while (true) {
                val next = tasks
                    .filter { it.dueAt <= now }
                    .minByOrNull(Task::dueAt)
                    ?: return
                tasks.remove(next)
                next.runnable.run()
            }
        }
    }
}
