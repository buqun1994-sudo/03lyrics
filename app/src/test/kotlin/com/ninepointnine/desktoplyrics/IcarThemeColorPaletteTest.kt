package com.ninepointnine.desktoplyrics

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
        val palette = IcarThemeColorPalette.resolve(themeKey = 35, nightMode = false)

        assertEquals(0xFFFDFD54.toInt(), palette.accentColor)
        assertEquals(0xFF16161B.toInt(), palette.accentTextColor)
    }

    @Test
    fun `the six vehicle theme keys resolve to the verified palette`() {
        val expected = mapOf(
            0 to 0xFF1A8CFF.toInt(),
            31 to 0xFF92B5CD.toInt(),
            32 to 0xFF5C66BF.toInt(),
            33 to 0xFFFB86A9.toInt(),
            34 to 0xFF9F704B.toInt(),
            35 to 0xFFFDFD54.toInt()
        )

        expected.forEach { (themeKey, color) ->
            assertEquals(
                "theme key $themeKey",
                color,
                IcarThemeColorPalette.resolve(themeKey, nightMode = false).accentColor
            )
        }
    }

    @Test
    fun `unknown theme key falls back to the default vehicle blue`() {
        val palette = IcarThemeColorPalette.resolve(themeKey = 999, nightMode = true)

        assertEquals(0xFF1A8CFF.toInt(), palette.accentColor)
    }
}
