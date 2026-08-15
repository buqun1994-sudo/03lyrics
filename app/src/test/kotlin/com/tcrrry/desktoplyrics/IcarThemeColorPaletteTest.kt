package com.tcrrry.desktoplyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class IcarThemeColorPaletteTest {

    @Test
    fun `purple theme key resolves to the verified iCAR primary color`() {
        val palette = IcarThemeColorPalette.resolve(themeKey = 32, nightMode = false)

        assertEquals(0xFF5C66BF.toInt(), palette.accentColor)
        assertEquals(0xFFFFFFFF.toInt(), palette.accentTextColor)
    }

    @Test
    fun `runtime pink theme key resolves to the verified pink primary color`() {
        val palette = IcarThemeColorPalette.resolve(themeKey = 33, nightMode = true)

        assertEquals(0xFFDE5185.toInt(), palette.accentColor)
        assertEquals(0xFFFFFFFF.toInt(), palette.accentTextColor)
    }

    @Test
    fun `yellow theme uses dark selected text for readability`() {
        val palette = IcarThemeColorPalette.resolve(themeKey = 64, nightMode = false)

        assertEquals(0xFFFDFD54.toInt(), palette.accentColor)
        assertEquals(0xFF16161B.toInt(), palette.accentTextColor)
    }

    @Test
    fun `unknown theme key falls back to the default vehicle blue`() {
        val palette = IcarThemeColorPalette.resolve(themeKey = 999, nightMode = true)

        assertEquals(0xFF1A8CFF.toInt(), palette.accentColor)
    }
}
