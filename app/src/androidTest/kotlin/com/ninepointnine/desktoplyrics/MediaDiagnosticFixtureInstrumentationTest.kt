package com.ninepointnine.desktoplyrics

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the independently recording diagnostic APK with public, silent test events. */
@RunWith(AndroidJUnit4::class)
class MediaDiagnosticFixtureInstrumentationTest {
    @Test
    fun publicCallbacksIncludePausePlayTrackChangeAndDestruction() {
        assumeTrue("Synthetic media is only allowed on an emulator", Build.FINGERPRINT.startsWith("google/sdk_gphone"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = MediaSession(context, "03lyrics diagnostic fixture")
        try {
            fun metadata(id: String, duration: Long) {
                session.setMetadata(MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, id)
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Diagnostic fixture " + id)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Test fixture")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duration).build())
            }
            fun state(value: Int, position: Long) {
                session.setPlaybackState(PlaybackState.Builder()
                    .setState(value, position, if (value == PlaybackState.STATE_PLAYING) 1f else 0f,
                        SystemClock.elapsedRealtime())
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE).build())
                Thread.sleep(1_500L)
            }
            metadata("A", 249_773L)
            state(PlaybackState.STATE_PAUSED, 0)
            session.isActive = true
            Thread.sleep(1_500L)
            state(PlaybackState.STATE_PLAYING, 1_000)
            state(PlaybackState.STATE_PLAYING, 2_500)
            state(PlaybackState.STATE_PAUSED, 4_000)
            metadata("B", 140_000L)
            state(PlaybackState.STATE_PLAYING, 0)
            assertEquals(140_000L, session.controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION))
        } finally {
            session.release()
        }
    }
}
