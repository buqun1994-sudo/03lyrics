package com.ninepointnine.desktoplyrics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext

class LyricsPlaybackStoreTest {
    @Test
    fun `cache hit publishes lyrics and cache summary together without a network lookup`() = Harness().use { h ->
        val cached = result("song", "cached")
        h.cache.automatic[identity()] = entry(cached)
        h.store.acceptPlayback(state())
        val loading = h.store.currentSnapshot()
        assertEquals(LyricsAvailability.LOADING, loading.availability)
        h.drain()

        assertEquals(cached, h.current.result)
        assertEquals(cached, h.current.cache.current?.result)
        assertEquals(LyricsAvailability.CACHED_AUTOMATIC, h.current.availability)
        assertTrue(h.requests.isEmpty())
        assertNull(loading.result)
        assertEquals(LyricsAvailability.LOADING, loading.availability)
    }

    @Test
    fun `online result is cached and read back before publication`() = Harness().use { h ->
        h.store.acceptPlayback(state())
        h.drain()
        assertEquals(LyricsAvailability.CACHED_AUTOMATIC, h.current.availability)
        assertEquals(h.current.result, h.current.cache.current?.result)
        assertEquals(1, h.current.cache.stats.automaticEntries)
    }

    @Test
    fun `failed cache write exposes online lyrics without claiming a cache entry`() = Harness().use { h ->
        h.cache.acceptWrites = false
        h.store.acceptPlayback(state())
        h.drain()
        assertEquals(LyricsAvailability.ONLINE_ONLY, h.current.availability)
        assertEquals("song", h.current.result?.candidateTrack)
        assertNull(h.current.cache.current)
        assertEquals(0, h.current.cache.stats.totalEntries)
    }

    @Test
    fun `late old recording result cannot overwrite the new recording or enter its cache`() = Harness().use { h ->
        val late = CompletableDeferred<LyricsResolutionOutcome>()
        h.resolve = { query ->
            if (query.track == "old") withContext(NonCancellable) { late.await() }
            else found(query.track)
        }
        h.store.acceptPlayback(state("old"))
        h.drain()
        h.store.acceptPlayback(state("new", 2L, 2L))
        h.drain()
        late.complete(found("old"))
        h.drain()
        assertEquals("new", h.current.result?.candidateTrack)
        assertEquals("new", h.current.identity?.track)
        assertFalse(h.cache.automatic.containsKey(identity("old")))
    }

    @Test
    fun `manual choice cancels the automatic refresh and survives its late result`() = Harness().use { h ->
        val late = CompletableDeferred<LyricsResolutionOutcome>()
        h.resolve = { withContext(NonCancellable) { late.await() } }
        h.cache.automatic[identity()] = entry(result("song", "automatic"), updatedAt = 0L)
        h.candidates = listOf(result("song", "manual"))
        h.store.acceptPlayback(state())
        h.drain()
        h.store.searchManual("song", "Artist", "Album")
        h.drain()
        h.store.selectManual(h.current.searchCandidates.single().token)
        h.drain()
        assertEquals(LyricsAvailability.CACHED_MANUAL, h.current.availability)
        assertEquals("manual", h.current.result?.sourceId)
        assertEquals(h.current.result, h.current.cache.current?.result)
        late.complete(found("song"))
        h.drain()
        assertEquals("manual", h.current.result?.sourceId)
        assertEquals("automatic", h.cache.automatic[identity()]?.result?.sourceId)
    }

