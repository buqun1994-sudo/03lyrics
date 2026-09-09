package com.ninepointnine.desktoplyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

/** All commands, state changes and publications run on the supplied main scope. */
internal class LyricsPlaybackStore(
    private val cache: LyricsPlaybackCache,
    private val resolveAutomatic: suspend (LyricsLookup) -> LyricsResolutionOutcome,
    private val searchCandidates: (LyricsLookup, LyricsCancellationSignal) -> List<LyricsResult>,
    private val loadManualLyrics: (LyricsResult, LyricsCancellationSignal) -> LyricsResult?,
    private val scope: CoroutineScope,
    private val listener: Listener,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) : Closeable {
    fun interface Listener {
        fun onLyricsSnapshot(snapshot: LyricsPlaybackStoreSnapshot)
    }

    private val cacheMutex = Mutex()
    private var initialized = false
    private var closed = false
    private var mediaState: MediaRecordingState? = null
    private var snapshot = LyricsPlaybackStoreSnapshot.empty()
    private var requestEpoch = 0L
    private var activeRequest: Job? = null
    private var manualEpoch = 0L
    private var manualJob: Job? = null
    private var manualCancellation: LyricsCancellationSignal? = null
    private val manualCandidates = linkedMapOf<String, LyricsResult>()

    fun acceptPlayback(state: MediaRecordingState?) {
        if (closed) return
        val unchanged = initialized && sameBinding(mediaState, state)
        mediaState = state
        if (unchanged) return
        initialized = true
        cancelAutomatic()
        cancelManual()
        publish(
            LyricsPlaybackStoreSnapshot.empty().copy(
                identity = state?.identity(),
                recordingGeneration = state?.recordingGeneration ?: 0L,
                queryRevision = state?.queryRevision ?: 0L,
                availability = loadingAvailability(state),
                cache = snapshot.cache.copy(current = null)
            )
        )
        startAutomaticResolution(requestEpoch, state)
    }

    fun retryCurrent() {
        if (closed) return
        val state = mediaState ?: return
        cancelAutomatic()
        cancelManual()
        publish(snapshot.copy(searchState = LyricsManualSearchState.IDLE, searchCandidates = emptyList()))
        startAutomaticResolution(requestEpoch, state)
    }

    fun searchManual(track: String, artist: String, album: String) {
        if (closed) return
        val state = mediaState
        if (state == null || !state.metadata.hasTrack || track.isBlank()) {
            publishManualState(LyricsManualSearchState.NO_CURRENT_TRACK)
            return
        }
        cancelManual()
        val epoch = manualEpoch
        val cancellation = LyricsCancellationSignal()
        manualCancellation = cancellation
        publishManualState(LyricsManualSearchState.SEARCHING)
        manualJob = scope.launch {
            try {
                val results = withContext(ioDispatcher) {
                    searchCandidates(
                        LyricsLookup(track, artist, album, state.metadata.durationMs),
                        cancellation
                    )
                }
                if (!isCurrentManual(epoch, state)) return@launch
                results.forEach { manualCandidates[LyricsManualSearchPolicy.token(it)] = it }
                publishManualState(
                    if (results.isEmpty()) LyricsManualSearchState.EMPTY else LyricsManualSearchState.READY
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (isCurrentManual(epoch, state)) publishManualState(LyricsManualSearchState.ERROR)
            } finally {
                finishManual(epoch)
            }
        }
    }

    fun selectManual(token: String) {
        if (closed) return
        val state = mediaState ?: return publishManualState(LyricsManualSearchState.NO_CURRENT_TRACK)
        val candidate = manualCandidates[token] ?: return publishManualState(LyricsManualSearchState.ERROR)
        cancelAutomatic()
        cancelManual(clearCandidates = false)
        val epoch = manualEpoch
        val cancellation = LyricsCancellationSignal()
        manualCancellation = cancellation
        publishManualState(LyricsManualSearchState.APPLYING)
        manualJob = scope.launch {
            try {
                val result = withContext(ioDispatcher) { loadManualLyrics(candidate, cancellation) }
                if (!isCurrentManual(epoch, state)) return@launch
                if (result == null) {
                    publishManualState(LyricsManualSearchState.ERROR)
                    return@launch
                }
                val read = withCache {
                    cache.writeManual(state.identity(), result)
                    readCache(state.identity())
                }
                if (!isCurrentManual(epoch, state)) return@launch
                val selected = read.entry?.takeIf {
                    it.selection == LyricsCacheSelection.MANUAL &&
                        it.result.source == result.source && it.result.sourceId == result.sourceId
                }
                publishRead(read, snapshot.result, LyricsAvailability.ERROR)
                publishManualState(
                    if (selected != null) LyricsManualSearchState.READY else LyricsManualSearchState.ERROR
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (isCurrentManual(epoch, state)) publishManualState(LyricsManualSearchState.ERROR)
            } finally {
                finishManual(epoch)
            }
        }
    }

    fun restoreAutomatic() = editCurrentCache(restoreAutomatic = true)

    fun clearCurrent() = editCurrentCache(restoreAutomatic = false)

    fun currentSnapshot(): LyricsPlaybackStoreSnapshot = snapshot

    override fun close() {
        if (closed) return
        closed = true
        cancelAutomatic()
        cancelManual()
    }

    private fun editCurrentCache(restoreAutomatic: Boolean) {
        if (closed) return
        val state = mediaState ?: return
        cancelAutomatic()
        cancelManual()
        val epoch = manualEpoch
        publishManualState(LyricsManualSearchState.APPLYING)
        manualJob = scope.launch {
            try {
                val read = withCache {
                    if (restoreAutomatic) cache.clearManual(state.identity())
                    else cache.clearCurrent(state.identity())
                    readCache(state.identity())
                }
                if (!isCurrentManual(epoch, state)) return@launch
                val failed = if (restoreAutomatic) {
                    read.entry?.selection == LyricsCacheSelection.MANUAL
                } else {
                    read.entry != null
                }
                publishRead(
                    read,
                    if (restoreAutomatic && !failed) null else snapshot.result,
                    if (restoreAutomatic) loadingAvailability(state) else LyricsAvailability.EMPTY
                )
                publishManualState(if (failed) LyricsManualSearchState.ERROR else LyricsManualSearchState.IDLE)
                if (restoreAutomatic && !failed) startAutomaticResolution(requestEpoch, state)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (isCurrentManual(epoch, state)) publishManualState(LyricsManualSearchState.ERROR)
            } finally {
                finishManual(epoch)
            }
        }
    }

    private fun startAutomaticResolution(epoch: Long, state: MediaRecordingState?) {
        val identity = state?.identity()
        activeRequest = scope.launch {
            try {
                val read = withCache { readCache(identity, recordUse = true) }
                if (!isCurrent(epoch, state)) return@launch
                if (read.entry?.selection == LyricsCacheSelection.MANUAL) {
                    publishRead(read, null, loadingAvailability(state))
                    return@launch
                }

                val embeddedCandidate = identity?.takeIf { it.isUsable }?.let { usableIdentity ->
                    embeddedResolved(state, usableIdentity)?.let { resolved ->
                        usableIdentity to resolved
                    }
                }
                if (embeddedCandidate != null) {
                    val (embeddedIdentity, embedded) = embeddedCandidate
                    val persisted = withCache {
                        cache.writeAutomatic(embeddedIdentity, embedded)
                        readCache(embeddedIdentity)
                    }
                    if (!isCurrent(epoch, state)) return@launch
                    val cachedEmbedded = persisted.entry?.takeIf {
                        it.selection == LyricsCacheSelection.AUTOMATIC &&
                            it.result.source == EMBEDDED_LYRICS_SOURCE &&
                            it.result.sourceId == EMBEDDED_LYRICS_SOURCE_ID &&
                            it.result == embedded.result
                    }
                    if (cachedEmbedded != null) {
                        publishRead(persisted, null, LyricsAvailability.ONLINE_ONLY)
                    } else {
                        publishEmbedded(embedded.result, persisted.summary)
                    }
                    return@launch
                }

                publishRead(read, null, loadingAvailability(state))
                if (identity?.isUsable != true ||
                    read.entry?.needsRefresh(nowEpochMs()) == false
                ) return@launch

                val outcome = resolveAutomatic(identity.lookup())
                if (!isCurrent(epoch, state)) return@launch
                val resolved = (outcome as? LyricsResolutionOutcome.Found)?.resolved
                if (resolved != null) {
                    val persisted = withCache {
                        cache.writeAutomatic(identity, resolved)
                        readCache(identity)
                    }
                    if (!isCurrent(epoch, state)) return@launch
                    publishRead(persisted, resolved.result, LyricsAvailability.ONLINE_ONLY)
                } else if (read.entry == null && outcome != LyricsResolutionOutcome.Cancelled) {
                    publish(
                        snapshot.copy(
                            result = null,
                            availability = when (outcome) {
                                LyricsResolutionOutcome.NoMatch,
                                LyricsResolutionOutcome.InvalidMetadata -> LyricsAvailability.NO_MATCH
                                else -> LyricsAvailability.ERROR
                            }
                        )
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (isCurrent(epoch, state) && snapshot.result == null) {
                    publish(snapshot.copy(availability = LyricsAvailability.ERROR))
                }
            } finally {
                if (epoch == requestEpoch) activeRequest = null
            }
        }
    }

    private data class CacheRead(val entry: LyricsCache.Entry?, val summary: LyricsCacheSnapshot)

    private fun readCache(identity: LyricsPlaybackIdentity?, recordUse: Boolean = false): CacheRead {
        val entry = identity?.takeIf { it.isUsable }?.let { cache.read(it, recordUse) }
        return CacheRead(entry, cache.snapshot(identity, entry))
    }

    private fun embeddedResolved(
        state: MediaRecordingState?,
        identity: LyricsPlaybackIdentity?
    ): ResolvedLyrics? {
        if (state == null || identity?.isUsable != true) return null
        val lyrics = state.metadata.embeddedLyrics
        if (!isEmbeddedSynchronizedLyrics(lyrics)) return null
        val candidate = LyricsResult(
            lyrics = lyrics,
            durationMs = identity.durationMs,
            source = EMBEDDED_LYRICS_SOURCE,
            sourceId = EMBEDDED_LYRICS_SOURCE_ID,
            candidateTrack = identity.track,
            candidateArtist = identity.artist,
            candidateAlbum = identity.album,
            lyricsKind = LyricsKind.SYNCHRONIZED
        )
        return LyricsCandidateSelector.selectCandidatesWithProof(
            identity.lookup(),
            listOf(candidate)
        ).firstOrNull()?.let { selection ->
            ResolvedLyrics(selection.candidate, selection.proof)
        }
    }

    private suspend fun <T> withCache(block: () -> T): T = withContext(ioDispatcher) {
        cacheMutex.withLock {
            // A cancelled writer must not resurrect a cache cleared by a newer command.
            currentCoroutineContext().ensureActive()
            block()
        }
    }

    private fun publishRead(read: CacheRead, fallback: LyricsResult?, empty: LyricsAvailability) {
        val result = read.entry?.result ?: fallback
        publish(
            snapshot.copy(
                result = result,
                availability = when (read.entry?.selection) {
                    LyricsCacheSelection.MANUAL -> LyricsAvailability.CACHED_MANUAL
                    LyricsCacheSelection.AUTOMATIC -> LyricsAvailability.CACHED_AUTOMATIC
                    null -> if (result != null) LyricsAvailability.ONLINE_ONLY else empty
                },
                cache = read.summary
            )
        )
    }

    private fun publishEmbedded(result: LyricsResult, summary: LyricsCacheSnapshot) {
        publish(
            snapshot.copy(
                result = result,
                availability = LyricsAvailability.ONLINE_ONLY,
                cache = summary.copy(current = null)
            )
        )
    }

    private fun cancelAutomatic() {
        requestEpoch += 1L
        activeRequest?.cancel()
        activeRequest = null
    }

    private fun cancelManual(clearCandidates: Boolean = true) {
        manualEpoch += 1L
        manualCancellation?.cancel()
        manualCancellation = null
        manualJob?.cancel()
        manualJob = null
        if (clearCandidates) manualCandidates.clear()
    }

    private fun finishManual(epoch: Long) {
        if (epoch != manualEpoch) return
        manualCancellation?.cancel()
        manualCancellation = null
        manualJob = null
    }

    private fun isCurrent(epoch: Long, state: MediaRecordingState?): Boolean =
        !closed && epoch == requestEpoch && sameBinding(mediaState, state)

    private fun isCurrentManual(epoch: Long, state: MediaRecordingState): Boolean =
        !closed && epoch == manualEpoch && sameBinding(mediaState, state)

    private fun sameBinding(first: MediaRecordingState?, second: MediaRecordingState?): Boolean =
        first?.recordingGeneration == second?.recordingGeneration &&
            first?.queryRevision == second?.queryRevision

    private fun loadingAvailability(state: MediaRecordingState?): LyricsAvailability =
        if (state?.identity()?.isUsable == true) LyricsAvailability.LOADING else LyricsAvailability.EMPTY

    private fun publishManualState(state: LyricsManualSearchState) {
        publish(snapshot.copy(
            searchState = state,
            searchCandidates = manualCandidates.map { (token, result) ->
                LyricsManualSearchCandidate(token, result.candidateSnapshot())
            }
        ))
    }

    private fun publish(next: LyricsPlaybackStoreSnapshot) {
        if (closed || next == snapshot) return
        snapshot = next
        listener.onLyricsSnapshot(next)
    }

    private fun MediaRecordingState.identity() = LyricsPlaybackIdentity(
        metadata.track, metadata.artist, metadata.album, metadata.durationMs
    )

    private companion object {
        const val EMBEDDED_LYRICS_SOURCE = "media-session"
        const val EMBEDDED_LYRICS_SOURCE_ID = "embedded"
    }
}

internal interface LyricsPlaybackCache {
    fun read(identity: LyricsPlaybackIdentity, recordUse: Boolean): LyricsCache.Entry?
    fun writeAutomatic(identity: LyricsPlaybackIdentity, resolved: ResolvedLyrics): Boolean
    fun writeManual(identity: LyricsPlaybackIdentity, result: LyricsResult): Boolean
    fun clearManual(identity: LyricsPlaybackIdentity): Boolean
    fun clearCurrent(identity: LyricsPlaybackIdentity): Boolean
    fun snapshot(identity: LyricsPlaybackIdentity?, currentEntry: LyricsCache.Entry?): LyricsCacheSnapshot
}

internal enum class LyricsAvailability {
    EMPTY,
    LOADING,
    CACHED_AUTOMATIC,
    CACHED_MANUAL,
    ONLINE_ONLY,
    NO_MATCH,
    ERROR
}

internal data class LyricsPlaybackStoreSnapshot(
    val identity: LyricsPlaybackIdentity?,
    val recordingGeneration: Long,
    val queryRevision: Long,
    val result: LyricsResult?,
    val availability: LyricsAvailability,
    val cache: LyricsCacheSnapshot,
    val searchState: LyricsManualSearchState,
    val searchCandidates: List<LyricsManualSearchCandidate>
) {
    companion object {
        fun empty() = LyricsPlaybackStoreSnapshot(
            identity = null,
            recordingGeneration = 0L,
            queryRevision = 0L,
            result = null,
            availability = LyricsAvailability.EMPTY,
            cache = LyricsCacheSnapshot(LyricsCacheStats(0, 0, 0L, LyricsCachePolicy.MAX_BYTES), null),
            searchState = LyricsManualSearchState.IDLE,
            searchCandidates = emptyList()
        )
    }
}
