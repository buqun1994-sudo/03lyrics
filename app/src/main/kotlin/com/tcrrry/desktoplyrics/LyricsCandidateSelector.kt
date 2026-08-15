package com.tcrrry.desktoplyrics

import kotlin.math.abs

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
            album = normalizedAlbumQueryText(query.album),
            durationMs = query.durationMs
        )
    }

    fun selectCandidates(
        query: LyricsLookup,
        candidates: Iterable<LyricsResult>
    ): List<LyricsResult> = selectCandidatesWithProof(query, candidates)
        .map(LyricsCandidateSelection::candidate)

    fun selectCandidatesWithProof(
        query: LyricsLookup,
        candidates: Iterable<LyricsResult>
    ): List<LyricsCandidateSelection> = rankCandidates(query, candidates)
        .map(CandidateEvidence::selection)

    fun isProofValid(
        query: LyricsLookup,
        result: LyricsResult,
        proof: LyricsSelectionProof
    ): Boolean {
        if (proof.matcherPolicyVersion != LYRICS_MATCHER_POLICY_VERSION) return false
        val resultSnapshot = result.candidateSnapshot()
        if (resultSnapshot !in proof.supportingCandidates) return false
        return selectCandidatesWithProof(
            query,
            proof.supportingCandidates.map(LyricsCandidateSnapshot::toResult)
        ).any { selection -> candidateIdentity(selection.candidate) == candidateIdentity(result) }
    }

    fun selectionSummary(
        query: LyricsLookup,
        candidates: Iterable<LyricsResult>,
        selected: LyricsResult? = null
    ): String {
        val ranked = rankCandidates(query, candidates, includeRejected = true)
        val selectedIdentity = selected?.let(::candidateIdentity)
        val selectedEvidence = selectedIdentity?.let { identity ->
            ranked.firstOrNull { evidence -> candidateIdentity(evidence.candidate) == identity }
        }
        val summaries = (listOfNotNull(selectedEvidence) + ranked.filterNot { it === selectedEvidence })
            .take(MAX_DIAGNOSTIC_CANDIDATES)
            .joinToString("|") { evidence ->
                "${evidence.candidate.source}/${evidence.candidate.sourceId}:${evidence.summary()}"
            }
        return summaries.ifBlank { "none" }
    }

    private fun rankCandidates(
        query: LyricsLookup,
        candidates: Iterable<LyricsResult>,
        includeRejected: Boolean = false
    ): List<CandidateEvidence> {
        if (!canConfirm(query)) return emptyList()
        val uniqueCandidates = candidates.asSequence()
            .distinctBy(::candidateIdentity)
            .toList()
        val queryTitle = titleIdentity(query.track)
        val eligibleCandidates = uniqueCandidates.asSequence()
            .mapNotNull { candidate -> candidateMetadata(query, candidate) }
            .toList()
        val hasMultipleSources = eligibleCandidates.asSequence()
            .map { metadata -> metadata.candidate.source }
            .filter(String::isNotBlank)
            .distinct()
            .take(MINIMUM_SUPPORTING_SOURCES)
            .count() >= MINIMUM_SUPPORTING_SOURCES
        val eligibleEvidence = eligibleCandidates.map { metadata ->
            val directTitleEvidence = titleEvidence(queryTitle, metadata.title)
            val effectiveTitleEvidence = when {
                directTitleEvidence.isConfirmed() -> directTitleEvidence
                directTitleEvidence == EvidenceLevel.UNKNOWN &&
                    hasIndependentTitleBridge(queryTitle, metadata, eligibleCandidates) ->
                    EvidenceLevel.NEAR
                else -> directTitleEvidence
            }
            candidateEvidence(query, metadata.candidate, effectiveTitleEvidence)
        }
        val matched = eligibleEvidence.asSequence()
            .map { evidence -> withSupportingCandidates(evidence, eligibleEvidence, hasMultipleSources) }
            .filter { evidence -> includeRejected || evidence.isEligible() }
            .sortedWith(
                compareByDescending<CandidateEvidence> { it.versionEvidence.titleScore() }
                    .thenByDescending { it.rankingScore() }
                    .thenBy { it.titleAnnotationRank }
                    .thenByDescending { it.supportingSources }
                    .thenBy { abs(query.durationMs - it.candidate.durationMs) }
                    .thenBy { it.candidate.source }
                    .thenBy { it.candidate.sourceId }
            )
            .toList()
        return matched
    }

    fun matchesVersion(query: LyricsLookup, candidate: LyricsResult): Boolean {
        return selectCandidates(query, listOf(candidate)).isNotEmpty()
    }

    fun canFindCover(query: LyricsLookup): Boolean =
        titleIdentity(query.track).isValid && isValidArtist(query.artist)

    fun selectCoverCandidate(
        query: LyricsLookup,
        candidates: Iterable<LyricsResult>
    ): LyricsResult? {
        if (!canFindCover(query)) return null
        val matched = candidates.asSequence()
            .filter { it.cover.isNotBlank() }
            .filter { titlesMatch(query.track, it.candidateTrack) }
            .filter { artistEvidence(query, it).isConfirmed() }
            .distinctBy(::candidateIdentity)
            .sortedWith(
                compareBy<LyricsResult> { it.source }
                    .thenBy { it.sourceId }
            )
            .toList()
        val anchor = matched.firstOrNull() ?: return null
        return anchor.takeIf { matched.all { candidate -> coversSameRelease(anchor, candidate) } }
    }

    private fun coversSameRelease(
        first: LyricsResult,
        second: LyricsResult
    ): Boolean =
        titlesMatch(first.candidateTrack, second.candidateTrack) && sameRelease(first, second)

    private fun sameRelease(
        first: LyricsResult,
        second: LyricsResult
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
        candidate: LyricsResult
    ): CandidateMetadata? {
        if (!hasKnownDuration(candidate.durationMs) ||
            !hasMatchingDuration(query.durationMs, candidate.durationMs) ||
            !isValidArtist(candidate.candidateArtist)
        ) {
            return null
        }
        val wantedTitle = titleIdentity(query.track)
        val foundTitle = titleIdentity(candidate.candidateTrack)
        if (!wantedTitle.isValid || !foundTitle.isValid) {
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
        candidate: LyricsResult,
        titleEvidence: EvidenceLevel
    ): CandidateEvidence {

        return CandidateEvidence(
            candidate = candidate,
            titleEvidence = titleEvidence,
            versionEvidence = versionEvidence(
                titleIdentity(query.track),
                titleIdentity(candidate.candidateTrack)
            ),
            artistEvidence = artistEvidence(query, candidate),
            albumEvidence = albumEvidence(query.album, candidate.candidateAlbum),
            titleAnnotationRank = titleAnnotationRank(query, candidate)
        )
    }

    private fun withSupportingCandidates(
        evidence: CandidateEvidence,
        candidates: List<CandidateEvidence>,
        hasMultipleSources: Boolean
    ): CandidateEvidence {
        if (!hasMultipleSources || !evidence.titleEvidence.isConfirmed()) return evidence
        val peers = candidates.asSequence()
            .filter { peer ->
                candidateIdentity(peer.candidate) != candidateIdentity(evidence.candidate) &&
                    peer.candidate.source.isNotBlank() &&
                    peer.candidate.source != evidence.candidate.source &&
                    candidatesDescribeSameRecording(evidence.candidate, peer.candidate)
            }
            .sortedWith(
                compareBy<CandidateEvidence> { it.candidate.source }
                    .thenBy { it.candidate.sourceId }
            )
            .toList()
        val supportingSources = (sequenceOf(evidence) + peers.asSequence())
            .map { peer -> peer.candidate.source }
            .filter(String::isNotBlank)
            .distinct()
            .count()
            .coerceAtLeast(1)
        return evidence.copy(
            supportingSources = supportingSources,
            proofCandidates = (listOf(evidence.candidate) + peers.map(CandidateEvidence::candidate))
                .distinctBy(::candidateIdentity)
                .take(LyricsSelectionProof.MAX_SUPPORTING_CANDIDATES)
        )
    }

    private fun candidatesDescribeSameRecording(
        first: LyricsResult,
        second: LyricsResult
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
            !titleEvidence(firstTitle, secondTitle).isConfirmed()
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


    private fun candidateIdentity(candidate: LyricsResult): String =
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


    private data class CandidateEvidence(
        val candidate: LyricsResult,
        val titleEvidence: EvidenceLevel,
        val versionEvidence: EvidenceLevel,
        val artistEvidence: EvidenceLevel,
        val albumEvidence: EvidenceLevel,
        val titleAnnotationRank: Int,
        val supportingSources: Int = 1,
        val proofCandidates: List<LyricsResult> = listOf(candidate)
    ) {
        fun isEligible(): Boolean =
            titleEvidence.isConfirmed() &&
                artistEvidence != EvidenceLevel.DIFFERENT &&
                (
                    artistEvidence.isConfirmed() ||
                        albumEvidence.isConfirmed() ||
                        supportingSources >= MINIMUM_SUPPORTING_SOURCES
                    )

        fun rankingScore(): Int =
            titleEvidence.titleScore() +
                artistEvidence.artistScore() +
                albumEvidence.albumScore() +
                consensusBonus()

        fun summary(): String =
            "eligible=${isEligible()} rank=${rankingScore()} title=$titleEvidence " +
                "version=$versionEvidence artist=$artistEvidence album=$albumEvidence " +
                "sources=$supportingSources"

        fun selection(): LyricsCandidateSelection = LyricsCandidateSelection(
            candidate = candidate,
            proof = LyricsSelectionProof(
                matcherPolicyVersion = LYRICS_MATCHER_POLICY_VERSION,
                supportingCandidates = proofCandidates.map(LyricsResult::candidateSnapshot)
            )
        )

        private fun consensusBonus(): Int {
            return if (titleEvidence.isConfirmed() &&
                supportingSources >= MINIMUM_SUPPORTING_SOURCES
            ) {
                CONSENSUS_BONUS
            } else {
                0
            }
        }
    }

    private data class CandidateMetadata(
        val candidate: LyricsResult,
        val title: TitleIdentity
    )


    private const val MINIMUM_SUPPORTING_SOURCES = 2
    private const val CONSENSUS_BONUS = 2
    private const val MAX_DIAGNOSTIC_CANDIDATES = 3

}
