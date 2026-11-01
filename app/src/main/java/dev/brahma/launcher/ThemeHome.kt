package dev.brahma.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** What a theme's home blocks may use from the home screen. */
class HomeKit(
    val context: Context,
    val colors: Palette,
    val theme: ThemeSpec?,
    val settings: LauncherSettings,
    val favorites: List<AppEntry>,
    val shortcuts: List<AppEntry>,
    val actions: HomeActions,
    val use24Hour: Boolean,
    val locale: Locale,
    /** Registers a refresh that runs on every minute, battery and weather change. */
    val tick: (() -> Unit) -> Unit,
    val battery: () -> Pair<Int?, Boolean>,
    val weather: () -> WeatherNow?,
    val icon: (AppEntry) -> View,
    val clock: (Float, Int) -> TextClock
)

/**
 * Builds home from a theme's block list (engine 2). Each block is one piece from the theme's
 * reference board: a clock card, a week pill, tall meters, ring gauges, a well clock, an info
 * panel, a dot list, glossy pills, pill keys, a badge dock, tabs, stat cards, toasts, alerts,
 * chips and a bottom bar. Blocks stack top to bottom; "row" puts blocks side by side.
 */
class ThemeHome(private val kit: HomeKit) {
    private val c = kit.colors
    private val ctx = kit.context
    private val ink = c.ink
    private val muted = c.muted
    private val density = ctx.resources.displayMetrics.density

    /** True when the blocks include their own way to all apps and settings. */
    fun hasNavigation(blocks: JSONArray): Boolean = (0 until blocks.length()).any { i ->
        val block = blocks.optJSONObject(i) ?: return@any false
        block.optString("type") in setOf("nav", "tabs") || (block.optString("type") == "row" && hasNavigation(block.optJSONArray("items") ?: JSONArray()))
    }

