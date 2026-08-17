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
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tcrrry.desktoplyrics.commercial.CommercialAccessUpdate
import com.tcrrry.desktoplyrics.commercial.CommercialController
import com.tcrrry.desktoplyrics.commercial.CommercialRuntimeFactory
import com.tcrrry.desktoplyrics.commercial.CommercialSettingsRenderer
import com.tcrrry.desktoplyrics.commercial.CommercialUiState
import com.tcrrry.desktoplyrics.commercial.CommercialVariantUi
import com.tcrrry.desktoplyrics.commercial.CommercialViewActions
import com.tcrrry.desktoplyrics.commercial.CheckoutState
import com.tcrrry.desktoplyrics.commercial.RecoveryState

class MainActivity : AppCompatActivity() {

    private val overlayPrefs by lazy {
        getSharedPreferences(LyricsOverlayService.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private lateinit var currentLineOption: TextView
    private lateinit var currentAndNextOption: TextView
    private lateinit var smallSizeOption: TextView
    private lateinit var standardSizeOption: TextView
    private lateinit var largeSizeOption: TextView
    private lateinit var displayNavigationItem: NavigationItem
    private lateinit var systemNavigationItem: NavigationItem
    private lateinit var commercialNavigationItem: NavigationItem
    private lateinit var displayContent: View
    private lateinit var systemContent: View
    private lateinit var commercialContent: View
    private lateinit var contentScroll: ScrollView
    private lateinit var wallpaperLyricsSetting: LinearLayout
    private lateinit var wallpaperLyricsSwitch: IcarSwitch
    private lateinit var lyricsTranslationSetting: LinearLayout
    private lateinit var lyricsTranslationSwitch: IcarSwitch
    private lateinit var restartLyricsSetting: LinearLayout
    private lateinit var commercialRenderer: CommercialSettingsRenderer
    private lateinit var commercialController: CommercialController
    private var themePalette = IcarThemeColorPalette.resolve(null, false)
    private var renderedNightMode = false
    private var themeObserverRegistered = false
    private var renderingOptions = false
    private var selectedSection = SettingsSection.DISPLAY

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
        displayNavigationItem = navigationItem(
            R.id.settings_navigation_display,
            R.id.settings_navigation_display_icon,
            R.id.settings_navigation_display_label
        )
        systemNavigationItem = navigationItem(
            R.id.settings_navigation_system,
            R.id.settings_navigation_system_icon,
            R.id.settings_navigation_system_label
        )
        commercialNavigationItem = navigationItem(
            R.id.settings_navigation_entitlement,
            R.id.settings_navigation_entitlement_icon,
            R.id.settings_navigation_entitlement_label
        )
        displayContent = findViewById(R.id.settings_display_content)
        systemContent = findViewById(R.id.settings_system_content)
        commercialContent = findViewById(R.id.settings_commercial_content)
        contentScroll = findViewById(R.id.settings_content_scroll)
        wallpaperLyricsSetting = findViewById(R.id.wallpaper_lyrics_setting)
        wallpaperLyricsSwitch = findViewById(R.id.wallpaper_lyrics_switch)
        lyricsTranslationSetting = findViewById(R.id.lyrics_translation_setting)
        lyricsTranslationSwitch = findViewById(R.id.lyrics_translation_switch)
        restartLyricsSetting = findViewById(R.id.restart_lyrics_setting)

        selectedSection = SettingsSection.from(
            savedInstanceState?.getString(STATE_SELECTED_SECTION)
                ?: intent.getStringExtra(EXTRA_OPEN_SETTINGS_SECTION)
        )

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
        lyricsTranslationSetting.setOnClickListener {
            lyricsTranslationSwitch.isChecked = !lyricsTranslationSwitch.isChecked
        }
        lyricsTranslationSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!renderingOptions) setLyricsTranslationEnabled(enabled)
        }
        displayNavigationItem.container.setOnClickListener { showSection(SettingsSection.DISPLAY) }
        systemNavigationItem.container.setOnClickListener { showSection(SettingsSection.SYSTEM) }
        commercialNavigationItem.container.setOnClickListener {
            commercialController.showEntitlementPage()
            showSection(SettingsSection.COMMERCIAL)
        }
        restartLyricsSetting.setOnClickListener { restartLyricsOverlay() }

