package com.tcrrry.desktoplyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DirectLyricsRepositoryOrchestrationTest {
    private val query = LyricsLookup(
        track = "A Song",
        artist = "Artist",
        album = "Album",
        durationMs = 200_000L
    )

    @Test
    fun `returns a completed catalog body without waiting for slow primary sources`() {
        val exact = FakeExactSource(
            exactHandler = { _, _, cancellation -> blockUntilCancelled(cancellation) }
        )
        val qq = FakeCatalogSource(
            sourceName = SOURCE_QQ,
            searchHandler = { _, _, _ -> listOf(candidate(SOURCE_QQ, "qq")) },
            loadHandler = { sourceCandidate, _, _ -> sourceCandidate.synchronized() }
        )
        val netEase = FakeCatalogSource(
            sourceName = SOURCE_NETEASE,
            searchHandler = { _, _, cancellation -> blockUntilCancelled(cancellation) }
        )
        val repository = repository(exact, listOf(qq, netEase), catalogDeadlineMs = 1_000L)

        try {
            val startedAt = System.nanoTime()
            val outcome = repository.resolveLyrics(query, LyricsCancellationSignal())
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            val found = requireFound(outcome)

            assertEquals(SOURCE_QQ, found.result.source)
            assertTrue("Fast source waited ${elapsedMs}ms", elapsedMs < 700L)
        } finally {
            repository.close()
        }
    }

    @Test
    fun `uses two different source body lanes and returns the first completed lyrics`() {
        val bodyStarts = AtomicInteger(0)
        val activeBodies = AtomicInteger(0)
        val maximumActiveBodies = AtomicInteger(0)
        val secondSource = AtomicReference<String>()
        val loadHandler: (LyricsResult, Long, LyricsCancellationSignal) -> LyricsResult? =
            { sourceCandidate, _, cancellation ->
                val active = activeBodies.incrementAndGet()
                maximumActiveBodies.updateAndGet { current -> maxOf(current, active) }
                try {
                    if (bodyStarts.incrementAndGet() == 1) {
                        blockUntilCancelled(cancellation)
                    } else {
                        secondSource.set(sourceCandidate.source)
                        sourceCandidate.synchronized()
                    }
                } finally {
                    activeBodies.decrementAndGet()
                }
            }
        val exact = FakeExactSource(exactHandler = { _, _, _ -> null })
        val qq = FakeCatalogSource(
            sourceName = SOURCE_QQ,
            searchHandler = { _, _, _ -> listOf(candidate(SOURCE_QQ, "qq", 200_000L)) },
            loadHandler = loadHandler
        )
        val netEase = FakeCatalogSource(
            sourceName = SOURCE_NETEASE,
            searchHandler = { _, _, _ -> listOf(candidate(SOURCE_NETEASE, "netease", 200_100L)) },
            loadHandler = loadHandler
        )
        val repository = repository(exact, listOf(qq, netEase))

        try {
            val found = requireFound(
                repository.resolveLyrics(query, LyricsCancellationSignal())
            )

            assertEquals(2, bodyStarts.get())
            assertEquals(2, maximumActiveBodies.get())
            assertEquals(secondSource.get(), found.result.source)
        } finally {
            repository.close()
        }
    }

    @Test
    fun `starts fallback immediately when primary paths are exhausted`() {
        val fallbackCalls = AtomicInteger(0)
        val exact = FakeExactSource(
            exactHandler = { _, _, _ -> null },
            fallbackHandler = { _, _, _ ->
                fallbackCalls.incrementAndGet()
                listOf(candidate(SOURCE_LRCLIB, "fallback").synchronized())
            }
        )
        val sources = listOf(
            FakeCatalogSource(SOURCE_QQ, searchHandler = { _, _, _ -> emptyList() }),
            FakeCatalogSource(SOURCE_NETEASE, searchHandler = { _, _, _ -> emptyList() })
        )
        val repository = repository(exact, sources, catalogDeadlineMs = 1_000L)

        try {
            val startedAt = System.nanoTime()
            val found = requireFound(
                repository.resolveLyrics(query, LyricsCancellationSignal())
            )
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertEquals(SOURCE_LRCLIB, found.result.source)
            assertEquals(1, fallbackCalls.get())
            assertTrue("Fallback waited for the full catalog window", elapsedMs < 700L)
        } finally {
            repository.close()
        }
    }

    @Test
    fun `returns a retryable deadline when every source remains blocked`() {
        val exact = FakeExactSource(
            exactHandler = { _, _, cancellation -> blockUntilCancelled(cancellation) },
            fallbackHandler = { _, _, cancellation -> blockUntilCancelled(cancellation) }
        )
        val sources = listOf(
            FakeCatalogSource(
                SOURCE_QQ,
                searchHandler = { _, _, cancellation -> blockUntilCancelled(cancellation) }
            ),
            FakeCatalogSource(
                SOURCE_NETEASE,
                searchHandler = { _, _, cancellation -> blockUntilCancelled(cancellation) }
            )
        )
        val repository = repository(
            exact,
            sources,
            catalogDeadlineMs = 40L,
            totalDeadlineMs = 90L
        )

        try {
            val outcome = repository.resolveLyrics(query, LyricsCancellationSignal())

            assertEquals(
                LyricsResolutionOutcome.RetryableFailure(LyricsFailureReason.DEADLINE),
                outcome
            )
        } finally {
            repository.close()
        }
    }

    private fun repository(
        exact: LyricsExactAndFallbackSource,
        sources: List<LyricsCatalogSource>,
        catalogDeadlineMs: Long = 500L,
        totalDeadlineMs: Long = 1_000L
    ): DirectLyricsRepository = DirectLyricsRepository(
        exactAndFallbackSource = exact,
        catalogSources = sources,
        executor = Executors.newFixedThreadPool(6),
        config = LyricsResolutionConfig(
            catalogDeadlineMs = catalogDeadlineMs,
            totalDeadlineMs = totalDeadlineMs
        ),
        logger = NoOpLyricsRepositoryLogger
    )

    private fun requireFound(outcome: LyricsResolutionOutcome): ResolvedLyrics =
        requireNotNull((outcome as? LyricsResolutionOutcome.Found)?.resolved) {
            "Expected lyrics, got $outcome"
        }

    private fun candidate(source: String, sourceId: String, durationMs: Long = 200_000L) =
        LyricsResult(
            durationMs = durationMs,
            source = source,
            sourceId = sourceId,
            candidateTrack = query.track,
            candidateArtist = query.artist,
            candidateAlbum = query.album
        )

    private fun LyricsResult.synchronized(): LyricsResult = copy(
        lyrics = "[00:01.00]Ready",
        lyricsKind = LyricsKind.SYNCHRONIZED
    )

    private fun blockUntilCancelled(cancellation: LyricsCancellationSignal): Nothing {
        val cancelled = CountDownLatch(1)
        val registration = cancellation.register(cancelled::countDown)
        try {
            cancelled.await()
            throw CancellationException("cancelled")
        } finally {
            registration.close()
        }
    }

    private class FakeExactSource(
        override val sourceName: String = SOURCE_LRCLIB,
        private val exactHandler: (
            LyricsLookup,
            Long,
            LyricsCancellationSignal
        ) -> LyricsResult? = { _, _, _ -> null },
        private val fallbackHandler: (
            LyricsLookup,
            Long,
            LyricsCancellationSignal
        ) -> List<LyricsResult> = { _, _, _ -> emptyList() }
    ) : LyricsExactAndFallbackSource {
        override fun exact(
            query: LyricsLookup,
            deadlineNanos: Long,
            cancellation: LyricsCancellationSignal
        ): LyricsResult? = exactHandler(query, deadlineNanos, cancellation)

        override fun fallback(
            query: LyricsLookup,
            deadlineNanos: Long,
            cancellation: LyricsCancellationSignal
        ): List<LyricsResult> = fallbackHandler(query, deadlineNanos, cancellation)

        override fun loadLyrics(
            candidate: LyricsResult,
            deadlineNanos: Long,
            cancellation: LyricsCancellationSignal
        ): LyricsResult? = candidate.takeIf {
            classifyLyrics(it.lyrics) == LyricsKind.SYNCHRONIZED
        }
    }

    private class FakeCatalogSource(
        override val sourceName: String,
        private val searchHandler: (
            LyricsLookup,
            Long,
            LyricsCancellationSignal
        ) -> List<LyricsResult>,
        private val loadHandler: (
            LyricsResult,
            Long,
            LyricsCancellationSignal
        ) -> LyricsResult? = { _, _, _ -> null }
    ) : LyricsCatalogSource {
        override fun search(
            query: LyricsLookup,
            deadlineNanos: Long,
            cancellation: LyricsCancellationSignal
        ): List<LyricsResult> = searchHandler(query, deadlineNanos, cancellation)

        override fun loadLyrics(
            candidate: LyricsResult,
            deadlineNanos: Long,
            cancellation: LyricsCancellationSignal
        ): LyricsResult? = loadHandler(candidate, deadlineNanos, cancellation)
    }

    private object NoOpLyricsRepositoryLogger : LyricsRepositoryLogger {
        override fun info(message: String) = Unit
        override fun warning(message: String, error: Throwable) = Unit
    }
}
