package com.tcrrry.desktoplyrics

import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Resolves lyrics directly from public music services. No request is routed through
 * a Lobsta/Tcrrry-owned server. MediaSession artwork remains the preferred cover;
 * QQ Music and NetEase artwork are only used when the player did not publish one.
 */
class DirectLyricsRepository {
    data class Result(
        val lyrics: String = "",
        val durationMs: Long = 0L,
        val cover: String = "",
        val source: String = "",
        val sourceId: String = "",
        val candidateTrack: String = "",
        val candidateArtist: String = "",
        val candidateAlbum: String = "",
        val lyricsKind: LyricsKind = LyricsKind.NONE
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("lyrics", lyrics)
            .put("duration", durationMs)
            .put("cover", cover)
            .put("source", source)
            .put("sourceId", sourceId)
            .put("candidateTrack", candidateTrack)
            .put("candidateArtist", candidateArtist)
            .put("candidateAlbum", candidateAlbum)
    }

    private val executor = Executors.newFixedThreadPool(6) { runnable ->
        Thread(runnable, "direct-lyrics").apply { isDaemon = true }
    }

    fun resolveLyrics(
        track: String,
        artist: String,
        album: String = "",
        durationMs: Long = 0L
    ): Result {
        val query = LyricsLookup(track, artist, album, durationMs)
        if (!LyricsCandidateSelector.canConfirm(query)) return Result()
        val startedAtNanos = System.nanoTime()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LYRICS_DEADLINE_MS)
        val catalogDeadline = minOf(
            deadline,
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CATALOG_DEADLINE_MS)
        )
        val exactFuture = executor.submit(Callable {
            runCatching { getLrcLibExact(query, catalogDeadline) }
                .onFailure { Log.w(LOG_TAG, "LRCLIB exact lookup failed", it) }
                .getOrNull()
        })
        val qqFuture = executor.submit(Callable {
            runCatching { searchQqMusic(query, catalogDeadline) }
                .onFailure { Log.w(LOG_TAG, "QQ Music catalog query failed", it) }
                .getOrDefault(emptyList())
        })
        val netEaseFuture = executor.submit(Callable {
            runCatching { searchNetEase(query, catalogDeadline) }
                .onFailure { Log.w(LOG_TAG, "NetEase catalog query failed", it) }
                .getOrDefault(emptyList())
        })

        val exactCandidate = awaitUntil(exactFuture, catalogDeadline)
        val exact = exactCandidate?.let { candidate ->
            LyricsCandidateSelector.selectCandidates(query, listOf(candidate)).firstOrNull()
        }
        if (exact != null) {
            qqFuture.cancel(true)
            netEaseFuture.cancel(true)
            Log.i(
                LOG_TAG,
                "Lyrics path=lrclib-exact selected=${exact.source} " +
                    "elapsedMs=${elapsedMs(startedAtNanos)}"
            )
            return exact
        }

        val candidates = buildList {
            exactCandidate?.let(::add)
            addAll(awaitUntil(qqFuture, catalogDeadline).orEmpty())
            addAll(awaitUntil(netEaseFuture, catalogDeadline).orEmpty())
        }
        exactFuture.cancel(true)
        val confirmed = LyricsCandidateSelector.selectCandidates(query, candidates)
        val selected = loadConfirmedLyrics(confirmed, deadline)
        if (selected != null) {
            Log.i(
                LOG_TAG,
                "Lyrics path=catalog catalogCandidates=${candidates.size} " +
                    "confirmed=${confirmed.size} selected=${selected.source} " +
                    "elapsedMs=${elapsedMs(startedAtNanos)}"
            )
            return selected
        }

        val fallbackCandidates = runCatching { searchLrcLib(query, deadline) }
            .onFailure { Log.w(LOG_TAG, "LRCLIB fallback search failed", it) }
            .getOrDefault(emptyList())
        val fallbackConfirmed = LyricsCandidateSelector.selectCandidates(query, fallbackCandidates)
        val fallbackSelected = loadConfirmedLyrics(fallbackConfirmed, deadline)
        Log.i(
            LOG_TAG,
            "Lyrics path=lrclib-fallback catalogCandidates=${candidates.size} " +
                "confirmed=${confirmed.size} fallbackCandidates=${fallbackCandidates.size} " +
                "fallbackConfirmed=${fallbackConfirmed.size} " +
                "selected=${fallbackSelected?.source ?: "none"} " +
                "elapsedMs=${elapsedMs(startedAtNanos)}"
        )
        return fallbackSelected ?: Result()
    }

    fun resolveCover(track: String, artist: String): String {
        val query = LyricsLookup(track, artist)
        if (!LyricsCandidateSelector.canFindCover(query)) return ""
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COVER_DEADLINE_MS)
        val candidates = collectCatalogCandidates(
            deadline,
            listOf(
                Callable { searchQqMusic(query, deadline) },
                Callable { searchNetEase(query, deadline) }
            )
        )
        return LyricsCandidateSelector.selectCoverCandidate(query, candidates)?.cover.orEmpty()
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun collectCatalogCandidates(
        deadlineNanos: Long,
        tasks: List<Callable<List<Result>>>
    ): List<Result> {
        val completion = ExecutorCompletionService<List<Result>>(executor)
        val futures = tasks.map(completion::submit)
        val candidates = mutableListOf<Result>()

        try {
            var completed = 0
            while (completed < futures.size) {
                val remaining = deadlineNanos - System.nanoTime()
                if (remaining <= 0L) break
                val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                completed += 1
                candidates += runCatching { future.get() }
                    .onFailure { Log.w(LOG_TAG, "Lyrics catalog query failed", it) }
                    .getOrDefault(emptyList())
            }
        } finally {
            futures.forEach { it.cancel(true) }
        }
        return candidates
    }

    private fun getLrcLibExact(query: LyricsLookup, deadlineNanos: Long): Result? {
        val terms = LyricsCandidateSelector.lrcLibExactTerms(query)
        val url = buildString {
            append("https://lrclib.net/api/get")
            append("?track_name=${encode(terms.track)}")
            append("&artist_name=${encode(terms.artist)}")
            if (terms.album.isNotBlank()) append("&album_name=${encode(terms.album)}")
            append("&duration=${encode((terms.durationMs / 1000.0).toString())}")
        }
        val item = try {
            JSONObject(getText(url, mapOf("Accept" to "application/json"), deadlineNanos))
        } catch (error: HttpStatusException) {
            if (error.statusCode == HttpURLConnection.HTTP_NOT_FOUND) return null
            throw error
        }
        return lrcLibResult(item)
    }

    private fun searchLrcLib(query: LyricsLookup, deadlineNanos: Long): List<Result> {
        val terms = LyricsCandidateSelector.searchTerms(query)
        val url = "https://lrclib.net/api/search" +
            "?track_name=${encode(terms.track)}&artist_name=${encode(terms.artist)}"
        val list = JSONArray(getText(url, mapOf("Accept" to "application/json"), deadlineNanos))
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                lrcLibResult(item)?.let(::add)
            }
        }
    }

    private fun lrcLibResult(item: JSONObject): Result? {
        val lyrics = cleanLyrics(item.contentString("syncedLyrics"))
        if (classifyLyrics(lyrics) != LyricsKind.SYNCHRONIZED) return null
        return Result(
            lyrics = lyrics,
            durationMs = (item.optDouble("duration", 0.0) * 1000.0).toLong(),
            source = SOURCE_LRCLIB,
            sourceId = item.contentString("id"),
            candidateTrack = item.contentString("trackName"),
            candidateArtist = item.contentString("artistName"),
            candidateAlbum = item.contentString("albumName"),
            lyricsKind = LyricsKind.SYNCHRONIZED
        )
    }

    private fun searchQqMusic(query: LyricsLookup, deadlineNanos: Long): List<Result> {
        val terms = LyricsCandidateSelector.searchTerms(query)
        val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp" +
            "?format=json&p=1&n=8&w=${encode("${terms.track} ${terms.artist}".trim())}"
        val root = JSONObject(getText(searchUrl, qqHeaders(), deadlineNanos))
        val songs = root.optJSONObject("data")
            ?.optJSONObject("song")
            ?.optJSONArray("list") ?: return emptyList()
        return buildList {
            for (index in 0 until songs.length()) {
                val song = songs.optJSONObject(index) ?: continue
                val songMid = song.contentString("songmid")
                if (songMid.isBlank()) continue
                add(
                    Result(
                        durationMs = song.optLong("interval", 0L) * 1000L,
                        cover = qqCover(song),
                        source = SOURCE_QQ,
                        sourceId = songMid,
                        candidateTrack = song.contentString("songname").ifBlank {
                            song.contentString("songorig")
                        },
                        candidateArtist = song.optJSONArray("singer").joinNames("name"),
                        candidateAlbum = song.contentString("albumname")
                    )
                )
            }
        }
    }

    private fun searchNetEase(query: LyricsLookup, deadlineNanos: Long): List<Result> {
        val terms = LyricsCandidateSelector.searchTerms(query)
        val searchUrl = "https://music.163.com/api/search/get/web" +
            "?type=1&limit=8&s=${encode("${terms.track} ${terms.artist}".trim())}"
        val root = JSONObject(getText(searchUrl, netEaseHeaders(), deadlineNanos))
        val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        return buildList {
            for (index in 0 until songs.length()) {
                val song = songs.optJSONObject(index) ?: continue
                val songId = song.optLong("id", 0L)
                if (songId <= 0L) continue
                val album = song.optJSONObject("album") ?: song.optJSONObject("al")
                add(
                    Result(
                        durationMs = song.optLong("duration", song.optLong("dt", 0L)),
                        cover = album.contentString("picUrl"),
                        source = SOURCE_NETEASE,
                        sourceId = songId.toString(),
                        candidateTrack = song.contentString("name"),
                        candidateArtist = (song.optJSONArray("artists") ?: song.optJSONArray("ar"))
                            .joinNames("name"),
                        candidateAlbum = album.contentString("name")
                    )
                )
            }
        }
    }

    private fun loadConfirmedLyrics(candidates: List<Result>, deadlineNanos: Long): Result? {
        return firstResolvedLyricsCandidate(candidates) { candidate ->
            if (deadlineNanos - System.nanoTime() <= 0L) return@firstResolvedLyricsCandidate null
            runCatching { loadCandidateLyrics(candidate, deadlineNanos) }
                .onFailure { Log.w(LOG_TAG, "Lyrics source lookup failed", it) }
                .getOrNull()
        }
    }

    private fun loadCandidateLyrics(candidate: Result, deadlineNanos: Long): Result? = when (candidate.source) {
        SOURCE_LRCLIB -> candidate.takeIf { classifyLyrics(it.lyrics) == LyricsKind.SYNCHRONIZED }
        SOURCE_QQ -> loadQqLyrics(candidate, deadlineNanos)
        SOURCE_NETEASE -> loadNetEaseLyrics(candidate, deadlineNanos)
        else -> null
    }

    private fun loadQqLyrics(candidate: Result, deadlineNanos: Long): Result? {
        if (candidate.sourceId.isBlank()) return null
        val lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
            "?songmid=${encode(candidate.sourceId)}&format=json&nobase64=1"
        val lyricRoot = parseJsonFlexible(getBytes(lyricUrl, qqHeaders(), deadlineNanos)) ?: return null
        val lyrics = cleanLyrics(unescapeHtml(lyricRoot.contentString("lyric")))
        return candidate.takeIf { classifyLyrics(lyrics) == LyricsKind.SYNCHRONIZED }
            ?.copy(lyrics = lyrics, lyricsKind = LyricsKind.SYNCHRONIZED)
    }

    private fun loadNetEaseLyrics(candidate: Result, deadlineNanos: Long): Result? {
        if (candidate.sourceId.isBlank()) return null
        val lyricUrl = "https://music.163.com/api/song/lyric?os=pc&id=${encode(candidate.sourceId)}" +
            "&lv=-1&kv=-1&tv=-1"
        val lyricRoot = JSONObject(getText(lyricUrl, netEaseHeaders(), deadlineNanos))
        val lyrics = cleanLyrics(lyricRoot.optJSONObject("lrc").contentString("lyric"))
        return candidate.takeIf { classifyLyrics(lyrics) == LyricsKind.SYNCHRONIZED }
            ?.copy(lyrics = lyrics, lyricsKind = LyricsKind.SYNCHRONIZED)
    }

    private fun qqCover(song: JSONObject): String {
        val albumMid = song.contentString("albummid")
        val albumId = song.optLong("albumid", 0L)
        return when {
            albumMid.isNotBlank() && !albumMid.all(Char::isDigit) ->
                "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albumMid.jpg"
            albumId > 0L ->
                "https://y.gtimg.cn/music/photo/album_500/${albumId % 100}/500_albumpic_${albumId}_0.jpg"
            else -> ""
        }
    }

    private fun qqHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Referer" to "https://y.qq.com/",
        "User-Agent" to USER_AGENT
    )

    private fun netEaseHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Referer" to "https://music.163.com/",
        "User-Agent" to USER_AGENT
    )

    private fun JSONArray?.joinNames(key: String): String {
        if (this == null) return ""
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.contentString(key)?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }.joinToString("/")
    }

    private fun getText(
        url: String,
        headers: Map<String, String>,
        deadlineNanos: Long? = null
    ): String = getBytes(url, headers, deadlineNanos).toString(Charsets.UTF_8)

    private fun getBytes(
        url: String,
        headers: Map<String, String>,
        deadlineNanos: Long? = null
    ): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            val remainingMs = remainingTimeoutMs(deadlineNanos)
            connection.requestMethod = "GET"
            connection.connectTimeout = minOf(CONNECT_TIMEOUT_MS, remainingMs)
            connection.readTimeout = minOf(READ_TIMEOUT_MS, remainingMs)
            connection.instanceFollowRedirects = true
            connection.useCaches = true
            headers.forEach(connection::setRequestProperty)
            val status = connection.responseCode
            if (status !in 200..299) throw HttpStatusException(status)
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    remainingTimeoutMs(deadlineNanos)
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) throw IllegalStateException("Response too large")
                    output.write(buffer, 0, read)
                }
                return output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun remainingTimeoutMs(deadlineNanos: Long?): Int {
        if (deadlineNanos == null) return READ_TIMEOUT_MS
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0L) throw IllegalStateException("Lyrics lookup deadline reached")
        return TimeUnit.NANOSECONDS.toMillis(remaining)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun parseJsonFlexible(bytes: ByteArray): JSONObject? {
        return runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrElse {
            runCatching { JSONObject(bytes.toString(Charset.forName("GBK"))) }.getOrNull()
        }
    }

    private fun unescapeHtml(value: String): String = value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun <T> awaitUntil(future: Future<T>, deadlineNanos: Long): T? {
        return runCatching {
            if (future.isDone) {
                future.get()
            } else {
                val remaining = deadlineNanos - System.nanoTime()
                if (remaining <= 0L) return null
                future.get(remaining, TimeUnit.NANOSECONDS)
            }
        }.getOrNull()
    }

    private fun elapsedMs(startedAtNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

    private class HttpStatusException(val statusCode: Int) :
        IllegalStateException("HTTP $statusCode")

    companion object {
        private const val LOG_TAG = "DesktopLyrics"
        private const val SOURCE_LRCLIB = "LRCLIB"
        private const val SOURCE_QQ = "QQ音乐"
        private const val SOURCE_NETEASE = "网易云音乐"
        private const val CONNECT_TIMEOUT_MS = 1_500
        private const val READ_TIMEOUT_MS = 2_200
        private const val CATALOG_DEADLINE_MS = 1_800L
        private const val LYRICS_DEADLINE_MS = 3_800L
        private const val COVER_DEADLINE_MS = 3_000L
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
    }
}