        commercialRenderer = CommercialSettingsRenderer(
            root = findViewById(android.R.id.content),
            actions = CommercialViewActions(
                onOpenEntitlement = {
                    commercialController.showEntitlementPage()
                    showSection(SettingsSection.COMMERCIAL)
                },
                onCheckout = {
                    showSection(SettingsSection.COMMERCIAL)
                    commercialController.showCheckout()
                },
                onRetryEntitlement = { commercialController.reloadEntitlement() },
                onDiscountCodeChanged = { commercialController.changeDiscountCode(it) },
                onApplyDiscount = { commercialController.applyDiscountCode() },
                onPaymentMethodChanged = { commercialController.selectPaymentMethod(it) },
                onPay = { commercialController.createPayment() },
                onRestore = { commercialController.restorePurchase() }
            )
        )
        commercialRenderer.updateAccent(
            accentColor = themePalette.accentColor,
            accentTextColor = themePalette.accentTextColor
        )
        commercialController = CommercialController(
            gateway = CommercialRuntimeFactory.gateway(this),
            onStateChanged = ::renderCommercialState,
            onAccessMayHaveChanged = ::refreshCommercialAccess
        )
        CommercialVariantUi.handleDebugIntent(this, intent, commercialController)
        commercialRenderer.render(commercialController.state)
        commercialController.start()

