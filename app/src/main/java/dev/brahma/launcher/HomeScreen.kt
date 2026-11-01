package dev.brahma.launcher

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

// Layout changes presentation only; installed apps, favorites and settings remain Activity-owned.
// Palette order: background, surface, accent, secondary, ink, muted, dark.
enum class HomeLayout(val title: String, val lightPalette: Palette, val darkPalette: Palette) {
    MONO("Mono · default",
        Palette(0xFFD6D6D3.toInt(), 0xFFE6E6E3.toInt(), 0xFF111111.toInt(), 0xFF2A2A2A.toInt(), 0xFF111111.toInt(), 0xFF3A3A3A.toInt(), false),
        Palette(0xFF121212.toInt(), 0xFF1E1E1E.toInt(), 0xFFEDEDED.toInt(), 0xFFBDBDBD.toInt(), 0xFFEDEDED.toInt(), 0xFFA8A8A8.toInt(), true)),
    EDITORIAL("Editorial · blue",
        Palette(0xFFFFFFFF.toInt(), 0xFFF2F6FA.toInt(), 0xFF1B8DC4.toInt(), 0xFF6F93B8.toInt(), 0xFF181C20.toInt(), 0xFF545C64.toInt(), false),
        Palette(0xFF0E1620.toInt(), 0xFF17222E.toInt(), 0xFF4FB3E8.toInt(), 0xFF5E83A8.toInt(), 0xFFE8EEF4.toInt(), 0xFF9FB0C0.toInt(), true)),
    CARDS("Soft cards",
        Palette(0xFFEDEFF2.toInt(), 0xFFF9FAFB.toInt(), 0xFF1F5FD1.toInt(), 0xFF0B1020.toInt(), 0xFF181C20.toInt(), 0xFF545C64.toInt(), false),
        Palette(0xFF0F1218.toInt(), 0xFF1A1F29.toInt(), 0xFF6E9BFF.toInt(), 0xFF05070D.toInt(), 0xFFE6EAF2.toInt(), 0xFF9AA3B5.toInt(), true)),
    ARCHES("Arches · grey",
        Palette(0xFFA8ABA7.toInt(), 0xFFB8BBB7.toInt(), 0xFF1E8FD0.toInt(), 0xFF111111.toInt(), 0xFF111111.toInt(), 0xFF2E2E2E.toInt(), false),
        Palette(0xFF1B1C1B.toInt(), 0xFF262826.toInt(), 0xFF4FB3E8.toInt(), 0xFFD8DAD6.toInt(), 0xFFE8EAE6.toInt(), 0xFFA6A9A4.toInt(), true)),
    NIGHT("Night list",
        Palette(0xFFE9E7E2.toInt(), 0xFFF4F2EE.toInt(), 0xFF2B2B2B.toInt(), 0xFF8C8A85.toInt(), 0xFF1C1C1C.toInt(), 0xFF66645F.toInt(), false),
        Palette(0xFF1E2023.toInt(), 0xFF2A2D31.toInt(), 0xFFF2EFE8.toInt(), 0xFF6E7176.toInt(), 0xFFF2EFE8.toInt(), 0xFFA3A6AB.toInt(), true)),
    DIAL("Dial clock",
        Palette(0xFFF1F1F1.toInt(), 0xFFFFFFFF.toInt(), 0xFF111111.toInt(), 0xFF9A9A9A.toInt(), 0xFF111111.toInt(), 0xFF6B6B6B.toInt(), false),
        Palette(0xFF1C1D1F.toInt(), 0xFF26282B.toInt(), 0xFFFFFFFF.toInt(), 0xFF6A6D72.toInt(), 0xFFF5F5F5.toInt(), 0xFF9EA1A6.toInt(), true)),
    SOFT("Soft glass",
        Palette(0xFFE6EAF0.toInt(), 0xFFF1F4F8.toInt(), 0xFF1E7BF2.toInt(), 0xFF2A3550.toInt(), 0xFF1E2430.toInt(), 0xFF5F6878.toInt(), false),
        Palette(0xFF161A21.toInt(), 0xFF1F2530.toInt(), 0xFF3D8BFF.toInt(), 0xFF2A3346.toInt(), 0xFFE6EAF2.toInt(), 0xFF97A0B0.toInt(), true)),
    // Gold on near-black; the light version is deep gold on ivory, so text keeps its contrast.
    LUXE("Dark luxury · gold",
        Palette(0xFFF6F1E7.toInt(), 0xFFFFFCF5.toInt(), 0xFF8A6512.toInt(), 0xFFC9A44A.toInt(), 0xFF1A1610.toInt(), 0xFF5E5545.toInt(), false),
        Palette(0xFF0A0A0A.toInt(), 0xFF15130F.toInt(), 0xFFE8B83A.toInt(), 0xFF8A6A1F.toInt(), 0xFFF3E9D2.toInt(), 0xFFA89F8C.toInt(), true));

    companion object {
        fun fromPreference(value: String?): HomeLayout = entries.find { it.name == value } ?: MONO
    }
}

