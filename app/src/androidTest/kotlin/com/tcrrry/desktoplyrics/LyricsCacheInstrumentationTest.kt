package com.tcrrry.desktoplyrics

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyricsCacheInstrumentationTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearTestDatabase() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun closeTestDatabase() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun rechecksDurationBeforeReturningAStoredLyricsEntry() {
        LyricsCache(context, DATABASE_NAME).use { cache ->
            cache.put(
                track = "Twinkle",
                artist = "Localized Artist",
                album = "Twinkle Mini Album",
                playbackDurationMs = 206_796L,
                resolved = resolved(synchronizedResult(durationMs = 208_720L))
            )

            assertNotNull(
                cache.get(
                    track = "Twinkle",
                    artist = "Localized Artist",
                    album = "Twinkle Mini Album",
                    playbackDurationMs = 206_796L,
                    recordUse = false
                )
            )
            assertNull(
                cache.get(
                    track = "Twinkle",
                    artist = "Localized Artist",
                    album = "Twinkle Mini Album",
                    playbackDurationMs = 211_000L,
                    recordUse = false
                )
            )
        }
    }

    @Test
    fun rejectsStoredLyricsWhenCandidateMetadataDoesNotMatchPlayback() {
        LyricsCache(context, DATABASE_NAME).use { cache ->
            cache.put(
                track = "MOYA",
                artist = "AOA",
                album = "MOYA - EP",
                playbackDurationMs = 220_427L,
                resolved = resolved(LyricsResult(
                    lyrics = "[00:01.00]wrong lyric",
                    durationMs = 219_533L,
                    source = "网易云音乐",
                    sourceId = "unrelated-korean-title",
                    candidateTrack = "사뿐사뿐",
                    candidateArtist = "AOA",
                    candidateAlbum = "사뿐사뿐",
                    lyricsKind = LyricsKind.SYNCHRONIZED
                ))
            )

            assertNull(
                cache.get(
                    track = "MOYA",
                    artist = "AOA",
                    album = "MOYA - EP",
                    playbackDurationMs = 220_427L,
                    recordUse = false
                )
            )
        }
    }

    @Test
    fun preservesOptionalTranslationInTheExistingCachePayload() {
        LyricsCache(context, DATABASE_NAME).use { cache ->
            cache.put(
                track = "Translated Song",
                artist = "Artist",
                album = "Album",
                playbackDurationMs = 200_000L,
                resolved = resolved(LyricsResult(
                    lyrics = "[00:01.00]Original lyric",
                    translatedLyrics = "[00:01.00]Translated lyric",
                    durationMs = 200_000L,
                    source = "QQ音乐",
                    sourceId = "translated-song",
                    candidateTrack = "Translated Song",
                    candidateArtist = "Artist",
                    candidateAlbum = "Album",
                    lyricsKind = LyricsKind.SYNCHRONIZED
                ))
            )

            val entry = cache.get(
                track = "Translated Song",
                artist = "Artist",
                album = "Album",
                playbackDurationMs = 200_000L,
                recordUse = false
            )
            val stored = requireNotNull(entry)

            assertEquals("[00:01.00]Translated lyric", stored.result.translatedLyrics)
            assertEquals(false, stored.needsRefresh(stored.updatedAtMs + 1L))
        }
    }

    @Test
    fun preservesIndependentSourceProofAcrossCacheReads() {
        val query = LyricsLookup(
            track = "마리아",
            artist = "HWASA",
            durationMs = 199_000L
        )
        val candidates = listOf(
            LyricsResult(
                durationMs = 199_000L,
                source = "QQ音乐",
                sourceId = "localized-a",
                candidateTrack = "마리아",
                candidateArtist = "华莎",
                candidateAlbum = "María"
            ),
            LyricsResult(
                durationMs = 199_100L,
                source = "网易云音乐",
                sourceId = "localized-b",
                candidateTrack = "마리아 (Maria)",
                candidateArtist = "华莎",
                candidateAlbum = "María"
            )
        )
        val selection = LyricsCandidateSelector.selectCandidatesWithProof(query, candidates).first()
        val resolved = ResolvedLyrics(
            result = selection.candidate.copy(
                lyrics = "[00:01.00]Maria",
                lyricsKind = LyricsKind.SYNCHRONIZED
            ),
            proof = selection.proof
        )

        LyricsCache(context, DATABASE_NAME).use { cache ->
            cache.put(
                track = query.track,
                artist = query.artist,
                album = query.album,
                playbackDurationMs = query.durationMs,
                resolved = resolved
            )

            val cached = cache.get(
                track = query.track,
                artist = query.artist,
                album = query.album,
                playbackDurationMs = query.durationMs,
                recordUse = false
            )

            assertEquals(selection.candidate.sourceId, requireNotNull(cached).result.sourceId)
            assertEquals(2, requireNotNull(cached.proof).supportingCandidates.size)
        }
    }

    @Test
    fun manualOverrideWinsAndRestoringAutomaticKeepsTheAutomaticCache() {
        val identity = LyricsPlaybackIdentity(
            track = "Twinkle",
            artist = "Localized Artist",
            album = "Twinkle Mini Album",
            durationMs = 206_796L
        )
        val automatic = synchronizedResult(durationMs = 208_720L)
        val manual = LyricsResult(
            lyrics = "[00:01.00]manually chosen lyric",
            durationMs = 215_000L,
            source = "网易云音乐",
            sourceId = "manual-version",
            candidateTrack = "Twinkle (Live)",
            candidateArtist = "Localized Artist",
            candidateAlbum = "Live Album",
            lyricsKind = LyricsKind.SYNCHRONIZED
        )

        LyricsCache(context, DATABASE_NAME).use { cache ->
            cache.put(
                track = identity.track,
                artist = identity.artist,
                album = identity.album,
                playbackDurationMs = identity.durationMs,
                resolved = resolved(automatic)
            )
            cache.putManual(identity, manual)

            val selected = requireNotNull(cache.get(
                identity.track,
                identity.artist,
                identity.album,
                identity.durationMs,
                recordUse = false
            ))
            assertEquals(LyricsCacheSelection.MANUAL, selected.selection)
            assertEquals("manual-version", selected.result.sourceId)
            assertEquals(false, selected.needsRefresh(Long.MAX_VALUE))

            assertTrue(cache.clearManual(identity))
            val restored = requireNotNull(cache.get(
                identity.track,
                identity.artist,
                identity.album,
                identity.durationMs,
                recordUse = false
            ))
            assertEquals(LyricsCacheSelection.AUTOMATIC, restored.selection)
            assertEquals(automatic.sourceId, restored.result.sourceId)
        }
    }

    @Test
    fun exposesCombinedStatsAndSupportsCurrentCleanup() {
        val first = LyricsPlaybackIdentity("Twinkle", "Localized Artist", "Album", 206_796L)
        val second = LyricsPlaybackIdentity("Second", "Artist", "Album", 190_000L)

        LyricsCache(context, DATABASE_NAME).use { cache ->
            cache.put(
                first.track,
                first.artist,
                first.album,
                first.durationMs,
                resolved(synchronizedResult(208_720L).copy(candidateAlbum = first.album))
            )
            cache.putManual(
                second,
                synchronizedResult(second.durationMs).copy(
                    sourceId = "manual-second",
                    candidateTrack = second.track,
                    candidateArtist = second.artist,
                    candidateAlbum = second.album
                )
            )

            val initial = cache.snapshot(second)
            assertEquals(1, initial.stats.automaticEntries)
            assertEquals(1, initial.stats.manualEntries)
            assertTrue(initial.stats.totalBytes > 0L)
            assertEquals(LyricsCacheSelection.MANUAL, requireNotNull(initial.current).selection)

            assertTrue(cache.clearCurrent(first))
            val afterCurrent = cache.snapshot(first)
            assertEquals(0, afterCurrent.stats.automaticEntries)
            assertEquals(1, afterCurrent.stats.manualEntries)
            assertNull(afterCurrent.current)

        }
    }

    @Test
    fun upgradesVersionThreeWithoutDiscardingAutomaticCacheRows() {
        val database = context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null)
        database.execSQL(
            """
            CREATE TABLE lyrics_cache (
                cache_key TEXT PRIMARY KEY NOT NULL,
                payload_json TEXT NOT NULL,
                byte_size INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                last_used_at INTEGER NOT NULL,
                use_count INTEGER NOT NULL,
                eviction_score INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE TABLE cache_meta (meta_id INTEGER PRIMARY KEY CHECK (meta_id = 1), total_bytes INTEGER NOT NULL)"
        )
        database.execSQL(
            "INSERT INTO lyrics_cache VALUES ('preserved', '{}', 2, 1, 1, 1, 1)"
        )
        database.execSQL("INSERT INTO cache_meta VALUES (1, 2)")
        database.version = 3
        database.close()

        LyricsCache(context, DATABASE_NAME).use { cache ->
            val snapshot = cache.snapshot(null)
            assertEquals(1, snapshot.stats.automaticEntries)
            assertEquals(0, snapshot.stats.manualEntries)
            assertEquals(2L, snapshot.stats.totalBytes)
        }
        val migrated = context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null)
        assertEquals(4, migrated.version)
        migrated.rawQuery("SELECT COUNT(*) FROM lyrics_manual_override", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun clearsLegacyCacheEntriesWhenTheVersionedIdentityChanges() {
        val database = context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null)
        database.execSQL(
            """
            CREATE TABLE lyrics_cache (
                cache_key TEXT PRIMARY KEY NOT NULL,
                payload_json TEXT NOT NULL,
                byte_size INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                last_used_at INTEGER NOT NULL,
                use_count INTEGER NOT NULL,
                eviction_score INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE TABLE cache_meta (meta_id INTEGER PRIMARY KEY CHECK (meta_id = 1), total_bytes INTEGER NOT NULL)"
        )
        val payload = JSONObject()
            .put("lyrics", "[00:01.00]legacy")
            .put("duration", 208_720L)
            .put("source", "QQ音乐")
            .put("sourceId", "legacy")
            .put("candidateTrack", "Twinkle")
            .put("candidateArtist", "Localized Artist")
            .put("candidateAlbum", "Twinkle Mini Album")
            .toString()
        database.execSQL(
            "INSERT INTO lyrics_cache VALUES ('legacy', ?, 1, 1, 1, 1, 1)",
            arrayOf(payload)
        )
        database.execSQL("INSERT INTO cache_meta VALUES (1, 1)")
        database.version = 1
        database.close()

        LyricsCache(context, DATABASE_NAME).use { cache ->
            assertNull(
                cache.get(
                    track = "Twinkle",
                    artist = "Localized Artist",
                    album = "Twinkle Mini Album",
                    playbackDurationMs = 206_796L,
                    recordUse = false
                )
            )
        }
        val migrated = context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null)
        migrated.rawQuery("SELECT COUNT(*) FROM lyrics_cache", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        migrated.rawQuery("SELECT total_bytes FROM cache_meta WHERE meta_id = 1", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals(0L, cursor.getLong(0))
        }
        assertEquals(4, migrated.version)
        migrated.close()
    }

    private fun synchronizedResult(durationMs: Long) = LyricsResult(
        lyrics = "[00:01.00]lyric",
        durationMs = durationMs,
        source = "QQ音乐",
        sourceId = "twinkle",
        candidateTrack = "Twinkle",
        candidateArtist = "Localized Artist",
        candidateAlbum = "Twinkle Mini Album",
        lyricsKind = LyricsKind.SYNCHRONIZED
    )

    private fun resolved(result: LyricsResult): ResolvedLyrics = ResolvedLyrics(
        result = result,
        proof = LyricsSelectionProof(
            matcherPolicyVersion = LYRICS_MATCHER_POLICY_VERSION,
            supportingCandidates = listOf(result.candidateSnapshot())
        )
    )

    private companion object {
        const val DATABASE_NAME = "lyrics-cache-instrumentation.db"
    }
}