    fun build(blocks: JSONArray, into: LinearLayout) {
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            val view = block(block) ?: continue
            into.addView(view, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(block.optInt("gap", 14))
                if (block.has("height")) height = dp(block.getInt("height"))
            })
        }
    }

    private fun block(b: JSONObject): View? = when (b.optString("type")) {
        "row" -> row(b)
        "stack" -> LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; build(b.optJSONArray("items") ?: JSONArray(), this) }
        "infoKeys" -> infoKeys()
        "spacer" -> View(ctx)
        "clockCard" -> clockCard(b)
        "week" -> weekPill()
        "meters" -> meters(b)
        "apps" -> apps(b)
        "rings" -> rings()
        "motto" -> motto(b)
        "panel" -> panel(b)
        "well" -> well()
        "dock" -> dock(b)
        "tabs" -> tabs(b)
        "stats" -> stats()
        "toasts" -> toasts()
        "profile" -> profile()
        "note" -> note()
        "nav" -> nav()
        "headline" -> headline()
        "chips" -> chips()
        "alerts" -> alerts()
        "status" -> statusCard()
        "battery" -> batteryCard()
        "search" -> search()
        "label" -> label(b.optString("text"), b.optDouble("size", 12.0).toFloat(), muted, Typeface.BOLD).apply {
            letterSpacing = 0.14f
            isAllCaps = true
        }
        else -> null
    }

    // ---- Small helpers ----

    private fun dp(v: Int) = (v * density).toInt()
    private fun dpf(v: Float) = v * density

    private fun label(text: String, size: Float, colour: Int = ink, style: Int = Typeface.NORMAL, family: String = "sans-serif") =
        ctx.label(text, size, colour, style, family)

    private fun now() = LocalDateTime.now()
    private fun date(pattern: String) = now().format(DateTimeFormatter.ofPattern(pattern, kit.locale))
    private fun extra(name: String, fallback: Int) = kit.theme?.extra(name, c.dark) ?: fallback

    private fun card(radius: Int = 26, role: Role = Role.CARD, fill: Int? = null, glow: Boolean = false) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        depth(c, dpf(radius.toFloat()), role, fill ?: if (role == Role.PRIMARY) c.accent else c.surface, glow)
    }

    private fun tap(view: View, description: String, action: () -> Unit) = view.apply {
        contentDescription = description
        isClickable = true
        setOnClickListener { action() }
    }

    private fun open(app: AppEntry, view: View) = view.apply {
        contentDescription = app.label
        setOnClickListener { kit.actions.onApp(app, this) }
        setOnLongClickListener { kit.actions.onAppOptions(app); true }
    }

    private fun weatherText(): String? = kit.weather()?.let { "${it.rounded()} ${Weather.describe(it.code)}" }

    private fun batteryText(): String = kit.battery().let { (p, charging) -> HomeText.batteryShort(p, charging) }

    private fun glyph(text: String, size: Float, colour: Int) = label(text, size, colour).apply {
        gravity = Gravity.CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun row(b: JSONObject) = LinearLayout(ctx).apply {
        val items = b.optJSONArray("items") ?: JSONArray()
        val weights = b.optJSONArray("weights")
        gravity = if (b.optString("align") == "top") Gravity.TOP else Gravity.CENTER_VERTICAL
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val view = block(item) ?: continue
            val weight = weights?.optDouble(i, 1.0)?.toFloat() ?: 1f
            addView(view, LinearLayout.LayoutParams(if (weight > 0f) 0 else -2, if (item.has("height")) dp(item.getInt("height")) else -2, weight).apply {
                if (i > 0) marginStart = dp(b.optInt("spacing", 14))
            })
        }
    }

    // ---- Clocks ----

    private fun clockCard(b: JSONObject): View {
        val style = b.optString("style", "chunky")
        val card = card(if (style == "glass") 30 else 32)
        card.setPadding(dp(22), dp(20), dp(22), dp(18))
        val top = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val clock = kit.clock(if (style == "chunky") 40f else 42f, ink).apply {
            typeface = Typeface.create(if (style == "glass") "sans-serif-light" else "sans-serif-black", Typeface.NORMAL)
            includeFontPadding = false
        }
        top.addView(clock, LinearLayout.LayoutParams(0, -2, 1f))
        val weatherView: TextView = when (style) {
            "glass" -> label("", 14f, c.onAccent, Typeface.BOLD).apply {
                setPadding(dp(14), dp(6), dp(14), dp(6))
                depth(c, dpf(18f), Role.PRIMARY)
            }
            "clay" -> glyph("☀", 26f, mix(extra("butter", 0xFFFFE99A.toInt()), Color.BLACK, 0.35f)).apply {
                depth(c, dpf(20f), Role.KEY, extra("butter", 0xFFFFE99A.toInt()))
                minWidth = dp(56)
                minHeight = dp(52)
            }
            else -> label("", 15f, ink, Typeface.BOLD)
        }
        top.addView(weatherView)
        card.addView(top)
        if (style != "clay") kit.tick { weatherView.text = weatherText()?.let { (if (style == "chunky") "☀ " else "") + it } ?: kit.battery().first?.let { "$it%" } ?: "" }
        val place = kit.settings.place.trim()
        if (style == "chunky" && place.isNotEmpty()) card.addView(label(place, 12f, muted).apply { setPadding(0, dp(8), 0, 0) })
        val dateRow = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val dateLabel = label("", if (style == "chunky") 15f else 16f, ink, Typeface.BOLD)
        dateRow.addView(dateLabel, LinearLayout.LayoutParams(0, -2, 1f))
        kit.tick { dateLabel.text = date("EEEE, d MMM") }
        if (style == "chunky") {
            val pill = label("", 15f, ink, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), dp(8))
                depth(c, dpf(22f), Role.PRIMARY, c.accent)
            }
            kit.tick { pill.text = kit.weather()?.rounded() ?: kit.battery().first?.let { "$it%" } ?: "—" }
            dateRow.addView(pill)
        }
        card.addView(dateRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (style == "chunky") 2 else 8) })
        when (style) {
            "glass" -> card.addView(label("", 12f, muted).also { line -> kit.tick { line.text = listOfNotNull(batteryText(), place.ifEmpty { null }).joinToString(" · ") } })
            "clay" -> {
                card.addView(label("", 12f, muted).also { line -> kit.tick { line.text = listOfNotNull(weatherText(), place.ifEmpty { null }).joinToString(" · ") } })
                val bar = BarGauge(ctx, c)
                kit.tick { bar.fraction = (kit.battery().first ?: 0) / 100f }
                card.addView(bar, LinearLayout.LayoutParams(-1, dp(18)).apply { topMargin = dp(14) })
                card.addView(label("", 11f, muted, Typeface.BOLD).also { l -> kit.tick { l.text = batteryText() } })
            }
        }
        if (b.optBoolean("week")) card.addView(weekPill(), LinearLayout.LayoutParams(-1, dp(70)).apply { topMargin = dp(14) })
        return tap(card, "Clock and date. Tap to open alarms") { kit.actions.onClock() }
    }

    private fun weekPill(): View {
        val week = WeekPill(ctx, c, kit.locale)
        kit.tick { week.today = LocalDate.now() }
        return tap(week, "This week. Tap to open calendar") { kit.actions.onCalendar() }
    }

    private fun rings() = FrameLayout(ctx).apply {
        val battery = RingGauge(ctx, c, c.secondary, false)
        addView(battery, FrameLayout.LayoutParams(dp(92), dp(92), Gravity.START or Gravity.TOP).apply { setMargins(dp(8), dp(8), 0, 0) })
        val clock = RingGauge(ctx, c, c.secondary, true)
        addView(clock, FrameLayout.LayoutParams(dp(140), dp(140), Gravity.END or Gravity.TOP).apply { setMargins(0, dp(84), dp(4), 0) })
        kit.tick {
            val (p, charging) = kit.battery()
            battery.fraction = (p ?: 0) / 100f
            battery.big = p?.let { "$it%" } ?: "—"
            battery.contentDescription = HomeText.batteryShort(p, charging)
            val t = LocalTime.now()
            clock.fraction = t.minute / 60f
            clock.small = weatherText()?.let { "- ${Weather.describe(kit.weather()!!.code)} -" } ?: "- ${date("EEE")} -"
            clock.big = "( ${HomeText.time(now(), kit.use24Hour, kit.locale).substringBefore(' ')} )"
            clock.foot = "- ${date("dd.MM.yyyy")} -"
            clock.contentDescription = "Clock, ${HomeText.time(now(), kit.use24Hour, kit.locale)}. Tap to open alarms"
        }
        clock.setOnClickListener { kit.actions.onClock() }
        minimumHeight = dp(232)
    }

    private fun well(): View {
        val face = WellClock(ctx, c)
        kit.tick { face.invalidate(); face.contentDescription = "Clock, ${HomeText.time(now(), kit.use24Hour, kit.locale)}. Tap to open alarms" }
        return FrameLayout(ctx).apply {
            addView(tap(face, "Clock") { kit.actions.onClock() }, FrameLayout.LayoutParams(dp(286), dp(286), Gravity.CENTER))
            minimumHeight = dp(300)
        }
    }

    private fun headline() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(kit.clock(64f, ink).apply { typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); includeFontPadding = false })
        val day = label("", 24f, ink, Typeface.BOLD, "sans-serif-medium")
        val line = label("", 14f, muted)
        kit.tick {
            day.text = date("EEEE, d MMMM")
            line.text = HomeText.greetingFor(now().hour, kit.settings.name)
        }
        addView(day)
        addView(line)
        addView(View(ctx).apply { setBackgroundColor(tone(ink, 0.25f)) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12) })
    }

    // ---- Panels and words ----

    private fun panel(b: JSONObject) = LinearLayout(ctx).apply {
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val number = label("", 46f, c.accent, family = "sans-serif-thin").apply { includeFontPadding = false }
        val month = label("", 15f, ink, Typeface.BOLD).apply { letterSpacing = 0.06f }
        val sentence = label("", 12f, ink, Typeface.BOLD).apply { setPadding(0, dp(8), 0, 0) }
        left.addView(number)
        left.addView(month)
        left.addView(sentence)
        addView(left, LinearLayout.LayoutParams(0, -2, 1.25f))
        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        right.addView(View(ctx).apply { setBackgroundColor(c.accent) }, LinearLayout.LayoutParams(dp(64), dp(2)).apply { bottomMargin = dp(10) })
        val weekday = label("", 28f, ink, family = "sans-serif-thin").apply { isAllCaps = true }
        val part = label("", 24f, ink, family = if (b.optBoolean("script", true)) SCRIPT else "sans-serif-light")
        right.addView(weekday)
        right.addView(part)
        addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        kit.tick {
            number.text = date("dd")
            month.text = date("MMMM").uppercase(kit.locale)
            sentence.text = HomeText.panelLine(now(), kit.weather(), kit.battery().first, kit.battery().second, kit.use24Hour, kit.locale)
            weekday.text = date("EEEE")
            part.text = DayPart.at(now().hour).word.replaceFirstChar { it.uppercase() }
        }
        setPadding(0, dp(6), 0, dp(6))
        tap(this, "Today. Tap to open calendar") { kit.actions.onCalendar() }
    }

    private fun motto(b: JSONObject) = FrameLayout(ctx).apply {
        minimumHeight = dp(300)
        val time = VerticalText(ctx, ink, 12f, true)
        kit.tick { time.text = HomeText.time(now(), kit.use24Hour, kit.locale).uppercase(kit.locale) }
        addView(time, FrameLayout.LayoutParams(dp(20), dp(92), Gravity.END or Gravity.TOP).apply { marginEnd = dp(10) })
        addView(View(ctx).apply { setBackgroundColor(c.secondary) }, FrameLayout.LayoutParams(dp(2), dp(26), Gravity.END or Gravity.TOP).apply { setMargins(0, dp(102), dp(19), 0) })
        val words = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(label(b.optString("word", "home") , 40f, ink, Typeface.BOLD, "sans-serif-black").apply {
                text = android.text.SpannableString(b.optString("word", "home") + b.optString("mark", ";")).apply {
                    setSpan(android.text.style.ForegroundColorSpan(c.secondary), length - 1, length, 0)
                }
            })
            addView(label(b.optString("line", "small steps make a good day"), 10f, muted))
            addView(label(b.optString("accentLine", "bright ones too."), 13f, c.secondary, Typeface.BOLD).apply { paintFlags = paintFlags })
        }
        addView(words, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply { setMargins(0, dp(138), dp(4), 0) })
        val day = VerticalText(ctx, c.secondary, 11f, true)
        kit.tick { day.text = date("EEEE, d MMM").uppercase(kit.locale) }
        addView(day, FrameLayout.LayoutParams(dp(20), dp(150), Gravity.END or Gravity.TOP).apply { setMargins(0, dp(226), dp(8), 0) })
    }

    // ---- Apps ----

    private fun appList(b: JSONObject): List<AppEntry> {
        val from = if (b.optString("from") == "shortcuts") kit.shortcuts else kit.favorites
        return from.drop(b.optInt("skip", 0)).take(b.optInt("count", 12))
    }

    private fun apps(b: JSONObject): View {
        val list = appList(b)
        if (list.isEmpty()) return label("Touch and hold an app in all apps to add it here.", 14f, muted)
        return when (b.optString("style", "tiles")) {
            "wide" -> grid(list, b.optInt("columns", 2)) { app, _ -> wideCard(app) }
            "pills" -> column(list, 12) { app, i -> jellyPill(app, i) }
            "dots" -> column(list, 22) { app, i -> dotRow(app, i) }
            "pillKeys" -> grid(list, 2) { app, i -> pillKey(app, i) }
            "list" -> listCard(list)
            "glass" -> column(list, 12) { app, i -> glassPill(app, i) }
            else -> grid(list, b.optInt("columns", 3)) { app, i -> tile(app, i) }
        }
    }

    private fun column(list: List<AppEntry>, gap: Int, make: (AppEntry, Int) -> View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        list.forEachIndexed { i, app -> addView(make(app, i), LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(gap) }) }
    }

    private fun grid(list: List<AppEntry>, columns: Int, make: (AppEntry, Int) -> View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        list.chunked(columns).forEachIndexed { r, group ->
            addView(LinearLayout(ctx).apply {
                group.forEachIndexed { k, app ->
                    addView(make(app, r * columns + k), LinearLayout.LayoutParams(0, -2, 1f).apply { if (k > 0) marginStart = dp(14) })
                }
                repeat(columns - group.size) { addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(14) }) }
            }, LinearLayout.LayoutParams(-1, -2).apply { if (r > 0) topMargin = dp(14) })
        }
    }

    private val plates: List<Int> by lazy {
        val dots = kit.theme?.dots().orEmpty()
        dots.ifEmpty { listOf(c.accent, c.secondary, mix(c.accent, Color.WHITE, 0.35f), mix(c.secondary, Color.WHITE, 0.35f)) }
    }

    private fun tile(app: AppEntry, i: Int) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(14), dp(6), dp(12))
        depth(c, dpf(24f), Role.KEY)
        addView(FrameLayout(ctx).apply {
            depth(c, dpf(16f), Role.KEY, plates[i % plates.size])
            addView(kit.icon(app), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        addView(label(app.label, 12f, ink, Typeface.BOLD).apply { maxLines = 1; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
        open(app, this)
    }

    private fun wideCard(app: AppEntry) = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(84)
        setPadding(dp(16), dp(10), dp(12), dp(10))
        depth(c, dpf(28f), Role.CARD)
        addView(kit.icon(app), LinearLayout.LayoutParams(dp(38), dp(38)))
        addView(label(app.label, 17f, ink, Typeface.BOLD, "sans-serif-black").apply { maxLines = 1; setPadding(dp(12), 0, 0, 0) })
        open(app, this)
    }

    private fun jellyPill(app: AppEntry, i: Int) = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(dp(20), 0, dp(20), 0)
        val states = kit.theme?.stateColours(c).orEmpty().ifEmpty { listOf(c.accent) }
        val fill = states[i % states.size]
        val strong = ThemeSpec.contrast(Color.WHITE, fill) >= 2.6
        depth(c, dpf(28f), if (strong) Role.PRIMARY else Role.KEY, fill, glow = strong)
        addView(kit.icon(app), LinearLayout.LayoutParams(dp(26), dp(26)))
        // The label takes whichever of white or near-black reads better on this pill's colour.
        val text = if (ThemeSpec.contrast(Color.WHITE, fill) >= ThemeSpec.contrast(0xFF121826.toInt(), fill)) Color.WHITE else 0xFF121826.toInt()
        addView(label(app.label, 17f, text, Typeface.BOLD).apply { setPadding(dp(14), 0, 0, 0) })
        open(app, this)
    }

    private fun glassPill(app: AppEntry, i: Int) = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(58)
        setPadding(dp(18), 0, dp(18), 0)
        depth(c, dpf(29f), Role.CARD, tint = Depth.glassTints[i % Depth.glassTints.size])
        addView(kit.icon(app), LinearLayout.LayoutParams(dp(28), dp(28)))
        addView(label(app.label, 17f, ink, Typeface.BOLD).apply { setPadding(dp(14), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(glyph("›", 22f, muted))
        open(app, this)
    }

    private fun dotRow(app: AppEntry, i: Int) = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(30)
        addView(View(ctx).apply { background = rounded(plates[i % plates.size], dpf(11f)) }, LinearLayout.LayoutParams(dp(22), dp(22)))
        addView(label(app.label, 17f, ink).apply { setPadding(dp(20), 0, 0, 0) })
        open(app, this)
    }

    private fun pillKey(app: AppEntry, i: Int) = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(dp(18), dp(8), dp(14), dp(8))
        depth(c, dpf(32f), Role.KEY, glow = i == 0, extrude = dpf(3f))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(app.label, 16f, ink, Typeface.BOLD).apply { maxLines = 1 })
            addView(label(if (app in kit.shortcuts) "Shortcut" else "Favourite", 11f, muted))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(kit.icon(app), LinearLayout.LayoutParams(dp(24), dp(24)))
        open(app, this)
    }

    private fun listCard(list: List<AppEntry>) = card(22).apply {
        setPadding(dp(8), dp(6), dp(8), dp(6))
        list.forEachIndexed { i, app ->
            if (i > 0) addView(View(ctx).apply { setBackgroundColor(tone(muted, 0.18f)) }, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(14), 0, dp(14), 0) })
            addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(12), 0, dp(10), 0)
                addView(FrameLayout(ctx).apply {
                    depth(c, dpf(12f), Role.FIELD)
                    addView(kit.icon(app), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                }, LinearLayout.LayoutParams(dp(38), dp(38)))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), 0, 0, 0)
                    addView(label(app.label, 16f, ink))
                    addView(label(if (app in kit.shortcuts) "Shortcut" else "Favourite", 11f, muted))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(glyph("›", 22f, muted))
                open(app, this)
            })
        }
    }

    /** Time, battery, weather and date as tactile pill keys, the way the pill-key board shows everything. */
    private fun infoKeys(): View {
        fun key(title: () -> String, sub: () -> String, glyph: String, glow: Boolean, action: () -> Unit) = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(18), dp(8), dp(14), dp(8))
            depth(c, dpf(32f), Role.KEY, glow = glow, extrude = dpf(3f))
            val t = label("", 18f, ink, Typeface.BOLD)
            val s = label("", 11f, muted)
            kit.tick { t.text = title(); s.text = sub() }
            addView(LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; addView(t); addView(s) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(glyph(glyph, 18f, if (glow) c.accent else ink))
            setOnClickListener { action() }
        }
        val keys = listOf(
            key({ HomeText.time(now(), kit.use24Hour, kit.locale) }, { date("EEEE") }, "◷", true) { kit.actions.onClock() },
            key({ kit.battery().first?.let { "$it%" } ?: "—" }, { if (kit.battery().second) "Charging" else "Battery" }, "⚡", false) {},
            key({ kit.weather()?.rounded() ?: date("d") }, { kit.weather()?.let { Weather.describe(it.code) } ?: date("MMMM") }, "☀", false) { kit.actions.onCalendar() },
            key({ "Apps" }, { "All of them" }, "+", false) { kit.actions.onAllApps() })
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            keys.chunked(2).forEachIndexed { r, pair ->
                addView(LinearLayout(ctx).apply {
                    pair.forEachIndexed { k, v -> addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (k > 0) marginStart = dp(14) }) }
                }, LinearLayout.LayoutParams(-1, -2).apply { if (r > 0) topMargin = dp(14) })
            }
        }
    }

    // ---- Docks, bars and tabs ----

    private fun dock(b: JSONObject) = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER
        val style = b.optString("style", "keys")
        val list = (kit.shortcuts + kit.favorites).distinct().take(b.optInt("count", 5))
        if (style == "glass" || style == "keys") {
            setPadding(dp(10), dp(12), dp(10), dp(12))
            depth(c, dpf(30f), Role.CARD)
        }
        list.forEachIndexed { i, app ->
            val key = FrameLayout(ctx).apply {
                when (style) {
                    "badges" -> depth(c, dpf(24f), Role.PRIMARY, c.accent)
                    "steps" -> depth(c, dpf(24f), Role.KEY, glow = i == 0)
                    "jelly" -> depth(c, dpf(26f), if (i == 1) Role.PRIMARY else Role.KEY, glow = i == 1)
                    else -> depth(c, dpf(24f), Role.KEY)
                }
                addView(kit.icon(app), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            }
            if (style == "steps" && i > 0) addView(View(ctx).apply { setBackgroundColor(tone(c.accent, 0.5f)) }, LinearLayout.LayoutParams(0, dp(2), 0.25f))
            val size = if (style == "badges") 44 else 48
            addView(open(app, key), LinearLayout.LayoutParams(dp(size), dp(size)).apply { if (style != "steps") setMargins(dp(4), 0, dp(4), 0) })
        }
        tap(this, "Dock") {}
        isClickable = false
    }

    private fun nav() = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        depth(c, dpf(30f), Role.CARD)
        fun item(icon: String, title: String, selected: Boolean, action: () -> Unit) = addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(56)
            addView(glyph(icon, 20f, if (selected) c.accent else muted))
            addView(label(title, 11f, if (selected) c.accent else muted, Typeface.BOLD).apply { gravity = Gravity.CENTER })
            tap(this, title, action)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        item("⌂", "Home", true) {}
        item("⊞", "Apps", false) { kit.actions.onAllApps() }
        item("⌕", "Search", false) { kit.actions.onSearch() }
        item("⚙", "Settings", false) { kit.actions.onSettings() }
    }

    private fun tabs(b: JSONObject) = LinearLayout(ctx).apply {
        val underline = b.optString("style") == "underline"
        gravity = Gravity.CENTER_VERTICAL
        if (!underline) {
            setPadding(dp(6), dp(6), dp(6), dp(6))
            depth(c, dpf(26f), Role.FIELD)
        }
        fun tab(title: String, selected: Boolean, action: () -> Unit) = addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(44)
            val text = label(title, 14f, if (selected) (if (underline) c.accent else c.onAccent) else muted, Typeface.BOLD).apply { gravity = Gravity.CENTER }
            if (selected && !underline) {
                depth(c, dpf(20f), Role.PRIMARY)
            }
            addView(text)
            if (underline) addView(View(ctx).apply { setBackgroundColor(if (selected) c.accent else Color.TRANSPARENT) }, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(6) })
            tap(this, title, action)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        tab("Today", true) {}
        tab("All apps", false) { kit.actions.onAllApps() }
        tab("Settings", false) { kit.actions.onSettings() }
    }

    // ---- Cards with information ----

    private fun stats() = LinearLayout(ctx).apply {
        fun stat(title: String, value: () -> String, glow: Boolean) = addView(card(20, glow = glow).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(label(title, 11f, muted, Typeface.BOLD).apply { isAllCaps = true; letterSpacing = 0.12f })
            val v = label("", 22f, ink, Typeface.BOLD)
            kit.tick { v.text = value() }
            addView(v)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = if (childCount > 0) dp(12) else 0 })
        stat("Battery", { kit.battery().first?.let { "$it%" } ?: "—" }, true)
        stat("Outside", { kit.weather()?.rounded() ?: "—" }, false)
        stat("Today", { date("d MMM") }, false)
    }

    private fun toasts() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        fun toast(icon: String, text: () -> String) = addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(46)
            setPadding(dp(14), 0, dp(14), 0)
            depth(c, dpf(14f), Role.KEY)
            addView(glyph(icon, 16f, c.accent))
            val t = label("", 13f, ink).apply { setPadding(dp(12), 0, 0, 0); maxLines = 2 }
            kit.tick { t.text = text() }
            addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { if (childCount > 0) topMargin = dp(8) })
        toast("◷") { HomeText.greetingFor(now().hour, kit.settings.name) + " · " + HomeText.time(now(), kit.use24Hour, kit.locale) }
        toast("☀") { weatherText()?.let { "Outside: $it" } ?: date("EEEE d MMMM") }
        toast("⚡") { HomeText.batteryShort(kit.battery().first, kit.battery().second) }
    }

    private fun profile() = card(26).apply {
        setPadding(dp(18), dp(16), dp(18), dp(16))
        addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            val name = kit.settings.name.trim()
            addView(glyph(name.firstOrNull()?.uppercase() ?: "☺", 24f, c.onAccent).apply { depth(c, dpf(30f), Role.PRIMARY) },
                LinearLayout.LayoutParams(dp(60), dp(60)))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, 0, 0)
                val hello = label("", 18f, ink, Typeface.BOLD)
                kit.tick { hello.text = HomeText.greetingFor(now().hour, name) }
                addView(hello)
                addView(label(kit.settings.footer.ifBlank { "Thoughtful tools for a kinder day." }, 13f, muted))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        tap(this, "Settings") { kit.actions.onSettings() }
    }

    private fun note() = card(24).apply {
        setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(glyph("🔔", 20f, c.accent).apply { depth(c, dpf(22f), Role.FIELD) }, LinearLayout.LayoutParams(dp(46), dp(46)))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                val title = label("", 17f, ink, Typeface.BOLD)
                val line = label("", 13f, muted)
                kit.tick {
                    val (p, charging) = kit.battery()
                    title.text = if (p != null && p < 20 && !charging) "Charge soon" else "You're all set!"
                    line.text = HomeText.batteryShort(p, charging)
                }
                addView(title)
                addView(line)
            })
        })
    }

    private fun batteryCard() = card(24).apply {
        setPadding(dp(18), dp(14), dp(18), dp(14))
        addView(label("BATTERY", 11f, muted, Typeface.BOLD).apply { letterSpacing = 0.14f })
        val r = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val bar = BarGauge(ctx, c)
        r.addView(bar, LinearLayout.LayoutParams(0, dp(18), 1f))
        val value = label("", 15f, ink, Typeface.BOLD).apply { setPadding(dp(12), 0, 0, 0) }
        r.addView(value)
        addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val line = label("", 12f, muted)
        addView(line)
        kit.tick {
            val (p, charging) = kit.battery()
            bar.fraction = (p ?: 0) / 100f
            value.text = p?.let { "$it%" } ?: "—"
            line.text = if (charging) "Charging" else "Making progress, one step at a time."
        }
    }

    private fun chips() = LinearLayout(ctx).apply {
        fun chip(text: () -> String, selected: Boolean) = addView(label("", 13f, if (selected) c.onAccent else ink, Typeface.BOLD).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            if (selected) depth(c, dpf(18f), Role.PRIMARY) else depth(c, dpf(18f), Role.KEY, border = tone(ink, 0.25f))
            kit.tick { this.text = text() }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        chip({ weatherText() ?: date("EEE d") }, true)
        chip({ kit.battery().first?.let { "Battery $it%" } ?: "Battery" }, false)
        if (kit.settings.place.isNotBlank()) chip({ kit.settings.place.trim() }, false)
    }

    private fun alerts() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        fun alert(colour: Int, icon: String, text: () -> String) = addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPadding(dp(14), 0, dp(14), 0)
            depth(c, dpf(10f), Role.KEY, mix(c.surface, colour, 0.16f), border = tone(colour, 0.6f))
            addView(glyph(icon, 15f, colour))
            val t = label("", 13f, ink, Typeface.BOLD).apply { setPadding(dp(10), 0, 0, 0) }
            kit.tick { t.text = text() }
            addView(t)
        }, LinearLayout.LayoutParams(-1, -2).apply { if (childCount > 0) topMargin = dp(8) })
        alert(0xFF2E9E5B.toInt(), "✓") { HomeText.batteryShort(kit.battery().first, kit.battery().second) }
        alert(0xFF2F6FE0.toInt(), "i") { weatherText()?.let { "Outside: $it" } ?: date("EEEE d MMMM") }
        alert(0xFFD99A1E.toInt(), "!") { HomeText.greetingFor(now().hour, kit.settings.name) }
    }

    private fun statusCard() = card(22).apply {
        setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Today", 16f, ink, Typeface.BOLD), LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("● Live", 11f, c.onAccent, Typeface.BOLD).apply { setPadding(dp(10), dp(4), dp(10), dp(4)); depth(c, dpf(12f), Role.PRIMARY) })
        })
        fun line(name: String, value: () -> String) = addView(LinearLayout(ctx).apply {
            setPadding(0, dp(10), 0, 0)
            addView(label(name, 13f, muted), LinearLayout.LayoutParams(0, -2, 1f))
            val v = label("", 13f, ink, Typeface.BOLD)
            kit.tick { v.text = value() }
            addView(v)
        })
        line("Battery") { kit.battery().first?.let { "$it%" } ?: "—" }
        line("Outside") { kit.weather()?.rounded() ?: "—" }
        line("Date") { date("dd.MM.yyyy") }
        addView(LinearLayout(ctx).apply {
            setPadding(0, dp(14), 0, 0)
            addView(ctx.action("Settings", ink) { kit.actions.onSettings() }.apply { gravity = Gravity.CENTER; depth(c, dpf(12f), Role.KEY, border = tone(ink, 0.25f)) },
                LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(ctx.action("All apps", c.onAccent) { kit.actions.onAllApps() }.apply { gravity = Gravity.CENTER; depth(c, dpf(12f), Role.PRIMARY) },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(10) })
        })
    }

    private fun search() = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        setPadding(dp(18), 0, dp(18), 0)
        depth(c, dpf(26f), Role.FIELD)
        addView(glyph("⌕", 20f, muted))
        addView(label(ctx.getString(R.string.search_home), 15f, muted).apply { setPadding(dp(10), 0, 0, 0) })
        tap(this, ctx.getString(R.string.search_home)) { kit.actions.onSearch() }
    }

    private fun meters(b: JSONObject) = LinearLayout(ctx).apply {
        val audio = ctx.getSystemService(AudioManager::class.java)
        fun meter(title: String, value: () -> Float, glyph: String?) = addView(card(28).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(12), dp(8), dp(12))
            addView(label(title, 13f, ink, Typeface.BOLD).apply { gravity = Gravity.CENTER })
            val m = MeterView(ctx, c, glyph)
            kit.tick { m.fraction = value() }
            addView(m, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        }, LinearLayout.LayoutParams(0, -1, 1f).apply { if (childCount > 0) marginStart = dp(12) })
        meter("Power", { (kit.battery().first ?: 0) / 100f }, "⚡")
        meter("Music", {
            val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
            if (max > 0) (audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0) / max.toFloat() else 0f
        }, null)
        minimumHeight = dp(b.optInt("height", 210))
    }
}