internal data class LyricsLookup(
    val track: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L
)

internal data class LyricsSearchTerms(
    val track: String,
    val artist: String
)

internal data class LrcLibExactLookupTerms(
    val track: String,
    val artist: String,
    val album: String,
    val durationMs: Long
)

enum class LyricsKind {
    NONE,
    PLAIN,
    SYNCHRONIZED
}

internal object LyricsCandidateSelector {
    private const val MINIMUM_DURATION_MS = 1_000L
    internal const val MAX_DURATION_DELTA_MS = 2_000L

    fun canConfirm(query: LyricsLookup): Boolean =
        hasKnownDuration(query.durationMs) &&
            titleIdentity(query.track).isValid &&
            isValidArtist(query.artist)

    fun hasKnownDuration(durationMs: Long): Boolean = durationMs >= MINIMUM_DURATION_MS

    fun hasMatchingDuration(firstDurationMs: Long, secondDurationMs: Long): Boolean =
        hasKnownDuration(firstDurationMs) &&
            hasKnownDuration(secondDurationMs) &&
            abs(firstDurationMs - secondDurationMs) <= MAX_DURATION_DELTA_MS

    fun searchTerms(query: LyricsLookup): LyricsSearchTerms {
        val title = titleIdentity(query.track)
        return LyricsSearchTerms(
            track = title.searchText.ifBlank { query.track.trim() },
            artist = query.artist.trim()
        )
    }

