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
        val candidateMetadata = uniqueCandidates.asSequence()
            .map { candidate -> candidateMetadata(query, candidate) }
            .toList()
        val comparableCandidates = candidateMetadata.filter { metadata ->
            metadata.preconditions.isEmpty()
        }
        val hasMultipleSources = comparableCandidates.asSequence()
            .map { metadata -> metadata.candidate.source }
            .filter(String::isNotBlank)
            .distinct()
            .take(MINIMUM_SUPPORTING_SOURCES)
            .count() >= MINIMUM_SUPPORTING_SOURCES
        val candidateEvidence = candidateMetadata.map { metadata ->
            val directTitleEvidence = titleMetadataEvidence(queryTitle, metadata.title)
            val effectiveTitleEvidence = when {
                directTitleEvidence.isConfirmed() -> directTitleEvidence
                directTitleEvidence.level == EvidenceLevel.UNKNOWN &&
                    metadata.preconditions.isEmpty() &&
                    hasIndependentTitleBridge(queryTitle, metadata, comparableCandidates) ->
                    MetadataEvidence(
                        EvidenceLevel.NEAR,
                        EvidenceReason.INDEPENDENT_TITLE_BRIDGE
                    )
                else -> directTitleEvidence
            }
            candidateEvidence(
                query = query,
                candidate = metadata.candidate,
                titleEvidence = effectiveTitleEvidence,
                preconditions = metadata.preconditions
            )
        }
        val matched = candidateEvidence.asSequence()
            .map { evidence -> withSupportingCandidates(evidence, candidateEvidence, hasMultipleSources) }
            .filter { evidence -> includeRejected || evidence.isEligible() }
            .sortedWith(
                compareByDescending<CandidateEvidence> { it.versionEvidence.level.titleScore() }
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
    ): CandidateMetadata {
        val foundTitle = titleIdentity(candidate.candidateTrack)
        val preconditions = buildSet {
            when {
                !hasKnownDuration(candidate.durationMs) ->
                    add(CandidateRejection.INVALID_DURATION)
                !hasMatchingDuration(query.durationMs, candidate.durationMs) ->
                    add(CandidateRejection.DURATION_CONFLICT)
            }
            if (!isValidArtist(candidate.candidateArtist)) {
                add(CandidateRejection.INVALID_ARTIST)
            }
            if (!foundTitle.isValid) {
                add(CandidateRejection.INVALID_TITLE)
            }
        }
        return CandidateMetadata(candidate, foundTitle, preconditions)
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
        titleEvidence: MetadataEvidence,
        preconditions: Set<CandidateRejection>
    ): CandidateEvidence {
        return CandidateEvidence(
            candidate = candidate,
            titleEvidence = titleEvidence,
            versionEvidence = versionMetadataEvidence(
                titleIdentity(query.track),
                titleIdentity(candidate.candidateTrack)
            ),
            artistEvidence = artistMetadataEvidence(
                query.track,
                query.artist,
                candidate.candidateTrack,
                candidate.candidateArtist
            ),
            albumEvidence = albumMetadataEvidence(query.album, candidate.candidateAlbum),
            titleAnnotationRank = titleAnnotationRank(query, candidate),
            preconditions = preconditions
        )
    }

    private fun withSupportingCandidates(
        evidence: CandidateEvidence,
        candidates: List<CandidateEvidence>,
        hasMultipleSources: Boolean
    ): CandidateEvidence {
        if (!hasMultipleSources ||
            evidence.preconditions.isNotEmpty() ||
            !evidence.titleEvidence.isConfirmed()
        ) {
            return evidence
        }
        val peers = candidates.asSequence()
            .filter { peer ->
                peer.preconditions.isEmpty() &&
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
        val titleEvidence: MetadataEvidence,
        val versionEvidence: MetadataEvidence,
        val artistEvidence: MetadataEvidence,
        val albumEvidence: MetadataEvidence,
        val titleAnnotationRank: Int,
        val preconditions: Set<CandidateRejection>,
        val supportingSources: Int = 1,
        val proofCandidates: List<LyricsResult> = listOf(candidate)
    ) {
        fun isEligible(): Boolean = rejectionReasons().isEmpty()

        fun rankingScore(): Int =
            titleEvidence.level.titleScore() +
                artistEvidence.level.artistScore() +
                albumEvidence.level.albumScore() +
                consensusBonus()

        fun summary(): String =
            "eligible=${isEligible()} rejected=${rejectionReasons().joinToString(",")} " +
                "rank=${rankingScore()} " +
                "title=${titleEvidence.level}/${titleEvidence.reason} " +
                "version=${versionEvidence.level}/${versionEvidence.reason} " +
                "artist=${artistEvidence.level}/${artistEvidence.reason} " +
                "album=${albumEvidence.level}/${albumEvidence.reason} sources=$supportingSources"

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

        private fun rejectionReasons(): Set<CandidateRejection> = buildSet {
            addAll(preconditions)
            when {
                versionEvidence.level == EvidenceLevel.DIFFERENT ->
                    add(CandidateRejection.VERSION_CONFLICT)
                titleEvidence.level == EvidenceLevel.DIFFERENT ->
                    add(CandidateRejection.TITLE_CONFLICT)
                !titleEvidence.isConfirmed() ->
                    add(CandidateRejection.TITLE_UNCONFIRMED)
            }
            if (artistEvidence.level == EvidenceLevel.DIFFERENT) {
                add(CandidateRejection.ARTIST_CONFLICT)
            }
            if (titleEvidence.isConfirmed() &&
                artistEvidence.level != EvidenceLevel.DIFFERENT &&
                !artistEvidence.isConfirmed() &&
                !albumEvidence.isConfirmed() &&
                supportingSources < MINIMUM_SUPPORTING_SOURCES
            ) {
                add(CandidateRejection.INSUFFICIENT_SUPPORT)
            }
        }
    }

    private data class CandidateMetadata(
        val candidate: LyricsResult,
        val title: TitleIdentity,
        val preconditions: Set<CandidateRejection>
    )

    private enum class CandidateRejection {
        INVALID_DURATION,
        DURATION_CONFLICT,
        INVALID_ARTIST,
        INVALID_TITLE,
        VERSION_CONFLICT,
        TITLE_CONFLICT,
        TITLE_UNCONFIRMED,
        ARTIST_CONFLICT,
        INSUFFICIENT_SUPPORT
    }


    private const val MINIMUM_SUPPORTING_SOURCES = 2
    private const val CONSENSUS_BONUS = 2
    private const val MAX_DIAGNOSTIC_CANDIDATES = 3

}
