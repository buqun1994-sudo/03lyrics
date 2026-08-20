package com.ninepointnine.desktoplyrics

import java.util.concurrent.BlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal class LyricsResolutionSession(
    private val exactAndFallbackSource: LyricsExactAndFallbackSource,
    private val catalogSources: List<LyricsCatalogSource>,
    private val bodySources: Map<String, LyricsBodySource>,
    private val executor: ExecutorService,
    private val clock: LyricsMonotonicClock,
    private val config: LyricsResolutionConfig,
    private val logger: LyricsRepositoryLogger
) {
    fun resolve(
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
        val catalogStates = catalogSources.associate { source ->
            source.sourceName to CatalogSourceState(
                source = source,
                requests = LyricsSearchPlanner.catalogRequests(query)
            )
        }
        val trace = LyricsResolutionTrace()
        var exactInFlight = true
        var fuzzyStarted = false
        var fuzzyInFlight = false
        var retryableFailure: LyricsFailureReason? = null
        val cancellationRegistration = cancellation.register {
            events.offer(ResolutionEvent.Cancelled)
        }

        submitSearch(
            operation = SearchOperation.LrcLibExact,
            deadlineNanos = catalogDeadlineNanos,
            cancellation = cancellation,
            events = events,
            futures = futures
        ) {
            listOfNotNull(
                exactAndFallbackSource.exact(query, catalogDeadlineNanos, cancellation)
            )
        }
        catalogStates.values.forEach { state ->
            startNextCatalogSearch(
                state = state,
                deadlineNanos = catalogDeadlineNanos,
                cancellation = cancellation,
                events = events,
                futures = futures
            )
        }

        try {
            while (true) {
                cancellation.throwIfCancelled()

                val ranked = LyricsCandidateSelector.selectCandidatesWithProof(query, candidates)
                ranked.firstOrNull { selection ->
                    classifyLyrics(selection.candidate.lyrics) == LyricsKind.SYNCHRONIZED
                }?.let { selection ->
                    return found(
                        query,
                        candidates,
                        selection,
                        selection.candidate,
                        startedAtNanos,
                        trace
                    )
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

                catalogStates.values.forEach { state ->
                    val hasPendingCandidate = ranked.any { selection ->
                        selection.candidate.source == state.source.sourceName &&
                            candidateIdentity(selection.candidate) !in startedCandidateIds
                    }
                    if (!state.searchInFlight &&
                        state.source.sourceName !in activeBodySources &&
                        !hasPendingCandidate
                    ) {
                        startNextCatalogSearch(
                            state = state,
                            deadlineNanos = totalDeadlineNanos,
                            cancellation = cancellation,
                            events = events,
                            futures = futures
                        )
                    }
                }

                val nowNanos = clock.nanoTime()
                val catalogExhausted = catalogStates.values.all(CatalogSourceState::isExhausted)
                val startFuzzyNow = nowNanos >= catalogDeadlineNanos ||
                    (!exactInFlight && catalogExhausted && activeBodySources.isEmpty())
                if (!fuzzyStarted && startFuzzyNow) {
                    fuzzyStarted = true
                    fuzzyInFlight = true
                    submitSearch(
                        operation = SearchOperation.LrcLibFuzzy,
                        deadlineNanos = totalDeadlineNanos,
                        cancellation = cancellation,
                        events = events,
                        futures = futures
                    ) {
                        exactAndFallbackSource.fallback(query, totalDeadlineNanos, cancellation)
                    }
                }

                val exactClosed = !exactInFlight || nowNanos >= catalogDeadlineNanos
                val fuzzyCompleted = fuzzyStarted && !fuzzyInFlight
                if (exactClosed &&
                    catalogExhausted &&
                    fuzzyCompleted &&
                    activeBodySources.isEmpty()
                ) {
                    return unresolved(
                        query = query,
                        candidates = candidates,
                        retryableFailure = retryableFailure,
                        startedAtNanos = startedAtNanos,
                        trace = trace
                    )
                }
                if (nowNanos >= totalDeadlineNanos) {
                    logger.info(
                        "Lyrics unresolved=deadline ${trace.summary()} " +
                            "evidence=${LyricsCandidateSelector.selectionSummary(query, candidates)} " +
                            "elapsedMs=${elapsedMs(startedAtNanos)}"
                    )
                    return LyricsResolutionOutcome.RetryableFailure(LyricsFailureReason.DEADLINE)
                }

                val nextBoundaryNanos = if (fuzzyStarted) {
                    totalDeadlineNanos
                } else {
                    minOf(catalogDeadlineNanos, totalDeadlineNanos)
                }
                val remainingNanos = nextBoundaryNanos - nowNanos
                if (remainingNanos <= 0L) continue
                val event = events.poll(remainingNanos, TimeUnit.NANOSECONDS) ?: continue
                when (event) {
                    is ResolutionEvent.SearchCompleted -> {
                        when (val operation = event.operation) {
                            SearchOperation.LrcLibExact -> exactInFlight = false
                            SearchOperation.LrcLibFuzzy -> fuzzyInFlight = false
                            is SearchOperation.Catalog -> {
                                catalogStates[operation.sourceName]?.searchInFlight = false
                            }
                        }
                        trace.searchCompleted(event.operation, event.candidates.size, event.elapsedMs)
                        candidates += event.candidates
                    }

                    is ResolutionEvent.SearchFailed -> {
                        when (val operation = event.operation) {
                            SearchOperation.LrcLibExact -> exactInFlight = false
                            SearchOperation.LrcLibFuzzy -> fuzzyInFlight = false
                            is SearchOperation.Catalog -> {
                                catalogStates[operation.sourceName]?.searchInFlight = false
                            }
                        }
                        trace.searchFailed(event.operation, event.elapsedMs)
                        if (event.retryable && retryableFailure == null) {
                            retryableFailure = event.reason
                        }
                    }

                    is ResolutionEvent.BodyCompleted -> {
                        activeBodySources -= event.sourceName
                        trace.bodyCompleted(event.sourceName, event.result != null, event.elapsedMs)
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
                                startedAtNanos,
                                trace
                            )
                        }
                    }

                    is ResolutionEvent.BodyFailed -> {
                        activeBodySources -= event.sourceName
                        trace.bodyFailed(event.sourceName, event.elapsedMs)
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
                val bodyStartedAtNanos = clock.nanoTime()
                try {
                    val result = source.loadLyrics(candidate, deadlineNanos, cancellation)
                    events.offer(
                        ResolutionEvent.BodyCompleted(
                            sourceName = source.sourceName,
                            selection = selection,
                            result = result,
                            elapsedMs = elapsedMs(bodyStartedAtNanos)
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
                            retryable = failure.retryable,
                            elapsedMs = elapsedMs(bodyStartedAtNanos)
                        )
                    )
                }
            }
        }
    }

    private fun startNextCatalogSearch(
        state: CatalogSourceState,
        deadlineNanos: Long,
        cancellation: LyricsCancellationSignal,
        events: BlockingQueue<ResolutionEvent>,
        futures: MutableList<Future<*>>
    ) {
        val request = state.takeNextRequest() ?: return
        submitSearch(
            operation = SearchOperation.Catalog(state.source.sourceName, request),
            deadlineNanos = deadlineNanos,
            cancellation = cancellation,
            events = events,
            futures = futures
        ) {
            state.source.search(request, deadlineNanos, cancellation)
        }
    }

    private fun submitSearch(
        operation: SearchOperation,
        deadlineNanos: Long,
        cancellation: LyricsCancellationSignal,
        events: BlockingQueue<ResolutionEvent>,
        futures: MutableList<Future<*>>,
        load: () -> List<LyricsResult>
    ) {
        futures += executor.submit {
            val searchStartedAtNanos = clock.nanoTime()
            try {
                cancellation.throwIfCancelled()
                val candidates = load()
                events.offer(
                    ResolutionEvent.SearchCompleted(
                        operation = operation,
                        candidates = candidates,
                        elapsedMs = elapsedMs(searchStartedAtNanos)
                    )
                )
            } catch (_: CancellationException) {
                Unit
            } catch (error: Throwable) {
                val failure = sourceFailure(error)
                logger.warning(
                    "Lyrics search=${operation.diagnosticLabel()} " +
                        "retryable=${failure.retryable} deadline=$deadlineNanos",
                    error
                )
                events.offer(
                    ResolutionEvent.SearchFailed(
                        operation = operation,
                        reason = failure.reason,
                        retryable = failure.retryable,
                        elapsedMs = elapsedMs(searchStartedAtNanos)
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
        startedAtNanos: Long,
        trace: LyricsResolutionTrace
    ): LyricsResolutionOutcome.Found {
        logger.info(
            "Lyrics selected=${result.source} " +
                "${trace.summary()} " +
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

    private fun unresolved(
        query: LyricsLookup,
        candidates: List<LyricsResult>,
        retryableFailure: LyricsFailureReason?,
        startedAtNanos: Long,
        trace: LyricsResolutionTrace
    ): LyricsResolutionOutcome {
        val outcome = retryableFailure?.let(LyricsResolutionOutcome::RetryableFailure)
            ?: LyricsResolutionOutcome.NoMatch
        logger.info(
            "Lyrics unresolved=${if (retryableFailure == null) "no-match" else retryableFailure} " +
                "${trace.summary()} " +
                "evidence=${LyricsCandidateSelector.selectionSummary(query, candidates)} " +
                "elapsedMs=${elapsedMs(startedAtNanos)}"
        )
        return outcome
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

    private data class CatalogSourceState(
        val source: LyricsCatalogSource,
        val requests: List<LyricsCatalogSearchRequest>,
        var nextRequestIndex: Int = 0,
        var searchInFlight: Boolean = false
    ) {
        fun takeNextRequest(): LyricsCatalogSearchRequest? {
            if (searchInFlight) return null
            val request = requests.getOrNull(nextRequestIndex) ?: return null
            nextRequestIndex += 1
            searchInFlight = true
            return request
        }

        fun isExhausted(): Boolean = nextRequestIndex >= requests.size && !searchInFlight
    }

    private sealed interface SearchOperation {
        val sourceName: String

        data object LrcLibExact : SearchOperation {
            override val sourceName: String = SOURCE_LRCLIB
        }

        data object LrcLibFuzzy : SearchOperation {
            override val sourceName: String = SOURCE_LRCLIB
        }

        data class Catalog(
            override val sourceName: String,
            val request: LyricsCatalogSearchRequest
        ) : SearchOperation

        fun diagnosticLabel(): String = when (this) {
            LrcLibExact -> "$sourceName/exact"
            LrcLibFuzzy -> "$sourceName/fuzzy"
            is Catalog -> "$sourceName/${request.kind}"
        }
    }

    private sealed interface ResolutionEvent {
        data class SearchCompleted(
            val operation: SearchOperation,
            val candidates: List<LyricsResult>,
            val elapsedMs: Long
        ) : ResolutionEvent

        data class SearchFailed(
            val operation: SearchOperation,
            val reason: LyricsFailureReason,
            val retryable: Boolean,
            val elapsedMs: Long
        ) : ResolutionEvent

        data class BodyCompleted(
            val sourceName: String,
            val selection: LyricsCandidateSelection,
            val result: LyricsResult?,
            val elapsedMs: Long
        ) : ResolutionEvent

        data class BodyFailed(
            val sourceName: String,
            val reason: LyricsFailureReason,
            val retryable: Boolean,
            val elapsedMs: Long
        ) : ResolutionEvent

        data object Cancelled : ResolutionEvent
    }

    private class LyricsResolutionTrace {
        private val searches = mutableListOf<String>()
        private val bodies = mutableListOf<String>()

        fun searchCompleted(operation: SearchOperation, candidateCount: Int, elapsedMs: Long) {
            searches += "${operation.diagnosticLabel()}:$candidateCount@${elapsedMs}ms"
        }

        fun searchFailed(operation: SearchOperation, elapsedMs: Long) {
            searches += "${operation.diagnosticLabel()}:failed@${elapsedMs}ms"
        }

        fun bodyCompleted(sourceName: String, found: Boolean, elapsedMs: Long) {
            bodies += "$sourceName:${if (found) "lyrics" else "empty"}@${elapsedMs}ms"
        }

        fun bodyFailed(sourceName: String, elapsedMs: Long) {
            bodies += "$sourceName:failed@${elapsedMs}ms"
        }

        fun summary(): String =
            "searches=${searches.joinToString(",").ifBlank { "none" }} " +
                "bodies=${bodies.joinToString(",").ifBlank { "none" }}"
    }


}
