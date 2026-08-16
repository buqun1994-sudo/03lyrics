package com.tcrrry.desktoplyrics

import android.util.Log
import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
    private val resolutionSession = LyricsResolutionSession(
        exactAndFallbackSource = exactAndFallbackSource,
        catalogSources = catalogSources,
        bodySources = bodySources,
        executor = executor,
        clock = clock,
        config = config,
        logger = logger
    )

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
            resolutionSession.resolve(query, cancellation, startedAtNanos)
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
        val request = LyricsSearchPlanner.catalogRequests(query).firstOrNull() ?: return ""
        activeCancellations += cancellation
        val deadlineNanos = clock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COVER_DEADLINE_MS)
        val events = LinkedBlockingQueue<CoverEvent>()
        val futures = catalogSources.map { source ->
            executor.submit {
                try {
                    val candidates = source.search(request, deadlineNanos, cancellation)
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
