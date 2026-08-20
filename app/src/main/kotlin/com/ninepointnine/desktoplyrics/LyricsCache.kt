package com.ninepointnine.desktoplyrics

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
        val result: LyricsResult,
        val proof: LyricsSelectionProof?,
        val updatedAtMs: Long,
        val selection: LyricsCacheSelection = LyricsCacheSelection.AUTOMATIC,
        val translationResolved: Boolean = true
    ) {
        val resolved: ResolvedLyrics? get() = proof?.let { ResolvedLyrics(result, it) }

        fun needsRefresh(nowMs: Long): Boolean =
            selection == LyricsCacheSelection.AUTOMATIC &&
                (!translationResolved || nowMs - updatedAtMs >= LyricsCachePolicy.REFRESH_AFTER_MS)
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
            findManualEntry(database, track, artist, album, playbackDurationMs)?.let {
                return@runCatching it.entry
            }
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
                    decodeAutomaticEntry(cursor.getString(0), cursor.getLong(1))
                        ?.takeIf {
                            it.proof?.let { proof ->
                                LyricsCandidateSelector.isProofValid(query, it.result, proof)
                            } == true
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
        resolved: ResolvedLyrics,
        updatedAtMs: Long = System.currentTimeMillis()
    ) {
        val result = resolved.result
        val query = LyricsLookup(track, artist, album, playbackDurationMs)
        if (closed || classifyLyrics(result.lyrics) != LyricsKind.SYNCHRONIZED ||
            !LyricsCandidateSelector.hasMatchingDuration(playbackDurationMs, result.durationMs) ||
            !LyricsCandidateSelector.isProofValid(query, result, resolved.proof)
        ) {
            return
        }
        runCatching {
            val cacheKey = key(track, artist, album, result.durationMs)
            val payload = encodeResolved(resolved)
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
    fun putManual(
        identity: LyricsPlaybackIdentity,
        result: LyricsResult,
        updatedAtMs: Long = System.currentTimeMillis()
    ) {
        if (closed || !identity.isUsable ||
            classifyLyrics(result.lyrics) != LyricsKind.SYNCHRONIZED
        ) {
            return
        }
        runCatching {
            val cacheKey = key(identity.track, identity.artist, identity.album, identity.durationMs)
            val payload = result.toJson()
                .put("playbackIdentity", identity.toJson())
                .toString()
            val byteSize = cacheEntrySize(cacheKey, payload)
            if (byteSize > LyricsCachePolicy.TRIM_TARGET_BYTES) return@runCatching

            val database = helper.writableDatabase
            database.beginTransaction()
            try {
                val previousBytes = rowByteSize(database, TABLE_MANUAL, cacheKey)
                val nowMs = System.currentTimeMillis()
                database.insertWithOnConflict(
                    TABLE_MANUAL,
                    null,
                    ContentValues().apply {
                        put(COLUMN_KEY, cacheKey)
                        put(COLUMN_PAYLOAD, payload)
                        put(COLUMN_BYTE_SIZE, byteSize)
                        put(COLUMN_UPDATED_AT, updatedAtMs.coerceIn(0L, nowMs))
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                var totalBytes = totalBytes(database) - previousBytes + byteSize
                totalBytes = trimManualCount(database, totalBytes, cacheKey)
                if (totalBytes > LyricsCachePolicy.MAX_BYTES) {
                    totalBytes = trimToTarget(database, totalBytes, cacheKey)
                }
                writeTotalBytes(database, totalBytes)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }.onFailure { error ->
            Log.w(LOG_TAG, "Unable to write manual lyrics override", error)
        }
    }

    @Synchronized
    fun clearManual(identity: LyricsPlaybackIdentity): Boolean {
        if (closed || !identity.isUsable) return false
        return runCatching {
            val database = helper.writableDatabase
            database.beginTransaction()
            try {
                val removedBytes = deleteKeys(
                    database,
                    TABLE_MANUAL,
                    lookupKeys(identity.track, identity.artist, identity.album, identity.durationMs)
                )
                if (removedBytes > 0L) {
                    writeTotalBytes(database, totalBytes(database) - removedBytes)
                }
                database.setTransactionSuccessful()
                removedBytes > 0L
            } finally {
                database.endTransaction()
            }
        }.onFailure { error ->
            Log.w(LOG_TAG, "Unable to clear manual lyrics override", error)
        }.getOrDefault(false)
    }

    @Synchronized
    fun clearCurrent(identity: LyricsPlaybackIdentity): Boolean {
        if (closed || !identity.isUsable) return false
        return runCatching {
            val database = helper.writableDatabase
            val keys = lookupKeys(identity.track, identity.artist, identity.album, identity.durationMs)
            database.beginTransaction()
            try {
                val removedBytes = deleteKeys(database, TABLE_MANUAL, keys) +
                    deleteKeys(database, TABLE_CACHE, keys)
                if (removedBytes > 0L) {
                    writeTotalBytes(database, totalBytes(database) - removedBytes)
                }
                database.setTransactionSuccessful()
                removedBytes > 0L
            } finally {
                database.endTransaction()
            }
        }.onFailure { error ->
            Log.w(LOG_TAG, "Unable to clear current lyrics cache", error)
        }.getOrDefault(false)
    }

    @Synchronized
    fun snapshot(identity: LyricsPlaybackIdentity?): LyricsCacheSnapshot {
        if (closed) return LyricsCacheSnapshot(emptyStats(), null)
        return runCatching {
            val database = helper.writableDatabase
            val stats = LyricsCacheStats(
                automaticEntries = tableCount(database, TABLE_CACHE),
                manualEntries = tableCount(database, TABLE_MANUAL),
                totalBytes = totalBytes(database),
                maximumAutomaticBytes = LyricsCachePolicy.MAX_BYTES
            )
            val current = identity?.takeIf(LyricsPlaybackIdentity::isUsable)?.let { playback ->
                get(
                    playback.track,
                    playback.artist,
                    playback.album,
                    playback.durationMs,
                    recordUse = false
                )?.let { entry ->
                    LyricsCachedTrackInfo(entry.selection, entry.result, entry.updatedAtMs)
                }
            }
            LyricsCacheSnapshot(stats, current)
        }.onFailure { error ->
            Log.w(LOG_TAG, "Unable to read lyrics cache snapshot", error)
        }.getOrElse { LyricsCacheSnapshot(emptyStats(), null) }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        helper.close()
    }

    private fun trimToTarget(
        database: SQLiteDatabase,
        startingBytes: Long,
        protectedManualKey: String? = null
    ): Long {
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
        if (totalBytes <= LyricsCachePolicy.TRIM_TARGET_BYTES) return totalBytes.coerceAtLeast(0L)
        val selection = if (protectedManualKey == null) {
            null
        } else {
            "$COLUMN_KEY != ?"
        }
        val selectionArgs = protectedManualKey?.let { arrayOf(it) }
        while (totalBytes > LyricsCachePolicy.TRIM_TARGET_BYTES) {
            val victim = database.query(
                TABLE_MANUAL,
                arrayOf(COLUMN_KEY, COLUMN_BYTE_SIZE),
                selection,
                selectionArgs,
                null,
                null,
                "$COLUMN_UPDATED_AT ASC",
                "1"
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) to cursor.getLong(1) else null
            } ?: break
            database.delete(TABLE_MANUAL, "$COLUMN_KEY = ?", arrayOf(victim.first))
            totalBytes -= victim.second
        }
        return totalBytes.coerceAtLeast(0L)
    }

    private fun trimManualCount(
        database: SQLiteDatabase,
        startingBytes: Long,
        protectedKey: String
    ): Long {
        var totalBytes = startingBytes
        while (tableCount(database, TABLE_MANUAL) > MANUAL_ENTRY_LIMIT) {
            val victim = database.query(
                TABLE_MANUAL,
                arrayOf(COLUMN_KEY, COLUMN_BYTE_SIZE),
                "$COLUMN_KEY != ?",
                arrayOf(protectedKey),
                null,
                null,
                "$COLUMN_UPDATED_AT ASC",
                "1"
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) to cursor.getLong(1) else null
            } ?: break
            database.delete(TABLE_MANUAL, "$COLUMN_KEY = ?", arrayOf(victim.first))
            totalBytes -= victim.second
        }
        return totalBytes.coerceAtLeast(0L)
    }

    private fun findManualEntry(
        database: SQLiteDatabase,
        track: String,
        artist: String,
        album: String,
        playbackDurationMs: Long
    ): ManualCachedEntry? = lookupKeys(track, artist, album, playbackDurationMs)
        .mapNotNull { cacheKey ->
            database.query(
                TABLE_MANUAL,
                arrayOf(COLUMN_PAYLOAD, COLUMN_UPDATED_AT),
                "$COLUMN_KEY = ?",
                arrayOf(cacheKey),
                null,
                null,
                null,
                "1"
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                decodeManualEntry(cursor.getString(0), cursor.getLong(1))
            }
        }
        .minWithOrNull(
            compareBy<ManualCachedEntry> {
                abs(playbackDurationMs - it.identity.durationMs)
            }.thenByDescending { it.entry.updatedAtMs }
        )

    private fun rowByteSize(database: SQLiteDatabase, table: String, cacheKey: String): Long =
        database.query(
            table,
            arrayOf(COLUMN_BYTE_SIZE),
            "$COLUMN_KEY = ?",
            arrayOf(cacheKey),
            null,
            null,
            null,
            "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private fun deleteKeys(database: SQLiteDatabase, table: String, keys: List<String>): Long {
        if (keys.isEmpty()) return 0L
        val placeholders = List(keys.size) { "?" }.joinToString(",")
        val bytes = database.query(
            table,
            arrayOf("COALESCE(SUM($COLUMN_BYTE_SIZE), 0)"),
            "$COLUMN_KEY IN ($placeholders)",
            keys.toTypedArray(),
            null,
            null,
            null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
        database.delete(table, "$COLUMN_KEY IN ($placeholders)", keys.toTypedArray())
        return bytes
    }

    private fun tableCount(database: SQLiteDatabase, table: String): Int = database.query(
        table,
        arrayOf("COUNT(*)"),
        null,
        null,
        null,
        null,
        null
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

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

    private fun encodeResolved(resolved: ResolvedLyrics): String = resolved.result.toJson()
        .put("selectionProof", resolved.proof.toJson())
        .toString()

    private fun decodeAutomaticEntry(payload: String, updatedAtMs: Long): Entry? {
        val value = JSONObject(payload)
        val result = decodeResult(value) ?: return null
        val proof = LyricsSelectionProof.fromJson(value.optJSONObject("selectionProof"))
            ?: return null
        return Entry(
            result = result,
            proof = proof,
            updatedAtMs = updatedAtMs,
            translationResolved = value.has("translatedLyrics")
        )
    }

    private fun decodeManualEntry(payload: String, updatedAtMs: Long): ManualCachedEntry? {
        val value = JSONObject(payload)
        val identity = LyricsPlaybackIdentity.fromJson(value.optJSONObject("playbackIdentity"))
            ?.takeIf(LyricsPlaybackIdentity::isUsable)
            ?: return null
        val result = decodeResult(value) ?: return null
        return ManualCachedEntry(
            identity,
            Entry(
                result = result,
                proof = null,
                updatedAtMs = updatedAtMs,
                selection = LyricsCacheSelection.MANUAL
            )
        )
    }

    private fun decodeResult(value: JSONObject): LyricsResult? {
        val lyrics = cleanLyrics(value.optString("lyrics"))
        if (classifyLyrics(lyrics) != LyricsKind.SYNCHRONIZED) return null
        return LyricsResult(
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
        )
    }

    private fun emptyStats() = LyricsCacheStats(0, 0, 0L, LyricsCachePolicy.MAX_BYTES)

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

    private data class ManualCachedEntry(
        val identity: LyricsPlaybackIdentity,
        val entry: Entry
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
            createManualTable(database)
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < AUTOMATIC_CACHE_IDENTITY_VERSION) {
                database.delete(TABLE_CACHE, null, null)
                database.delete(TABLE_META, null, null)
                database.execSQL(
                    "INSERT INTO $TABLE_META ($COLUMN_META_ID, $COLUMN_TOTAL_BYTES) VALUES (1, 0)"
                )
            }
            if (oldVersion < MANUAL_OVERRIDE_VERSION) createManualTable(database)
        }

        private fun createManualTable(database: SQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_MANUAL (
                    $COLUMN_KEY TEXT PRIMARY KEY NOT NULL,
                    $COLUMN_PAYLOAD TEXT NOT NULL,
                    $COLUMN_BYTE_SIZE INTEGER NOT NULL,
                    $COLUMN_UPDATED_AT INTEGER NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS $INDEX_MANUAL_UPDATED ON $TABLE_MANUAL " +
                    "($COLUMN_UPDATED_AT ASC)"
            )
        }
    }

    companion object {
        private const val LOG_TAG = "DesktopLyrics"
        private const val DATABASE_NAME = "lyrics-cache.db"
        private const val DATABASE_VERSION = 4
        private const val AUTOMATIC_CACHE_IDENTITY_VERSION = 3
        private const val MANUAL_OVERRIDE_VERSION = 4
        private const val TABLE_CACHE = "lyrics_cache"
        private const val TABLE_MANUAL = "lyrics_manual_override"
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
        private const val INDEX_MANUAL_UPDATED = "index_lyrics_manual_override_updated"
        private const val EVICTION_BATCH_SIZE = 256
        private const val MANUAL_ENTRY_LIMIT = 128
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
