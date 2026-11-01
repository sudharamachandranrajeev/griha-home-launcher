package dev.brahma.launcher

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast

class SettingsActions(
    val onChange: (LauncherSettings) -> Unit,
    val onAddSticker: () -> Unit,
    val onEditSticker: (String) -> Unit,
    val onToggleSticker: (String, Boolean) -> Unit,
    val onDeleteSticker: (String) -> Unit,
    val onArrangeStickers: () -> Unit,
    val onDesignLock: () -> Unit,
    val onHomeApp: () -> Unit,
    val onWallpaper: () -> Unit,
    val onCustomiseLuxe: () -> Unit,
    /** Opens one category's page, or the list of categories for null. */
    val onCategory: (SettingsCategory?) -> Unit,
    val onDone: () -> Unit
)

/** The groups settings are sorted into; the first settings page lists them and each opens its own page. */
enum class SettingsCategory(val title: String, val summary: String) {
    PROFILE("You and weather", "Name, place, footer line, weather"),
    HOME("Home screen", "Layout, Luxe home, shortcuts, clock"),
    LOOK("Look and theme", "Light or dark, style, wallpapers"),
    GESTURES("Gestures and motion", "Swipe distance, all-apps animation, effects"),
    ICONS("App icons", "Icon style, shape, plate, icon packs"),
    LOCK("Lock screen", "Themed lock wallpaper and its design"),
    STICKERS("Stickers", "Cut-out photos on home"),
    ANDROID("Android", "Default home app")
}

