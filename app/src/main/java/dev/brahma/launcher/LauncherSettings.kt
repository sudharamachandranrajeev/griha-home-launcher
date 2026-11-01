package dev.brahma.launcher

import android.content.SharedPreferences

enum class ClockFormat(val title: String) { SYSTEM("Follow phone"), H12("12-hour"), H24("24-hour") }

/** How far a swipe must travel, in dp, to open or close all apps. A longer swipe is harder to trigger by accident. */
enum class SwipeDistance(val title: String, val dp: Int) {
    SHORT("Short swipe · opens easily", 72), MEDIUM("Medium swipe", 130), LONG("Long swipe · hardest to trigger", 200)
}

/** How all apps opens over home and closes back to it. */
enum class DrawerMotion(val title: String) { SLIDE("Slide up and down"), FADE("Fade"), ZOOM("Zoom"), NONE("None · instant") }

data class LauncherSettings(
    val name: String = "",
    val place: String = "",
    val footer: String = "",
    val sticker: String? = null,
    val layout: HomeLayout = HomeLayout.MONO,
    val shortcuts: List<String> = emptyList(),
    val iconStyle: IconStyle = IconStyle.ORIGINAL,
    val iconShape: IconShape = IconShape.ORIGINAL,
    val iconPlate: IconPlate = IconPlate.NONE,
    /** Package name of the chosen icon pack; null keeps each app's own icon. */
    val iconPack: String? = null,
    val wallpaper: Boolean = false,
    val clock: ClockFormat = ClockFormat.SYSTEM,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val weather: Boolean = false,
    val uiStyle: UiStyle = UiStyle.SOFT,
    val effects: Boolean = true,
    val lockScreen: Boolean = false,
    val swipeDistance: SwipeDistance = SwipeDistance.MEDIUM,
    val drawerMotion: DrawerMotion = DrawerMotion.SLIDE
) {
    companion object { const val MAX_SHORTCUTS = 3 }

    fun iconKit(colors: Palette) = IconKit(iconStyle, iconShape, iconPlate, iconPack, colors)

    /** Colours for settings and editors: the Luxe theme carries its gold everywhere, other layouts use the calm settings colours. */
    fun uiPalette(dark: Boolean) = if (layout == HomeLayout.LUXE) layout.palette(dark) else settingsPalette(dark)
}

// One owner for every persisted launcher value; keys predate this class and must not change.
class SettingsStore(private val preferences: SharedPreferences) {
    fun load() = LauncherSettings(
        name = preferences.getString("name", "").orEmpty(),
        place = preferences.getString("place", "").orEmpty(),
        footer = preferences.getString("footer", "").orEmpty(),
        sticker = preferences.getString("sticker", null),
        layout = HomeLayout.fromPreference(preferences.getString("layout", null)),
        shortcuts = preferences.getString("shortcuts", "").orEmpty().split('\n').filter { it.isNotEmpty() }
            .take(LauncherSettings.MAX_SHORTCUTS),
        // "grey_icons" was the first, on/off form of the icon style.
        iconStyle = IconStyle.entries.find { it.name == preferences.getString("icon_style", null) }
            ?: if (preferences.getBoolean("grey_icons", false)) IconStyle.GREY else IconStyle.ORIGINAL,
        iconShape = IconShape.entries.find { it.name == preferences.getString("icon_shape", null) } ?: IconShape.ORIGINAL,
        iconPlate = IconPlate.entries.find { it.name == preferences.getString("icon_plate", null) } ?: IconPlate.NONE,
        iconPack = preferences.getString("icon_pack", null),
        wallpaper = preferences.getBoolean("show_wallpaper", false),
        clock = ClockFormat.entries.find { it.name == preferences.getString("clock", null) } ?: ClockFormat.SYSTEM,
        theme = ThemeMode.entries.find { it.name == preferences.getString("theme", null) } ?: ThemeMode.SYSTEM,
        weather = preferences.getBoolean("weather", false),
        uiStyle = UiStyle.entries.find { it.name == preferences.getString("ui_style", null) } ?: UiStyle.SOFT,
        effects = preferences.getBoolean("effects", true),
        lockScreen = preferences.getBoolean("lock_screen", false),
        swipeDistance = SwipeDistance.entries.find { it.name == preferences.getString("swipe_distance", null) } ?: SwipeDistance.MEDIUM,
        drawerMotion = DrawerMotion.entries.find { it.name == preferences.getString("drawer_motion", null) } ?: DrawerMotion.SLIDE
    )

    fun save(settings: LauncherSettings) {
        preferences.edit()
            .putString("name", settings.name.trim())
            .putString("place", settings.place.trim())
            .putString("footer", settings.footer.trim())
            .putString("sticker", settings.sticker)
            .putString("layout", settings.layout.name)
            .putString("shortcuts", settings.shortcuts.take(LauncherSettings.MAX_SHORTCUTS).joinToString("\n"))
            .putString("icon_style", settings.iconStyle.name)
            .putString("icon_shape", settings.iconShape.name)
            .putString("icon_plate", settings.iconPlate.name)
            .putString("icon_pack", settings.iconPack)
            .remove("grey_icons")
            .putBoolean("show_wallpaper", settings.wallpaper)
            .putString("clock", settings.clock.name)
            .putString("theme", settings.theme.name)
            .putBoolean("weather", settings.weather)
            .putString("ui_style", settings.uiStyle.name)
            .putBoolean("effects", settings.effects)
            .putBoolean("lock_screen", settings.lockScreen)
            .putString("swipe_distance", settings.swipeDistance.name)
            .putString("drawer_motion", settings.drawerMotion.name)
            .apply()
        if (!settings.lockScreen) saveLockKey(null)
        if (!settings.weather) clearWeather()
    }

    fun favorites(): Set<String> = preferences.getStringSet("favorites", emptySet()).orEmpty()

    fun saveFavorites(ids: Set<String>) = preferences.edit().putStringSet("favorites", ids).apply()

    /** The sticker library, in the order stickers were added. */
    fun stickers(): List<Sticker> = StickerJson.decode(preferences.getString("stickers", null))

    fun saveStickers(stickers: List<Sticker>) = preferences.edit().putString("stickers", StickerJson.encode(stickers)).apply()

    fun lockDesign(): LockDesign = LockDesign.decode(preferences.getString("lock_design", null))

    fun saveLockDesign(design: LockDesign) = preferences.edit().putString("lock_design", design.encode()).apply()

    /** What the current lock wallpaper was drawn from; null when Griha has not set one. */
    fun lockKey(): String? = preferences.getString("lock_key", null)

    fun saveLockKey(key: String?) = preferences.edit().putString("lock_key", key).apply()

    fun weather(): WeatherNow? {
        if (!preferences.contains("weather_at")) return null
        return WeatherNow(
            preferences.getString("weather_place", "").orEmpty(),
            preferences.getFloat("weather_temperature", 0f).toDouble(),
            preferences.getString("weather_unit", "°C").orEmpty(),
            preferences.getInt("weather_code", 0),
            preferences.getLong("weather_at", 0L)
        )
    }

    fun saveWeather(weather: WeatherNow) {
        preferences.edit()
            .putString("weather_place", weather.place)
            .putFloat("weather_temperature", weather.temperature.toFloat())
            .putString("weather_unit", weather.unit)
            .putInt("weather_code", weather.code)
            .putLong("weather_at", weather.fetchedAt)
            .apply()
    }

    private fun clearWeather() {
        preferences.edit().remove("weather_place").remove("weather_temperature").remove("weather_unit")
            .remove("weather_code").remove("weather_at").apply()
    }
}
