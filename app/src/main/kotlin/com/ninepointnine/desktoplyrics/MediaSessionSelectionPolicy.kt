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
        val armedAtMs: Long
    )

    private data class ActivityEvidence(
        var progressedAtMs: Long? = null,
        var publishedAtMs: Long? = null
    )

    private var selectedSessionId: String? = null
    private var preferredSourceId: String? = null
    private var coldStartStartedAtMs: Long? = null
    private var selectionMayResumeImmediately = false
    private var pendingHandoff: PendingHandoff? = null
    private val previousCandidates = linkedMapOf<String, MediaSessionCandidate>()
    private val activityEvidence = linkedMapOf<String, ActivityEvidence>()

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
        observeActivity(eligible, nowMs)
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
            val fresh = bestActive(eligible, nowMs)?.takeIf { evidenceRank(it, nowMs) >= 2 }
            if (fresh != null) {
                selectionMayResumeImmediately = true
                return commit(fresh, candidates, "current_session_missing_new_playing")
            }
            selectionMayResumeImmediately = true
            remember(candidates)
            return decisionClear("current_session_missing")
        }

        if (currentRaw?.isEnded == true) {
            selectedSessionId = null
            pendingHandoff = null
            val fresh = bestActive(eligible, nowMs, excludeSessionId = currentId)
                ?.takeIf { evidenceRank(it, nowMs) >= 2 }
            if (fresh != null) {
                selectionMayResumeImmediately = true
                return commit(fresh, candidates, "current_session_ended_new_playing")
            }
            coldStartStartedAtMs = nowMs
            selectionMayResumeImmediately = true
            remember(candidates)
            return decisionClear("current_session_ended")
        }

        if (currentId == null) {
            if (coldStartStartedAtMs == null) coldStartStartedAtMs = nowMs
            val coldStartElapsed = nowMs - (coldStartStartedAtMs ?: nowMs)
            if (!selectionMayResumeImmediately && coldStartElapsed < coldStartSettleMs) {
                remember(candidates)
                return decisionKeep(
                    null,
                    "cold_start_waiting_for_session_evidence",
                    coldStartSettleMs - coldStartElapsed
                )
            }
            val active = bestActive(eligible, nowMs)
            if (active != null && evidenceRank(active, nowMs) >= 2) {
                return commit(active, candidates, "cold_start_playback_evidence")
            }
            // Once a source ends, only new playback evidence can replace it.
            if (selectionMayResumeImmediately) {
                remember(candidates)
                return decisionKeep(null, "waiting_for_new_playback")
            }
            val preferred = preferredSourceId?.let { sourceId ->
                eligible.firstOrNull { sourceIdentityOf(it) == sourceId && isColdStartCandidate(it) }
            }
            if (preferred != null) {
                return commit(preferred, candidates, "cold_start_preferred_source")
            }

            if (preferredSourceId != null && discoveryPending &&
                coldStartElapsed < PREFERRED_DISCOVERY_MAX_WAIT_MS
            ) {
                remember(candidates)
                return decisionKeep(
                    null,
                    "cold_start_waiting_for_preferred_browser",
                    minOf(
                        preferredSourceSettleMs.coerceAtLeast(1L),
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
            val restored = bestColdStartCandidate(eligible, nowMs)
            if (restored != null) {
                return commit(restored, candidates, "cold_start_best_evidence")
            }
            remember(candidates)
            return decisionKeep(null, "cold_start_waiting")
        }

        coldStartStartedAtMs = null
        current?.let { preferredSourceId = sourceIdentityOf(it) }
        val pending = pendingHandoff
        val challenger = bestActive(
            eligible,
            nowMs,
            excludeSessionId = currentId
        )
        if (challenger != null && hasHandoffEvidence(challenger, current, nowMs)) {
            val challengerId = identityOf(challenger)
            if (pending?.sessionId != challengerId) {
                pendingHandoff = PendingHandoff(
                    sessionId = challengerId,
                    armedAtMs = nowMs
                )
                remember(candidates)
                return decisionKeep(currentId, "handoff_armed", handoffConfirmMs)
            }

            val elapsed = nowMs - pending.armedAtMs
            remember(candidates)
            if (elapsed >= handoffConfirmMs) return commit(challenger, candidates, "handoff_confirmed")
            return decisionKeep(
                currentId,
                "handoff_waiting_for_fresh_progress",
                (handoffConfirmMs - elapsed).takeIf { it > 0L }
            )
        }

        pendingHandoff = null
        remember(candidates)
        val reason = when {
            currentRaw?.playbackState == null -> "incumbent_state_unknown"
            current == null -> "incumbent_state_transitional"
            evidenceRank(current, nowMs) == 3 -> "incumbent_progressing"
            current.isPlaying || current.isBuffering -> "incumbent_active_without_challenger"
            else -> "incumbent_paused"
        }
        return decisionKeep(currentId, reason)
    }

    fun forgetSession(sessionId: String) {
        previousCandidates.remove(sessionId)
        activityEvidence.remove(sessionId)
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

    fun hasCurrentPlaybackEvidence(nowMs: Long): Boolean = selectedSessionId
        ?.let(previousCandidates::get)
        ?.let { evidenceRank(it, nowMs) >= 2 } == true

    fun reset() {
        selectedSessionId = null
        preferredSourceId = null
        coldStartStartedAtMs = null
        pendingHandoff = null
        selectionMayResumeImmediately = false
        previousCandidates.clear()
        activityEvidence.clear()
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

    private fun isColdStartCandidate(candidate: MediaSessionCandidate): Boolean =
        candidate.isPlaying || candidate.isBuffering || (candidate.isPaused && candidate.hasTitle)

    private fun bestColdStartCandidate(
        candidates: List<MediaSessionCandidate>,
        nowMs: Long
    ): MediaSessionCandidate? {
        val viable = candidates.filter(::isColdStartCandidate)
        bestActive(viable, nowMs)?.let { return it }

        val paused = viable.filter(MediaSessionCandidate::isPaused)
        if (paused.size == 1) return paused.single()
        val withPublisherTime = paused.filter { candidate ->
            candidate.positionUpdateTimeMs in 1..nowMs
        }
        return withPublisherTime.maxWithOrNull(
            compareBy<MediaSessionCandidate>(MediaSessionCandidate::positionUpdateTimeMs)
                .thenBy { if (it.activeInSystemList) 1 else 0 }
                .thenBy { -it.index }
        )
    }

    private fun evidenceRank(
        candidate: MediaSessionCandidate,
        nowMs: Long
    ): Int {
        if (!isColdStartCandidate(candidate)) return 0
        val evidence = activityEvidence[identityOf(candidate)]
        return when {
            isRecent(evidence?.progressedAtMs, nowMs) -> 3
            isRecent(evidence?.publishedAtMs, nowMs) -> 2
            candidate.isPlaying || candidate.isBuffering -> 1
            else -> 0
        }
    }

    private fun isRecent(atMs: Long?, nowMs: Long): Boolean =
        atMs != null && atMs <= nowMs && nowMs - atMs <= ACTIVITY_EVIDENCE_MAX_AGE_MS

    private fun bestActive(
        candidates: List<MediaSessionCandidate>,
        nowMs: Long,
        excludeSessionId: String? = null
    ): MediaSessionCandidate? = candidates
        .asSequence()
        .filter { identityOf(it) != excludeSessionId }
        .filter { evidenceRank(it, nowMs) > 0 }
        .sortedWith(
            compareByDescending<MediaSessionCandidate> { evidenceRank(it, nowMs) }
                .thenByDescending { evidenceTime(it, nowMs) }
                .thenByDescending(::activityRank)
                .thenByDescending(MediaSessionCandidate::activeInSystemList)
                .thenBy(MediaSessionCandidate::index)
        )
        .firstOrNull()

    private fun evidenceTime(candidate: MediaSessionCandidate, nowMs: Long): Long {
        val evidence = activityEvidence[identityOf(candidate)] ?: return 0L
        return when (evidenceRank(candidate, nowMs)) {
            3 -> evidence.progressedAtMs ?: 0L
            2 -> evidence.publishedAtMs ?: 0L
            else -> 0L
        }
    }

    private fun hasHandoffEvidence(
        candidate: MediaSessionCandidate,
        incumbent: MediaSessionCandidate?,
        nowMs: Long
    ): Boolean {
        val challengerRank = evidenceRank(candidate, nowMs)
        if (challengerRank < 2) return false
        val incumbentRank = incumbent?.let { evidenceRank(it, nowMs) } ?: 0
        if (challengerRank != incumbentRank) return challengerRank > incumbentRank
        // Equally progressing sources retain the incumbent to avoid callback-order churn.
        return challengerRank == 2 && incumbent != null &&
            evidenceTime(candidate, nowMs) > evidenceTime(incumbent, nowMs)
    }

    /** Retain observed facts across duplicate callbacks, never extrapolated positions. */
    private fun observeActivity(candidates: List<MediaSessionCandidate>, nowMs: Long) {
        activityEvidence.keys.retainAll(candidates.mapTo(hashSetOf(), ::identityOf))
        candidates.forEach { candidate ->
            val id = identityOf(candidate)
            if (!isColdStartCandidate(candidate)) {
                activityEvidence.remove(id)
                return@forEach
            }
            val previous = previousCandidates[id]
            val evidence = activityEvidence.getOrPut(id, ::ActivityEvidence)
            val paused = candidate.isPaused && previous != null && !previous.isPaused
            if (paused || (previous != null && !isColdStartCandidate(previous))) {
                evidence.progressedAtMs = null
                evidence.publishedAtMs = null
            }
            val positionChanged = previous != null && candidate.reportedPositionMs >= 0L &&
                previous.reportedPositionMs >= 0L && candidate.reportedPositionMs != previous.reportedPositionMs
            val progressing = positionChanged && !paused && previous != null && isColdStartCandidate(previous) &&
                (candidate.reportedPositionMs > previous.reportedPositionMs ||
                    candidate.playbackState == PlaybackState.STATE_REWINDING)
            if (progressing) evidence.progressedAtMs = nowMs

            if (candidate.isPlaying || candidate.isBuffering) {
                val resumed = previous != null && !previous.isPlaying && !previous.isBuffering
                if (previous == null || resumed || positionChanged) {
                    evidence.publishedAtMs = candidate.positionUpdateTimeMs.takeIf {
                        candidate.reportedPositionMs >= 0L && it in 1..nowMs
                    }
                }
            } else {
                evidence.publishedAtMs = null
            }
        }
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
