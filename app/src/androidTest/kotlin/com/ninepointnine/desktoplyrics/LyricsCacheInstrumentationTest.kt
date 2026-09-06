package com.ninepointnine.desktoplyrics

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun upgradesVersionFourWithProvableAutomaticRowsAndAllManualRowsIntact() {
        val automatic = synchronizedResult(208_720L)
        val identity = LyricsPlaybackIdentity("Twinkle", "Localized Artist", "Twinkle Mini Album", 208_720L)
        val originalManual = createLegacyDatabase(4).use { database ->
            insertLegacyAutomatic(database, identity, automatic, includePlaybackIdentity = false)
            insertLegacyManual(database, identity, automatic.copy(sourceId = "manual"))
            database.execSQL("INSERT INTO lyrics_cache VALUES ('unprovable', '{}', 2, 1, 1, 1, 1)")
            database.execSQL("INSERT INTO lyrics_manual_override VALUES ('unprovable-manual', '{}', 3, 1)")
            database.execSQL("UPDATE cache_meta SET total_bytes = total_bytes + 5 WHERE meta_id = 1")
            manualRows(database)
        }

        LyricsCache(context, DATABASE_NAME).use { cache ->
            val snapshot = cache.snapshot(identity)
            assertEquals(1, snapshot.stats.automaticEntries)
            assertEquals(2, snapshot.stats.manualEntries)
            assertEquals(2_003L, snapshot.stats.totalBytes)
            assertEquals("manual", snapshot.current?.result?.sourceId)
        }
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { migrated ->
            assertEquals(6, migrated.version)
            assertEquals(originalManual, manualRows(migrated))
            migrated.rawQuery("SELECT recording_key FROM lyrics_manual_override WHERE cache_key = 'unprovable-manual'", null).use {
                it.moveToFirst()
                assertTrue(it.isNull(0))
            }
        }
    }

    @Test
    fun versionFiveReindexesDisplayVariantsAndConvergesWritesWithoutLosingStatistics() {
        val original = LyricsPlaybackIdentity("Twinkle", "Localized Artist / Other", "Twinkle Mini Album", 208_720L)
        val variant = original.copy(artist = "Localized Artist/Other")
        val automatic = synchronizedResult(original.durationMs).copy(candidateArtist = original.artist)
        val originalManual = createLegacyDatabase(5).use { database ->
            listOf(original, variant).forEach { identity ->
                insertLegacyAutomatic(database, identity, automatic, includePlaybackIdentity = true)
                insertLegacyManual(database, identity, automatic.copy(sourceId = "manual"))
            }
            manualRows(database)
        }

        LyricsCache(context, DATABASE_NAME).use { cache ->
            val migrated = cache.snapshot(variant)
            assertEquals(2, migrated.stats.automaticEntries)
            assertEquals(2, migrated.stats.manualEntries)
            assertEquals(4_000L, migrated.stats.totalBytes)
            assertEquals("manual", migrated.current?.result?.sourceId)
            context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { database ->
                assertEquals(originalManual, manualRows(database))
                database.rawQuery("SELECT COUNT(DISTINCT recording_key) FROM lyrics_cache", null).use {
                    it.moveToFirst()
                    assertEquals(1, it.getInt(0))
                }
            }

            assertTrue(cache.writeManual(variant, automatic.copy(sourceId = "new-manual")))
            assertEquals(1, cache.snapshot(variant).stats.manualEntries)
            assertEquals("new-manual", cache.read(original, recordUse = false)?.result?.sourceId)
            assertTrue(cache.clearManual(original))
            assertEquals(2, cache.snapshot(variant).stats.automaticEntries)
            assertEquals(0, cache.snapshot(variant).stats.manualEntries)
            assertEquals(2_000L, cache.snapshot(variant).stats.totalBytes)
            assertTrue(cache.writeAutomatic(variant, resolved(automatic)))
            assertEquals(1, cache.snapshot(original).stats.automaticEntries)
            assertEquals(automatic.sourceId, cache.read(original, recordUse = true)?.result?.sourceId)
        }

        LyricsCache(context, DATABASE_NAME).use { cache ->
            context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { database ->
                database.rawQuery("SELECT SUM(byte_size) FROM lyrics_cache", null).use {
                    it.moveToFirst()
                    assertEquals(it.getLong(0), cache.snapshot(original).stats.totalBytes)
                }
            }
            assertTrue(cache.clearCurrent(original))
            assertEquals(0, cache.snapshot(variant).stats.totalEntries)
            assertEquals(0L, cache.snapshot(variant).stats.totalBytes)
        }
    }

    @Test
    fun manualLookupAndDeletionCheckRealMillisecondsInsideTheIndexWindow() {
        val original = LyricsPlaybackIdentity("Twinkle", "Localized Artist", "Twinkle Mini Album", 200_001L)
        val outsideTolerance = original.copy(durationMs = 202_400L)
        val insideTolerance = original.copy(durationMs = 202_000L)
        LyricsCache(context, DATABASE_NAME).use { cache ->
            assertTrue(cache.writeManual(original, synchronizedResult(215_000L)))
            val before = cache.snapshot(original)
            assertNull(cache.read(outsideTolerance, recordUse = false))
            assertFalse(cache.clearManual(outsideTolerance))
            assertFalse(cache.clearCurrent(outsideTolerance))
            assertEquals(before.stats, cache.snapshot(original).stats)
            assertNotNull(cache.read(insideTolerance, recordUse = false))
            assertTrue(cache.clearCurrent(insideTolerance))
            assertEquals(0L, cache.snapshot(original).stats.totalBytes)
        }
    }

    @Test
    fun upgradesVersionThreeWithoutDiscardingAutomaticCacheRows() {
        val database = createLegacyDatabase(3)
        val result = synchronizedResult(208_720L)
        val key = LyricsCache.legacyKey(result.candidateTrack, result.candidateArtist, result.candidateAlbum, result.durationMs)
        val payload = result.toJson().put("selectionProof", resolved(result).proof.toJson()).toString()
        database.execSQL("INSERT INTO lyrics_cache VALUES (?, ?, 2, 1, 1, 1, 1)", arrayOf(key, payload))
        database.execSQL("UPDATE cache_meta SET total_bytes = 2 WHERE meta_id = 1")
        database.close()

        LyricsCache(context, DATABASE_NAME).use { cache ->
            val snapshot = cache.snapshot(null)
            assertEquals(1, snapshot.stats.automaticEntries)
            assertEquals(0, snapshot.stats.manualEntries)
            assertEquals(2L, snapshot.stats.totalBytes)
        }
        val migrated = context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null)
        assertEquals(6, migrated.version)
        migrated.rawQuery("SELECT COUNT(*) FROM lyrics_manual_override", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun clearsLegacyCacheEntriesWhenTheVersionedIdentityChanges() {
        val database = createLegacyDatabase(1)
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
        database.execSQL("UPDATE cache_meta SET total_bytes = 1 WHERE meta_id = 1")
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
        assertEquals(6, migrated.version)
        migrated.close()
    }

    private fun createLegacyDatabase(version: Int): SQLiteDatabase =
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).apply {
            execSQL(
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
            execSQL("CREATE TABLE cache_meta (meta_id INTEGER PRIMARY KEY CHECK (meta_id = 1), total_bytes INTEGER NOT NULL)")
            execSQL("INSERT INTO cache_meta VALUES (1, 0)")
            if (version >= 4) execSQL(
                """
                CREATE TABLE lyrics_manual_override (
                    cache_key TEXT PRIMARY KEY NOT NULL,
                    payload_json TEXT NOT NULL,
                    byte_size INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            this.version = version
        }

    private fun insertLegacyAutomatic(
        database: SQLiteDatabase,
        identity: LyricsPlaybackIdentity,
        result: LyricsResult,
        includePlaybackIdentity: Boolean
    ) {
        val key = LyricsCache.legacyKey(identity.track, identity.artist, identity.album, result.durationMs)
        val payload = result.toJson().put("selectionProof", resolved(result).proof.toJson())
        if (includePlaybackIdentity) payload.put("playbackIdentity", identity.toJson())
        database.execSQL("INSERT INTO lyrics_cache VALUES (?, ?, 1000, 1, 1, 1, 1)", arrayOf(key, payload.toString()))
        database.execSQL("UPDATE cache_meta SET total_bytes = total_bytes + 1000 WHERE meta_id = 1")
    }

    private fun insertLegacyManual(database: SQLiteDatabase, identity: LyricsPlaybackIdentity, result: LyricsResult) {
        val key = LyricsCache.legacyKey(identity.track, identity.artist, identity.album, identity.durationMs)
        val payload = result.toJson().put("playbackIdentity", identity.toJson()).toString()
        database.execSQL("INSERT INTO lyrics_manual_override VALUES (?, ?, 1000, 1)", arrayOf(key, payload))
        database.execSQL("UPDATE cache_meta SET total_bytes = total_bytes + 1000 WHERE meta_id = 1")
    }

    private fun manualRows(database: SQLiteDatabase): List<List<String>> = database.rawQuery(
        "SELECT cache_key, payload_json, byte_size, updated_at FROM lyrics_manual_override ORDER BY cache_key", null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(List(cursor.columnCount) { cursor.getString(it) })
        }
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