// ---- Drawn widgets ----

/** A pressed-in track with an accent fill, like a battery bar. */
@SuppressLint("ViewConstructor")
class BarGauge(context: Context, colors: Palette) : View(context) {
    var fraction = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    private val density = resources.displayMetrics.density
    private val track = colors.depthDrawable(density, 100 * density, Role.FIELD)
    private val fill = colors.depthDrawable(density, 100 * density, Role.PRIMARY)

    init {
        listOf(track, fill).forEach { it.callback = this }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        unclipAncestors()
    }

    override fun verifyDrawable(who: android.graphics.drawable.Drawable) = who === track || who === fill || super.verifyDrawable(who)

    override fun onDraw(canvas: Canvas) {
        track.setBounds(0, 0, width, height)
        track.draw(canvas)
        val w = (width * fraction).toInt()
        if (w > height) { fill.setBounds(0, 0, w, height); fill.draw(canvas) }
    }
}

/** A tall rounded meter with an outlined pill, filled from the bottom, its value in the middle. */
@SuppressLint("ViewConstructor")
class MeterView(context: Context, private val c: Palette, private val glyph: String?) : View(context) {
    var fraction = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val path = android.graphics.Path()
    private val black: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        val stroke = 2.6f * d
        box.set(stroke, stroke, width - stroke, height - stroke)
        val r = box.width() / 2
        paint.style = Paint.Style.FILL
        paint.color = mix(c.surface, Color.WHITE, 0.4f)
        canvas.drawRoundRect(box, r, r, paint)
        canvas.save()
        path.rewind()
        path.addRoundRect(box, r, r, android.graphics.Path.Direction.CW)
        canvas.clipPath(path)
        paint.color = if (fraction >= 0.5f) c.secondary else c.accent
        canvas.drawRect(box.left, box.bottom - box.height() * fraction, box.right, box.bottom, paint)
        canvas.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = c.ink
        canvas.drawRoundRect(box, r, r, paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = black
        paint.textSize = 13 * d
        canvas.drawText("${(fraction * 100).toInt()}%", width / 2f, height * 0.46f, paint)
        if (glyph != null) { paint.textSize = 18 * d; canvas.drawText(glyph, width / 2f, height * 0.62f, paint) }
    }
}

