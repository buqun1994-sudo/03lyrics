package com.ninepointnine.desktoplyrics

import android.content.ContextWrapper
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.os.Handler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicMediaBrowserSessionRegistryTest {
    @Test
    fun `resolver accepts exported standard services without a player allowlist`() {
        val aqt = ServiceInfo().apply {
            packageName = "com.tencent.wecarflow"
            name = "com.tencent.wecarflow.player.MediaPlaybackService"
            exported = true
        }
        val hidden = ServiceInfo().apply {
            packageName = "com.example.hidden"
            name = "com.example.hidden.MusicService"
            exported = false
        }

        assertEquals(
            "com.tencent.wecarflow/.player.MediaPlaybackService",
            PublicMediaBrowserServiceResolver.resolve(aqt)?.sourceKey
        )
        assertNull(PublicMediaBrowserServiceResolver.resolve(hidden))
        assertNull(
            PublicMediaBrowserServiceResolver.resolve(
                aqt,
                excludedPackages = setOf("com.tencent.wecarflow")
            )
        )
    }

    @Test
    fun `resolver deduplicates services and enforces the connection budget`() {
        val descriptors = (0 until 10).map { index ->
            descriptor("com.example.player$index", "MusicService")
        }

        val selected = PublicMediaBrowserServiceResolver.select(
            listOf(descriptors.first()) + descriptors
        )

        assertEquals(PublicMediaBrowserServiceResolver.MAX_SERVICES, selected.size)
        assertEquals(descriptors.take(8), selected)
    }

    @Test
    fun `resolver keeps bluetooth duration profiles in the unified adapter`() {
        assertEquals(
            MediaSessionDurationUnit.MILLISECONDS,
            PublicMediaBrowserServiceResolver.durationUnitFor(
                "com.android.bluetooth",
                "com.android.bluetooth.A2dpMediaBrowserService",
                sdkInt = 28
            )
        )
        assertEquals(
            MediaSessionDurationUnit.SECONDS,
            PublicMediaBrowserServiceResolver.durationUnitFor(
                "com.android.bluetooth",
                "com.android.bluetooth.BluetoothMediaBrowserService",
                sdkInt = 30
            )
        )
        assertEquals(
            MediaSessionDurationUnit.MILLISECONDS,
            PublicMediaBrowserServiceResolver.durationUnitFor(
                "com.tencent.wecarflow",
                "com.tencent.wecarflow.player.MediaPlaybackService",
                sdkInt = 28
            )
        )
    }

    @Test
    fun `bluetooth connections keep the route gate while other sources use public discovery`() {
        val bluetooth = descriptor("com.android.bluetooth", ".A2dpMediaBrowserService")
        val aqt = descriptor("com.tencent.wecarflow", ".player.MediaPlaybackService")
        assertFalse(
            PublicMediaBrowserRegistryPolicy.shouldInclude(
                bluetooth,
                eligiblePackages = setOf("com.android.bluetooth"),
                preferredSourceId = null,
                bluetoothRoutePresent = false,
                discoverAllSources = false
            )
        )
        assertTrue(
            PublicMediaBrowserRegistryPolicy.shouldInclude(
                bluetooth,
                eligiblePackages = emptySet(),
                preferredSourceId = null,
                bluetoothRoutePresent = true,
                discoverAllSources = false
            )
        )
        assertTrue(
            PublicMediaBrowserRegistryPolicy.shouldInclude(
                aqt,
                eligiblePackages = emptySet(),
                preferredSourceId = aqt.sourceKey,
                bluetoothRoutePresent = false,
                discoverAllSources = false
            )
        )
        assertTrue(
            PublicMediaBrowserRegistryPolicy.shouldInclude(
                aqt,
                eligiblePackages = emptySet(),
                preferredSourceId = null,
                bluetoothRoutePresent = false,
                discoverAllSources = true
            )
        )
        assertTrue(
            PublicMediaBrowserRegistryPolicy.isBluetoothOutputType(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                sdkInt = 28
            )
        )
        assertFalse(
            PublicMediaBrowserRegistryPolicy.isBluetoothOutputType(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                sdkInt = 28
            )
        )
    }

    @Test
    fun `registry connects only eligible packages and preferred source`() {
        val scheduler = FakeScheduler()
        val clients = linkedMapOf<PublicMediaBrowserServiceDescriptor, FakeClient>()
        val aqt = descriptor("com.tencent.wecarflow", ".player.MediaPlaybackService")
        val bluetooth = descriptor("com.android.bluetooth", ".A2dpMediaBrowserService")
        val podcast = descriptor("com.example.podcast", ".PodcastService")
        val registry = PublicMediaBrowserSessionRegistry(
            context = ContextWrapper(null),
            mainHandler = Handler(),
            listener = RecordingListener(),
            serviceResolver = { listOf(aqt, bluetooth, podcast) },
            clientFactory = PublicMediaBrowserClientFactory { _, descriptor, callback ->
                FakeClient(callback).also { clients[descriptor] = it }
            },
            scheduler = scheduler
        )

        registry.refresh(
            eligiblePackages = setOf("com.tencent.wecarflow"),
            preferredSourceId = podcast.sourceKey,
            bluetoothRoutePresent = false,
            discoverAllSources = false
        )

        assertEquals(setOf(aqt, podcast), clients.keys)
        assertTrue(clients.values.all { it.connectCount == 1 })
        assertFalse(clients.containsKey(bluetooth))
    }

    @Test
    fun `registry retries once then waits for the bounded reprobe interval`() {
        val scheduler = FakeScheduler()
        val clients = mutableListOf<FakeClient>()
        var nowMs = 0L
        val aqt = descriptor("com.tencent.wecarflow", ".player.MediaPlaybackService")
        val registry = PublicMediaBrowserSessionRegistry(
            context = ContextWrapper(null),
            mainHandler = Handler(),
            listener = RecordingListener(),
            serviceResolver = { listOf(aqt) },
            clientFactory = PublicMediaBrowserClientFactory { _, _, callback ->
                FakeClient(callback).also(clients::add)
            },
            scheduler = scheduler,
            elapsedRealtime = { nowMs }
        )

        registry.refresh(
            setOf(aqt.packageName),
            null,
            bluetoothRoutePresent = false,
            discoverAllSources = false
        )
        scheduler.advanceBy(PublicMediaBrowserRegistryPolicy.CONNECT_TIMEOUT_MS)
        scheduler.advanceBy(PublicMediaBrowserRegistryPolicy.RETRY_DELAY_MS)
        scheduler.advanceBy(PublicMediaBrowserRegistryPolicy.CONNECT_TIMEOUT_MS)
        assertEquals(2, clients.size)

        registry.refresh(
            setOf(aqt.packageName),
            null,
            bluetoothRoutePresent = false,
            discoverAllSources = false
        )
        assertEquals(2, clients.size)
        nowMs = PublicMediaBrowserRegistryPolicy.REPROBE_DELAY_MS
        registry.refresh(
            setOf(aqt.packageName),
            null,
            bluetoothRoutePresent = false,
            discoverAllSources = false
        )
        assertEquals(3, clients.size)
    }

    @Test
    fun `disconnect invalidates callbacks and removes scheduled work`() {
        val scheduler = FakeScheduler()
        val clients = mutableListOf<FakeClient>()
        val listener = RecordingListener()
        val aqt = descriptor("com.tencent.wecarflow", ".player.MediaPlaybackService")
        val registry = PublicMediaBrowserSessionRegistry(
            context = ContextWrapper(null),
            mainHandler = Handler(),
            listener = listener,
            serviceResolver = { listOf(aqt) },
            clientFactory = PublicMediaBrowserClientFactory { _, _, callback ->
                FakeClient(callback).also(clients::add)
            },
            scheduler = scheduler
        )

        registry.refresh(
            setOf(aqt.packageName),
            null,
            bluetoothRoutePresent = false,
            discoverAllSources = false
        )
        val client = clients.single()
        registry.disconnect()
        client.callback.onConnectionFailed()
        scheduler.advanceBy(60_000L)

        assertEquals(1, client.disconnectCount)
        assertEquals(1, clients.size)
        assertTrue(listener.sessions.isEmpty())
    }

    private fun descriptor(packageName: String, className: String) =
        PublicMediaBrowserServiceDescriptor(packageName, className)

    private class RecordingListener : PublicMediaBrowserSessionRegistry.Listener {
        val sessions = mutableListOf<List<PublicMediaBrowserSession>>()
        val states = mutableListOf<PublicMediaBrowserConnectionState>()

        override fun onSessionsChanged(sessions: List<PublicMediaBrowserSession>) {
            this.sessions += sessions
        }

        override fun onStateChanged(
            descriptor: PublicMediaBrowserServiceDescriptor,
            state: PublicMediaBrowserConnectionState
        ) {
            states += state
        }
    }

    private class FakeClient(
        val callback: PublicMediaBrowserClient.Callback
    ) : PublicMediaBrowserClient {
        var connectCount = 0
        var disconnectCount = 0

        override fun connect() {
            connectCount += 1
        }

        override fun disconnect() {
            disconnectCount += 1
        }
    }

    private class FakeScheduler : PublicMediaBrowserScheduler {
        private data class Task(val dueAt: Long, val runnable: Runnable)

        private val tasks = mutableListOf<Task>()
        private var nowMs = 0L

        override fun postDelayed(runnable: Runnable, delayMillis: Long) {
            tasks += Task(nowMs + delayMillis, runnable)
        }

        override fun removeCallbacks(runnable: Runnable) {
            tasks.removeAll { it.runnable === runnable }
        }

        fun advanceBy(deltaMillis: Long) {
            nowMs += deltaMillis
            while (true) {
                val next = tasks
                    .filter { it.dueAt <= nowMs }
                    .minByOrNull(Task::dueAt)
                    ?: return
                tasks.remove(next)
                next.runnable.run()
            }
        }
    }
}
