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
    val dark: Boolean
) {
    val onInk get() = background
    val onAccent get() = if (dark) background else Color.WHITE

    /** Screen fill: solid, or a see-through scrim of the background colour over the wallpaper. */
    fun backdrop(wallpaper: Boolean, alpha: Int) = if (wallpaper) (background and 0x00FFFFFF) or (alpha shl 24) else background
}

fun HomeLayout.palette(dark: Boolean) = if (dark) darkPalette else lightPalette

// Settings uses a neutral palette so every layout's settings look the same.
fun settingsPalette(dark: Boolean) = if (dark) {
    Palette(0xFF121417.toInt(), 0xFF1D2025.toInt(), 0xFF5CB8E4.toInt(), 0xFF3A4048.toInt(), 0xFFE6E9ED.toInt(), 0xFFA3ABB5.toInt(), true)
} else {
    Palette(0xFFF8F9FA.toInt(), 0xFFFFFFFF.toInt(), 0xFF006991.toInt(), 0xFFC9D1D9.toInt(), 0xFF181C20.toInt(), 0xFF545C64.toInt(), false)
}
