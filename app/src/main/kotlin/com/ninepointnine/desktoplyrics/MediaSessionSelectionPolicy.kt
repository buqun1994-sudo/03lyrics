package com.ninepointnine.desktoplyrics

import android.media.AudioAttributes
import android.media.session.PlaybackState

/**
 * The public MediaSession contract is the source boundary for playback.
 * Package names are diagnostic data, not a capability allowlist.
 */
internal data class MediaSessionCandidate(
    val index: Int,
    val sessionId: String = "",
    val sourceId: String = "",
    val packageName: String,
    val playbackState: Int?,
    val audioUsage: Int?,
    val audioContentType: Int?,
    val playbackActions: Long,
    val hasTitle: Boolean,
    val activeInSystemList: Boolean = true,
    val reportedPositionMs: Long = PlaybackState.PLAYBACK_POSITION_UNKNOWN,
    val positionUpdateTimeMs: Long = 0L
) {
    val isPlaying: Boolean
        get() = playbackState == PlaybackState.STATE_PLAYING ||
            playbackState == PlaybackState.STATE_FAST_FORWARDING ||
            playbackState == PlaybackState.STATE_REWINDING

    val isBuffering: Boolean
        get() = playbackState == PlaybackState.STATE_BUFFERING ||
            playbackState == PlaybackState.STATE_CONNECTING

    val isPaused: Boolean
        get() = playbackState == PlaybackState.STATE_PAUSED

    val isEnded: Boolean
        get() = playbackState == PlaybackState.STATE_STOPPED ||
            playbackState == PlaybackState.STATE_NONE

    val hasTransportActions: Boolean
        get() = playbackActions and MEDIA_ACTION_MASK != 0L

    companion object {
        private val MEDIA_ACTION_MASK =
            PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO
    }
}

internal object MediaSessionSelectionPolicy {
    /**
     * MediaSessionManager already orders controllers by system priority. State
     * tier is considered first, then that order is preserved. Metadata detail
     * never allows a stale session to outrank the session Android addresses.
     */
    fun select(
        candidates: List<MediaSessionCandidate>,
        currentIndex: Int?,
        hasCurrentSelection: Boolean = false,
        ownPackageName: String
    ): Int? {
        val candidatesWithoutSelf = candidates.filter { it.packageName != ownPackageName }
        val active = candidatesWithoutSelf
            .asSequence()
            .filter { it.isPlaying || it.isBuffering }
            .filter { isEligible(it, allowMissingTitle = true) }
            .toList()
        if (active.isNotEmpty()) {
            val bestActivityRank = active.maxOf(::activityRank)
            return active
                .filter { activityRank(it) == bestActivityRank }
                .minBy(MediaSessionCandidate::index)
                .index
        }

        val current = currentIndex?.let { index ->
            candidatesWithoutSelf.firstOrNull { it.index == index }
        }
        if (current?.isPaused == true && isEligible(current, allowMissingTitle = false)) {
            return current.index
        }

        // A paused session may be selected during cold discovery or while it
        // remains current. Once the selected source stops or disappears, do
        // not resurrect an unrelated paused session on a later refresh.
        if (hasCurrentSelection) return null

        return candidatesWithoutSelf
            .asSequence()
            .filter(MediaSessionCandidate::isPaused)
            .filter { isEligible(it, allowMissingTitle = false) }
            .minByOrNull(MediaSessionCandidate::index)
            ?.index
    }

    internal fun isEligible(
        candidate: MediaSessionCandidate,
        allowMissingTitle: Boolean
    ): Boolean {
        if (!hasMediaSemantics(candidate)) return false
        val explicitMusicSignal = candidate.audioUsage == AudioAttributes.USAGE_MEDIA ||
            candidate.audioContentType == AudioAttributes.CONTENT_TYPE_MUSIC
        if (allowMissingTitle && explicitMusicSignal) return true
        return candidate.hasTitle && (explicitMusicSignal || candidate.hasTransportActions)
    }

    private fun hasMediaSemantics(candidate: MediaSessionCandidate): Boolean {
        val usage = candidate.audioUsage
        if (usage != null && usage != AudioAttributes.USAGE_UNKNOWN &&
            usage != AudioAttributes.USAGE_MEDIA
        ) {
            return false
        }
        return candidate.audioContentType != AudioAttributes.CONTENT_TYPE_SPEECH &&
            candidate.audioContentType != AudioAttributes.CONTENT_TYPE_SONIFICATION
    }

    private fun activityRank(candidate: MediaSessionCandidate): Int = when {
        candidate.isPlaying -> 2
        candidate.isBuffering -> 1
        else -> 0
    }
}

