package com.tcrrry.desktoplyrics

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
                result = synchronizedResult(durationMs = 208_720L)
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
                result = DirectLyricsRepository.Result(
                    lyrics = "[00:01.00]wrong lyric",
                    durationMs = 219_533L,
                    source = "网易云音乐",
                    sourceId = "unrelated-korean-title",
                    candidateTrack = "사뿐사뿐",
                    candidateArtist = "AOA",
                    candidateAlbum = "사뿐사뿐",
                    lyricsKind = LyricsKind.SYNCHRONIZED
                )
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
        assertEquals(2, migrated.version)
        migrated.close()
    }

    private fun synchronizedResult(durationMs: Long) = DirectLyricsRepository.Result(
        lyrics = "[00:01.00]lyric",
        durationMs = durationMs,
        source = "QQ音乐",
        sourceId = "twinkle",
        candidateTrack = "Twinkle",
        candidateArtist = "Localized Artist",
        candidateAlbum = "Twinkle Mini Album",
        lyricsKind = LyricsKind.SYNCHRONIZED
    )

    private companion object {
        const val DATABASE_NAME = "lyrics-cache-instrumentation.db"
    }
}
