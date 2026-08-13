package com.tcrrry.desktoplyrics

import android.content.ContentResolver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.Handler
import android.os.storage.StorageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

internal enum class LyricsSurfaceMode {
    TOPBAR,
    DESKTOP
}

internal enum class IcarIconVisibility {
    HIDDEN,
    VISIBLE,
    UNKNOWN
}

internal enum class IcarStandardWindowOccupancy {
    CLOSED,
    OPEN,
    UNKNOWN
}

/**
 * The known small dynamic items between the fixed map/wallpaper and lock
 * buttons and the clock. Unknown states intentionally consume no width: the
 * layout follows confirmed visible icons only.
 */
internal enum class IcarTopbarIconSlot {
    PEDESTRIAN_REMINDER,
    WIRELESS_CHARGING,
    SD_CARD_DVR,
    USB_STORAGE,
    GUARDIAN_MODE
}

internal data class IcarTopbarGeometry(
    val leftPx: Int,
    val rightPx: Int
) {
    companion object {
        const val MIN_USABLE_WIDTH_PX = 180
    }

    val widthPx: Int
        get() = (rightPx - leftPx).coerceAtLeast(0)

    val canShowLyrics: Boolean
        get() = widthPx >= MIN_USABLE_WIDTH_PX
}

/** Pure 1920px design-space geometry shared by the service and unit tests. */
internal object IcarTopbarLayout {
    const val DESIGN_WIDTH_PX = 1920
    // The two fixed launcher controls end at x=288 in the 1920px design space.
    const val DYNAMIC_ICON_REGION_LEFT_PX = 288
    const val ICON_SIZE_PX = 42
    const val ICON_SAFE_GAP_PX = 33
    const val ICON_SLOT_WIDTH_PX = ICON_SIZE_PX + ICON_SAFE_GAP_PX
    const val LYRICS_LEFT_WITHOUT_DYNAMIC_ICONS_PX =
        DYNAMIC_ICON_REGION_LEFT_PX + ICON_SAFE_GAP_PX
    const val LYRICS_RIGHT_PX = 881

    fun geometry(state: IcarDisplayState): IcarTopbarGeometry = IcarTopbarGeometry(
        leftPx = LYRICS_LEFT_WITHOUT_DYNAMIC_ICONS_PX + state.occupiedTopbarWidthPx(),
        rightPx = LYRICS_RIGHT_PX
    )
}

internal data class IcarDisplayState(
    val launcherState: Int,
    val iconVisibility: Map<IcarTopbarIconSlot, IcarIconVisibility> = emptyMap(),
    val windowMode: Int = IcarDisplayStateMonitor.WINDOW_MODE_CLOSED
) {
    val standardWindowOccupancy: IcarStandardWindowOccupancy
        get() = when (windowMode) {
            IcarDisplayStateMonitor.WINDOW_MODE_CLOSED -> IcarStandardWindowOccupancy.CLOSED
            IcarDisplayStateMonitor.WINDOW_MODE_STANDARD_WINDOW -> IcarStandardWindowOccupancy.OPEN
            else -> IcarStandardWindowOccupancy.UNKNOWN
        }

    val surfaceMode: LyricsSurfaceMode
        get() = if (launcherState == IcarDisplayStateMonitor.LAUNCHER_STATE_WALLPAPER) {
            LyricsSurfaceMode.DESKTOP
        } else {
            LyricsSurfaceMode.TOPBAR
        }

    fun visibilityOf(slot: IcarTopbarIconSlot): IcarIconVisibility =
        iconVisibility[slot] ?: IcarIconVisibility.UNKNOWN

    fun isSlotOccupied(slot: IcarTopbarIconSlot): Boolean =
        visibilityOf(slot) == IcarIconVisibility.VISIBLE

    fun occupiedTopbarWidthPx(): Int = IcarTopbarIconSlot.entries.count(::isSlotOccupied) *
        IcarTopbarLayout.ICON_SLOT_WIDTH_PX

    fun topbarGeometry(): IcarTopbarGeometry = IcarTopbarLayout.geometry(this)
}

/**
 * Applies user-facing display choices after the launcher state has been read.
 * The monitor itself stays a faithful view of the car's state; temporary UI
 * state and user preferences never alter that source state.
 */