    @Test
    fun `restoring automatic removes only the manual override and immediately reuses automatic cache`() = Harness().use { h ->
        h.cache.automatic[identity()] = entry(result("song", "automatic"))
        h.cache.manual[identity()] = entry(result("song", "manual"), manual = true)
        h.store.acceptPlayback(state())
        h.drain()
        assertEquals(LyricsAvailability.CACHED_MANUAL, h.current.availability)
        h.store.restoreAutomatic()
        h.drain()
        assertEquals(LyricsAvailability.CACHED_AUTOMATIC, h.current.availability)
        assertEquals("automatic", h.current.result?.sourceId)
        assertTrue(h.cache.manual.isEmpty())
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun `clearing cache keeps loaded lyrics and reports memory only`() = Harness().use { h ->
        h.cache.automatic[identity()] = entry(result("song", "automatic"))
        h.store.acceptPlayback(state())
        h.drain()
        h.store.clearCurrent()
        h.drain()
        assertEquals("automatic", h.current.result?.sourceId)
        assertEquals(LyricsAvailability.ONLINE_ONLY, h.current.availability)
        assertNull(h.current.cache.current)
        assertEquals(0, h.current.cache.stats.totalEntries)
    }

    @Test
    fun `cache clear cancels an automatic write already queued for IO`() = Harness().use { h ->
        val result = CompletableDeferred<LyricsResolutionOutcome>()
        h.resolve = { result.await() }
        h.store.acceptPlayback(state())
        h.drain()
        result.complete(found("song"))
        h.main.drain()
        assertTrue(h.io.hasTasks)
        h.store.clearCurrent()
        h.drain()
        assertTrue(h.cache.automatic.isEmpty())
        assertNull(h.current.result)
        assertNull(h.current.cache.current)
        assertFalse(h.current.availability == LyricsAvailability.LOADING)
    }

    @Test
    fun `failed manual persistence keeps the previous cached lyrics and reports the failure`() = Harness().use { h ->
        h.cache.automatic[identity()] = entry(result("song", "automatic"))
        h.candidates = listOf(result("song", "manual"))
        h.store.acceptPlayback(state())
        h.drain()
        h.cache.acceptWrites = false
        h.store.searchManual("song", "Artist", "Album")
        h.drain()
        h.store.selectManual(h.current.searchCandidates.single().token)
        h.drain()
        assertEquals("automatic", h.current.result?.sourceId)
        assertEquals(LyricsManualSearchState.ERROR, h.current.searchState)
        assertEquals(h.current.result, h.current.cache.current?.result)
    }

    @Test
    fun `query revision invalidates a queued manual selection`() = Harness().use { h ->
        h.cache.automatic[identity()] = entry(result("song", "automatic"))
        h.candidates = listOf(result("song", "manual"))
        h.store.acceptPlayback(state())
        h.drain()
        h.store.searchManual("song", "Artist", "Album")
        h.drain()
        h.store.selectManual(h.current.searchCandidates.single().token)
        h.main.drain()
        h.store.acceptPlayback(state(revision = 2L))
        h.drain()
        assertTrue(h.cache.manual.isEmpty())
        assertEquals("automatic", h.current.result?.sourceId)
        assertEquals(LyricsManualSearchState.IDLE, h.current.searchState)
    }

    @Test
    fun `unchanged recording does not repeat cache usage or resolution`() = Harness().use { h ->
        h.store.acceptPlayback(state())
        h.drain()
        val reads = h.cache.reads
        val publications = h.snapshots.size
        h.store.acceptPlayback(state())
        h.drain()
        assertEquals(reads, h.cache.reads)
        assertEquals(publications, h.snapshots.size)
        assertEquals(1, h.requests.size)
    }

    @Test
    fun `a new store loads an already observed recording without a change event`() = Harness().use { h ->
        val adapter = MediaPlaybackAdapter { 1_000L }
        val metadata = state().metadata
        adapter.updateRecording("source", metadata)
        val replayed = requireNotNull(adapter.updateRecording("source", metadata))
        assertFalse(replayed.recordingChanged)
        assertFalse(replayed.queryChanged)
        val cached = result("song", "before-restart")
        h.cache.automatic[identity()] = entry(cached)

        h.store.acceptPlayback(replayed)
        h.drain()
        assertEquals(cached, h.current.result)
        assertEquals(cached, h.current.cache.current?.result)
        assertEquals(1, h.cache.reads)
        h.store.acceptPlayback(replayed)
        h.drain()
        assertEquals(1, h.cache.reads)
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun `startup metadata enrichment loads the cache without changing songs`() = Harness().use { h ->
        val adapter = MediaPlaybackAdapter { 1_000L }
        val metadata = state().metadata
        val partial = requireNotNull(adapter.updateRecording("source", metadata.copy(durationMs = 0L)))
        h.cache.automatic[identity()] = entry(result("song", "before-restart"))
        h.store.acceptPlayback(partial)
        h.drain()
        assertEquals(LyricsAvailability.EMPTY, h.current.availability)
        assertEquals(0, h.cache.reads)

        val complete = requireNotNull(adapter.updateRecording("source", metadata))
        assertEquals(partial.recordingGeneration, complete.recordingGeneration)
        assertTrue(complete.queryChanged)
        h.store.acceptPlayback(complete)
        h.drain()
        assertEquals(LyricsAvailability.CACHED_AUTOMATIC, h.current.availability)
        assertEquals("before-restart", h.current.result?.sourceId)
        assertEquals(h.current.result, h.current.cache.current?.result)
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun `cache overview remains available without a current recording`() = Harness().use { h ->
        h.cache.automatic[identity()] = entry(result("song", "automatic"))
        h.store.acceptPlayback(null)
        h.drain()
        assertNull(h.current.identity)
        assertNull(h.current.cache.current)
        assertEquals(1, h.current.cache.stats.totalEntries)
    }

    @Test
    fun `closed store never publishes queued work`() = Harness().use { h ->
        h.store.acceptPlayback(state())
        val publications = h.snapshots.size
        h.store.close()
        h.drain()
        h.store.acceptPlayback(state("new", 2L, 2L))
        h.drain()
        assertEquals(publications, h.snapshots.size)
        assertTrue(h.cache.automatic.isEmpty())
    }

    private class Harness : Closeable {
        val main = QueueDispatcher()
        val io = QueueDispatcher()
        private val job = SupervisorJob()
        val cache = MemoryCache()
        val requests = mutableListOf<LyricsLookup>()
        val snapshots = mutableListOf<LyricsPlaybackStoreSnapshot>()
        var candidates = emptyList<LyricsResult>()
        var resolve: suspend (LyricsLookup) -> LyricsResolutionOutcome = { found(it.track) }
        val store = LyricsPlaybackStore(
            cache = cache,
            resolveAutomatic = { requests += it; resolve(it) },
            searchCandidates = { _, _ -> candidates },
            loadManualLyrics = { candidate, _ -> candidate },
            scope = CoroutineScope(job + main),
            listener = LyricsPlaybackStore.Listener { snapshots += it },
            ioDispatcher = io,
            nowEpochMs = { NOW }
        )
        val current get() = store.currentSnapshot()

        fun drain() {
            repeat(100) {
                main.drain()
                io.drain()
                if (!main.hasTasks && !io.hasTasks) return
            }
            error("Store did not reach an idle state")
        }

        override fun close() {
            store.close()
            job.cancel()
            drain()
        }
    }

    private class QueueDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        val hasTasks get() = tasks.isNotEmpty()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private class MemoryCache : LyricsPlaybackCache {
        val automatic = linkedMapOf<LyricsPlaybackIdentity, LyricsCache.Entry>()
        val manual = linkedMapOf<LyricsPlaybackIdentity, LyricsCache.Entry>()
        var acceptWrites = true
        var reads = 0
        override fun read(identity: LyricsPlaybackIdentity, recordUse: Boolean): LyricsCache.Entry? {
            reads += 1
            return manual[identity] ?: automatic[identity]
        }
        override fun writeAutomatic(identity: LyricsPlaybackIdentity, resolved: ResolvedLyrics): Boolean {
            if (!acceptWrites) return false
            automatic[identity] = entry(resolved.result)
            return true
        }
        override fun writeManual(identity: LyricsPlaybackIdentity, result: LyricsResult): Boolean {
            if (!acceptWrites) return false
            manual[identity] = entry(result, manual = true)
            return true
        }
        override fun clearManual(identity: LyricsPlaybackIdentity) = manual.remove(identity) != null
        override fun clearCurrent(identity: LyricsPlaybackIdentity): Boolean {
            val removedManual = manual.remove(identity)
            val removedAutomatic = automatic.remove(identity)
            return removedManual != null || removedAutomatic != null
        }
        override fun snapshot(identity: LyricsPlaybackIdentity?, currentEntry: LyricsCache.Entry?) =
            LyricsCacheSnapshot(
                LyricsCacheStats(automatic.size, manual.size, (automatic.size + manual.size) * 1024L, LyricsCachePolicy.MAX_BYTES),
                currentEntry?.let { LyricsCachedTrackInfo(it.selection, it.result, it.updatedAtMs) }
            )
    }

    private companion object {
        const val NOW = 4_000_000_000L
        fun identity(track: String = "song") = LyricsPlaybackIdentity(track, "Artist", "Album", 200_000L)
        fun state(track: String = "song", generation: Long = 1L, revision: Long = 1L) = MediaRecordingState(
            MediaRecordingMetadata(track, "Artist", "Album", 200_000L), generation, revision, true, true
        )
        fun result(track: String, id: String) = LyricsResult(
            lyrics = "[00:01.00]$id\n[00:10.00]Second line",
            durationMs = 200_000L,
            source = "test",
            sourceId = id,
            candidateTrack = track,
            candidateArtist = "Artist",
            candidateAlbum = "Album",
            lyricsKind = LyricsKind.SYNCHRONIZED
        )
        fun entry(result: LyricsResult, manual: Boolean = false, updatedAt: Long = NOW) = LyricsCache.Entry(
            result,
            if (manual) null else LyricsSelectionProof(LYRICS_MATCHER_POLICY_VERSION, listOf(result.candidateSnapshot())),
            updatedAt,
            if (manual) LyricsCacheSelection.MANUAL else LyricsCacheSelection.AUTOMATIC
        )
        fun found(track: String): LyricsResolutionOutcome = LyricsResolutionOutcome.Found(
            requireNotNull(entry(result(track, "online")).resolved)
        )
    }
}
