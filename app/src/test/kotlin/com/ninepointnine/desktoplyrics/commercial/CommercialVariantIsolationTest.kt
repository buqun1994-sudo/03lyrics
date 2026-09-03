package com.ninepointnine.desktoplyrics.commercial

import com.ninepointnine.desktoplyrics.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CommercialVariantIsolationTest {
    @Test
    fun `package identity follows the active build variant`() {
        assertEquals(BuildConfig.APPLICATION_ID, DeviceCommerceProductContract.PACKAGE_NAME)
        assertEquals("com.ninepointnine.desktoplyrics", DeviceCommerceProductContract.PRODUCTION_PACKAGE_NAME)
        assertEquals("com.ninepointnine.desktoplyrics.test", DeviceCommerceProductContract.TEST_PACKAGE_NAME)
    }

    @Test
    fun `fixture catalog covers staging production and minimum charge boundaries`() {
        val staging = DebugCommercialFixtureCatalog.staging
        assertEquals(2, staging.listAmount)
        assertEquals(5_000, staging.paymentRatioBps)
        assertEquals(1, staging.calculatedAmount)
        assertEquals(1, staging.finalAmount)
        assertFalse(staging.minimumChargeApplied)

        val production = DebugCommercialFixtureCatalog.production
        assertEquals(4_900, production.listAmount)
        assertEquals(6_000, production.paymentRatioBps)
        assertEquals(2_940, production.calculatedAmount)
        assertEquals(2_940, production.finalAmount)

        val zero = DebugCommercialFixtureCatalog.zeroRatio
        assertEquals(0, zero.calculatedAmount)
        assertEquals(1, zero.finalAmount)
        assertTrue(zero.minimumChargeApplied)
    }

    @Test
    fun `fixture amounts keys codes and qr markers are absent from main and release`() {
        var appDirectory = File(requireNotNull(System.getProperty("user.dir")))
        while (!File(appDirectory, "src/main").isDirectory) {
            appDirectory = requireNotNull(appDirectory.parentFile)
        }
        val productionText = sequenceOf(File(appDirectory, "src/main"), File(appDirectory, "src/release"))
            .flatMap { root -> root.walkTopDown().filter(File::isFile) }
            .filter { file -> file.extension in setOf("kt", "xml", "kts") }
            .joinToString("\n") { file -> file.readText() }

        listOf(
            "¥0.02",
            "¥0.01",
            "¥49.00",
            "¥29.40",
            "icar 03",
            "debug-fixture-key-v2",
            "fixture.03lyrics.invalid"
        ).forEach { marker -> assertFalse(marker, productionText.contains(marker)) }
        assertTrue(
            File(appDirectory, "src/debug").walkTopDown()
                .filter(File::isFile)
                .any { file -> file.readText().contains("fixture.03lyrics.invalid") }
        )
    }

    @Test
    fun `release build enables r8 optimization and resource shrinking`() {
        var appDirectory = File(requireNotNull(System.getProperty("user.dir")))
        while (!File(appDirectory, "src/main").isDirectory) {
            appDirectory = requireNotNull(appDirectory.parentFile)
        }
        val buildScript = File(appDirectory, "build.gradle.kts").readText()

        assertTrue(buildScript.contains("isDebuggable = false"))
        assertTrue(buildScript.contains("isJniDebuggable = false"))
        assertTrue(buildScript.contains("isMinifyEnabled = true"))
        assertTrue(buildScript.contains("isShrinkResources = true"))
        assertTrue(buildScript.contains("proguard-android-optimize.txt"))
    }
}
