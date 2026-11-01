package dev.brahma.launcher

import android.content.res.Configuration
import android.graphics.Color

enum class ThemeMode(val title: String) {
    SYSTEM("Follow phone"), LIGHT("Light"), DARK("Dark");

    fun isDark(configuration: Configuration) = when (this) {
        SYSTEM -> configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        LIGHT -> false
        DARK -> true
    }
}

/** Colours for one layout in one theme. [onInk] and [onAccent] are text drawn on ink or accent fills. */
data class Palette(
    val background: Int,
    val surface: Int,
    val accent: Int,
    val secondary: Int,
    val ink: Int,
    val muted: Int,
    val dark: Boolean,
    /** How surfaces are drawn: shadows, glow and edges. */
    val finish: UiStyle = UiStyle.SOFT
) {
    val onInk get() = background
    val onAccent get() = if (dark) background else Color.WHITE

    /** Screen fill: solid, or a see-through scrim of the background colour over the wallpaper. */
    fun backdrop(wallpaper: Boolean, alpha: Int) = if (wallpaper) (background and 0x00FFFFFF) or (alpha shl 24) else background
}

fun HomeLayout.palette(dark: Boolean) = if (dark) darkPalette else lightPalette

/**
 * Settings and editors use a calm palette so every layout's settings look the same; each finish
 * brings the colours of its reference board.
 */
fun settingsPalette(dark: Boolean, finish: UiStyle = UiStyle.FLAT): Palette {
    fun p(vararg c: Long) = Palette(c[0].toInt(), c[1].toInt(), c[2].toInt(), c[3].toInt(), c[4].toInt(), c[5].toInt(), dark, finish)
    return when (finish) {
        // Ivory and cobalt, raised from the same colour as the background.
        UiStyle.SOFT -> if (dark) p(0xFF1F2228, 0xFF23272E, 0xFF6E9BFF, 0xFF343A45, 0xFFE6E9EF, 0xFFA0A7B3)
            else p(0xFFEDEBE6, 0xFFF1EFEA, 0xFF1F55D6, 0xFFD3CFC6, 0xFF1D2230, 0xFF5B606E)
        // Product styleguide: white cards on a cool grey, cobalt actions.
        UiStyle.LAYERED -> if (dark) p(0xFF0F1115, 0xFF1A1D23, 0xFF6E9BFF, 0xFF333A46, 0xFFE8EBF0, 0xFFA0A8B4)
            else p(0xFFF2F4F7, 0xFFFFFFFF, 0xFF0F4FD9, 0xFFD5DAE3, 0xFF14171C, 0xFF5A616C)
        // White keys, black type and a warm amber light.
        UiStyle.TACTILE -> if (dark) p(0xFF17181B, 0xFF26282D, 0xFFFFB054, 0xFF3A3C42, 0xFFF1F1F2, 0xFFA5A7AD)
            else p(0xFFE9EAEC, 0xFFF8F8F9, 0xFF9A4D00, 0xFFD2D3D7, 0xFF141518, 0xFF5C5E64)
        // Frosted panes with lavender light.
        UiStyle.GLASS -> if (dark) p(0xFF15161D, 0xFF262838, 0xFFB3A4FF, 0xFF3A3C52, 0xFFECEBF5, 0xFFA6A5B8)
            else p(0xFFE9EAF1, 0xFFFFFFFF, 0xFF5E45D6, 0xFFD6D3EC, 0xFF1B1D26, 0xFF5C5F6E)
        // Charcoal and copper.
        UiStyle.CARVED -> if (dark) p(0xFF1C1D20, 0xFF27282C, 0xFFE8773F, 0xFF3A3B40, 0xFFECE6DF, 0xFFA39C94)
            else p(0xFFE2DFDA, 0xFFEBE8E3, 0xFFA2471A, 0xFFC9C4BC, 0xFF1E1B18, 0xFF5E5852)
        UiStyle.FLAT -> if (dark) p(0xFF121417, 0xFF1D2025, 0xFF5CB8E4, 0xFF3A4048, 0xFFE6E9ED, 0xFFA3ABB5)
            else p(0xFFF8F9FA, 0xFFFFFFFF, 0xFF006991, 0xFFC9D1D9, 0xFF181C20, 0xFF545C64)
    }
}
