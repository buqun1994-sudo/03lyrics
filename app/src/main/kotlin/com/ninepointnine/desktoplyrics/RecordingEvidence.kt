package com.ninepointnine.desktoplyrics

internal enum class EvidenceLevel {
    EXACT,
    NEAR,
    UNKNOWN,
    DIFFERENT;

    fun isConfirmed(): Boolean = this == EXACT || this == NEAR

    fun titleScore(): Int = when (this) {
        EXACT -> 4
        NEAR -> 3
        UNKNOWN -> 0
        DIFFERENT -> -5
    }

    fun artistScore(): Int = when (this) {
        EXACT -> 3
        NEAR -> 2
        UNKNOWN -> 0
        DIFFERENT -> -2
    }

    fun albumScore(): Int = when (this) {
        EXACT -> 3
        NEAR -> 2
        UNKNOWN -> 0
        DIFFERENT -> -1
    }
}

internal enum class EvidenceReason {
    NORMALIZED_EQUAL,
    DECLARED_ARTISTS_EQUAL,
    FEATURED_ARTIST_OMITTED,
    ALTERNATE_TITLE,
    CONTIGUOUS_SUBJECT,
    EDIT_SIMILARITY,
    RELEASE_CORE,
    METADATA_SIMILARITY,
    VERSION_OMITTED,
    VERSION_CONFLICT,
    CROSS_SCRIPT,
    MISSING_METADATA,
    TEXT_CONFLICT,
    INDEPENDENT_TITLE_BRIDGE
}

internal data class MetadataEvidence(
    val level: EvidenceLevel,
    val reason: EvidenceReason
) {
    fun isConfirmed(): Boolean = level.isConfirmed()
}

