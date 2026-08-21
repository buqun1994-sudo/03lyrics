package com.ninepointnine.desktoplyrics

import android.media.AudioAttributes
import android.media.session.PlaybackState

/**
 * The public MediaSession contract is the source boundary for playback.
 * Package names are diagnostic data, not a capability allowlist.
 */
internal data class MediaSessionCandidate(
    val index: Int,
    val packageName: String,
    val playbackState: Int?,
    val audioUsage: Int?,
    val audioContentType: Int?,
    val playbackActions: Long,
    val hasTitle: Boolean
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

    private fun isEligible(
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