/** The week as a pill, with today raised in a white box (from the neumorphism board). */
@SuppressLint("ViewConstructor")
class WeekPill(context: Context, private val c: Palette, private val locale: Locale) : View(context) {
    var today: LocalDate = LocalDate.now()
        set(value) { field = value; days = HomeText.week(value, WeekFields.of(locale).firstDayOfWeek); invalidate() }
    private var days = HomeText.week(today, WeekFields.of(locale).firstDayOfWeek)
    private val dayName = DateTimeFormatter.ofPattern("EEE", locale)
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-black", Typeface.NORMAL) }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val stroke = 2.6f * d
        val barTop = height * 0.08f
        val barBottom = height * 0.58f
        box.set(stroke, barTop, width - stroke, barBottom)
        paint.style = Paint.Style.FILL
        paint.color = c.secondary
        canvas.drawRoundRect(box, box.height() / 2, box.height() / 2, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = c.ink
        canvas.drawRoundRect(box, box.height() / 2, box.height() / 2, paint)
        val cell = (width - 2 * stroke) / days.size
        days.forEachIndexed { i, day ->
            val cx = stroke + cell * (i + 0.5f)
            if (day == today) {
                box.set(cx - cell * 0.42f, 0f + stroke, cx + cell * 0.42f, height - stroke)
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                canvas.drawRoundRect(box, 9 * d, 9 * d, paint)
                paint.style = Paint.Style.STROKE
                paint.color = c.ink
                canvas.drawRoundRect(box, 9 * d, 9 * d, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = if (day == today) 0xFF111111.toInt() else c.ink
            paint.textSize = 12 * d
            canvas.drawText(day.format(dayName), cx, (barTop + barBottom) / 2 + 4 * d, paint)
            paint.color = if (day == today) 0xFF111111.toInt() else c.ink
            paint.textSize = 13 * d
            canvas.drawText(day.dayOfMonth.toString(), cx, height - 10 * d, paint)
        }
    }
}

/** A circular gauge: a track, a lit arc or a ring of dots, and text in the middle. */
@SuppressLint("ViewConstructor")
class RingGauge(context: Context, private val c: Palette, private val arc: Int, private val dots: Boolean) : View(context) {
    var fraction = 0f
        set(value) { field = value; invalidate() }
    var big = ""
        set(value) { field = value; invalidate() }
    var small = ""
    var foot = ""
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val face = c.depthDrawable(d, 1000 * d, Role.CARD, if (c.dark) mix(c.background, Color.BLACK, 0.3f) else c.surface)
    private val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    init { face.callback = this; unclipAncestors() }

    override fun verifyDrawable(who: android.graphics.drawable.Drawable) = who === face || super.verifyDrawable(who)

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        face.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
        face.draw(canvas)
        val inkColour = if (c.dark || ThemeSpec.luminance(c.surface) < 0.2f) c.ink else c.ink
        if (dots) {
            val r = size * 0.43f
            for (i in 0 until 60) {
                val a = Math.toRadians(i * 6.0)
                paint.color = if (i < fraction * 60) arc else tone(inkColour, 0.7f)
                canvas.drawCircle(cx + r * sin(a).toFloat(), cy - r * cos(a).toFloat(), if (i % 5 == 0) 2.2f * d else 1.4f * d, paint)
            }
        } else {
            val r = size * 0.38f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * 0.08f
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = tone(inkColour, 0.12f)
            canvas.drawCircle(cx, cy, r, paint)
            paint.color = arc
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, 360f * fraction, false, paint)
            paint.style = Paint.Style.FILL
        }
        paint.textAlign = Paint.Align.CENTER
        paint.color = inkColour
        paint.typeface = light
        paint.textSize = if (dots) size * 0.17f else size * 0.22f
        canvas.drawText(big, cx, cy + paint.textSize * 0.35f, paint)
        paint.textSize = size * 0.065f
        paint.color = tone(inkColour, 0.7f)
        if (small.isNotEmpty()) canvas.drawText(small, cx, cy - size * 0.16f, paint)
        if (foot.isNotEmpty()) canvas.drawText(foot, cx, cy + size * 0.22f, paint)
    }
}

