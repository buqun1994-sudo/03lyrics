package com.tcrrry.desktoplyrics

internal data class IcarThemePalette(
    val accentColor: Int,
    val accentTextColor: Int
)

/** Maps the public iCAR theme key to the same primary shade used by car settings. */
internal object IcarThemeColorPalette {
    const val GLOBAL_THEME_KEY = "com.mb.provider.theme_key"

    private const val DEFAULT = 0
    private const val CYAN = 2
    private const val METAL = 4
    private const val ORANGE = 8
    private const val PINK = 16
    private const val PURPLE = 32
    private const val YELLOW = 64

    fun resolve(themeKey: Int?, nightMode: Boolean): IcarThemePalette {
        val accentColor = when (themeKey) {
            CYAN -> if (nightMode) CYAN_NIGHT else CYAN_DAY
            METAL -> if (nightMode) METAL_NIGHT else METAL_DAY
            ORANGE -> if (nightMode) ORANGE_NIGHT else ORANGE_DAY
            PINK -> if (nightMode) PINK_NIGHT else PINK_DAY
            PURPLE -> PURPLE_PRIMARY
            YELLOW -> if (nightMode) YELLOW_NIGHT else YELLOW_DAY
            else -> DEFAULT_PRIMARY
        }
        return IcarThemePalette(
            accentColor = accentColor,
            accentTextColor = if (isLight(accentColor)) BLACK else WHITE
        )
    }

    private fun isLight(color: Int): Boolean {
        val red = (color shr 16) and 0xff
        val green = (color shr 8) and 0xff
        val blue = color and 0xff
        return red * 299 + green * 587 + blue * 114 >= 150_000
    }

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF16161B.toInt()
    private const val DEFAULT_PRIMARY = 0xFF1A8CFF.toInt()
    private const val CYAN_DAY = 0xFF92B5CD.toInt()
    private const val CYAN_NIGHT = 0xFFB5D3E2.toInt()
    private const val METAL_DAY = 0xFF9F704B.toInt()
    private const val METAL_NIGHT = 0xFFB68A61.toInt()
    private const val ORANGE_DAY = 0xFFFAC813.toInt()
    private const val ORANGE_NIGHT = 0xFFE6B609.toInt()
    private const val PINK_DAY = 0xFFFB86A9.toInt()
    private const val PINK_NIGHT = 0xFFDE5185.toInt()
    private const val PURPLE_PRIMARY = 0xFF5C66BF.toInt()
    private const val YELLOW_DAY = 0xFFFDFD54.toInt()
    private const val YELLOW_NIGHT = 0xFFFDF200.toInt()
}
