package com.tcrrry.desktoplyrics

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class MainActivity : AppCompatActivity() {

    private val overlayPrefs by lazy {
        getSharedPreferences(LyricsOverlayService.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private lateinit var currentLineOption: TextView
    private lateinit var currentAndNextOption: TextView
    private lateinit var smallSizeOption: TextView
    private lateinit var standardSizeOption: TextView
    private lateinit var largeSizeOption: TextView
    private lateinit var wallpaperLyricsSwitch: SwitchCompat
    private var updatingOptions = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        currentLineOption = findViewById(R.id.topbar_lines_current)
        currentAndNextOption = findViewById(R.id.topbar_lines_current_next)
        smallSizeOption = findViewById(R.id.font_size_small)
        standardSizeOption = findViewById(R.id.font_size_standard)
        largeSizeOption = findViewById(R.id.font_size_large)
        wallpaperLyricsSwitch = findViewById(R.id.wallpaper_lyrics_switch)

        findViewById<TextView>(R.id.settings_close).setOnClickListener { finish() }
        findViewById<TextView>(R.id.settings_done).setOnClickListener { finish() }

        currentLineOption.setOnClickListener { setTopbarLines(1) }
        currentAndNextOption.setOnClickListener { setTopbarLines(2) }
        smallSizeOption.setOnClickListener { setFontScale(FONT_SCALE_SMALL) }
        standardSizeOption.setOnClickListener { setFontScale(FONT_SCALE_STANDARD) }
        largeSizeOption.setOnClickListener { setFontScale(FONT_SCALE_LARGE) }
        wallpaperLyricsSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!updatingOptions) setWallpaperLyricsEnabled(enabled)
        }

        updateOptions()
    }

    override fun onResume() {
        super.onResume()
        if (::currentLineOption.isInitialized) updateOptions()
    }

    override fun onStart() {
        super.onStart()
        notifyOverlay(LyricsOverlayService.ACTION_SETTINGS_OPENED)
    }

    override fun onStop() {
        notifyOverlay(LyricsOverlayService.ACTION_SETTINGS_CLOSED)
        super.onStop()
    }

    private fun setTopbarLines(lines: Int) {
        val normalized = if (lines == 1) 1 else 2
        overlayPrefs.edit()
            .putInt(LyricsOverlayService.PREF_TOPBAR_LINES, normalized)
            .apply()
        updateOptions()

        if (LyricsOverlayService.isRunning) {
            startService(Intent(this, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_SET_TOPBAR_LINES
                putExtra(LyricsOverlayService.EXTRA_TOPBAR_LINES, normalized)
            })
        }
    }

    private fun setFontScale(percent: Int) {
        val normalized = percent.coerceIn(
            LyricsOverlayService.FONT_SCALE_MIN_PERCENT,
            LyricsOverlayService.FONT_SCALE_MAX_PERCENT
        )
        overlayPrefs.edit()
            .putInt(LyricsOverlayService.PREF_FONT_SCALE_PERCENT, normalized)
            .apply()
        updateOptions()

        if (LyricsOverlayService.isRunning) {
            startService(Intent(this, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_SET_FONT_SCALE
                putExtra(LyricsOverlayService.EXTRA_FONT_SCALE_PERCENT, normalized)
            })
        }
    }

    private fun setWallpaperLyricsEnabled(enabled: Boolean) {
        overlayPrefs.edit()
            .putBoolean(LyricsOverlayService.PREF_WALLPAPER_LYRICS_ENABLED, enabled)
            .apply()

        if (LyricsOverlayService.isRunning) {
            startService(Intent(this, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_SET_WALLPAPER_LYRICS
                putExtra(LyricsOverlayService.EXTRA_WALLPAPER_LYRICS_ENABLED, enabled)
            })
        }
    }

    private fun updateOptions() {
        val lines = if (
            overlayPrefs.getInt(
                LyricsOverlayService.PREF_TOPBAR_LINES,
                TOPBAR_LINES_DEFAULT
            ) == 1
        ) {
            1
        } else {
            2
        }
        val fontScale = overlayPrefs.getInt(
            LyricsOverlayService.PREF_FONT_SCALE_PERCENT,
            LyricsOverlayService.FONT_SCALE_DEFAULT_PERCENT
        ).coerceIn(
            LyricsOverlayService.FONT_SCALE_MIN_PERCENT,
            LyricsOverlayService.FONT_SCALE_MAX_PERCENT
        )

        updatingOptions = true
        try {
            wallpaperLyricsSwitch.isChecked = overlayPrefs.getBoolean(
                LyricsOverlayService.PREF_WALLPAPER_LYRICS_ENABLED,
                LyricsOverlayService.WALLPAPER_LYRICS_DEFAULT
            )
            setOptionSelected(currentLineOption, lines == 1)
            setOptionSelected(currentAndNextOption, lines == 2)

            val selectedFontScale = when {
                fontScale < (FONT_SCALE_SMALL + FONT_SCALE_STANDARD) / 2 -> FONT_SCALE_SMALL
                fontScale > (FONT_SCALE_STANDARD + FONT_SCALE_LARGE) / 2 -> FONT_SCALE_LARGE
                else -> FONT_SCALE_STANDARD
            }
            setOptionSelected(smallSizeOption, selectedFontScale == FONT_SCALE_SMALL)
            setOptionSelected(standardSizeOption, selectedFontScale == FONT_SCALE_STANDARD)
            setOptionSelected(largeSizeOption, selectedFontScale == FONT_SCALE_LARGE)
        } finally {
            updatingOptions = false
        }
    }

    private fun setOptionSelected(option: TextView, selected: Boolean) {
        option.setBackgroundResource(
            if (selected) R.drawable.bg_settings_option_selected else R.drawable.bg_settings_option
        )
        option.setTextColor(Color.parseColor(if (selected) "#F7FAFF" else "#AAB1BE"))
        option.typeface = Typeface.create(
            "sans-serif",
            if (selected) Typeface.BOLD else Typeface.NORMAL
        )
    }

    private fun notifyOverlay(action: String) {
        if (!LyricsOverlayService.isRunning) return
        startService(Intent(this, LyricsOverlayService::class.java).apply {
            this.action = action
        })
    }

    private companion object {
        const val TOPBAR_LINES_DEFAULT = 2
        const val FONT_SCALE_SMALL = 88
        const val FONT_SCALE_STANDARD = 100
        const val FONT_SCALE_LARGE = 108
    }
}
