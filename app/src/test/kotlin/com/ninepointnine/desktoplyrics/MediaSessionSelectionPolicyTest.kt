package com.ninepointnine.desktoplyrics

import android.media.AudioAttributes
import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSessionSelectionPolicyTest {
    @Test
    fun `accepts the built in local and usb sessions through standard media usage`() {
        val local = candidate(
            index = 1,
            packageName = "com.tencent.wecarflow",
            playbackState = PlaybackState.STATE_PAUSED,
            audioUsage = AudioAttributes.USAGE_MEDIA,
            title = true
        )
        val usb = local.copy(index = 2, playbackState = PlaybackState.STATE_PLAYING)

        assertEquals(
            2,
            MediaSessionSelectionPolicy.select(
                candidates = listOf(local, usb),
                currentIndex = 1,
                ownPackageName = "com.ninepointnine.desktoplyrics"
            )
        )
    }

    @Test
    fun `accepts a generic media package without a music package name`() {
        val podcastPackage = candidate(
            index = 3,
            packageName = "com.ximalaya.ting.android.car",
            playbackState = PlaybackState.STATE_PLAYING,
            audioUsage = AudioAttributes.USAGE_MEDIA,
            title = true
        )

        assertEquals(
            3,
            MediaSessionSelectionPolicy.select(
                candidates = listOf(podcastPackage),
                currentIndex = null,
                ownPackageName = "com.ninepointnine.desktoplyrics"
            )
        )
    }

    @Test
    fun `keeps the current paused source when all candidates are paused`() {
        val bluetooth = candidate(
            index = 0,
            packageName = "com.android.bluetooth",
            playbackState = PlaybackState.STATE_PAUSED,
            title = true
        )
        val usb = candidate(
            index = 1,
            packageName = "com.tencent.wecarflow",
            playbackState = PlaybackState.STATE_PAUSED,
            audioUsage = AudioAttributes.USAGE_MEDIA,
            title = true
        )

        assertEquals(
            1,
            MediaSessionSelectionPolicy.select(
                candidates = listOf(bluetooth, usb),
                currentIndex = 1,
                ownPackageName = "com.ninepointnine.desktoplyrics"
            )
        )
    }

    @Test
    fun `active playback wins over the current paused source`() {
        val bluetooth = candidate(
            index = 0,
            packageName = "com.android.bluetooth",
            playbackState = PlaybackState.STATE_PAUSED,
            title = true
        )
        val aqt = candidate(
            index = 1,
            packageName = "com.tencent.wecarflow",
            playbackState = PlaybackState.STATE_PLAYING,
            audioUsage = AudioAttributes.USAGE_MEDIA,
            title = true
        )

        assertEquals(
            1,
            MediaSessionSelectionPolicy.select(
                candidates = listOf(bluetooth, aqt),
                currentIndex = 0,
                ownPackageName = "com.ninepointnine.desktoplyrics"
            )
        )
    }

    @Test
    fun `selects the playing session when one vehicle package publishes parallel media sessions`() {
        val online = candidate(
            index = 0,
            packageName = "com.tencent.wecarflow",
            playbackState = PlaybackState.STATE_PLAYING,
            audioUsage = AudioAttributes.USAGE_MEDIA,
            title = true
        )
        val helper = candidate(
            index = 1,
            packageName = "com.tencent.wecarflow",
            playbackState = null,
            audioUsage = AudioAttributes.USAGE_MEDIA
        )
        val local = candidate(
            index = 2,
            packageName = "com.tencent.wecarflow",
            playbackState = PlaybackState.STATE_PAUSED,
            audioUsage = AudioAttributes.USAGE_MEDIA
        )
        val usb = candidate(
            index = 3,
            packageName = "com.tencent.wecarflow",
            playbackState = PlaybackState.STATE_PAUSED,
            audioUsage = AudioAttributes.USAGE_MEDIA,
            title = true
        )
        val bluetooth = candidate(
            index = 4,
            packageName = "com.android.bluetooth",
            playbackState = PlaybackState.STATE_PAUSED,
            audioUsage = AudioAttributes.USAGE_MEDIA,
            title = true
        )

        assertEquals(
            0,
            MediaSessionSelectionPolicy.select(
                candidates = listOf(online, helper, local, usb, bluetooth),
                currentIndex = 4,
                ownPackageName = "com.ninepointnine.desktoplyrics"
            )
        )
    }

    @Test
    fun `rejects non media audio sessions and the overlay itself`() {
        val voice = candidate(
            index = 0,
            packageName = "com.example.voice",
            playbackState = PlaybackState.STATE_PLAYING,
            audioUsage = AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            title = true
        )
        val own = candidate(
            index = 1,
            packageName = "com.ninepointnine.desktoplyrics",
            playbackState = PlaybackState.STATE_PLAYING,
            audioUsage = AudioAttributes.USAGE_MEDIA,
            title = true
        )

        assertNull(
            MediaSessionSelectionPolicy.select(
                candidates = listOf(voice, own),
                currentIndex = null,
                ownPackageName = "com.ninepointnine.desktoplyrics"
            )
        )
    }

    @Test
    fun `metadata may arrive after an active session is selected`() {
        val activeWithoutMetadata = candidate(
            index = 0,
            packageName = "com.tencent.wecarflow",
            playbackState = PlaybackState.STATE_PLAYING,
            audioUsage = AudioAttributes.USAGE_MEDIA
        )

        assertEquals(
            0,
            MediaSessionSelectionPolicy.select(
                candidates = listOf(activeWithoutMetadata),
                currentIndex = null,
                ownPackageName = "com.ninepointnine.desktoplyrics"
            )
        )
    }

    @Test
    fun `legacy bluetooth media semantics do not require a package allowlist`() {
        val bluetooth = candidate(
            index = 0,
            packageName = "com.android.bluetooth",
            playbackState = PlaybackState.STATE_PLAYING,
            playbackActions = PlaybackState.ACTION_PLAY_PAUSE,
            title = true
        )

        assertEquals(
            0,
            MediaSessionSelectionPolicy.select(
                candidates = listOf(bluetooth),
                currentIndex = null,
                ownPackageName = "com.ninepointnine.desktoplyrics"
            )
        )
    }

    @Test
    fun `unknown audio semantics without metadata are not admitted early`() {
        val unknown = candidate(
            index = 0,
            packageName = "com.example.player",
            playbackState = PlaybackState.STATE_PLAYING
        )

        assertNull(
            MediaSessionSelectionPolicy.select(
                candidates = listOf(unknown),
                currentIndex = null,
                ownPackageName = "com.ninepointnine.desktoplyrics"
            )
        )
    }

    @Test
    fun `stopped sessions are not selected as a new source`() {
        val stopped = candidate(
            index = 0,
            packageName = "com.tencent.wecarflow",
            playbackState = PlaybackState.STATE_STOPPED,
            audioUsage = AudioAttributes.USAGE_MEDIA,
            title = true
        )

        assertNull(
            MediaSessionSelectionPolicy.select(
                candidates = listOf(stopped),
                currentIndex = null,
                ownPackageName = "com.ninepointnine.desktoplyrics"
            )
        )
    }

    @Test
    fun `speech sessions are not accepted even when they are active`() {
        val speech = candidate(
            index = 0,
            packageName = "com.example.voice",
            playbackState = PlaybackState.STATE_PLAYING,
            audioUsage = AudioAttributes.USAGE_MEDIA,
            audioContentType = AudioAttributes.CONTENT_TYPE_SPEECH,
            title = true
        )

        assertNull(
            MediaSessionSelectionPolicy.select(
                candidates = listOf(speech),
                currentIndex = null,
                ownPackageName = "com.ninepointnine.desktoplyrics"
            )
        )
    }

    @Test
    fun `system session order wins when more than one source is playing`() {
        val first = candidate(
            index = 0,
            packageName = "com.tencent.wecarflow",
            playbackState = PlaybackState.STATE_PLAYING,
            audioUsage = AudioAttributes.USAGE_MEDIA,
            title = true
        )
        val second = first.copy(index = 1, packageName = "com.example.player")

        assertEquals(
            0,
            MediaSessionSelectionPolicy.select(
                candidates = listOf(first, second),
                currentIndex = 1,
                ownPackageName = "com.ninepointnine.desktoplyrics"
            )
        )
    }

    @Test
    fun `playing session outranks an earlier buffering session`() {
        val buffering = candidate(
            index = 0,
            packageName = "com.tencent.wecarflow",
            playbackState = PlaybackState.STATE_BUFFERING,
            audioUsage = AudioAttributes.USAGE_MEDIA
        )
        val playing = buffering.copy(
            index = 1,
            packageName = "com.example.player",
            playbackState = PlaybackState.STATE_PLAYING
        )

        assertEquals(
            1,
            MediaSessionSelectionPolicy.select(
                candidates = listOf(buffering, playing),
                currentIndex = null,
                ownPackageName = "com.ninepointnine.desktoplyrics"
            )
        )
    }

    @Test
    fun `stopped current source does not resurrect an unrelated paused session`() {
        val stoppedCurrent = candidate(
            index = 0,
            packageName = "com.tencent.wecarflow",
            playbackState = PlaybackState.STATE_STOPPED,
            audioUsage = AudioAttributes.USAGE_MEDIA,
            title = true
        )
        val staleBluetooth = candidate(
            index = 1,
            packageName = "com.android.bluetooth",
            playbackState = PlaybackState.STATE_PAUSED,
            audioUsage = AudioAttributes.USAGE_MEDIA,
            title = true
        )

        assertNull(
            MediaSessionSelectionPolicy.select(
                candidates = listOf(stoppedCurrent, staleBluetooth),
                currentIndex = 0,
                hasCurrentSelection = true,
                ownPackageName = "com.ninepointnine.desktoplyrics"
            )
        )
        assertNull(
            MediaSessionSelectionPolicy.select(
                candidates = listOf(staleBluetooth),
                currentIndex = null,
                hasCurrentSelection = true,
                ownPackageName = "com.ninepointnine.desktoplyrics"
            )
        )
    }

    @Test
    fun `new active source can take over after the previous source disappears`() {
        val staleBluetooth = candidate(
            index = 0,
            packageName = "com.android.bluetooth",
            playbackState = PlaybackState.STATE_PAUSED,
            audioUsage = AudioAttributes.USAGE_MEDIA,
            title = true
        )
        val activeOnline = candidate(
            index = 1,
            packageName = "com.tencent.wecarflow",
            playbackState = PlaybackState.STATE_PLAYING,
            audioUsage = AudioAttributes.USAGE_MEDIA,
            title = true
        )

        assertEquals(
            1,
            MediaSessionSelectionPolicy.select(
                candidates = listOf(staleBluetooth, activeOnline),
                currentIndex = null,
                hasCurrentSelection = true,
                ownPackageName = "com.ninepointnine.desktoplyrics"
            )
        )
    }

    private fun candidate(
        index: Int,
        packageName: String,
        playbackState: Int?,
        audioUsage: Int? = null,
        audioContentType: Int? = null,
        playbackActions: Long = PlaybackState.ACTION_PLAY_PAUSE,
        title: Boolean = false
    ) = MediaSessionCandidate(
        index = index,
        packageName = packageName,
        playbackState = playbackState,
        audioUsage = audioUsage,
        audioContentType = audioContentType,
        playbackActions = playbackActions,
        hasTitle = title
    )
}
