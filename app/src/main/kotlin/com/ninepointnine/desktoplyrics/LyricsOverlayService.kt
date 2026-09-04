package com.ninepointnine.desktoplyrics

import android.animation.ValueAnimator
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
import android.media.AudioPlaybackConfiguration
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
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
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ninepointnine.desktoplyrics.commercial.CommercialAccessDecision
import com.ninepointnine.desktoplyrics.commercial.CommercialAccessDenial
import com.ninepointnine.desktoplyrics.commercial.CommercialAccessRefreshResult
import com.ninepointnine.desktoplyrics.commercial.CommercialFailure
import com.ninepointnine.desktoplyrics.commercial.CommercialRuntimeFactory
import com.ninepointnine.desktoplyrics.commercial.EntitlementState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
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
    private var bluetoothBrowserSession: BluetoothMediaBrowserSession? = null
    private val bluetoothBrowserProfiles = linkedMapOf<MediaSession.Token, BluetoothMediaBrowserProfile>()
    private var systemBluetoothProfile = BluetoothMediaBrowserProfile.ANDROID_9_A2DP
    private val bluetoothBrowserBridge by lazy {
        BluetoothMediaBrowserSessionBridge(
            context = this,
            mainHandler = mainHandler,
            listener = object : BluetoothMediaBrowserSessionBridge.Listener {
                override fun onSessionChanged(session: BluetoothMediaBrowserSession?) {
                    bluetoothBrowserSession = session
                    if (session == null) {
                        bluetoothBrowserProfiles.clear()
                    } else {
                        bluetoothBrowserProfiles[session.controller.sessionToken] = session.profile
                    }
                    if (monitorStarted) scheduleSessionSelectionRefresh()
                }

                override fun onConnectionSuspended() {
                    if (monitorStarted) scheduleSessionSelectionRefresh()
                }

                override fun onStateChanged(
                    state: BluetoothMediaBrowserBridgeState,
                    descriptor: BluetoothMediaBrowserServiceDescriptor?
                ) {
                    Log.i(
                        LOG_TAG,
                        "Bluetooth Browser state=${state.name.lowercase()} " +
                            "component=${descriptor?.componentName?.flattenToShortString() ?: "none"}"
                    )
                }
            }
        )
    }
    private val displayStateMonitor by lazy {
        IcarDisplayStateMonitor(this, mainHandler, ::onIcarDisplayStateChanged)
    }
    private var lyricsRepository: DirectLyricsRepository? = null
    private var lyricsResolutionCoordinator: LyricsResolutionCoordinator? = null
    private var lyricsCache: LyricsCache? = null
    private var lyricsJob: Job? = null
    private var lyricsScope: CoroutineScope? = null
    private val lyricsUsageLock = Any()
    private val commercialCheckJob = SupervisorJob()
    private val commercialCheckScope = CoroutineScope(commercialCheckJob + Dispatchers.IO)
    private var commercialStartupCheckStarted = false
    private var commercialStartupCheckPending = false
    private var commercialTrialLeaseCheckPending = false
    private var resumeAfterCommercialStartupCheck = false
    private var pendingCommercialStartupIntent: Intent? = null
    private var latestStartId = 0
    private var commercialAccessBoundaryReceiverRegistered = false
    private val commercialRuntimeAccess by lazy {
        CommercialRuntimeAccessGuard(
            nowEpochMs = System::currentTimeMillis,
            evaluateAccess = { now ->
                CommercialRuntimeFactory.entitlementCoordinator(this).evaluate(now)
            },
            scheduleExpiry = { runnable, delayMillis ->
                mainHandler.postDelayed(runnable, delayMillis)
            },
            cancelExpiry = mainHandler::removeCallbacks,
            onDenied = ::onCommercialRuntimeAccessDenied,
            onTrialLeaseDue = ::recheckCommercialEntitlementAtTrialLease
        )
    }

    private var overlayRoot: FrameLayout? = null
    private var webView: WebView? = null
    private var webContainer: FrameLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var webReady = false
    private var compact = false
    private var localSettingsOpen = false
    private var topbarLines = TOPBAR_LINES_DEFAULT
    private var wallpaperLyricsEnabled = WALLPAPER_LYRICS_DEFAULT
    private var lyricsTranslationEnabled = LYRICS_TRANSLATION_DEFAULT
    private var translationAvailable = false
    private var backgroundMode = BACKGROUND_DEFAULT
    private var topbarSingleLineFontSizePx = LyricsTopbarFontSizePolicy.PRIMARY_DEFAULT_PX
    private var topbarFirstLineFontSizePx = LyricsTopbarFontSizePolicy.PRIMARY_DEFAULT_PX
    private var topbarSecondLineFontSizePx = LyricsTopbarFontSizePolicy.SECONDARY_DEFAULT_PX
    private var wallpaperFontScalePercent = FONT_SCALE_DEFAULT_PERCENT
    private var wallpaperBlurEnabled = WALLPAPER_BLUR_DEFAULT
    private var wallpaperShadowEnabled = WALLPAPER_SHADOW_DEFAULT
    private var wallpaperSpacing = WallpaperLyricsSpacing.STANDARD
    private var wallpaperFocus = WallpaperLyricsFocus.CENTER
    private var wallpaperPosition = WallpaperLyricsPosition.RIGHT
    private var nightTheme = true
    private var surfaceMode = LyricsSurfaceMode.TOPBAR
    private var surfaceHandoffTarget: LyricsSurfaceMode? = null
    private var surfaceHandoffGeneration = 0L
    private var displayState: IcarDisplayState? = null
    private var externalSurfaceOccupancy = IcarExternalSurfaceOccupancy()
    private var dockState = IcarDockWindowState.UNKNOWN
    private var srPanelMotionOccupancy = IcarSrPanelOccupancy.UNKNOWN
    private var wallpaperHorizontalAnimator: ValueAnimator? = null
    private var desktopVisibleRatioBasisPoints: Int? = null
    private var monitorStarted = false
    private var foregroundStarted = false
    private var audioRouteMonitorStarted = false
    private var audioPlaybackMonitorStarted = false
    private var avrcpEventMonitorStarted = false
    private var currentController: MediaController? = null
    private var hasMediaControllerSelection = false
    private val observedControllerCallbacks = linkedMapOf<
        MediaSession.Token,
        Pair<MediaController, MediaController.Callback>
    >()
    private val standardTimelineTracker = MediaSessionTimelineTracker {
        SystemClock.elapsedRealtime()
    }
    private val recordingStateTracker = MediaRecordingStateTracker()
    @Volatile private var currentRecordingState: MediaRecordingState? = null
    private var pendingSnapshot: JSONObject? = null
    private var cachedArtworkKey = ""
    private var cachedArtworkDataUrl = ""
    private var snapshotScheduled = false
    private var sessionSelectionRefreshScheduled = false
    private var lastSessionDiagnostics = ""
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
    private var activeLyricsRequestJob: Job? = null
    private var manualLyricsJob: Job? = null
    private var manualLyricsCancellation: LyricsCancellationSignal? = null
    private var manualSearchBinding: LyricsPlaybackIdentity? = null
    private var manualSearchBindingGeneration: Long? = null
    private var manualSearchState = LyricsManualSearchState.IDLE
    private val manualSearchCandidates = linkedMapOf<String, LyricsResult>()
    private var manualLyricsGeneration = 0L
    private var settingsStateGeneration = 0L
    private var observedSettingsPlayback: LyricsPlaybackIdentity? = null
    private var observedSettingsRecordingGeneration: Long? = null
    private val runtimeGeneration = AtomicLong(0L)
    @Volatile private var latestLyricsRequestId = 0
    @Volatile private var latestLyricsRecordingGeneration = 0L
    @Volatile private var latestLyricsQueryRevision = 0L

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
            mainHandler.postDelayed(this, 5_000L)
        }
    }
    private val sessionSelectionRefreshRunnable = Runnable {
        sessionSelectionRefreshScheduled = false
        if (monitorStarted) refreshActiveSessions()
    }
    private val sessionConvergenceRefreshRunnable = Runnable {
        if (monitorStarted) refreshActiveSessions()
    }
    private val sessionRetryRunnable = Runnable {
        if (isRunning && !monitorStarted) startMediaMonitor()
    }
    private val commercialAccessBoundaryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON ||
                intent?.action == Intent.ACTION_TIME_CHANGED
            ) {
                commercialRuntimeAccess.revalidate()
            }
        }
    }
    private val surfaceOccupancyListener: (IcarExternalSurfaceOccupancy) -> Unit = { occupancy ->
        if (externalSurfaceOccupancy != occupancy) {
            externalSurfaceOccupancy = occupancy
            Log.i(
                LOG_TAG,
                "External surface occupancy desktop=${occupancy.desktopRegionOccupied} " +
                    "fullDisplay=${occupancy.fullDisplayOccupied}"
            )
            if (displayState != null || overlayRoot != null) applyCurrentSurface()
        }
    }
    private val dockStateListener: (IcarDockWindowState) -> Unit = { state ->
        if (dockState != state) {
            dockState = state
            Log.i(
                LOG_TAG,
                "Dock window left=${state.left.status}/${state.left.expandedTopPx} " +
                    "center=${state.center.status}/${state.center.expandedTopPx} " +
                    "right=${state.right.status}/${state.right.expandedTopPx}"
            )
            if (displayState != null || overlayRoot != null) applyCurrentSurface()
        }
    }
    private val srPanelMotionListener: (IcarSrPanelOccupancy) -> Unit = { occupancy ->
        if (srPanelMotionOccupancy != occupancy) {
            srPanelMotionOccupancy = occupancy
            Log.i(LOG_TAG, "SR motion occupancy=$occupancy")
            if (displayState != null || overlayRoot != null) applyCurrentSurface()
        }
    }

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            if (monitorStarted) refreshActiveSessions(controllers.orEmpty())
        }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            if (monitorStarted) scheduleSessionSelectionRefresh()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            if (!monitorStarted) return
            // Drop a Browser binding as soon as the last Bluetooth output is
            // gone; the debounced session refresh then performs normal
            // controller selection and snapshot convergence.
            if (!hasBluetoothBrowserRoute()) bluetoothBrowserBridge.disconnect()
            scheduleSessionSelectionRefresh()
        }
    }

    private val audioPlaybackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            // This callback only wakes the MediaSession discovery path. Its
            // anonymized configuration payload is intentionally not consumed.
            if (monitorStarted) scheduleSessionSelectionRefresh()
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
        IcarDockStateRegistry.addListener(dockStateListener)
        IcarSrPanelMotionRegistry.addListener(srPanelMotionListener)
        registerCommercialAccessBoundaryReceiver()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val nextNightTheme = isNightTheme(newConfig)
        if (nightTheme == nextNightTheme) return
        nightTheme = nextNightTheme
        applyThemeToWeb()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val source = startupSource(intent)
        val overlayAccess = Settings.canDrawOverlays(this)
        val notificationAccess = hasNotificationListenerAccess()
        val systemDecision = LyricsStartupPolicy.decide(
            action = intent?.action,
            overlayAccess = overlayAccess,
            notificationAccess = notificationAccess
        )

        if (systemDecision == LyricsStartupOutcome.USER_STOPPED) {
            if (systemDecision.clearsAutoStart) {
                prefs.edit().putBoolean(PREF_AUTO_START, false).apply()
            }
            invalidatePendingCommercialChecks()
            commercialRuntimeAccess.clear()
            clearRecoveryNotification()
            releaseRuntimeResources()
            stopForegroundNotification()
            stopSelfResult(startId)
            logStartupOutcome(source, overlayAccess, notificationAccess, systemDecision)
            return START_NOT_STICKY
        }

        if (systemDecision == LyricsStartupOutcome.COMMERCIAL_RECOVERY) {
            enterCommercialRecoveryState(
                startId,
                CommercialAccessDecision.Denied(
                    CommercialAccessDenial.ENTITLEMENT_REVOKED
                )
            )
            logStartupOutcome(source, overlayAccess, notificationAccess, systemDecision)
            return START_NOT_STICKY
        }

        val commercialAccess = CommercialRuntimeFactory.entitlementCoordinator(this)
            .evaluate(System.currentTimeMillis())
        val waitingForCheck = beginCommercialStartupCheckIfNeeded(intent, commercialAccess)
        if (systemDecision == LyricsStartupOutcome.RECOVERY) {
            enterRecoveryState(startId, overlayAccess, notificationAccess)
            logStartupOutcome(source, overlayAccess, notificationAccess, systemDecision)
            return START_NOT_STICKY
        }

        val decision = LyricsCommercialGatePolicy.decide(systemDecision, commercialAccess)
        if (decision == LyricsStartupOutcome.COMMERCIAL_RECOVERY) {
            val runtimeActive = lyricsRepository != null || overlayRoot != null || monitorStarted
            if (LyricsCommercialStartupCheckPolicy.shouldDeferDeniedAccess(
                    waitingForCheck = waitingForCheck,
                    runtimeActive = runtimeActive
                )
            ) {
                Log.i(LOG_TAG, "Commercial startup revalidation pending; local access denied")
                logStartupOutcome(source, overlayAccess, notificationAccess, decision)
                return START_NOT_STICKY
            }
            enterCommercialRecoveryState(startId, commercialAccess)
            logStartupOutcome(source, overlayAccess, notificationAccess, decision)
            return START_NOT_STICKY
        }

        return enterAuthorizedState(
            intent = intent,
            source = source,
            startId = startId,
            overlayAccess = overlayAccess,
            notificationAccess = notificationAccess,
            commercialAccess = commercialAccess as CommercialAccessDecision.Allowed
        )
    }

    private fun beginCommercialStartupCheckIfNeeded(
        intent: Intent?,
        localAccess: CommercialAccessDecision
    ): Boolean {
        val localDenied = localAccess is CommercialAccessDecision.Denied
        if (!LyricsCommercialStartupCheckPolicy.shouldCheck(
                action = intent?.action,
                alreadyStarted = commercialStartupCheckStarted
            )
        ) {
            if (commercialStartupCheckPending && localDenied) {
                resumeAfterCommercialStartupCheck = true
                pendingCommercialStartupIntent = intent?.let(::Intent)
            }
            return commercialStartupCheckPending && localDenied
        }

        commercialStartupCheckStarted = true
        commercialStartupCheckPending = true
        resumeAfterCommercialStartupCheck = localDenied
        pendingCommercialStartupIntent = if (localDenied) intent?.let(::Intent) else null
        Log.i(
            LOG_TAG,
            "Commercial startup entitlement check started localAllowed=${!localDenied}"
        )
        launchCommercialEntitlementCheck()
        return localDenied
    }

    private fun recheckCommercialEntitlementAtTrialLease() {
        if (!isRunning || lyricsRepository == null) return
        if (commercialStartupCheckPending || commercialTrialLeaseCheckPending) return
        commercialTrialLeaseCheckPending = true
        Log.i(LOG_TAG, "Commercial trial lease boundary reached; checking entitlement")
        launchCommercialEntitlementCheck(::finishCommercialTrialLeaseCheck)
    }

    private fun launchCommercialEntitlementCheck(
        onComplete: (CommercialAccessRefreshResult) -> Unit = ::finishCommercialStartupCheck
    ) {
        commercialCheckScope.launch {
            val result = try {
                CommercialRuntimeFactory.entitlementCoordinator(this@LyricsOverlayService)
                    .recheckEntitlement(System.currentTimeMillis())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(LOG_TAG, "Commercial entitlement recheck failed unexpectedly", error)
                CommercialAccessRefreshResult.Failure(CommercialFailure.UNKNOWN)
            }
            mainHandler.post { onComplete(result) }
        }
    }

    private fun finishCommercialTrialLeaseCheck(result: CommercialAccessRefreshResult) {
        if (!commercialTrialLeaseCheckPending) return
        commercialTrialLeaseCheckPending = false

        val localAccess = CommercialRuntimeFactory.entitlementCoordinator(this)
            .evaluate(System.currentTimeMillis())
        val access = LyricsCommercialStartupCheckPolicy.reconcile(result, localAccess)
        val resultLog = when (result) {
            is CommercialAccessRefreshResult.Ready -> "ready"
            is CommercialAccessRefreshResult.Failure -> "failure_${result.reason.name.lowercase()}"
        }
        Log.i(
            LOG_TAG,
            "Commercial trial lease check completed result=$resultLog " +
                "localAllowed=${access is CommercialAccessDecision.Allowed}"
        )

        when (access) {
            is CommercialAccessDecision.Allowed -> {
                // The gateway has already verified and persisted a newly
                // issued lease when one was required. Re-authorizing here
                // replaces the old lease callback without recreating lyrics.
                commercialRuntimeAccess.authorize(access)
            }
            is CommercialAccessDecision.Denied -> {
                enterCommercialRecoveryState(latestStartId, access)
                logStartupOutcome(
                    source = START_SOURCE_RUNTIME_ACCESS,
                    overlayAccess = Settings.canDrawOverlays(this),
                    notificationAccess = hasNotificationListenerAccess(),
                    outcome = LyricsStartupOutcome.COMMERCIAL_RECOVERY
                )
            }
        }
    }

    private fun finishCommercialStartupCheck(result: CommercialAccessRefreshResult) {
        if (!commercialStartupCheckPending) return
        commercialStartupCheckPending = false
        val shouldResume = resumeAfterCommercialStartupCheck
        val pendingIntent = pendingCommercialStartupIntent
        resumeAfterCommercialStartupCheck = false
        pendingCommercialStartupIntent = null

        val localAccess = CommercialRuntimeFactory.entitlementCoordinator(this)
            .evaluate(System.currentTimeMillis())
        val access = LyricsCommercialStartupCheckPolicy.reconcile(result, localAccess)
        val resultLog = when (result) {
            is CommercialAccessRefreshResult.Ready -> "ready"
            is CommercialAccessRefreshResult.Failure -> "failure_${result.reason.name.lowercase()}"
        }
        Log.i(
            LOG_TAG,
            "Commercial entitlement recheck completed result=$resultLog " +
                "localAllowed=${access is CommercialAccessDecision.Allowed}"
        )

        if (access is CommercialAccessDecision.Denied) {
            enterCommercialRecoveryState(latestStartId, access)
            logStartupOutcome(
                startupSource(pendingIntent),
                Settings.canDrawOverlays(this),
                hasNotificationListenerAccess(),
                LyricsStartupOutcome.COMMERCIAL_RECOVERY
            )
            return
        }
        val allowedAccess = access as CommercialAccessDecision.Allowed
        if (lyricsRepository != null) {
            commercialRuntimeAccess.authorize(allowedAccess)
            return
        }
        if (!shouldResume) return

        val overlayAccess = Settings.canDrawOverlays(this)
        val notificationAccess = hasNotificationListenerAccess()
        if (!LyricsStartupPolicy.hasRequiredAccess(overlayAccess, notificationAccess)) {
            enterRecoveryState(latestStartId, overlayAccess, notificationAccess)
            logStartupOutcome(
                startupSource(pendingIntent),
                overlayAccess,
                notificationAccess,
                LyricsStartupOutcome.RECOVERY
            )
            return
        }
        enterAuthorizedState(
            intent = pendingIntent,
            source = startupSource(pendingIntent),
            startId = latestStartId,
            overlayAccess = overlayAccess,
            notificationAccess = notificationAccess,
            commercialAccess = allowedAccess
        )
    }

    private fun enterAuthorizedState(
        intent: Intent?,
        source: String,
        startId: Int,
        overlayAccess: Boolean,
        notificationAccess: Boolean,
        commercialAccess: CommercialAccessDecision.Allowed
    ): Int {
        commercialRuntimeAccess.authorize(commercialAccess)
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
        logStartupOutcome(source, overlayAccess, notificationAccess, LyricsStartupOutcome.RUNNING)
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
            val legacyScale = normalizedFontScale(
                intent.getIntExtra(EXTRA_FONT_SCALE_PERCENT, FONT_SCALE_DEFAULT_PERCENT)
            )
            topbarSingleLineFontSizePx = LyricsTopbarFontSizePolicy.primaryFromLegacyScale(legacyScale)
            topbarFirstLineFontSizePx = topbarSingleLineFontSizePx
            topbarSecondLineFontSizePx = LyricsTopbarFontSizePolicy.secondaryFromLegacyScale(legacyScale)
            wallpaperFontScalePercent = legacyScale
            prefs.edit()
                .putInt(PREF_TOPBAR_SINGLE_LINE_FONT_SIZE_PX, topbarSingleLineFontSizePx)
                .putInt(PREF_TOPBAR_FIRST_LINE_FONT_SIZE_PX, topbarFirstLineFontSizePx)
                .putInt(PREF_TOPBAR_SECOND_LINE_FONT_SIZE_PX, topbarSecondLineFontSizePx)
                .putInt(PREF_WALLPAPER_FONT_SCALE_PERCENT, wallpaperFontScalePercent)
                .apply()
            applyDisplayPreferencesToWeb()
            refreshTopbarPresentationGeometry()
            if (overlayRoot != null) return START_STICKY
        }

        if (intent?.action == ACTION_SET_TOPBAR_FONT_SCALE) {
            val legacyScale = normalizedFontScale(
                intent.getIntExtra(EXTRA_FONT_SCALE_PERCENT, FONT_SCALE_DEFAULT_PERCENT)
            )
            topbarSingleLineFontSizePx = LyricsTopbarFontSizePolicy.primaryFromLegacyScale(legacyScale)
            topbarFirstLineFontSizePx = topbarSingleLineFontSizePx
            topbarSecondLineFontSizePx = LyricsTopbarFontSizePolicy.secondaryFromLegacyScale(legacyScale)
            prefs.edit()
                .putInt(PREF_TOPBAR_SINGLE_LINE_FONT_SIZE_PX, topbarSingleLineFontSizePx)
                .putInt(PREF_TOPBAR_FIRST_LINE_FONT_SIZE_PX, topbarFirstLineFontSizePx)
                .putInt(PREF_TOPBAR_SECOND_LINE_FONT_SIZE_PX, topbarSecondLineFontSizePx)
                .apply()
            applyDisplayPreferencesToWeb()
            refreshTopbarPresentationGeometry()
            if (overlayRoot != null) return START_STICKY
        }

        if (intent?.action == ACTION_SET_TOPBAR_SINGLE_LINE_FONT_SIZE) {
            topbarSingleLineFontSizePx = LyricsTopbarFontSizePolicy.normalizePrimary(
                intent.getIntExtra(
                    EXTRA_TOPBAR_SINGLE_LINE_FONT_SIZE_PX,
                    LyricsTopbarFontSizePolicy.PRIMARY_DEFAULT_PX
                )
            )
            prefs.edit()
                .putInt(PREF_TOPBAR_SINGLE_LINE_FONT_SIZE_PX, topbarSingleLineFontSizePx)
                .apply()
            applyDisplayPreferencesToWeb()
            refreshTopbarPresentationGeometry()
            if (overlayRoot != null) return START_STICKY
        }

        if (intent?.action == ACTION_SET_TOPBAR_FIRST_LINE_FONT_SIZE) {
            topbarFirstLineFontSizePx = LyricsTopbarFontSizePolicy.normalizePrimary(
                intent.getIntExtra(
                    EXTRA_TOPBAR_FIRST_LINE_FONT_SIZE_PX,
                    LyricsTopbarFontSizePolicy.PRIMARY_DEFAULT_PX
                )
            )
            prefs.edit()
                .putInt(PREF_TOPBAR_FIRST_LINE_FONT_SIZE_PX, topbarFirstLineFontSizePx)
                .apply()
            applyDisplayPreferencesToWeb()
            refreshTopbarPresentationGeometry()
            if (overlayRoot != null) return START_STICKY
        }

        if (intent?.action == ACTION_SET_TOPBAR_SECOND_LINE_FONT_SIZE) {
            topbarSecondLineFontSizePx = LyricsTopbarFontSizePolicy.normalizeSecondary(
                intent.getIntExtra(
                    EXTRA_TOPBAR_SECOND_LINE_FONT_SIZE_PX,
                    LyricsTopbarFontSizePolicy.SECONDARY_DEFAULT_PX
                )
            )
            prefs.edit()
                .putInt(PREF_TOPBAR_SECOND_LINE_FONT_SIZE_PX, topbarSecondLineFontSizePx)
                .apply()
            applyDisplayPreferencesToWeb()
            refreshTopbarPresentationGeometry()
            if (overlayRoot != null) return START_STICKY
        }

        if (intent?.action == ACTION_SET_WALLPAPER_FONT_SCALE) {
            wallpaperFontScalePercent = normalizedFontScale(
                intent.getIntExtra(EXTRA_FONT_SCALE_PERCENT, FONT_SCALE_DEFAULT_PERCENT)
            )
            prefs.edit()
                .putInt(PREF_WALLPAPER_FONT_SCALE_PERCENT, wallpaperFontScalePercent)
                .apply()
            applyDisplayPreferencesToWeb()
            if (overlayRoot != null) return START_STICKY
        }

        if (intent?.action == ACTION_SET_WALLPAPER_BLUR) {
            wallpaperBlurEnabled = intent.getBooleanExtra(
                EXTRA_WALLPAPER_BLUR_ENABLED,
                WALLPAPER_BLUR_DEFAULT
            )
            prefs.edit().putBoolean(PREF_WALLPAPER_BLUR_ENABLED, wallpaperBlurEnabled).apply()
            applyDisplayPreferencesToWeb()
            if (overlayRoot != null) return START_STICKY
        }

        if (intent?.action == ACTION_SET_WALLPAPER_SHADOW) {
            wallpaperShadowEnabled = intent.getBooleanExtra(
                EXTRA_WALLPAPER_SHADOW_ENABLED,
                WALLPAPER_SHADOW_DEFAULT
            )
            prefs.edit().putBoolean(PREF_WALLPAPER_SHADOW_ENABLED, wallpaperShadowEnabled).apply()
            applyDisplayPreferencesToWeb()
            if (overlayRoot != null) return START_STICKY
        }

        if (intent?.action == ACTION_SET_WALLPAPER_SPACING) {
            wallpaperSpacing = WallpaperLyricsSpacing.fromPreference(
                intent.getStringExtra(EXTRA_WALLPAPER_SPACING)
            )
            prefs.edit().putString(PREF_WALLPAPER_SPACING, wallpaperSpacing.preferenceValue).apply()
            applyDisplayPreferencesToWeb()
            if (overlayRoot != null) return START_STICKY
        }

        if (intent?.action == ACTION_SET_WALLPAPER_FOCUS) {
            wallpaperFocus = WallpaperLyricsFocus.fromPreference(
                intent.getStringExtra(EXTRA_WALLPAPER_FOCUS)
            )
            prefs.edit().putString(PREF_WALLPAPER_FOCUS, wallpaperFocus.preferenceValue).apply()
            applyDisplayPreferencesToWeb()
            if (overlayRoot != null) return START_STICKY
        }

        if (intent?.action == ACTION_SET_WALLPAPER_POSITION) {
            wallpaperPosition = WallpaperLyricsPosition.fromPreference(
                intent.getStringExtra(EXTRA_WALLPAPER_POSITION)
            )
            prefs.edit()
                .putString(PREF_WALLPAPER_POSITION, wallpaperPosition.preferenceValue)
                .apply()
            applyDisplayPreferencesToWeb()
            applyCurrentSurface()
            return START_STICKY
        }

        if (intent?.action == ACTION_SET_TOPBAR_LINES) {
            topbarLines = normalizedTopbarLines(
                intent.getIntExtra(EXTRA_TOPBAR_LINES, TOPBAR_LINES_DEFAULT)
            )
            val (nextSingle, nextFirst) = LyricsTopbarFontSizePolicy.synchronizeLineMode(
                topbarLines,
                topbarSingleLineFontSizePx,
                topbarFirstLineFontSizePx
            )
            topbarSingleLineFontSizePx = nextSingle
            topbarFirstLineFontSizePx = nextFirst
            prefs.edit()
                .putInt(PREF_TOPBAR_LINES, topbarLines)
                .putInt(PREF_TOPBAR_SINGLE_LINE_FONT_SIZE_PX, topbarSingleLineFontSizePx)
                .putInt(PREF_TOPBAR_FIRST_LINE_FONT_SIZE_PX, topbarFirstLineFontSizePx)
                .apply()
            applyTopbarLines()
            refreshTopbarPresentationGeometry()
            if (overlayRoot != null) return START_STICKY
        }

        if (intent?.action == ACTION_SET_LYRICS_TRANSLATION) {
            lyricsTranslationEnabled = intent.getBooleanExtra(
                EXTRA_LYRICS_TRANSLATION_ENABLED,
                LYRICS_TRANSLATION_DEFAULT
            )
            prefs.edit()
                .putBoolean(PREF_LYRICS_TRANSLATION_ENABLED, lyricsTranslationEnabled)
                .apply()
            applyLyricsTranslationEnabled()
            refreshTopbarPresentationGeometry()
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
                refreshMediaSelectionForCommand()
                applyCurrentSurface()
                publishSettingsState()
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

        when (intent?.action) {
            ACTION_REQUEST_SETTINGS_STATE -> {
                if (lyricsCache == null) startRuntime()
                refreshMediaSelectionForCommand()
                publishSettingsState()
                return START_STICKY
            }
            ACTION_SEARCH_MANUAL_LYRICS -> {
                if (lyricsRepository == null) startRuntime()
                refreshMediaSelectionForCommand()
                searchManualLyrics(intent)
                return START_STICKY
            }
            ACTION_SELECT_MANUAL_LYRICS -> {
                if (lyricsCache == null) startRuntime()
                refreshMediaSelectionForCommand()
                selectManualLyrics(intent.getStringExtra(EXTRA_MANUAL_CANDIDATE_TOKEN).orEmpty())
                return START_STICKY
            }
            ACTION_RESTORE_AUTOMATIC_LYRICS -> {
                if (lyricsCache == null) startRuntime()
                refreshMediaSelectionForCommand()
                restoreAutomaticLyrics()
                return START_STICKY
            }
            ACTION_CLEAR_CURRENT_LYRICS_CACHE -> {
                if (lyricsCache == null) startRuntime()
                refreshMediaSelectionForCommand()
                clearCurrentLyricsCache()
                return START_STICKY
            }
        }

        startRuntime()
        publishSettingsState()
        return START_STICKY
    }

    override fun onDestroy() {
        SurfaceOccupancyLeaseRegistry.removeListener(surfaceOccupancyListener)
        IcarDockStateRegistry.removeListener(dockStateListener)
        IcarSrPanelMotionRegistry.removeListener(srPanelMotionListener)
        unregisterCommercialAccessBoundaryReceiver()
        invalidatePendingCommercialChecks()
        commercialCheckJob.cancel()
        commercialRuntimeAccess.clear()
        releaseRuntimeResources()
        mainHandler.removeCallbacksAndMessages(null)
        isRunning = false
        announceOverlayState()
        super.onDestroy()
    }

    private fun loadRuntimePreferences() {
        nightTheme = isNightTheme(resources.configuration)
        backgroundMode = BACKGROUND_TRANSPARENT
        val legacyFontScale = normalizedFontScale(
            prefs.getInt(PREF_FONT_SCALE_PERCENT, FONT_SCALE_DEFAULT_PERCENT)
        )
        val legacyTopbarFontScale = normalizedFontScale(
            prefs.getInt(PREF_TOPBAR_FONT_SCALE_PERCENT, legacyFontScale)
        )
        topbarSingleLineFontSizePx = LyricsTopbarFontSizePolicy.normalizePrimary(
            prefs.getInt(
                PREF_TOPBAR_SINGLE_LINE_FONT_SIZE_PX,
                LyricsTopbarFontSizePolicy.primaryFromLegacyScale(legacyTopbarFontScale)
            )
        )
        topbarFirstLineFontSizePx = LyricsTopbarFontSizePolicy.normalizePrimary(
            prefs.getInt(
                PREF_TOPBAR_FIRST_LINE_FONT_SIZE_PX,
                LyricsTopbarFontSizePolicy.primaryFromLegacyScale(legacyTopbarFontScale)
            )
        )
        topbarSecondLineFontSizePx = LyricsTopbarFontSizePolicy.normalizeSecondary(
            prefs.getInt(
                PREF_TOPBAR_SECOND_LINE_FONT_SIZE_PX,
                LyricsTopbarFontSizePolicy.secondaryFromLegacyScale(legacyTopbarFontScale)
            )
        )
        wallpaperFontScalePercent = normalizedFontScale(
            prefs.getInt(PREF_WALLPAPER_FONT_SCALE_PERCENT, legacyFontScale)
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
        lyricsTranslationEnabled = prefs.getBoolean(
            PREF_LYRICS_TRANSLATION_ENABLED,
            LYRICS_TRANSLATION_DEFAULT
        )
        wallpaperBlurEnabled = prefs.getBoolean(
            PREF_WALLPAPER_BLUR_ENABLED,
            WALLPAPER_BLUR_DEFAULT
        )
        wallpaperShadowEnabled = prefs.getBoolean(
            PREF_WALLPAPER_SHADOW_ENABLED,
            WALLPAPER_SHADOW_DEFAULT
        )
        wallpaperSpacing = WallpaperLyricsSpacing.fromPreference(
            prefs.getString(PREF_WALLPAPER_SPACING, WallpaperLyricsSpacing.STANDARD.preferenceValue)
        )
        wallpaperFocus = WallpaperLyricsFocus.fromPreference(
            prefs.getString(PREF_WALLPAPER_FOCUS, WallpaperLyricsFocus.CENTER.preferenceValue)
        )
        wallpaperPosition = WallpaperLyricsPosition.fromPreference(
            prefs.getString(PREF_WALLPAPER_POSITION, WallpaperLyricsPosition.RIGHT.preferenceValue)
        )
        prefs.edit()
            .putString(PREF_BACKGROUND_MODE, BACKGROUND_TRANSPARENT)
            .putInt(PREF_TOPBAR_SINGLE_LINE_FONT_SIZE_PX, topbarSingleLineFontSizePx)
            .putInt(PREF_TOPBAR_FIRST_LINE_FONT_SIZE_PX, topbarFirstLineFontSizePx)
            .putInt(PREF_TOPBAR_SECOND_LINE_FONT_SIZE_PX, topbarSecondLineFontSizePx)
            .putInt(PREF_WALLPAPER_FONT_SCALE_PERCENT, wallpaperFontScalePercent)
            .apply()
    }

    private fun startRuntime() {
        if (!commercialRuntimeAccess.hasCurrentAccess()) {
            Log.w(LOG_TAG, "Lyrics runtime withheld because commercial access is not granted")
            return
        }
        ensureLyricsRuntimeResources()
        displayStateMonitor.start()
        if (overlayRoot == null) createOverlay()
        startMediaMonitor()
    }

    private fun onCommercialRuntimeAccessDenied(access: CommercialAccessDecision.Denied) {
        invalidatePendingCommercialChecks()
        Log.i(
            LOG_TAG,
            "Commercial runtime access ended reason=${access.reason.name.lowercase()}"
        )
        enterCommercialRecoveryState(latestStartId, access)
        logStartupOutcome(
            source = START_SOURCE_RUNTIME_ACCESS,
            overlayAccess = Settings.canDrawOverlays(this),
            notificationAccess = hasNotificationListenerAccess(),
            outcome = LyricsStartupOutcome.COMMERCIAL_RECOVERY
        )
    }

    private fun registerCommercialAccessBoundaryReceiver() {
        if (commercialAccessBoundaryReceiverRegistered) return
        runCatching {
            ContextCompat.registerReceiver(
                this,
                commercialAccessBoundaryReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_TIME_CHANGED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            commercialAccessBoundaryReceiverRegistered = true
        }.onFailure { error ->
            Log.w(LOG_TAG, "Commercial runtime access boundary receiver unavailable", error)
        }
    }

    private fun unregisterCommercialAccessBoundaryReceiver() {
        if (!commercialAccessBoundaryReceiverRegistered) return
        runCatching { unregisterReceiver(commercialAccessBoundaryReceiver) }
        commercialAccessBoundaryReceiverRegistered = false
    }

    private fun ensureLyricsRuntimeResources() {
        if (lyricsRepository != null) return
        val repository = DirectLyricsRepository()
        lyricsRepository = repository
        lyricsResolutionCoordinator = LyricsResolutionCoordinator(
            lyricsResolver = repository,
            coverResolver = repository
        )
        lyricsCache = LyricsCache(this)
        val job = SupervisorJob()
        lyricsJob = job
        lyricsScope = CoroutineScope(job + Dispatchers.IO)
    }

    private fun restartRuntime() {
        val preserveAutoStart = prefs.getBoolean(PREF_AUTO_START, AUTO_START_DEFAULT)
        releaseRuntimeResources()
        loadRuntimePreferences()
        startRuntime()
        if (prefs.getBoolean(PREF_AUTO_START, AUTO_START_DEFAULT) != preserveAutoStart) {
            prefs.edit().putBoolean(PREF_AUTO_START, preserveAutoStart).apply()
            Log.w(LOG_TAG, "Restored auto-start intent after runtime restart")
        }
        Log.i(
            LOG_TAG,
            "Lyrics overlay runtime restarted generation=${runtimeGeneration.get()} " +
                "autoStart=$preserveAutoStart"
        )
    }

    private fun releaseRuntimeResources() {
        runtimeGeneration.incrementAndGet()
        cancelManualLyricsWork()
        manualSearchBinding = null
        manualSearchBindingGeneration = null
        manualSearchCandidates.clear()
        manualSearchState = LyricsManualSearchState.IDLE
        observedSettingsPlayback = null
        observedSettingsRecordingGeneration = null
        val activeRequest = synchronized(lyricsUsageLock) {
            latestLyricsRequestId = 0
            latestLyricsRecordingGeneration = 0L
            latestLyricsQueryRevision = 0L
            lastLyricsUsageKey = ""
            activeLyricsRequestJob.also { activeLyricsRequestJob = null }
        }
        activeRequest?.cancel()
        lyricsResolutionCoordinator?.cancelCurrent()
        lyricsJob?.cancel()
        lyricsJob = null
        lyricsScope = null
        lyricsResolutionCoordinator?.close()
        lyricsResolutionCoordinator = null
        lyricsRepository?.close()
        lyricsRepository = null
        lyricsCache?.close()
        lyricsCache = null

        mainHandler.removeCallbacks(dispatchRunnable)
        mainHandler.removeCallbacks(sessionRefreshRunnable)
        mainHandler.removeCallbacks(sessionRetryRunnable)
        snapshotScheduled = false
        displayStateMonitor.stop()
        stopMediaMonitor()
        destroyOverlay()

        displayState = null
        pendingSnapshot = null
        currentRecordingState = null
        recordingStateTracker.clear()
        translationAvailable = false
        cachedArtworkKey = ""
        cachedArtworkDataUrl = ""
    }

    private fun destroyOverlay() {
        webReady = false
        desktopVisibleRatioBasisPoints = null
        val player = webView
        val root = overlayRoot
        cancelSurfaceHandoff(resetAlpha = false)
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
            recordingGenerationText: String,
            queryRevisionText: String,
            requestId: Int,
            needsRemoteCover: Boolean
        ) {
            if (generation != runtimeGeneration.get() || requestId <= 0) return
            val recordingGeneration = recordingGenerationText.toLongOrNull() ?: return
            val queryRevision = queryRevisionText.toLongOrNull() ?: return
            val recordingState = currentRecordingState ?: return
            if (recordingState.recordingGeneration != recordingGeneration ||
                recordingState.queryRevision != queryRevision
            ) {
                return
            }
            val metadata = recordingState.metadata
            val track = metadata.track
            val artist = metadata.artist
            val album = metadata.album
            val durationMs = metadata.durationMs
            if (track.isBlank()) return
            val hasKnownDuration = LyricsCandidateSelector.hasKnownDuration(durationMs)
            val usageKey = if (hasKnownDuration) {
                LyricsCache.usageKey(track, artist, album, durationMs)
            } else {
                ""
            }
            val claim = synchronized(lyricsUsageLock) {
                val latestRecordingState = currentRecordingState
                if (generation != runtimeGeneration.get() ||
                    latestRecordingState?.recordingGeneration != recordingGeneration ||
                    latestRecordingState.queryRevision != queryRevision
                ) {
                    null
                } else {
                    val recordUse = hasKnownDuration && usageKey != lastLyricsUsageKey
                    if (hasKnownDuration) lastLyricsUsageKey = usageKey
                    latestLyricsRequestId = requestId
                    latestLyricsRecordingGeneration = recordingGeneration
                    latestLyricsQueryRevision = queryRevision
                    recordUse to activeLyricsRequestJob.also { activeLyricsRequestJob = null }
                }
            } ?: return
            claim.second?.cancel()
            val coordinator = lyricsResolutionCoordinator ?: return
            val cache = lyricsCache ?: return
            val requestScope = lyricsScope ?: return
            coordinator.cancelCurrent()
            if (!hasKnownDuration) {
                deliverLyricsResult(
                    generation,
                    recordingGeneration,
                    queryRevision,
                    requestId,
                    LyricsResult()
                )
                return
            }
            mainHandler.post {
                if (isCurrentLyricsRequest(
                        generation,
                        recordingGeneration,
                        queryRevision,
                        requestId
                    )
                ) {
                    updateTranslationAvailability(false)
                }
            }
            if (generation != runtimeGeneration.get()) return
            val query = LyricsLookup(track, artist, album, durationMs)
            val requestJob = requestScope.launch(start = CoroutineStart.LAZY) {
                val nowMs = System.currentTimeMillis()
                val cached = cache.get(track, artist, album, durationMs, claim.first, nowMs)
                if (!isCurrentLyricsRequest(
                        generation,
                        recordingGeneration,
                        queryRevision,
                        requestId
                    )
                ) return@launch
                if (cached != null) {
                    deliverLyricsResult(
                        generation,
                        recordingGeneration,
                        queryRevision,
                        requestId,
                        cached.result
                    )
                    if (!cached.needsRefresh(nowMs)) return@launch
                }

                val startedAt = SystemClock.elapsedRealtime()
                val outcome = coordinator.resolveLatest(query)
                if (!isCurrentLyricsRequest(
                        generation,
                        recordingGeneration,
                        queryRevision,
                        requestId
                    )
                ) {
                    return@launch
                }
                val resolved = (outcome as? LyricsResolutionOutcome.Found)?.resolved
                val result = resolved?.result ?: LyricsResult()
                val outcomeName = when (outcome) {
                    is LyricsResolutionOutcome.Found -> "found"
                    LyricsResolutionOutcome.NoMatch -> "no-match"
                    LyricsResolutionOutcome.InvalidMetadata -> "invalid-metadata"
                    is LyricsResolutionOutcome.RetryableFailure ->
                        "retryable-failure:${outcome.reason}"
                    LyricsResolutionOutcome.Cancelled -> "cancelled"
                }
                Log.i(
                    LOG_TAG,
                    "Direct lyrics outcome=$outcomeName " +
                        "source=${result.source.ifBlank { "none" }} " +
                        "kind=${result.lyricsKind} " +
                        "found=${result.lyrics.isNotBlank()} " +
                        "translation=${result.translatedLyrics.isNotBlank()} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
                )
                if (resolved != null) {
                    cache.put(track, artist, album, durationMs, resolved)
                    deliverLyricsResult(
                        generation,
                        recordingGeneration,
                        queryRevision,
                        requestId,
                        result
                    )
                } else if (cached == null && outcome != LyricsResolutionOutcome.Cancelled) {
                    deliverLyricsResult(
                        generation,
                        recordingGeneration,
                        queryRevision,
                        requestId,
                        result
                    )
                }

                if (needsRemoteCover && result.cover.isBlank()) {
                    val cover = coordinator.resolveCover(
                        LyricsLookup(track = track, artist = artist)
                    )
                    if (cover.isNotBlank() && isCurrentLyricsRequest(
                            generation,
                            recordingGeneration,
                            queryRevision,
                            requestId
                        )
                    ) {
                        deliverRemoteCover(
                            generation,
                            recordingGeneration,
                            queryRevision,
                            requestId,
                            cover
                        )
                    }
                }
            }
            requestJob.invokeOnCompletion {
                synchronized(lyricsUsageLock) {
                    if (activeLyricsRequestJob === requestJob) activeLyricsRequestJob = null
                }
            }
            val shouldStart = synchronized(lyricsUsageLock) {
                if (isCurrentLyricsRequest(
                        generation,
                        recordingGeneration,
                        queryRevision,
                        requestId
                    )
                ) {
                    activeLyricsRequestJob = requestJob
                    true
                } else {
                    false
                }
            }
            if (shouldStart) requestJob.start() else requestJob.cancel()
        }
    }

    private fun searchManualLyrics(intent: Intent) {
        val playback = currentPlaybackIdentity()
        val playbackGeneration = currentRecordingState?.recordingGeneration
        val track = intent.getStringExtra(EXTRA_MANUAL_TRACK)?.trim().orEmpty()
        val artist = intent.getStringExtra(EXTRA_MANUAL_ARTIST)?.trim().orEmpty()
        val album = intent.getStringExtra(EXTRA_MANUAL_ALBUM)?.trim().orEmpty()
        if (playback?.isUsable != true || track.isBlank()) {
            cancelManualLyricsWork()
            manualSearchBinding = null
            manualSearchBindingGeneration = null
            manualSearchCandidates.clear()
            manualSearchState = LyricsManualSearchState.NO_CURRENT_TRACK
            publishSettingsState()
            return
        }
        val repository = lyricsRepository ?: return
        val scope = lyricsScope ?: return
        cancelManualLyricsWork()
        manualSearchBinding = playback
        manualSearchBindingGeneration = playbackGeneration
        manualSearchCandidates.clear()
        manualSearchState = LyricsManualSearchState.SEARCHING
        publishSettingsState()

        val generation = manualLyricsGeneration
        val cancellation = LyricsCancellationSignal()
        manualLyricsCancellation = cancellation
        val job = scope.launch {
            val results = repository.searchManualCandidates(
                LyricsLookup(track, artist, album, playback.durationMs),
                cancellation
            )
            mainHandler.post {
                if (generation != manualLyricsGeneration) return@post
                manualLyricsJob = null
                manualLyricsCancellation = null
                manualSearchCandidates.clear()
                results.forEach { candidate ->
                    manualSearchCandidates[LyricsManualSearchPolicy.token(candidate)] = candidate
                }
                manualSearchState = if (results.isEmpty()) {
                    LyricsManualSearchState.EMPTY
                } else {
                    LyricsManualSearchState.READY
                }
                publishSettingsState()
            }
        }
        manualLyricsJob = job
    }

    private fun selectManualLyrics(token: String) {
        val playbackGeneration = currentRecordingState?.recordingGeneration
        val binding = manualSearchBinding
        val bindingGeneration = manualSearchBindingGeneration
        val candidate = manualSearchCandidates[token]
        if (binding?.isUsable != true || bindingGeneration == null ||
            bindingGeneration != playbackGeneration
        ) {
            cancelManualLyricsWork()
            manualSearchCandidates.clear()
            manualSearchBinding = null
            manualSearchBindingGeneration = null
            manualSearchState = LyricsManualSearchState.NO_CURRENT_TRACK
            publishSettingsState()
            return
        }
        if (candidate == null) {
            manualSearchState = LyricsManualSearchState.ERROR
            publishSettingsState()
            return
        }
        val repository = lyricsRepository ?: return
        val cache = lyricsCache ?: return
        val scope = lyricsScope ?: return
        cancelManualLyricsWork()
        manualSearchState = LyricsManualSearchState.APPLYING
        publishSettingsState()

        val generation = manualLyricsGeneration
        val cancellation = LyricsCancellationSignal()
        manualLyricsCancellation = cancellation
        val loadJob = scope.launch {
            val result = repository.loadManualLyrics(candidate, cancellation)
            mainHandler.post {
                if (generation != manualLyricsGeneration) return@post
                manualLyricsCancellation = null
                if (bindingGeneration != currentRecordingState?.recordingGeneration) {
                    manualLyricsJob = null
                    manualSearchCandidates.clear()
                    manualSearchBinding = null
                    manualSearchBindingGeneration = null
                    manualSearchState = LyricsManualSearchState.NO_CURRENT_TRACK
                    publishSettingsState()
                    return@post
                }
                if (result == null) {
                    manualLyricsJob = null
                    manualSearchState = LyricsManualSearchState.ERROR
                    publishSettingsState()
                    return@post
                }
                val cacheJob = scope.launch {
                    cache.putManual(binding, result)
                    mainHandler.post cacheCommitted@{
                        if (generation != manualLyricsGeneration) return@cacheCommitted
                        manualLyricsJob = null
                        manualSearchState = LyricsManualSearchState.READY
                        reloadCurrentLyrics()
                        publishSettingsState()
                    }
                }
                manualLyricsJob = cacheJob
            }
        }
        manualLyricsJob = loadJob
    }

    private fun restoreAutomaticLyrics() {
        val playback = currentPlaybackIdentity()
        val cache = lyricsCache
        val scope = lyricsScope
        if (playback?.isUsable != true || cache == null || scope == null) {
            manualSearchState = LyricsManualSearchState.NO_CURRENT_TRACK
            publishSettingsState()
            return
        }
        scope.launch {
            cache.clearManual(playback)
            mainHandler.post {
                reloadCurrentLyrics()
                publishSettingsState()
            }
        }
    }

    private fun clearCurrentLyricsCache() {
        val playback = currentPlaybackIdentity()
        val cache = lyricsCache
        val scope = lyricsScope
        if (playback?.isUsable != true || cache == null || scope == null) {
            publishSettingsState()
            return
        }
        scope.launch {
            cache.clearCurrent(playback)
            mainHandler.post { publishSettingsState() }
        }
    }

    private fun cancelManualLyricsWork() {
        manualLyricsGeneration += 1L
        manualLyricsCancellation?.cancel()
        manualLyricsCancellation = null
        manualLyricsJob?.cancel()
        manualLyricsJob = null
    }

    private fun reloadCurrentLyrics() {
        if (!webReady) return
        webView?.evaluateJavascript(
            "window.LobstaOverlay && window.LobstaOverlay.retryLyrics();",
            null
        )
    }

    private fun currentPlaybackIdentity(): LyricsPlaybackIdentity? {
        val metadata = currentRecordingState?.metadata ?: return null
        if (!metadata.hasTrack) return null
        return LyricsPlaybackIdentity(
            metadata.track,
            metadata.artist,
            metadata.album,
            metadata.durationMs
        )
    }

    private fun refreshSettingsPlaybackIdentity() {
        val current = currentPlaybackIdentity()
        val currentGeneration = currentRecordingState?.recordingGeneration
        if (observedSettingsPlayback == current &&
            observedSettingsRecordingGeneration == currentGeneration
        ) return
        observedSettingsPlayback = current
        observedSettingsRecordingGeneration = currentGeneration
        if (manualSearchBinding != null &&
            manualSearchBindingGeneration != currentGeneration
        ) {
            cancelManualLyricsWork()
            manualSearchBinding = null
            manualSearchBindingGeneration = null
            manualSearchCandidates.clear()
            manualSearchState = LyricsManualSearchState.IDLE
        }
        publishSettingsState()
    }

    private fun publishSettingsState() {
        val cache = lyricsCache ?: return
        val scope = lyricsScope ?: return
        val playback = currentPlaybackIdentity()
        val recordingGeneration = currentRecordingState?.recordingGeneration ?: 0L
        val searchState = manualSearchState
        val candidates = manualSearchCandidates.map { (token, result) ->
            LyricsManualSearchCandidate(token, result.candidateSnapshot())
        }
        val generation = ++settingsStateGeneration
        scope.launch {
            val snapshot = cache.snapshot(playback)
            val state = LyricsSettingsRuntimeState(
                playback = playback,
                cache = snapshot,
                searchState = searchState,
                searchCandidates = candidates,
                recordingGeneration = recordingGeneration
            )
            mainHandler.post {
                if (generation != settingsStateGeneration) return@post
                sendBroadcast(
                    Intent(ACTION_SETTINGS_STATE_CHANGED)
                        .setPackage(packageName)
                        .putExtra(EXTRA_SETTINGS_STATE, state.encode())
                )
            }
        }
    }

    private fun isCurrentLyricsRequest(
        generation: Long,
        recordingGeneration: Long,
        queryRevision: Long,
        requestId: Int
    ): Boolean = generation == runtimeGeneration.get() &&
        recordingGeneration == latestLyricsRecordingGeneration &&
        queryRevision == latestLyricsQueryRevision &&
        requestId == latestLyricsRequestId &&
        currentRecordingState?.let { current ->
            current.recordingGeneration == recordingGeneration &&
                current.queryRevision == queryRevision
        } == true

    private fun deliverLyricsResult(
        generation: Long,
        recordingGeneration: Long,
        queryRevision: Long,
        requestId: Int,
        result: LyricsResult
    ) {
        val payload = result.toJson().toString()
        val hasTranslation = classifyLyrics(result.translatedLyrics) == LyricsKind.SYNCHRONIZED
        mainHandler.post {
            if (!isCurrentLyricsRequest(
                    generation,
                    recordingGeneration,
                    queryRevision,
                    requestId
                ) || !webReady
            ) return@post
            updateTranslationAvailability(hasTranslation)
            webView?.evaluateJavascript(
                "window.LobstaOverlay && window.LobstaOverlay.receiveLyrics(" +
                    "$recordingGeneration,$queryRevision,$requestId,$payload);",
                null
            )
        }
    }

    private fun updateTranslationAvailability(available: Boolean) {
        if (translationAvailable == available) return
        translationAvailable = available
        refreshTopbarPresentationGeometry()
    }

    private fun deliverRemoteCover(
        generation: Long,
        recordingGeneration: Long,
        queryRevision: Long,
        requestId: Int,
        cover: String
    ) {
        val encodedCover = JSONObject.quote(cover)
        mainHandler.post {
            if (!isCurrentLyricsRequest(
                    generation,
                    recordingGeneration,
                    queryRevision,
                    requestId
                ) || !webReady
            ) return@post
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
        commercialRuntimeAccess.clear()
        releaseRuntimeResources()
        stopForegroundNotification()
        notificationManager.notify(
            RECOVERY_NOTIFICATION_ID,
            buildRecoveryNotification(overlayAccess, notificationAccess)
        )
        stopSelfResult(startId)
    }

    private fun enterCommercialRecoveryState(
        startId: Int,
        access: CommercialAccessDecision
    ) {
        invalidatePendingCommercialChecks()
        commercialRuntimeAccess.clear()
        releaseRuntimeResources()
        stopForegroundNotification()
        notificationManager.notify(
            COMMERCIAL_RECOVERY_NOTIFICATION_ID,
            buildCommercialRecoveryNotification(access)
        )
        stopSelfResult(startId)
    }

    /** Ignore completions from a check that raced with a terminal lifecycle state. */
    private fun invalidatePendingCommercialChecks() {
        commercialStartupCheckPending = false
        commercialTrialLeaseCheckPending = false
        resumeAfterCommercialStartupCheck = false
        pendingCommercialStartupIntent = null
    }

    private fun buildCommercialRecoveryNotification(
        access: CommercialAccessDecision
    ): android.app.Notification {
        val reason = (access as? CommercialAccessDecision.Denied)?.reason
        val message = getString(
            when (reason) {
                CommercialAccessDenial.LICENSE_EXPIRED -> R.string.notification_commercial_expired
                CommercialAccessDenial.ENTITLEMENT_REVOKED -> {
                    R.string.notification_commercial_revoked
                }
                CommercialAccessDenial.CONFIGURATION_MISSING -> {
                    R.string.notification_commercial_configuration_missing
                }
                else -> R.string.notification_commercial_unavailable
            }
        )
        val settingsIntent = PendingIntent.getActivity(
            this,
            COMMERCIAL_RECOVERY_SETTINGS_REQUEST_CODE,
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_SETTINGS_SECTION, MainActivity.SECTION_COMMERCIAL)
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
            .setContentTitle(getString(R.string.notification_commercial_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(settingsIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
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
        notificationManager.cancel(COMMERCIAL_RECOVERY_NOTIFICATION_ID)
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
            ACTION_COMMERCIAL_ACCESS_CHANGED -> "commercial_access_changed"
            ACTION_COMMERCIAL_ACCESS_REVOKED -> "commercial_access_revoked"
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
        if (!commercialRuntimeAccess.hasCurrentAccess()) {
            Log.w(LOG_TAG, "Lyric surface withheld because commercial access is not granted")
            return
        }
        val generation = runtimeGeneration.get()
        val presentation = currentPresentation()
        surfaceMode = presentation.surfaceMode
        compact = surfaceMode == LyricsSurfaceMode.TOPBAR
        val geometry = geometryForPresentation(presentation)
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
            clipChildren = true
            clipToPadding = true
            clipToOutline = true
            elevation = 0f
            background = overlayBackground(compact)
        }
        overlayRoot = root

        val webContainerView = FrameLayout(this)
        webContainer = webContainerView
        root.addView(webContainerView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (compact) topbarWindowHeight() else desktopWindowHeight(),
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
                    applyDisplayPreferencesToWeb()
                    applySurfaceModeToWeb()
                    applyDesktopVisibleBoundaryToWeb(
                        OverlayGeometry(
                            x = params.x,
                            y = params.y,
                            width = params.width,
                            height = params.height
                        ),
                        force = true
                    )
                    applyTopbarLines()
                    applyLyricsTranslationEnabled()
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
        updateOverlayVisibility(geometry, presentation.visibility)
    }

    private data class OverlayGeometry(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    )

    private fun onIcarDisplayStateChanged(state: IcarDisplayState) {
        displayState = state
        applyCurrentSurface()
    }

    /** Resolves the only lyric presentation from the latest vehicle and user state. */
    private fun currentPresentation(): IcarLyricsPresentation =
        IcarLyricsPresentationPolicy.resolve(
            displayState = displayState,
            wallpaperLyricsEnabled = wallpaperLyricsEnabled,
            localSettingsOpen = localSettingsOpen,
            externalSurfaceOccupancy = externalSurfaceOccupancy,
            wallpaperPosition = wallpaperPosition,
            srPanelMotionOccupancy = srPanelMotionOccupancy,
            dockState = dockState
        )

    private fun geometryForPresentation(presentation: IcarLyricsPresentation): OverlayGeometry =
        overlayGeometry(
            mode = presentation.surfaceMode,
            state = displayState,
            desktopBottomLimitPx = presentation.desktopBottomLimitPx,
            desktopPosition = presentation.desktopPosition,
            srPanelOccupancy = presentation.srPanelOccupancy
        )

    private fun geometryForSurface(mode: LyricsSurfaceMode): OverlayGeometry {
        val presentation = currentPresentation()
        return overlayGeometry(
            mode = mode,
            state = displayState,
            desktopBottomLimitPx = if (presentation.surfaceMode == mode) {
                presentation.desktopBottomLimitPx
            } else {
                null
            },
            desktopPosition = presentation.desktopPosition,
            srPanelOccupancy = presentation.srPanelOccupancy
        )
    }

    private fun applyCurrentSurface() {
        if (!commercialRuntimeAccess.hasCurrentAccess()) return
        val presentation = currentPresentation()
        val nextSurfaceMode = presentation.surfaceMode
        val geometry = geometryForPresentation(presentation)
        val root = overlayRoot
        if (root == null) {
            createOverlay()
            return
        }
        if (presentation.visibility == LyricsOverlayVisibility.HIDDEN) {
            cancelSurfaceHandoff()
            root.visibility = View.GONE
        }
        if (surfaceMode != nextSurfaceMode ||
            (surfaceHandoffTarget != null && surfaceHandoffTarget != nextSurfaceMode)
        ) {
            applySurfaceMode(nextSurfaceMode)
        }
        if (surfaceMode == nextSurfaceMode) {
            windowParams?.let { params ->
                applyOverlayGeometry(
                    params = params,
                    geometry = geometry,
                    animateDesktopHorizontal = nextSurfaceMode == LyricsSurfaceMode.DESKTOP &&
                        presentation.visibility == LyricsOverlayVisibility.VISIBLE &&
                        root.visibility == View.VISIBLE
                )
            }
        }
        updateOverlayVisibility(geometry, presentation.visibility)
    }

    private fun applySurfaceMode(nextSurfaceMode: LyricsSurfaceMode) {
        val root = overlayRoot
        val geometry = geometryForSurface(nextSurfaceMode)
        if (root == null || root.visibility != View.VISIBLE ||
            !IcarLyricsSurfacePolicy.hasRenderableGeometry(geometry.width, geometry.height)
        ) {
            cancelSurfaceHandoff()
            commitSurfaceMode(nextSurfaceMode, geometry)
            updateWindowTouchability(compact)
            return
        }
        if (surfaceHandoffTarget == nextSurfaceMode) return

        surfaceHandoffGeneration += 1
        val generation = surfaceHandoffGeneration
        surfaceHandoffTarget = nextSurfaceMode
        root.animate().cancel()
        updateWindowTouchability(isTopbar = false)

        if (nextSurfaceMode == surfaceMode) {
            startSurfaceFadeIn(root, nextSurfaceMode, generation)
            return
        }
        if (root.alpha <= SURFACE_HIDDEN_ALPHA) {
            commitSurfaceModeWhileHidden(root, nextSurfaceMode, generation)
            return
        }

        root.animate()
            .alpha(0f)
            .setDuration(SURFACE_FADE_OUT_MS)
            .setInterpolator(LinearInterpolator())
            .withLayer()
            .withEndAction {
                if (isCurrentSurfaceHandoff(root, nextSurfaceMode, generation)) {
                    commitSurfaceModeWhileHidden(root, nextSurfaceMode, generation)
                }
            }
            .start()
    }

    private fun commitSurfaceModeWhileHidden(
        root: FrameLayout,
        nextSurfaceMode: LyricsSurfaceMode,
        generation: Long
    ) {
        if (!isCurrentSurfaceHandoff(root, nextSurfaceMode, generation)) return
        root.alpha = 0f
        val geometry = geometryForSurface(nextSurfaceMode)
        if (!IcarLyricsSurfacePolicy.hasRenderableGeometry(geometry.width, geometry.height)) {
            commitSurfaceMode(nextSurfaceMode, geometry)
            finishSurfaceHandoff(root, nextSurfaceMode, generation)
            return
        }
        commitSurfaceMode(nextSurfaceMode, geometry) {
            root.postOnAnimation {
                if (isCurrentSurfaceHandoff(root, nextSurfaceMode, generation)) {
                    startSurfaceFadeIn(root, nextSurfaceMode, generation)
                }
            }
        }
    }

    private fun startSurfaceFadeIn(
        root: FrameLayout,
        target: LyricsSurfaceMode,
        generation: Long
    ) {
        if (!isCurrentSurfaceHandoff(root, target, generation)) return
        if (root.visibility != View.VISIBLE) {
            finishSurfaceHandoff(root, target, generation)
            return
        }
        root.animate().cancel()
        root.animate()
            .alpha(1f)
            .setDuration(SURFACE_FADE_IN_MS)
            .setInterpolator(LinearInterpolator())
            .withLayer()
            .withEndAction {
                finishSurfaceHandoff(root, target, generation)
            }
            .start()
    }

    private fun finishSurfaceHandoff(
        root: FrameLayout,
        target: LyricsSurfaceMode,
        generation: Long
    ) {
        if (!isCurrentSurfaceHandoff(root, target, generation)) return
        root.alpha = 1f
        surfaceHandoffTarget = null
        updateWindowTouchability(compact)
    }

    private fun isCurrentSurfaceHandoff(
        root: FrameLayout,
        target: LyricsSurfaceMode,
        generation: Long
    ): Boolean = overlayRoot === root &&
        surfaceHandoffTarget == target &&
        surfaceHandoffGeneration == generation

    private fun cancelSurfaceHandoff(resetAlpha: Boolean = true) {
        surfaceHandoffGeneration += 1
        surfaceHandoffTarget = null
        overlayRoot?.animate()?.cancel()
        cancelWallpaperHorizontalAnimation()
        if (resetAlpha) overlayRoot?.alpha = 1f
    }

    private fun commitSurfaceMode(
        nextSurfaceMode: LyricsSurfaceMode,
        geometry: OverlayGeometry = geometryForSurface(nextSurfaceMode),
        onWebApplied: (() -> Unit)? = null
    ) {
        surfaceMode = nextSurfaceMode
        compact = surfaceMode == LyricsSurfaceMode.TOPBAR
        val params = windowParams
        if (params == null) {
            onWebApplied?.invoke()
            return
        }
        applyOverlayGeometry(params, geometry)
        overlayRoot?.background = overlayBackground(compact)
        updateWebContainerLayout(compact)
        applySurfaceModeToWeb(onWebApplied)
    }

    /** Icon updates reach here only while already in topbar mode. */
    private fun applyTopbarGeometry(state: IcarDisplayState) {
        val params = windowParams ?: return
        applyOverlayGeometry(params, overlayGeometry(LyricsSurfaceMode.TOPBAR, state))
    }

    private fun applyOverlayGeometry(
        params: WindowManager.LayoutParams,
        geometry: OverlayGeometry,
        animateDesktopHorizontal: Boolean = false
    ) {
        if (!IcarLyricsSurfacePolicy.hasRenderableGeometry(geometry.width, geometry.height)) {
            cancelWallpaperHorizontalAnimation()
            return
        }
        if (animateDesktopHorizontal && params.x != geometry.x) {
            startWallpaperHorizontalAnimation(params, geometry)
            return
        }
        cancelWallpaperHorizontalAnimation()
        commitOverlayGeometry(params, geometry)
    }

    private fun startWallpaperHorizontalAnimation(
        params: WindowManager.LayoutParams,
        geometry: OverlayGeometry
    ) {
        val root = overlayRoot ?: return
        val startX = params.x
        cancelWallpaperHorizontalAnimation()
        commitOverlayGeometry(params, geometry.copy(x = startX))
        wallpaperHorizontalAnimator = ValueAnimator.ofInt(startX, geometry.x).apply {
            duration = IcarWallpaperHorizontalMotionSpec.DURATION_MS
            interpolator = PathInterpolator(
                IcarWallpaperHorizontalMotionSpec.CONTROL_X1,
                IcarWallpaperHorizontalMotionSpec.CONTROL_Y1,
                IcarWallpaperHorizontalMotionSpec.CONTROL_X2,
                IcarWallpaperHorizontalMotionSpec.CONTROL_Y2
            )
            addUpdateListener { animator ->
                if (overlayRoot !== root || windowParams !== params) {
                    animator.cancel()
                    return@addUpdateListener
                }
                params.x = animator.animatedValue as Int
                runCatching { windowManager.updateViewLayout(root, params) }
                    .onFailure { error ->
                        Log.w(LOG_TAG, "Unable to animate lyric surface position", error)
                        animator.cancel()
                    }
            }
            start()
        }
    }

    private fun cancelWallpaperHorizontalAnimation() {
        wallpaperHorizontalAnimator?.cancel()
        wallpaperHorizontalAnimator = null
    }

    private fun commitOverlayGeometry(
        params: WindowManager.LayoutParams,
        geometry: OverlayGeometry
    ) {
        val changed = params.x != geometry.x ||
            params.y != geometry.y ||
            params.width != geometry.width ||
            params.height != geometry.height
        params.x = geometry.x
        params.y = geometry.y
        params.width = geometry.width
        params.height = geometry.height
        applyDesktopVisibleBoundaryToWeb(geometry)
        if (!changed) return
        overlayRoot?.let { root ->
            runCatching { windowManager.updateViewLayout(root, params) }
                .onFailure { error -> Log.w(LOG_TAG, "Unable to update lyric surface", error) }
        }
    }

    private fun applyDesktopVisibleBoundaryToWeb(
        geometry: OverlayGeometry,
        force: Boolean = false
    ) {
        if (!webReady) return
        val ratioBasisPoints = if (surfaceMode == LyricsSurfaceMode.DESKTOP) {
            IcarWallpaperClipPolicy.visibleRatioBasisPoints(
                defaultHeightPx = desktopWindowHeight(),
                visibleHeightPx = geometry.height
            )
        } else {
            IcarWallpaperClipPolicy.FULL_RATIO_BASIS_POINTS
        }
        if (!force && desktopVisibleRatioBasisPoints == ratioBasisPoints) return
        desktopVisibleRatioBasisPoints = ratioBasisPoints
        val ratio = ratioBasisPoints.toDouble() /
            IcarWallpaperClipPolicy.FULL_RATIO_BASIS_POINTS
        webView?.evaluateJavascript(
            "window.LobstaOverlay && window.LobstaOverlay.setDesktopVisibleRatio($ratio);",
            null
        )
    }

    private fun updateOverlayVisibility(
        geometry: OverlayGeometry,
        visibility: LyricsOverlayVisibility
    ) {
        overlayRoot?.visibility = if (
            visibility == LyricsOverlayVisibility.VISIBLE &&
            IcarLyricsSurfacePolicy.hasRenderableGeometry(geometry.width, geometry.height)
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun overlayGeometry(
        mode: LyricsSurfaceMode,
        state: IcarDisplayState?,
        desktopBottomLimitPx: Int? = null,
        desktopPosition: WallpaperLyricsPosition = WallpaperLyricsPosition.RIGHT,
        srPanelOccupancy: IcarSrPanelOccupancy =
            state?.srPanelOccupancy ?: IcarSrPanelOccupancy.UNKNOWN
    ): OverlayGeometry {
        val realMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(realMetrics)
        val screenWidth = realMetrics.widthPixels
        val screenHeight = realMetrics.heightPixels
        fun scaledX(value: Int): Int = (screenWidth * value / DESIGN_WIDTH.toFloat()).roundToInt()
        fun scaledY(value: Int): Int = (screenHeight * value / DESIGN_HEIGHT.toFloat()).roundToInt()

        if (mode == LyricsSurfaceMode.DESKTOP) {
            val top = scaledY(DESKTOP_TOP)
            val width = scaledX(DESKTOP_RIGHT - DESKTOP_LEFT).coerceAtMost(screenWidth)
            val left = IcarWallpaperPositionPolicy.leftPx(
                screenWidthPx = screenWidth,
                surfaceWidthPx = width,
                edgeInsetPx = scaledX(DESKTOP_EDGE_INSET),
                position = desktopPosition,
                srPanelOccupancy = srPanelOccupancy
            )
            val defaultBottom = scaledY(DESKTOP_BOTTOM).coerceAtMost(screenHeight)
            val bottom = IcarWallpaperClipPolicy.bottomPx(
                defaultBottomPx = defaultBottom,
                dockTopPx = desktopBottomLimitPx,
                safeGapPx = scaledY(DOCK_SAFE_GAP)
            )
            return OverlayGeometry(
                x = left,
                y = top,
                width = max(1, width),
                height = max(0, bottom - top)
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
            height = topbarWindowHeight()
        )
    }

    private fun applySurfaceModeToWeb(onApplied: (() -> Unit)? = null) {
        if (!webReady) {
            onApplied?.invoke()
            return
        }
        val encodedMode = JSONObject.quote(
            if (surfaceMode == LyricsSurfaceMode.DESKTOP) "desktop" else "topbar"
        )
        val player = webView
        if (player == null) {
            onApplied?.invoke()
            return
        }
        runCatching {
            player.evaluateJavascript(
                "window.LobstaOverlay && window.LobstaOverlay.setSurfaceMode($encodedMode);"
            ) {
                onApplied?.invoke()
            }
        }.onFailure {
            onApplied?.invoke()
        }
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

    private fun topbarWindowHeight(): Int {
        val baseHeight = statusBarHeight()
        val realMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(realMetrics)
        val maximumHeight = max(
            baseHeight,
            (realMetrics.heightPixels * DESKTOP_TOP / DESIGN_HEIGHT.toFloat()).roundToInt()
        )
        return min(
            maximumHeight,
            max(
                baseHeight,
                dp(
                    LyricsTopbarHeightPolicy.requiredHeightDp(
                        topbarLines,
                        if (topbarLines == 1) topbarSingleLineFontSizePx else topbarFirstLineFontSizePx,
                        if (topbarLines == 1) {
                            LyricsTopbarFontSizePolicy.secondaryForSingleLine(topbarSingleLineFontSizePx)
                        } else {
                            topbarSecondLineFontSizePx
                        },
                        lyricsTranslationEnabled && translationAvailable
                    )
                )
            )
        )
    }

    private fun desktopWindowHeight(): Int {
        val realMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(realMetrics)
        val top = (realMetrics.heightPixels * DESKTOP_TOP / DESIGN_HEIGHT.toFloat()).roundToInt()
        val bottom = (realMetrics.heightPixels * DESKTOP_BOTTOM / DESIGN_HEIGHT.toFloat()).roundToInt()
        return (bottom - top).coerceAtLeast(1)
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

    private fun applyDisplayPreferencesToWeb() {
        if (!webReady) return
        val value = JSONObject()
            .put("topbarSingleLineFontSize", topbarSingleLineFontSizePx)
            .put("topbarFirstLineFontSize", topbarFirstLineFontSizePx)
            .put("topbarSecondLineFontSize", topbarSecondLineFontSizePx)
            .put("wallpaperFontScale", wallpaperFontScalePercent)
            .put("wallpaperBlur", wallpaperBlurEnabled)
            .put("wallpaperShadow", wallpaperShadowEnabled)
            .put("wallpaperSpacing", wallpaperSpacing.preferenceValue)
            .put("wallpaperFocus", wallpaperFocus.preferenceValue)
            .put("wallpaperPosition", wallpaperPosition.preferenceValue)
        webView?.evaluateJavascript(
            "window.LobstaOverlay && window.LobstaOverlay.setDisplayPreferences($value);",
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
                topbarWindowHeight()
            } else {
                desktopWindowHeight()
            }
            containerParams.gravity = Gravity.TOP
            container.layoutParams = containerParams
            container.requestLayout()
        }
    }

    private fun refreshTopbarPresentationGeometry() {
        if (surfaceMode != LyricsSurfaceMode.TOPBAR) return
        val params = windowParams ?: return
        applyOverlayGeometry(params, overlayGeometry(LyricsSurfaceMode.TOPBAR, displayState))
        updateWebContainerLayout(isTopbar = true)
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

    private fun applyLyricsTranslationEnabled() {
        if (!webReady) return
        webView?.evaluateJavascript(
            "window.LobstaOverlay && window.LobstaOverlay.setLyricsTranslationEnabled(" +
                "$lyricsTranslationEnabled);",
            null
        )
    }

    private fun startMediaMonitor() {
        if (monitorStarted) {
            refreshActiveSessions()
            return
        }
        startAudioRouteMonitor()
        startAudioPlaybackMonitor()
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
        mainHandler.removeCallbacks(sessionSelectionRefreshRunnable)
        mainHandler.removeCallbacks(sessionConvergenceRefreshRunnable)
        sessionSelectionRefreshScheduled = false
        mainHandler.removeCallbacks(sessionRetryRunnable)
        val activeSessionsListenerRegistered = monitorStarted
        monitorStarted = false
        bluetoothBrowserBridge.disconnect()
        bluetoothBrowserSession = null
        bluetoothBrowserProfiles.clear()
        systemBluetoothProfile = BluetoothMediaBrowserProfile.ANDROID_9_A2DP
        stopAudioRouteMonitor()
        stopAudioPlaybackMonitor()
        stopAvrcpEventMonitor()
        if (activeSessionsListenerRegistered) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener)
            } catch (_: Exception) {
            }
        }
        observedControllerCallbacks.values.forEach { (controller, callback) ->
            runCatching { controller.unregisterCallback(callback) }
        }
        observedControllerCallbacks.clear()
        currentController = null
        hasMediaControllerSelection = false
        resetBluetoothTimeline()
        standardTimelineTracker.reset()
        lastSessionDiagnostics = ""
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

    private fun startAudioPlaybackMonitor() {
        if (audioPlaybackMonitorStarted) return
        try {
            audioManager.registerAudioPlaybackCallback(audioPlaybackCallback, mainHandler)
            audioPlaybackMonitorStarted = true
            Log.i(LOG_TAG, "Audio playback change monitor registered")
        } catch (error: Exception) {
            Log.w(LOG_TAG, "Audio playback change monitor unavailable", error)
        }
    }

    private fun stopAudioPlaybackMonitor() {
        if (!audioPlaybackMonitorStarted) return
        try {
            audioManager.unregisterAudioPlaybackCallback(audioPlaybackCallback)
        } catch (_: Exception) {
        }
        audioPlaybackMonitorStarted = false
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

    private fun refreshMediaSelectionForCommand() {
        if (monitorStarted) refreshActiveSessions()
    }

    private fun refreshActiveSessions(
        systemControllersOverride: List<MediaController>? = null
    ) {
        try {
            val systemControllers = systemControllersOverride
                ?: sessionManager.getActiveSessions(listenerComponent).orEmpty()
            val systemPackages = systemControllers.map { it.packageName }
            systemBluetoothProfile = if (systemPackages.contains(BLUETOOTH_PACKAGE)) {
                bluetoothBrowserBridge.resolveProfile()
                    ?: BluetoothMediaBrowserProfile.ANDROID_9_A2DP
            } else {
                BluetoothMediaBrowserProfile.ANDROID_9_A2DP
            }
            bluetoothBrowserBridge.refresh(
                bluetoothRoutePresent = hasBluetoothBrowserRoute(),
                systemControllerPackages = systemPackages
            )
            val controllers = mergeMediaControllers(
                systemControllers,
                bluetoothBrowserSession?.controller
            )
            updateBluetoothBrowserProfiles(bluetoothBrowserSession)
            selectController(controllers)
            logBluetoothBridgeDiagnostics(systemControllers, controllers)
        } catch (error: SecurityException) {
            bluetoothBrowserBridge.disconnect()
            bluetoothBrowserSession = null
            bluetoothBrowserProfiles.clear()
            systemBluetoothProfile = BluetoothMediaBrowserProfile.ANDROID_9_A2DP
            pendingSnapshot = JSONObject()
                .put("hasSession", false)
                .put("permissionRequired", true)
            pendingSnapshot?.let { deliverToWeb(it) }
        }
    }

    private fun mergeMediaControllers(
        systemControllers: List<MediaController>,
        browserController: MediaController?
    ): List<MediaController> {
        val seen = LinkedHashSet<MediaSession.Token>()
        val merged = ArrayList<MediaController>(
            systemControllers.size + if (browserController == null) 0 else 1
        )
        systemControllers.forEach { controller ->
            if (seen.add(controller.sessionToken)) merged += controller
        }
        if (browserController != null && seen.add(browserController.sessionToken)) {
            merged += browserController
        }
        return merged
    }

    private fun updateBluetoothBrowserProfiles(session: BluetoothMediaBrowserSession?) {
        bluetoothBrowserProfiles.clear()
        session?.let {
            bluetoothBrowserProfiles[it.controller.sessionToken] = it.profile
        }
    }

    private fun logBluetoothBridgeDiagnostics(
        systemControllers: List<MediaController>,
        mergedControllers: List<MediaController>
    ) {
        val selected = currentController
        val metadata = selected?.let(::normalizedRecordingMetadata)
        val durationUsable = metadata?.durationMs
            ?.takeIf { it >= MediaRecordingStateTracker.MINIMUM_QUERY_DURATION_MS } != null
        val bridgeState = bluetoothBrowserBridge.currentState
        val rejectionReason = when {
            selected == null && bridgeState == BluetoothMediaBrowserBridgeState.UNAVAILABLE ->
                "browser_unavailable"
            selected == null -> "no_selection"
            metadata == null -> "metadata_missing"
            !metadata.hasTrack -> "track_missing"
            !durationUsable -> "duration_unusable"
            else -> "none"
        }
        Log.i(
            LOG_TAG,
            "MediaSession bridge systemControllerCount=${systemControllers.size} " +
                "browserBridgeState=${bridgeState.name.lowercase()} " +
                "mergedControllerCount=${mergedControllers.size} " +
                "selectedPackage=${selected?.packageName ?: "none"} " +
                "durationUnit=${selected?.let(::durationUnitForController)?.name?.lowercase() ?: "none"} " +
                "trackPresent=${metadata?.hasTrack == true} " +
                "durationUsable=$durationUsable " +
                "rejectionReason=$rejectionReason"
        )
    }

    private fun hasBluetoothBrowserRoute(): Boolean = runCatching {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { device -> isBluetoothBrowserRouteType(device.type) }
    }.getOrDefault(false)

    private fun isBluetoothBrowserRouteType(type: Int): Boolean =
        BluetoothMediaBrowserBridgePolicy.isBluetoothOutputType(type, Build.VERSION.SDK_INT)

    private fun selectController(controllers: List<MediaController>) {
        updateControllerCallbacks(controllers)
        val currentIndex = currentController?.let { current ->
            controllers.indexOfFirst { it.sessionToken == current.sessionToken }
                .takeIf { it >= 0 }
        }
        val candidates = controllers.mapIndexed { index, controller ->
            val playback = controller.playbackState
            val playbackInfo = runCatching { controller.playbackInfo }.getOrNull()
            MediaSessionCandidate(
                index = index,
                packageName = controller.packageName,
                playbackState = playback?.state,
                audioUsage = playbackInfo?.audioAttributes?.usage,
                audioContentType = playbackInfo?.audioAttributes?.contentType,
                playbackActions = playback?.actions ?: 0L,
                hasTitle = normalizedRecordingMetadata(controller)?.hasTrack == true
            )
        }
        logSessionCandidates(candidates)
        val selectedIndex = MediaSessionSelectionPolicy.select(
            candidates = candidates,
            currentIndex = currentIndex,
            hasCurrentSelection = hasMediaControllerSelection,
            ownPackageName = packageName
        )
        val best = selectedIndex?.let(controllers::get)

        if (best?.sessionToken == currentController?.sessionToken) {
            updateCurrentRecordingState(best)
            scheduleSnapshot()
            return
        }

        Log.i(
            LOG_TAG,
            "MediaSession selected package=${best?.packageName ?: "none"} " +
                "index=${selectedIndex ?: -1} state=${best?.playbackState?.state ?: -1} " +
                "candidateCount=${controllers.size}"
        )
        currentController = best
        if (best != null) hasMediaControllerSelection = true
        resetBluetoothTimeline()
        standardTimelineTracker.reset()
        cachedArtworkKey = ""
        cachedArtworkDataUrl = ""
        updateCurrentRecordingState(best)
        scheduleSessionConvergenceRefreshes()
        scheduleSnapshot()
    }

    private fun scheduleSessionConvergenceRefreshes() {
        mainHandler.removeCallbacks(sessionConvergenceRefreshRunnable)
        mainHandler.postDelayed(
            sessionConvergenceRefreshRunnable,
            SESSION_CONVERGENCE_FIRST_REFRESH_MS
        )
        mainHandler.postDelayed(
            sessionConvergenceRefreshRunnable,
            SESSION_CONVERGENCE_FINAL_REFRESH_MS
        )
    }

    private fun updateCurrentRecordingState(controller: MediaController?) {
        val previous = currentRecordingState
        val next = if (controller == null) {
            recordingStateTracker.clear()
            null
        } else {
            recordingStateTracker.update(
                sourceIdentity = controller.sessionToken,
                incoming = normalizedRecordingMetadata(controller)
            )
        }
        currentRecordingState = next
        val recordingChanged = next?.recordingChanged == true ||
            (previous != null && next == null)
        if (recordingChanged) {
            resetBluetoothTimeline()
            standardTimelineTracker.reset()
        }
        if (recordingChanged || next?.queryChanged == true) {
            invalidateCurrentLyricsRequest()
            Log.i(
                LOG_TAG,
                "Media recording advanced generation=${next?.recordingGeneration ?: 0L} " +
                    "queryRevision=${next?.queryRevision ?: 0L} " +
                    "recordingChanged=$recordingChanged"
            )
        }
    }

    private fun invalidateCurrentLyricsRequest() {
        val activeRequest = synchronized(lyricsUsageLock) {
            latestLyricsRequestId = 0
            latestLyricsRecordingGeneration = 0L
            latestLyricsQueryRevision = 0L
            activeLyricsRequestJob.also { activeLyricsRequestJob = null }
        }
        activeRequest?.cancel()
        lyricsResolutionCoordinator?.cancelCurrent()
        updateTranslationAvailability(false)
    }

    private fun logSessionCandidates(candidates: List<MediaSessionCandidate>) {
        val signature = candidates.joinToString(separator = ";") { candidate ->
            "${candidate.index}:${candidate.packageName}" +
                ":state=${candidate.playbackState ?: -1}" +
                ":usage=${candidate.audioUsage ?: -1}" +
                ":content=${candidate.audioContentType ?: -1}" +
                ":actions=${candidate.playbackActions}" +
                ":title=${candidate.hasTitle}"
        }.ifBlank { "none" }
        if (signature == lastSessionDiagnostics) return
        lastSessionDiagnostics = signature
        Log.i(
            LOG_TAG,
            "MediaSession candidates count=${candidates.size} matrix=$signature"
        )
    }

    private fun updateControllerCallbacks(controllers: List<MediaController>) {
        val activeTokens = controllers.mapTo(hashSetOf()) { it.sessionToken }
        val removedTokens = observedControllerCallbacks.keys.filterNot(activeTokens::contains)
        removedTokens.forEach { token ->
            observedControllerCallbacks.remove(token)?.let { (controller, callback) ->
                runCatching { controller.unregisterCallback(callback) }
            }
        }

        controllers.forEach { controller ->
            val token = controller.sessionToken
            if (observedControllerCallbacks.containsKey(token)) return@forEach
            val callback = createControllerCallback(token)
            runCatching {
                controller.registerCallback(callback, mainHandler)
                observedControllerCallbacks[token] = controller to callback
            }
        }
    }

    private fun scheduleSessionSelectionRefresh() {
        if (sessionSelectionRefreshScheduled) return
        sessionSelectionRefreshScheduled = true
        mainHandler.postDelayed(sessionSelectionRefreshRunnable, 80L)
    }

    private fun createControllerCallback(token: MediaSession.Token): MediaController.Callback =
        object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                onObservedControllerChanged(token)
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                onObservedControllerChanged(token)
            }

            override fun onAudioInfoChanged(info: MediaController.PlaybackInfo?) {
                onObservedControllerChanged(token)
            }

            override fun onSessionDestroyed() {
                if (bluetoothBrowserSession?.controller?.sessionToken == token) {
                    bluetoothBrowserBridge.onSessionDestroyed()
                }
                if (monitorStarted) refreshActiveSessions()
            }
        }

    private fun onObservedControllerChanged(token: MediaSession.Token) {
        if (!monitorStarted) return
        scheduleSessionSelectionRefresh()
        if (currentController?.sessionToken == token) scheduleSnapshot()
    }

    private fun scheduleSnapshot() {
        if (snapshotScheduled) return
        snapshotScheduled = true
        mainHandler.postDelayed(dispatchRunnable, 35)
    }

    private fun dispatchSnapshot() {
        val controller = currentController
        updateCurrentRecordingState(controller)
        val snapshot = if (controller == null) {
            JSONObject().put("hasSession", false).put("permissionRequired", false)
        } else {
            buildSnapshot(controller)
        }
        pendingSnapshot = snapshot
        deliverToWeb(snapshot)
        refreshSettingsPlaybackIdentity()
    }

    private fun buildSnapshot(controller: MediaController): JSONObject {
        val recordingState = currentRecordingState
        val metadata = recordingState?.metadata
        val playback = controller.playbackState
        val title = metadata?.track.orEmpty()
        val artist = metadata?.artist.orEmpty()
        val album = metadata?.album.orEmpty()
        val duration = metadata?.durationMs ?: 0L
        val recordingGeneration = recordingState?.recordingGeneration ?: 0L
        val queryRevision = recordingState?.queryRevision ?: 0L
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
        val timeline = if (controller.packageName == BLUETOOTH_PACKAGE) {
            bluetoothTimeline(
                "recording:$recordingGeneration",
                state,
                playback?.position ?: 0L,
                duration
            )
        } else {
            val standardTimeline = standardTimelineTracker.update(
                trackKey = "recording:$recordingGeneration",
                playbackState = playback?.state,
                reportedPositionMs = playback?.position ?: 0L,
                playbackSpeed = playback?.playbackSpeed ?: 0f,
                publisherPositionTime = playback?.lastPositionUpdateTime ?: 0L,
                durationMs = duration
            )
            PlaybackTimeline(
                positionMs = standardTimeline.positionMs,
                speed = standardTimeline.speed,
                timelineReady = standardTimeline.timelineReady
            )
        }
        return JSONObject()
            .put("hasSession", title.isNotBlank() || playback != null)
            .put("permissionRequired", false)
            .put("track", title)
            .put("artist", artist)
            .put("album", album)
            .put("recordingGeneration", recordingGeneration)
            .put("queryRevision", queryRevision)
            .put("packageName", controller.packageName)
            .put("state", state)
            .put("positionMs", timeline.positionMs)
            .put("durationMs", max(0L, duration))
            .put("speed", if (timeline.speed.isFinite()) timeline.speed else 1.0)
            .put("timelineReady", timeline.timelineReady)
            .put("capturedAtMs", System.currentTimeMillis())
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

    private fun normalizedRecordingMetadata(controller: MediaController?): MediaRecordingMetadata? {
        val metadata = controller?.metadata ?: return null
        val description = runCatching { metadata.description }.getOrNull()
        return MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                descriptionTitle = description?.title?.toString().orEmpty(),
                descriptionSubtitle = description?.subtitle?.toString().orEmpty(),
                descriptionDescription = description?.description?.toString().orEmpty(),
                displayTitle = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty(),
                displaySubtitle = metadata.getString(
                    MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE
                ).orEmpty(),
                displayDescription = metadata.getString(
                    MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION
                ).orEmpty(),
                title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                albumArtist = metadata.getString(
                    MediaMetadata.METADATA_KEY_ALBUM_ARTIST
                ).orEmpty(),
                author = metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR).orEmpty(),
                album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
                durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
                transport = if (controller.packageName == BLUETOOTH_PACKAGE) {
                    MediaSessionTransport.BLUETOOTH_AVRCP
                } else {
                    MediaSessionTransport.STANDARD
                },
                durationUnit = durationUnitForController(controller),
                reportedPositionMs = controller.playbackState?.position ?: -1L
            )
        ).takeIf(MediaRecordingMetadata::hasTrack)
    }

    private fun durationUnitForController(controller: MediaController): MediaSessionDurationUnit =
        if (controller.packageName != BLUETOOTH_PACKAGE) {
            MediaSessionDurationUnit.MILLISECONDS
        } else {
            bluetoothBrowserProfiles[controller.sessionToken]?.durationUnit
                ?: systemBluetoothProfile.durationUnit
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
        if (!commercialRuntimeAccess.hasCurrentAccess()) return
        pendingSnapshot = snapshot
        if (!webReady) return
        val generation = runtimeGeneration.get()
        val targetWebView = webView ?: return
        targetWebView.post {
            if (!commercialRuntimeAccess.hasCurrentAccess() || generation != runtimeGeneration.get() ||
                targetWebView !== webView || !webReady
            ) {
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
        const val ACTION_START = "com.ninepointnine.desktoplyrics.action.START_LYRICS_OVERLAY"
        const val ACTION_STOP = "com.ninepointnine.desktoplyrics.action.STOP_LYRICS_OVERLAY"
        const val ACTION_RESTART = "com.ninepointnine.desktoplyrics.action.RESTART_LYRICS_OVERLAY"
        const val ACTION_COMMERCIAL_ACCESS_CHANGED =
            "com.ninepointnine.desktoplyrics.action.COMMERCIAL_ACCESS_CHANGED"
        const val ACTION_COMMERCIAL_ACCESS_REVOKED =
            "com.ninepointnine.desktoplyrics.action.COMMERCIAL_ACCESS_REVOKED"
        const val ACTION_STATE_CHANGED = "com.ninepointnine.desktoplyrics.action.LYRICS_OVERLAY_STATE_CHANGED"
        const val ACTION_SET_BACKGROUND = "com.ninepointnine.desktoplyrics.action.SET_LYRICS_BACKGROUND"
        const val ACTION_SET_FONT_SCALE = "com.ninepointnine.desktoplyrics.action.SET_LYRICS_FONT_SCALE"
        const val ACTION_SET_TOPBAR_FONT_SCALE =
            "com.ninepointnine.desktoplyrics.action.SET_TOPBAR_FONT_SCALE"
        const val ACTION_SET_TOPBAR_SINGLE_LINE_FONT_SIZE =
            "com.ninepointnine.desktoplyrics.action.SET_TOPBAR_SINGLE_LINE_FONT_SIZE"
        const val ACTION_SET_TOPBAR_FIRST_LINE_FONT_SIZE =
            "com.ninepointnine.desktoplyrics.action.SET_TOPBAR_FIRST_LINE_FONT_SIZE"
        const val ACTION_SET_TOPBAR_SECOND_LINE_FONT_SIZE =
            "com.ninepointnine.desktoplyrics.action.SET_TOPBAR_SECOND_LINE_FONT_SIZE"
        const val ACTION_SET_WALLPAPER_FONT_SCALE =
            "com.ninepointnine.desktoplyrics.action.SET_WALLPAPER_FONT_SCALE"
        const val ACTION_SET_WALLPAPER_BLUR =
            "com.ninepointnine.desktoplyrics.action.SET_WALLPAPER_BLUR"
        const val ACTION_SET_WALLPAPER_SHADOW =
            "com.ninepointnine.desktoplyrics.action.SET_WALLPAPER_SHADOW"
        const val ACTION_SET_WALLPAPER_SPACING =
            "com.ninepointnine.desktoplyrics.action.SET_WALLPAPER_SPACING"
        const val ACTION_SET_WALLPAPER_FOCUS =
            "com.ninepointnine.desktoplyrics.action.SET_WALLPAPER_FOCUS"
        const val ACTION_SET_WALLPAPER_POSITION =
            "com.ninepointnine.desktoplyrics.action.SET_WALLPAPER_POSITION"
        const val ACTION_SET_TOPBAR_LINES = "com.ninepointnine.desktoplyrics.action.SET_TOPBAR_LINES"
        const val ACTION_SET_LYRICS_TRANSLATION =
            "com.ninepointnine.desktoplyrics.action.SET_LYRICS_TRANSLATION"
        const val ACTION_SET_WALLPAPER_LYRICS =
            "com.ninepointnine.desktoplyrics.action.SET_WALLPAPER_LYRICS"
        const val ACTION_SETTINGS_OPENED = "com.ninepointnine.desktoplyrics.action.SETTINGS_OPENED"
        const val ACTION_SETTINGS_CLOSED = "com.ninepointnine.desktoplyrics.action.SETTINGS_CLOSED"
        const val ACTION_REQUEST_SETTINGS_STATE =
            "com.ninepointnine.desktoplyrics.action.REQUEST_SETTINGS_STATE"
        const val ACTION_SETTINGS_STATE_CHANGED =
            "com.ninepointnine.desktoplyrics.action.SETTINGS_STATE_CHANGED"
        const val ACTION_SEARCH_MANUAL_LYRICS =
            "com.ninepointnine.desktoplyrics.action.SEARCH_MANUAL_LYRICS"
        const val ACTION_SELECT_MANUAL_LYRICS =
            "com.ninepointnine.desktoplyrics.action.SELECT_MANUAL_LYRICS"
        const val ACTION_RESTORE_AUTOMATIC_LYRICS =
            "com.ninepointnine.desktoplyrics.action.RESTORE_AUTOMATIC_LYRICS"
        const val ACTION_CLEAR_CURRENT_LYRICS_CACHE =
            "com.ninepointnine.desktoplyrics.action.CLEAR_CURRENT_LYRICS_CACHE"
        const val EXTRA_START_SOURCE = "start_source"
        const val EXTRA_BACKGROUND_MODE = "background_mode"
        const val EXTRA_FONT_SCALE_PERCENT = "font_scale_percent"
        const val EXTRA_TOPBAR_LINES = "topbar_lines"
        const val EXTRA_TOPBAR_SINGLE_LINE_FONT_SIZE_PX = "topbar_single_line_font_size_px"
        const val EXTRA_TOPBAR_FIRST_LINE_FONT_SIZE_PX = "topbar_first_line_font_size_px"
        const val EXTRA_TOPBAR_SECOND_LINE_FONT_SIZE_PX = "topbar_second_line_font_size_px"
        const val EXTRA_LYRICS_TRANSLATION_ENABLED = "lyrics_translation_enabled"
        const val EXTRA_WALLPAPER_LYRICS_ENABLED = "wallpaper_lyrics_enabled"
        const val EXTRA_WALLPAPER_BLUR_ENABLED = "wallpaper_blur_enabled"
        const val EXTRA_WALLPAPER_SHADOW_ENABLED = "wallpaper_shadow_enabled"
        const val EXTRA_WALLPAPER_SPACING = "wallpaper_spacing"
        const val EXTRA_WALLPAPER_FOCUS = "wallpaper_focus"
        const val EXTRA_WALLPAPER_POSITION = "wallpaper_position"
        const val EXTRA_MANUAL_TRACK = "manual_track"
        const val EXTRA_MANUAL_ARTIST = "manual_artist"
        const val EXTRA_MANUAL_ALBUM = "manual_album"
        const val EXTRA_MANUAL_CANDIDATE_TOKEN = "manual_candidate_token"
        const val EXTRA_SETTINGS_STATE = "settings_state"
        const val EXTRA_RUNNING = "running"
        const val PREFS_NAME = "lyrics_overlay_prefs"
        const val PREF_BACKGROUND_MODE = "background_mode"
        const val PREF_FONT_SCALE_PERCENT = "font_scale_percent"
        const val PREF_TOPBAR_FONT_SCALE_PERCENT = "topbar_font_scale_percent_v1"
        const val PREF_TOPBAR_SINGLE_LINE_FONT_SIZE_PX = "topbar_single_line_font_size_px_v2"
        const val PREF_TOPBAR_FIRST_LINE_FONT_SIZE_PX = "topbar_first_line_font_size_px_v2"
        const val PREF_TOPBAR_SECOND_LINE_FONT_SIZE_PX = "topbar_second_line_font_size_px_v2"
        const val PREF_WALLPAPER_FONT_SCALE_PERCENT = "wallpaper_font_scale_percent_v1"
        const val PREF_AUTO_START = "auto_start"
        const val PREF_TOPBAR_LINES = "topbar_lines_v1"
        const val PREF_LYRICS_TRANSLATION_ENABLED = "lyrics_translation_enabled_v1"
        const val PREF_WALLPAPER_LYRICS_ENABLED = "wallpaper_lyrics_enabled_v1"
        const val PREF_WALLPAPER_BLUR_ENABLED = "wallpaper_blur_enabled_v1"
        const val PREF_WALLPAPER_SHADOW_ENABLED = "wallpaper_shadow_enabled_v1"
        const val PREF_WALLPAPER_SPACING = "wallpaper_spacing_v1"
        const val PREF_WALLPAPER_FOCUS = "wallpaper_focus_v1"
        const val PREF_WALLPAPER_POSITION = "wallpaper_position_v1"
        const val BACKGROUND_TRANSPARENT = "transparent"
        const val BACKGROUND_LOW = "low"
        const val BACKGROUND_HIGH = "high"
        const val BACKGROUND_DEFAULT = BACKGROUND_TRANSPARENT
        const val FONT_SCALE_MIN_PERCENT = 75
        const val FONT_SCALE_MAX_PERCENT = 150
        const val FONT_SCALE_DEFAULT_PERCENT = 100
        const val AUTO_START_DEFAULT = true
        const val WALLPAPER_LYRICS_DEFAULT = true
        const val WALLPAPER_BLUR_DEFAULT = true
        const val WALLPAPER_SHADOW_DEFAULT = true
        const val LYRICS_TRANSLATION_DEFAULT = true
        const val START_SOURCE_BOOT_COMPLETED = "boot_completed"
        const val START_SOURCE_PACKAGE_REPLACED = "package_replaced"
        private const val START_SOURCE_RUNTIME_ACCESS = "runtime_access"

        fun compactMinimumHeightDp(percent: Int): Int {
            val scale = percent.coerceIn(FONT_SCALE_MIN_PERCENT, FONT_SCALE_MAX_PERCENT) / 100f
            return (9.5f + 34.5f * scale).roundToInt().coerceIn(36, 64)
        }
        private const val LOG_TAG = "DesktopLyrics"
        private const val SESSION_CONVERGENCE_FIRST_REFRESH_MS = 250L
        private const val SESSION_CONVERGENCE_FINAL_REFRESH_MS = 1_000L
        private const val CHANNEL_ID = "lobsta_lyrics_overlay"
        private const val NOTIFICATION_ID = 4202
        private const val RECOVERY_NOTIFICATION_ID = 4203
        private const val RECOVERY_SETTINGS_REQUEST_CODE = 2
        private const val COMMERCIAL_RECOVERY_NOTIFICATION_ID = 4204
        private const val COMMERCIAL_RECOVERY_SETTINGS_REQUEST_CODE = 3
        private const val BLUETOOTH_POSITION_RESET_TOLERANCE_MS = 2_500L
        private const val TOPBAR_LINES_DEFAULT = 2
        private const val DESIGN_WIDTH = 1920
        private const val DESIGN_HEIGHT = 1080
        private const val DESKTOP_LEFT = 660
        private const val DESKTOP_TOP = 90
        private const val DESKTOP_RIGHT = 1890
        private const val DESKTOP_BOTTOM = 900
        private const val DESKTOP_EDGE_INSET = 30
        private const val DOCK_SAFE_GAP = 16
        private const val SURFACE_FADE_OUT_MS = 120L
        private const val SURFACE_FADE_IN_MS = 160L
        private const val SURFACE_HIDDEN_ALPHA = 0.01f
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

internal object LyricsTopbarHeightPolicy {
    private const val BASE_HEIGHT_DP = 72
    private const val MAX_HEIGHT_DP = 90
    private const val VERTICAL_ALLOWANCE_DP = 10f
    private const val CURRENT_LINE_HEIGHT = 1.05f
    private const val SECONDARY_LINE_HEIGHT = 1.12f

    fun requiredHeightDp(topbarLines: Int, fontScalePercent: Int): Int {
        val scale = fontScalePercent.coerceIn(
            LyricsOverlayService.FONT_SCALE_MIN_PERCENT,
            LyricsOverlayService.FONT_SCALE_MAX_PERCENT
        ) / 100f
        val secondaryRows = if (topbarLines == 1) 1 else 2
        val contentHeight = 10f + scale * (32f * 1.05f + secondaryRows * 20f * 1.12f)
        return min(MAX_HEIGHT_DP, max(BASE_HEIGHT_DP, ceil(contentHeight).toInt()))
    }

    fun requiredHeightDp(
        topbarLines: Int,
        primaryFontSizePx: Int,
        secondaryFontSizePx: Int,
        translationAvailable: Boolean
    ): Int {
        val primary = LyricsTopbarFontSizePolicy.normalizePrimary(primaryFontSizePx).toFloat()
        val secondary = LyricsTopbarFontSizePolicy.normalizeSecondary(secondaryFontSizePx).toFloat()
        val secondaryRows = when {
            topbarLines == 1 && translationAvailable -> 1
            topbarLines == 2 && translationAvailable -> 2
            topbarLines == 2 -> 1
            else -> 0
        }
        val contentHeight = VERTICAL_ALLOWANCE_DP + (
            primary * CURRENT_LINE_HEIGHT +
                secondaryRows * secondary * SECONDARY_LINE_HEIGHT
            )
        return min(MAX_HEIGHT_DP, max(BASE_HEIGHT_DP, ceil(contentHeight).toInt()))
    }
}

internal enum class LyricsStartupOutcome(
    val logValue: String,
    val clearsAutoStart: Boolean
) {
    RUNNING("running", false),
    RECOVERY("recovery", false),
    COMMERCIAL_RECOVERY("commercial_recovery", false),
    USER_STOPPED("stopped", true)
}

internal object LyricsStartupPolicy {
    fun decide(
        action: String?,
        overlayAccess: Boolean,
        notificationAccess: Boolean
    ): LyricsStartupOutcome = when {
        action == LyricsOverlayService.ACTION_STOP -> LyricsStartupOutcome.USER_STOPPED
        action == LyricsOverlayService.ACTION_COMMERCIAL_ACCESS_REVOKED -> {
            LyricsStartupOutcome.COMMERCIAL_RECOVERY
        }
        hasRequiredAccess(overlayAccess, notificationAccess) -> LyricsStartupOutcome.RUNNING
        else -> LyricsStartupOutcome.RECOVERY
    }

    fun hasRequiredAccess(overlayAccess: Boolean, notificationAccess: Boolean): Boolean =
        overlayAccess && notificationAccess
}

internal object LyricsCommercialGatePolicy {
    fun decide(
        systemOutcome: LyricsStartupOutcome,
        access: CommercialAccessDecision
    ): LyricsStartupOutcome = when {
        systemOutcome != LyricsStartupOutcome.RUNNING -> systemOutcome
        access is CommercialAccessDecision.Allowed -> LyricsStartupOutcome.RUNNING
        else -> LyricsStartupOutcome.COMMERCIAL_RECOVERY
    }
}

internal object LyricsCommercialStartupCheckPolicy {
    fun shouldCheck(action: String?, alreadyStarted: Boolean): Boolean =
        action != LyricsOverlayService.ACTION_STOP &&
            action != LyricsOverlayService.ACTION_COMMERCIAL_ACCESS_REVOKED &&
            action != LyricsOverlayService.ACTION_COMMERCIAL_ACCESS_CHANGED &&
            (
                !alreadyStarted ||
                    action == LyricsOverlayService.ACTION_RESTART ||
                    action == LyricsOverlayService.ACTION_SETTINGS_OPENED
                )

    fun reconcile(
        result: CommercialAccessRefreshResult,
        localAccess: CommercialAccessDecision
    ): CommercialAccessDecision = when {
        result is CommercialAccessRefreshResult.Failure -> when (result.reason) {
            CommercialFailure.ENTITLEMENT_REVOKED -> {
                CommercialAccessDecision.Denied(CommercialAccessDenial.ENTITLEMENT_REVOKED)
            }
            CommercialFailure.DEVICE_MISMATCH -> {
                CommercialAccessDecision.Denied(CommercialAccessDenial.DEVICE_MISMATCH)
            }
            CommercialFailure.INVALID_LICENSE -> {
                CommercialAccessDecision.Denied(CommercialAccessDenial.INVALID_LICENSE)
            }
            CommercialFailure.CLOCK_ROLLBACK -> {
                CommercialAccessDecision.Denied(CommercialAccessDenial.CLOCK_ROLLBACK)
            }
            CommercialFailure.STORAGE -> {
                CommercialAccessDecision.Denied(CommercialAccessDenial.STORAGE_FAILURE)
            }
            CommercialFailure.CONFIGURATION_MISSING -> {
                CommercialAccessDecision.Denied(CommercialAccessDenial.CONFIGURATION_MISSING)
            }
            else -> localAccess
        }
        result is CommercialAccessRefreshResult.Ready &&
            result.entitlement is EntitlementState.Expired -> {
            CommercialAccessDecision.Denied(CommercialAccessDenial.LICENSE_EXPIRED)
        }
        else -> localAccess
    }

    fun shouldDeferDeniedAccess(
        waitingForCheck: Boolean,
        runtimeActive: Boolean
    ): Boolean = waitingForCheck && !runtimeActive
}