internal enum class MediaSessionArbitrationAction {
    KEEP_CURRENT,
    SELECT,
    CLEAR
}

internal data class MediaSessionArbitrationDecision(
    val action: MediaSessionArbitrationAction,
    val sessionId: String?,
    val reason: String,
    val recheckAfterMs: Long? = null
)

/**
 * Owns source stickiness and handoff confirmation independently of Android
 * controller objects. A session disappearing from the active list is not an
 * ended session; the service may keep it in the candidate set as the retained
 * current source until it is explicitly stopped or destroyed.
 */
internal class MediaSessionArbiter(
    private val coldStartSettleMs: Long = COLD_START_SETTLE_MS,
    private val handoffConfirmMs: Long = HANDOFF_CONFIRM_MS,
    private val preferredSourceSettleMs: Long = PREFERRED_SOURCE_SETTLE_MS
) {
    private data class PendingHandoff(
        val sessionId: String,
        val armedAtMs: Long,
        val evidenceAtArm: Boolean
    )

    private var selectedSessionId: String? = null
    private var preferredSourceId: String? = null
    private var coldStartStartedAtMs: Long? = null
    private var selectionMayResumeImmediately = false
    private var pendingHandoff: PendingHandoff? = null
    private val previousCandidates = linkedMapOf<String, MediaSessionCandidate>()

    fun evaluate(
        candidates: List<MediaSessionCandidate>,
        nowMs: Long,
        ownPackageName: String,
        discoveryPending: Boolean = false
    ): MediaSessionArbitrationDecision {
        val eligible = candidates
            .filter { it.packageName != ownPackageName }
            .filter {
                MediaSessionSelectionPolicy.isEligible(
                    candidate = it,
                    allowMissingTitle = it.isPlaying || it.isBuffering
                )
            }
        val byId = eligible.associateBy { identityOf(it) }
        val currentId = selectedSessionId
        val currentRaw = currentId?.let { id ->
            candidates.firstOrNull { identityOf(it) == id }
        }
        val current = currentId?.let(byId::get)

        if (currentId != null && currentRaw == null) {
            selectedSessionId = null
            pendingHandoff = null
            coldStartStartedAtMs = nowMs
            val fresh = freshestPlaying(eligible)
            if (fresh != null) {
                selectionMayResumeImmediately = true
                return commit(fresh, candidates, "current_session_missing_new_playing")
            }
            selectionMayResumeImmediately = true
            remember(candidates)
            return decisionClear("current_session_missing")
        }

        // MediaSession providers can briefly publish a null playback state
        // while replacing metadata or reconnecting. Keep the incumbent until
        // the provider reports a concrete ended state or the session is
        // destroyed; otherwise a transient callback would clear lyrics.
        if (currentId != null && current == null && currentRaw?.playbackState == null) {
            pendingHandoff = null
            remember(candidates)
            return decisionKeep(currentId, "incumbent_state_unknown")
        }

        if (currentRaw?.isEnded == true) {
            selectedSessionId = null
            pendingHandoff = null
            val fresh = freshestPlaying(eligible, excludeSessionId = currentId)
            if (fresh != null) {
                selectionMayResumeImmediately = true
                return commit(fresh, candidates, "current_session_ended_new_playing")
            }
            coldStartStartedAtMs = nowMs
            selectionMayResumeImmediately = true
            remember(candidates)
            return decisionClear("current_session_ended")
        }

        if (currentId != null && current == null) {
            pendingHandoff = null
            remember(candidates)
            return decisionKeep(currentId, "incumbent_state_transitional")
        }

        if (current == null) {
            if (coldStartStartedAtMs == null) coldStartStartedAtMs = nowMs
            val preferred = preferredSourceId?.let { sourceId ->
                eligible.firstOrNull { sourceIdentityOf(it) == sourceId && it.hasTitle }
            }
            if (preferred != null) {
                return commit(preferred, candidates, "cold_start_preferred_source")
            }

            val coldStartElapsed = nowMs - (coldStartStartedAtMs ?: nowMs)
            if (preferredSourceId != null && discoveryPending &&
                coldStartElapsed < PREFERRED_DISCOVERY_MAX_WAIT_MS
            ) {
                remember(candidates)
                return decisionKeep(
                    null,
                    "cold_start_waiting_for_preferred_browser",
                    minOf(
                        preferredSourceSettleMs,
                        PREFERRED_DISCOVERY_MAX_WAIT_MS - coldStartElapsed
                    )
                )
            }
            if (preferredSourceId != null && coldStartElapsed < preferredSourceSettleMs) {
                remember(candidates)
                return decisionKeep(
                    null,
                    "cold_start_waiting_for_preferred_source",
                    preferredSourceSettleMs - coldStartElapsed
                )
            }
            if (!selectionMayResumeImmediately && coldStartElapsed < coldStartSettleMs) {
                remember(candidates)
                return decisionKeep(
                    null,
                    "cold_start_waiting_for_session_evidence",
                    coldStartSettleMs - coldStartElapsed
                )
            }
            val restored = bestColdStartCandidate(eligible, nowMs)
            if (restored != null) {
                return commit(restored, candidates, "cold_start_best_evidence")
            }
            remember(candidates)
            return decisionKeep(null, "cold_start_waiting")
        }

        coldStartStartedAtMs = null
        preferredSourceId = sourceIdentityOf(current)
        val pending = pendingHandoff
        val pendingCandidate = pending?.let { handoff ->
            eligible.firstOrNull {
                identityOf(it) == handoff.sessionId && (it.isPlaying || it.isBuffering)
            }
        }
        val challenger = pendingCandidate ?: bestActive(
            eligible,
            excludeSessionId = currentId
        )
        val incumbentProgressed = hasFreshProgress(current, previousCandidates[currentId])
        if (challenger != null && !incumbentProgressed) {
            val challengerId = identityOf(challenger)
            if (pending?.sessionId != challengerId) {
                pendingHandoff = PendingHandoff(
                    sessionId = challengerId,
                    armedAtMs = nowMs,
                    evidenceAtArm = hasHandoffEvidence(
                        candidate = challenger,
                        previous = previousCandidates[challengerId],
                        incumbent = current,
                        nowMs = nowMs
                    )
                )
                remember(candidates)
                return decisionKeep(currentId, "handoff_armed", handoffConfirmMs)
            }

            val previous = previousCandidates[challengerId]
            val elapsed = nowMs - pending.armedAtMs
            val confirmed = elapsed >= handoffConfirmMs &&
                (pending.evidenceAtArm || hasHandoffEvidence(
                    candidate = challenger,
                    previous = previous,
                    incumbent = current,
                    nowMs = nowMs
                ))
            remember(candidates)
            if (confirmed) return commit(challenger, candidates, "handoff_confirmed")
            return decisionKeep(
                currentId,
                "handoff_waiting_for_fresh_progress",
                (handoffConfirmMs - elapsed).takeIf { it > 0L }
            )
        }

        pendingHandoff = null
        remember(candidates)
        val reason = when {
            incumbentProgressed -> "incumbent_progressing"
            current.isPlaying || current.isBuffering -> "incumbent_active_without_challenger"
            else -> "incumbent_paused"
        }
        return decisionKeep(currentId, reason)
    }

    fun forgetSession(sessionId: String) {
        previousCandidates.remove(sessionId)
        if (selectedSessionId == sessionId) {
            selectedSessionId = null
            preferredSourceId = null
            coldStartStartedAtMs = null
            pendingHandoff = null
            selectionMayResumeImmediately = true
        }
    }

    fun restorePreferredSource(sourceId: String?) {
        if (selectedSessionId == null) preferredSourceId = sourceId?.takeIf(String::isNotBlank)
    }

    fun reset() {
        selectedSessionId = null
        preferredSourceId = null
        coldStartStartedAtMs = null
        pendingHandoff = null
        selectionMayResumeImmediately = false
        previousCandidates.clear()
    }

    private fun commit(
        candidate: MediaSessionCandidate,
        candidates: List<MediaSessionCandidate>,
        reason: String
    ): MediaSessionArbitrationDecision {
        val id = identityOf(candidate)
        val changed = selectedSessionId != id
        selectedSessionId = id
        preferredSourceId = sourceIdentityOf(candidate)
        coldStartStartedAtMs = null
        pendingHandoff = null
        selectionMayResumeImmediately = false
        remember(candidates)
        return if (changed) {
            MediaSessionArbitrationDecision(MediaSessionArbitrationAction.SELECT, id, reason)
        } else {
            decisionKeep(id, reason)
        }
    }

    private fun freshestPlaying(
        candidates: List<MediaSessionCandidate>,
        excludeSessionId: String? = null
    ): MediaSessionCandidate? =
        candidates
            .asSequence()
            .filter { identityOf(it) != excludeSessionId }
            .filter { it.isPlaying || it.isBuffering }
            .filter { hasFreshProgress(it, previousCandidates[identityOf(it)]) }
            .sortedWith(
                compareByDescending<MediaSessionCandidate> { if (it.isPlaying) 2 else 1 }
                    .thenBy(MediaSessionCandidate::index)
            )
            .firstOrNull()

    private fun bestColdStartCandidate(
        candidates: List<MediaSessionCandidate>,
        nowMs: Long
    ): MediaSessionCandidate? {
        val viable = candidates.filter { candidate ->
            candidate.isPlaying || candidate.isBuffering ||
                (candidate.isPaused && candidate.hasTitle)
        }
        val withPublisherTime = viable.filter { candidate ->
            candidate.positionUpdateTimeMs in 1..nowMs
        }
        if (withPublisherTime.isNotEmpty()) {
            return withPublisherTime.maxWithOrNull(
                compareBy<MediaSessionCandidate>(MediaSessionCandidate::positionUpdateTimeMs)
                    .thenBy { if (it.activeInSystemList) 1 else 0 }
                    .thenBy(::activityRank)
                    .thenBy { -it.index }
            )
        }
        val active = viable.filter { it.isPlaying || it.isBuffering }
        if (active.isNotEmpty()) {
            return active
                .sortedWith(
                    compareByDescending<MediaSessionCandidate>(::activityRank)
                        .thenBy(MediaSessionCandidate::index)
                )
                .first()
        }
        val paused = viable.filter(MediaSessionCandidate::isPaused)
        return paused.singleOrNull()
    }

    private fun bestActive(
        candidates: List<MediaSessionCandidate>,
        excludeSessionId: String? = null
    ): MediaSessionCandidate? = candidates
        .asSequence()
        .filter { identityOf(it) != excludeSessionId }
        .filter { it.isPlaying || it.isBuffering }
        .sortedWith(
            compareByDescending<MediaSessionCandidate> { if (it.isPlaying) 2 else 1 }
                .thenBy(MediaSessionCandidate::index)
        )
        .firstOrNull()

    private fun hasFreshProgress(
        candidate: MediaSessionCandidate,
        previous: MediaSessionCandidate?
    ): Boolean {
        if (previous == null) return true
        val positionChanged = candidate.reportedPositionMs >= 0L &&
            previous.reportedPositionMs >= 0L &&
            candidate.reportedPositionMs != previous.reportedPositionMs
        val publisherTimeChanged = candidate.positionUpdateTimeMs > 0L &&
            candidate.positionUpdateTimeMs != previous.positionUpdateTimeMs
        val becameVisible = candidate.activeInSystemList && !previous.activeInSystemList
        return positionChanged || publisherTimeChanged || becameVisible
    }

    private fun hasHandoffEvidence(
        candidate: MediaSessionCandidate,
        previous: MediaSessionCandidate?,
        incumbent: MediaSessionCandidate,
        nowMs: Long
    ): Boolean {
        if (previous != null && hasFreshProgress(candidate, previous)) return true
        val publisherTime = candidate.positionUpdateTimeMs
        val ageMs = nowMs - publisherTime
        return publisherTime > incumbent.positionUpdateTimeMs &&
            ageMs in 0..ACTIVITY_EVIDENCE_MAX_AGE_MS
    }

    private fun identityOf(candidate: MediaSessionCandidate): String =
        candidate.sessionId.ifBlank { "${candidate.packageName}#${candidate.index}" }

    private fun sourceIdentityOf(candidate: MediaSessionCandidate): String =
        candidate.sourceId.ifBlank { candidate.packageName }

    private fun activityRank(candidate: MediaSessionCandidate): Int = when {
        candidate.isPlaying -> 2
        candidate.isBuffering -> 1
        else -> 0
    }

    private fun remember(candidates: List<MediaSessionCandidate>) {
        val currentIds = candidates.mapTo(hashSetOf(), ::identityOf)
        previousCandidates.keys.retainAll(currentIds)
        candidates.forEach { previousCandidates[identityOf(it)] = it }
    }

    private fun decisionKeep(
        sessionId: String?,
        reason: String,
        recheckAfterMs: Long? = null
    ) = MediaSessionArbitrationDecision(
        MediaSessionArbitrationAction.KEEP_CURRENT,
        sessionId,
        reason,
        recheckAfterMs
    )

    private fun decisionClear(reason: String) =
        MediaSessionArbitrationDecision(MediaSessionArbitrationAction.CLEAR, null, reason)

    companion object {
        internal const val COLD_START_SETTLE_MS = 1_500L
        internal const val HANDOFF_CONFIRM_MS = 250L
        internal const val PREFERRED_SOURCE_SETTLE_MS = 3_500L
        internal const val PREFERRED_DISCOVERY_MAX_WAIT_MS = 7_500L
        internal const val ACTIVITY_EVIDENCE_MAX_AGE_MS = 5_000L
    }
}
