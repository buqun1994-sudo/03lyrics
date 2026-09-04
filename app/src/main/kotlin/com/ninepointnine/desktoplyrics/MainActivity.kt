package com.ninepointnine.desktoplyrics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ninepointnine.desktoplyrics.commercial.CommercialAccessUpdate
import com.ninepointnine.desktoplyrics.commercial.CommercialController
import com.ninepointnine.desktoplyrics.commercial.CommercialRuntimeFactory
import com.ninepointnine.desktoplyrics.commercial.CommercialSettingsRenderer
import com.ninepointnine.desktoplyrics.commercial.CommercialUiState
import com.ninepointnine.desktoplyrics.commercial.CommercialVariantUi
import com.ninepointnine.desktoplyrics.commercial.CommercialViewActions
import com.ninepointnine.desktoplyrics.commercial.CheckoutState
import com.ninepointnine.desktoplyrics.commercial.RecoveryState

class MainActivity : AppCompatActivity() {

    private val overlayPrefs by lazy {
        getSharedPreferences(LyricsOverlayService.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private lateinit var displayNavigationItem: NavigationItem
    private lateinit var systemNavigationItem: NavigationItem
    private lateinit var cacheNavigationItem: NavigationItem
    private lateinit var searchNavigationItem: NavigationItem
    private lateinit var commercialNavigationItem: NavigationItem
    private lateinit var aboutNavigationItem: NavigationItem
    private lateinit var displayContent: View
    private lateinit var systemContent: View
    private lateinit var cacheContent: View
    private lateinit var searchContent: View
    private lateinit var commercialContent: View
    private lateinit var aboutContent: View
    private lateinit var aboutVersionValue: TextView
    private lateinit var aboutTermsQr: ImageView
    private lateinit var contentScroll: ScrollView
    private lateinit var lyricsSettingsRenderer: LyricsSettingsRenderer
    private lateinit var commercialRenderer: CommercialSettingsRenderer
    private lateinit var commercialController: CommercialController
    private var themePalette = IcarThemeColorPalette.resolve(null, false)
    private var renderedNightMode = false
    private var themeObserverRegistered = false
    private var settingsReceiverRegistered = false
    private var selectedSection = SettingsSection.LYRICS

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                LyricsOverlayService.ACTION_STATE_CHANGED -> {
                    val running = intent.getBooleanExtra(
                        LyricsOverlayService.EXTRA_RUNNING,
                        LyricsOverlayService.isRunning
                    )
                    lyricsSettingsRenderer.renderServiceRunning(running)
                    if (!running) updateOptions()
                }
                LyricsOverlayService.ACTION_SETTINGS_STATE_CHANGED -> {
                    LyricsSettingsRuntimeState.decode(
                        intent.getStringExtra(LyricsOverlayService.EXTRA_SETTINGS_STATE)
                    )?.let(lyricsSettingsRenderer::renderRuntimeState)
                }
            }
        }
    }

    private val themeColorObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshThemePalette()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderedNightMode = isNightMode()
        setContentView(R.layout.activity_main)

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
        cacheNavigationItem = navigationItem(
            R.id.settings_navigation_cache,
            R.id.settings_navigation_cache_icon,
            R.id.settings_navigation_cache_label
        )
        searchNavigationItem = navigationItem(
            R.id.settings_navigation_search,
            R.id.settings_navigation_search_icon,
            R.id.settings_navigation_search_label
        )
        commercialNavigationItem = navigationItem(
            R.id.settings_navigation_entitlement,
            R.id.settings_navigation_entitlement_icon,
            R.id.settings_navigation_entitlement_label
        )
        aboutNavigationItem = navigationItem(
            R.id.settings_navigation_about,
            R.id.settings_navigation_about_icon,
            R.id.settings_navigation_about_label
        )
        displayContent = findViewById(R.id.settings_display_content)
        systemContent = findViewById(R.id.settings_system_content)
        cacheContent = findViewById(R.id.settings_cache_content)
        searchContent = findViewById(R.id.settings_search_content)
        commercialContent = findViewById(R.id.settings_commercial_content)
        aboutContent = findViewById(R.id.settings_about_content)
        aboutVersionValue = findViewById(R.id.about_version_value)
        aboutTermsQr = findViewById(R.id.about_terms_qr)
        renderAboutContent()
        contentScroll = findViewById(R.id.settings_content_scroll)

        lyricsSettingsRenderer = LyricsSettingsRenderer(
            root = findViewById(android.R.id.content),
            actions = LyricsSettingsActions(
                onTopbarLinesChanged = ::setTopbarLines,
                onTopbarFontScaleChanged = ::setTopbarFontScale,
                onWallpaperEnabledChanged = ::setWallpaperLyricsEnabled,
                onWallpaperFontScaleChanged = ::setWallpaperFontScale,
                onWallpaperBlurChanged = ::setWallpaperBlurEnabled,
                onWallpaperShadowChanged = ::setWallpaperShadowEnabled,
                onWallpaperSpacingChanged = ::setWallpaperSpacing,
                onWallpaperFocusChanged = ::setWallpaperFocus,
                onWallpaperPositionChanged = ::setWallpaperPosition,
                onAutoStartChanged = ::setAutoStartEnabled,
                onTranslationChanged = ::setLyricsTranslationEnabled,
                onServiceRunningChanged = ::setServiceRunning,
                onRestartService = ::restartLyricsOverlay,
                onSearch = ::searchLyrics,
                onSelectCandidate = ::selectManualLyrics,
                onDeleteCurrentCache = ::deleteCurrentLyricsCache,
                onRestoreAutomatic = ::restoreAutomaticLyrics
            )
        )
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (lyricsSettingsRenderer.dismissMessageDialog()) return
                if (selectedSection == SettingsSection.COMMERCIAL &&
                    commercialRenderer.consumeBack()
                ) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })

        selectedSection = SettingsSection.from(
            savedInstanceState?.getString(STATE_SELECTED_SECTION)
                ?: intent.getStringExtra(EXTRA_OPEN_SETTINGS_SECTION)
        )

        displayNavigationItem.container.setOnClickListener { showSection(SettingsSection.LYRICS) }
        systemNavigationItem.container.setOnClickListener { showSection(SettingsSection.SERVICE) }
        cacheNavigationItem.container.setOnClickListener { showSection(SettingsSection.CACHE) }
        searchNavigationItem.container.setOnClickListener { showSection(SettingsSection.SEARCH) }
        commercialNavigationItem.container.setOnClickListener {
            commercialController.showEntitlementPage()
            showSection(SettingsSection.COMMERCIAL)
        }
        aboutNavigationItem.container.setOnClickListener { showSection(SettingsSection.ABOUT) }

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
            coordinator = CommercialRuntimeFactory.entitlementCoordinator(this),
            onStateChanged = ::renderCommercialState,
            onAccessMayHaveChanged = ::refreshCommercialAccess
        )
        CommercialVariantUi.handleDebugIntent(this, intent, commercialController)
        commercialRenderer.render(commercialController.state)

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
        CommercialVariantUi.handleDiagnosticResume(this, intent)
        if (renderedNightMode != isNightMode()) {
            recreate()
            return
        }
        if (::lyricsSettingsRenderer.isInitialized) {
            refreshThemePalette()
        }
    }

    override fun onStart() {
        super.onStart()
        registerSettingsReceiver()
        registerThemeColorObserver()
        refreshThemePalette()
        lyricsSettingsRenderer.renderServiceRunning(LyricsOverlayService.isRunning)
        ensureLyricsOverlayForSettings()
        // A newly visible settings surface is an entitlement lifecycle
        // boundary. The controller keeps rendering the locally verified state
        // while this request runs asynchronously.
        if (::commercialController.isInitialized) {
            commercialController.reloadEntitlement()
        }
        requestSettingsState()
    }

    override fun onStop() {
        unregisterThemeColorObserver()
        unregisterSettingsReceiver()
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

    private fun setTopbarFontScale(percent: Int) {
        val normalized = percent.coerceIn(
            LyricsOverlayService.FONT_SCALE_MIN_PERCENT,
            LyricsOverlayService.FONT_SCALE_MAX_PERCENT
        )
        overlayPrefs.edit()
            .putInt(LyricsOverlayService.PREF_TOPBAR_FONT_SCALE_PERCENT, normalized)
            .apply()
        updateOptions()

        if (LyricsOverlayService.isRunning) {
            startService(Intent(this, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_SET_TOPBAR_FONT_SCALE
                putExtra(LyricsOverlayService.EXTRA_FONT_SCALE_PERCENT, normalized)
            })
        }
    }

    private fun setWallpaperFontScale(percent: Int) {
        val normalized = percent.coerceIn(
            LyricsOverlayService.FONT_SCALE_MIN_PERCENT,
            LyricsOverlayService.FONT_SCALE_MAX_PERCENT
        )
        overlayPrefs.edit()
            .putInt(LyricsOverlayService.PREF_WALLPAPER_FONT_SCALE_PERCENT, normalized)
            .apply()
        updateOptions()
        notifyDisplaySetting(LyricsOverlayService.ACTION_SET_WALLPAPER_FONT_SCALE) {
            putExtra(LyricsOverlayService.EXTRA_FONT_SCALE_PERCENT, normalized)
        }
    }

    private fun setWallpaperLyricsEnabled(enabled: Boolean) {
        overlayPrefs.edit()
            .putBoolean(LyricsOverlayService.PREF_WALLPAPER_LYRICS_ENABLED, enabled)
            .apply()
        updateOptions()
        notifyDisplaySetting(LyricsOverlayService.ACTION_SET_WALLPAPER_LYRICS) {
            putExtra(LyricsOverlayService.EXTRA_WALLPAPER_LYRICS_ENABLED, enabled)
        }
    }

    private fun setWallpaperBlurEnabled(enabled: Boolean) {
        overlayPrefs.edit()
            .putBoolean(LyricsOverlayService.PREF_WALLPAPER_BLUR_ENABLED, enabled)
            .apply()
        updateOptions()
        notifyDisplaySetting(LyricsOverlayService.ACTION_SET_WALLPAPER_BLUR) {
            putExtra(LyricsOverlayService.EXTRA_WALLPAPER_BLUR_ENABLED, enabled)
        }
    }

    private fun setWallpaperShadowEnabled(enabled: Boolean) {
        overlayPrefs.edit()
            .putBoolean(LyricsOverlayService.PREF_WALLPAPER_SHADOW_ENABLED, enabled)
            .apply()
        updateOptions()
        notifyDisplaySetting(LyricsOverlayService.ACTION_SET_WALLPAPER_SHADOW) {
            putExtra(LyricsOverlayService.EXTRA_WALLPAPER_SHADOW_ENABLED, enabled)
        }
    }

    private fun setWallpaperSpacing(spacing: WallpaperLyricsSpacing) {
        overlayPrefs.edit()
            .putString(LyricsOverlayService.PREF_WALLPAPER_SPACING, spacing.preferenceValue)
            .apply()
        updateOptions()
        notifyDisplaySetting(LyricsOverlayService.ACTION_SET_WALLPAPER_SPACING) {
            putExtra(LyricsOverlayService.EXTRA_WALLPAPER_SPACING, spacing.preferenceValue)
        }
    }

    private fun setWallpaperFocus(focus: WallpaperLyricsFocus) {
        overlayPrefs.edit()
            .putString(LyricsOverlayService.PREF_WALLPAPER_FOCUS, focus.preferenceValue)
            .apply()
        updateOptions()
        notifyDisplaySetting(LyricsOverlayService.ACTION_SET_WALLPAPER_FOCUS) {
            putExtra(LyricsOverlayService.EXTRA_WALLPAPER_FOCUS, focus.preferenceValue)
        }
    }

    private fun setWallpaperPosition(position: WallpaperLyricsPosition) {
        overlayPrefs.edit()
            .putString(LyricsOverlayService.PREF_WALLPAPER_POSITION, position.preferenceValue)
            .apply()
        updateOptions()
        notifyDisplaySetting(LyricsOverlayService.ACTION_SET_WALLPAPER_POSITION) {
            putExtra(LyricsOverlayService.EXTRA_WALLPAPER_POSITION, position.preferenceValue)
        }
    }

    private fun setAutoStartEnabled(enabled: Boolean) {
        overlayPrefs.edit()
            .putBoolean(LyricsOverlayService.PREF_AUTO_START, enabled)
            .apply()
        updateOptions()
    }

    private fun setLyricsTranslationEnabled(enabled: Boolean) {
        overlayPrefs.edit()
            .putBoolean(LyricsOverlayService.PREF_LYRICS_TRANSLATION_ENABLED, enabled)
            .apply()
        updateOptions()
        notifyDisplaySetting(LyricsOverlayService.ACTION_SET_LYRICS_TRANSLATION) {
            putExtra(LyricsOverlayService.EXTRA_LYRICS_TRANSLATION_ENABLED, enabled)
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
        val legacyFontScale = overlayPrefs.getInt(
            LyricsOverlayService.PREF_FONT_SCALE_PERCENT,
            LyricsOverlayService.FONT_SCALE_DEFAULT_PERCENT
        ).coerceIn(
            LyricsOverlayService.FONT_SCALE_MIN_PERCENT,
            LyricsOverlayService.FONT_SCALE_MAX_PERCENT
        )
        lyricsSettingsRenderer.renderPreferences(
            LyricsSettingsPreferences(
                topbarLines = lines,
                topbarFontScale = overlayPrefs.getInt(
                    LyricsOverlayService.PREF_TOPBAR_FONT_SCALE_PERCENT,
                    legacyFontScale
                ),
                wallpaperEnabled = overlayPrefs.getBoolean(
                    LyricsOverlayService.PREF_WALLPAPER_LYRICS_ENABLED,
                    LyricsOverlayService.WALLPAPER_LYRICS_DEFAULT
                ),
                wallpaperFontScale = overlayPrefs.getInt(
                    LyricsOverlayService.PREF_WALLPAPER_FONT_SCALE_PERCENT,
                    legacyFontScale
                ),
                wallpaperBlur = overlayPrefs.getBoolean(
                    LyricsOverlayService.PREF_WALLPAPER_BLUR_ENABLED,
                    LyricsOverlayService.WALLPAPER_BLUR_DEFAULT
                ),
                wallpaperShadow = overlayPrefs.getBoolean(
                    LyricsOverlayService.PREF_WALLPAPER_SHADOW_ENABLED,
                    LyricsOverlayService.WALLPAPER_SHADOW_DEFAULT
                ),
                wallpaperSpacing = WallpaperLyricsSpacing.fromPreference(
                    overlayPrefs.getString(
                        LyricsOverlayService.PREF_WALLPAPER_SPACING,
                        WallpaperLyricsSpacing.STANDARD.preferenceValue
                    )
                ),
                wallpaperFocus = WallpaperLyricsFocus.fromPreference(
                    overlayPrefs.getString(
                        LyricsOverlayService.PREF_WALLPAPER_FOCUS,
                        WallpaperLyricsFocus.CENTER.preferenceValue
                    )
                ),
                wallpaperPosition = WallpaperLyricsPosition.fromPreference(
                    overlayPrefs.getString(
                        LyricsOverlayService.PREF_WALLPAPER_POSITION,
                        WallpaperLyricsPosition.RIGHT.preferenceValue
                    )
                ),
                autoStart = overlayPrefs.getBoolean(
                    LyricsOverlayService.PREF_AUTO_START,
                    LyricsOverlayService.AUTO_START_DEFAULT
                ),
                translationEnabled = overlayPrefs.getBoolean(
                    LyricsOverlayService.PREF_LYRICS_TRANSLATION_ENABLED,
                    LyricsOverlayService.LYRICS_TRANSLATION_DEFAULT
                )
            )
        )
        renderNavigation()
    }

    private fun notifyDisplaySetting(action: String, extras: Intent.() -> Unit) {
        if (!LyricsOverlayService.isRunning) return
        startService(Intent(this, LyricsOverlayService::class.java).apply {
            this.action = action
            extras()
        })
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
            cacheNavigationItem,
            searchNavigationItem,
            commercialNavigationItem,
            aboutNavigationItem
        ).filterNot { it === selectedItem }.forEach(::setNavigationUnselected)
        setNavigationSelected(selectedItem)
        selectedItem.container.isSelected = true
        selectedItem.container.contentDescription = getString(
            R.string.accessibility_navigation_selected,
            selectedItem.label.text
        )
    }

    private fun showSection(section: SettingsSection) {
        selectedSection = section
        commercialRenderer.setSummaryVisibleForSection(section != SettingsSection.COMMERCIAL)
        displayContent.visibility = if (section == SettingsSection.LYRICS) View.VISIBLE else View.GONE
        systemContent.visibility = if (section == SettingsSection.SERVICE) View.VISIBLE else View.GONE
        cacheContent.visibility = if (section == SettingsSection.CACHE) View.VISIBLE else View.GONE
        searchContent.visibility = if (section == SettingsSection.SEARCH) View.VISIBLE else View.GONE
        commercialContent.visibility = if (section == SettingsSection.COMMERCIAL) {
            View.VISIBLE
        } else {
            View.GONE
        }
        aboutContent.visibility = if (section == SettingsSection.ABOUT) View.VISIBLE else View.GONE
        renderNavigation()
        contentScroll.post { contentScroll.scrollTo(0, 0) }
    }

    private fun navigationItem(section: SettingsSection): NavigationItem = when (section) {
        SettingsSection.LYRICS -> displayNavigationItem
        SettingsSection.SERVICE -> systemNavigationItem
        SettingsSection.CACHE -> cacheNavigationItem
        SettingsSection.SEARCH -> searchNavigationItem
        SettingsSection.COMMERCIAL -> commercialNavigationItem
        SettingsSection.ABOUT -> aboutNavigationItem
    }

    private fun renderAboutContent() {
        aboutVersionValue.text = BuildConfig.VERSION_NAME
        aboutTermsQr.setImageBitmap(
            TermsQrCodeGenerator.createBitmap(
                BuildConfig.USER_AGREEMENT_URL,
                resources.getDimensionPixelSize(R.dimen.about_terms_qr_size)
            )
        )
        aboutTermsQr.contentDescription = getString(R.string.accessibility_about_terms_qr)
    }

    private fun navigationItem(containerId: Int, iconId: Int, labelId: Int) = NavigationItem(
        container = findViewById(containerId),
        icon = findViewById(iconId),
        label = findViewById(labelId)
    )

    private fun hasNotificationListenerAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun setServiceRunning(enabled: Boolean) {
        if (!enabled) {
            if (LyricsOverlayService.isRunning) {
                startService(Intent(this, LyricsOverlayService::class.java).apply {
                    action = LyricsOverlayService.ACTION_STOP
                })
            } else {
                lyricsSettingsRenderer.renderServiceRunning(false)
            }
            return
        }
        if (!hasRequiredLyricsAccess()) {
            lyricsSettingsRenderer.renderServiceRunning(false)
            lyricsSettingsRenderer.showMessageDialog(
                R.string.settings_authorization_dialog_title,
                R.string.settings_service_requires_authorization,
            )
            return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_START
            }
        )
    }

    private fun searchLyrics(track: String, artist: String, album: String) {
        sendRuntimeCommand(Intent(this, LyricsOverlayService::class.java).apply {
            action = LyricsOverlayService.ACTION_SEARCH_MANUAL_LYRICS
            putExtra(LyricsOverlayService.EXTRA_MANUAL_TRACK, track)
            putExtra(LyricsOverlayService.EXTRA_MANUAL_ARTIST, artist)
            putExtra(LyricsOverlayService.EXTRA_MANUAL_ALBUM, album)
        })
    }

    private fun selectManualLyrics(token: String) {
        sendRuntimeCommand(Intent(this, LyricsOverlayService::class.java).apply {
            action = LyricsOverlayService.ACTION_SELECT_MANUAL_LYRICS
            putExtra(LyricsOverlayService.EXTRA_MANUAL_CANDIDATE_TOKEN, token)
        })
    }

    private fun deleteCurrentLyricsCache() {
        sendRuntimeCommand(Intent(this, LyricsOverlayService::class.java).apply {
            action = LyricsOverlayService.ACTION_CLEAR_CURRENT_LYRICS_CACHE
        })
    }

    private fun restoreAutomaticLyrics() {
        sendRuntimeCommand(Intent(this, LyricsOverlayService::class.java).apply {
            action = LyricsOverlayService.ACTION_RESTORE_AUTOMATIC_LYRICS
        })
    }

    private fun requestSettingsState() {
        sendRuntimeCommand(Intent(this, LyricsOverlayService::class.java).apply {
            action = LyricsOverlayService.ACTION_REQUEST_SETTINGS_STATE
        })
    }

    private fun sendRuntimeCommand(intent: Intent) {
        if (!hasRequiredLyricsAccess()) return
        if (LyricsOverlayService.isRunning) {
            startService(intent)
        } else {
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private fun hasRequiredLyricsAccess(): Boolean = LyricsStartupPolicy.hasRequiredAccess(
        overlayAccess = Settings.canDrawOverlays(this),
        notificationAccess = hasNotificationListenerAccess()
    )

    private fun restartLyricsOverlay() {
        if (!hasRequiredLyricsAccess()) {
            lyricsSettingsRenderer.showMessageDialog(
                R.string.settings_authorization_dialog_title,
                R.string.settings_restart_requires_authorization,
            )
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
        if (::lyricsSettingsRenderer.isInitialized) {
            lyricsSettingsRenderer.updateAccent(
                themePalette.accentColor,
                themePalette.accentTextColor
            )
            updateOptions()
        }
    }

    private fun registerSettingsReceiver() {
        if (settingsReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            settingsReceiver,
            IntentFilter().apply {
                addAction(LyricsOverlayService.ACTION_STATE_CHANGED)
                addAction(LyricsOverlayService.ACTION_SETTINGS_STATE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        settingsReceiverRegistered = true
    }

    private fun unregisterSettingsReceiver() {
        if (!settingsReceiverRegistered) return
        runCatching { unregisterReceiver(settingsReceiver) }
        settingsReceiverRegistered = false
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
        if (!hasRequiredLyricsAccess()) return
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
    }

    private enum class SettingsSection {
        LYRICS,
        SERVICE,
        CACHE,
        SEARCH,
        COMMERCIAL,
        ABOUT;

        companion object {
            fun from(value: String?): SettingsSection = entries.firstOrNull { it.name == value }
                ?: LYRICS
        }
    }

    private data class NavigationItem(
        val container: View,
        val icon: ImageView,
        val label: TextView
    )
}