/** An analog clock in a deep well pressed into the page, with large quarter numerals. */
@SuppressLint("ViewConstructor")
class WellClock(context: Context, private val c: Palette) : View(context) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val well = c.depthDrawable(d, 1000 * d, Role.FIELD, c.background)
    private val heavy: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    init { well.callback = this; unclipAncestors() }

    override fun verifyDrawable(who: android.graphics.drawable.Drawable) = who === well || super.verifyDrawable(who)

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        well.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
        well.draw(canvas)
        val numeral = if (ThemeSpec.contrast(c.ink, c.background) >= 3) c.ink else c.onInk
        paint.color = numeral
        for (i in 1..12) {
            val quarter = i % 3 == 0
            val r = size * (if (quarter) 0.34f else 0.37f)
            val a = Math.toRadians(i * 30.0)
            paint.textSize = size * (if (quarter) 0.16f else 0.06f)
            paint.typeface = if (quarter) heavy else medium
            canvas.drawText(i.toString(), cx + r * sin(a).toFloat(), cy - r * cos(a).toFloat() + paint.textSize * 0.36f, paint)
        }
        val t = LocalTime.now()
        fun hand(degrees: Double, length: Float, width: Float) {
            val a = Math.toRadians(degrees)
            paint.strokeWidth = width
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = 0x55000000
            canvas.drawLine(cx - 2 * d, cy + 3 * d, cx - 2 * d + length * sin(a).toFloat(), cy + 3 * d - length * cos(a).toFloat(), paint)
            paint.color = Color.WHITE
            canvas.drawLine(cx, cy, cx + length * sin(a).toFloat(), cy - length * cos(a).toFloat(), paint)
        }
        hand((t.hour % 12 + t.minute / 60.0) * 30, size * 0.22f, 7 * d)
        hand(t.minute * 6.0, size * 0.32f, 4.5f * d)
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, 7 * d, paint)
        paint.color = numeral
        canvas.drawCircle(cx, cy, 3.5f * d, paint)
    }
}

/** Text set sideways, reading top to bottom. */
@SuppressLint("ViewConstructor")
class VerticalText(context: Context, private val colour: Int, private val sizeSp: Float, private val bold: Boolean) : View(context) {
    var text = ""
        set(value) { field = value; contentDescription = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colour
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp, context.resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
        letterSpacing = 0.14f
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.rotate(90f)
        canvas.drawText(text, 0f, -width / 2f + paint.textSize * 0.35f, paint)
        canvas.restore()
    }
}
