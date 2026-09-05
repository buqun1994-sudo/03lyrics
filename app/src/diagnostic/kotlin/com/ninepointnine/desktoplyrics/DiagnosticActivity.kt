package com.ninepointnine.desktoplyrics

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only launcher for reproducible 03T evidence collection. */
class DiagnosticActivity : AppCompatActivity() {
    private val scope = MainScope()
    private var job: Job? = null
    private var clusterPresentation: ClusterPresentation? = null
    private lateinit var mediaAccessStatus: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        refreshMediaAccessStatus()
    }

    override fun onDestroy() {
        dismissClusterPresentation()
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onStop() {
        dismissClusterPresentation()
        super.onStop()
    }

    private fun buildContent(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 28)
            setBackgroundColor(Color.rgb(245, 247, 250))
        }
        root.addView(TextView(this).apply {
            text = "03歌词诊断"
            textSize = 26f
            setTextColor(Color.rgb(20, 28, 38))
        }, fullWidth(64))
        root.addView(TextView(this).apply {
            text = "用于 03T：媒体会话、公开媒体 Browser、03投屏能力基座、启动闪退和网络发现取证。请先在待测媒体应用（如车机网易云）播放歌曲。报告写入车机本地目录，由 ADB 取出。"
            textSize = 16f
            setTextColor(Color.rgb(70, 80, 94))
        }, fullWidth(90))
        mediaAccessStatus = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(30, 42, 56))
        }
        root.addView(mediaAccessStatus, fullWidth(48))
        root.addView(actionButton("执行完整诊断") { runFullDiagnostic() }, fullWidth(58))
        root.addView(actionButton("启动 03投屏并采集启动证据") { launchCastAndCollect() }, fullWidth(58))
        root.addView(actionButton("开始 30 秒媒体动态采样（播放待测应用）") { runMediaSampling() }, fullWidth(58))
        root.addView(actionButton("启动仪表屏目标模式") { toggleClusterPresentation() }, fullWidth(58))
        status = TextView(this).apply {
            text = "尚未开始。建议先在待测媒体应用中播放一首歌，再执行完整诊断；随后可做 30 秒动态采样。如需复现闪退，点击启动按钮。"
            textSize = 15f
            setTextColor(Color.rgb(30, 42, 56))
            setPadding(0, 20, 0, 0)
        }
        root.addView(status, fullWidth(ViewGroup.LayoutParams.WRAP_CONTENT))
        return ScrollView(this).apply {
            addView(root)
            isFillViewport = true
        }
    }

    private fun toggleClusterPresentation() {
        if (clusterPresentation != null) {
            dismissClusterPresentation()
            updateStatus("仪表屏目标模式已停止；未再向副屏发送内容。")
            return
        }

        val display = findTargetDisplay()
        if (display == null) {
            updateStatus(
                "未找到符合条件的仪表屏：需要 Display 1、PRESENTATION、1920×384 且处于开启状态。",
            )
            return
        }

        val presentation = ClusterPresentation(this, display)
        presentation.setOnDismissListener {
            if (clusterPresentation === presentation) {
                clusterPresentation = null
                updateStatus("仪表屏目标模式已结束；副屏内容已撤销。")
            }
        }
        runCatching {
            presentation.show()
        }.onSuccess {
            clusterPresentation = presentation
            updateStatus(
                "仪表屏目标模式已启动：Display 1 / HDMI / 1920×384 全屏测试。\n" +
                    "亮色背景覆盖整块副屏；再次点击同一按钮立即停止。",
            )
        }.onFailure { error ->
            presentation.dismiss()
            updateStatus(
                "仪表屏目标模式启动失败：${error.javaClass.simpleName}: ${error.message}",
            )
        }
    }

    private fun dismissClusterPresentation() {
        clusterPresentation?.dismiss()
        clusterPresentation = null
    }

    private fun findTargetDisplay(): Display? {
        val manager = getSystemService(DisplayManager::class.java) ?: return null
        return manager.getDisplays().firstOrNull { display ->
            val mode = display.mode
            display.displayId == TARGET_DISPLAY_ID &&
                display.state == Display.STATE_ON &&
                display.flags and Display.FLAG_PRESENTATION != 0 &&
                mode.physicalWidth == TARGET_DISPLAY_WIDTH &&
                mode.physicalHeight == TARGET_DISPLAY_HEIGHT
        }
    }

    private fun runFullDiagnostic() {
        if (job?.isActive == true) return
        job = scope.launch {
            updateStatus("正在读取设备、03投屏安装包、网络、MediaCodec、Car API 和媒体会话…")
            runCatching { collectReport() }
                .onSuccess { publishReport(it, "完整诊断完成") }
                .onFailure { error ->
                    if (error !is CancellationException) {
                        updateStatus("诊断失败：${error.javaClass.simpleName}: ${error.message}")
                    }
                }
        }
    }

    private fun launchCastAndCollect() {
        if (job?.isActive == true) return
        job = scope.launch {
            val target = withContext(Dispatchers.IO) {
                CastCapabilityDiagnosticCollector(this@DiagnosticActivity)
                    .installedTargetPackages()
                    .firstOrNull()
            }
            if (target == null) {
                updateStatus("未安装 com.ninepointnine.desktopcast 或其 Debug 包。")
                return@launch
            }
            updateStatus("正在启动 $target；请保持当前车机画面，随后读取启动结果…")
            val intent = packageManager.getLaunchIntentForPackage(target)
            if (intent == null) {
                updateStatus("$target 已安装，但系统未提供可启动的 Launcher Activity。")
                return@launch
            }
            runCatching { startActivity(intent) }.onFailure {
                updateStatus("无法启动 $target：${it.javaClass.simpleName}: ${it.message}")
                return@launch
            }
            delay(3_500L)
            val report = collectReport().put(
                "launchReproduction",
                JSONObject()
                    .put("targetPackage", target)
                    .put("waitedMs", 3_500L)
                    .put("launchIntentSent", true),
            )
            publishReport(report, "启动证据采集完成；其它应用的 Java/native 崩溃正文请同时运行 ADB 脚本")
        }
    }

    private fun runMediaSampling() {
        if (job?.isActive == true) return
        job = scope.launch {
            updateStatus("媒体动态采样开始：请在 30 秒内播放、暂停、恢复并切换歌曲…")
            val collector = MediaContractDiagnosticCollector(this@DiagnosticActivity)
            val samples = JSONArray()
            val start = System.currentTimeMillis()
            runCatching {
                while (isActive && System.currentTimeMillis() - start < 30_000L) {
                    samples.put(collector.collectOnce())
                    delay(2_000L)
                }
            }.onSuccess {
                val report = JSONObject()
                    .put("schemaVersion", 1)
                    .put("observedAtEpochMs", start)
                    .put("app", JSONObject().put("packageName", packageName))
                    .put("mediaSampling", JSONObject()
                        .put("durationMs", System.currentTimeMillis() - start)
                        .put("intervalMs", 2_000L)
                        .put("samples", samples))
                publishReport(report, "30 秒媒体动态采样完成")
            }.onFailure { error ->
                if (error !is CancellationException) {
                    updateStatus("动态采样失败：${error.javaClass.simpleName}: ${error.message}")
                }
            }
        }
    }

    private suspend fun collectReport(): JSONObject = withContext(Dispatchers.IO) {
        val cast = CastCapabilityDiagnosticCollector(this@DiagnosticActivity).collect()
        val media = withContext(Dispatchers.Main) {
            MediaContractDiagnosticCollector(this@DiagnosticActivity).collectOnce()
        }
        JSONObject()
            .put("schemaVersion", 1)
            .put("observedAtEpochMs", System.currentTimeMillis())
            .put("app", JSONObject()
                .put("packageName", packageName)
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE))
            .put("castCapability", cast)
            .put("mediaContract", media)
    }

    private fun publishReport(report: JSONObject, message: String) {
        val files = DiagnosticReportWriter.write(this, report)
        val media = report.optJSONObject("mediaContract")
        val sessions = media?.optInt("activeSessionCount", -1) ?: -1
        val castPackages = report.optJSONObject("castCapability")
            ?.optJSONArray("targetPackages")
            ?.length() ?: 0
        updateStatus(
            "$message\n" +
                "目标包记录：$castPackages 个；当前 MediaSession：$sessions 个。\n" +
                "JSON：${files.json.absolutePath}\n" +
                "TXT：${files.text.absolutePath}\n" +
                "闪退正文需同时运行 scripts/collect-desktopcast-diagnostics.mjs。",
        )
    }

    private fun refreshMediaAccessStatus() {
        if (!::mediaAccessStatus.isInitialized) return
        mediaAccessStatus.text = if (hasNotificationAccess()) {
            "媒体读取授权：已开启"
        } else {
            "媒体读取授权：未开启；媒体会话结果将不完整"
        }
    }

    private fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun updateStatus(value: String) {
        if (::status.isInitialized) runOnUiThread { status.text = value }
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setOnClickListener { action() }
    }

    private fun fullWidth(height: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
            bottomMargin = 12
        }

    private class ClusterPresentation(
        context: DiagnosticActivity,
        display: Display,
    ) : android.app.Presentation(context, display) {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            val label = TextView(context).apply {
                text = "03歌词仪表全屏测试\nDISPLAY 1 · HDMI · 1920×384"
                textSize = 44f
                gravity = Gravity.CENTER
                setTextColor(Color.BLACK)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }

            val root = FrameLayout(context).apply {
                setBackgroundColor(TEST_BACKGROUND_COLOR)
                addView(
                    label,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            setContentView(root)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(TEST_BACKGROUND_COLOR))
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                )
                attributes = attributes.apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                    dimAmount = 0f
                }
            }
        }

        override fun onStart() {
            super.onStart()
            window?.setLayout(TARGET_DISPLAY_WIDTH, TARGET_DISPLAY_HEIGHT)
        }
    }

    private companion object {
        const val TARGET_DISPLAY_ID = 1
        const val TARGET_DISPLAY_WIDTH = 1920
        const val TARGET_DISPLAY_HEIGHT = 384
        const val TEST_BACKGROUND_COLOR = 0xff00dcff.toInt()
    }
}