    fun lrcLibExactTerms(query: LyricsLookup): LrcLibExactLookupTerms {
        val search = searchTerms(query)
        val declaredArtists = artistNames(query.artist).toSet()
        val missingFeaturedArtists = featuredArtistDisplayNames(query.track)
            .filterNot { normalizeText(it) in declaredArtists }
        return LrcLibExactLookupTerms(
            track = search.track,
            artist = (listOf(search.artist) + missingFeaturedArtists)
                .filter(String::isNotBlank)
                .distinctBy(::normalizeText)
                .joinToString(", "),
            album = query.album.trim().takeIf { normalizedAlbum(it).isNotBlank() }.orEmpty(),
            durationMs = query.durationMs
        )
    }

    fun selectCandidates(
        query: LyricsLookup,
        candidates: Iterable<DirectLyricsRepository.Result>
    ): List<DirectLyricsRepository.Result> {
        if (!canConfirm(query)) return emptyList()
        val uniqueCandidates = candidates.asSequence()
            .distinctBy(::candidateIdentity)
            .toList()
        val queryTitle = titleIdentity(query.track)
        val eligibleCandidates = uniqueCandidates.asSequence()
            .mapNotNull { candidate -> candidateMetadata(query, candidate) }
            .toList()
        val eligibleEvidence = eligibleCandidates.mapNotNull { metadata ->
            val directTitleEvidence = titleEvidence(queryTitle, metadata.title)
            val effectiveTitleEvidence = when {
                directTitleEvidence.isConfirmed() -> directTitleEvidence
                hasIndependentTitleBridge(queryTitle, metadata, eligibleCandidates) ->
                    EvidenceLevel.NEAR
                else -> return@mapNotNull null
            }
            candidateEvidence(query, metadata.candidate, effectiveTitleEvidence)
        }
        val matched = eligibleEvidence.asSequence()
            .map { evidence ->
                evidence.copy(
                    supportingSources = supportingSourceCount(
                        evidence,
                        eligibleEvidence
                    )
                )
            }
            .filter(CandidateEvidence::isConfirmed)
            .sortedWith(
                compareBy<CandidateEvidence> { it.titleAnnotationRank }
                    .thenBy(CandidateEvidence::confirmationRank)
                    .thenByDescending { it.supportingSources }
                    .thenBy { abs(query.durationMs - it.candidate.durationMs) }
                    .thenBy { it.candidate.source }
                    .thenBy { it.candidate.sourceId }
            )
            .map(CandidateEvidence::candidate)
            .toList()
        return matched
    }

