package com.ninepointnine.desktoplyrics

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

    @Test
    fun outgoingLongLineStaysAtItsEndWhileNextLineStartsAtBeginning() {
        val firstLine = "This intentionally long first lyric must finish far beyond the visible top bar before the next lyric appears"
        val secondLine = "This intentionally long second lyric must enter at its starting edge before its own horizontal sweep begins"
        val lyrics = """
            [00:00.00]$firstLine
            [00:20.00]$secondLine
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

        waitForMarquee(true)
        evaluate(
            """
            (() => {
              const viewport = document.querySelector('.compact-line.current .compact-line-text');
              viewport.style.setProperty('--compact-delay', '0s');
              viewport.style.setProperty('--compact-duration', '0.05s');
              return true;
            })()
            """.trimIndent()
        )
        SystemClock.sleep(120)
        assertTrue("first line should reach a negative horizontal offset", currentTextOffset() < -4.0)

        updatePosition(19_910, speed = 0.1)
        val transition = waitForLineTransition(secondLine)

        assertTrue("next line should still be waiting at its timestamp", !transition.getBoolean("currentMarquee"))
        assertTrue("outgoing line must keep its completed marquee state", transition.getBoolean("outgoingMarquee"))
        assertTrue("next line should enter at its starting edge", transition.getDouble("currentOffset") > -1.0)
        assertTrue("outgoing line must not rewind while fading out", transition.getDouble("outgoingOffset") < -4.0)
    }

    @Test
    fun desktopSurfaceUsesStaticEdgeFadeWithoutAffectingTopbar() {
        evaluate("window.LobstaOverlay.setSurfaceMode('desktop'); true")
        val desktopViewportHeight = evaluate(
            "document.querySelector('.lyrics-viewport').clientHeight"
        )
        val desktopMask = evaluate(
            "getComputedStyle(document.querySelector('.lyrics-viewport')).webkitMaskImage"
        )
        assertTrue("desktop lyric viewport should use a linear alpha mask", desktopMask.contains("linear-gradient"))

        evaluate("window.LobstaOverlay.setDesktopVisibleRatio(0.5926); true")
        assertEquals(
            "updating the mask boundary must not reflow the lyric viewport",
            desktopViewportHeight,
            evaluate("document.querySelector('.lyrics-viewport').clientHeight")
        )
        assertEquals(
            "\"59.2600%\"",
            evaluate("document.documentElement.style.getPropertyValue('--desktop-visible-bottom')")
        )
        assertTrue(
            "desktop mask should fade at the clipped visible boundary",
            evaluate(
                "getComputedStyle(document.querySelector('.lyrics-viewport')).webkitMaskImage"
            ).contains("59.26%")
        )

        evaluate("window.LobstaOverlay.setSurfaceMode('topbar'); true")
        assertEquals(
            "topbar must not keep the desktop edge mask",
            "\"none\"",
            evaluate("getComputedStyle(document.querySelector('.lyrics-viewport')).webkitMaskImage")
        )
    }

    @Test
    fun desktopLineMotionCommitsFinalTypographyAndCleansUpCompositorStyles() {
        resizeWebView(1230, 810)
        val lyrics = """
            [00:00.00]This deliberately long opening lyric wraps across the wallpaper surface so layout changes are exercised during the transition
            [00:10.00]Second lyric line
            [00:20.00]Third lyric line
            [00:21.00]Fourth lyric line
            [00:22.00]Fifth lyric line
            [00:23.00]Sixth lyric line
        """.trimIndent()
        val translations = """
            [00:00.00]Opening translation
            [00:10.00]Second translation
            [00:20.00]Third translation
            [00:21.00]Fourth translation
            [00:22.00]Fifth translation
            [00:23.00]Sixth translation
        """.trimIndent()
        evaluate(
            """
            window.LobstaOverlay.setSurfaceMode('desktop');
            window.LobstaOverlay.setLyricsTranslationEnabled(true);
            window.LobstaOverlay.setDisplayPreferences({
              topbarFontScale:100, wallpaperFontScale:100,
              wallpaperBlur:true, wallpaperShadow:true,
              wallpaperSpacing:'standard', wallpaperFocus:'top'
            });
            window.LobstaOverlay.updatePlayback({
              hasSession:true, track:'Desktop Motion Test', artist:'Test Artist', album:'',
              state:'playing', positionMs:0, durationMs:30000, speed:1, timelineReady:true
            });
            window.LobstaOverlay.receiveLyrics(1, {
              lyrics:${JSONObject.quote(lyrics)},
              translatedLyrics:${JSONObject.quote(translations)},
              duration:30000
            });
            true
            """.trimIndent()
        )
        SystemClock.sleep(320)

        val initial = desktopLineMotionSnapshot(
            activeIndex = 0,
            outgoingIndex = 1,
            watchedIndex = 4
        )
        assertEquals(58.0, initial.getDouble("activeFontSize"), 0.1)
        assertEquals(28.0, initial.getDouble("translationFontSize"), 0.1)
        assertEquals(0, initial.getInt("motionNodeCount"))

        val prepared = desktopLineMotionSnapshot(
            activeIndex = 1,
            outgoingIndex = 0,
            positionMs = 10_000,
            watchedIndex = 4
        )
        assertEquals("the active line must already own its final layout size", 58.0, prepared.getDouble("activeFontSize"), 0.1)
        assertEquals("the outgoing line must already own its final layout size", 42.0, prepared.getDouble("outgoingFontSize"), 0.1)
        assertEquals("translation typography must not be scaled as the active line grows", 28.0, prepared.getDouble("translationFontSize"), 0.1)
        assertTrue("the active row should start from an inverse compositor transform", prepared.getString("activeLineTransform") != "none")
        assertTrue("the active original text should start from an inverse scale", prepared.getString("activeOriginalTransform") != "none")
        assertEquals(
            "the incoming translation must keep its pre-layout visual position",
            initial.getDouble("outgoingTranslationTop"),
            prepared.getDouble("activeTranslationTop"),
            0.5
        )
        assertEquals(
            "the outgoing translation must keep its pre-layout visual position",
            initial.getDouble("activeTranslationTop"),
            prepared.getDouble("outgoingTranslationTop"),
            0.5
        )
        assertEquals(
            "visible downstream rows must not jump when a wrapped active line changes layout",
            initial.getDouble("watchedTop"),
            prepared.getDouble("watchedTop"),
            0.5
        )

        val during = waitForDesktopLineMotion(activeIndex = 1, outgoingIndex = 0)
        assertEquals(58.0, during.getDouble("activeFontSize"), 0.1)
        assertEquals(42.0, during.getDouble("outgoingFontSize"), 0.1)
        assertEquals(28.0, during.getDouble("translationFontSize"), 0.1)
        assertEquals("transform, opacity", during.getString("lineTransitionProperty"))
        assertEquals("transform", during.getString("originalTransitionProperty"))
        assertEquals("transform", during.getString("translationTransitionProperty"))
        assertEquals("none", during.getString("activeFilter"))
        assertTrue("the outgoing row should keep the configured static blur", during.getString("outgoingFilter").contains("blur"))
        assertTrue("the active row should keep the configured static shadow", during.getString("activeShadow") != "none")
        assertTrue("the outgoing row should keep the configured static shadow", during.getString("outgoingShadow") != "none")

        SystemClock.sleep(320)
        val settled = desktopLineMotionSnapshot(activeIndex = 1, outgoingIndex = 0)
        assertEquals(0, settled.getInt("motionNodeCount"))
        assertTrue("temporary compositor styles must be removed after the transition", !settled.getBoolean("hasInlineMotion"))
        assertEquals("none", settled.getString("activeLineTransform"))
        assertEquals("none", settled.getString("activeOriginalTransform"))
        assertEquals("none", settled.getString("activeTranslationTransform"))
        assertEquals(58.0, settled.getDouble("activeFontSize"), 0.1)
        assertEquals(42.0, settled.getDouble("outgoingFontSize"), 0.1)

        updatePosition(20_000)
        updatePosition(0)
        val afterSeek = desktopLineMotionSnapshot(activeIndex = 0, outgoingIndex = 2)
        assertEquals("a non-adjacent seek must cancel temporary motion owners", 0, afterSeek.getInt("motionNodeCount"))
        assertTrue("a seek must not leave inline transforms or opacity", !afterSeek.getBoolean("hasInlineMotion"))

        updatePosition(10_000)
        evaluate("window.LobstaOverlay.setSurfaceMode('topbar'); true")
        val afterSurfaceChange = desktopLineMotionSnapshot(activeIndex = 1, outgoingIndex = 0)
        assertEquals("a surface change must cancel temporary motion owners", 0, afterSurfaceChange.getInt("motionNodeCount"))
        assertTrue("a surface change must not leave inline transforms or opacity", !afterSurfaceChange.getBoolean("hasInlineMotion"))
    }

    private fun updatePosition(positionMs: Long, speed: Double = 1.0) {
        evaluate(
            """
            window.LobstaOverlay.updatePlayback({
              hasSession:true, track:'Timing Test', artist:'Test Artist', album:'',
              state:'playing', positionMs:$positionMs, durationMs:30000, speed:$speed, timelineReady:true
            });
            true
            """.trimIndent()
        )
    }

    private fun resizeWebView(width: Int, height: Int) {
        instrumentation.runOnMainSync {
            webView.layoutParams = ViewGroup.LayoutParams(width, height)
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, width, height)
        }
    }

    private fun desktopLineMotionSnapshot(
        activeIndex: Int,
        outgoingIndex: Int,
        positionMs: Long? = null,
        watchedIndex: Int = activeIndex
    ): JSONObject = JSONObject(
        evaluate(
            """
            (() => {
              ${positionMs?.let {
                  """
                  window.LobstaOverlay.updatePlayback({
                    hasSession:true, track:'Desktop Motion Test', artist:'Test Artist', album:'',
                    state:'playing', positionMs:$it, durationMs:30000, speed:1, timelineReady:true
                  });
                  """.trimIndent()
              }.orEmpty()}
              const active = document.querySelector('.line[data-i="$activeIndex"]');
              const outgoing = document.querySelector('.line[data-i="$outgoingIndex"]');
              const watched = document.querySelector('.line[data-i="$watchedIndex"]');
              const activeOriginal = active && active.querySelector('.line-original');
              const activeTranslation = active && active.querySelector('.line-translation');
              const outgoingTranslation = outgoing && outgoing.querySelector('.line-translation');
              const activeStyle = getComputedStyle(active);
              const outgoingStyle = getComputedStyle(outgoing);
              const originalStyle = getComputedStyle(activeOriginal);
              const translationStyle = getComputedStyle(activeTranslation);
              const motionNodes = Array.from(document.querySelectorAll('.desktop-motion'));
              const hasInlineMotion = Array.from(document.querySelectorAll('.line')).some(line => {
                const original = line.querySelector('.line-original');
                const translation = line.querySelector('.line-translation');
                return !!line.style.transform || !!line.style.opacity ||
                  (!!original && !!original.style.transform) ||
                  (!!translation && !!translation.style.transform);
              });
              return {
                activeFontSize:parseFloat(originalStyle.fontSize),
                outgoingFontSize:parseFloat(getComputedStyle(outgoing.querySelector('.line-original')).fontSize),
                translationFontSize:parseFloat(getComputedStyle(activeTranslation).fontSize),
                activeLineTransform:activeStyle.transform,
                activeOriginalTransform:originalStyle.transform,
                activeTranslationTransform:translationStyle.transform,
                activeTranslationTop:activeTranslation.getBoundingClientRect().top,
                outgoingTranslationTop:outgoingTranslation.getBoundingClientRect().top,
                lineTransitionProperty:activeStyle.transitionProperty,
                originalTransitionProperty:originalStyle.transitionProperty,
                translationTransitionProperty:translationStyle.transitionProperty,
                activeFilter:activeStyle.filter,
                outgoingFilter:outgoingStyle.filter,
                activeShadow:activeStyle.textShadow,
                outgoingShadow:outgoingStyle.textShadow,
                watchedTop:watched.getBoundingClientRect().top,
                motionNodeCount:motionNodes.length,
                hasInlineMotion
              };
            })()
            """.trimIndent()
        )
    )

    private fun waitForDesktopLineMotion(activeIndex: Int, outgoingIndex: Int): JSONObject {
        val deadline = SystemClock.uptimeMillis() + 250
        var snapshot: JSONObject
        do {
            snapshot = desktopLineMotionSnapshot(activeIndex, outgoingIndex)
            if (snapshot.getString("lineTransitionProperty") == "transform, opacity" &&
                snapshot.getString("originalTransitionProperty") == "transform"
            ) {
                return snapshot
            }
            SystemClock.sleep(8)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("desktop row transition properties", "transform, opacity", snapshot.getString("lineTransitionProperty"))
        assertEquals("desktop original transition properties", "transform", snapshot.getString("originalTransitionProperty"))
        return snapshot
    }

    private fun waitForLineTransition(expectedCurrentText: String): JSONObject {
        val deadline = SystemClock.uptimeMillis() + 1_000
        var snapshot: JSONObject
        do {
            snapshot = lineTransitionSnapshot()
            if (snapshot.optString("currentText") == expectedCurrentText && snapshot.optBoolean("hasOutgoing")) {
                return snapshot
            }
            SystemClock.sleep(16)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("current compact lyric", expectedCurrentText, snapshot.optString("currentText"))
        assertTrue("outgoing compact lyric should still be present", snapshot.optBoolean("hasOutgoing"))
        return snapshot
    }

    private fun lineTransitionSnapshot(): JSONObject = JSONObject(
        evaluate(
            """
            (() => {
              const current = document.querySelector('.compact-line.current');
              const outgoing = document.querySelector('.compact-line.outgoing');
              const state = (line) => {
                const viewport = line && line.querySelector('.compact-line-text');
                const content = viewport && viewport.querySelector('.compact-scroll-text');
                return {
                  text: content ? content.textContent : '',
                  marquee: !!viewport && viewport.classList.contains('marquee'),
                  offset: content && viewport
                    ? content.getBoundingClientRect().left - viewport.getBoundingClientRect().left
                    : 0
                };
              };
              const currentState = state(current);
              const outgoingState = state(outgoing);
              return {
                currentText: currentState.text,
                currentMarquee: currentState.marquee,
                currentOffset: currentState.offset,
                hasOutgoing: !!outgoing,
                outgoingMarquee: outgoingState.marquee,
                outgoingOffset: outgoingState.offset
              };
            })()
            """.trimIndent()
        )
    )

    private fun currentTextOffset(): Double = evaluate(
        """
        (() => {
          const viewport = document.querySelector('.compact-line.current .compact-line-text');
          const content = viewport && viewport.querySelector('.compact-scroll-text');
          return content && viewport
            ? content.getBoundingClientRect().left - viewport.getBoundingClientRect().left
            : 0;
        })()
        """.trimIndent()
    ).toDouble()

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
