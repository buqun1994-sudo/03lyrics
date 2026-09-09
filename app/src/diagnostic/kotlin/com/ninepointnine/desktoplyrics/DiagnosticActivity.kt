package com.ninepointnine.desktoplyrics

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

class DiagnosticActivity : AppCompatActivity() {
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var access: TextView
    private lateinit var location: TextView
    private lateinit var start: Button
    private lateinit var export: Button
    private var latest: File? = null
    private var exportSource: File? = null
    private var exporting = false
    private var message = ""
    private val requestAccess = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (MediaContractDiagnosticCollector.hasAccess(this)) startCapture()
        else { message = getString(R.string.diagnostic_access_missing); render() }
    }
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000L)
        }
    }
    private val saveReport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val source = exportSource
        exportSource = null
        if (uri != null && source != null) {
            exporting = true
            scope.launch {
                message = try {
                    withContext(Dispatchers.IO) {
                        DiagnosticReportWriter.verify(source)
                        val output = requireNotNull(contentResolver.openOutputStream(uri, "wt"))
                        output.use { destination -> source.inputStream().use { it.copyTo(destination) } }
                    }
                    getString(R.string.diagnostic_exported)
                } catch (_: Exception) {
                    getString(R.string.diagnostic_export_failed)
                }
                exporting = false
                render()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportSource = savedInstanceState?.getString("exportSource")?.let(::File)
        setContentView(buildContent())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("exportSource", exportSource?.absolutePath)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    private fun buildContent(): View {
        val wide = resources.configuration.screenWidthDp >= 700
        val root = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@DiagnosticActivity, R.color.settings_content_background_end))
        }
        val side = column().apply { setBackgroundResource(R.drawable.bg_settings_sidebar) }
        side.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_art)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        side.addView(label(getString(R.string.diagnostic_title), R.style.SettingsText_Title))
        access = label("")
        side.addView(access)
        val content = column().apply { setBackgroundResource(R.drawable.bg_settings_content) }
        status = label("", R.style.SettingsText_Title)
        content.addView(status)
        start = button(R.string.diagnostic_start, android.R.drawable.ic_media_play) {
            if (MediaContractDiagnosticCollector.hasAccess(this)) startCapture()
            else runCatching { requestAccess.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                .onFailure { message = getString(R.string.diagnostic_grant_unavailable); render() }
        }
        content.addView(start)
        export = button(R.string.diagnostic_export, android.R.drawable.ic_menu_save) {
            latest?.let { file ->
                exportSource = file
                runCatching { saveReport.launch(file.name) }.onFailure {
                    exportSource = null
                    message = getString(R.string.diagnostic_export_unavailable)
                    render()
                }
            }
        }
        content.addView(export)
        location = label("", R.style.SettingsText_Caption2)
        location.setTextIsSelectable(true)
        content.addView(location)
        if (wide) {
            root.addView(ScrollView(this).apply { addView(side); isFillViewport = true },
                LinearLayout.LayoutParams(dimen(R.dimen.settings_sidebar_width), ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(ScrollView(this).apply { addView(content); isFillViewport = true },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            return root
        }
        root.addView(side)
        root.addView(content)
        return ScrollView(this).apply { addView(root); isFillViewport = true }
    }

    private fun render() {
        val authorized = MediaContractDiagnosticCollector.hasAccess(this)
        val recording = MediaDiagnosticService.recording
        val saving = MediaDiagnosticService.saving
        latest = DiagnosticReportWriter.latest(DiagnosticReportWriter.directory(this))
        access.updateText(getString(if (authorized) R.string.diagnostic_access_granted else R.string.diagnostic_access_missing))
        start.isEnabled = !recording && !saving && !exporting
        export.isEnabled = latest != null && !exporting && !saving && !recording
        export.visibility = if (latest != null && !recording && !saving) View.VISIBLE else View.GONE
        listOf(start, export).forEach { it.alpha = if (it.isEnabled) 1f else 0.4f }
        val remaining = ((MediaDiagnosticService.DURATION_MS -
            (SystemClock.elapsedRealtime() - MediaDiagnosticService.startedAtMs)).coerceAtLeast(0) + 999) / 1_000
        status.updateText(when {
            recording -> getString(R.string.diagnostic_remaining, remaining)
            saving -> getString(R.string.diagnostic_saving)
            MediaDiagnosticService.error.isNotBlank() -> getString(R.string.diagnostic_recording_failed)
            latest != null -> getString(R.string.diagnostic_complete)
            else -> getString(R.string.diagnostic_ready)
        })
        location.updateText(listOfNotNull(
            message.takeIf { it.isNotBlank() },
            latest?.takeIf { !recording && !saving }?.let {
                getString(R.string.diagnostic_local_report, DateFormat.getDateTimeInstance(
                    DateFormat.SHORT, DateFormat.SHORT).format(Date(it.lastModified())))
            }
        ).joinToString("\n\n"))
    }

    private fun TextView.updateText(value: String) {
        if (text.toString() != value) text = value
    }

    private fun startCapture() {
        message = ""
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, MediaDiagnosticService::class.java)
                .setAction(MediaDiagnosticService.ACTION_START))
        }.onFailure { message = getString(R.string.diagnostic_recording_failed) }
        render()
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val padding = dimen(R.dimen.settings_sidebar_padding_horizontal)
        setPadding(padding, padding, padding, padding)
    }

    private fun label(value: String, style: Int = R.style.SettingsText_CompactSecondary) = TextView(this).apply {
        setTextAppearance(style)
        text = value
        setPadding(0, dp(12), 0, dp(16))
    }

    private fun button(title: Int, icon: Int, action: () -> Unit) = Button(this).apply {
        setText(title)
        setTextAppearance(R.style.SettingsText_CompactPrimary)
        isAllCaps = false
        minHeight = dimen(R.dimen.settings_compact_control_height)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setBackgroundResource(R.drawable.bg_commercial_secondary_button)
        val drawable = requireNotNull(ContextCompat.getDrawable(this@DiagnosticActivity, icon)).mutate()
        drawable.setTint(ContextCompat.getColor(this@DiagnosticActivity, R.color.settings_accent))
        drawable.setBounds(0, 0, dp(24), dp(24))
        setCompoundDrawables(drawable, null, null, null)
        compoundDrawablePadding = dp(12)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(12) }
        setOnClickListener { action() }
    }

    private fun dimen(id: Int) = resources.getDimensionPixelSize(id)
    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()
}