    fun matchesVersion(query: LyricsLookup, candidate: DirectLyricsRepository.Result): Boolean {
        if (!canConfirm(query)) return false
        val metadata = candidateMetadata(query, candidate) ?: return false
        val directTitleEvidence = titleEvidence(titleIdentity(query.track), metadata.title)
        if (!directTitleEvidence.isConfirmed()) return false
        val evidence = candidateEvidence(query, candidate, directTitleEvidence)
        return evidence.copy(supportingSources = 1).isConfirmed()
    }

    fun canFindCover(query: LyricsLookup): Boolean =
        titleIdentity(query.track).isValid && isValidArtist(query.artist)

    fun selectCoverCandidate(
        query: LyricsLookup,
        candidates: Iterable<DirectLyricsRepository.Result>
    ): DirectLyricsRepository.Result? {
        if (!canFindCover(query)) return null
        val matched = candidates.asSequence()
            .filter { it.cover.isNotBlank() }
            .filter { titlesMatch(query.track, it.candidateTrack) }
            .filter { artistEvidence(query, it).isConfirmed() }
            .distinctBy(::candidateIdentity)
            .sortedWith(
                compareBy<DirectLyricsRepository.Result> { it.source }
                    .thenBy { it.sourceId }
            )
            .toList()
        val anchor = matched.firstOrNull() ?: return null
        return anchor.takeIf { matched.all { candidate -> coversSameRelease(anchor, candidate) } }
    }

    private fun coversSameRelease(
        first: DirectLyricsRepository.Result,
        second: DirectLyricsRepository.Result
    ): Boolean =
        titlesMatch(first.candidateTrack, second.candidateTrack) && sameRelease(first, second)

    private fun sameRelease(
        first: DirectLyricsRepository.Result,
        second: DirectLyricsRepository.Result
    ): Boolean {
        return if (normalizedAlbum(first.candidateAlbum).isNotBlank() &&
            normalizedAlbum(second.candidateAlbum).isNotBlank()
        ) {
            albumEvidence(first.candidateAlbum, second.candidateAlbum).isConfirmed()
        } else {
            artistEvidence(
                first.candidateTrack,
                first.candidateArtist,
                second.candidateTrack,
                second.candidateArtist
            ).isConfirmed()
        }
    }

    private fun candidateMetadata(
        query: LyricsLookup,
        candidate: DirectLyricsRepository.Result
    ): CandidateMetadata? {
        if (!hasKnownDuration(candidate.durationMs) ||
            !hasMatchingDuration(query.durationMs, candidate.durationMs) ||
            !isValidArtist(candidate.candidateArtist)
        ) {
            return null
        }
        val wantedTitle = titleIdentity(query.track)
        val foundTitle = titleIdentity(candidate.candidateTrack)
        if (!wantedTitle.isValid || !foundTitle.isValid ||
            !versionsMatch(wantedTitle, foundTitle)
        ) {
            return null
        }
        return CandidateMetadata(candidate, foundTitle)
    }

