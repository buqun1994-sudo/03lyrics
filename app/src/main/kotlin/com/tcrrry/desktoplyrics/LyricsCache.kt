package com.tcrrry.desktoplyrics

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

internal class LyricsCache(
    context: Context,
    databaseName: String = DATABASE_NAME
) : Closeable {
    data class Entry(
        val result: DirectLyricsRepository.Result,
        val updatedAtMs: Long,
        val translationResolved: Boolean = true
    ) {
        fun needsRefresh(nowMs: Long): Boolean =
            !translationResolved || nowMs - updatedAtMs >= LyricsCachePolicy.REFRESH_AFTER_MS
    }

    private val helper = CacheDatabase(context.applicationContext, databaseName)
    @Volatile private var closed = false

    @Synchronized
    fun get(
        track: String,
        artist: String,
        album: String,
        playbackDurationMs: Long,
        recordUse: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): Entry? {
        if (closed || !LyricsCandidateSelector.hasKnownDuration(playbackDurationMs)) return null
        return runCatching {
            val database = helper.writableDatabase
            val query = LyricsLookup(track, artist, album, playbackDurationMs)
            val matches = lookupKeys(track, artist, album, playbackDurationMs).mapNotNull { cacheKey ->
                database.query(
                    TABLE_CACHE,
                    arrayOf(COLUMN_PAYLOAD, COLUMN_UPDATED_AT, COLUMN_USE_COUNT),
                    "$COLUMN_KEY = ?",
                    arrayOf(cacheKey),
                    null,
                    null,
                    null,
                    "1"
                ).use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    decodeEntry(cursor.getString(0), cursor.getLong(1))
                        ?.takeIf {
                            LyricsCandidateSelector.matchesVersion(query, it.result)
                        }
                        ?.let { CachedEntry(cacheKey, it, cursor.getInt(2)) }
                }
            }
            val cached = matches.minWithOrNull(
                compareBy<CachedEntry> { abs(playbackDurationMs - it.entry.result.durationMs) }
                    .thenByDescending { it.entry.updatedAtMs }
            ) ?: return@runCatching null
            if (recordUse) {
                val nextUseCount = LyricsCachePolicy.nextUseCount(cached.useCount)
                database.update(
                    TABLE_CACHE,
                    ContentValues().apply {
                        put(COLUMN_LAST_USED_AT, nowMs)
                        put(COLUMN_USE_COUNT, nextUseCount)
                        put(
                            COLUMN_EVICTION_SCORE,
                            LyricsCachePolicy.evictionScore(nowMs, nextUseCount)
                        )
                    },
                    "$COLUMN_KEY = ?",
                    arrayOf(cached.cacheKey)
                )
            }
            cached.entry
        }.onFailure { error ->
            Log.w(LOG_TAG, "Unable to read native lyrics cache", error)
        }.getOrNull()
    }

    @Synchronized
    fun put(
        track: String,
        artist: String,
        album: String,
        playbackDurationMs: Long,
        result: DirectLyricsRepository.Result,
        updatedAtMs: Long = System.currentTimeMillis()
    ) {
        if (closed || classifyLyrics(result.lyrics) != LyricsKind.SYNCHRONIZED ||
            !LyricsCandidateSelector.hasMatchingDuration(playbackDurationMs, result.durationMs)
        ) {
            return
        }
        runCatching {
            val cacheKey = key(track, artist, album, result.durationMs)
            val payload = encodeResult(result)
            val byteSize = cacheEntrySize(cacheKey, payload)
            if (byteSize > LyricsCachePolicy.MAX_BYTES) return@runCatching

            val database = helper.writableDatabase
            database.beginTransaction()
            try {
                val existing = existingMetadata(database, cacheKey)
                val nowMs = System.currentTimeMillis()
                val useCount = existing?.useCount ?: 1
                val lastUsedAtMs = existing?.lastUsedAtMs ?: nowMs
                val evictionScore = existing?.evictionScore
                    ?: LyricsCachePolicy.evictionScore(lastUsedAtMs, useCount)
                database.insertWithOnConflict(
                    TABLE_CACHE,
                    null,
                    ContentValues().apply {
                        put(COLUMN_KEY, cacheKey)
                        put(COLUMN_PAYLOAD, payload)
                        put(COLUMN_BYTE_SIZE, byteSize)
                        put(COLUMN_UPDATED_AT, updatedAtMs.coerceIn(0L, nowMs))
                        put(COLUMN_LAST_USED_AT, lastUsedAtMs)
                        put(COLUMN_USE_COUNT, useCount)
                        put(COLUMN_EVICTION_SCORE, evictionScore)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                var totalBytes = totalBytes(database) - (existing?.byteSize ?: 0L) + byteSize
                if (totalBytes > LyricsCachePolicy.MAX_BYTES) {
                    totalBytes = trimToTarget(database, totalBytes)
                }
                writeTotalBytes(database, totalBytes)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }.onFailure { error ->
            Log.w(LOG_TAG, "Unable to write native lyrics cache", error)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        helper.close()
    }

    private fun trimToTarget(database: SQLiteDatabase, startingBytes: Long): Long {
        var totalBytes = startingBytes
        while (totalBytes > LyricsCachePolicy.TRIM_TARGET_BYTES) {
            val victims = mutableListOf<Pair<String, Long>>()
            database.query(
                TABLE_CACHE,
                arrayOf(COLUMN_KEY, COLUMN_BYTE_SIZE),
                null,
                null,
                null,
                null,
                "$COLUMN_EVICTION_SCORE ASC, $COLUMN_LAST_USED_AT ASC",
                EVICTION_BATCH_SIZE.toString()
            ).use { cursor ->
                while (cursor.moveToNext() && totalBytes > LyricsCachePolicy.TRIM_TARGET_BYTES) {
                    val victim = cursor.getString(0) to cursor.getLong(1)
                    victims += victim
                    totalBytes -= victim.second
                }
            }
            if (victims.isEmpty()) break
            val placeholders = List(victims.size) { "?" }.joinToString(",")
            database.delete(
                TABLE_CACHE,
                "$COLUMN_KEY IN ($placeholders)",
                victims.map { it.first }.toTypedArray()
            )
        }
        return totalBytes.coerceAtLeast(0L)
    }

    private fun existingMetadata(database: SQLiteDatabase, cacheKey: String): ExistingMetadata? {
        database.query(
            TABLE_CACHE,
            arrayOf(COLUMN_BYTE_SIZE, COLUMN_LAST_USED_AT, COLUMN_USE_COUNT, COLUMN_EVICTION_SCORE),
            "$COLUMN_KEY = ?",
            arrayOf(cacheKey),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return ExistingMetadata(
                byteSize = cursor.getLong(0),
                lastUsedAtMs = cursor.getLong(1),
                useCount = cursor.getInt(2),
                evictionScore = cursor.getLong(3)
            )
        }
    }

    private fun totalBytes(database: SQLiteDatabase): Long {
        database.query(
            TABLE_META,
            arrayOf(COLUMN_TOTAL_BYTES),
            "$COLUMN_META_ID = 1",
            null,
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return 0L
    }

    private fun writeTotalBytes(database: SQLiteDatabase, totalBytes: Long) {
        database.insertWithOnConflict(
            TABLE_META,
            null,
            ContentValues().apply {
                put(COLUMN_META_ID, 1)
                put(COLUMN_TOTAL_BYTES, totalBytes)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun encodeResult(result: DirectLyricsRepository.Result): String = result.toJson().toString()

    private fun decodeEntry(payload: String, updatedAtMs: Long): Entry? {
        val value = JSONObject(payload)
        val lyrics = cleanLyrics(value.optString("lyrics"))
        if (classifyLyrics(lyrics) != LyricsKind.SYNCHRONIZED) return null
        return Entry(
            result = DirectLyricsRepository.Result(
                lyrics = lyrics,
                translatedLyrics = synchronizedLyricsOrEmpty(value.optString("translatedLyrics")),
                durationMs = value.optLong("duration", 0L),
                cover = value.optString("cover"),
                source = value.optString("source"),
                sourceId = value.optString("sourceId"),
                candidateTrack = value.optString("candidateTrack"),
                candidateArtist = value.optString("candidateArtist"),
                candidateAlbum = value.optString("candidateAlbum"),
                lyricsKind = LyricsKind.SYNCHRONIZED
            ),
            updatedAtMs = updatedAtMs,
            translationResolved = value.has("translatedLyrics")
        )
    }

    private fun cacheEntrySize(cacheKey: String, payload: String): Long =
        cacheKey.toByteArray(StandardCharsets.UTF_8).size.toLong() +
            payload.toByteArray(StandardCharsets.UTF_8).size.toLong() +
            ESTIMATED_ROW_OVERHEAD_BYTES

    private data class ExistingMetadata(
        val byteSize: Long,
        val lastUsedAtMs: Long,
        val useCount: Int,
        val evictionScore: Long
    )

    private data class CachedEntry(
        val cacheKey: String,
        val entry: Entry,
        val useCount: Int
    )

    private class CacheDatabase(context: Context, databaseName: String) :
        SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {
        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE $TABLE_CACHE (
                    $COLUMN_KEY TEXT PRIMARY KEY NOT NULL,
                    $COLUMN_PAYLOAD TEXT NOT NULL,
                    $COLUMN_BYTE_SIZE INTEGER NOT NULL,
                    $COLUMN_UPDATED_AT INTEGER NOT NULL,
                    $COLUMN_LAST_USED_AT INTEGER NOT NULL,
                    $COLUMN_USE_COUNT INTEGER NOT NULL,
                    $COLUMN_EVICTION_SCORE INTEGER NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL(
                "CREATE INDEX $INDEX_EVICTION ON $TABLE_CACHE " +
                    "($COLUMN_EVICTION_SCORE ASC, $COLUMN_LAST_USED_AT ASC)"
            )
            database.execSQL(
                """
                CREATE TABLE $TABLE_META (
                    $COLUMN_META_ID INTEGER PRIMARY KEY CHECK ($COLUMN_META_ID = 1),
                    $COLUMN_TOTAL_BYTES INTEGER NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL(
                "INSERT INTO $TABLE_META ($COLUMN_META_ID, $COLUMN_TOTAL_BYTES) VALUES (1, 0)"
            )
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            database.delete(TABLE_CACHE, null, null)
            database.delete(TABLE_META, null, null)
            database.execSQL(
                "INSERT INTO $TABLE_META ($COLUMN_META_ID, $COLUMN_TOTAL_BYTES) VALUES (1, 0)"
            )
        }
    }

    companion object {
        private const val LOG_TAG = "DesktopLyrics"
        private const val DATABASE_NAME = "lyrics-cache.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE_CACHE = "lyrics_cache"
        private const val TABLE_META = "cache_meta"
        private const val COLUMN_KEY = "cache_key"
        private const val COLUMN_PAYLOAD = "payload_json"
        private const val COLUMN_BYTE_SIZE = "byte_size"
        private const val COLUMN_UPDATED_AT = "updated_at"
        private const val COLUMN_LAST_USED_AT = "last_used_at"
        private const val COLUMN_USE_COUNT = "use_count"
        private const val COLUMN_EVICTION_SCORE = "eviction_score"
        private const val COLUMN_META_ID = "meta_id"
        private const val COLUMN_TOTAL_BYTES = "total_bytes"
        private const val INDEX_EVICTION = "index_lyrics_cache_eviction"
        private const val EVICTION_BATCH_SIZE = 256
        private const val ESTIMATED_ROW_OVERHEAD_BYTES = 256L

        fun key(track: String, artist: String, album: String, durationMs: Long): String {
            val durationSeconds = roundedDurationSeconds(durationMs)
            val identity = Normalizer.normalize(
                "$track\u0000$artist\u0000$album\u0000$durationSeconds",
                Normalizer.Form.NFKC
            )
                .trim()
                .lowercase(Locale.ROOT)
            return MessageDigest.getInstance("SHA-256")
                .digest(identity.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

        fun usageKey(track: String, artist: String, album: String, playbackDurationMs: Long): String =
            key(track, artist, album, playbackDurationMs)

        internal fun lookupKeys(
            track: String,
            artist: String,
            album: String,
            playbackDurationMs: Long
        ): List<String> {
            val durationSeconds = roundedDurationSeconds(playbackDurationMs)
            val toleranceSeconds = (LyricsCandidateSelector.MAX_DURATION_DELTA_MS + 999L) / 1_000L
            return (-toleranceSeconds..toleranceSeconds)
                .map { offset -> key(track, artist, album, (durationSeconds + offset).coerceAtLeast(0L) * 1_000L) }
                .distinct()
        }

        private fun roundedDurationSeconds(durationMs: Long): Long =
            (durationMs.coerceAtLeast(0L) + 500L) / 1_000L
    }
}

internal object LyricsCachePolicy {
    const val MAX_BYTES = 128L * 1024L * 1024L
    const val TRIM_TARGET_BYTES = MAX_BYTES * 9L / 10L
    const val REFRESH_AFTER_MS = 30L * 24L * 60L * 60L * 1000L
    private const val MAX_USE_COUNT = 30
    private const val FREQUENCY_BONUS_MS = 7L * 24L * 60L * 60L * 1000L

    fun nextUseCount(current: Int): Int = min(MAX_USE_COUNT, current.coerceAtLeast(0) + 1)

    fun evictionScore(lastUsedAtMs: Long, useCount: Int): Long =
        lastUsedAtMs + min(MAX_USE_COUNT, useCount.coerceAtLeast(1)) * FREQUENCY_BONUS_MS
}
