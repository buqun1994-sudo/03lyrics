package com.ninepointnine.desktoplyrics

internal enum class LyricsCatalogSearchKind {
    TITLE_ARTIST,
    TITLE_ALBUM,
    TITLE_ARTIST_ANCHOR,
    TITLE_ONLY
}

internal data class LyricsCatalogSearchRequest(
    val kind: LyricsCatalogSearchKind,
    val text: String
)

/**
 * Builds a bounded source-independent recall plan. Recording admission remains the selector's job.
 */
internal object LyricsSearchPlanner {
    private const val MAX_CATALOG_REQUESTS_PER_SOURCE = 3

    fun primaryTerms(query: LyricsLookup): LyricsSearchTerms {
        val title = titleIdentity(query.track)
        return LyricsSearchTerms(
            track = title.searchText.ifBlank { query.track.trim() },
            artist = query.artist.trim()
        )
    }

    fun lrcLibExactTerms(query: LyricsLookup): LrcLibExactLookupTerms {
        val search = primaryTerms(query)
        val declaredArtists = artistNames(query.artist).toSet()
        val missingFeaturedArtists = featuredArtistDisplayNames(query.track)
            .filterNot { normalizeText(it) in declaredArtists }
        return LrcLibExactLookupTerms(
            track = search.track,
            artist = (listOf(search.artist) + missingFeaturedArtists)
                .filter(String::isNotBlank)
                .distinctBy(::normalizeText)
                .joinToString(", "),
            album = normalizedAlbumQueryText(query.album),
            durationMs = query.durationMs
        )
    }

    fun catalogRequests(query: LyricsLookup): List<LyricsCatalogSearchRequest> {
        val primary = primaryTerms(query)
        val title = primary.track.trim()
        if (title.isBlank()) return emptyList()

        val album = normalizedAlbumQueryText(query.album)
        val artistAnchor = artistSearchAnchor(query.artist)
        return buildList {
            addRequest(LyricsCatalogSearchKind.TITLE_ARTIST, title, primary.artist)
            addRequest(LyricsCatalogSearchKind.TITLE_ALBUM, title, album)
            addRequest(LyricsCatalogSearchKind.TITLE_ARTIST_ANCHOR, title, artistAnchor.orEmpty())
            addRequest(LyricsCatalogSearchKind.TITLE_ONLY, title)
        }
            .distinctBy { request -> normalizeText(request.text) }
            .take(MAX_CATALOG_REQUESTS_PER_SOURCE)
    }

    private fun MutableList<LyricsCatalogSearchRequest>.addRequest(
        kind: LyricsCatalogSearchKind,
        vararg parts: String
    ) {
        if (parts.any(String::isBlank)) return
        val text = parts.joinToString(" ") { it.trim() }
        if (text.isNotBlank()) add(LyricsCatalogSearchRequest(kind, text))
    }
}