    private fun hasIndependentTitleBridge(
        queryTitle: TitleIdentity,
        candidate: CandidateMetadata,
        candidates: List<CandidateMetadata>
    ): Boolean = candidates.any { peer ->
        peer.candidate.source.isNotBlank() &&
            peer.candidate.source != candidate.candidate.source &&
            titleEvidence(queryTitle, peer.title).isConfirmed() &&
            titleEvidence(candidate.title, peer.title).isConfirmed() &&
            hasMatchingDuration(candidate.candidate.durationMs, peer.candidate.durationMs) &&
            (
                artistEvidence(
                    candidate.candidate.candidateTrack,
                    candidate.candidate.candidateArtist,
                    peer.candidate.candidateTrack,
                    peer.candidate.candidateArtist
                ).isConfirmed() ||
                    albumEvidence(
                        candidate.candidate.candidateAlbum,
                        peer.candidate.candidateAlbum
                    ).isConfirmed()
                )
    }

    private fun candidateEvidence(
        query: LyricsLookup,
        candidate: DirectLyricsRepository.Result,
        titleEvidence: EvidenceLevel
    ): CandidateEvidence {

        return CandidateEvidence(
            candidate = candidate,
            titleEvidence = titleEvidence,
            artistEvidence = artistEvidence(query, candidate),
            albumEvidence = albumEvidence(query.album, candidate.candidateAlbum),
            titleAnnotationRank = titleAnnotationRank(query, candidate)
        )
    }

    private fun supportingSourceCount(
        evidence: CandidateEvidence,
        candidates: List<CandidateEvidence>
    ): Int {
        val sources = candidates.asSequence()
            .filter { peer -> candidatesDescribeSameRecording(evidence.candidate, peer.candidate) }
            .map { peer -> peer.candidate.source }
            .filter(String::isNotBlank)
            .distinct()
            .count()
        return sources.coerceAtLeast(1)
    }

    private fun candidatesDescribeSameRecording(
        first: DirectLyricsRepository.Result,
        second: DirectLyricsRepository.Result
    ): Boolean {
        if (!hasMatchingDuration(first.durationMs, second.durationMs) ||
            !isValidArtist(first.candidateArtist) ||
            !isValidArtist(second.candidateArtist)
        ) {
            return false
        }
        val firstTitle = titleIdentity(first.candidateTrack)
        val secondTitle = titleIdentity(second.candidateTrack)
        if (!firstTitle.isValid || !secondTitle.isValid ||
            !versionsMatch(firstTitle, secondTitle) ||
            titleEvidence(firstTitle, secondTitle) == EvidenceLevel.DIFFERENT
        ) {
            return false
        }
        return artistEvidence(
            first.candidateTrack,
            first.candidateArtist,
            second.candidateTrack,
            second.candidateArtist
        ).isConfirmed() || albumEvidence(
            first.candidateAlbum,
            second.candidateAlbum
        ).isConfirmed()
    }

    private fun titleAnnotationRank(
        query: LyricsLookup,
        candidate: DirectLyricsRepository.Result
    ): Int {
        val wanted = titleIdentity(query.track)
        val found = titleIdentity(candidate.candidateTrack)
        val wantedArtists = artistIdentity(query.track, query.artist).effective
        val foundArtists = artistIdentity(candidate.candidateTrack, candidate.candidateArtist).effective
        val featuredExact = wanted.featuredArtists.all(foundArtists::contains) &&
            found.featuredArtists.all(wantedArtists::contains)
        if (wanted.annotations == found.annotations && featuredExact) return 0

        val wantedHasAnnotations = wanted.annotations.isNotEmpty() ||
            wanted.featuredArtists.isNotEmpty()
        val foundHasAnnotations = found.annotations.isNotEmpty() ||
            found.featuredArtists.isNotEmpty()
        if (!wantedHasAnnotations || !foundHasAnnotations) return 1

        val featuredRelated = wanted.featuredArtists.any(foundArtists::contains) ||
            found.featuredArtists.any(wantedArtists::contains)
        val versionRelated = wanted.versionQualifiers.isNotEmpty() &&
            wanted.versionQualifiers == found.versionQualifiers
        val annotationRelated = wanted.annotationTokens.any(found.annotationTokens::contains)
        return if (featuredRelated || versionRelated || annotationRelated) 1 else 2
    }

    private fun albumEvidence(first: String, second: String): EvidenceLevel {
        val firstAlbum = normalizedAlbum(first)
        val secondAlbum = normalizedAlbum(second)
        if (firstAlbum.isBlank() || secondAlbum.isBlank()) return EvidenceLevel.UNKNOWN
        if (firstAlbum == secondAlbum) return EvidenceLevel.EXACT
        if (nearMetadataText(first, second)) return EvidenceLevel.NEAR
        return if (haveDisjointScripts(firstAlbum, secondAlbum)) {
            EvidenceLevel.UNKNOWN
        } else {
            EvidenceLevel.DIFFERENT
        }
    }

    private fun titlesMatch(first: String, second: String): Boolean {
        val firstTitle = titleIdentity(first)
        val secondTitle = titleIdentity(second)
        return firstTitle.isValid && secondTitle.isValid &&
            versionsMatch(firstTitle, secondTitle) &&
            titleEvidence(firstTitle, secondTitle).isConfirmed()
    }

    private fun versionsMatch(first: TitleIdentity, second: TitleIdentity): Boolean =
        first.versionQualifiers == second.versionQualifiers

    private fun titleEvidence(first: TitleIdentity, second: TitleIdentity): EvidenceLevel {
        if (!first.isValid || !second.isValid) return EvidenceLevel.UNKNOWN
        if (first.base == second.base) return EvidenceLevel.EXACT
        val firstBases = first.alternateBases + first.base
        val secondBases = second.alternateBases + second.base
        return if (firstBases.any(secondBases::contains)) {
            EvidenceLevel.NEAR
        } else {
            EvidenceLevel.DIFFERENT
        }
    }

