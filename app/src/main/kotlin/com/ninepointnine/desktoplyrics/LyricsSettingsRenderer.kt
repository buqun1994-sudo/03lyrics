package com.ninepointnine.desktoplyrics

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal data class LyricsSettingsPreferences(
    val topbarLines: Int,
    val topbarFontScale: Int,
    val wallpaperEnabled: Boolean,
    val wallpaperFontScale: Int,
    val wallpaperBlur: Boolean,
    val wallpaperShadow: Boolean,
    val wallpaperSpacing: WallpaperLyricsSpacing,
    val wallpaperFocus: WallpaperLyricsFocus,
    val wallpaperPosition: WallpaperLyricsPosition,
    val autoStart: Boolean,
    val translationEnabled: Boolean
)

internal data class LyricsSettingsActions(
    val onTopbarLinesChanged: (Int) -> Unit,
    val onTopbarFontScaleChanged: (Int) -> Unit,
    val onWallpaperEnabledChanged: (Boolean) -> Unit,
    val onWallpaperFontScaleChanged: (Int) -> Unit,
    val onWallpaperBlurChanged: (Boolean) -> Unit,
    val onWallpaperShadowChanged: (Boolean) -> Unit,
    val onWallpaperSpacingChanged: (WallpaperLyricsSpacing) -> Unit,
    val onWallpaperFocusChanged: (WallpaperLyricsFocus) -> Unit,
    val onWallpaperPositionChanged: (WallpaperLyricsPosition) -> Unit,
    val onAutoStartChanged: (Boolean) -> Unit,
    val onTranslationChanged: (Boolean) -> Unit,
    val onServiceRunningChanged: (Boolean) -> Unit,
    val onRestartService: () -> Unit,
    val onSearch: (String, String, String) -> Unit,
    val onSelectCandidate: (String) -> Unit,
    val onDeleteCurrentCache: () -> Unit,
    val onRestoreAutomatic: () -> Unit
)

