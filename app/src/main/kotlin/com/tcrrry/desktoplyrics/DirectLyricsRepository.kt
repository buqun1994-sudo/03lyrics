package com.tcrrry.desktoplyrics

import android.util.Log
import java.io.Closeable
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates public lyrics sources without routing requests through an app-owned server.
 * Source parsing, recording identity and admission policy live behind separate boundaries.
 */
internal class DirectLyricsRepository internal constructor(
    private val exactAndFallbackSource: LyricsExactAndFallbackSource = LrcLibLyricsSource(),
    private val catalogSources: List<LyricsCatalogSource> = listOf(
        QqLyricsSource(),
        NetEaseLyricsSource()
    ),
    private val executor: ExecutorService = newNetworkExecutor(),
    private val clock: LyricsMonotonicClock = SystemLyricsMonotonicClock,
    private val config: LyricsResolutionConfig = LyricsResolutionConfig(),
    private val logger: LyricsRepositoryLogger = AndroidLyricsRepositoryLogger
) : LyricsResolver, LyricsCoverResolver, Closeable {
    private val closed = AtomicBoolean(false)
    private val activeCancellations = ConcurrentHashMap.newKeySet<LyricsCancellationSignal>()
    private val bodySources: Map<String, LyricsBodySource> =
        (listOf(exactAndFallbackSource) + catalogSources).associateBy(LyricsBodySource::sourceName)

    init {
        require(bodySources.size == catalogSources.size + 1) {
            "Lyrics sources must have unique names"
        }
    }

    fun resolveLyrics(
        track: String,
        artist: String,
        album: String = "",
        durationMs: Long = 0L
    ): LyricsResolutionOutcome {
        val cancellation = LyricsCancellationSignal()
        return resolveLyrics(LyricsLookup(track, artist, album, durationMs), cancellation)
    }

    override fun resolveLyrics(
        query: LyricsLookup,
        cancellation: LyricsCancellationSignal
    ): LyricsResolutionOutcome {
        if (!LyricsCandidateSelector.canConfirm(query)) {
            return LyricsResolutionOutcome.InvalidMetadata
        }
        if (closed.get()) return LyricsResolutionOutcome.Cancelled
        activeCancellations += cancellation
        val startedAtNanos = clock.nanoTime()
        return try {
            resolveFromSources(query, cancellation, startedAtNanos)
        } catch (_: CancellationException) {
            LyricsResolutionOutcome.Cancelled
        } finally {
            cancellation.cancel()
            activeCancellations -= cancellation
        }
    }

    fun resolveCover(track: String, artist: String): String {
        val cancellation = LyricsCancellationSignal()
        return resolveCover(LyricsLookup(track, artist), cancellation)
    }

    override fun resolveCover(
        query: LyricsLookup,
        cancellation: LyricsCancellationSignal
    ): String {
        if (!LyricsCandidateSelector.canFindCover(query) || closed.get()) return ""
        activeCancellations += cancellation
        val deadlineNanos = clock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COVER_DEADLINE_MS)
        val events = LinkedBlockingQueue<CoverEvent>()
        val futures = catalogSources.map { source ->
            executor.submit {
                try {
                    val candidates = source.search(query, deadlineNanos, cancellation)
                    events.offer(CoverEvent.Completed(candidates))
                } catch (_: CancellationException) {
                    Unit
                } catch (error: Throwable) {
                    logger.warning("Cover catalog source=${source.sourceName} failed", error)
                    events.offer(CoverEvent.Completed(emptyList()))
                }
            }
        }
        val candidates = mutableListOf<LyricsResult>()
        try {
            var remainingSources = futures.size
            while (remainingSources > 0) {
                cancellation.throwIfCancelled()
                val remainingNanos = deadlineNanos - clock.nanoTime()
                if (remainingNanos <= 0L) break
                val event = events.poll(remainingNanos, TimeUnit.NANOSECONDS) ?: break
                remainingSources -= 1
                candidates += event.candidates
            }
            return LyricsCandidateSelector.selectCoverCandidate(query, candidates)?.cover.orEmpty()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            cancellation.cancel()
            return ""
        } catch (_: CancellationException) {
            return ""
        } finally {
            futures.forEach { it.cancel(true) }
            cancellation.cancel()
            activeCancellations -= cancellation
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeCancellations.forEach(LyricsCancellationSignal::cancel)
        executor.shutdownNow()
    }

    private fun resolveFromSources(
        query: LyricsLookup,
        cancellation: LyricsCancellationSignal,
        startedAtNanos: Long
    ): LyricsResolutionOutcome {
        val totalDeadlineNanos = config.deadlineAfter(startedAtNanos, config.totalDeadlineMs)
        val catalogDeadlineNanos = minOf(
            totalDeadlineNanos,
            config.deadlineAfter(startedAtNanos, config.catalogDeadlineMs)
        )
        val events = LinkedBlockingQueue<ResolutionEvent>()
        val futures = mutableListOf<Future<*>>()
        val candidates = mutableListOf<LyricsResult>()
        val activeBodySources = mutableSetOf<String>()
        val startedCandidateIds = mutableSetOf<String>()
        var primaryOutstanding = catalogSources.size + 1
        var fallbackOutstanding = 0
        var fallbackStarted = false
        var retryableFailure: LyricsFailureReason? = null
        val cancellationRegistration = cancellation.register {
            events.offer(ResolutionEvent.Cancelled)
        }

        submitCatalog(
            sourceName = exactAndFallbackSource.sourceName,
            phase = CatalogPhase.PRIMARY,
            deadlineNanos = catalogDeadlineNanos,
            cancellation = cancellation,
            events = events,
            futures = futures
        ) {
            listOfNotNull(
                exactAndFallbackSource.exact(query, catalogDeadlineNanos, cancellation)
            )
        }
        catalogSources.forEach { source ->
            submitCatalog(
                sourceName = source.sourceName,
                phase = CatalogPhase.PRIMARY,
                deadlineNanos = catalogDeadlineNanos,
                cancellation = cancellation,
                events = events,
                futures = futures
            ) {
                source.search(query, catalogDeadlineNanos, cancellation)
            }
        }

        try {
            while (true) {
                cancellation.throwIfCancelled()

                val ranked = LyricsCandidateSelector.selectCandidatesWithProof(query, candidates)
                ranked.firstOrNull { selection ->
                    classifyLyrics(selection.candidate.lyrics) == LyricsKind.SYNCHRONIZED
                }?.let { selection ->
                    return found(query, candidates, selection, selection.candidate, startedAtNanos)
                }
                scheduleBodyLoads(
                    ranked = ranked,
                    activeBodySources = activeBodySources,
                    startedCandidateIds = startedCandidateIds,
                    deadlineNanos = totalDeadlineNanos,
                    cancellation = cancellation,
                    events = events,
                    futures = futures
                )

                val nowNanos = clock.nanoTime()
                val primaryPhaseExpired =
                    primaryOutstanding > 0 && nowNanos >= catalogDeadlineNanos
                val primaryPhaseExhausted =
                    primaryOutstanding == 0 && activeBodySources.isEmpty()
                if (!fallbackStarted && (primaryPhaseExpired || primaryPhaseExhausted)) {
                    fallbackStarted = true
                    fallbackOutstanding = catalogSources.size + 1
                    submitCatalog(
                        sourceName = exactAndFallbackSource.sourceName,
                        phase = CatalogPhase.FALLBACK,
                        deadlineNanos = totalDeadlineNanos,
                        cancellation = cancellation,
                        events = events,
                        futures = futures
                    ) {
                        exactAndFallbackSource.fallback(query, totalDeadlineNanos, cancellation)
                    }
                    catalogSources.forEach { source ->
                        submitCatalog(
                            sourceName = source.sourceName,
                            phase = CatalogPhase.FALLBACK,
                            deadlineNanos = totalDeadlineNanos,
                            cancellation = cancellation,
                            events = events,
                            futures = futures
                        ) {
                            source.fallback(query, totalDeadlineNanos, cancellation)
                        }
                    }
                }

                val primaryClosed = primaryOutstanding == 0 || nowNanos >= catalogDeadlineNanos
                val fallbackCompleted = fallbackStarted && fallbackOutstanding == 0
                if (primaryClosed && fallbackCompleted && activeBodySources.isEmpty()) {
                    return retryableFailure?.let(LyricsResolutionOutcome::RetryableFailure)
                        ?: LyricsResolutionOutcome.NoMatch
                }
                if (nowNanos >= totalDeadlineNanos) {
                    return LyricsResolutionOutcome.RetryableFailure(LyricsFailureReason.DEADLINE)
                }

                val nextBoundaryNanos = if (fallbackStarted) {
                    totalDeadlineNanos
                } else {
                    minOf(catalogDeadlineNanos, totalDeadlineNanos)
                }
                val remainingNanos = nextBoundaryNanos - nowNanos
                if (remainingNanos <= 0L) continue
                val event = events.poll(remainingNanos, TimeUnit.NANOSECONDS) ?: continue
                when (event) {
                    is ResolutionEvent.CatalogCompleted -> {
                        if (event.phase == CatalogPhase.PRIMARY) {
                            primaryOutstanding = (primaryOutstanding - 1).coerceAtLeast(0)
                        } else {
                            fallbackOutstanding = (fallbackOutstanding - 1).coerceAtLeast(0)
                        }
                        candidates += event.candidates
                    }

                    is ResolutionEvent.CatalogFailed -> {
                        if (event.phase == CatalogPhase.PRIMARY) {
                            primaryOutstanding = (primaryOutstanding - 1).coerceAtLeast(0)
                        } else {
                            fallbackOutstanding = (fallbackOutstanding - 1).coerceAtLeast(0)
                        }
                        if (event.retryable && retryableFailure == null) {
                            retryableFailure = event.reason
                        }
                    }

                    is ResolutionEvent.BodyCompleted -> {
                        activeBodySources -= event.sourceName
                        val loaded = event.result
                        if (loaded != null &&
                            classifyLyrics(loaded.lyrics) == LyricsKind.SYNCHRONIZED &&
                            LyricsCandidateSelector.isProofValid(
                                query,
                                loaded,
                                event.selection.proof
                            )
                        ) {
                            return found(
                                query,
                                candidates,
                                event.selection,
                                loaded,
                                startedAtNanos
                            )
                        }
                    }

                    is ResolutionEvent.BodyFailed -> {
                        activeBodySources -= event.sourceName
                        if (event.retryable && retryableFailure == null) {
                            retryableFailure = event.reason
                        }
                    }

                    ResolutionEvent.Cancelled -> return LyricsResolutionOutcome.Cancelled
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            cancellation.cancel()
            return LyricsResolutionOutcome.Cancelled
        } finally {
            cancellationRegistration.close()
            futures.forEach { it.cancel(true) }
        }
    }

    private fun scheduleBodyLoads(
        ranked: List<LyricsCandidateSelection>,
        activeBodySources: MutableSet<String>,
        startedCandidateIds: MutableSet<String>,
        deadlineNanos: Long,
        cancellation: LyricsCancellationSignal,
        events: BlockingQueue<ResolutionEvent>,
        futures: MutableList<Future<*>>
    ) {
        if (activeBodySources.size >= config.maximumBodyLanes) return
        for (selection in ranked) {
            if (activeBodySources.size >= config.maximumBodyLanes) break
            val candidate = selection.candidate
            if (classifyLyrics(candidate.lyrics) == LyricsKind.SYNCHRONIZED) continue
            val candidateId = candidateIdentity(candidate)
            val source = bodySources[candidate.source] ?: continue
            if (candidateId in startedCandidateIds || source.sourceName in activeBodySources) continue
            startedCandidateIds += candidateId
            activeBodySources += source.sourceName
            futures += executor.submit {
                try {
                    val result = source.loadLyrics(candidate, deadlineNanos, cancellation)
                    events.offer(
                        ResolutionEvent.BodyCompleted(
                            sourceName = source.sourceName,
                            selection = selection,
                            result = result
                        )
                    )
                } catch (_: CancellationException) {
                    Unit
                } catch (error: Throwable) {
                    val failure = sourceFailure(error)
                    logger.warning(
                        "Lyrics body source=${source.sourceName} retryable=${failure.retryable}",
                        error
                    )
                    events.offer(
                        ResolutionEvent.BodyFailed(
                            sourceName = source.sourceName,
                            reason = failure.reason,
                            retryable = failure.retryable
                        )
                    )
                }
            }
        }
    }

    private fun submitCatalog(
        sourceName: String,
        phase: CatalogPhase,
        deadlineNanos: Long,
        cancellation: LyricsCancellationSignal,
        events: BlockingQueue<ResolutionEvent>,
        futures: MutableList<Future<*>>,
        load: () -> List<LyricsResult>
    ) {
        futures += executor.submit {
            try {
                cancellation.throwIfCancelled()
                val candidates = load()
                events.offer(ResolutionEvent.CatalogCompleted(sourceName, phase, candidates))
            } catch (_: CancellationException) {
                Unit
            } catch (error: Throwable) {
                val failure = sourceFailure(error)
                logger.warning(
                    "Lyrics catalog phase=$phase source=$sourceName " +
                        "retryable=${failure.retryable} deadline=$deadlineNanos",
                    error
                )
                events.offer(
                    ResolutionEvent.CatalogFailed(
                        sourceName = sourceName,
                        phase = phase,
                        reason = failure.reason,
                        retryable = failure.retryable
                    )
                )
            }
        }
    }

    private fun found(
        query: LyricsLookup,
        candidates: List<LyricsResult>,
        selection: LyricsCandidateSelection,
        result: LyricsResult,
        startedAtNanos: Long
    ): LyricsResolutionOutcome.Found {
        logger.info(
            "Lyrics selected=${result.source} " +
                "evidence=${LyricsCandidateSelector.selectionSummary(query, candidates, result)} " +
                "elapsedMs=${elapsedMs(startedAtNanos)}"
        )
        return LyricsResolutionOutcome.Found(
            ResolvedLyrics(
                result = result.copy(lyricsKind = LyricsKind.SYNCHRONIZED),
                proof = selection.proof
            )
        )
    }

    private fun sourceFailure(error: Throwable): SourceFailure {
        return if (error is LyricsSourceException) {
            SourceFailure(error.reason, error.retryable)
        } else {
            SourceFailure(LyricsFailureReason.NETWORK, retryable = false)
        }
    }

    private fun candidateIdentity(candidate: LyricsResult): String =
        if (candidate.source.isNotBlank() && candidate.sourceId.isNotBlank()) {
            "${candidate.source}\u0000${candidate.sourceId}"
        } else {
            listOf(
                candidate.candidateTrack,
                candidate.candidateArtist,
                candidate.candidateAlbum,
                candidate.durationMs.toString()
            ).joinToString("\u0000")
        }

    private fun elapsedMs(startedAtNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - startedAtNanos)

    private data class SourceFailure(
        val reason: LyricsFailureReason,
        val retryable: Boolean
    )

    private enum class CatalogPhase {
        PRIMARY,
        FALLBACK
    }

    private sealed interface ResolutionEvent {
        data class CatalogCompleted(
            val sourceName: String,
            val phase: CatalogPhase,
            val candidates: List<LyricsResult>
        ) : ResolutionEvent

        data class CatalogFailed(
            val sourceName: String,
            val phase: CatalogPhase,
            val reason: LyricsFailureReason,
            val retryable: Boolean
        ) : ResolutionEvent

        data class BodyCompleted(
            val sourceName: String,
            val selection: LyricsCandidateSelection,
            val result: LyricsResult?
        ) : ResolutionEvent

        data class BodyFailed(
            val sourceName: String,
            val reason: LyricsFailureReason,
            val retryable: Boolean
        ) : ResolutionEvent

        data object Cancelled : ResolutionEvent
    }

    private sealed interface CoverEvent {
        val candidates: List<LyricsResult>

        data class Completed(
            override val candidates: List<LyricsResult>
        ) : CoverEvent
    }

    private companion object {
        const val COVER_DEADLINE_MS = 3_000L

        fun newNetworkExecutor(): ExecutorService =
            Executors.newFixedThreadPool(NETWORK_THREAD_COUNT) { runnable ->
                Thread(runnable, "direct-lyrics").apply { isDaemon = true }
            }

        const val NETWORK_THREAD_COUNT = 6
    }
}

internal interface LyricsRepositoryLogger {
    fun info(message: String)
    fun warning(message: String, error: Throwable)
}

internal object AndroidLyricsRepositoryLogger : LyricsRepositoryLogger {
    override fun info(message: String) {
        Log.i(LOG_TAG, message)
    }

    override fun warning(message: String, error: Throwable) {
        Log.w(LOG_TAG, message, error)
    }

    private const val LOG_TAG = "DesktopLyrics"
}