    private fun titleIdentity(value: String): TitleIdentity {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        val versionQualifiers = mutableSetOf<String>()
        val annotations = mutableSetOf<String>()
        val annotationTokens = mutableSetOf<String>()
        val featuredArtists = mutableSetOf<String>()
        val bracketIdentities = BRACKET_PATTERN.findAll(normalized).map { match ->
            val content = match.groupValues[1].trim()
            val featured = extractFeaturedArtists(content)
            val versions = if (featured.isEmpty()) versionQualifiers(content) else emptySet()
            TitleAnnotationIdentity(content, featured, versions)
        }.toList()
        bracketIdentities.forEach { identity ->
            val content = identity.content
            val featured = identity.featuredArtists
            val versions = identity.versionQualifiers
            when {
                featured.isNotEmpty() -> featuredArtists += featured
                versions.isNotEmpty() -> versionQualifiers += versions
            }
            if (featured.isEmpty()) {
                normalizeText(content)
                    .takeIf(String::isNotBlank)
                    ?.let(annotations::add)
                annotationTokens += annotationTokens(content)
            }
        }
        val identityIterator = bracketIdentities.iterator()
        val searchText = BRACKET_PATTERN.replace(normalized) {
            val identity = identityIterator.next()
            if (identity.versionQualifiers.isEmpty()) " " else " ${identity.content} "
        }
            .replace(WHITESPACE_PATTERN, " ")
            .trim()
        val base = normalizeText(normalized.replace(BRACKET_PATTERN, " "))
        val alternateBases = bracketIdentities.asSequence()
            .filter { it.featuredArtists.isEmpty() && it.versionQualifiers.isEmpty() }
            .map { normalizeText(it.content) }
            .filter(String::isNotBlank)
            .filter { alternate -> haveDisjointScripts(base, alternate) }
            .toSet()
        return TitleIdentity(
            base = base,
            alternateBases = alternateBases,
            searchText = searchText,
            versionQualifiers = versionQualifiers,
            annotations = annotations,
            annotationTokens = annotationTokens,
            featuredArtists = featuredArtists
        )
    }

    private fun artistNames(value: String): List<String> = value
        .split(ARTIST_SEPARATOR_PATTERN)
        .map(::normalizeText)
        .filter(String::isNotBlank)

    private fun artistEvidence(
        query: LyricsLookup,
        candidate: DirectLyricsRepository.Result
    ): EvidenceLevel = artistEvidence(
        query.track,
        query.artist,
        candidate.candidateTrack,
        candidate.candidateArtist
    )

    private fun artistEvidence(
        firstTrack: String,
        firstArtist: String,
        secondTrack: String,
        secondArtist: String
    ): EvidenceLevel {
        if (!isValidArtist(firstArtist) || !isValidArtist(secondArtist)) {
            return EvidenceLevel.UNKNOWN
        }
        val first = artistIdentity(firstTrack, firstArtist)
        val second = artistIdentity(secondTrack, secondArtist)
        if (first.effective == second.effective) return EvidenceLevel.EXACT
        if (first.declared == second.declared) return EvidenceLevel.NEAR

        if (first.effective.containsAll(second.effective)) {
            val omitted = first.effective - second.effective
            if (omitted.isNotEmpty() && omitted.all(first.featured::contains)) {
                return EvidenceLevel.NEAR
            }
        }
        if (second.effective.containsAll(first.effective)) {
            val omitted = second.effective - first.effective
            if (omitted.isNotEmpty() && omitted.all(second.featured::contains)) {
                return EvidenceLevel.NEAR
            }
        }
        return if (haveDisjointScripts(firstArtist, secondArtist)) {
            EvidenceLevel.UNKNOWN
        } else {
            EvidenceLevel.DIFFERENT
        }
    }

    private fun artistIdentity(track: String, artist: String): ArtistIdentity {
        val featured = titleIdentity(track).featuredArtists
        return ArtistIdentity(
            declared = artistNames(artist).toSet(),
            featured = featured
        )
    }

    private fun isValidArtist(value: String): Boolean = normalizeText(value).let {
        it.isNotBlank() && it !in PLACEHOLDER_ARTISTS
    }

    private fun normalizeText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .lowercase(Locale.ROOT)
        .replace(COMBINING_MARK_PATTERN, "")
        .replace(NON_LETTER_OR_DIGIT_PATTERN, "")

    private fun normalizedAlbum(value: String): String = normalizeText(value)
        .takeUnless { it in PLACEHOLDER_ALBUMS }
        .orEmpty()

    private fun nearMetadataText(first: String, second: String): Boolean {
        val firstTokens = metadataTokens(first)
        val secondTokens = metadataTokens(second)
        if (firstTokens.isEmpty() || secondTokens.isEmpty()) return false
        if (firstTokens == secondTokens) return true
        val (smaller, larger) = if (firstTokens.size <= secondTokens.size) {
            firstTokens to secondTokens
        } else {
            secondTokens to firstTokens
        }
        if (!larger.containsAll(smaller)) return false
        val extraTokens = larger - smaller
        return extraTokens.isNotEmpty() &&
            extraTokens.all { token -> token.length <= MAX_SHORT_METADATA_TOKEN_LENGTH }
    }

    private fun metadataTokens(value: String): Set<String> =
        Normalizer.normalize(value, Normalizer.Form.NFKD)
            .lowercase(Locale.ROOT)
            .replace(COMBINING_MARK_PATTERN, "")
            .split(NON_ALPHANUMERIC_PATTERN)
            .filter(String::isNotBlank)
            .toSet()

    private fun haveDisjointScripts(first: String, second: String): Boolean {
        val firstScripts = scripts(first)
        val secondScripts = scripts(second)
        return firstScripts.isNotEmpty() && secondScripts.isNotEmpty() &&
            firstScripts.intersect(secondScripts).isEmpty()
    }

