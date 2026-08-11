package com.tcrrry.desktoplyrics

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val overlayPrefs by lazy {
        getSharedPreferences(LyricsOverlayService.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private lateinit var btnOverlay: Button
    private lateinit var btnOverlayPermission: Button
    private lateinit var btnListenerPermission: Button
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvRuntimeBadge: TextView
    private lateinit var backgroundModeTransparent: TextView
    private lateinit var backgroundModeLow: TextView
    private lateinit var backgroundModeHigh: TextView
    private lateinit var seekFontSize: SeekBar
    private lateinit var fontSizeValue: TextView
    private var overlayStateReceiverRegistered = false

    private val overlayStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LyricsOverlayService.ACTION_STATE_CHANGED &&
                ::tvOverlayStatus.isInitialized
            ) {
                updateOverlayUi()
            }
        }
    }

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startLyricsOverlay()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnOverlay = findViewById(R.id.btn_overlay)
        btnOverlayPermission = findViewById(R.id.btn_overlay_permission)
        btnListenerPermission = findViewById(R.id.btn_listener_permission)
        tvOverlayStatus = findViewById(R.id.tv_overlay_status)
        tvRuntimeBadge = findViewById(R.id.tv_runtime_badge)
        backgroundModeTransparent = findViewById(R.id.background_mode_transparent)
        backgroundModeLow = findViewById(R.id.background_mode_low)
        backgroundModeHigh = findViewById(R.id.background_mode_high)
        seekFontSize = findViewById(R.id.seek_font_size)
        fontSizeValue = findViewById(R.id.font_size_value)

        btnListenerPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        btnOverlayPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        btnOverlay.setOnClickListener {
            if (LyricsOverlayService.isRunning) {
                stopService(Intent(this, LyricsOverlayService::class.java).apply {
                    action = LyricsOverlayService.ACTION_STOP
                })
                btnOverlay.postDelayed({ updateOverlayUi() }, 250)
                return@setOnClickListener
            }

            if (!hasNotificationListenerAccess()) {
                Toast.makeText(this, "请先授予通知使用权，用于读取 MediaSession", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先允许显示悬浮窗", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return@setOnClickListener
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                startLyricsOverlay()
            }
        }

        backgroundModeTransparent.setOnClickListener {
            setBackgroundMode(LyricsOverlayService.BACKGROUND_TRANSPARENT)
        }
        backgroundModeLow.setOnClickListener {
            setBackgroundMode(LyricsOverlayService.BACKGROUND_LOW)
        }
        backgroundModeHigh.setOnClickListener {
            setBackgroundMode(LyricsOverlayService.BACKGROUND_HIGH)
        }

        updateBackgroundModeUi()
        updateFontSizeUi()
        seekFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val percent = LyricsOverlayService.FONT_SCALE_MIN_PERCENT + progress
                fontSizeValue.text = "$percent%"
                if (fromUser) setFontScale(percent)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        updateOverlayUi()
    }

    override fun onStart() {
        super.onStart()
        if (!overlayStateReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                overlayStateReceiver,
                IntentFilter(LyricsOverlayService.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            overlayStateReceiverRegistered = true
        }
        updateOverlayUi()
    }

    override fun onStop() {
        if (overlayStateReceiverRegistered) {
            unregisterReceiver(overlayStateReceiver)
            overlayStateReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (::tvOverlayStatus.isInitialized) {
            updateOverlayUi()
            updateBackgroundModeUi()
            updateFontSizeUi()
        }
    }

    private fun startLyricsOverlay() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_START
            }
        )
        btnOverlay.postDelayed({ updateOverlayUi() }, 250)
    }

    private fun hasNotificationListenerAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun setBackgroundMode(mode: String) {
        val normalized = when (mode) {
            LyricsOverlayService.BACKGROUND_LOW -> LyricsOverlayService.BACKGROUND_LOW
            LyricsOverlayService.BACKGROUND_HIGH -> LyricsOverlayService.BACKGROUND_HIGH
            else -> LyricsOverlayService.BACKGROUND_TRANSPARENT
        }
        overlayPrefs.edit()
            .putString(LyricsOverlayService.PREF_BACKGROUND_MODE, normalized)
            .apply()
        updateBackgroundModeUi()

        if (LyricsOverlayService.isRunning) {
            startService(Intent(this, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_SET_BACKGROUND
                putExtra(LyricsOverlayService.EXTRA_BACKGROUND_MODE, normalized)
            })
        }
    }

    private fun setFontScale(percent: Int) {
        val normalized = percent.coerceIn(
            LyricsOverlayService.FONT_SCALE_MIN_PERCENT,
            LyricsOverlayService.FONT_SCALE_MAX_PERCENT
        )
        val previous = overlayPrefs.getInt(
            LyricsOverlayService.PREF_FONT_SCALE_PERCENT,
            LyricsOverlayService.FONT_SCALE_DEFAULT_PERCENT
        ).coerceIn(
            LyricsOverlayService.FONT_SCALE_MIN_PERCENT,
            LyricsOverlayService.FONT_SCALE_MAX_PERCENT
        )
        val density = resources.displayMetrics.density
        fun minHeightPx(value: Int): Int =
            (LyricsOverlayService.compactMinimumHeightDp(value) * density + 0.5f).toInt()

        val storedHeight = overlayPrefs.getInt(
            "compact_height_v3",
            (48 * density + 0.5f).toInt()
        )
        val previousMin = minHeightPx(previous)
        val nextMin = minHeightPx(normalized)
        val adjustedHeight = if (storedHeight <= previousMin + (2 * density + 0.5f).toInt()) {
            nextMin
        } else {
            maxOf(storedHeight, nextMin)
        }

        overlayPrefs.edit()
            .putInt(LyricsOverlayService.PREF_FONT_SCALE_PERCENT, normalized)
            .putInt("compact_height_v3", adjustedHeight)
            .apply()
        fontSizeValue.text = "$normalized%"

        if (LyricsOverlayService.isRunning) {
            startService(Intent(this, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_SET_FONT_SCALE
                putExtra(LyricsOverlayService.EXTRA_FONT_SCALE_PERCENT, normalized)
            })
        }
    }

    private fun updateFontSizeUi() {
        val percent = overlayPrefs.getInt(
            LyricsOverlayService.PREF_FONT_SCALE_PERCENT,
            LyricsOverlayService.FONT_SCALE_DEFAULT_PERCENT
        ).coerceIn(
            LyricsOverlayService.FONT_SCALE_MIN_PERCENT,
            LyricsOverlayService.FONT_SCALE_MAX_PERCENT
        )
        fontSizeValue.text = "$percent%"
        seekFontSize.progress = percent - LyricsOverlayService.FONT_SCALE_MIN_PERCENT
    }

    private fun updateBackgroundModeUi() {
        val selectedMode = overlayPrefs.getString(
            LyricsOverlayService.PREF_BACKGROUND_MODE,
            LyricsOverlayService.BACKGROUND_DEFAULT
        )

        listOf(
            backgroundModeTransparent to LyricsOverlayService.BACKGROUND_TRANSPARENT,
            backgroundModeLow to LyricsOverlayService.BACKGROUND_LOW,
            backgroundModeHigh to LyricsOverlayService.BACKGROUND_HIGH
        ).forEach { (option, mode) ->
            val selected = selectedMode == mode || (
                selectedMode !in setOf(
                    LyricsOverlayService.BACKGROUND_TRANSPARENT,
                    LyricsOverlayService.BACKGROUND_LOW,
                    LyricsOverlayService.BACKGROUND_HIGH
                ) && mode == LyricsOverlayService.BACKGROUND_DEFAULT
            )
            option.setBackgroundResource(
                if (selected) R.drawable.bg_ui_segment_selected else android.R.color.transparent
            )
            option.setTextColor(
                android.graphics.Color.parseColor(if (selected) "#202331" else "#9DA4B5")
            )
            option.typeface = android.graphics.Typeface.create(
                "sans-serif",
                if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
        }
    }

    private fun updateOverlayUi() {
        val listenerGranted = hasNotificationListenerAccess()
        val overlayGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(this)
        val running = LyricsOverlayService.isRunning

        btnListenerPermission.text = if (listenerGranted) "✓ 通知使用权" else "通知使用权"
        btnOverlayPermission.text = if (overlayGranted) "✓ 悬浮窗权限" else "悬浮窗权限"
        btnOverlay.text = if (running) "关闭歌词悬浮窗" else "开启歌词悬浮窗"
        btnOverlay.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor(if (running) "#D85B65" else "#5D7CFF")
        )

        val permissionsReady = listenerGranted && overlayGranted
        tvRuntimeBadge.text = when {
            running -> "运行中"
            permissionsReady -> "准备就绪"
            else -> "待授权"
        }
        tvRuntimeBadge.setTextColor(
            android.graphics.Color.parseColor(
                when {
                    running -> "#90F0C0"
                    permissionsReady -> "#B8C5FF"
                    else -> "#FFD18A"
                }
            )
        )
        tvRuntimeBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor(
                when {
                    running -> "#2638C98B"
                    permissionsReady -> "#263E5FE0"
                    else -> "#265F4723"
                }
            )
        )

        tvOverlayStatus.text = when {
            running -> "已运行：系统回调实时同步，歌词进度在本机按帧推进"
            !listenerGranted && !overlayGranted -> "还需要授予“通知使用权”和“悬浮窗权限”"
            !listenerGranted -> "还需要通知使用权（读取第三方 MediaSession）"
            !overlayGranted -> "还需要悬浮窗权限"
            else -> "权限齐全，可以开启；无需给音乐 App 单独打开通知显示"
        }
    }
}
