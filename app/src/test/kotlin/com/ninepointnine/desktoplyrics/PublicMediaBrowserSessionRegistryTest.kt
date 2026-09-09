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
    fun `discovery remains open until arbitration selects a source`() {
        assertTrue(
            PublicMediaBrowserRegistryPolicy.shouldDiscoverAllSources(
                currentControllerPresent = false
            )
        )
        assertFalse(
            PublicMediaBrowserRegistryPolicy.shouldDiscoverAllSources(
                currentControllerPresent = true
            )
        )
    }

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
    fun `resolver supplements an exported undeclared vehicle music service`() {
        val cloudMusic = ServiceInfo().apply {
            packageName = "com.tencent.wecarflow"
            name = "com.mychery.cloudmusic.service.CloudMusicService"
            exported = true
            enabled = true
        }
        val protectedMusic = ServiceInfo().apply {
            packageName = "com.tencent.wecarflow"
            name = "com.mychery.cloudmusic.service.ProtectedMusicService"
            exported = true
            enabled = true
            permission = "com.example.MEDIA_CONTROL"
        }
        val unrelated = ServiceInfo().apply {
            packageName = "com.tencent.wecarflow"
            name = "com.tencent.wecarflow.DeviceInfoService"
            exported = true
            enabled = true
        }

        val resolved = listOf(cloudMusic, protectedMusic, unrelated)
            .map(PublicMediaBrowserServiceResolver::resolveUndeclared)
            .filterNotNull()

        assertEquals(
            listOf("com.tencent.wecarflow/com.mychery.cloudmusic.service.CloudMusicService"),
            resolved.map(PublicMediaBrowserServiceDescriptor::sourceKey)
        )
        assertTrue(resolved.single().supplemental)
    }

    @Test
    fun `supplemental vehicle music service survives the shared connection budget`() {
        val standard = (0 until 9).map { descriptor("com.example.player$it", "MusicService") }
        val cloudMusic = descriptor(
            "com.tencent.wecarflow",
            "com.mychery.cloudmusic.service.CloudMusicService",
            supplemental = true
        )

        val selected = PublicMediaBrowserServiceResolver.select(
            descriptors = standard + cloudMusic,
            limit = PublicMediaBrowserServiceResolver.MAX_SERVICES,
            eligiblePackages = setOf(cloudMusic.packageName)
        )

        assertEquals(PublicMediaBrowserServiceResolver.MAX_SERVICES, selected.size)
        assertTrue(cloudMusic.sourceKey in selected.map(PublicMediaBrowserServiceDescriptor::sourceKey))
        assertEquals(cloudMusic.sourceKey, selected.first().sourceKey)
    }

    @Test
    fun `registry merges supplemental endpoints into the existing connection owner`() {
        val cloudMusic = descriptor(
            "com.tencent.wecarflow",
            "com.mychery.cloudmusic.service.CloudMusicService"
        )
        val clients = linkedMapOf<String, FakeClient>()
        val registry = PublicMediaBrowserSessionRegistry(
            context = ContextWrapper(null),
            mainHandler = Handler(),
            listener = RecordingListener(),
            serviceResolver = { emptyList() },
            supplementalServiceResolver = { listOf(cloudMusic) },
            clientFactory = PublicMediaBrowserClientFactory { _, endpoint, callback ->
                FakeClient(callback).also { clients[endpoint.sourceKey] = it }
            },
            scheduler = FakeScheduler()
        )

        registry.refresh(
            eligiblePackages = setOf("com.tencent.wecarflow"),
            preferredSourceId = null,
            bluetoothRoutePresent = false,
            discoverAllSources = false
        )

        assertEquals(1, registry.activeConnectionCount)
        assertEquals(1, clients[cloudMusic.sourceKey]?.connectCount)
    }

    @Test
    fun `discovery keeps the complete public inventory before allocating connections`() {
        val services = (0 until 10).map { index ->
            ServiceInfo().apply {
                packageName = "com.example.player$index"
                name = "$packageName.MusicService"
                exported = true
            }
        }
        val privateService = ServiceInfo().apply {
            packageName = "com.example.private"
            name = "$packageName.MusicService"
            exported = false
        }

        val inventory = PublicMediaBrowserServiceResolver.resolveServices(
            services + services.first() + privateService + null
        )

        assertEquals(10, inventory.size)
        assertEquals(services.last().packageName, inventory.last().packageName)
        assertEquals(8, PublicMediaBrowserServiceResolver.select(inventory).size)
    }

    @Test
    fun `resolver keeps bluetooth duration profiles in the unified adapter`() {
        assertEquals(
            MediaSessionDurationUnit.MILLISECONDS,
            PublicMediaBrowserServiceResolver.durationUnitFor(
                "com.android.bluetooth",
                "com.android.bluetooth.A2dpMediaBrowserService"
            )
        )
        assertEquals(
            MediaSessionDurationUnit.MILLISECONDS,
            PublicMediaBrowserServiceResolver.durationUnitFor(
                "com.android.bluetooth",
                "com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService"
            )
        )
        assertEquals(
            MediaSessionDurationUnit.MILLISECONDS,
            PublicMediaBrowserServiceResolver.durationUnitFor(
                "com.tencent.wecarflow",
                "com.tencent.wecarflow.player.MediaPlaybackService"
            )
        )
    }

    @Test
    fun `bluetooth browser remains discoverable without an output route`() {
        val bluetooth = descriptor("com.android.bluetooth", ".A2dpMediaBrowserService")
        val aqt = descriptor("com.tencent.wecarflow", ".player.MediaPlaybackService")
        assertTrue(
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

        assertEquals(setOf(aqt, bluetooth, podcast), clients.keys)
        assertTrue(clients.values.all { it.connectCount == 1 })
    }

    @Test
    fun `connection budget preserves preferred and active sources beyond the first eight`() {
        val clients = linkedMapOf<String, FakeClient>()
        val others = (0 until 8).map { descriptor("com.example.player$it", "MusicService") }
        val online = descriptor("com.tencent.wecarflow", "com.tencent.wecarflow.player.MediaPlaybackService")
        val bluetooth = descriptor("com.android.bluetooth", "com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService")
        val registry = PublicMediaBrowserSessionRegistry(
            context = ContextWrapper(null),
            mainHandler = Handler(),
            listener = RecordingListener(),
            serviceResolver = {
                PublicMediaBrowserServiceResolver.resolveServices(
                    (others + online + bluetooth).map { descriptor ->
                        ServiceInfo().apply {
                            packageName = descriptor.packageName
                            name = descriptor.serviceName
                            exported = true
                        }
                    }
                )
            },
            clientFactory = PublicMediaBrowserClientFactory { _, descriptor, callback ->
                FakeClient(callback).also { clients[descriptor.sourceKey] = it }
            },
            scheduler = FakeScheduler()
        )

        registry.refresh(
            eligiblePackages = setOf(online.packageName),
            preferredSourceId = bluetooth.sourceKey,
            bluetoothRoutePresent = false,
            discoverAllSources = true
        )

        assertEquals(8, registry.activeConnectionCount)
        assertTrue(online.sourceKey in clients)
        assertTrue(bluetooth.sourceKey in clients)
        assertEquals(listOf(bluetooth.sourceKey, online.sourceKey), clients.keys.take(2))

        registry.refresh(
            eligiblePackages = setOf(online.packageName),
            preferredSourceId = bluetooth.sourceKey,
            bluetoothRoutePresent = false,
            discoverAllSources = false
        )

        assertEquals(2, registry.activeConnectionCount)
        assertEquals(0, clients.getValue(online.sourceKey).disconnectCount)
        assertEquals(0, clients.getValue(bluetooth.sourceKey).disconnectCount)
        assertTrue(clients.values.all { it.connectCount == 1 })
        val otherKeys = others.map { it.sourceKey }.toSet()
        assertTrue(clients.filterKeys { it in otherKeys }.values.all { it.disconnectCount == 1 })
    }

    @Test
    fun `diagnostic connection budget includes later services and still releases every client`() {
        val clients = mutableListOf<FakeClient>()
        val registry = PublicMediaBrowserSessionRegistry(
            context = ContextWrapper(null),
            mainHandler = Handler(),
            listener = RecordingListener(),
            serviceResolver = { (0 until 18).map { descriptor("com.example.player$it", "MusicService") } },
            clientFactory = PublicMediaBrowserClientFactory { _, _, callback -> FakeClient(callback).also(clients::add) },
            scheduler = FakeScheduler(),
            connectionLimit = 16
        )
        registry.refresh(emptySet(), null, bluetoothRoutePresent = false, discoverAllSources = true)
        assertEquals(16, registry.activeConnectionCount)
        registry.disconnect()
        assertEquals(0, registry.activeConnectionCount)
        assertTrue(clients.all { it.disconnectCount == 1 })
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

    private fun descriptor(
        packageName: String,
        className: String,
        supplemental: Boolean = false
    ) = PublicMediaBrowserServiceDescriptor(
        packageName = packageName,
        serviceName = className,
        supplemental = supplemental
    )

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
