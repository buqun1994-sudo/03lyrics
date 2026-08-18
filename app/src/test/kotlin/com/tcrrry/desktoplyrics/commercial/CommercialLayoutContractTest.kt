package com.tcrrry.desktoplyrics.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CommercialLayoutContractTest {
    private val appDirectory: File = findAppDirectory()

    @Test
    fun `summary is a neutral offer card with dynamic pricing and no embedded pay action`() {
        val summary = File(
            appDirectory,
            "src/main/res/layout/view_commercial_summary.xml"
        ).readText()

        assertTrue(summary.contains("@drawable/bg_settings_card"))
        assertTrue(summary.contains("@+id/commercial_summary_status"))
        assertTrue(summary.contains("@string/commercial_offer_title"))
        assertTrue(summary.contains("@+id/commercial_summary_original_price"))
        assertTrue(summary.contains("@+id/commercial_summary_discount"))
        assertTrue(summary.contains("@+id/commercial_summary_final_price"))
        assertTrue(summary.contains("@+id/commercial_summary_offer_group"))
        assertTrue(summary.contains("@+id/commercial_summary_offer_heading"))
        assertTrue(summary.contains("android:layout_gravity=\"end\""))
        assertFalse(summary.contains("commercial_summary_marketing"))
        assertFalse(summary.contains("commercial_pay_action"))
        assertFalse(summary.contains("commercial_marketing_art"))
    }

    @Test
    fun `commercial flow has mutually exclusive entitlement order and qr page roots`() {
        val content = File(
            appDirectory,
            "src/main/res/layout/content_settings_commercial.xml"
        ).readText()

        assertTrue(content.contains("@+id/commercial_entitlement_page"))
        assertTrue(content.contains("@+id/commercial_order_page"))
        assertTrue(content.contains("@+id/commercial_qr_page"))
        assertTrue(content.contains("@+id/commercial_order_final_price_value"))
        assertTrue(content.contains("@dimen/commercial_order_label_width"))
        assertTrue(content.contains("android:textAlignment=\"viewEnd\""))
        assertFalse(content.contains("commercial_debug_tools_host"))
    }

    @Test
    fun `entitlement status and recovery feedback use the middle when no offer is present`() {
        val content = File(
            appDirectory,
            "src/main/res/layout/content_settings_commercial.xml"
        ).readText()
        val renderer = File(
            appDirectory,
            "src/main/kotlin/com/tcrrry/desktoplyrics/commercial/CommercialSettingsRenderer.kt"
        ).readText()
        val statusStart = content.indexOf("@+id/commercial_entitlement_status_group")
        val actionsStart = content.indexOf("@+id/commercial_entitlement_actions")
        val recoveryFeedback = content.indexOf("@+id/commercial_restore_status")

        assertTrue(content.contains("@+id/commercial_entitlement_status_slot"))
        assertTrue(content.contains("android:layout_above=\"@id/commercial_entitlement_actions\""))
        assertTrue(content.contains("android:layout_marginTop=\"@dimen/commercial_page_edge_margin\""))
        assertTrue(content.contains("android:paddingBottom=\"@dimen/commercial_status_action_gap\""))
        assertTrue(content.contains("android:layout_alignParentBottom=\"true\""))
        assertTrue(content.contains("android:layout_gravity=\"center\""))
        assertTrue(statusStart >= 0 && recoveryFeedback > statusStart)
        assertTrue(actionsStart > recoveryFeedback)
        assertTrue(renderer.contains("placeEntitlementStatusGroup(hasMarketingContent = canPurchase)"))
        assertTrue(renderer.contains("Gravity.CENTER"))
        assertTrue(renderer.contains("Gravity.TOP or Gravity.CENTER_HORIZONTAL"))
        assertTrue(renderer.contains("ViewGroup.LayoutParams.MATCH_PARENT"))
        assertTrue(renderer.contains("ViewGroup.LayoutParams.WRAP_CONTENT"))
    }

    @Test
    fun `commercial pages remove slogans and keep offer copy price focused`() {
        val content = File(
            appDirectory,
            "src/main/res/layout/content_settings_commercial.xml"
        ).readText()
        val strings = File(appDirectory, "src/main/res/values/strings.xml").readText()

        assertTrue(content.contains("@string/commercial_offer_title"))
        assertTrue(content.contains("@string/commercial_offer_price_label"))
        assertTrue(content.contains("@style/SettingsText.CommercialOfferTitle"))
        assertTrue(content.contains("@string/commercial_offer_original_price_label"))
        assertTrue(content.contains("@+id/commercial_marketing_group"))
        assertTrue(content.contains("@+id/commercial_marketing_heading"))
        assertTrue(content.contains("@+id/commercial_marketing_price_comparison"))
        assertTrue(content.contains("@+id/commercial_entitlement_status_group"))
        assertTrue(content.contains("@+id/commercial_entitlement_actions"))
        assertTrue(content.contains("@+id/commercial_pro_state"))
        assertTrue(content.contains("@+id/commercial_pro_crown"))
        assertTrue(content.contains("@drawable/ic_commercial_pro_crown"))
        assertTrue(content.contains("@+id/commercial_pro_state_title"))
        assertTrue(content.contains("@+id/commercial_pro_state_status"))
        assertTrue(content.contains("android:layout_gravity=\"center\""))
        assertTrue(content.contains("android:textAlignment=\"center\""))
        assertFalse(content.contains("commercial_entitlement_offer_arrow"))
        assertFalse(strings.contains("让每一句歌词"))
        assertFalse(strings.contains("让喜欢的歌词"))
    }

    @Test
    fun `commercial display uses title case Pro without changing protocol words`() {
        val strings = File(appDirectory, "src/main/res/values/strings.xml").readText()

        assertTrue(strings.contains(">Pro</string>"))
        assertTrue(strings.contains("现在购买 Pro"))
        assertTrue(strings.contains("Pro 权益已生效"))
        assertTrue(strings.contains("恢复成功，Pro 已生效"))
        assertFalse(
            Regex("(?<![A-Za-z0-9])PRO(?![A-Za-z0-9])").containsMatchIn(strings)
        )
        assertEquals(
            "03歌词 Pro 永久权益",
            normalizeCommercialDisplayText("03歌词 PRO 永久权益")
        )
        assertEquals(
            "03歌词Pro永久权益",
            normalizeCommercialDisplayText("03歌词PRO永久权益")
        )
        assertEquals(
            "PROTOCOL Pro",
            normalizeCommercialDisplayText("PROTOCOL PRO")
        )
    }

    @Test
    fun `sidebar uses icons and stable four character labels`() {
        val activity = File(appDirectory, "src/main/res/layout/activity_main.xml").readText()
        val strings = File(appDirectory, "src/main/res/values/strings.xml").readText()

        assertTrue(activity.contains("@+id/settings_navigation_display_icon"))
        assertTrue(activity.contains("@+id/settings_navigation_system_icon"))
        assertTrue(activity.contains("@+id/settings_navigation_cache_icon"))
        assertTrue(activity.contains("@+id/settings_navigation_search_icon"))
        assertTrue(activity.contains("@+id/settings_navigation_entitlement_icon"))
        assertTrue(activity.contains("@+id/settings_navigation_about_icon"))
        assertTrue(activity.contains("android:layout_alignBottom=\"@id/settings_title_text\""))
        assertTrue(activity.contains("android:layout_toEndOf=\"@id/settings_title_text\""))
        assertTrue(activity.contains("android:layout_marginBottom=\"8dp\""))
        assertTrue(strings.contains("<string name=\"settings_navigation_display\">歌词设置</string>"))
        assertTrue(strings.contains("<string name=\"settings_navigation_system\">服务状态</string>"))
        assertTrue(strings.contains("<string name=\"settings_navigation_cache\">歌词缓存</string>"))
        assertTrue(strings.contains("<string name=\"settings_navigation_search\">歌词查找</string>"))
        assertTrue(strings.contains("<string name=\"settings_navigation_entitlement\">权益中心</string>"))
        assertTrue(strings.contains("<string name=\"settings_navigation_about\">关于</string>"))
    }

    @Test
    fun `entitlement and order edge groups share the verified page margin`() {
        val content = File(
            appDirectory,
            "src/main/res/layout/content_settings_commercial.xml"
        ).readText()
        val dimens = File(appDirectory, "src/main/res/values/dimens.xml").readText()

        assertTrue(
            Regex("@dimen/commercial_page_edge_margin").findAll(content).count() >= 3
        )
        assertTrue(content.contains("@dimen/commercial_marketing_vertical_offset"))
        assertTrue(content.contains("@dimen/commercial_order_total_padding_horizontal"))
        assertTrue(
            dimens.contains(
                "<dimen name=\"commercial_marketing_vertical_offset\">-18dp</dimen>"
            )
        )
        assertTrue(
            dimens.contains("<dimen name=\"commercial_page_edge_margin\">64dp</dimen>")
        )
    }

    @Test
    fun `commercial section suppresses the duplicated summary card`() {
        val activity = File(
            appDirectory,
            "src/main/kotlin/com/tcrrry/desktoplyrics/MainActivity.kt"
        ).readText()
        val renderer = File(
            appDirectory,
            "src/main/kotlin/com/tcrrry/desktoplyrics/commercial/CommercialSettingsRenderer.kt"
        ).readText()

        assertTrue(
            activity.contains(
                "setSummaryVisibleForSection(section != SettingsSection.COMMERCIAL)"
            )
        )
        assertTrue(renderer.contains("summaryVisibleForSection"))
        assertTrue(renderer.contains("CommercialAdPolicy.isVisible(entitlement)"))
        assertTrue(renderer.contains("R.dimen.commercial_content_padding_top_no_summary"))
        assertTrue(renderer.contains("content.setPaddingRelative"))
    }

    @Test
    fun `pro removes the complete summary container instead of preserving height`() {
        val renderer = File(
            appDirectory,
            "src/main/kotlin/com/tcrrry/desktoplyrics/commercial/CommercialSettingsRenderer.kt"
        ).readText()
        val activity = File(appDirectory, "src/main/res/layout/activity_main.xml").readText()

        assertTrue(renderer.contains("summaryContainer.visibility"))
        assertTrue(renderer.contains("View.GONE"))
        assertTrue(renderer.contains("proState.visibility"))
        assertTrue(renderer.contains("entitlementStatusGroup.visibility"))
        assertTrue(activity.contains("<include layout=\"@layout/view_commercial_summary\" />"))
        assertFalse(activity.contains("commercial_summary_spacer"))
    }

    @Test
    fun `commercial emphasis uses the settings accent entry point`() {
        val renderer = File(
            appDirectory,
            "src/main/kotlin/com/tcrrry/desktoplyrics/commercial/CommercialSettingsRenderer.kt"
        ).readText()
        val colors = File(appDirectory, "src/main/res/values/colors.xml").readText()

        assertTrue(renderer.contains("ColorStateList.valueOf(accentColor)"))
        assertTrue(colors.contains("<color name=\"commercial_action\">@color/settings_accent</color>"))
        assertFalse(colors.contains("#345C66BF"))
    }

    private fun findAppDirectory(): File {
        var current = File(requireNotNull(System.getProperty("user.dir")))
        while (!File(current, "src/main").isDirectory) {
            current = requireNotNull(current.parentFile)
        }
        return current
    }
}