internal fun titleAnnotationRank(
    query: LyricsLookup,
    candidate: LyricsResult
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

internal fun albumEvidence(first: String, second: String): EvidenceLevel =
    albumMetadataEvidence(first, second).level

internal fun albumMetadataEvidence(first: String, second: String): MetadataEvidence {
    val firstAlbum = normalizedAlbum(first)
    val secondAlbum = normalizedAlbum(second)
    if (firstAlbum.isBlank() || secondAlbum.isBlank()) {
        return MetadataEvidence(EvidenceLevel.UNKNOWN, EvidenceReason.MISSING_METADATA)
    }
    if (firstAlbum == secondAlbum) {
        return MetadataEvidence(EvidenceLevel.EXACT, EvidenceReason.NORMALIZED_EQUAL)
    }
    val firstCore = normalizedAlbumCore(first)
    val secondCore = normalizedAlbumCore(second)
    if (firstCore.isNotBlank() && firstCore == secondCore) {
        return MetadataEvidence(EvidenceLevel.NEAR, EvidenceReason.RELEASE_CORE)
    }
    if (nearMetadataText(first, second)) {
        return MetadataEvidence(EvidenceLevel.NEAR, EvidenceReason.METADATA_SIMILARITY)
    }
    return if (haveDisjointScripts(firstAlbum, secondAlbum)) {
        MetadataEvidence(EvidenceLevel.UNKNOWN, EvidenceReason.CROSS_SCRIPT)
    } else {
        MetadataEvidence(EvidenceLevel.DIFFERENT, EvidenceReason.TEXT_CONFLICT)
    }
}

internal fun titlesMatch(first: String, second: String): Boolean {
    val firstTitle = titleIdentity(first)
    val secondTitle = titleIdentity(second)
    return firstTitle.isValid && secondTitle.isValid &&
        titleEvidence(firstTitle, secondTitle).isConfirmed()
}

internal fun versionEvidence(first: TitleIdentity, second: TitleIdentity): EvidenceLevel =
    versionMetadataEvidence(first, second).level

internal fun versionMetadataEvidence(
    first: TitleIdentity,
    second: TitleIdentity
): MetadataEvidence = when {
    first.versionQualifiers == second.versionQualifiers ->
        MetadataEvidence(EvidenceLevel.EXACT, EvidenceReason.NORMALIZED_EQUAL)
    first.versionQualifiers.isEmpty() || second.versionQualifiers.isEmpty() ->
        MetadataEvidence(EvidenceLevel.NEAR, EvidenceReason.VERSION_OMITTED)
    else -> MetadataEvidence(EvidenceLevel.DIFFERENT, EvidenceReason.VERSION_CONFLICT)
}

internal fun titleEvidence(first: TitleIdentity, second: TitleIdentity): EvidenceLevel =
    titleMetadataEvidence(first, second).level

internal fun titleMetadataEvidence(
    first: TitleIdentity,
    second: TitleIdentity
): MetadataEvidence {
    if (!first.isValid || !second.isValid) {
        return MetadataEvidence(EvidenceLevel.UNKNOWN, EvidenceReason.MISSING_METADATA)
    }
    val baseEvidence = when {
        first.base == second.base ->
            MetadataEvidence(EvidenceLevel.EXACT, EvidenceReason.NORMALIZED_EQUAL)
        else -> {
            val firstBases = first.alternateBases + first.base
            val secondBases = second.alternateBases + second.base
            when {
                firstBases.any(secondBases::contains) ->
                    MetadataEvidence(EvidenceLevel.NEAR, EvidenceReason.ALTERNATE_TITLE)
                hasContiguousSubjectRelation(first.baseSegments, second.baseSegments) ->
                    MetadataEvidence(EvidenceLevel.NEAR, EvidenceReason.CONTIGUOUS_SUBJECT)
                firstBases.any { firstBase ->
                    secondBases.any { secondBase ->
                        hasMinimumTextSimilarity(
                            firstBase,
                            secondBase,
                            MINIMUM_TITLE_SIMILARITY_PERCENT
                        )
                    }
                } -> MetadataEvidence(EvidenceLevel.NEAR, EvidenceReason.EDIT_SIMILARITY)
                haveDisjointScripts(first.base, second.base) ->
                    MetadataEvidence(EvidenceLevel.UNKNOWN, EvidenceReason.CROSS_SCRIPT)
                else -> MetadataEvidence(EvidenceLevel.DIFFERENT, EvidenceReason.TEXT_CONFLICT)
            }
        }
    }
    val versionEvidence = versionMetadataEvidence(first, second)
    return when {
        versionEvidence.level == EvidenceLevel.DIFFERENT -> versionEvidence
        baseEvidence.level == EvidenceLevel.DIFFERENT -> baseEvidence
        baseEvidence.level == EvidenceLevel.UNKNOWN -> baseEvidence
        baseEvidence.level == EvidenceLevel.NEAR -> baseEvidence
        versionEvidence.level == EvidenceLevel.NEAR -> versionEvidence
        else -> MetadataEvidence(EvidenceLevel.EXACT, EvidenceReason.NORMALIZED_EQUAL)
    }
}

internal fun artistEvidence(
    query: LyricsLookup,
    candidate: LyricsResult
): EvidenceLevel = artistEvidence(
    query.track,
    query.artist,
    candidate.candidateTrack,
    candidate.candidateArtist
)

internal fun artistEvidence(
    firstTrack: String,
    firstArtist: String,
    secondTrack: String,
    secondArtist: String
): EvidenceLevel = artistMetadataEvidence(
    firstTrack,
    firstArtist,
    secondTrack,
    secondArtist
).level

internal fun artistMetadataEvidence(
    firstTrack: String,
    firstArtist: String,
    secondTrack: String,
    secondArtist: String
): MetadataEvidence {
    if (!isValidArtist(firstArtist) || !isValidArtist(secondArtist)) {
        return MetadataEvidence(EvidenceLevel.UNKNOWN, EvidenceReason.MISSING_METADATA)
    }
    val first = artistIdentity(firstTrack, firstArtist)
    val second = artistIdentity(secondTrack, secondArtist)
    if (first.effective == second.effective) {
        return MetadataEvidence(EvidenceLevel.EXACT, EvidenceReason.NORMALIZED_EQUAL)
    }
    if (first.declared == second.declared) {
        return MetadataEvidence(EvidenceLevel.NEAR, EvidenceReason.DECLARED_ARTISTS_EQUAL)
    }

    if (first.effective.containsAll(second.effective)) {
        val omitted = first.effective - second.effective
        if (omitted.isNotEmpty() && omitted.all(first.featured::contains)) {
            return MetadataEvidence(EvidenceLevel.NEAR, EvidenceReason.FEATURED_ARTIST_OMITTED)
        }
    }
    if (second.effective.containsAll(first.effective)) {
        val omitted = second.effective - first.effective
        if (omitted.isNotEmpty() && omitted.all(second.featured::contains)) {
            return MetadataEvidence(EvidenceLevel.NEAR, EvidenceReason.FEATURED_ARTIST_OMITTED)
        }
    }
    if (hasContiguousArtistSubject(first, second)) {
        return MetadataEvidence(EvidenceLevel.NEAR, EvidenceReason.CONTIGUOUS_SUBJECT)
    }
    if (first.effective.any { firstName ->
            second.effective.any { secondName ->
                hasMinimumTextSimilarity(
                    firstName,
                    secondName,
                    MINIMUM_ARTIST_SIMILARITY_PERCENT
                )
            }
        }
    ) {
        return MetadataEvidence(EvidenceLevel.NEAR, EvidenceReason.EDIT_SIMILARITY)
    }
    return if (haveDisjointScripts(firstArtist, secondArtist)) {
        MetadataEvidence(EvidenceLevel.UNKNOWN, EvidenceReason.CROSS_SCRIPT)
    } else {
        MetadataEvidence(EvidenceLevel.DIFFERENT, EvidenceReason.TEXT_CONFLICT)
    }
}
