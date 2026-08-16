package com.tcrrry.desktoplyrics

import android.annotation.SuppressLint
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LyricsOverlayTimingInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var activity: MainActivity
    private lateinit var webView: WebView

    @Before
    @SuppressLint("SetJavaScriptEnabled")
    fun loadOverlay() {
        val loaded = CountDownLatch(1)
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        instrumentation.runOnMainSync {
            webView = WebView(activity).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        loaded.countDown()
                    }
                }
                measure(
                    View.MeasureSpec.makeMeasureSpec(560, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(72, View.MeasureSpec.EXACTLY)
                )
                layout(0, 0, 560, 72)
                loadUrl("file:///android_asset/lyrics_overlay.html")
            }
            activity.setContentView(webView, ViewGroup.LayoutParams(560, 72))
        }
        assertTrue("lyrics overlay did not load", loaded.await(10, TimeUnit.SECONDS))
        evaluate("window.LobstaOverlay.setSurfaceMode('topbar'); true")
    }

    @After
    fun destroyOverlay() {
        if (::webView.isInitialized) {
            instrumentation.runOnMainSync {
                webView.stopLoading()
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
                if (::activity.isInitialized) activity.finish()
            }
        }
    }

    @Test
    fun longFirstLineStartsMarqueeOnlyWhenItsTimestampIsReached() {
        val lyrics = """
            [00:10.00]This intentionally long first lyric must remain at its starting edge throughout the entire instrumental intro before the vocal begins
            [00:20.00]Second line
        """.trimIndent()
        evaluate(
            """
            window.LobstaOverlay.updatePlayback({
              hasSession:true, track:'Timing Test', artist:'Test Artist', album:'',
              state:'playing', positionMs:0, durationMs:30000, speed:1, timelineReady:true
            });
            window.LobstaOverlay.receiveLyrics(1, {
              lyrics:${JSONObject.quote(lyrics)}, duration:30000
            });
            true
            """.trimIndent()
        )

        assertMarqueeRemains(false, 250)

        updatePosition(10_000)
        waitForMarquee(true)

        updatePosition(0)
        waitForMarquee(false)
        assertMarqueeRemains(false, 150)
    }

    private fun updatePosition(positionMs: Long) {
        evaluate(
            """
            window.LobstaOverlay.updatePlayback({
              hasSession:true, track:'Timing Test', artist:'Test Artist', album:'',
              state:'playing', positionMs:$positionMs, durationMs:30000, speed:1, timelineReady:true
            });
            true
            """.trimIndent()
        )
    }

    private fun waitForMarquee(expected: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 3_000
        var actual: Boolean
        do {
            actual = marqueeActive()
            if (actual == expected) return
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("compact marquee state", expected, actual)
    }

    private fun assertMarqueeRemains(expected: Boolean, durationMs: Long) {
        val deadline = SystemClock.uptimeMillis() + durationMs
        do {
            assertEquals("compact marquee state", expected, marqueeActive())
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
    }

    private fun marqueeActive(): Boolean = evaluate(
        """
        (() => {
          const viewport = document.querySelector('.compact-line.current .compact-line-text');
          return !!viewport && viewport.classList.contains('marquee');
        })()
        """.trimIndent()
    ) == "true"

    private fun evaluate(script: String): String {
        val evaluated = CountDownLatch(1)
        var result: String? = null
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(script) { value ->
                result = value
                evaluated.countDown()
            }
        }
        assertTrue("JavaScript evaluation timed out", evaluated.await(5, TimeUnit.SECONDS))
        return requireNotNull(result)
    }
}
