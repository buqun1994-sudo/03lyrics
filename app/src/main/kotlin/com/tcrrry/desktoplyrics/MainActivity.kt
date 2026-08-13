package com.tcrrry.desktoplyrics

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.database.ContentObserver
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val overlayPrefs by lazy {
        getSharedPreferences(LyricsOverlayService.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private lateinit var currentLineOption: TextView
    private lateinit var currentAndNextOption: TextView
    private lateinit var smallSizeOption: TextView
    private lateinit var standardSizeOption: TextView
    private lateinit var largeSizeOption: TextView
    private lateinit var displayNavigationItem: TextView
    private lateinit var wallpaperLyricsSetting: LinearLayout
    private lateinit var wallpaperLyricsSwitch: SwitchCompat
    private var themePalette = IcarThemeColorPalette.resolve(null, false)
    private var renderedNightMode = false
    private var themeObserverRegistered = false
    private var renderingOptions = false

    private val themeColorObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshThemePalette()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderedNightMode = isNightMode()
        setContentView(R.layout.activity_main)

        currentLineOption = findViewById(R.id.topbar_lines_current)
        currentAndNextOption = findViewById(R.id.topbar_lines_current_next)
        smallSizeOption = findViewById(R.id.font_size_small)
        standardSizeOption = findViewById(R.id.font_size_standard)
        largeSizeOption = findViewById(R.id.font_size_large)
        displayNavigationItem = findViewById(R.id.settings_navigation_display)
        wallpaperLyricsSetting = findViewById(R.id.wallpaper_lyrics_setting)
        wallpaperLyricsSwitch = findViewById(R.id.wallpaper_lyrics_switch)

        currentLineOption.setOnClickListener { setTopbarLines(1) }
        currentAndNextOption.setOnClickListener { setTopbarLines(2) }
        smallSizeOption.setOnClickListener { setFontScale(FONT_SCALE_SMALL) }
        standardSizeOption.setOnClickListener { setFontScale(FONT_SCALE_STANDARD) }
        largeSizeOption.setOnClickListener { setFontScale(FONT_SCALE_LARGE) }
        wallpaperLyricsSetting.setOnClickListener {
            wallpaperLyricsSwitch.isChecked = !wallpaperLyricsSwitch.isChecked
        }
        wallpaperLyricsSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!renderingOptions) setWallpaperLyricsEnabled(enabled)
        }

        refreshThemePalette()
    }

    override fun onResume() {
        super.onResume()
        if (renderedNightMode != isNightMode()) {
            recreate()
            return
        }
        if (::currentLineOption.isInitialized) refreshThemePalette()
    }

    override fun onStart() {
        super.onStart()
        registerThemeColorObserver()
        refreshThemePalette()
        notifyOverlay(LyricsOverlayService.ACTION_SETTINGS_OPENED)
    }

    override fun onStop() {
        unregisterThemeColorObserver()
        if (!isChangingConfigurations) {
            notifyOverlay(LyricsOverlayService.ACTION_SETTINGS_CLOSED)
        }
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

        renderingOptions = true
        try {
            renderWallpaperLyricsSwitch(
                overlayPrefs.getBoolean(
                    LyricsOverlayService.PREF_WALLPAPER_LYRICS_ENABLED,
                    LyricsOverlayService.WALLPAPER_LYRICS_DEFAULT
                )
            )
            setNavigationSelected(displayNavigationItem)
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
            renderingOptions = false
        }
    }

    private fun setOptionSelected(option: TextView, selected: Boolean) {
        option.setBackgroundResource(if (selected) R.drawable.bg_settings_segment_selected else 0)
        option.backgroundTintList = if (selected) {
            ColorStateList.valueOf(themePalette.accentColor)
        } else {
            null
        }
        option.setTextColor(
            if (selected) themePalette.accentTextColor
            else ContextCompat.getColor(this, R.color.settings_text_option)
        )
        option.typeface = Typeface.create(
            if (selected) "sans-serif-medium" else "sans-serif",
            Typeface.NORMAL
        )
    }

    private fun setNavigationSelected(item: TextView) {
        item.setBackgroundResource(R.drawable.bg_settings_navigation_selected)
        item.backgroundTintList = ColorStateList.valueOf(themePalette.accentColor)
        item.setTextColor(themePalette.accentTextColor)
        item.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun renderWallpaperLyricsSwitch(enabled: Boolean) {
        wallpaperLyricsSwitch.trackTintList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(
                themePalette.accentColor,
                ContextCompat.getColor(this, R.color.settings_switch_track_off)
            )
        )
        wallpaperLyricsSwitch.isChecked = enabled
    }

    private fun refreshThemePalette() {
        themePalette = IcarThemeColorPalette.resolve(
            themeKey = runCatching {
                Settings.Global.getInt(contentResolver, IcarThemeColorPalette.GLOBAL_THEME_KEY)
            }.getOrNull(),
            nightMode = isNightMode()
        )
        if (::currentLineOption.isInitialized) updateOptions()
    }

    private fun registerThemeColorObserver() {
        if (themeObserverRegistered) return
        runCatching {
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor(IcarThemeColorPalette.GLOBAL_THEME_KEY),
                false,
                themeColorObserver
            )
            themeObserverRegistered = true
        }
    }

    private fun unregisterThemeColorObserver() {
        if (!themeObserverRegistered) return
        runCatching { contentResolver.unregisterContentObserver(themeColorObserver) }
        themeObserverRegistered = false
    }

    private fun isNightMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

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