    private fun scripts(value: String): Set<Character.UnicodeScript> = value.asSequence()
        .filter(Char::isLetter)
        .map { character -> Character.UnicodeScript.of(character.code) }
        .filterNot { script ->
            script == Character.UnicodeScript.COMMON ||
                script == Character.UnicodeScript.INHERITED
        }
        .toSet()

    private fun extractFeaturedArtists(value: String): Set<String> =
        FEATURED_ARTIST_PATTERN.matchEntire(value.trim())
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::artistNames)
            ?.toSet()
            .orEmpty()

    private fun featuredArtistDisplayNames(value: String): List<String> =
        BRACKET_PATTERN.findAll(value)
            .mapNotNull { match ->
                FEATURED_ARTIST_PATTERN.matchEntire(match.groupValues[1].trim())
                    ?.groupValues
                    ?.getOrNull(1)
            }
            .flatMap { featured -> featured.split(ARTIST_SEPARATOR_PATTERN).asSequence() }
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(::normalizeText)
            .toList()

    private fun versionQualifiers(value: String): Set<String> {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val qualifiers = VERSION_QUALIFIER_PATTERNS
            .mapNotNull { (qualifier, pattern) -> qualifier.takeIf { pattern.containsMatchIn(normalized) } }
            .toMutableSet()
        if (qualifiers.isEmpty()) {
            explicitVersionQualifier(normalized)?.let(qualifiers::add)
        }
        return qualifiers
    }

    private fun explicitVersionQualifier(value: String): String? {
        val explicitDescriptor = ENGLISH_VERSION_PATTERN.matchEntire(value)
            ?.groupValues
            ?.getOrNull(1)
            ?: CJK_VERSION_PATTERN.matchEntire(value)?.groupValues?.getOrNull(1)
        val descriptor = explicitDescriptor ?: value
        LANGUAGE_VERSION_ALIASES[normalizeText(descriptor)]?.let { language ->
            return "language:$language"
        }
        return explicitDescriptor
            ?.let(::normalizeText)
            ?.takeIf(String::isNotBlank)
            ?.let { "version:$it" }
    }

    private fun annotationTokens(value: String): Set<String> {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val words = normalized.split(NON_ALPHANUMERIC_PATTERN)
            .map(::normalizeText)
            .filter { it.length >= 2 && it !in ANNOTATION_STOP_WORDS }
        val cjkBigrams = CJK_SEQUENCE_PATTERN.findAll(normalized)
            .flatMap { it.value.windowed(2).asSequence() }
        return (words.asSequence() + cjkBigrams).toSet()
    }

    private fun candidateIdentity(candidate: DirectLyricsRepository.Result): String =
        if (candidate.source.isNotBlank() && candidate.sourceId.isNotBlank()) {
            "${candidate.source}\u0000${candidate.sourceId}"
        } else {
            listOf(
                candidate.candidateTrack,
                candidate.candidateArtist,
                candidate.candidateAlbum,
                candidate.durationMs.toString()
            ).joinToString("\u0000")
        }

    private data class TitleIdentity(
        val base: String,
        val alternateBases: Set<String>,
        val searchText: String,
        val versionQualifiers: Set<String>,
        val annotations: Set<String>,
        val annotationTokens: Set<String>,
        val featuredArtists: Set<String>
    ) {
        val isValid: Boolean get() = base.isNotBlank()
    }

    private data class TitleAnnotationIdentity(
        val content: String,
        val featuredArtists: Set<String>,
        val versionQualifiers: Set<String>
    )

    private data class ArtistIdentity(
        val declared: Set<String>,
        val featured: Set<String>
    ) {
        val effective: Set<String> = declared + featured
    }

    private data class CandidateEvidence(
        val candidate: DirectLyricsRepository.Result,
        val titleEvidence: EvidenceLevel,
        val artistEvidence: EvidenceLevel,
        val albumEvidence: EvidenceLevel,
        val titleAnnotationRank: Int,
        val supportingSources: Int = 1
    ) {
        fun isConfirmed(): Boolean {
            val directConfirmation = artistEvidence.isConfirmed() || albumEvidence.isConfirmed()
            val consensusConfirmation = supportingSources >= MINIMUM_SUPPORTING_SOURCES &&
                artistEvidence != EvidenceLevel.DIFFERENT &&
                albumEvidence != EvidenceLevel.DIFFERENT
            return titleEvidence.isConfirmed() && (directConfirmation || consensusConfirmation)
        }

        fun confirmationRank(): Int = when {
            artistEvidence == EvidenceLevel.EXACT && albumEvidence == EvidenceLevel.EXACT -> 0
            artistEvidence.isConfirmed() && albumEvidence.isConfirmed() -> 1
            artistEvidence.isConfirmed() -> 2
            albumEvidence.isConfirmed() -> 3
            supportingSources >= MINIMUM_SUPPORTING_SOURCES -> 4
            else -> 5
        }
    }

    private data class CandidateMetadata(
        val candidate: DirectLyricsRepository.Result,
        val title: TitleIdentity
    )

    private enum class EvidenceLevel {
        EXACT,
        NEAR,
        UNKNOWN,
        DIFFERENT;

        fun isConfirmed(): Boolean = this == EXACT || this == NEAR
    }

    private val BRACKET_PATTERN = Regex("[（(\\[【{]([^）)\\]】}]{1,80})[）)\\]】}]")
    private val WHITESPACE_PATTERN = Regex("\\s+")
    private val FEATURED_ARTIST_PATTERN = Regex(
        "^\\s*(?:feat(?:uring)?\\.?|ft\\.?|with)\\s*[:：-]?\\s*(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val ARTIST_SEPARATOR_PATTERN = Regex(
        "\\s*(?:[,，、/&／;；+＋]|\\bfeat(?:uring)?\\.?\\b|\\bft\\.?\\b|\\bwith\\b|\\band\\b)\\s*",
        RegexOption.IGNORE_CASE
    )
    private val VERSION_QUALIFIER_PATTERNS = listOf(
        "live" to Regex("\\blive\\b|现场|演唱会", RegexOption.IGNORE_CASE),
        "remix" to Regex("\\bremix\\b|混音", RegexOption.IGNORE_CASE),
        "remaster" to Regex("\\bremaster(?:ed)?\\b|重制", RegexOption.IGNORE_CASE),
        "acoustic" to Regex("\\bacoustic\\b|不插电|清唱", RegexOption.IGNORE_CASE),
        "instrumental" to Regex("\\binstrumental\\b|伴奏|纯音乐", RegexOption.IGNORE_CASE),
        "karaoke" to Regex("\\bkaraoke\\b|卡拉ok", RegexOption.IGNORE_CASE),
        "demo" to Regex("\\bdemo\\b|小样", RegexOption.IGNORE_CASE),
        "radio-edit" to Regex("\\bradio\\s*edit\\b|电台剪辑", RegexOption.IGNORE_CASE),
        "edit" to Regex("(?<!radio\\s)\\bedit\\b|剪辑版", RegexOption.IGNORE_CASE),
        "mix" to Regex("\\bmix\\b", RegexOption.IGNORE_CASE),
        "dj" to Regex("\\bdj\\b|dj版", RegexOption.IGNORE_CASE),
        "sped-up" to Regex("\\bsped\\s*up\\b|加速", RegexOption.IGNORE_CASE),
        "slowed" to Regex("\\bslowed(?:\\s*down)?\\b|慢速|降速", RegexOption.IGNORE_CASE),
        "nightcore" to Regex("\\bnightcore\\b", RegexOption.IGNORE_CASE),
        "mono" to Regex("\\bmono\\b|单声道", RegexOption.IGNORE_CASE),
        "stereo" to Regex("\\bstereo\\b|立体声", RegexOption.IGNORE_CASE),
        "cover" to Regex("\\bcover\\b|翻唱", RegexOption.IGNORE_CASE),
        "original" to Regex("^\\s*(?:original(?:\\s+(?:ver(?:sion)?\\.?))?|原版)\\s*$", RegexOption.IGNORE_CASE),
        "rerecorded" to Regex("\\bre-?recorded\\b|重新录制", RegexOption.IGNORE_CASE)
    )
    private val ENGLISH_VERSION_PATTERN = Regex(
        "^\\s*(.+?)\\s+ver(?:sion)?\\.?\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val CJK_VERSION_PATTERN = Regex("^\\s*(.+?)(?:版本|版)\\s*$")
    private val NON_ALPHANUMERIC_PATTERN = Regex("[^\\p{L}\\p{N}]+")
    private val NON_LETTER_OR_DIGIT_PATTERN = Regex("[^\\p{L}\\p{N}]")
    private val COMBINING_MARK_PATTERN = Regex("\\p{M}+")
    private val CJK_SEQUENCE_PATTERN = Regex("[\\u3400-\\u9fff\\uF900-\\uFAFF]+")
    private val ANNOTATION_STOP_WORDS = setOf("the", "and", "from", "with", "of")
    private const val MAX_SHORT_METADATA_TOKEN_LENGTH = 3
    private const val MINIMUM_SUPPORTING_SOURCES = 2
    private val LANGUAGE_DISPLAY_LOCALES = listOf(
        Locale.ENGLISH,
        Locale.SIMPLIFIED_CHINESE,
        Locale.TRADITIONAL_CHINESE
    )
    private val LANGUAGE_VERSION_ALIASES: Map<String, String> by lazy {
        buildMap {
            Locale.getISOLanguages().forEach { languageCode ->
                    val language = languageCode.lowercase(Locale.ROOT)
                    val locale = Locale(language)
                    put(normalizeText(language), language)
                    LANGUAGE_DISPLAY_LOCALES.forEach { displayLocale ->
                        normalizeText(locale.getDisplayLanguage(displayLocale))
                            .takeIf(String::isNotBlank)
                            ?.let { put(it, language) }
                    }
                }
            put("mandarin", "cmn")
            put("国语", "cmn")
            put("普通话", "cmn")
            put("华语", "cmn")
            put("cantonese", "yue")
            put("粤语", "yue")
            put("广东话", "yue")
        }
    }
    private val PLACEHOLDER_ARTISTS = setOf(
        "unknown",
        "unkown",
        "null",
        "undefined",
        "未知",
        "未知歌手"
    )
    private val PLACEHOLDER_ALBUMS = setOf(
        "unknown",
        "unkown",
        "null",
        "undefined",
        "未知",
        "未知专辑"
    )

}

internal fun firstResolvedLyricsCandidate(
    candidates: Iterable<DirectLyricsRepository.Result>,
    load: (DirectLyricsRepository.Result) -> DirectLyricsRepository.Result?
): DirectLyricsRepository.Result? {
    candidates.forEach { candidate ->
        load(candidate)?.let { return it }
    }
    return null
}

internal fun cleanLyrics(value: String?): String {
    val text = value?.trim().orEmpty()
    return if (text.equals("null", ignoreCase = true) ||
        text.equals("undefined", ignoreCase = true) ||
        text.equals("[object Object]", ignoreCase = true)
    ) {
        ""
    } else {
        text
    }
}

internal fun classifyLyrics(value: String?): LyricsKind {
    val lyrics = cleanLyrics(value)
    if (lyrics.isBlank()) return LyricsKind.NONE
    return if (TIMESTAMP_PATTERN.containsMatchIn(lyrics)) LyricsKind.SYNCHRONIZED else LyricsKind.PLAIN
}

private fun JSONObject?.contentString(key: String): String {
    val value = this?.opt(key)
    return cleanLyrics(value?.toString())
}

private val TIMESTAMP_PATTERN = Regex("\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?]")