class HomeActions(
    val onApp: (AppEntry, View?) -> Unit,
    val onAppOptions: (AppEntry) -> Unit,
    val onAllApps: () -> Unit,
    val onSearch: () -> Unit,
    val onChooseFavorites: () -> Unit,
    val onSettings: () -> Unit,
    val onClock: () -> Unit,
    val onCalendar: () -> Unit,
    val onWebSearch: (String) -> Unit,
    val onVoiceSearch: () -> Unit,
    val onArrangeDone: (List<Sticker>) -> Unit,
    val luxe: LuxeHooks
)

fun appIcon(context: Context, app: AppEntry) = try {
    context.packageManager.getActivityIcon(ComponentName.unflattenFromString(app.id)!!)
} catch (_: PackageManager.NameNotFoundException) {
    context.packageManager.defaultActivityIcon
}

// Constructed in code with Activity callbacks; never inflated from XML.
@SuppressLint("ViewConstructor")
class HomeScreen(
    context: Context,
    private val settings: LauncherSettings,
    dark: Boolean,
    private val allApps: List<AppEntry>,
    private val favorites: List<AppEntry>,
    private val shortcuts: List<AppEntry>,
    private val stickers: List<Pair<Sticker, Bitmap>>,
    arranging: Boolean,
    private var weather: WeatherNow?,
    private val actions: HomeActions
) : ScrollView(context) {
    private val style = settings.layout
    private val colors = settings.homePalette(dark)
    private val ink = colors.ink
    private val muted = colors.muted
    private val locale: Locale = resources.configuration.locales[0]
    private val use24Hour = when (settings.clock) {
        ClockFormat.SYSTEM -> DateFormat.is24HourFormat(context)
        ClockFormat.H12 -> false
        ClockFormat.H24 -> true
    }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    // Every time-, battery- or weather-dependent view registers its refresh here.
    private val tickers = mutableListOf<() -> Unit>()
    private var percent: Int? = null
    private var charging = false
    private var searchField: EditText? = null
    private var arranger: StickerLayer? = null
    private val arrangingNow = arranging
    // A theme home with its own bar or tabs needs no All apps and Settings keys at the bottom.
    private var ownNavigation = false

    init {
        isFillViewport = true
        finishBackdrop(colors, if (settings.wallpaper) 0xA0 else null)
        body.setPadding(dp(24), dp(20), dp(24), dp(24))
        // A frame so layouts can draw art behind the content.
        val frame = FrameLayout(context)
        if (style == HomeLayout.ARCHES) {
            frame.addView(LineArt(context, colors.secondary, resources.displayMetrics.heightPixels), FrameLayout.LayoutParams(-1, -1))
        }
        // Stickers sit behind the text and buttons so they never cover them; while being arranged
        // they come to the front to take touches.
        if (stickers.isNotEmpty()) {
            arranger = StickerLayer(context, stickers, style.name, arranging, Effects.enabled(settings), this, colors, actions.onArrangeDone)
            if (!arranging) frame.addView(arranger, FrameLayout.LayoutParams(-1, -1))
        }
        frame.addView(body, FrameLayout.LayoutParams(-1, -2))
        if (arranging) arranger?.let { frame.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        addView(frame)
        // Touch and hold empty home space for settings, as most launchers do.
        body.setOnLongClickListener { actions.onSettings(); true }
        val themeHome = colors.theme?.home?.takeIf { settings.themeHome }
        if (themeHome != null) buildThemeHome(themeHome) else when (style) {
            HomeLayout.MONO -> buildMono()
            HomeLayout.EDITORIAL -> buildEditorial()
            HomeLayout.CARDS -> buildCards()
            HomeLayout.ARCHES -> buildArches()
            HomeLayout.NIGHT -> buildNight()
            HomeLayout.DIAL -> buildDial()
            HomeLayout.SOFT -> buildSoft()
            HomeLayout.LUXE -> buildLuxe()
        }
        if (settings.footer.isNotBlank()) {
            space(20)
            body.addView(context.label(settings.footer, 12f, muted, Typeface.ITALIC).apply { gravity = Gravity.CENTER })
        }
        // Luxe and themes with their own bar or tabs already have the two ways out.
        if (style != HomeLayout.LUXE && !ownNavigation) {
            space(12)
            body.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                fun key(title: String, onTap: () -> Unit) = addView(context.action(title, colors.accent, onTap).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(20), 0, dp(20), 0)
                    depth(colors, dp(24).toFloat(), Role.KEY, border = Color.TRANSPARENT)
                }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(8), dp(4), dp(8), dp(4)) })
                key("All apps", actions.onAllApps)
                key("Settings", actions.onSettings)
            })
        }
    }

    // ---- Swipe up for all apps ----

    // Only once home is scrolled to its end, and only after the distance chosen in settings.
    private val swipe = EdgeSwipe(up = true, distancePx = dp(settings.swipeDistance.dp).toFloat(),
        slopPx = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
        atEdge = { !canScrollVertically(1) }, onSwipe = { actions.onAllApps() })

    private fun swipeUp(event: MotionEvent) = !arrangingNow && swipe.onTouch(event)

    override fun onInterceptTouchEvent(event: MotionEvent) = swipeUp(event) || super.onInterceptTouchEvent(event)

    /** The wallpaper behind home, told about every touch and scroll so it can answer them. */
    var backdrop: TouchBackdrop? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        backdrop?.touch(event, this)
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER)) backdrop?.touch(event, this)
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        val range = (getChildAt(0)?.height ?: 0) - height
        backdrop?.scrolled(if (range > 0) top / range.toFloat() else 0f)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent) = swipeUp(event) || super.onTouchEvent(event)

    // ---- Luxe: gold on near-black, with the optional cards ----

    private fun buildLuxe() {
        searchBar()
        space(10)
        if (favorites.isEmpty()) emptyFavorites() else body.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(context).apply {
                favorites.forEach { app ->
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(6), dp(8), dp(6), dp(8))
                        addView(FrameLayout(context).apply {
                            depth(colors, dp(18).toFloat(), Role.KEY, border = (colors.accent and 0x00FFFFFF) or 0x66000000)
                            addView(icon(app), FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
                        }, LinearLayout.LayoutParams(dp(56), dp(56)))
                        addView(context.label(app.label, 11f, muted).apply {
                            maxLines = 1
                            gravity = Gravity.CENTER
                        }, LinearLayout.LayoutParams(dp(64), -2))
                        contentDescription = app.label
                        setOnClickListener { actions.onApp(app, this) }
                        setOnLongClickListener { actions.onAppOptions(app); true }
                    })
                }
            })
        })
        space(6)
        body.addView(LuxeHome(context, colors, settings.name, use24Hour, actions.luxe, actions.onAllApps, actions.onSettings,
            scrollTo = { view ->
                val box = android.graphics.Rect()
                view.getDrawingRect(box)
                offsetDescendantRectToMyCoords(view, box)
                smoothScrollTo(0, box.top - dp(12))
            },
            registerTicker = { tickers += it }))
    }

    /** Floats the home items in one after another. */
    fun animateIn() = Effects.stagger(body)

    fun updateStatus(percent: Int?, charging: Boolean) {
        this.percent = percent
        this.charging = charging
        tickers.forEach { it() }
    }

    fun updateWeather(weather: WeatherNow?) {
        this.weather = weather
        tickers.forEach { it() }
    }

    /** Ends sticker arranging, keeping the new places; false when not arranging. */
    fun finishArranging(arranging: Boolean): Boolean {
        if (!arranging) return false
        arranger?.finish()
        return true
    }

    /** Clears an in-progress home search; returns false when there was nothing to clear. */
    fun clearSearch(): Boolean {
        val field = searchField ?: return false
        if (field.text.isEmpty()) return false
        field.text.clear()
        return true
    }

    private fun now() = LocalDateTime.now()

    private fun dateText(pattern: String) = now().format(DateTimeFormatter.ofPattern(pattern, locale))

    private fun shownWeather() = weather?.takeIf {
        settings.weather && it.place == settings.place.trim() &&
            System.currentTimeMillis() - it.fetchedAt < Weather.SHOW_MILLIS
    }

    // ---- Mono: grey monochrome reference ----

    private fun buildMono() {
        val top = LinearLayout(context).apply { gravity = Gravity.TOP }
        val intro = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (settings.name.isBlank()) {
            intro.addView(context.label("Hi there", 22f, ink, Typeface.BOLD, "serif"))
        } else {
            intro.addView(context.label("Hi, I'm", 22f, ink, Typeface.BOLD, "serif"))
            intro.addView(context.label(settings.name, 30f, ink, family = SCRIPT).apply { setPadding(dp(24), 0, 0, 0) })
        }
        intro.addView(View(context).apply { setBackgroundColor(ink) }, LinearLayout.LayoutParams(-1, dp(1)))
        top.addView(intro, LinearLayout.LayoutParams(0, -2, 1f))
        val dayBlock = context.label("", 34f, colors.onInk, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            depth(colors, dp(4).toFloat(), Role.KEY, ink)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { actions.onCalendar() }
        }
        tickers += { dayBlock.text = dateText(DatePatterns.DAY_PADDED); dayBlock.contentDescription = "Day ${dateText(DatePatterns.DAY)}. Tap to open calendar" }
        top.addView(dayBlock, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(16), 0, 0, 0) })
        body.addView(top)
        space(16)
        val date = context.label("", 16f, ink, Typeface.BOLD).apply { setOnClickListener { actions.onCalendar() } }
        tickers += { date.text = dateText(DatePatterns.MONO_DATE).uppercase(locale) }
        body.addView(date)
        space(4)
        body.addView(sentence(11f, uppercase = true))
        space(16)
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(clock(16f, colors.onInk).apply {
            depth(colors, dp(20).toFloat(), Role.KEY, ink)
            setPadding(dp(18), dp(8), dp(18), dp(8))
        })
        row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        shortcuts.forEach { app ->
            row.addView(shortcutCircle(app), LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(dp(6), 0, 0, 0) })
        }
        body.addView(row)
        space(16)
        body.addView(View(context).apply { setBackgroundColor(ink) }, LinearLayout.LayoutParams(dp(28), dp(3)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        space(6)
        body.addView(batteryLine(10f).apply { gravity = Gravity.CENTER })
        space(12)
        appList(iconSize = 30, textSize = 22f)
    }

    // ---- Editorial: blue reference ----

    private fun buildEditorial() {
        val hello = if (settings.name.isBlank()) "Hello" else "Hello, ${settings.name}"
        body.addView(context.label(hello, 30f, ink, Typeface.BOLD))
        body.addView(context.label("How can I help you?", 13f, muted))
        space(12)
        searchBar()
        space(20)
        val greeting = context.label("", 18f, ink, Typeface.BOLD).apply { gravity = Gravity.CENTER }
        tickers += { greeting.text = HomeText.greeting(now().hour) }
        body.addView(greeting)
        space(4)
        body.addView(sentence(13f, uppercase = false).apply { gravity = Gravity.CENTER })
        if (shortcuts.isNotEmpty()) {
            space(16)
            val pills = LinearLayout(context).apply { gravity = Gravity.CENTER }
            val colors = intArrayOf(0xFFE5322D.toInt(), 0xFF2F5BA8.toInt(), 0xFF3C9BE8.toInt())
            shortcuts.forEachIndexed { index, app ->
                pills.addView(shortcutPill(app, colors[index % colors.size]),
                    LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
            body.addView(pills)
        }
        space(24)
        stripes()
        val banner = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val days = context.label("", 34f, ink, Typeface.BOLD).apply { setOnClickListener { actions.onCalendar() } }
        val number = context.label("", 30f, colors.onAccent, Typeface.BOLD).apply {
            depth(colors, dp(6).toFloat(), Role.PRIMARY)
            setPadding(dp(10), 0, dp(10), 0)
        }
        tickers += {
            days.text = dateText(DatePatterns.BANNER_DAYS).uppercase(locale)
            number.text = dateText(DatePatterns.BANNER_NUMBER)
            days.contentDescription = "${dateText(DatePatterns.SPOKEN_DATE)}. Tap to open calendar"
        }
        banner.addView(days, LinearLayout.LayoutParams(0, -2, 1f))
        banner.addView(number)
        body.addView(banner)
        stripes()
        space(16)
        body.addView(context.label("APPS", 22f, ink, family = "sans-serif-light"))
        body.addView(View(context).apply { setBackgroundColor(colors.accent) }, LinearLayout.LayoutParams(dp(160), dp(1)))
        space(8)
        appList(iconSize = 26, textSize = 20f)
    }

    // ---- Soft cards: astronaut reference ----

    private fun buildCards() {
        val top = LinearLayout(context)
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val greeting = context.label("", 26f, ink, family = SCRIPT)
        tickers += { greeting.text = HomeText.greetingWithGlyph(now().hour) }
        left.addView(greeting)
        left.addView(clock(52f, ink).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setOnClickListener { actions.onClock() }
        })
        val date = context.label("", 14f, ink).apply {
            minHeight = dp(32)
            setOnClickListener { actions.onCalendar() }
        }
        tickers += { date.text = dateText(DatePatterns.CARDS_DATE) }
        left.addView(date)
        if (settings.name.isNotBlank()) {
            left.addView(context.label(settings.name, 16f, ink, Typeface.BOLD))
            left.addView(context.label("Have a great day!", 11f, muted))
        }
        if (settings.place.isNotBlank()) left.addView(context.label("⌖  ${settings.place}", 13f, ink).apply { setPadding(0, dp(6), 0, 0) })
        top.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(darkCard(), LinearLayout.LayoutParams(dp(112), dp(150)).apply { setMargins(dp(12), dp(8), 0, 0) })
        body.addView(top)
        space(16)
        appTiles()
        space(8)
        weekCard()
        space(12)
        val bottom = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val dial = AnalogClockView(context, colors.secondary, 0xFF4C8DFF.toInt()).apply {
            // The face is drawn with a corner of 0.38 of its radius; the surface matches it.
            depth(colors, dp(28).toFloat(), Role.CARD, colors.secondary)
            setOnClickListener { actions.onClock() }
        }
        tickers += { dial.tick(HomeText.time(now(), use24Hour, locale)) }
        bottom.addView(dial, LinearLayout.LayoutParams(dp(150), dp(150)))
        body.addView(bottom)
        shortcuts.forEach { app ->
            space(10)
            body.addView(widePill(app))
        }
    }

    // ---- Arches: grey line-art reference ----

    private fun buildArches() {
        val header = FrameLayout(context).apply { setOnClickListener { actions.onCalendar() } }
        val month = context.label("", 104f, ink, Typeface.BOLD, "sans-serif-condensed").apply { includeFontPadding = false }
        val weekday = context.label("", 34f, colors.accent, family = SCRIPT).apply { rotation = -8f }
        tickers += {
            month.text = dateText(DatePatterns.MONTH_SHORT).uppercase(locale)
            weekday.text = dateText(DatePatterns.WEEKDAY)
            header.contentDescription = "${dateText(DatePatterns.SPOKEN_DATE)}. Tap to open calendar"
        }
        header.addView(month, FrameLayout.LayoutParams(-2, -2))
        header.addView(weekday, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(56), 0, 0, dp(2))
        })
        body.addView(header)
        if (settings.place.isNotBlank()) body.addView(context.label(settings.place, 15f, ink, Typeface.BOLD))
        val weatherLine = context.label("", 14f, muted)
        tickers += {
            val current = shownWeather()
            weatherLine.text = if (current == null) "" else HomeText.weatherLine(current)
            weatherLine.visibility = if (current == null) GONE else VISIBLE
        }
        body.addView(weatherLine)
        space(20)
        body.addView(context.label(context.getString(R.string.search_home), 15f, colors.accent, Typeface.BOLD).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(52)
            depth(colors, dp(26).toFloat(), Role.KEY, colors.secondary)
            setPadding(dp(22), 0, dp(22), 0)
            contentDescription = context.getString(R.string.search_home)
            setOnClickListener { actions.onSearch() }
        }, LinearLayout.LayoutParams(dp(220), -2))
        space(28)
        body.addView(clock(40f, ink).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        })
        val battery = context.label("", 13f, ink).apply { gravity = Gravity.CENTER }
        tickers += { battery.text = HomeText.batteryShort(percent, charging) }
        body.addView(battery)
        body.addView(context.label(HomeText.affirmation(settings.name), 22f, ink, family = SCRIPT).apply { gravity = Gravity.CENTER })
        space(16)
        appList(iconSize = 28, textSize = 20f)
    }

    // ---- Night list: "It's Friday" reference ----

    private fun buildNight() {
        val greeting = context.label("", 12f, muted, Typeface.BOLD).apply { letterSpacing = 0.2f }
        tickers += { greeting.text = HomeText.greetingFor(now().hour, settings.name).uppercase(locale) }
        body.addView(greeting)
        space(8)
        val day = context.label("", 52f, ink, family = "sans-serif-thin").apply {
            includeFontPadding = false
            setOnClickListener { actions.onCalendar() }
        }
        tickers += {
            day.text = HomeText.itsDay(now(), locale).uppercase(locale)
            day.contentDescription = "${dateText(DatePatterns.SPOKEN_DATE)}. Tap to open calendar"
        }
        body.addView(day)
        val date = context.label("", 14f, muted)
        tickers += { date.text = dateText(DatePatterns.NIGHT_DATE) }
        body.addView(date)
        space(4)
        body.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(clock(14f, ink))
            addView(batteryLine(12f).apply { setPadding(dp(16), 0, 0, 0) })
        })
        space(28)
        if (favorites.isEmpty()) return emptyFavorites()
        favorites.forEachIndexed { index, app -> body.addView(timelineRow(app, index == 0, index == favorites.lastIndex)) }
    }

    // A rail with a dot per app joins the list into one timeline.
    private fun timelineRow(app: AppEntry, first: Boolean, last: Boolean) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        val rail = FrameLayout(context)
        rail.addView(View(context).apply { setBackgroundColor(colors.secondary) }, FrameLayout.LayoutParams(dp(1), -1, Gravity.CENTER_HORIZONTAL).apply {
            if (first) topMargin = dp(32)
            if (last) bottomMargin = dp(32)
        })
        rail.addView(View(context).apply { background = rounded(ink, dp(4).toFloat()) },
            FrameLayout.LayoutParams(dp(8), dp(8), Gravity.CENTER))
        addView(rail, LinearLayout.LayoutParams(dp(16), dp(64)))
        addView(icon(app), LinearLayout.LayoutParams(dp(28), dp(28)).apply { setMargins(dp(16), 0, 0, 0) })
        addView(context.label(app.label, 20f, ink, family = "sans-serif-light").apply { setPadding(dp(18), 0, dp(8), 0) })
        contentDescription = app.label
        setOnClickListener { actions.onApp(app, this) }
        setOnLongClickListener { actions.onAppOptions(app); true }
    }

    // ---- Dial clock reference ----

    private fun buildDial() {
        val face = FrameLayout(context)
        val dial = DialClockView(context, ink, muted, use24Hour).apply { setOnClickListener { actions.onClock() } }
        tickers += { dial.tick(HomeText.time(now(), use24Hour, locale)) }
        face.addView(dial, FrameLayout.LayoutParams(-1, -1))
        val side = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setOnClickListener { actions.onCalendar() }
        }
        val date = context.label("", 12f, muted)
        val weekday = context.label("", 14f, ink, Typeface.BOLD)
        tickers += {
            date.text = dateText(DatePatterns.DIAL_DATE)
            weekday.text = dateText(DatePatterns.WEEKDAY)
            side.contentDescription = "${dateText(DatePatterns.SPOKEN_DATE)}. Tap to open calendar"
        }
        side.addView(date)
        side.addView(weekday)
        face.addView(side, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        body.addView(face, LinearLayout.LayoutParams(-1, dp(300)))
        val greeting = context.label("", 18f, ink, family = "sans-serif-light").apply { gravity = Gravity.CENTER }
        tickers += { greeting.text = HomeText.greetingFor(now().hour, settings.name) }
        body.addView(greeting)
        body.addView(sentence(12f, uppercase = false).apply { gravity = Gravity.CENTER })
        space(20)
        if (shortcuts.isNotEmpty()) {
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER }
            shortcuts.forEach { app ->
                row.addView(shortcutCircle(app), LinearLayout.LayoutParams(dp(52), dp(52)).apply { setMargins(dp(10), 0, dp(10), 0) })
            }
            body.addView(row)
            space(8)
        }
        body.addView(batteryLine(11f).apply { gravity = Gravity.CENTER })
        space(16)
        appList(iconSize = 28, textSize = 20f)
    }

    // ---- Theme homes and the glass layout: built from blocks ----

    private fun kit() = HomeKit(context, colors, colors.theme, settings, favorites, shortcuts, actions, use24Hour, locale,
        tick = { tickers += it }, battery = { percent to charging }, weather = { shownWeather() }, icon = { icon(it) },
        clock = { size, colour -> clock(size, colour) })

    private fun buildThemeHome(blocks: org.json.JSONArray) {
        val builder = ThemeHome(kit())
        ownNavigation = builder.hasNavigation(blocks)
        builder.build(blocks, body)
    }

    /**
     * Soft glass, redrawn after the glass components board: a frosted clock card, chips, clear pill
     * keys with coloured rims for favourites, a glass dock and a bottom bar.
     */
    private fun buildSoft() = buildThemeHome(org.json.JSONArray("""[
        {"type":"clockCard","style":"glass","gap":4},
        {"type":"chips"},
        {"type":"apps","style":"glass","count":6},
        {"type":"dock","style":"glass","count":5,"gap":22},
        {"type":"nav"}
    ]"""))

    @Suppress("unused")
    private fun buildSoftClassic() {
        val row = LinearLayout(context)
        row.addView(railView(), LinearLayout.LayoutParams(dp(64), -2).apply { setMargins(0, dp(4), dp(16), dp(4)) })
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        row.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(row)

        val hello = softCard(column)
        val greeting = context.label("", 20f, ink, Typeface.BOLD)
        tickers += { greeting.text = HomeText.greetingFor(now().hour, settings.name) }
        hello.addView(greeting)
        val date = context.label("", 13f, muted).apply { setOnClickListener { actions.onCalendar() } }
        tickers += { date.text = dateText(DatePatterns.MONO_DATE) }
        hello.addView(date)

        val time = softCard(column)
        time.addView(clock(46f, ink).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
        val slider = LevelSlider(context, colors)
        time.addView(slider, LinearLayout.LayoutParams(-1, dp(28)).apply { setMargins(0, dp(8), 0, 0) })
        val level = context.label("", 12f, muted)
        tickers += {
            slider.percent = percent
            level.text = HomeText.batteryShort(percent, charging)
        }
        time.addView(level)
        val weatherLine = context.label("", 13f, ink).apply { setPadding(0, dp(6), 0, 0) }
        tickers += {
            val current = shownWeather()
            weatherLine.text = when {
                current != null -> "${settings.place.trim()} · ${HomeText.weatherLine(current)}"
                else -> settings.place.trim()
            }
            weatherLine.visibility = if (weatherLine.text.isEmpty()) GONE else VISIBLE
        }
        time.addView(weatherLine)

        // Glowing input from the kit; it opens the drawer with search focused.
        column.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(18), 0, dp(18), 0)
            depth(colors, dp(26).toFloat(), Role.FIELD, glow = true)
            addView(context.label(context.getString(R.string.search_home), 15f, muted), LinearLayout.LayoutParams(0, -2, 1f))
            addView(View(context).apply { background = rounded(colors.accent, dp(5).toFloat()) }, LinearLayout.LayoutParams(dp(10), dp(10)))
            contentDescription = context.getString(R.string.search_home)
            setOnClickListener { actions.onSearch() }
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(10)) })

        space(8)
        if (favorites.isEmpty()) return emptyFavorites()
        // Favorites as table rows inside one raised card.
        val table = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            soft(colors, dp(22).toFloat())
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        favorites.forEachIndexed { index, app ->
            if (index > 0) table.addView(View(context).apply { setBackgroundColor(mix(colors.surface, colors.muted, 0.18f)) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(12), 0, dp(12), 0) })
            table.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(12), 0, dp(12), 0)
                background = rounded(Color.TRANSPARENT, dp(16).toFloat())
                addView(icon(app), LinearLayout.LayoutParams(dp(28), dp(28)))
                addView(context.label(app.label, 17f, ink).apply { setPadding(dp(16), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(context.label("›", 20f, muted))
                contentDescription = app.label
                setOnClickListener { actions.onApp(app, this) }
                setOnLongClickListener { actions.onAppOptions(app); true }
            })
        }
        body.addView(table, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(2), 0, dp(2), dp(6)) })
    }

    private fun softCard(parent: LinearLayout) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        soft(colors, dp(22).toFloat())
        setPadding(dp(18), dp(14), dp(18), dp(14))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(10)) })
    }

    // Side icon rail: shortcut apps, then clock, calendar and settings, and a glowing All apps key.
    private fun railView() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        soft(colors, dp(24).toFloat())
        setPadding(dp(8), dp(10), dp(8), dp(10))
        fun key(view: View, size: Int, description: String, glow: Boolean = false, onTap: (View) -> Unit) {
            addView(FrameLayout(context).apply {
                depth(colors, dp(14).toFloat(), if (glow) Role.PRIMARY else Role.KEY)
                addView(view, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
                contentDescription = description
                setOnClickListener(onTap)
            }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(0, dp(6), 0, dp(6)) })
        }
        fun glyph(text: String, color: Int = ink) = context.label(text, 18f, color).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        shortcuts.forEach { app ->
            key(icon(app), dp(24), app.label) { actions.onApp(app, it) }
        }
        key(glyph("◷"), -2, "Clock. Tap to open alarms") { actions.onClock() }
        key(glyph("▦"), -2, "Calendar") { actions.onCalendar() }
        key(glyph("⚙"), -2, "Settings") { actions.onSettings() }
        key(glyph("⊞", colors.onAccent), -2, context.getString(R.string.all_apps), glow = true) { actions.onAllApps() }
    }

    private fun darkCard() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        depth(colors, dp(56).toFloat(), Role.CARD, colors.secondary, glow = true)
        setPadding(dp(10), dp(12), dp(10), dp(12))
        val big = context.label("", 30f, Color.WHITE, Typeface.BOLD).apply { gravity = Gravity.CENTER }
        val small = context.label("", 11f, 0xFFB8C7E6.toInt()).apply { gravity = Gravity.CENTER }
        addView(small)
        addView(big)
        tickers += {
            val current = shownWeather()
            if (current != null) {
                small.text = Weather.describe(current.code)
                big.text = current.rounded()
            } else {
                small.text = dateText(DatePatterns.MONTH).uppercase(locale)
                big.text = dateText(DatePatterns.DAY)
            }
            contentDescription = "${small.text} ${big.text}"
        }
        setOnClickListener { actions.onCalendar() }
    }

    private fun weekCard() {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            depth(colors, dp(28).toFloat())
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setOnClickListener { actions.onCalendar() }
        }
        val strip = LinearLayout(context)
        card.addView(strip)
        var shown: LocalDate? = null
        tickers += {
            val today = LocalDate.now()
            if (today != shown) {
                shown = today
                strip.removeAllViews()
                HomeText.week(today, WeekFields.of(locale).firstDayOfWeek).forEach { day ->
                    strip.addView(dayCell(day, day == today), LinearLayout.LayoutParams(0, -2, 1f))
                }
                card.contentDescription = "${today.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale))}. Tap to open calendar"
            }
        }
        val energy = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(12), 0, 0)
        }
        val glyph = BatteryGlyph(context, ink, colors.accent)
        energy.addView(glyph, LinearLayout.LayoutParams(dp(44), dp(20)))
        val level = context.label("", 13f, ink, Typeface.BOLD).apply { setPadding(dp(10), 0, 0, 0) }
        tickers += {
            glyph.percent = percent
            level.text = when {
                percent == null -> "Energy unavailable"
                charging -> "Energy $percent% · charging"
                else -> "Energy $percent%"
            }
        }
        energy.addView(level)
        card.addView(energy)
        body.addView(card)
    }

    private fun dayCell(day: LocalDate, today: Boolean) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(context.label(day.format(DateTimeFormatter.ofPattern("EEE", locale)).uppercase(locale), 9f, muted))
        addView(context.label(day.dayOfMonth.toString(), 13f, if (today) Color.WHITE else ink, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            if (today) depth(colors, dp(16).toFloat(), Role.KEY, colors.secondary, glow = true)
            else depth(colors, dp(16).toFloat(), Role.FIELD, colors.background, border = Color.TRANSPARENT)
        }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { setMargins(0, dp(4), 0, 0) })
    }

    // ---- Shared components ----

    private fun clock(size: Float, color: Int) = TextClock(context).apply {
        when (settings.clock) {
            ClockFormat.SYSTEM -> { format12Hour = "h:mm a"; format24Hour = "HH:mm" }
            ClockFormat.H12 -> { format12Hour = "h:mm a"; format24Hour = "h:mm a" }
            ClockFormat.H24 -> { format12Hour = "HH:mm"; format24Hour = "HH:mm" }
        }
        textSize = size
        setTextColor(color)
        setOnClickListener { actions.onClock() }
        contentDescription = "Clock. Tap to open alarms"
    }

    private fun sentence(size: Float, uppercase: Boolean) = context.label("", size, muted).apply {
        tickers += {
            val text = HomeText.daySentence(now(), settings.place, shownWeather(), use24Hour, locale)
            this.text = if (uppercase) text.uppercase(locale) else text
            contentDescription = text
        }
    }

    private fun batteryLine(size: Float) = context.label("", size, ink).apply {
        tickers += { text = HomeText.battery(percent, charging) }
    }

    private fun stripes() {
        repeat(2) {
            body.addView(View(context).apply { setBackgroundColor(colors.secondary) },
                LinearLayout.LayoutParams(-1, dp(4)).apply { setMargins(0, dp(4), 0, dp(4)) })
        }
    }

    private fun searchBar() {
        val bar = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            depth(colors, dp(26).toFloat(), Role.FIELD, border = colors.secondary)
            setPadding(dp(16), 0, dp(4), 0)
        }
        val results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val field = EditText(context).apply {
            hint = context.getString(R.string.search_home)
            setTextColor(ink)
            setHintTextColor(muted)
            setSingleLine(true)
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            contentDescription = context.getString(R.string.search_home)
        }
        searchField = field
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = showResults(results, s.toString())
            override fun afterTextChanged(s: Editable?) = Unit
        })
        field.setOnEditorActionListener { _, actionId, _ ->
            val query = field.text.toString().trim()
            if (actionId != EditorInfo.IME_ACTION_SEARCH || query.isEmpty()) return@setOnEditorActionListener false
            val first = AppCatalog.search(allApps, query).firstOrNull()
            if (first != null) actions.onApp(first, field) else actions.onWebSearch(query)
            true
        }
        bar.addView(field, LinearLayout.LayoutParams(0, dp(52), 1f))
        bar.addView(ImageView(context).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = context.getString(R.string.voice_search)
            setOnClickListener { actions.onVoiceSearch() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        body.addView(bar)
        body.addView(results)
    }

    private fun showResults(results: LinearLayout, query: String) {
        results.removeAllViews()
        if (query.isBlank()) return
        AppCatalog.search(allApps, query).take(5).forEach { app -> results.addView(appRow(app, 26, 17f)) }
        results.addView(context.action("Search the web for “${query.trim()}”", colors.accent) { actions.onWebSearch(query.trim()) })
    }

    private fun appList(iconSize: Int, textSize: Float) {
        if (favorites.isEmpty()) return emptyFavorites()
        favorites.forEach { body.addView(appRow(it, iconSize, textSize)) }
    }

    private fun appRow(app: AppEntry, iconSize: Int, textSize: Float) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(60)
        addView(icon(app), LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)))
        addView(context.label(app.label, textSize, ink).apply { setPadding(dp(20), dp(10), dp(8), dp(10)) })
        contentDescription = app.label
        setOnClickListener { actions.onApp(app, this) }
        setOnLongClickListener { actions.onAppOptions(app); true }
    }

    private fun appTiles() {
        if (favorites.isEmpty()) return emptyFavorites()
        favorites.chunked(3).forEachIndexed { line, group ->
            val row = LinearLayout(context)
            group.forEachIndexed { place, app ->
                val tile = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    depth(colors, dp(24).toFloat(), tint = glassTint(line * 3 + place))
                    setPadding(dp(8), dp(16), dp(8), dp(12))
                    addView(icon(app), LinearLayout.LayoutParams(dp(40), dp(40)))
                    addView(context.label(app.label, 12f, ink).apply {
                        gravity = Gravity.CENTER
                        maxLines = 1
                        setPadding(0, dp(8), 0, 0)
                    })
                    contentDescription = app.label
                    setOnClickListener { actions.onApp(app, this) }
                    setOnLongClickListener { actions.onAppOptions(app); true }
                }
                row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(6), dp(7), dp(6), dp(7)) })
            }
            repeat(3 - group.size) { row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            body.addView(row)
        }
    }

    private fun shortcutCircle(app: AppEntry) = FrameLayout(context).apply {
        depth(colors, dp(26).toFloat(), Role.KEY, border = ink)
        addView(icon(app), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        contentDescription = app.label
        setOnClickListener { actions.onApp(app, this) }
        setOnLongClickListener { actions.onAppOptions(app); true }
    }

    private fun shortcutPill(app: AppEntry, color: Int) = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        depth(colors, dp(24).toFloat(), Role.KEY, color)
        setPadding(dp(8), 0, dp(8), 0)
        addView(icon(app), LinearLayout.LayoutParams(dp(20), dp(20)))
        addView(context.label(app.label, 12f, Color.WHITE, Typeface.BOLD).apply {
            maxLines = 1
            setPadding(dp(6), 0, 0, 0)
        })
        contentDescription = app.label
        setOnClickListener { actions.onApp(app, this) }
        setOnLongClickListener { actions.onAppOptions(app); true }
    }

    private fun widePill(app: AppEntry) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        depth(colors, dp(30).toFloat())
        minimumHeight = dp(60)
        setPadding(dp(24), dp(8), dp(16), dp(8))
        addView(context.label(app.label, 15f, ink), LinearLayout.LayoutParams(0, -2, 1f))
        addView(icon(app), LinearLayout.LayoutParams(dp(32), dp(32)))
        contentDescription = app.label
        setOnClickListener { actions.onApp(app, this) }
        setOnLongClickListener { actions.onAppOptions(app); true }
    }

    private fun emptyFavorites() {
        body.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            depth(colors, dp(if (style == HomeLayout.CARDS || style == HomeLayout.SOFT) 24 else 8).toFloat())
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(context.label("Make yourself at home", 22f, ink, Typeface.BOLD))
            addView(context.label("Pick the apps you use most. You can change them any time by touching and holding an app.", 16f, muted).apply {
                setPadding(0, dp(12), 0, dp(12))
            })
            addView(context.action(context.getString(R.string.choose_favorites), colors.accent, actions.onChooseFavorites))
        })
    }

    private fun icon(app: AppEntry) = ImageView(context).apply {
        showIcon(app, settings.iconKit(colors))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun dp(value: Int) = context.dp(value)

    /** The glass finish lights its panes mint, lavender and peach in turn, as on its board. */
    private fun glassTint(index: Int) = if (colors.finish == UiStyle.GLASS) Depth.glassTints[index % Depth.glassTints.size] else null

    private fun space(height: Int) = body.addView(View(context), LinearLayout.LayoutParams(1, dp(height)))
}