// Every change is saved at once, so leaving by Back, Home or Done never loses input.
@SuppressLint("ViewConstructor")
class SettingsScreen(
    context: Context,
    private var current: LauncherSettings,
    dark: Boolean,
    private val apps: List<AppEntry>,
    private val stickers: List<Pair<Sticker, Bitmap?>>,
    /** The open category; null shows the list of categories. */
    private val category: SettingsCategory?,
    private val actions: SettingsActions
) : ScrollView(context) {
    private val colors = current.uiPalette(dark)
    private val ink = colors.ink
    private val muted = colors.muted
    private val accent = colors.accent
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var shortcutSummary: android.widget.TextView? = null
    private val softStyle = current.uiStyle == UiStyle.SOFT
    // Rows go into the current section; in the soft style each section is a raised card.
    private var group = body

    init {
        isFillViewport = true
        setBackgroundColor(colors.background)
        body.setPadding(context.dp(24), context.dp(20), context.dp(24), context.dp(32))
        addView(body)
        body.addView(LinearLayout(context).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            if (category != null) addView(context.action("‹", accent) { actions.onCategory(null) }.apply {
                textSize = 28f
                contentDescription = "Back to all settings"
            })
            addView(context.label(category?.title ?: "Settings", if (category == null) 28f else 24f, ink, Typeface.BOLD),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(context.action("Done", accent, actions.onDone))
        })
        when (category) {
            null -> SettingsCategory.entries.forEach(::categoryRow)
            SettingsCategory.PROFILE -> buildProfile()
            SettingsCategory.HOME -> buildHome()
            SettingsCategory.LOOK -> buildLook()
            SettingsCategory.GESTURES -> buildGestures()
            SettingsCategory.ICONS -> buildIcons()
            SettingsCategory.LOCK -> buildLock()
            SettingsCategory.STICKERS -> buildStickers()
            SettingsCategory.ANDROID -> {
                section("Android")
                group.addView(context.action("Choose default home app", accent, actions.onHomeApp))
            }
        }
    }

    // One tappable card on the first page: the category's name and what is inside it.
    private fun categoryRow(entry: SettingsCategory) {
        body.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = context.dp(64)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(context.dp(18), context.dp(12), context.dp(18), context.dp(12))
            if (softStyle) soft(colors, context.dp(18).toFloat())
            else background = rounded(colors.surface, context.dp(14).toFloat(), colors.secondary, context.dp(1))
            addView(context.label(entry.title, 17f, ink, Typeface.BOLD))
            addView(context.label(entry.summary, 13f, muted))
            isClickable = true
            isFocusable = true
            contentDescription = "${entry.title}. ${entry.summary}"
            setOnClickListener { actions.onCategory(entry) }
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(context.dp(2), context.dp(12), context.dp(2), 0) })
    }

    private fun buildProfile() {
        section("About you")
        text("Your name", current.name, InputType.TYPE_TEXT_FLAG_CAP_WORDS) { update(current.copy(name = it)) }
        text("Place, for example Lisbon, or Pune, India", current.place, InputType.TYPE_TEXT_FLAG_CAP_WORDS) {
            update(current.copy(place = it))
        }
        text("Footer line, for example a favourite quote", current.footer, InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) {
            update(current.copy(footer = it))
        }

        section("Weather")
        group.addView(context.label("Off by default. When on, the launcher sends your typed place and its " +
            "coordinates to Open-Meteo (open-meteo.com) about every 30 minutes while home is open. " +
            "No account, location permission or device ID is used.", 14f, muted))
        toggle("Show weather", current.weather) { enabled ->
            update(current.copy(weather = enabled))
            if (enabled && current.place.isBlank()) {
                Toast.makeText(context, "Type a place above to see its weather", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun buildHome() {
        section("Home layout")
        choices(HomeLayout.entries.map { it.title }, current.layout.ordinal) { update(current.copy(layout = HomeLayout.entries[it])) }
        if (current.layout == HomeLayout.LUXE) {
            group.addView(context.label("Gold on near-black across home, apps, settings and editors. At the end of home, swipe up for all apps.", 14f, muted))
            group.addView(context.action("Customise Luxe home: cards, focus apps, quotes", accent, actions.onCustomiseLuxe))
        }

        section("Shortcuts")
        shortcutSummary = context.label("", 15f, muted).also { group.addView(it) }
        group.addView(context.action("Choose up to ${LauncherSettings.MAX_SHORTCUTS} shortcut apps", accent, ::chooseShortcuts))
        describeShortcuts()

        section("Clock")
        choices(ClockFormat.entries.map { it.title }, current.clock.ordinal) { update(current.copy(clock = ClockFormat.entries[it])) }
    }

    private fun buildLook() {
        section("Theme")
        choices(ThemeMode.entries.map { it.title }, current.theme.ordinal) { update(current.copy(theme = ThemeMode.entries[it])) }

        section("Style")
        choices(UiStyle.entries.map { it.title }, current.uiStyle.ordinal) { update(current.copy(uiStyle = UiStyle.entries[it])) }

        section("Wallpaper")
        toggle("Show my wallpaper behind home", current.wallpaper) { update(current.copy(wallpaper = it)) }
        group.addView(context.action("Wallpapers: theme sets and your packs", accent, actions.onWallpaper))
    }

    private fun buildGestures() {
        section("Swipe for all apps")
        group.addView(context.label("Scroll to the end of home, then swipe up to open all apps. In all apps, " +
            "scroll to the top, then swipe down to go home. A longer swipe is harder to trigger by accident.", 14f, muted))
        choices(SwipeDistance.entries.map { it.title }, current.swipeDistance.ordinal) {
            update(current.copy(swipeDistance = SwipeDistance.entries[it]))
        }

        section("All apps animation")
        choices(DrawerMotion.entries.map { it.title }, current.drawerMotion.ordinal) {
            update(current.copy(drawerMotion = DrawerMotion.entries[it]))
        }
        group.addView(context.label("Off while Android's Remove animations setting is on.", 14f, muted))

        section("Motion effects")
        toggle("Motion effects", current.effects) { update(current.copy(effects = it)) }
        group.addView(context.label("Heartbeat pop when you tap, screens that blend into each other, home items " +
            "that float in, and apps that open out of their icon. Always off while Android's Remove animations " +
            "setting is on.", 14f, muted))
    }

    private fun buildIcons() {
        section("Icon style")
        choices(IconStyle.entries.map { it.title }, current.iconStyle.ordinal) { update(current.copy(iconStyle = IconStyle.entries[it])) }
        group.addView(context.label("Monochrome uses each app's themed icon where the app provides one, otherwise grey.", 14f, muted))
        section("Icon shape and plate")
        choices(IconShape.entries.map { it.title }, current.iconShape.ordinal) { update(current.copy(iconShape = IconShape.entries[it])) }
        group.addView(context.label("Plate: a background behind each icon in your theme's colours.", 14f, muted))
        choices(IconPlate.entries.map { it.title }, current.iconPlate.ordinal) { update(current.copy(iconPlate = IconPlate.entries[it])) }
        section("Icon pack")
        val packs = IconPacks.installed(context)
        choices(listOf("None · each app's own icon") + packs.map { it.label }, packs.indexOfFirst { it.packageName == current.iconPack } + 1) {
            update(current.copy(iconPack = if (it == 0) null else packs[it - 1].packageName))
        }
        group.addView(context.label(if (packs.isEmpty()) "No icon packs found. Install one from your app store; packs made for Nova, ADW and similar launchers work here."
            else "Apps the pack does not cover use your shape and plate.", 14f, muted))
    }

    private fun buildLock() {
        section("Lock screen")
        group.addView(context.label("Draws your lock-screen wallpaper from this layout and theme, with the date " +
            "and a greeting, and redraws it each day. Android's clock and unlock stay on top.", 14f, muted))
        lateinit var lock: Switch
        lock = toggle("Theme my lock screen", current.lockScreen) { enabled ->
            if (!enabled) return@toggle update(current.copy(lockScreen = false))
            AlertDialog.Builder(context).setTitle("Replace lock-screen wallpaper?")
                .setMessage("Your current lock-screen picture will be replaced and Griha cannot bring it back. " +
                    "Turning this off later makes the lock screen use your home wallpaper.")
                .setPositiveButton("Replace") { _, _ -> update(current.copy(lockScreen = true)) }
                .setNegativeButton(R.string.cancel) { _, _ -> lock.isChecked = false }
                .setOnCancelListener { lock.isChecked = false }
                .show()
        }
        group.addView(context.action("Design lock screen", accent, actions.onDesignLock))
    }

    private fun buildStickers() {
        section("Stickers")
        group.addView(context.label("Pick a photo and Griha finds the person, pet or thing in it and cuts it out, " +
            "then you add effects. Subjects are found on this phone by Google ML Kit through Play services; " +
            "the photo is not uploaded. Play services downloads the finder the first time and may send Google " +
            "anonymous usage statistics.", 14f, muted))
        stickers.forEachIndexed { index, (sticker, thumbnail) -> stickerRow(index, sticker, thumbnail) }
        group.addView(LinearLayout(context).apply {
            addView(context.action("Add sticker", accent, actions.onAddSticker))
            if (stickers.any { it.first.enabled }) addView(context.action("Move on home", accent, actions.onArrangeStickers))
        })
    }

    // One library sticker: picture, on/off switch, edit and delete.
    private fun stickerRow(index: Int, sticker: Sticker, thumbnail: Bitmap?) {
        val name = context.getString(R.string.sticker_name, index + 1)
        group.addView(LinearLayout(context).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageBitmap(thumbnail)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
            addView(Switch(context).apply {
                text = name
                textSize = 16f
                minHeight = context.dp(48)
                isChecked = sticker.enabled
                if (softStyle) accent(colors)
                setPadding(context.dp(12), 0, 0, 0)
                setOnCheckedChangeListener { _: CompoundButton, value: Boolean -> actions.onToggleSticker(sticker.id, value) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        group.addView(LinearLayout(context).apply {
            setPadding(context.dp(52), 0, 0, 0)
            addView(context.action("Edit", accent) { actions.onEditSticker(sticker.id) }.apply { contentDescription = "Edit $name" })
            addView(context.action("Delete", accent) {
                AlertDialog.Builder(context).setTitle("Delete $name?")
                    .setMessage("Its cutout and effects are removed from Griha. Your original photo is not touched.")
                    .setPositiveButton("Delete") { _, _ -> actions.onDeleteSticker(sticker.id) }
                    .setNegativeButton(R.string.cancel, null).show()
            }.apply { contentDescription = "Delete $name" })
        })
    }

    private fun update(settings: LauncherSettings) {
        current = settings
        actions.onChange(settings)
    }

    private fun section(title: String) {
        body.addView(View(context), LinearLayout.LayoutParams(1, context.dp(if (softStyle) 18 else 24)))
        body.addView(context.label(title.uppercase(), 12f, accent, Typeface.BOLD).apply { setPadding(context.dp(4), 0, 0, context.dp(6)) })
        if (!softStyle) return
        group = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            soft(colors, context.dp(20).toFloat())
            setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(10))
        }
        body.addView(group, LinearLayout.LayoutParams(-1, -2).apply { setMargins(context.dp(2), 0, context.dp(2), context.dp(4)) })
    }

    private fun text(hint: String, value: String, flags: Int, onText: (String) -> Unit) {
        group.addView(EditText(context).apply {
            this.hint = hint
            contentDescription = hint
            setText(value)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or flags
            minHeight = context.dp(48)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = onText(s.toString())
            })
        })
    }

    private fun choices(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        val radios = RadioGroup(context)
        titles.forEachIndexed { index, title ->
            radios.addView(RadioButton(context).apply {
                id = View.generateViewId()
                text = title
                textSize = 16f
                minHeight = context.dp(48)
                isChecked = index == selected
                if (softStyle) accent(colors)
                setOnClickListener { onSelect(index) }
            })
        }
        group.addView(radios)
    }

    private fun toggle(title: String, checked: Boolean, onToggle: (Boolean) -> Unit) = Switch(context).apply {
        text = title
        textSize = 16f
        minHeight = context.dp(48)
        isChecked = checked
        if (softStyle) accent(colors)
        setOnCheckedChangeListener { _: CompoundButton, value: Boolean -> onToggle(value) }
        group.addView(this)
    }

    private fun describeShortcuts() {
        val names = current.shortcuts.mapNotNull { id -> apps.find { it.id == id }?.label }
        shortcutSummary?.text = if (names.isEmpty()) "No shortcuts chosen." else names.joinToString(" · ")
    }

    private fun chooseShortcuts() {
        val chosen = current.shortcuts.filter { id -> apps.any { it.id == id } }.toMutableList()
        val checked = BooleanArray(apps.size) { apps[it].id in chosen }
        AlertDialog.Builder(context).setTitle("Shortcut apps")
            .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), checked) { dialog, which, isChecked ->
                val id = apps[which].id
                if (isChecked && chosen.size >= LauncherSettings.MAX_SHORTCUTS) {
                    (dialog as AlertDialog).listView.setItemChecked(which, false)
                    checked[which] = false
                    Toast.makeText(context, "Up to ${LauncherSettings.MAX_SHORTCUTS} shortcuts", Toast.LENGTH_SHORT).show()
                } else if (isChecked) {
                    chosen += id
                } else {
                    chosen -= id
                }
            }
            .setPositiveButton("Save") { _, _ ->
                update(current.copy(shortcuts = chosen.toList()))
                describeShortcuts()
            }
            .setNegativeButton("Cancel", null).show()
    }
}
