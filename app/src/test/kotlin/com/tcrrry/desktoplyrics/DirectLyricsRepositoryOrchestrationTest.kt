package com.tcrrry.desktoplyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
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
    fun `does not start fuzzy fallback while exact lookup is still inside its window`() {
        val releaseExact = CountDownLatch(1)
        val exactStarted = CountDownLatch(1)
        val catalogSearchesCompleted = CountDownLatch(6)
        val fuzzyStarted = CountDownLatch(1)
        val exact = FakeExactSource(
            exactHandler = { _, _, _ ->
                exactStarted.countDown()
                releaseExact.await()
                candidate(SOURCE_LRCLIB, "exact").synchronized()
            },
            fallbackHandler = { _, _, _ ->
                fuzzyStarted.countDown()
                emptyList()
            }
        )
        val emptySearch: (
            LyricsCatalogSearchRequest,
            Long,
            LyricsCancellationSignal
        ) -> List<LyricsResult> = { _, _, _ ->
            catalogSearchesCompleted.countDown()
            emptyList()
        }
        val sources = listOf(
            FakeCatalogSource(
                SOURCE_QQ,
                searchHandler = emptySearch,
                expandedSearchHandler = emptySearch
            ),
            FakeCatalogSource(
                SOURCE_NETEASE,
                searchHandler = emptySearch,
                expandedSearchHandler = emptySearch
            )
        )
        val repository = repository(
            exact,
            sources,
            catalogDeadlineMs = 1_000L,
            totalDeadlineMs = 1_500L
        )
        val caller = Executors.newSingleThreadExecutor()

        try {
            val outcome = caller.submit<LyricsResolutionOutcome> {
                repository.resolveLyrics(query, LyricsCancellationSignal())
            }
            assertTrue(exactStarted.await(500L, TimeUnit.MILLISECONDS))
            assertTrue(catalogSearchesCompleted.await(500L, TimeUnit.MILLISECONDS))
            assertFalse(fuzzyStarted.await(150L, TimeUnit.MILLISECONDS))

            releaseExact.countDown()
            val found = requireFound(outcome.get(500L, TimeUnit.MILLISECONDS))

            assertEquals("exact", found.result.sourceId)
            assertEquals(1L, fuzzyStarted.count)
        } finally {
            releaseExact.countDown()
            caller.shutdownNow()
            repository.close()
        }
    }

    @Test
    fun `loads a catalog album fallback after primary paths are exhausted`() {
        val fallbackCalls = AtomicInteger(0)
        val exact = FakeExactSource(
            exactHandler = { _, _, _ -> null },
            fallbackHandler = { _, _, _ -> emptyList() }
        )
        val qq = FakeCatalogSource(
            sourceName = SOURCE_QQ,
            searchHandler = { _, _, _ -> emptyList() },
            expandedSearchHandler = { _, _, _ ->
                fallbackCalls.incrementAndGet()
                listOf(candidate(SOURCE_QQ, "album-fallback"))
            },
            loadHandler = { sourceCandidate, _, _ -> sourceCandidate.synchronized() }
        )
        val netEase = FakeCatalogSource(
            sourceName = SOURCE_NETEASE,
            searchHandler = { _, _, _ -> emptyList() }
        )
        val repository = repository(exact, listOf(qq, netEase), catalogDeadlineMs = 1_000L)

        try {
            val found = requireFound(
                repository.resolveLyrics(query, LyricsCancellationSignal())
            )

            assertEquals(SOURCE_QQ, found.result.source)
            assertEquals(1, fallbackCalls.get())
        } finally {
            repository.close()
        }
    }

    @Test
    fun `expands Tank lookup on the completed source without waiting for slow peers`() {
        val tankQuery = LyricsLookup(
            track = "千年泪",
            artist = "Tank Lu",
            album = "Fighting! 生存之道",
            durationMs = 260_000L
        )
        val requests = CopyOnWriteArrayList<LyricsCatalogSearchKind>()
        val exact = FakeExactSource(
            exactHandler = { _, _, cancellation -> blockUntilCancelled(cancellation) }
        )
        val qq = FakeCatalogSource(
            sourceName = SOURCE_QQ,
            searchHandler = { request, _, _ ->
                requests += request.kind
                listOf(
                    LyricsResult(
                        durationMs = tankQuery.durationMs,
                        source = SOURCE_QQ,
                        sourceId = "wrong-primary",
                        candidateTrack = tankQuery.track,
                        candidateArtist = "Different Singer",
                        candidateAlbum = "Other Album"
                    )
                )
            },
            expandedSearchHandler = { request, _, _ ->
                requests += request.kind
                if (request.kind == LyricsCatalogSearchKind.TITLE_ALBUM) {
                    listOf(
                        LyricsResult(
                            durationMs = tankQuery.durationMs,
                            source = SOURCE_QQ,
                            sourceId = "003uqv3H0ZIitc",
                            candidateTrack = tankQuery.track,
                            candidateArtist = "Tank",
                            candidateAlbum = "Fighting！生存之道"
                        )
                    )
                } else {
                    emptyList()
                }
            },
            loadHandler = { sourceCandidate, _, _ -> sourceCandidate.synchronized() }
        )
        val netEase = FakeCatalogSource(
            sourceName = SOURCE_NETEASE,
            searchHandler = { _, _, cancellation -> blockUntilCancelled(cancellation) }
        )
        val repository = repository(
            exact,
            listOf(qq, netEase),
            catalogDeadlineMs = 1_000L,
            totalDeadlineMs = 1_500L
        )

        try {
            val startedAt = System.nanoTime()
            val found = requireFound(
                repository.resolveLyrics(tankQuery, LyricsCancellationSignal())
            )
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertEquals("003uqv3H0ZIitc", found.result.sourceId)
            assertEquals(
                listOf(
                    LyricsCatalogSearchKind.TITLE_ARTIST,
                    LyricsCatalogSearchKind.TITLE_ALBUM
                ),
                requests
            )
            assertTrue("Album expansion waited ${elapsedMs}ms for slow peers", elapsedMs < 700L)
        } finally {
            repository.close()
        }
    }

    @Test
    fun `Twinkle stops its source plan after the primary localized candidate succeeds`() {
        val twinkleQuery = LyricsLookup(
            track = "Twinkle",
            artist = "少女时代-太蒂徐",
            album = "'Twinkle' Mini Album",
            durationMs = 206_796L
        )
        val requests = CopyOnWriteArrayList<LyricsCatalogSearchKind>()
        val exact = FakeExactSource(
            exactHandler = { _, _, cancellation -> blockUntilCancelled(cancellation) }
        )
        val qq = FakeCatalogSource(
            sourceName = SOURCE_QQ,
            searchHandler = { request, _, _ ->
                requests += request.kind
                listOf(
                    LyricsResult(
                        durationMs = 208_000L,
                        source = SOURCE_QQ,
                        sourceId = "002uAK7V2AiPDn",
                        candidateTrack = "Twinkle",
                        candidateArtist = "少女时代-TaeTiSeo",
                        candidateAlbum = "'Twinkle' Mini Album"
                    )
                )
            },
            loadHandler = { sourceCandidate, _, _ -> sourceCandidate.synchronized() }
        )
        val netEase = FakeCatalogSource(
            sourceName = SOURCE_NETEASE,
            searchHandler = { _, _, cancellation -> blockUntilCancelled(cancellation) }
        )
        val repository = repository(exact, listOf(qq, netEase))

        try {
            val found = requireFound(
                repository.resolveLyrics(twinkleQuery, LyricsCancellationSignal())
            )

            assertEquals("002uAK7V2AiPDn", found.result.sourceId)
            assertEquals(listOf(LyricsCatalogSearchKind.TITLE_ARTIST), requests)
        } finally {
            repository.close()
        }
    }

    @Test
    fun `continues the source plan after an eligible catalog body is empty`() {
        val requests = CopyOnWriteArrayList<LyricsCatalogSearchKind>()
        val exact = FakeExactSource(exactHandler = { _, _, _ -> null })
        val qq = FakeCatalogSource(
            sourceName = SOURCE_QQ,
            searchHandler = { request, _, _ ->
                requests += request.kind
                listOf(candidate(SOURCE_QQ, "empty-primary"))
            },
            expandedSearchHandler = { request, _, _ ->
                requests += request.kind
                listOf(candidate(SOURCE_QQ, "album-with-lyrics"))
            },
            loadHandler = { sourceCandidate, _, _ ->
                sourceCandidate.takeIf { it.sourceId == "album-with-lyrics" }?.synchronized()
            }
        )
        val netEase = FakeCatalogSource(
            sourceName = SOURCE_NETEASE,
            searchHandler = { _, _, _ -> emptyList() }
        )
        val repository = repository(exact, listOf(qq, netEase))

        try {
            val found = requireFound(
                repository.resolveLyrics(query, LyricsCancellationSignal())
            )

            assertEquals("album-with-lyrics", found.result.sourceId)
            assertEquals(
                listOf(
                    LyricsCatalogSearchKind.TITLE_ARTIST,
                    LyricsCatalogSearchKind.TITLE_ALBUM
                ),
                requests
            )
        } finally {
            repository.close()
        }
    }

    @Test
    fun `no match diagnostics separate search expansion from candidate rejection`() {
        val logger = CapturingLyricsRepositoryLogger()
        val exact = FakeExactSource(exactHandler = { _, _, _ -> null })
        val qq = FakeCatalogSource(
            sourceName = SOURCE_QQ,
            searchHandler = { _, _, _ ->
                listOf(
                    LyricsResult(
                        durationMs = query.durationMs,
                        source = SOURCE_QQ,
                        sourceId = "wrong-artist",
                        candidateTrack = query.track,
                        candidateArtist = "Different Singer",
                        candidateAlbum = query.album
                    )
                )
            }
        )
        val netEase = FakeCatalogSource(
            sourceName = SOURCE_NETEASE,
            searchHandler = { _, _, _ -> emptyList() }
        )
        val repository = repository(exact, listOf(qq, netEase), logger = logger)

        try {
            assertEquals(
                LyricsResolutionOutcome.NoMatch,
                repository.resolveLyrics(query, LyricsCancellationSignal())
            )

            val summary = logger.messages.last()
            assertTrue(summary.contains("Lyrics unresolved=no-match"))
            assertTrue(summary.contains("TITLE_ARTIST"))
            assertTrue(summary.contains("TITLE_ALBUM"))
            assertTrue(summary.contains("ARTIST_CONFLICT"))
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
        totalDeadlineMs: Long = 1_000L,
        logger: LyricsRepositoryLogger = NoOpLyricsRepositoryLogger
    ): DirectLyricsRepository = DirectLyricsRepository(
        exactAndFallbackSource = exact,
        catalogSources = sources,
        executor = Executors.newFixedThreadPool(6),
        config = LyricsResolutionConfig(
            catalogDeadlineMs = catalogDeadlineMs,
            totalDeadlineMs = totalDeadlineMs
        ),
        logger = logger
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
            LyricsCatalogSearchRequest,
            Long,
            LyricsCancellationSignal
        ) -> List<LyricsResult>,
        private val expandedSearchHandler: (
            LyricsCatalogSearchRequest,
            Long,
            LyricsCancellationSignal
        ) -> List<LyricsResult> = { _, _, _ -> emptyList() },
        private val loadHandler: (
            LyricsResult,
            Long,
            LyricsCancellationSignal
        ) -> LyricsResult? = { _, _, _ -> null }
    ) : LyricsCatalogSource {
        override fun search(
            request: LyricsCatalogSearchRequest,
            deadlineNanos: Long,
            cancellation: LyricsCancellationSignal
        ): List<LyricsResult> = when (request.kind) {
            LyricsCatalogSearchKind.TITLE_ARTIST ->
                searchHandler(request, deadlineNanos, cancellation)
            else -> expandedSearchHandler(request, deadlineNanos, cancellation)
        }

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

    private class CapturingLyricsRepositoryLogger : LyricsRepositoryLogger {
        val messages = CopyOnWriteArrayList<String>()

        override fun info(message: String) {
            messages += message
        }

        override fun warning(message: String, error: Throwable) = Unit
    }
}
