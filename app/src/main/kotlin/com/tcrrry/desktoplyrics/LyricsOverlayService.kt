package com.tcrrry.desktoplyrics

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Base64
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A user-started overlay that consumes MediaSession callbacks directly on-device.
 * Network requests are only used to resolve lyrics/cover art; playback synchronization
 * never waits for the website status polling path.
 */
@SuppressLint("ForegroundServiceType")
class LyricsOverlayService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val windowManager by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private val sessionManager by lazy { getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager }
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val listenerComponent by lazy { ComponentName(this, MediaListenerService::class.java) }
    private val lyricsRepository = DirectLyricsRepository()
    private val lyricsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var overlayRoot: FrameLayout? = null
    private var chromeBar: LinearLayout? = null
    private var dragTouchArea: View? = null
    private var scaleButton: TextView? = null
    private var closeButton: TextView? = null
    private var webView: WebView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var webReady = false
    private var compact = false
    private var backgroundMode = BACKGROUND_DEFAULT
    private var fontScalePercent = FONT_SCALE_DEFAULT_PERCENT
    private var monitorStarted = false
    private var audioRouteMonitorStarted = false
    private var currentController: MediaController? = null
    private var pendingSnapshot: JSONObject? = null
    private var cachedArtworkKey = ""
    private var cachedArtworkDataUrl = ""
    private var snapshotScheduled = false
    @Volatile private var latestLyricsRequestId = 0

    private val dispatchRunnable = Runnable {
        snapshotScheduled = false
        dispatchSnapshot()
    }
    private val sessionRefreshRunnable = object : Runnable {
        override fun run() {
            if (!monitorStarted) return
            refreshActiveSessions()
            mainHandler.postDelayed(this, 2_000L)
        }
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = scheduleSnapshot()
        override fun onPlaybackStateChanged(state: PlaybackState?) = scheduleSnapshot()
        override fun onSessionDestroyed() = refreshActiveSessions()
    }

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            selectController(controllers.orEmpty())
        }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = scheduleSnapshot()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = scheduleSnapshot()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        announceOverlayState()
        backgroundMode = normalizedBackgroundMode(
            prefs.getString(PREF_BACKGROUND_MODE, BACKGROUND_DEFAULT)
        )
        fontScalePercent = normalizedFontScale(
            prefs.getInt(PREF_FONT_SCALE_PERCENT, FONT_SCALE_DEFAULT_PERCENT)
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_SET_BACKGROUND) {
            backgroundMode = normalizedBackgroundMode(
                intent.getStringExtra(EXTRA_BACKGROUND_MODE)
            )
            prefs.edit().putString(PREF_BACKGROUND_MODE, backgroundMode).apply()
            applyBackgroundMode()
            if (overlayRoot != null) return START_STICKY
        }

        if (intent?.action == ACTION_SET_FONT_SCALE) {
            val previousPercent = fontScalePercent
            fontScalePercent = normalizedFontScale(
                intent.getIntExtra(EXTRA_FONT_SCALE_PERCENT, FONT_SCALE_DEFAULT_PERCENT)
            )
            prefs.edit().putInt(PREF_FONT_SCALE_PERCENT, fontScalePercent).apply()
            applyFontScale(previousPercent, adjustCompactHeight = true)
            if (overlayRoot != null) return START_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        if (overlayRoot == null) createOverlay()
        startMediaMonitor()
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        lyricsScope.cancel()
        lyricsRepository.close()
        stopMediaMonitor()
        val player = webView
        (player?.parent as? ViewGroup)?.removeView(player)
        overlayRoot?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        player?.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        webView = null
        overlayRoot = null
        chromeBar = null
        dragTouchArea = null
        scaleButton = null
        closeButton = null
        isRunning = false
        announceOverlayState()
        super.onDestroy()
    }

    private fun announceOverlayState() {
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_RUNNING, isRunning)
        )
    }

    private inner class LyricsJavascriptBridge {
        @JavascriptInterface
        fun requestLyrics(track: String, artist: String, requestId: Int, needsRemoteCover: Boolean) {
            if (track.isBlank() || requestId <= 0) return
            latestLyricsRequestId = requestId
            lyricsScope.launch {
                val startedAt = SystemClock.elapsedRealtime()
                val coverLookup = if (needsRemoteCover) {
                    async { lyricsRepository.resolveCover(track, artist) }
                } else null
                val result = lyricsRepository.resolveLyrics(track, artist)
                if (requestId != latestLyricsRequestId) {
                    coverLookup?.cancel()
                    return@launch
                }
                Log.i(
                    LOG_TAG,
                    "Direct lyrics source=${result.source.ifBlank { "none" }} " +
                        "found=${result.lyrics.isNotBlank()} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
                )
                deliverLyricsResult(requestId, result)

                if (needsRemoteCover && result.cover.isBlank()) {
                    val cover = runCatching { coverLookup?.await().orEmpty() }.getOrDefault("")
                    if (cover.isNotBlank() && requestId == latestLyricsRequestId) {
                        deliverRemoteCover(requestId, cover)
                    }
                } else {
                    coverLookup?.cancel()
                }
            }
        }
    }

    private fun deliverLyricsResult(requestId: Int, result: DirectLyricsRepository.Result) {
        val payload = result.toJson().toString()
        mainHandler.post {
            if (requestId != latestLyricsRequestId || !webReady) return@post
            webView?.evaluateJavascript(
                "window.LobstaOverlay && window.LobstaOverlay.receiveLyrics($requestId,$payload);",
                null
            )
        }
    }

    private fun deliverRemoteCover(requestId: Int, cover: String) {
        val encodedCover = JSONObject.quote(cover)
        mainHandler.post {
            if (requestId != latestLyricsRequestId || !webReady) return@post
            webView?.evaluateJavascript(
                "window.LobstaOverlay && window.LobstaOverlay.receiveRemoteCover($requestId,$encodedCover);",
                null
            )
        }
    }

    private fun startAsForeground() {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, LyricsOverlayService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("${getString(R.string.app_name)} 正在监听")
            .setContentText("本地实时同步当前媒体会话")
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "关闭悬浮窗", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "歌词悬浮窗",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持本地歌词悬浮窗与 MediaSession 实时同步"
                setShowBadge(false)
            }
        )
    }

    @Suppress("SetJavaScriptEnabled")
    private fun createOverlay() {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val minWidth = minimumOverlayWidth()
        val maxWidth = max(minWidth, screenWidth - dp(8))
        val maxHeight = max(dp(300), screenHeight - dp(48))
        val normalWidth = prefs.getInt("width", min(dp(360), screenWidth - dp(24)))
            .coerceIn(minWidth, maxWidth)
        val wasCompact = prefs.getBoolean("compact", false)
        val storedNormalHeight = prefs.getInt("height", dp(520))
        val compactMinimumHeight = dp(compactMinimumHeightDp(fontScalePercent))
        val storedCompactHeight = prefs.getInt("compact_height_v3", max(dp(48), compactMinimumHeight))
        val activeStoredHeight = if (wasCompact) storedCompactHeight else storedNormalHeight
        compact = activeStoredHeight <= dp(COMPACT_MAX_HEIGHT_DP)
        val normalHeightSource = if (!compact && wasCompact) activeStoredHeight else storedNormalHeight
        val compactHeightSource = if (compact && !wasCompact) activeStoredHeight else storedCompactHeight
        val normalHeight = normalHeightSource
            .coerceIn(dp(COMPACT_MAX_HEIGHT_DP + 1), maxHeight)
        val compactHeight = compactHeightSource
            .coerceIn(compactMinimumHeight, dp(COMPACT_MAX_HEIGHT_DP))
        if (compact != wasCompact) {
            val migration = prefs.edit().putBoolean("compact", compact)
            if (compact) migration.putInt("compact_height_v3", compactHeight)
            else migration.putInt("height", normalHeight)
            migration.apply()
        }

        val params = WindowManager.LayoutParams(
            normalWidth,
            if (compact) compactHeight else normalHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", max(dp(12), screenWidth - normalWidth - dp(12)))
                .coerceIn(0, max(0, screenWidth - normalWidth))
            y = prefs.getInt("y", dp(96))
                .coerceIn(0, max(0, screenHeight - if (compact) compactHeight else normalHeight))
        }
        windowParams = params

        val root = FrameLayout(this).apply {
            clipToOutline = true
            elevation = dp(14).toFloat()
            background = overlayBackground(compact)
        }
        overlayRoot = root

        val chrome = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.TRANSPARENT)
        }
        chromeBar = chrome

        val dragArea = View(this)
        dragTouchArea = dragArea

        val closeControl = chromeButton("×") {
            stopSelf()
        }.apply { textSize = 17f }
        closeButton = closeControl
        chrome.addView(closeControl, LinearLayout.LayoutParams(dp(26), dp(26)))

        val resizeButton = chromeButton("↘") {
            toggleCompact(normalHeight)
        }.apply { textSize = 14f }
        scaleButton = resizeButton
        chrome.addView(resizeButton, LinearLayout.LayoutParams(dp(26), dp(26)))

        val dragTouch = object : View.OnTouchListener {
            var downRawX = 0f
            var downRawY = 0f
            var downX = 0
            var downY = 0

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val lp = windowParams ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        downX = lp.x
                        downY = lp.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val maxX = max(0, resources.displayMetrics.widthPixels - lp.width)
                        val maxY = max(0, resources.displayMetrics.heightPixels - lp.height)
                        lp.x = (downX + event.rawX - downRawX).toInt().coerceIn(0, maxX)
                        lp.y = (downY + event.rawY - downRawY).toInt().coerceIn(0, maxY)
                        overlayRoot?.let { windowManager.updateViewLayout(it, lp) }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                        return true
                    }
                }
                return false
            }
        }
        dragArea.setOnTouchListener(dragTouch)

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        resizeButton.setOnTouchListener(object : View.OnTouchListener {
            var downRawX = 0f
            var downRawY = 0f
            var downX = 0
            var downY = 0
            var downWidth = 0
            var downHeight = 0
            var resizing = false
            var movedBeforeLongPress = false
            var pendingLongPress: Runnable? = null

            fun cancelLongPress() {
                pendingLongPress?.let(mainHandler::removeCallbacks)
                pendingLongPress = null
            }

            fun saveSize(lp: WindowManager.LayoutParams) {
                val edit = prefs.edit()
                    .putInt("width", lp.width)
                    .putInt("x", lp.x)
                    .putInt("y", lp.y)
                    .putBoolean("compact", compact)
                if (compact) {
                    edit.putInt("compact_height_v3", lp.height)
                } else {
                    edit.putInt("height", lp.height)
                }
                edit.apply()
            }

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val lp = windowParams ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        downX = lp.x
                        downY = lp.y
                        downWidth = lp.width
                        downHeight = lp.height
                        resizing = false
                        movedBeforeLongPress = false
                        pendingLongPress = Runnable {
                            resizing = true
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }.also {
                            mainHandler.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong())
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - downRawX
                        val deltaY = event.rawY - downRawY
                        if (!resizing && max(kotlin.math.abs(deltaX), kotlin.math.abs(deltaY)) > touchSlop.toFloat()) {
                            movedBeforeLongPress = true
                            cancelLongPress()
                        }
                        if (!resizing) return true
                        val minimumWidth = minimumOverlayWidth()
                        val availableWidth = max(minimumWidth, resources.displayMetrics.widthPixels - downX)
                        lp.width = (downWidth + deltaX).toInt()
                            .coerceIn(minimumWidth, availableWidth)
                        val bottomEdge = downY + downHeight
                        val minimumHeight = dp(compactMinimumHeightDp(fontScalePercent))
                        val maximumHeight = max(minimumHeight, bottomEdge)
                        lp.height = (downHeight - deltaY).toInt()
                            .coerceIn(minimumHeight, maximumHeight)
                        lp.y = bottomEdge - lp.height
                        overlayRoot?.let { windowManager.updateViewLayout(it, lp) }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        cancelLongPress()
                        if (resizing) {
                            val compactForSize = lp.height <= dp(COMPACT_MAX_HEIGHT_DP)
                            if (compactForSize != compact) {
                                setCompactUi(compactForSize)
                            }
                            saveSize(lp)
                        } else if (!movedBeforeLongPress) {
                            view.performClick()
                        }
                        resizing = false
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        cancelLongPress()
                        if (resizing) saveSize(lp)
                        resizing = false
                        return true
                    }
                }
                return false
            }
        })

        val webContainer = FrameLayout(this)
        root.addView(webContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val player = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(LyricsJavascriptBridge(), "LobstaNativeLyrics")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = true

                override fun onPageFinished(view: WebView?, url: String?) {
                    webReady = true
                    applyFontScale(fontScalePercent, adjustCompactHeight = false)
                    evaluateJavascript(
                        "window.LobstaOverlay && window.LobstaOverlay.setCompact($compact);",
                        null
                    )
                    applyBackgroundMode()
                    pendingSnapshot?.let { deliverToWeb(it) } ?: scheduleSnapshot()
                }
            }
            loadUrl("file:///android_asset/lyrics_overlay.html")
        }
        webView = player
        webContainer.addView(player, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(dragArea, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(chrome, FrameLayout.LayoutParams(
            if (compact) dp(26) else ViewGroup.LayoutParams.WRAP_CONTENT,
            if (compact) ViewGroup.LayoutParams.MATCH_PARENT else dp(28),
            if (compact) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.END or Gravity.BOTTOM
        ))
        updateControlLayout(compact)

        windowManager.addView(root, params)
    }

    private fun minimumOverlayWidth(): Int {
        val oneThirdScreen = resources.displayMetrics.widthPixels / 3
        return oneThirdScreen.coerceIn(dp(112), dp(140))
    }

    private fun chromeButton(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.argb(225, 255, 255, 255))
        textSize = 11f
        gravity = Gravity.CENTER
        setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.argb(190, 0, 0, 0))
        setOnClickListener { action() }
    }

    private fun overlayBackground(isCompact: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(22).toFloat()
        setColor(Color.TRANSPARENT)
        if (!isCompact) {
            setStroke(dp(1), Color.argb(45, 255, 255, 255))
        }
    }

    private fun toggleCompact(normalHeight: Int) {
        val nextCompact = !compact
        val lp = windowParams ?: return
        lp.height = if (nextCompact) {
            prefs.getInt("compact_height_v3", dp(48))
                .coerceIn(dp(compactMinimumHeightDp(fontScalePercent)), dp(COMPACT_MAX_HEIGHT_DP))
        } else {
            prefs.getInt("height", normalHeight)
                .coerceAtLeast(dp(COMPACT_MAX_HEIGHT_DP + 1))
        }
        setCompactUi(nextCompact)
        overlayRoot?.let { windowManager.updateViewLayout(it, lp) }
    }

    private fun setCompactUi(value: Boolean) {
        compact = value
        prefs.edit().putBoolean("compact", compact).apply()
        overlayRoot?.background = overlayBackground(compact)
        updateControlLayout(compact)
        webView?.evaluateJavascript(
            "window.LobstaOverlay && window.LobstaOverlay.setCompact($compact);",
            null
        )
    }

    private fun applyBackgroundMode() {
        val encodedMode = JSONObject.quote(backgroundMode)
        webView?.evaluateJavascript(
            "window.LobstaOverlay && window.LobstaOverlay.setBackgroundMode($encodedMode);",
            null
        )
    }

    private fun applyFontScale(previousPercent: Int, adjustCompactHeight: Boolean) {
        webView?.evaluateJavascript(
            "window.LobstaOverlay && window.LobstaOverlay.setFontScale($fontScalePercent);",
            null
        )
        if (!adjustCompactHeight || !compact) return

        val lp = windowParams ?: return
        val previousMinimum = dp(compactMinimumHeightDp(previousPercent))
        val nextMinimum = dp(compactMinimumHeightDp(fontScalePercent))
        val wasAtMinimum = lp.height <= previousMinimum + dp(2)
        val nextHeight = if (wasAtMinimum) nextMinimum else max(lp.height, nextMinimum)
        if (nextHeight == lp.height) return

        lp.height = nextHeight.coerceAtMost(dp(COMPACT_MAX_HEIGHT_DP))
        val screenHeight = resources.displayMetrics.heightPixels
        lp.y = lp.y.coerceIn(0, max(0, screenHeight - lp.height))
        overlayRoot?.let { windowManager.updateViewLayout(it, lp) }
        prefs.edit().putInt("compact_height_v3", lp.height).apply()
    }

    private fun normalizedBackgroundMode(value: String?): String = when (value) {
        BACKGROUND_TRANSPARENT -> BACKGROUND_TRANSPARENT
        BACKGROUND_LOW -> BACKGROUND_LOW
        BACKGROUND_HIGH -> BACKGROUND_HIGH
        else -> BACKGROUND_DEFAULT
    }

    private fun normalizedFontScale(value: Int): Int =
        value.coerceIn(FONT_SCALE_MIN_PERCENT, FONT_SCALE_MAX_PERCENT)

    private fun updateControlLayout(isCompact: Boolean) {
        val chrome = chromeBar ?: return
        chrome.orientation = if (isCompact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        chrome.gravity = Gravity.CENTER
        scaleButton?.text = if (isCompact) "↙" else "↖"

        chrome.removeAllViews()
        if (isCompact) {
            scaleButton?.let(chrome::addView)
            closeButton?.let(chrome::addView)
        } else {
            scaleButton?.let(chrome::addView)
            closeButton?.let(chrome::addView)
        }

        val params = (chrome.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28))
        params.width = if (isCompact) dp(26) else ViewGroup.LayoutParams.WRAP_CONTENT
        params.height = if (isCompact) ViewGroup.LayoutParams.MATCH_PARENT else dp(24)
        params.gravity = if (isCompact) {
            Gravity.END or Gravity.CENTER_VERTICAL
        } else {
            Gravity.END or Gravity.TOP
        }
        params.setMargins(0, if (isCompact) 0 else dp(16), if (isCompact) 0 else dp(17), 0)
        chrome.layoutParams = params

        if (isCompact) {
            closeButton?.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            scaleButton?.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        } else {
            closeButton?.layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            scaleButton?.layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        }
        chrome.requestLayout()

        dragTouchArea?.let { area ->
            val areaParams = (area.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            areaParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            areaParams.height = if (isCompact) ViewGroup.LayoutParams.MATCH_PARENT else dp(112)
            areaParams.gravity = Gravity.TOP
            area.layoutParams = areaParams
            area.requestLayout()
        }
    }

    private fun startMediaMonitor() {
        if (monitorStarted) {
            refreshActiveSessions()
            return
        }
        startAudioRouteMonitor()
        try {
            sessionManager.addOnActiveSessionsChangedListener(activeSessionsListener, listenerComponent)
            monitorStarted = true
            refreshActiveSessions()
            mainHandler.removeCallbacks(sessionRefreshRunnable)
            mainHandler.postDelayed(sessionRefreshRunnable, 750L)
        } catch (_: SecurityException) {
            pendingSnapshot = JSONObject()
                .put("hasSession", false)
                .put("permissionRequired", true)
            pendingSnapshot?.let { deliverToWeb(it) }
            mainHandler.postDelayed({
                if (!monitorStarted) startMediaMonitor()
            }, 1_500L)
        }
    }

    private fun stopMediaMonitor() {
        mainHandler.removeCallbacks(sessionRefreshRunnable)
        stopAudioRouteMonitor()
        if (monitorStarted) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener)
            } catch (_: Exception) {
            }
        }
        monitorStarted = false
        currentController?.unregisterCallback(controllerCallback)
        currentController = null
    }

    private fun startAudioRouteMonitor() {
        if (audioRouteMonitorStarted) return
        try {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
            audioRouteMonitorStarted = true
        } catch (_: Exception) {
        }
    }

    private fun stopAudioRouteMonitor() {
        if (!audioRouteMonitorStarted) return
        try {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        } catch (_: Exception) {
        }
        audioRouteMonitorStarted = false
    }

    private fun refreshActiveSessions() {
        try {
            val controllers = sessionManager.getActiveSessions(listenerComponent)
            selectController(controllers)
        } catch (error: SecurityException) {
            pendingSnapshot = JSONObject()
                .put("hasSession", false)
                .put("permissionRequired", true)
            pendingSnapshot?.let { deliverToWeb(it) }
        }
    }

    private fun selectController(controllers: List<MediaController>) {
        val best = controllers
            .asSequence()
            .filter { it.packageName != packageName }
            .filter { isSupportedMusicPackage(it.packageName) }
            .maxByOrNull { controllerScore(it) }

        if (best?.sessionToken == currentController?.sessionToken) {
            scheduleSnapshot()
            return
        }

        currentController?.unregisterCallback(controllerCallback)
        currentController = best
        cachedArtworkKey = ""
        cachedArtworkDataUrl = ""
        best?.registerCallback(controllerCallback, mainHandler)
        scheduleSnapshot()
    }

    private fun controllerScore(controller: MediaController): Int {
        val stateScore = when (controller.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> 1000
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> 800
            PlaybackState.STATE_PAUSED -> 600
            else -> 100
        }
        val metadataScore = if (!mediaTitle(controller.metadata).isNullOrBlank()) 100 else 0
        return stateScore + metadataScore
    }

    private fun isSupportedMusicPackage(packageName: String): Boolean {
        val p = packageName.lowercase(Locale.ROOT)
        val exactOrPrefix = listOf(
            "com.apple.android.music",
            "com.spotify.music",
            "com.netease.cloudmusic",
            "com.tencent.qqmusic",
            "com.kugou.android",
            "cn.kuwo.player",
            "com.kuwo.player",
            "com.google.android.apps.youtube.music",
            "com.amazon.mp3",
            "com.soundcloud.android",
            "deezer.android.app",
            "com.aspiro.tidal",
            "com.miui.player",
            "com.sec.android.app.music",
            "com.maxmpz.audioplayer",
            "in.krosbits.musicolet",
            "com.aimp.player",
            "com.fiio.music",
            "com.plexamp.android",
            "org.videolan.vlc"
        )
        return exactOrPrefix.any { p == it || p.startsWith("$it.") } ||
            (p.contains("music") && !p.contains("bilibili"))
    }

    private fun scheduleSnapshot() {
        if (snapshotScheduled) return
        snapshotScheduled = true
        mainHandler.postDelayed(dispatchRunnable, 35)
    }

    private fun dispatchSnapshot() {
        val controller = currentController
        val snapshot = if (controller == null) {
            JSONObject().put("hasSession", false).put("permissionRequired", false)
        } else {
            buildSnapshot(controller)
        }
        pendingSnapshot = snapshot
        deliverToWeb(snapshot)
    }

    private fun buildSnapshot(controller: MediaController): JSONObject {
        val metadata = controller.metadata
        val playback = controller.playbackState
        val title = mediaTitle(metadata).orEmpty()
        val artist = firstMetadataString(
            metadata,
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_AUTHOR,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE
        ).orEmpty()
        val album = firstMetadataString(metadata, MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val state = when (playback?.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> "buffering"
            PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE -> "stopped"
            else -> "paused"
        }
        val speed = playback?.playbackSpeed?.toDouble() ?: 0.0
        val position = currentPosition(playback, duration)
        val artwork = artworkDataUrl(metadata, "$title\u0000$artist\u0000$album")

        return JSONObject()
            .put("hasSession", title.isNotBlank() || playback != null)
            .put("permissionRequired", false)
            .put("track", title)
            .put("artist", artist)
            .put("album", album)
            .put("packageName", controller.packageName)
            .put("state", state)
            .put("positionMs", position)
            .put("durationMs", max(0L, duration))
            .put("speed", if (speed.isFinite()) speed else 1.0)
            .put("capturedAtMs", System.currentTimeMillis())
            .put("cover", artwork)
            .put("volumePct", mediaVolumePercent())
            .put("audioDevice", currentAudioDeviceLabel())
    }

    private fun mediaVolumePercent(): Int {
        return try {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (maxVolume > 0) (current * 100f / maxVolume).toInt().coerceIn(0, 100) else 0
        } catch (_: Exception) {
            0
        }
    }

    private fun currentAudioDeviceLabel(): String {
        return try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val selected = devices.maxByOrNull(::audioDeviceScore)
            if (selected == null) return "未知设备"
            val productName = selected.productName?.toString()?.trim().orEmpty()
            if (selected.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                audioDeviceTypeLabel(selected.type)
            } else if (isBluetoothAudioDevice(selected.type)) {
                connectedBluetoothName(selected)
                    ?: productName.takeUnless(::isLikelyPhoneName)
                    ?: audioDeviceTypeLabel(selected.type)
            } else {
                productName.ifBlank { audioDeviceTypeLabel(selected.type) }
            }
        } catch (_: Exception) {
            "未知设备"
        }
    }

    private fun audioDeviceScore(device: AudioDeviceInfo): Int {
        var score = audioDevicePriority(device.type)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val address = device.address.trim()
            if (address.isNotBlank() && address != "00:00:00:00:00:00") score += 5
        }
        val productName = device.productName?.toString()?.trim().orEmpty()
        if (productName.isNotBlank() && !isLikelyPhoneName(productName)) score += 2
        return score
    }

    private fun isBluetoothAudioDevice(type: Int): Boolean = type in setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER
    )

    @SuppressLint("MissingPermission")
    private fun connectedBluetoothName(audioDevice: AudioDeviceInfo): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return null

        return try {
            val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                audioDevice.address.trim()
            } else {
                ""
            }
            if (address.isBlank()) return null
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return null
            val remoteName = runCatching { adapter.getRemoteDevice(address).name }.getOrNull()
            val bondedName = adapter.bondedDevices
                .firstOrNull { it.address.equals(address, ignoreCase = true) }
                ?.name
            (remoteName ?: bondedName)
                ?.trim()
                ?.takeIf { it.isNotBlank() && !isLikelyPhoneName(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun isLikelyPhoneName(value: String): Boolean {
        val normalized = value.trim().lowercase(Locale.ROOT).replace(" ", "")
        if (normalized.isBlank()) return true
        return listOf(
            Build.MODEL,
            Build.DEVICE,
            Build.PRODUCT,
            "${Build.MANUFACTURER}${Build.MODEL}"
        ).any { localName ->
            val local = localName.trim().lowercase(Locale.ROOT).replace(" ", "")
            local.isNotBlank() && (normalized == local || normalized.contains(local))
        }
    }

    private fun audioDevicePriority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 120
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> 115
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 100
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> 90
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 80
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC -> 70
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 50
        else -> 10
    }

    private fun audioDeviceTypeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "蓝牙音频"
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 音频"
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI 音频"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "手机扬声器"
        else -> "音频设备"
    }

    private fun currentPosition(state: PlaybackState?, duration: Long): Long {
        if (state == null) return 0L
        var position = max(0L, state.position)
        if (state.state == PlaybackState.STATE_PLAYING && state.playbackSpeed > 0f) {
            val elapsed = max(0L, SystemClock.elapsedRealtime() - state.lastPositionUpdateTime)
            position += (elapsed * state.playbackSpeed).toLong()
        }
        return if (duration > 0) min(position, duration) else position
    }

    private fun mediaTitle(metadata: MediaMetadata?): String? = firstMetadataString(
        metadata,
        MediaMetadata.METADATA_KEY_TITLE,
        MediaMetadata.METADATA_KEY_DISPLAY_TITLE
    )

    private fun firstMetadataString(metadata: MediaMetadata?, vararg keys: String): String? {
        if (metadata == null) return null
        for (key in keys) {
            metadata.getString(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    private fun artworkDataUrl(metadata: MediaMetadata?, key: String): String {
        // Media apps often publish title/artist first and artwork in a later callback.
        // Do not permanently cache an empty first result for the lifetime of the track.
        if (key == cachedArtworkKey && cachedArtworkDataUrl.isNotEmpty()) {
            return cachedArtworkDataUrl
        }
        cachedArtworkKey = key
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        cachedArtworkDataUrl = bitmap?.let { bitmapDataUrl(it) }.orEmpty()
        return cachedArtworkDataUrl
    }

    private fun bitmapDataUrl(source: Bitmap): String {
        return try {
            val maxSide = max(source.width, source.height)
            val scaled = if (maxSide > 640) {
                val ratio = 640f / maxSide.toFloat()
                Bitmap.createScaledBitmap(
                    source,
                    max(1, (source.width * ratio).toInt()),
                    max(1, (source.height * ratio).toInt()),
                    true
                )
            } else {
                source
            }
            val bytes = ByteArrayOutputStream().use { output ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, output)
                output.toByteArray()
            }
            if (scaled !== source) scaled.recycle()
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    private fun deliverToWeb(snapshot: JSONObject) {
        pendingSnapshot = snapshot
        if (!webReady) return
        webView?.post {
            webView?.evaluateJavascript(
                "window.LobstaOverlay && window.LobstaOverlay.updatePlayback($snapshot);",
                null
            )
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val ACTION_START = "com.tcrrry.desktoplyrics.action.START_LYRICS_OVERLAY"
        const val ACTION_STOP = "com.tcrrry.desktoplyrics.action.STOP_LYRICS_OVERLAY"
        const val ACTION_STATE_CHANGED = "com.tcrrry.desktoplyrics.action.LYRICS_OVERLAY_STATE_CHANGED"
        const val ACTION_SET_BACKGROUND = "com.tcrrry.desktoplyrics.action.SET_LYRICS_BACKGROUND"
        const val ACTION_SET_FONT_SCALE = "com.tcrrry.desktoplyrics.action.SET_LYRICS_FONT_SCALE"
        const val EXTRA_BACKGROUND_MODE = "background_mode"
        const val EXTRA_FONT_SCALE_PERCENT = "font_scale_percent"
        const val EXTRA_RUNNING = "running"
        const val PREFS_NAME = "lyrics_overlay_prefs"
        const val PREF_BACKGROUND_MODE = "background_mode"
        const val PREF_FONT_SCALE_PERCENT = "font_scale_percent"
        const val BACKGROUND_TRANSPARENT = "transparent"
        const val BACKGROUND_LOW = "low"
        const val BACKGROUND_HIGH = "high"
        const val BACKGROUND_DEFAULT = BACKGROUND_HIGH
        const val FONT_SCALE_MIN_PERCENT = 75
        const val FONT_SCALE_MAX_PERCENT = 150
        const val FONT_SCALE_DEFAULT_PERCENT = 100

        fun compactMinimumHeightDp(percent: Int): Int {
            val scale = percent.coerceIn(FONT_SCALE_MIN_PERCENT, FONT_SCALE_MAX_PERCENT) / 100f
            return (9.5f + 34.5f * scale).roundToInt().coerceIn(36, 64)
        }
        private const val LOG_TAG = "DesktopLyrics"
        private const val CHANNEL_ID = "lobsta_lyrics_overlay"
        private const val COMPACT_MAX_HEIGHT_DP = 130
        private const val NOTIFICATION_ID = 4202

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
