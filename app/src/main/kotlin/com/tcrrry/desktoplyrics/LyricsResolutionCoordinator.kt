package com.tcrrry.desktoplyrics

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class LyricsResolutionCoordinator internal constructor(
    private val lyricsResolver: LyricsResolver,
    private val coverResolver: LyricsCoverResolver,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    private val retryWait: suspend (Long) -> Unit = { delay(it) },
    private val blockingExecutor: ExecutorService = newBlockingExecutor()
) : Closeable {
    private val stateLock = Any()
    private val closed = AtomicBoolean(false)
    private var latestRequestGeneration = 0L
    private var activeLyricsCancellation: LyricsCancellationSignal? = null
    private val activeCoverCancellations = mutableSetOf<LyricsCancellationSignal>()

    suspend fun resolveLatest(query: LyricsLookup): LyricsResolutionOutcome {
        val requestGeneration = synchronized(stateLock) {
            if (closed.get()) return LyricsResolutionOutcome.Cancelled
            latestRequestGeneration += 1L
            activeLyricsCancellation?.cancel()
            activeLyricsCancellation = null
            latestRequestGeneration
        }
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val cancellation = LyricsCancellationSignal()
            val activated = synchronized(stateLock) {
                if (closed.get() || requestGeneration != latestRequestGeneration) {
                    false
                } else {
                    activeLyricsCancellation?.cancel()
                    activeLyricsCancellation = cancellation
                    true
                }
            }
            if (!activated) {
                cancellation.cancel()
                return LyricsResolutionOutcome.Cancelled
            }

            val outcome = try {
                executeBlocking(cancellation) {
                    lyricsResolver.resolveLyrics(query, cancellation)
                }
            } finally {
                synchronized(stateLock) {
                    if (activeLyricsCancellation === cancellation) {
                        activeLyricsCancellation = null
                    }
                }
            }
            if (!isLatest(requestGeneration)) return LyricsResolutionOutcome.Cancelled
            if (outcome !is LyricsResolutionOutcome.RetryableFailure || attempt >= 1) {
                return outcome
            }
            attempt += 1
            retryWait(retryDelayMs)
        }
    }

    suspend fun resolveCover(query: LyricsLookup): String {
        if (closed.get()) return ""
        val cancellation = LyricsCancellationSignal()
        val accepted = synchronized(stateLock) {
            if (closed.get()) false else activeCoverCancellations.add(cancellation)
        }
        if (!accepted) return ""
        return try {
            executeBlocking(cancellation) {
                coverResolver.resolveCover(query, cancellation)
            }
        } finally {
            synchronized(stateLock) {
                activeCoverCancellations.remove(cancellation)
            }
        }
    }

    fun cancelCurrent() {
        synchronized(stateLock) {
            latestRequestGeneration += 1L
            activeLyricsCancellation?.cancel()
            activeLyricsCancellation = null
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(stateLock) {
            latestRequestGeneration += 1L
            activeLyricsCancellation?.cancel()
            activeLyricsCancellation = null
            activeCoverCancellations.forEach(LyricsCancellationSignal::cancel)
            activeCoverCancellations.clear()
        }
        blockingExecutor.shutdownNow()
    }

    private fun isLatest(requestGeneration: Long): Boolean = synchronized(stateLock) {
        !closed.get() && requestGeneration == latestRequestGeneration
    }

    private suspend fun <T> executeBlocking(
        cancellation: LyricsCancellationSignal,
        block: () -> T
    ): T = suspendCancellableCoroutine { continuation ->
        val future = blockingExecutor.submit {
            try {
                val result = block()
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
        continuation.invokeOnCancellation {
            cancellation.cancel()
            future.cancel(true)
        }
    }

    private companion object {
        const val RETRY_DELAY_MS = 1_000L
        const val BLOCKING_THREAD_COUNT = 2

        fun newBlockingExecutor(): ExecutorService =
            Executors.newFixedThreadPool(BLOCKING_THREAD_COUNT) { runnable ->
                Thread(runnable, "lyrics-resolution").apply { isDaemon = true }
            }
    }
}
