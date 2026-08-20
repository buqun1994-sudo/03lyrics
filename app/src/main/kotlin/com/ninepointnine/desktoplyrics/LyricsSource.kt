package com.ninepointnine.desktoplyrics

import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal interface LyricsBodySource {
    val sourceName: String

    fun loadLyrics(
        candidate: LyricsResult,
        deadlineNanos: Long,
        cancellation: LyricsCancellationSignal
    ): LyricsResult?
}

internal interface LyricsCatalogSource : LyricsBodySource {
    fun search(
        request: LyricsCatalogSearchRequest,
        deadlineNanos: Long,
        cancellation: LyricsCancellationSignal
    ): List<LyricsResult>
}

internal interface LyricsExactAndFallbackSource : LyricsBodySource {
    fun exact(
        query: LyricsLookup,
        deadlineNanos: Long,
        cancellation: LyricsCancellationSignal
    ): LyricsResult?

    fun fallback(
        query: LyricsLookup,
        deadlineNanos: Long,
        cancellation: LyricsCancellationSignal
    ): List<LyricsResult>
}

internal fun interface LyricsResolver {
    fun resolveLyrics(
        query: LyricsLookup,
        cancellation: LyricsCancellationSignal
    ): LyricsResolutionOutcome
}

internal fun interface LyricsCoverResolver {
    fun resolveCover(
        query: LyricsLookup,
        cancellation: LyricsCancellationSignal
    ): String
}

internal class LyricsCancellationSignal {
    private val cancelled = AtomicBoolean(false)
    private val nextRegistrationId = AtomicLong(0L)
    private val registrationsLock = Any()
    private val registrations = linkedMapOf<Long, () -> Unit>()

    val isCancelled: Boolean get() = cancelled.get()

    fun throwIfCancelled() {
        if (isCancelled || Thread.currentThread().isInterrupted) {
            throw CancellationException("Lyrics request cancelled")
        }
    }

    fun register(onCancel: () -> Unit): Closeable {
        val registrationId = nextRegistrationId.incrementAndGet()
        val invokeImmediately = synchronized(registrationsLock) {
            if (isCancelled) {
                true
            } else {
                registrations[registrationId] = onCancel
                false
            }
        }
        if (invokeImmediately) onCancel()
        return Closeable {
            synchronized(registrationsLock) {
                registrations.remove(registrationId)
            }
        }
    }

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        val callbacks = synchronized(registrationsLock) {
            registrations.values.toList().also { registrations.clear() }
        }
        callbacks.forEach { callback -> runCatching(callback) }
    }
}

internal open class LyricsSourceException(
    val reason: LyricsFailureReason,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

internal fun interface LyricsMonotonicClock {
    fun nanoTime(): Long
}

internal object SystemLyricsMonotonicClock : LyricsMonotonicClock {
    override fun nanoTime(): Long = System.nanoTime()
}

internal data class LyricsResolutionConfig(
    val catalogDeadlineMs: Long = 1_800L,
    val totalDeadlineMs: Long = 3_800L,
    val maximumBodyLanes: Int = 2
) {
    init {
        require(catalogDeadlineMs > 0L)
        require(totalDeadlineMs >= catalogDeadlineMs)
        require(maximumBodyLanes in 1..2)
    }

    fun deadlineAfter(startedAtNanos: Long, durationMs: Long): Long =
        startedAtNanos + TimeUnit.MILLISECONDS.toNanos(durationMs)
}