        refreshThemePalette()
        showSection(selectedSection)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra(EXTRA_OPEN_SETTINGS_SECTION) == SECTION_COMMERCIAL) {
            showSection(SettingsSection.COMMERCIAL)
        }
        CommercialVariantUi.handleDebugIntent(this, intent, commercialController)
    }

    override fun onResume() {
        super.onResume()
        if (renderedNightMode != isNightMode()) {
            recreate()
            return
        }
        if (::currentLineOption.isInitialized) {
            refreshThemePalette()
        }
    }

    override fun onStart() {
        super.onStart()
        registerThemeColorObserver()
        refreshThemePalette()
        ensureLyricsOverlayForSettings()
    }

    override fun onStop() {
        unregisterThemeColorObserver()
        if (!isChangingConfigurations) {
            notifyOverlay(LyricsOverlayService.ACTION_SETTINGS_CLOSED)
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SELECTED_SECTION, selectedSection.name)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::commercialController.isInitialized) commercialController.close()
        super.onDestroy()
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

    private fun setLyricsTranslationEnabled(enabled: Boolean) {
        overlayPrefs.edit()
            .putBoolean(LyricsOverlayService.PREF_LYRICS_TRANSLATION_ENABLED, enabled)
            .apply()

        if (LyricsOverlayService.isRunning) {
            startService(Intent(this, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_SET_LYRICS_TRANSLATION
                putExtra(LyricsOverlayService.EXTRA_LYRICS_TRANSLATION_ENABLED, enabled)
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
            renderLyricsTranslationSwitch(
                overlayPrefs.getBoolean(
                    LyricsOverlayService.PREF_LYRICS_TRANSLATION_ENABLED,
                    LyricsOverlayService.LYRICS_TRANSLATION_DEFAULT
                )
            )
            renderNavigation()
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

    private fun setNavigationSelected(item: NavigationItem) {
        item.container.setBackgroundResource(R.drawable.bg_settings_navigation_selected)
        item.container.backgroundTintList = ColorStateList.valueOf(themePalette.accentColor)
        item.icon.imageTintList = ColorStateList.valueOf(themePalette.accentTextColor)
        item.label.setTextColor(themePalette.accentTextColor)
        item.label.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun setNavigationUnselected(item: NavigationItem) {
        val textColor = ContextCompat.getColor(this, R.color.settings_text_primary)
        item.container.background = null
        item.container.backgroundTintList = null
        item.icon.imageTintList = ColorStateList.valueOf(textColor)
        item.label.setTextColor(textColor)
        item.label.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        item.container.isSelected = false
        item.container.contentDescription = item.label.text
    }

    private fun renderNavigation() {
        val selectedItem = navigationItem(selectedSection)
        listOf(
            displayNavigationItem,
            systemNavigationItem,
            commercialNavigationItem
        ).filterNot { it === selectedItem }.forEach(::setNavigationUnselected)
        setNavigationSelected(selectedItem)
        selectedItem.container.isSelected = true
        selectedItem.container.contentDescription = getString(
            R.string.accessibility_navigation_selected,
            selectedItem.label.text
        )
    }

    private fun renderWallpaperLyricsSwitch(enabled: Boolean) {
        wallpaperLyricsSwitch.accentColor = themePalette.accentColor
        wallpaperLyricsSwitch.isChecked = enabled
    }

    private fun renderLyricsTranslationSwitch(enabled: Boolean) {
        lyricsTranslationSwitch.accentColor = themePalette.accentColor
        lyricsTranslationSwitch.isChecked = enabled
    }

    private fun showSection(section: SettingsSection) {
        selectedSection = section
        commercialRenderer.setSummaryVisibleForSection(section != SettingsSection.COMMERCIAL)
        displayContent.visibility = if (section == SettingsSection.DISPLAY) View.VISIBLE else View.GONE
        systemContent.visibility = if (section == SettingsSection.SYSTEM) View.VISIBLE else View.GONE
        commercialContent.visibility = if (section == SettingsSection.COMMERCIAL) {
            View.VISIBLE
        } else {
            View.GONE
        }
        renderNavigation()
        contentScroll.post { contentScroll.scrollTo(0, 0) }
    }

    private fun navigationItem(section: SettingsSection): NavigationItem = when (section) {
        SettingsSection.DISPLAY -> displayNavigationItem
        SettingsSection.SYSTEM -> systemNavigationItem
        SettingsSection.COMMERCIAL -> commercialNavigationItem
    }

    private fun navigationItem(containerId: Int, iconId: Int, labelId: Int) = NavigationItem(
        container = findViewById(containerId),
        icon = findViewById(iconId),
        label = findViewById(labelId)
    )

    private fun hasNotificationListenerAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun restartLyricsOverlay() {
        if (!LyricsStartupPolicy.hasRequiredAccess(
                overlayAccess = Settings.canDrawOverlays(this),
                notificationAccess = hasNotificationListenerAccess()
            )
        ) {
            Toast.makeText(
                this,
                R.string.settings_restart_requires_authorization,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val intent = Intent(this, LyricsOverlayService::class.java).apply {
            action = LyricsOverlayService.ACTION_RESTART
        }
        if (LyricsOverlayService.isRunning) {
            startService(intent)
        } else {
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private fun refreshThemePalette() {
        themePalette = IcarThemeColorPalette.resolve(
            themeKey = runCatching {
                Settings.Global.getInt(contentResolver, IcarThemeColorPalette.GLOBAL_THEME_KEY)
            }.getOrNull(),
            nightMode = isNightMode()
        )
        if (::commercialRenderer.isInitialized) {
            commercialRenderer.updateAccent(
                accentColor = themePalette.accentColor,
                accentTextColor = themePalette.accentTextColor
            )
        }
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

    private fun ensureLyricsOverlayForSettings() {
        if (!LyricsStartupPolicy.hasRequiredAccess(
                overlayAccess = Settings.canDrawOverlays(this),
                notificationAccess = hasNotificationListenerAccess()
            )
        ) return
        val intent = Intent(this, LyricsOverlayService::class.java).apply {
            action = LyricsOverlayService.ACTION_SETTINGS_OPENED
        }
        if (LyricsOverlayService.isRunning) {
            startService(intent)
        } else {
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private fun refreshCommercialAccess(update: CommercialAccessUpdate) {
        if (update == CommercialAccessUpdate.REVOKED) {
            val intent = Intent(this, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_COMMERCIAL_ACCESS_REVOKED
            }
            if (LyricsOverlayService.isRunning) {
                startService(intent)
            } else {
                ContextCompat.startForegroundService(this, intent)
            }
            return
        }
        if (!LyricsStartupPolicy.hasRequiredAccess(
                overlayAccess = Settings.canDrawOverlays(this),
                notificationAccess = hasNotificationListenerAccess()
            )
        ) return
        val intent = Intent(this, LyricsOverlayService::class.java).apply {
            action = LyricsOverlayService.ACTION_COMMERCIAL_ACCESS_CHANGED
        }
        if (LyricsOverlayService.isRunning) {
            startService(intent)
        } else {
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private fun renderCommercialState(state: CommercialUiState) {
        commercialRenderer.render(state)
        if (state.checkout is CheckoutState.Paid || state.recovery is RecoveryState.Success) {
            showSection(SettingsSection.COMMERCIAL)
        }
    }

    private fun notifyOverlay(action: String) {
        if (!LyricsOverlayService.isRunning) return
        startService(Intent(this, LyricsOverlayService::class.java).apply {
            this.action = action
        })
    }

    companion object {
        const val STATE_SELECTED_SECTION = "selected_settings_section"
        const val EXTRA_OPEN_SETTINGS_SECTION = "open_settings_section"
        const val SECTION_COMMERCIAL = "COMMERCIAL"
        const val TOPBAR_LINES_DEFAULT = 2
        const val FONT_SCALE_SMALL = 88
        const val FONT_SCALE_STANDARD = 100
        const val FONT_SCALE_LARGE = 108
    }

    private enum class SettingsSection {
        DISPLAY,
        SYSTEM,
        COMMERCIAL;

        companion object {
            fun from(value: String?): SettingsSection = entries.firstOrNull { it.name == value }
                ?: DISPLAY
        }
    }

    private data class NavigationItem(
        val container: View,
        val icon: ImageView,
        val label: TextView
    )
}