internal object IcarLyricsSurfacePolicy {
    fun effectiveSurfaceMode(
        displayState: IcarDisplayState?,
        wallpaperLyricsEnabled: Boolean,
        localSettingsOpen: Boolean
    ): LyricsSurfaceMode = when {
        localSettingsOpen -> LyricsSurfaceMode.TOPBAR
        displayState == null -> LyricsSurfaceMode.TOPBAR
        displayState.standardWindowOccupancy != IcarStandardWindowOccupancy.CLOSED -> {
            LyricsSurfaceMode.TOPBAR
        }
        !wallpaperLyricsEnabled -> LyricsSurfaceMode.TOPBAR
        else -> displayState.surfaceMode
    }

    fun hasRenderableGeometry(width: Int, height: Int): Boolean = width > 0 && height > 0
}

/**
 * Passive iCAR state reader. It observes only verified, readable Settings
 * values plus public storage state. It never writes vehicle state or connects
 * to private car, CAN, Bluetooth-phone, or accessibility interfaces.
 */
internal class IcarDisplayStateMonitor(
    context: Context,
    private val handler: Handler,
    private val onStateChanged: (IcarDisplayState) -> Unit
) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val storageManager =
        appContext.getSystemService(Context.STORAGE_SERVICE) as? StorageManager

    private var started = false
    private var storageReceiverRegistered = false
    private var lastState: IcarDisplayState? = null

    private val observer = object : android.database.ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = refresh()
    }
    private val storageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    fun start() {
        if (started) return
        started = true
        OBSERVED_GLOBAL_KEYS.forEach(::observeGlobal)
        observeSecure(KEY_WINDOW_MODE)
        registerStorageReceiver(handler)
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { resolver.unregisterContentObserver(observer) }
        if (storageReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(storageReceiver) }
            storageReceiverRegistered = false
        }
        lastState = null
    }

    fun refresh() {
        if (!started) return
        val state = IcarDisplayState(
            launcherState = readGlobal(KEY_LAUNCHER_STATE) ?: STATE_UNKNOWN,
            iconVisibility = mapOf(
                IcarTopbarIconSlot.PEDESTRIAN_REMINDER to pedestrianReminderVisibility(),
                IcarTopbarIconSlot.WIRELESS_CHARGING to wirelessChargingVisibility(),
                IcarTopbarIconSlot.SD_CARD_DVR to sdCardDvrVisibility(),
                IcarTopbarIconSlot.USB_STORAGE to usbStorageVisibility(),
                IcarTopbarIconSlot.GUARDIAN_MODE to guardianModeVisibility()
            ),
            windowMode = readSecure(KEY_WINDOW_MODE) ?: STATE_UNKNOWN
        )
        if (state == lastState) return
        lastState = state
        Log.i(
            LOG_TAG,
            "iCAR display launcher=${state.launcherState} window=${state.windowMode} " +
                "occupancy=${state.standardWindowOccupancy} surface=${state.surfaceMode} " +
                "topbarOccupied=${state.occupiedTopbarWidthPx()} slots=${state.iconVisibility}"
        )
        onStateChanged(state)
    }

    private fun observeGlobal(key: String) {
        runCatching {
            resolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer)
        }.onFailure { error ->
            Log.w(LOG_TAG, "Unable to observe iCAR setting $key", error)
        }
    }

    private fun observeSecure(key: String) {
        runCatching {
            resolver.registerContentObserver(Settings.Secure.getUriFor(key), false, observer)
        }.onFailure { error ->
            Log.w(LOG_TAG, "Unable to observe iCAR secure setting $key", error)
        }
    }

    private fun registerStorageReceiver(handler: Handler) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                storageReceiver,
                filter,
                null,
                handler,
                ContextCompat.RECEIVER_EXPORTED
            )
            storageReceiverRegistered = true
        }.onFailure { error ->
            Log.w(LOG_TAG, "Unable to observe USB storage changes", error)
        }
    }

    private fun pedestrianReminderVisibility(): IcarIconVisibility = when {
        readGlobalString(KEY_SAFE_PEDESTRIAN)?.equals("FALSE", ignoreCase = true) == true -> {
            IcarIconVisibility.VISIBLE
        }
        readGlobalString(KEY_SAFE_PEDESTRIAN)?.equals("TRUE", ignoreCase = true) == true -> {
            IcarIconVisibility.HIDDEN
        }
        else -> IcarIconVisibility.UNKNOWN
    }

    private fun wirelessChargingVisibility(): IcarIconVisibility = when (
        readGlobal(KEY_WIRELESS_CHARGING_STATE)
    ) {
        WIRELESS_CHARGING_ACTIVE,
        WIRELESS_CHARGING_FINISHED -> IcarIconVisibility.VISIBLE
        WIRELESS_CHARGING_INACTIVE -> IcarIconVisibility.HIDDEN
        null -> IcarIconVisibility.UNKNOWN
        else -> IcarIconVisibility.UNKNOWN
    }

    private fun sdCardDvrVisibility(): IcarIconVisibility {
        val mounted = readGlobal(KEY_SD_CARD_MOUNTED) ?: return IcarIconVisibility.UNKNOWN
        // DVR changes this shared slot's artwork; card presence controls occupancy.
        return if (mounted != 0) IcarIconVisibility.VISIBLE else IcarIconVisibility.HIDDEN
    }

    private fun usbStorageVisibility(): IcarIconVisibility {
        val volumes = runCatching { storageManager?.storageVolumes }.getOrNull()
            ?: return IcarIconVisibility.UNKNOWN
        var hasUnidentifiableMountedVolume = false
        volumes.forEach { volume ->
            if (volume.isPrimary || !volume.isRemovable || volume.state != Environment.MEDIA_MOUNTED) {
                return@forEach
            }
            val uuid = volume.uuid
            if (uuid.isNullOrBlank()) {
                hasUnidentifiableMountedVolume = true
            } else if (uuid.equals(USB_STORAGE_VOLUME_UUID, ignoreCase = true)) {
                return IcarIconVisibility.VISIBLE
            } else if (!uuid.equals(SD_CARD_VOLUME_UUID, ignoreCase = true)) {
                hasUnidentifiableMountedVolume = true
            }
        }
        return if (hasUnidentifiableMountedVolume) {
            IcarIconVisibility.UNKNOWN
        } else {
            IcarIconVisibility.HIDDEN
        }
    }

    private fun guardianModeVisibility(): IcarIconVisibility = when (
        readGlobal(KEY_GUARDIAN_MODE_ICON_STATE)
    ) {
        GUARDIAN_MODE_ACTIVE -> IcarIconVisibility.VISIBLE
        GUARDIAN_MODE_INACTIVE -> IcarIconVisibility.HIDDEN
        null -> IcarIconVisibility.UNKNOWN
        else -> IcarIconVisibility.UNKNOWN
    }

    private fun readGlobal(key: String): Int? = runCatching {
        Settings.Global.getInt(resolver, key)
    }.getOrNull()

    private fun readGlobalString(key: String): String? = runCatching {
        Settings.Global.getString(resolver, key)
    }.getOrNull()

    private fun readSecure(key: String): Int? = runCatching {
        Settings.Secure.getInt(resolver, key)
    }.getOrNull()

    companion object {
        const val STATE_UNKNOWN = -1
        const val LAUNCHER_STATE_WALLPAPER = 1
        const val LAUNCHER_STATE_MAP = 2
        const val LAUNCHER_STATE_CAR_SETTINGS = 3
        const val WINDOW_MODE_CLOSED = 0
        const val WINDOW_MODE_STANDARD_WINDOW = 2

        private const val WIRELESS_CHARGING_INACTIVE = 0
        private const val WIRELESS_CHARGING_ACTIVE = 1
        private const val WIRELESS_CHARGING_FINISHED = 2
        private const val GUARDIAN_MODE_ACTIVE = 2
        private const val GUARDIAN_MODE_INACTIVE = 1
        private const val SD_CARD_VOLUME_UUID = "udisk"
        private const val USB_STORAGE_VOLUME_UUID = "usbotg"

        private const val KEY_LAUNCHER_STATE =
            "com.mengbo.launcher3.SETTINGS_KEY_LAUNCHER_STATE"
        private const val KEY_WINDOW_MODE =
            "com.mengbo.launcher3.settings.secure.window_mode"
        private const val KEY_WIRELESS_CHARGING_STATE =
            "com.mengbo.provider.wireless_charging_state"
        private const val KEY_SD_CARD_MOUNTED = "com.mb.provider.usb_sd_mounted"
        private const val KEY_SAFE_PEDESTRIAN = "com.mengbo.provider.SAFE_PEDESTRIAN"
        // Verified on iCAR03: 2 shows the guardian icon; 1 removes it.
        private const val KEY_GUARDIAN_MODE_ICON_STATE =
            "com.chery.carsettings.action.SENTRY_MODEL"

        private val OBSERVED_GLOBAL_KEYS = arrayOf(
            KEY_LAUNCHER_STATE,
            KEY_SAFE_PEDESTRIAN,
            KEY_WIRELESS_CHARGING_STATE,
            KEY_SD_CARD_MOUNTED,
            KEY_GUARDIAN_MODE_ICON_STATE
        )
        private const val LOG_TAG = "DesktopLyrics"
    }
}
