package com.tcrrry.desktoplyrics

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.ActivityOptions
import android.app.Service
import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
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
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
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
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private val windowManager by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private val sessionManager by lazy { getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager }
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val listenerComponent by lazy { ComponentName(this, MediaListenerService::class.java) }
    private val displayStateMonitor by lazy {
        IcarDisplayStateMonitor(this, mainHandler, ::onIcarDisplayStateChanged)
    }
    private val lyricsRepository = DirectLyricsRepository()
    private val lyricsCache by lazy { LyricsCache(this) }
    private var lyricsJob = SupervisorJob()
    private var lyricsScope = CoroutineScope(lyricsJob + Dispatchers.IO)
    private val lyricsUsageLock = Any()

    private var overlayRoot: FrameLayout? = null
    private var webView: WebView? = null
    private var webContainer: FrameLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var webReady = false
    private var compact = false
    private var localSettingsOpen = false
    private var topbarLines = TOPBAR_LINES_DEFAULT
    private var wallpaperLyricsEnabled = WALLPAPER_LYRICS_DEFAULT
    private var backgroundMode = BACKGROUND_DEFAULT
    private var fontScalePercent = FONT_SCALE_DEFAULT_PERCENT
    private var nightTheme = true
    private var surfaceMode = LyricsSurfaceMode.TOPBAR
    private var displayState: IcarDisplayState? = null
    private var desktopSurfaceOccupied = false
    private var monitorStarted = false
    private var foregroundStarted = false
    private var audioRouteMonitorStarted = false
    private var avrcpEventMonitorStarted = false
    private var currentController: MediaController? = null
    private var pendingSnapshot: JSONObject? = null
    private var cachedArtworkKey = ""
    private var cachedArtworkDataUrl = ""
    private var snapshotScheduled = false
    private var bluetoothTrackKey = ""
    private var bluetoothPositionMs = 0L
    private var bluetoothPositionCapturedAtRealtime = 0L
    private var bluetoothWasPlaying = false
    private var bluetoothLastReportedPositionMs = -1L
    private var pendingBluetoothPositionMs: Long? = null
    private var pendingBluetoothPositionCapturedAtRealtime = 0L
    private var bluetoothTimelineGenerationStartedAtRealtime = 0L
    private var bluetoothTimelineReady = false
    private var bluetoothReportedPlaybackState: Int? = null
    private var lastLyricsUsageKey = ""
    private val runtimeGeneration = AtomicLong(0L)
    @Volatile private var latestLyricsRequestId = 0

    private data class PlaybackTimeline(
        val positionMs: Long,
        val speed: Double,
        val timelineReady: Boolean
    )

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
    private val sessionRetryRunnable = Runnable {
        if (isRunning && !monitorStarted) startMediaMonitor()
    }
    private val surfaceOccupancyListener: (Boolean) -> Unit = { occupied ->
        if (desktopSurfaceOccupied != occupied) {
            desktopSurfaceOccupied = occupied
            Log.i(LOG_TAG, "External desktop surface occupancy=$occupied")
            if (displayState != null || overlayRoot != null) applyCurrentSurface()
        }
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            if (monitorStarted) scheduleSnapshot()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (monitorStarted) scheduleSnapshot()
        }

        override fun onSessionDestroyed() {
            if (monitorStarted) refreshActiveSessions()
        }
    }

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            if (monitorStarted) selectController(controllers.orEmpty())
        }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            if (monitorStarted) scheduleSnapshot()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            if (monitorStarted) scheduleSnapshot()
        }
    }

    @Suppress("DEPRECATION")
    private val avrcpEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!monitorStarted) return
            val event = intent ?: return
            val extrasBundle = event.extras
            val reportedPlaybackState = if (event.action == ACTION_AVRCP_TRACK_EVENT) {
                extrasBundle?.playbackState(EXTRA_AVRCP_PLAYBACK)
            } else {
                null
            }
            val position = when (event.action) {
                ACTION_AVRCP_PLAYBACK_POSITION_CHANGED -> {
                    extrasBundle?.number(EXTRA_AVRCP_SONG_POSITION)
                        ?: extrasBundle?.number(EXTRA_AVRCP_PLAY_SONG_POSITION)
                }
                ACTION_AVRCP_TRACK_EVENT -> {
                    @Suppress("DEPRECATION")
                    val playback = extrasBundle?.getParcelable(EXTRA_AVRCP_PLAYBACK) as? PlaybackState
                    playback?.position?.takeIf { it >= 0L }
                        ?: extrasBundle?.number(EXTRA_AVRCP_SONG_POSITION)
                        ?: extrasBundle?.number(EXTRA_AVRCP_PLAY_SONG_POSITION)
                }
                else -> null
            }
            val playbackStateChanged = reportedPlaybackState != null &&
                reportedPlaybackState != bluetoothReportedPlaybackState
            if (playbackStateChanged) {
                bluetoothReportedPlaybackState = reportedPlaybackState
                Log.i(LOG_TAG, "AVRCP playback state update=$reportedPlaybackState")
            }
            if (position != null && position >= 0L) {
                Log.i(LOG_TAG, "AVRCP position update=$position action=${event.action}")
                pendingBluetoothPositionMs = position
                pendingBluetoothPositionCapturedAtRealtime = SystemClock.elapsedRealtime()
            }
            if (playbackStateChanged || (position != null && position >= 0L)) scheduleSnapshot()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground()
        isRunning = true
        announceOverlayState()
        loadRuntimePreferences()
        SurfaceOccupancyLeaseRegistry.addListener(surfaceOccupancyListener)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val nextNightTheme = isNightTheme(newConfig)
        if (nightTheme == nextNightTheme) return
        nightTheme = nextNightTheme
        applyThemeToWeb()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val source = startupSource(intent)
        val overlayAccess = Settings.canDrawOverlays(this)
        val notificationAccess = hasNotificationListenerAccess()
        val decision = LyricsStartupPolicy.decide(
            action = intent?.action,
            overlayAccess = overlayAccess,
            notificationAccess = notificationAccess
        )

        if (decision == LyricsStartupOutcome.USER_STOPPED) {
            if (decision.clearsAutoStart) {
                prefs.edit().putBoolean(PREF_AUTO_START, false).apply()
            }
            clearRecoveryNotification()
            releaseRuntimeResources(prepareForRestart = true)
            stopForegroundNotification()
            stopSelfResult(startId)
            logStartupOutcome(source, overlayAccess, notificationAccess, decision)
            return START_NOT_STICKY
        }

        if (decision == LyricsStartupOutcome.RECOVERY) {
            enterRecoveryState(startId, overlayAccess, notificationAccess)
            logStartupOutcome(source, overlayAccess, notificationAccess, decision)
            return START_NOT_STICKY
        }

        clearRecoveryNotification()
        ensureForegroundNotification()
        val result = try {
            handleAuthorizedCommand(intent)
        } catch (error: RuntimeException) {
            val latestOverlayAccess = Settings.canDrawOverlays(this)
            val latestNotificationAccess = hasNotificationListenerAccess()
            if (LyricsStartupPolicy.hasRequiredAccess(
                    overlayAccess = latestOverlayAccess,
                    notificationAccess = latestNotificationAccess
                )
            ) {
                throw error
            }
            Log.w(LOG_TAG, "Authorization changed while starting; entering recovery state")
            enterRecoveryState(startId, latestOverlayAccess, latestNotificationAccess)
            logStartupOutcome(
                source,
                latestOverlayAccess,
                latestNotificationAccess,
                LyricsStartupOutcome.RECOVERY
            )
            return START_NOT_STICKY
        }
        logStartupOutcome(source, overlayAccess, notificationAccess, decision)
        return result
    }

    private fun handleAuthorizedCommand(intent: Intent?): Int {
        if (intent?.action == ACTION_RESTART) {
            restartRuntime()
            return START_STICKY
        }

        if (intent?.action == ACTION_SET_BACKGROUND) {
            backgroundMode = BACKGROUND_TRANSPARENT
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

        if (intent?.action == ACTION_SET_TOPBAR_LINES) {
            topbarLines = normalizedTopbarLines(
                intent.getIntExtra(EXTRA_TOPBAR_LINES, TOPBAR_LINES_DEFAULT)
            )
            prefs.edit().putInt(PREF_TOPBAR_LINES, topbarLines).apply()
            applyTopbarLines()
            if (overlayRoot != null) return START_STICKY
        }

        if (intent?.action == ACTION_SET_WALLPAPER_LYRICS) {
            wallpaperLyricsEnabled = intent.getBooleanExtra(
                EXTRA_WALLPAPER_LYRICS_ENABLED,
                WALLPAPER_LYRICS_DEFAULT
            )
            prefs.edit()
                .putBoolean(PREF_WALLPAPER_LYRICS_ENABLED, wallpaperLyricsEnabled)
                .apply()
            applyCurrentSurface()
            return START_STICKY
        }

        if (intent?.action == ACTION_SETTINGS_OPENED) {
            localSettingsOpen = true
            if (monitorStarted) {
                applyCurrentSurface()
                return START_STICKY
            }
        }

        if (intent?.action == ACTION_SETTINGS_CLOSED) {
            localSettingsOpen = false
            // Re-read the car's final state instead of restoring the mode that
            // was active before settings opened. A launcher action may have
            // closed settings while switching from wallpaper to map.
            if (monitorStarted) {
                displayStateMonitor.refresh()
                applyCurrentSurface()
                return START_STICKY
            }
        }

        prefs.edit().putBoolean(PREF_AUTO_START, true).apply()
        startRuntime()
        return START_STICKY
    }

    override fun onDestroy() {
        SurfaceOccupancyLeaseRegistry.removeListener(surfaceOccupancyListener)
        releaseRuntimeResources(prepareForRestart = false)
        mainHandler.removeCallbacksAndMessages(null)
        lyricsRepository.close()
        lyricsCache.close()
        isRunning = false
        announceOverlayState()
        super.onDestroy()
    }

    private fun loadRuntimePreferences() {
        nightTheme = isNightTheme(resources.configuration)
        backgroundMode = BACKGROUND_TRANSPARENT
        fontScalePercent = normalizedFontScale(
            prefs.getInt(PREF_FONT_SCALE_PERCENT, FONT_SCALE_DEFAULT_PERCENT)
        )
        compact = true
        surfaceMode = LyricsSurfaceMode.TOPBAR
        topbarLines = normalizedTopbarLines(
            prefs.getInt(PREF_TOPBAR_LINES, TOPBAR_LINES_DEFAULT)
        )
        wallpaperLyricsEnabled = prefs.getBoolean(
            PREF_WALLPAPER_LYRICS_ENABLED,
            WALLPAPER_LYRICS_DEFAULT
        )
        prefs.edit()
            .putString(PREF_BACKGROUND_MODE, BACKGROUND_TRANSPARENT)
            .apply()
    }

    private fun startRuntime() {
        displayStateMonitor.start()
        if (overlayRoot == null) createOverlay()
        startMediaMonitor()
    }

    private fun restartRuntime() {
        val preserveAutoStart = prefs.getBoolean(PREF_AUTO_START, false)
        releaseRuntimeResources(prepareForRestart = true)
        loadRuntimePreferences()
        startRuntime()
        if (prefs.getBoolean(PREF_AUTO_START, false) != preserveAutoStart) {
            prefs.edit().putBoolean(PREF_AUTO_START, preserveAutoStart).apply()
            Log.w(LOG_TAG, "Restored auto-start intent after runtime restart")
        }
        Log.i(
            LOG_TAG,
            "Lyrics overlay runtime restarted generation=${runtimeGeneration.get()} " +
                "autoStart=$preserveAutoStart"
        )
    }

    private fun releaseRuntimeResources(prepareForRestart: Boolean) {
        runtimeGeneration.incrementAndGet()
        synchronized(lyricsUsageLock) {
            latestLyricsRequestId = 0
            lastLyricsUsageKey = ""
        }
        lyricsJob.cancel()
        if (prepareForRestart) {
            lyricsJob = SupervisorJob()
            lyricsScope = CoroutineScope(lyricsJob + Dispatchers.IO)
        }

        mainHandler.removeCallbacks(dispatchRunnable)
        mainHandler.removeCallbacks(sessionRefreshRunnable)
        mainHandler.removeCallbacks(sessionRetryRunnable)
        snapshotScheduled = false
        displayStateMonitor.stop()
        stopMediaMonitor()
        destroyOverlay()

        displayState = null
        pendingSnapshot = null
        cachedArtworkKey = ""
        cachedArtworkDataUrl = ""
    }

    private fun destroyOverlay() {
        webReady = false
        val player = webView
        val root = overlayRoot
        webView = null
        webContainer = null
        overlayRoot = null
        windowParams = null

        (player?.parent as? ViewGroup)?.removeView(player)
        root?.let {
            runCatching { windowManager.removeViewImmediate(it) }
                .onFailure { error -> Log.w(LOG_TAG, "Unable to remove lyric surface", error) }
        }
        player?.apply {
            stopLoading()
            loadUrl("about:blank")
            removeJavascriptInterface("LobstaNativeLyrics")
            destroy()
        }
    }

    private fun announceOverlayState() {
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_RUNNING, isRunning)
        )
    }

    private inner class LyricsJavascriptBridge(private val generation: Long) {
        @JavascriptInterface
        fun requestSettings() {
            mainHandler.post {
                if (generation != runtimeGeneration.get()) return@post
                if (surfaceMode == LyricsSurfaceMode.TOPBAR && !localSettingsOpen) {
                    openSettings()
                }
            }
        }

        @JavascriptInterface
        fun requestLyrics(
            track: String,
            artist: String,
            album: String,
            durationMsText: String,
            requestId: Int,
            needsRemoteCover: Boolean
        ) {
            if (generation != runtimeGeneration.get() || track.isBlank() || requestId <= 0) return
            val durationMs = durationMsText.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            if (!LyricsCandidateSelector.hasKnownDuration(durationMs)) {
                val claimed = synchronized(lyricsUsageLock) {
                    if (generation != runtimeGeneration.get()) {
                        false
                    } else {
                        latestLyricsRequestId = requestId
                        true
                    }
                }
                if (!claimed) return
                deliverLyricsResult(generation, requestId, DirectLyricsRepository.Result())
                return
            }
            val usageKey = LyricsCache.usageKey(track, artist, album, durationMs)
            val recordUse = synchronized(lyricsUsageLock) {
                if (generation != runtimeGeneration.get()) {
                    null
                } else {
                    val changed = usageKey != lastLyricsUsageKey
                    lastLyricsUsageKey = usageKey
                    latestLyricsRequestId = requestId
                    changed
                }
            } ?: return
            val requestScope = lyricsScope
            if (generation != runtimeGeneration.get()) return
            requestScope.launch {
                val nowMs = System.currentTimeMillis()
                val cached = lyricsCache.get(track, artist, album, durationMs, recordUse, nowMs)
                if (!isCurrentLyricsRequest(generation, requestId)) return@launch
                if (cached != null) {
                    deliverLyricsResult(generation, requestId, cached.result)
                    if (!cached.needsRefresh(nowMs)) return@launch
                }

                val startedAt = SystemClock.elapsedRealtime()
                val coverLookup = if (needsRemoteCover) {
                    async { lyricsRepository.resolveCover(track, artist) }
                } else null
                val result = lyricsRepository.resolveLyrics(track, artist, album, durationMs)
                if (!isCurrentLyricsRequest(generation, requestId)) {
                    coverLookup?.cancel()
                    return@launch
                }
                Log.i(
                    LOG_TAG,
                    "Direct lyrics source=${result.source.ifBlank { "none" }} " +
                        "kind=${result.lyricsKind} " +
                        "found=${result.lyrics.isNotBlank()} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
                )
                if (classifyLyrics(result.lyrics) == LyricsKind.SYNCHRONIZED) {
                    lyricsCache.put(track, artist, album, durationMs, result)
                    deliverLyricsResult(generation, requestId, result)
                } else if (cached == null) {
                    deliverLyricsResult(generation, requestId, result)
                }

                if (needsRemoteCover && result.cover.isBlank()) {
                    val cover = runCatching { coverLookup?.await().orEmpty() }.getOrDefault("")
                    if (cover.isNotBlank() && isCurrentLyricsRequest(generation, requestId)) {
                        deliverRemoteCover(generation, requestId, cover)
                    }
                } else {
                    coverLookup?.cancel()
                }
            }
        }
    }

    private fun isCurrentLyricsRequest(generation: Long, requestId: Int): Boolean =
        generation == runtimeGeneration.get() && requestId == latestLyricsRequestId

    private fun deliverLyricsResult(
        generation: Long,
        requestId: Int,
        result: DirectLyricsRepository.Result
    ) {
        val payload = result.toJson().toString()
        mainHandler.post {
            if (!isCurrentLyricsRequest(generation, requestId) || !webReady) return@post
            webView?.evaluateJavascript(
                "window.LobstaOverlay && window.LobstaOverlay.receiveLyrics($requestId,$payload);",
                null
            )
        }
    }

    private fun deliverRemoteCover(generation: Long, requestId: Int, cover: String) {
        val encodedCover = JSONObject.quote(cover)
        mainHandler.post {
            if (!isCurrentLyricsRequest(generation, requestId) || !webReady) return@post
            webView?.evaluateJavascript(
                "window.LobstaOverlay && window.LobstaOverlay.receiveRemoteCover($requestId,$encodedCover);",
                null
            )
        }
    }

    private fun startAsForeground() {
        val stopIntent = Intent(this, LyricsOverlayService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_lyrics)
            .setContentTitle("${getString(R.string.app_name)} 正在监听")
            .setContentText("本地实时同步当前媒体会话")
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
        foregroundStarted = true
    }

    private fun ensureForegroundNotification() {
        if (!foregroundStarted) startAsForeground()
    }

    private fun stopForegroundNotification() {
        if (!foregroundStarted) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
    }

    private fun enterRecoveryState(
        startId: Int,
        overlayAccess: Boolean,
        notificationAccess: Boolean
    ) {
        releaseRuntimeResources(prepareForRestart = true)
        stopForegroundNotification()
        notificationManager.notify(
            RECOVERY_NOTIFICATION_ID,
            buildRecoveryNotification(overlayAccess, notificationAccess)
        )
        stopSelfResult(startId)
    }

    private fun buildRecoveryNotification(
        overlayAccess: Boolean,
        notificationAccess: Boolean
    ): android.app.Notification {
        val message = getString(
            when {
                !overlayAccess && !notificationAccess -> {
                    R.string.notification_recovery_missing_both
                }
                !overlayAccess -> R.string.notification_recovery_missing_overlay
                else -> R.string.notification_recovery_missing_notification_access
            }
        )
        val settingsIntent = PendingIntent.getActivity(
            this,
            RECOVERY_SETTINGS_REQUEST_CODE,
            Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_lyrics)
            .setContentTitle(getString(R.string.notification_recovery_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(settingsIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    private fun clearRecoveryNotification() {
        notificationManager.cancel(RECOVERY_NOTIFICATION_ID)
    }

    private fun startupSource(intent: Intent?): String {
        val explicitSource = intent?.getStringExtra(EXTRA_START_SOURCE)
        if (explicitSource == START_SOURCE_BOOT_COMPLETED ||
            explicitSource == START_SOURCE_PACKAGE_REPLACED
        ) {
            return explicitSource
        }
        return when (intent?.action) {
            null -> "system_restart"
            ACTION_START -> "explicit_start"
            ACTION_STOP -> "user_stop"
            ACTION_RESTART -> "manual_restart"
            ACTION_SETTINGS_OPENED -> "settings_opened"
            ACTION_SETTINGS_CLOSED -> "settings_closed"
            else -> "runtime_command"
        }
    }

    private fun logStartupOutcome(
        source: String,
        overlayAccess: Boolean,
        notificationAccess: Boolean,
        outcome: LyricsStartupOutcome
    ) {
        Log.i(
            LOG_TAG,
            "Lyrics startup decision source=$source overlayAccess=$overlayAccess " +
                "notificationAccess=$notificationAccess outcome=${outcome.logValue}"
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "顶栏歌词",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持本地歌词悬浮窗与 MediaSession 实时同步"
                setShowBadge(false)
            }
        )
    }

    @Suppress("SetJavaScriptEnabled")
    private fun createOverlay() {
        val generation = runtimeGeneration.get()
        compact = surfaceMode == LyricsSurfaceMode.TOPBAR
        val geometry = overlayGeometry(surfaceMode, displayState)
        if (geometry.width <= 0 || geometry.height <= 0) {
            Log.i(LOG_TAG, "Lyric surface withheld because no safe width is available")
            return
        }

        val params = WindowManager.LayoutParams(
            geometry.width,
            geometry.height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (compact) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = geometry.x
            y = geometry.y
        }
        windowParams = params

        val root = FrameLayout(this).apply {
            clipToOutline = true
            elevation = 0f
            background = overlayBackground(compact)
        }
        overlayRoot = root

        val webContainerView = FrameLayout(this)
        webContainer = webContainerView
        root.addView(webContainerView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (compact) statusBarHeight() else ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.TOP
        ))

        val player = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(LyricsJavascriptBridge(generation), "LobstaNativeLyrics")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = true

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (generation != runtimeGeneration.get() || view !== webView) return
                    webReady = true
                    applyThemeToWeb()
                    applyFontScale(fontScalePercent, adjustCompactHeight = false)
                    applySurfaceModeToWeb()
                    applyTopbarLines()
                    applyBackgroundMode()
                    pendingSnapshot?.let { deliverToWeb(it) } ?: scheduleSnapshot()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    val message = consoleMessage ?: return false
                    if (message.messageLevel() != ConsoleMessage.MessageLevel.ERROR) return false
                    Log.e(LOG_TAG, "WebView ${message.sourceId()}:${message.lineNumber()} ${message.message()}")
                    return true
                }
            }
            loadUrl("file:///android_asset/lyrics_overlay.html")
        }
        webView = player
        webContainerView.addView(player, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        updateWebContainerLayout(compact)

        windowManager.addView(root, params)
    }

    private data class OverlayGeometry(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    )

    private fun onIcarDisplayStateChanged(state: IcarDisplayState) {
        val previous = displayState
        displayState = state
        applyCurrentSurface(previous)
    }

    /** Reconciles launcher, system-window, leased-surface, local-page, and preference state. */
    private fun applyCurrentSurface(previousState: IcarDisplayState? = null) {
        val nextSurfaceMode = IcarLyricsSurfacePolicy.effectiveSurfaceMode(
            displayState = displayState,
            wallpaperLyricsEnabled = wallpaperLyricsEnabled,
            localSettingsOpen = localSettingsOpen,
            desktopSurfaceOccupied = desktopSurfaceOccupied
        )
        val geometry = overlayGeometry(nextSurfaceMode, displayState)
        if (overlayRoot == null && geometry.width > 0 && geometry.height > 0) {
            surfaceMode = nextSurfaceMode
            createOverlay()
            return
        }
        if (surfaceMode != nextSurfaceMode) {
            applySurfaceMode(nextSurfaceMode)
        } else if (nextSurfaceMode == LyricsSurfaceMode.TOPBAR &&
            previousState?.topbarGeometry() != displayState?.topbarGeometry()
        ) {
            displayState?.let(::applyTopbarGeometry)
        }
        updateOverlayVisibility(geometry)
    }

    private fun applySurfaceMode(nextSurfaceMode: LyricsSurfaceMode) {
        surfaceMode = nextSurfaceMode
        compact = surfaceMode == LyricsSurfaceMode.TOPBAR
        val params = windowParams ?: return
        applyOverlayGeometry(params, overlayGeometry(surfaceMode, displayState))
        overlayRoot?.background = overlayBackground(compact)
        updateWindowTouchability(compact)
        updateWebContainerLayout(compact)
        applySurfaceModeToWeb()
    }

    /** Icon updates reach here only while already in topbar mode. */
    private fun applyTopbarGeometry(state: IcarDisplayState) {
        val params = windowParams ?: return
        applyOverlayGeometry(params, overlayGeometry(LyricsSurfaceMode.TOPBAR, state))
    }

    private fun applyOverlayGeometry(params: WindowManager.LayoutParams, geometry: OverlayGeometry) {
        if (!IcarLyricsSurfacePolicy.hasRenderableGeometry(geometry.width, geometry.height)) {
            overlayRoot?.visibility = View.GONE
            return
        }
        params.x = geometry.x
        params.y = geometry.y
        params.width = geometry.width
        params.height = geometry.height
        overlayRoot?.let { root ->
            root.visibility = View.VISIBLE
            runCatching { windowManager.updateViewLayout(root, params) }
                .onFailure { error -> Log.w(LOG_TAG, "Unable to update lyric surface", error) }
        }
    }

    private fun updateOverlayVisibility(geometry: OverlayGeometry = overlayGeometry(surfaceMode, displayState)) {
        overlayRoot?.visibility = if (
            IcarLyricsSurfacePolicy.hasRenderableGeometry(geometry.width, geometry.height)
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun overlayGeometry(
        mode: LyricsSurfaceMode,
        state: IcarDisplayState?
    ): OverlayGeometry {
        val realMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(realMetrics)
        val screenWidth = realMetrics.widthPixels
        val screenHeight = realMetrics.heightPixels
        fun scaledX(value: Int): Int = (screenWidth * value / DESIGN_WIDTH.toFloat()).roundToInt()
        fun scaledY(value: Int): Int = (screenHeight * value / DESIGN_HEIGHT.toFloat()).roundToInt()

        if (mode == LyricsSurfaceMode.DESKTOP) {
            val left = scaledX(DESKTOP_LEFT)
            val top = scaledY(DESKTOP_TOP)
            val right = scaledX(DESKTOP_RIGHT).coerceAtMost(screenWidth)
            val bottom = scaledY(DESKTOP_BOTTOM).coerceAtMost(screenHeight)
            return OverlayGeometry(
                x = left,
                y = top,
                width = max(1, right - left),
                height = max(1, bottom - top)
            )
        }

        val topbarGeometry = state?.topbarGeometry() ?: IcarTopbarGeometry(
            leftPx = IcarTopbarLayout.LYRICS_LEFT_WITHOUT_DYNAMIC_ICONS_PX,
            rightPx = IcarTopbarLayout.LYRICS_RIGHT_PX
        )
        if (!topbarGeometry.canShowLyrics) return OverlayGeometry(0, 0, 0, 0)
        val right = scaledX(topbarGeometry.rightPx).coerceAtMost(screenWidth)
        val left = scaledX(topbarGeometry.leftPx).coerceAtMost(right)
        return OverlayGeometry(
            x = left,
            y = 0,
            width = max(0, right - left),
            height = statusBarHeight()
        )
    }

    private fun applySurfaceModeToWeb() {
        if (!webReady) return
        val encodedMode = JSONObject.quote(
            if (surfaceMode == LyricsSurfaceMode.DESKTOP) "desktop" else "topbar"
        )
        webView?.evaluateJavascript(
            "window.LobstaOverlay && window.LobstaOverlay.setSurfaceMode($encodedMode);",
            null
        )
    }

    private fun overlayBackground(@Suppress("UNUSED_PARAMETER") isCompact: Boolean) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
        }

    private fun statusBarHeight(): Int {
        val realMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(realMetrics)
        return (realMetrics.heightPixels * 72 / 1080f)
            .toInt()
            .coerceAtLeast(dp(48))
    }

    private fun applyBackgroundMode() {
        val encodedMode = JSONObject.quote(backgroundMode)
        webView?.evaluateJavascript(
            "window.LobstaOverlay && window.LobstaOverlay.setBackgroundMode($encodedMode);",
            null
        )
    }

    private fun applyThemeToWeb() {
        if (!webReady) return
        val encodedTheme = JSONObject.quote(if (nightTheme) "dark" else "light")
        webView?.evaluateJavascript(
            "window.LobstaOverlay && window.LobstaOverlay.setTheme($encodedTheme);",
            null
        )
    }

    private fun isNightTheme(configuration: Configuration): Boolean =
        configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun applyFontScale(
        @Suppress("UNUSED_PARAMETER") previousPercent: Int,
        @Suppress("UNUSED_PARAMETER") adjustCompactHeight: Boolean
    ) {
        webView?.evaluateJavascript(
            "window.LobstaOverlay && window.LobstaOverlay.setFontScale($fontScalePercent);",
            null
        )
    }

    private fun normalizedBackgroundMode(value: String?): String = when (value) {
        BACKGROUND_TRANSPARENT -> BACKGROUND_TRANSPARENT
        BACKGROUND_LOW -> BACKGROUND_LOW
        BACKGROUND_HIGH -> BACKGROUND_HIGH
        else -> BACKGROUND_DEFAULT
    }

    private fun normalizedTopbarLines(value: Int): Int = if (value == 1) 1 else 2

    private fun normalizedFontScale(value: Int): Int =
        value.coerceIn(FONT_SCALE_MIN_PERCENT, FONT_SCALE_MAX_PERCENT)

    private fun updateWebContainerLayout(isTopbar: Boolean) {
        webContainer?.let { container ->
            val containerParams = (container.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.TOP
                )
            containerParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            containerParams.height = if (isTopbar) {
                statusBarHeight()
            } else {
                ViewGroup.LayoutParams.MATCH_PARENT
            }
            containerParams.gravity = Gravity.TOP
            container.layoutParams = containerParams
            container.requestLayout()
        }
    }

    private fun updateWindowTouchability(isTopbar: Boolean) {
        val params = windowParams ?: return
        val nextFlags = if (isTopbar) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (params.flags == nextFlags) return
        params.flags = nextFlags
        overlayRoot?.let { root ->
            runCatching { windowManager.updateViewLayout(root, params) }
                .onFailure { error -> Log.w(LOG_TAG, "Unable to update lyric touch mode", error) }
        }
    }

    private fun openSettings() {
        localSettingsOpen = true
        applyCurrentSurface()
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val options = ActivityOptions.makeBasic().setLaunchBounds(
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        )
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
                options.toBundle()
            )
        }.onFailure {
            localSettingsOpen = false
            displayStateMonitor.refresh()
            applyCurrentSurface()
            Log.w(LOG_TAG, "Unable to open lyric settings", it)
        }
    }

    private fun applyTopbarLines() {
        if (!webReady) return
        webView?.evaluateJavascript(
            "window.LobstaOverlay && window.LobstaOverlay.setCompactLines($topbarLines);",
            null
        )
    }

    private fun startMediaMonitor() {
        if (monitorStarted) {
            refreshActiveSessions()
            return
        }
        startAudioRouteMonitor()
        startAvrcpEventMonitor()
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
            mainHandler.removeCallbacks(sessionRetryRunnable)
            mainHandler.postDelayed(sessionRetryRunnable, 1_500L)
        }
    }

    private fun stopMediaMonitor() {
        mainHandler.removeCallbacks(sessionRefreshRunnable)
        mainHandler.removeCallbacks(sessionRetryRunnable)
        val activeSessionsListenerRegistered = monitorStarted
        monitorStarted = false
        stopAudioRouteMonitor()
        stopAvrcpEventMonitor()
        if (activeSessionsListenerRegistered) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener)
            } catch (_: Exception) {
            }
        }
        currentController?.unregisterCallback(controllerCallback)
        currentController = null
        resetBluetoothTimeline()
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

    private fun startAvrcpEventMonitor() {
        if (avrcpEventMonitorStarted) return
        val filter = IntentFilter().apply {
            addAction(ACTION_AVRCP_PLAYBACK_POSITION_CHANGED)
            addAction(ACTION_AVRCP_TRACK_EVENT)
        }
        try {
            ContextCompat.registerReceiver(
                this,
                avrcpEventReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            avrcpEventMonitorStarted = true
            Log.i(LOG_TAG, "AVRCP passive event monitor registered")
        } catch (error: Exception) {
            Log.w(LOG_TAG, "AVRCP passive event monitor unavailable", error)
        }
    }

    private fun stopAvrcpEventMonitor() {
        if (!avrcpEventMonitorStarted) return
        try {
            unregisterReceiver(avrcpEventReceiver)
        } catch (_: Exception) {
        }
        avrcpEventMonitorStarted = false
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
        resetBluetoothTimeline()
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
            BLUETOOTH_PACKAGE,
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
        val mediaSessionState = when (playback?.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> "buffering"
            PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE -> "stopped"
            else -> "paused"
        }
        val state = if (controller.packageName == BLUETOOTH_PACKAGE) {
            bluetoothPlaybackState(mediaSessionState)
        } else {
            mediaSessionState
        }
        val rawSpeed = playback?.playbackSpeed?.toDouble() ?: 0.0
        val timeline = if (controller.packageName == BLUETOOTH_PACKAGE) {
            bluetoothTimeline(
                bluetoothTrackIdentity(title, artist, album),
                state,
                playback?.position ?: 0L,
                duration
            )
        } else {
            PlaybackTimeline(
                positionMs = currentPosition(playback, duration),
                speed = rawSpeed,
                timelineReady = true
            )
        }
        return JSONObject()
            .put("hasSession", title.isNotBlank() || playback != null)
            .put("permissionRequired", false)
            .put("track", title)
            .put("artist", artist)
            .put("album", album)
            .put("packageName", controller.packageName)
            .put("state", state)
            .put("positionMs", timeline.positionMs)
            .put("durationMs", max(0L, duration))
            .put("speed", if (timeline.speed.isFinite()) timeline.speed else 1.0)
            .put("timelineReady", timeline.timelineReady)
            .put("capturedAtMs", System.currentTimeMillis())
    }

    private fun bluetoothTrackIdentity(
        title: String,
        artist: String,
        album: String
    ): String {
        return "text:$title\u0000$artist\u0000$album"
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

    private fun bluetoothTimeline(
        trackKey: String,
        state: String,
        reportedPositionMs: Long,
        durationMs: Long
    ): PlaybackTimeline {
        val now = SystemClock.elapsedRealtime()
        val isPlaying = state == "playing"
        val eventPosition = pendingBluetoothPositionMs
        val eventPositionCapturedAt = pendingBluetoothPositionCapturedAtRealtime
        pendingBluetoothPositionMs = null

        if (trackKey != bluetoothTrackKey) {
            bluetoothTrackKey = trackKey
            bluetoothLastReportedPositionMs = -1L
            // A metadata update can still carry the prior track's position.
            // Start every new generation at zero and wait for a fresh low
            // position before trusting later AVRCP progress.
            bluetoothPositionMs = 0L
            bluetoothPositionCapturedAtRealtime = now
            bluetoothWasPlaying = isPlaying
            bluetoothTimelineGenerationStartedAtRealtime = now
            bluetoothTimelineReady = false
        } else {
            if (eventPosition != null) {
                val eventBelongsToGeneration = eventPositionCapturedAt >=
                    bluetoothTimelineGenerationStartedAtRealtime
                if (bluetoothTimelineReady || eventBelongsToGeneration ||
                    eventPosition <= BLUETOOTH_POSITION_RESET_TOLERANCE_MS
                ) {
                    bluetoothPositionMs = eventPosition
                    bluetoothPositionCapturedAtRealtime = eventPositionCapturedAt
                    bluetoothTimelineReady = true
                }
            } else if (reportedPositionMs >= 0L &&
                reportedPositionMs != bluetoothLastReportedPositionMs
            ) {
                if (bluetoothTimelineReady || reportedPositionMs <= BLUETOOTH_POSITION_RESET_TOLERANCE_MS) {
                    bluetoothPositionMs = reportedPositionMs
                    bluetoothPositionCapturedAtRealtime = now
                    bluetoothTimelineReady = true
                }
            } else if (bluetoothWasPlaying && !isPlaying) {
                bluetoothPositionMs += max(0L, now - bluetoothPositionCapturedAtRealtime)
                bluetoothPositionCapturedAtRealtime = now
            } else if (!bluetoothWasPlaying && isPlaying) {
                bluetoothPositionCapturedAtRealtime = now
            }
            bluetoothLastReportedPositionMs = reportedPositionMs
            bluetoothWasPlaying = isPlaying
        }

        var position = bluetoothPositionMs
        if (isPlaying) {
            position += max(0L, now - bluetoothPositionCapturedAtRealtime)
        }
        if (durationMs > 0L) position = min(position, durationMs)
        return PlaybackTimeline(
            positionMs = max(0L, position),
            speed = if (isPlaying) 1.0 else 0.0,
            timelineReady = bluetoothTimelineReady
        )
    }

    private fun resetBluetoothTimeline() {
        bluetoothTrackKey = ""
        bluetoothPositionMs = 0L
        bluetoothPositionCapturedAtRealtime = 0L
        bluetoothWasPlaying = false
        bluetoothLastReportedPositionMs = -1L
        pendingBluetoothPositionMs = null
        pendingBluetoothPositionCapturedAtRealtime = 0L
        bluetoothTimelineGenerationStartedAtRealtime = 0L
        bluetoothTimelineReady = false
        bluetoothReportedPlaybackState = null
    }

    private fun bluetoothPlaybackState(fallback: String): String = when (bluetoothReportedPlaybackState) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_STOPPED,
        PlaybackState.STATE_NONE -> "stopped"
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING -> "buffering"
        else -> fallback
    }

    @Suppress("DEPRECATION")
    private fun android.os.Bundle.number(key: String): Long? =
        (runCatching { get(key) }.getOrNull() as? Number)?.toLong()

    @Suppress("DEPRECATION")
    private fun android.os.Bundle.playbackState(key: String): Int? = when (
        val value = runCatching { get(key) }.getOrNull()
    ) {
        is PlaybackState -> value.state
        is Number -> value.toInt()
        else -> null
    }?.takeIf { it in PlaybackState.STATE_NONE..PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM }

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
        val generation = runtimeGeneration.get()
        val targetWebView = webView ?: return
        targetWebView.post {
            if (generation != runtimeGeneration.get() || targetWebView !== webView || !webReady) {
                return@post
            }
            targetWebView.evaluateJavascript(
                "window.LobstaOverlay && window.LobstaOverlay.updatePlayback($snapshot);",
                null
            )
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun hasNotificationListenerAccess(): Boolean =
        androidx.core.app.NotificationManagerCompat
            .getEnabledListenerPackages(this)
            .contains(packageName)

    companion object {
        const val ACTION_START = "com.tcrrry.desktoplyrics.action.START_LYRICS_OVERLAY"
        const val ACTION_STOP = "com.tcrrry.desktoplyrics.action.STOP_LYRICS_OVERLAY"
        const val ACTION_RESTART = "com.tcrrry.desktoplyrics.action.RESTART_LYRICS_OVERLAY"
        const val ACTION_STATE_CHANGED = "com.tcrrry.desktoplyrics.action.LYRICS_OVERLAY_STATE_CHANGED"
        const val ACTION_SET_BACKGROUND = "com.tcrrry.desktoplyrics.action.SET_LYRICS_BACKGROUND"
        const val ACTION_SET_FONT_SCALE = "com.tcrrry.desktoplyrics.action.SET_LYRICS_FONT_SCALE"
        const val ACTION_SET_TOPBAR_LINES = "com.tcrrry.desktoplyrics.action.SET_TOPBAR_LINES"
        const val ACTION_SET_WALLPAPER_LYRICS =
            "com.tcrrry.desktoplyrics.action.SET_WALLPAPER_LYRICS"
        const val ACTION_SETTINGS_OPENED = "com.tcrrry.desktoplyrics.action.SETTINGS_OPENED"
        const val ACTION_SETTINGS_CLOSED = "com.tcrrry.desktoplyrics.action.SETTINGS_CLOSED"
        const val EXTRA_START_SOURCE = "start_source"
        const val EXTRA_BACKGROUND_MODE = "background_mode"
        const val EXTRA_FONT_SCALE_PERCENT = "font_scale_percent"
        const val EXTRA_TOPBAR_LINES = "topbar_lines"
        const val EXTRA_WALLPAPER_LYRICS_ENABLED = "wallpaper_lyrics_enabled"
        const val EXTRA_RUNNING = "running"
        const val PREFS_NAME = "lyrics_overlay_prefs"
        const val PREF_BACKGROUND_MODE = "background_mode"
        const val PREF_FONT_SCALE_PERCENT = "font_scale_percent"
        const val PREF_AUTO_START = "auto_start"
        const val PREF_TOPBAR_LINES = "topbar_lines_v1"
        const val PREF_WALLPAPER_LYRICS_ENABLED = "wallpaper_lyrics_enabled_v1"
        const val BACKGROUND_TRANSPARENT = "transparent"
        const val BACKGROUND_LOW = "low"
        const val BACKGROUND_HIGH = "high"
        const val BACKGROUND_DEFAULT = BACKGROUND_TRANSPARENT
        const val FONT_SCALE_MIN_PERCENT = 75
        const val FONT_SCALE_MAX_PERCENT = 150
        const val FONT_SCALE_DEFAULT_PERCENT = 100
        const val WALLPAPER_LYRICS_DEFAULT = true
        const val START_SOURCE_BOOT_COMPLETED = "boot_completed"
        const val START_SOURCE_PACKAGE_REPLACED = "package_replaced"

        fun compactMinimumHeightDp(percent: Int): Int {
            val scale = percent.coerceIn(FONT_SCALE_MIN_PERCENT, FONT_SCALE_MAX_PERCENT) / 100f
            return (9.5f + 34.5f * scale).roundToInt().coerceIn(36, 64)
        }
        private const val LOG_TAG = "DesktopLyrics"
        private const val CHANNEL_ID = "lobsta_lyrics_overlay"
        private const val NOTIFICATION_ID = 4202
        private const val RECOVERY_NOTIFICATION_ID = 4203
        private const val RECOVERY_SETTINGS_REQUEST_CODE = 2
        private const val BLUETOOTH_POSITION_RESET_TOLERANCE_MS = 2_500L
        private const val TOPBAR_LINES_DEFAULT = 2
        private const val DESIGN_WIDTH = 1920
        private const val DESIGN_HEIGHT = 1080
        private const val DESKTOP_LEFT = 660
        private const val DESKTOP_TOP = 90
        private const val DESKTOP_RIGHT = 1890
        private const val DESKTOP_BOTTOM = 900
        private const val BLUETOOTH_PACKAGE = "com.android.bluetooth"
        private const val ACTION_AVRCP_PLAYBACK_POSITION_CHANGED =
            "android.bluetooth.avrcp-controller.profile.action.PLAYBACK_POS_CHANGEDS"
        private const val ACTION_AVRCP_TRACK_EVENT =
            "android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT"
        private const val EXTRA_AVRCP_SONG_POSITION =
            "android.bluetooth.avrcp-controller.profile.extra.SONG_POS"
        private const val EXTRA_AVRCP_PLAY_SONG_POSITION =
            "android.bluetooth.avrcp-controller.extra.PLAY_SONG_POS"
        private const val EXTRA_AVRCP_PLAYBACK =
            "android.bluetooth.avrcp-controller.profile.extra.PLAYBACK"

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}

internal enum class LyricsStartupOutcome(
    val logValue: String,
    val clearsAutoStart: Boolean
) {
    RUNNING("running", false),
    RECOVERY("recovery", false),
    USER_STOPPED("stopped", true)
}

internal object LyricsStartupPolicy {
    fun decide(
        action: String?,
        overlayAccess: Boolean,
        notificationAccess: Boolean
    ): LyricsStartupOutcome = when {
        action == LyricsOverlayService.ACTION_STOP -> LyricsStartupOutcome.USER_STOPPED
        hasRequiredAccess(overlayAccess, notificationAccess) -> LyricsStartupOutcome.RUNNING
        else -> LyricsStartupOutcome.RECOVERY
    }

    fun hasRequiredAccess(overlayAccess: Boolean, notificationAccess: Boolean): Boolean =
        overlayAccess && notificationAccess
}