internal class LyricsSettingsRenderer(
    private val root: View,
    private val actions: LyricsSettingsActions
) {
    private val context = root.context
    private val currentLineOption: TextView = root.findViewById(R.id.topbar_lines_current)
    private val currentAndNextOption: TextView = root.findViewById(R.id.topbar_lines_current_next)
    private val topbarSizeOptions = listOf(
        root.findViewById<TextView>(R.id.topbar_font_size_small) to FONT_SCALE_SMALL,
        root.findViewById<TextView>(R.id.topbar_font_size_standard) to FONT_SCALE_STANDARD,
        root.findViewById<TextView>(R.id.topbar_font_size_large) to FONT_SCALE_LARGE
    )
    private val wallpaperSizeOptions = listOf(
        root.findViewById<TextView>(R.id.wallpaper_font_size_small) to FONT_SCALE_SMALL,
        root.findViewById<TextView>(R.id.wallpaper_font_size_standard) to FONT_SCALE_STANDARD,
        root.findViewById<TextView>(R.id.wallpaper_font_size_large) to FONT_SCALE_LARGE
    )
    private val spacingOptions = listOf(
        root.findViewById<TextView>(R.id.wallpaper_spacing_dense) to WallpaperLyricsSpacing.DENSE,
        root.findViewById<TextView>(R.id.wallpaper_spacing_standard) to WallpaperLyricsSpacing.STANDARD,
        root.findViewById<TextView>(R.id.wallpaper_spacing_loose) to WallpaperLyricsSpacing.LOOSE
    )
    private val focusOptions = listOf(
        root.findViewById<TextView>(R.id.wallpaper_focus_top) to WallpaperLyricsFocus.TOP,
        root.findViewById<TextView>(R.id.wallpaper_focus_center) to WallpaperLyricsFocus.CENTER
    )
    private val positionOptions = listOf(
        root.findViewById<TextView>(R.id.wallpaper_position_left) to WallpaperLyricsPosition.LEFT,
        root.findViewById<TextView>(R.id.wallpaper_position_right) to WallpaperLyricsPosition.RIGHT
    )

    private val wallpaperSwitch: IcarSwitch = root.findViewById(R.id.wallpaper_lyrics_switch)
    private val wallpaperBlurSwitch: IcarSwitch = root.findViewById(R.id.wallpaper_blur_switch)
    private val wallpaperShadowSwitch: IcarSwitch = root.findViewById(R.id.wallpaper_shadow_switch)
    private val autoStartSwitch: IcarSwitch = root.findViewById(R.id.auto_start_switch)
    private val translationSwitch: IcarSwitch = root.findViewById(R.id.lyrics_translation_switch)
    private val serviceSwitch: IcarSwitch = root.findViewById(R.id.service_running_switch)

    private val cacheUsage: TextView = root.findViewById(R.id.cache_usage_text)
    private val cacheProgress: ProgressBar = root.findViewById(R.id.cache_usage_progress)
    private val cacheCount: TextView = root.findViewById(R.id.cache_count_text)
    private val cacheRemainingEstimate: TextView = root.findViewById(R.id.cache_remaining_estimate_text)
    private val cacheTrack: TextView = root.findViewById(R.id.cache_current_track)
    private val cacheIdentity: TextView = root.findViewById(R.id.cache_current_identity)
    private val cacheSource: TextView = root.findViewById(R.id.cache_current_source)
    private val cacheVersion: TextView = root.findViewById(R.id.cache_current_version)
    private val cacheDetails: TextView = root.findViewById(R.id.cache_current_details)
    private val deleteCurrentAction: View = root.findViewById(R.id.cache_delete_current_action)
    private val restoreAutomaticAction: View = root.findViewById(R.id.cache_restore_automatic_action)

    private val searchTrack: EditText = root.findViewById(R.id.search_track_input)
    private val searchArtist: EditText = root.findViewById(R.id.search_artist_input)
    private val searchAlbum: EditText = root.findViewById(R.id.search_album_input)
    private val searchAction: View = root.findViewById(R.id.search_action)
    private val searchActionIcon: ImageView = root.findViewById(R.id.search_action_icon)
    private val searchActionLabel: TextView = root.findViewById(R.id.search_action_label)
    private val searchStatus: TextView = root.findViewById(R.id.search_status)
    private val searchResults: LinearLayout = root.findViewById(R.id.search_results)
    private val messageDialog: View = root.findViewById(R.id.settings_message_dialog)
    private val messageDialogTitle: TextView = root.findViewById(R.id.settings_message_dialog_title)
    private val messageDialogMessage: TextView = root.findViewById(R.id.settings_message_dialog_message)
    private val messageDialogButton: TextView = root.findViewById(R.id.settings_message_dialog_button)

    private var accentColor = ContextCompat.getColor(context, R.color.settings_accent)
    private var accentTextColor = android.graphics.Color.WHITE
    private var rendering = false
    private var serviceRunning = false
    private var preferences: LyricsSettingsPreferences? = null
    private var runtimeState: LyricsSettingsRuntimeState? = null
    private var populatedRecordingGeneration: Long? = null

    init {
        currentLineOption.setOnClickListener { actions.onTopbarLinesChanged(1) }
        currentAndNextOption.setOnClickListener { actions.onTopbarLinesChanged(2) }
        topbarSizeOptions.forEach { (view, value) ->
            view.setOnClickListener { actions.onTopbarFontScaleChanged(value) }
        }
        wallpaperSizeOptions.forEach { (view, value) ->
            view.setOnClickListener { actions.onWallpaperFontScaleChanged(value) }
        }
        spacingOptions.forEach { (view, value) ->
            view.setOnClickListener { actions.onWallpaperSpacingChanged(value) }
        }
        focusOptions.forEach { (view, value) ->
            view.setOnClickListener { actions.onWallpaperFocusChanged(value) }
        }
        positionOptions.forEach { (view, value) ->
            view.setOnClickListener { actions.onWallpaperPositionChanged(value) }
        }
        bindSwitch(
            root.findViewById(R.id.wallpaper_lyrics_setting),
            wallpaperSwitch,
            actions.onWallpaperEnabledChanged
        )
        bindSwitch(
            root.findViewById(R.id.wallpaper_blur_setting),
            wallpaperBlurSwitch,
            actions.onWallpaperBlurChanged
        )
        bindSwitch(
            root.findViewById(R.id.wallpaper_shadow_setting),
            wallpaperShadowSwitch,
            actions.onWallpaperShadowChanged
        )
        bindSwitch(
            root.findViewById(R.id.auto_start_setting),
            autoStartSwitch,
            actions.onAutoStartChanged
        )
        bindSwitch(
            root.findViewById(R.id.lyrics_translation_setting),
            translationSwitch,
            actions.onTranslationChanged
        )
        bindSwitch(
            root.findViewById(R.id.service_running_setting),
            serviceSwitch,
            actions.onServiceRunningChanged
        )
        root.findViewById<View>(R.id.restart_lyrics_setting).setOnClickListener {
            actions.onRestartService()
        }
        deleteCurrentAction.setOnClickListener { actions.onDeleteCurrentCache() }
        restoreAutomaticAction.setOnClickListener { actions.onRestoreAutomatic() }
        searchAction.setOnClickListener { submitSearch() }
        listOf(searchTrack, searchArtist, searchAlbum).forEach { input ->
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE
                ) {
                    submitSearch()
                    true
                } else {
                    false
                }
            }
        }
        searchTrack.imeOptions = EditorInfo.IME_ACTION_NEXT
        searchArtist.imeOptions = EditorInfo.IME_ACTION_NEXT
        searchAlbum.imeOptions = EditorInfo.IME_ACTION_SEARCH
        messageDialogButton.setOnClickListener { dismissMessageDialog() }
    }

    fun renderPreferences(value: LyricsSettingsPreferences) {
        preferences = value
        rendering = true
        try {
            currentLineOption.renderSelected(value.topbarLines == 1)
            currentAndNextOption.renderSelected(value.topbarLines != 1)
            val topbarScale = scaleOption(value.topbarFontScale)
            topbarSizeOptions.forEach { (view, scale) -> view.renderSelected(scale == topbarScale) }
            val wallpaperScale = scaleOption(value.wallpaperFontScale)
            wallpaperSizeOptions.forEach { (view, scale) ->
                view.renderSelected(scale == wallpaperScale)
            }
            spacingOptions.forEach { (view, spacing) ->
                view.renderSelected(spacing == value.wallpaperSpacing)
            }
            focusOptions.forEach { (view, focus) ->
                view.renderSelected(focus == value.wallpaperFocus)
            }
            positionOptions.forEach { (view, position) ->
                view.renderSelected(position == value.wallpaperPosition)
            }
            renderSwitch(wallpaperSwitch, value.wallpaperEnabled)
            renderSwitch(wallpaperBlurSwitch, value.wallpaperBlur)
            renderSwitch(wallpaperShadowSwitch, value.wallpaperShadow)
            renderSwitch(autoStartSwitch, value.autoStart)
            renderSwitch(translationSwitch, value.translationEnabled)
        } finally {
            rendering = false
        }
    }

    fun renderServiceRunning(running: Boolean) {
        serviceRunning = running
        rendering = true
        try {
            renderSwitch(serviceSwitch, running)
        } finally {
            rendering = false
        }
    }

    fun renderRuntimeState(state: LyricsSettingsRuntimeState) {
        runtimeState = state
        populateSearchInputs(state.playback, state.recordingGeneration)
        renderCache(state)
        renderSearch(state)
    }

    fun updateAccent(color: Int, textColor: Int) {
        accentColor = color
        accentTextColor = textColor
        cacheProgress.progressTintList = ColorStateList.valueOf(color)
        searchAction.backgroundTintList = ColorStateList.valueOf(color)
        searchActionIcon.imageTintList = ColorStateList.valueOf(textColor)
        searchActionLabel.setTextColor(textColor)
        messageDialogButton.backgroundTintList = ColorStateList.valueOf(color)
        messageDialogButton.setTextColor(textColor)
        preferences?.let(::renderPreferences)
        renderServiceRunning(serviceRunning)
    }

    fun showMessageDialog(@StringRes titleResId: Int, @StringRes messageResId: Int) {
        messageDialogTitle.setText(titleResId)
        messageDialogMessage.setText(messageResId)
        messageDialog.visibility = View.VISIBLE
        messageDialog.bringToFront()
        messageDialogButton.post { messageDialogButton.requestFocus() }
    }

    fun dismissMessageDialog(): Boolean {
        if (messageDialog.visibility != View.VISIBLE) return false
        messageDialog.visibility = View.GONE
        return true
    }

    private fun bindSwitch(card: View, control: IcarSwitch, onChanged: (Boolean) -> Unit) {
        card.setOnClickListener { control.isChecked = !control.isChecked }
        control.setOnCheckedChangeListener { _, enabled ->
            if (!rendering) onChanged(enabled)
        }
    }

    private fun renderSwitch(control: IcarSwitch, checked: Boolean) {
        control.accentColor = accentColor
        control.isChecked = checked
    }

    private fun TextView.renderSelected(selected: Boolean) {
        setBackgroundResource(if (selected) R.drawable.bg_settings_segment_selected else 0)
        backgroundTintList = if (selected) ColorStateList.valueOf(accentColor) else null
        setTextColor(
            if (selected) accentTextColor
            else ContextCompat.getColor(context, R.color.settings_text_option)
        )
        typeface = Typeface.create(
            if (selected) "sans-serif-medium" else "sans-serif",
            Typeface.NORMAL
        )
    }

    private fun renderCache(state: LyricsSettingsRuntimeState) {
        val stats = state.cache.stats
        cacheUsage.text = context.getString(
            R.string.settings_cache_usage,
            formatBytes(stats.totalBytes),
            formatBytes(stats.maximumAutomaticBytes)
        )
        cacheCount.text = context.getString(
            R.string.settings_cache_count,
            stats.totalEntries,
            stats.manualEntries
        )
        cacheRemainingEstimate.text = context.getString(
            R.string.settings_cache_remaining_estimate,
            stats.estimatedRemainingTracks
        )
        cacheProgress.progress = if (stats.maximumAutomaticBytes <= 0L) {
            0
        } else {
            (stats.totalBytes * 100.0 / stats.maximumAutomaticBytes)
                .roundToInt()
                .coerceIn(0, 100)
        }
        val playback = state.playback
        val current = state.cache.current
        when {
            playback == null || playback.track.isBlank() -> {
                cacheTrack.setText(R.string.settings_cache_no_track)
                cacheIdentity.text = ""
                cacheSource.visibility = View.GONE
                cacheVersion.visibility = View.GONE
                cacheDetails.visibility = View.GONE
            }
            current == null -> {
                cacheTrack.text = playback.track
                cacheIdentity.text = identityLine(playback.artist, playback.album)
                cacheSource.setText(R.string.settings_cache_not_found)
                cacheSource.visibility = View.VISIBLE
                cacheVersion.visibility = View.GONE
                cacheDetails.visibility = View.GONE
            }
            else -> {
                cacheTrack.text = playback.track
                cacheIdentity.text = identityLine(playback.artist, playback.album)
                cacheSource.text = context.getString(
                    R.string.settings_cache_source,
                    current.result.source.ifBlank { "-" }
                )
                cacheVersion.text = context.getString(
                    R.string.settings_cache_version,
                    candidateVersion(current.result, playback)
                )
                val selection = context.getString(
                    if (current.selection == LyricsCacheSelection.MANUAL) {
                        R.string.settings_cache_manual
                    } else {
                        R.string.settings_cache_automatic
                    }
                )
                val translation = context.getString(
                    if (current.result.translatedLyrics.isNotBlank()) {
                        R.string.settings_cache_with_translation
                    } else {
                        R.string.settings_cache_without_translation
                    }
                )
                cacheDetails.text = context.getString(
                    R.string.settings_cache_details,
                    selection,
                    translation,
                    formatUpdatedAt(current.updatedAtMs)
                )
                cacheSource.visibility = View.VISIBLE
                cacheVersion.visibility = View.VISIBLE
                cacheDetails.visibility = View.VISIBLE
            }
        }
        deleteCurrentAction.setAvailable(current != null)
        restoreAutomaticAction.visibility = if (
            current?.selection == LyricsCacheSelection.MANUAL
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun renderSearch(state: LyricsSettingsRuntimeState) {
        if (state.searchState == LyricsManualSearchState.READY) {
            searchStatus.text = context.getString(
                R.string.settings_search_results,
                state.searchCandidates.size
            )
        } else {
            searchStatus.setText(
                when (state.searchState) {
                LyricsManualSearchState.IDLE -> R.string.settings_search_waiting
                LyricsManualSearchState.SEARCHING -> R.string.settings_search_running
                LyricsManualSearchState.APPLYING -> R.string.settings_search_applying
                LyricsManualSearchState.EMPTY -> R.string.settings_search_empty
                LyricsManualSearchState.ERROR -> R.string.settings_search_error
                LyricsManualSearchState.NO_CURRENT_TRACK -> R.string.settings_search_no_current
                LyricsManualSearchState.READY -> R.string.settings_search_waiting
                }
            )
        }
        val busy = state.searchState == LyricsManualSearchState.SEARCHING ||
            state.searchState == LyricsManualSearchState.APPLYING
        searchAction.isEnabled = !busy
        searchAction.alpha = if (busy) DISABLED_ALPHA else 1f

        searchResults.removeAllViews()
        state.searchCandidates.forEach { candidate ->
            val item = LayoutInflater.from(context).inflate(
                R.layout.item_lyrics_search_result,
                searchResults,
                false
            )
            bindCandidate(item, candidate, busy)
            item.layoutParams = (item.layoutParams as LinearLayout.LayoutParams).apply {
                bottomMargin = context.resources.getDimensionPixelSize(
                    R.dimen.settings_search_result_spacing
                )
            }
            searchResults.addView(item)
        }
    }

    private fun bindCandidate(
        item: View,
        candidate: LyricsManualSearchCandidate,
        busy: Boolean
    ) {
        val value = candidate.snapshot
        val track = value.track.ifBlank {
            context.getString(R.string.settings_search_result_title_unknown)
        }
        val artist = value.artist.ifBlank {
            context.getString(R.string.settings_search_result_artist_unknown)
        }
        val album = value.album.ifBlank {
            context.getString(R.string.settings_search_result_album_unknown)
        }
        item.findViewById<TextView>(R.id.search_result_title).text = track
        item.findViewById<TextView>(R.id.search_result_source_duration).text = context.getString(
            R.string.settings_search_source_duration,
            value.source,
            formatDuration(value.durationMs)
        )
        item.findViewById<TextView>(R.id.search_result_artist_album).text = context.getString(
            R.string.settings_search_artist_album,
            artist,
            album
        )
        item.contentDescription = context.getString(
            R.string.settings_search_result_meta,
            track,
            artist,
            album
        )
        item.isEnabled = !busy
        item.alpha = if (busy) DISABLED_ALPHA else 1f
        item.setOnClickListener {
            if (!busy) actions.onSelectCandidate(candidate.token)
        }
    }

    private fun populateSearchInputs(
        playback: LyricsPlaybackIdentity?,
        recordingGeneration: Long
    ) {
        if (recordingGeneration == populatedRecordingGeneration) return
        populatedRecordingGeneration = recordingGeneration
        searchTrack.setText(playback?.track.orEmpty())
        searchArtist.setText(playback?.artist.orEmpty())
        searchAlbum.setText(playback?.album.orEmpty())
    }

    private fun submitSearch() {
        actions.onSearch(
            searchTrack.text?.toString()?.trim().orEmpty(),
            searchArtist.text?.toString()?.trim().orEmpty(),
            searchAlbum.text?.toString()?.trim().orEmpty()
        )
    }

    private fun View.setAvailable(available: Boolean) {
        isEnabled = available
        alpha = if (available) 1f else DISABLED_ALPHA
    }

    private fun scaleOption(value: Int): Int = when {
        value < (FONT_SCALE_SMALL + FONT_SCALE_STANDARD) / 2 -> FONT_SCALE_SMALL
        value > (FONT_SCALE_STANDARD + FONT_SCALE_LARGE) / 2 -> FONT_SCALE_LARGE
        else -> FONT_SCALE_STANDARD
    }

    private fun identityLine(artist: String, album: String): String = listOf(artist, album)
        .filter(String::isNotBlank)
        .joinToString(" · ")

    private fun candidateVersion(
        result: LyricsResult,
        playback: LyricsPlaybackIdentity
    ): String = listOf(
        result.candidateTrack.ifBlank { playback.track },
        result.candidateArtist.ifBlank { playback.artist },
        result.candidateAlbum.ifBlank { playback.album }
    ).filter(String::isNotBlank).joinToString(" · ")

    private fun formatBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        val megaBytes = safe / (1024.0 * 1024.0)
        return when {
            megaBytes >= 10 -> String.format(Locale.getDefault(), "%.0f MB", megaBytes)
            megaBytes >= 0.1 -> String.format(Locale.getDefault(), "%.1f MB", megaBytes)
            else -> String.format(Locale.getDefault(), "%.0f KB", safe / 1024.0)
        }
    }

    private fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return context.getString(R.string.settings_duration_unknown)
        val seconds = durationMs / 1_000L
        return "%d:%02d".format(Locale.getDefault(), seconds / 60L, seconds % 60L)
    }

    private fun formatUpdatedAt(updatedAtMs: Long): String {
        if (updatedAtMs <= 0L) return "-"
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(updatedAtMs))
    }

    private companion object {
        const val FONT_SCALE_SMALL = 88
        const val FONT_SCALE_STANDARD = 100
        const val FONT_SCALE_LARGE = 108
        const val DISABLED_ALPHA = 0.42f
    }
}
