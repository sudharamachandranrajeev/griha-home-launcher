package dev.brahma.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class DrawerActions(
    val onApp: (AppEntry, View?) -> Unit,
    val onAppOptions: (AppEntry) -> Unit,
    val onHome: () -> Unit
)

// Full-screen app drawer: every launchable app under A–Z headers, with a letter strip to jump.
@SuppressLint("ViewConstructor")
class DrawerScreen(
    context: Context,
    private val colors: Palette,
    private val icons: IconKit,
    wallpaper: Boolean,
    private val effects: Boolean,
    private var apps: List<AppEntry>,
    private var favoriteIds: Set<String>,
    query: String,
    swipeDistance: SwipeDistance,
    private val depth3d: DrawerDepth,
    private val actions: DrawerActions
) : LinearLayout(context) {
    private val ink = colors.ink
    private val muted = colors.muted
    private var rows = emptyList<DrawerRow>()
    private val count = context.label("", 13f, muted)
    private val list = ListView(context).apply {
        divider = null
        isFastScrollEnabled = false
        isVerticalScrollBarEnabled = false
    }
    private val letters = LetterStrip(context, colors) { jumpTo(it) }
    private val empty = context.label(context.getString(R.string.no_apps), 16f, muted).apply {
        gravity = Gravity.CENTER
        setPadding(0, context.dp(48), 0, 0)
    }
    val search = EditText(context).apply {
        hint = context.getString(R.string.search_apps)
        contentDescription = context.getString(R.string.search_apps)
        setText(query)
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT
        setTextColor(ink)
        setHintTextColor(muted)
        depth(colors, context.dp(26).toFloat(), Role.FIELD, border = colors.secondary)
        setPadding(context.dp(18), 0, context.dp(18), 0)
    }
    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is DrawerRow.Header) 0 else 1
        override fun isEnabled(position: Int) = rows[position] is DrawerRow.App

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = when (val row = rows[position]) {
            is DrawerRow.Header -> ((convertView as? TextView) ?: context.label("", 13f, colors.accent, Typeface.BOLD).apply {
                setPadding(context.dp(8), context.dp(16), 0, context.dp(4))
            }).apply {
                text = if (row.title == AppCatalog.FAVORITES) context.getString(R.string.favorites) else row.title
            }
            is DrawerRow.App -> ((convertView as? android.widget.FrameLayout) ?: appRow()).apply {
                findViewById<ImageView>(android.R.id.icon).apply {
                    showIcon(row.app, icons)
                }
                findViewById<TextView>(android.R.id.text1).text = row.app.label
                contentDescription = row.app.label
            }
        }
    }

    // One app: icon and name on a tile. Extruded tiles stand up from the page on a solid side and sink
    // into it when pressed; the 3D wheel turns rows away as they near the top and bottom edges.
    private fun appRow() = android.widget.FrameLayout(context).apply {
        val extruded = depth3d == DrawerDepth.EXTRUDED
        if (extruded) setPadding(context.dp(4), context.dp(5), context.dp(6), context.dp(7))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(if (extruded) 60 else 56)
            setPadding(context.dp(if (extruded) 12 else 8), context.dp(4), context.dp(8), context.dp(4))
            if (extruded) depth(colors, context.dp(18).toFloat(), Role.KEY, extrude = 5 * resources.displayMetrics.density)
            addView(ImageView(context).apply {
                id = android.R.id.icon
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(context.dp(36), context.dp(36)))
            addView(context.label("", 17f, ink).apply {
                id = android.R.id.text1
                setPadding(context.dp(16), 0, 0, 0)
            }, LayoutParams(0, -2, 1f))
        }, android.widget.FrameLayout.LayoutParams(-1, -2))
        // The wheel sets each row's scale itself, so the tap pop stays off there.
        if (effects && depth3d != DrawerDepth.WHEEL) Effects.pop(this)
    }

    /** Turns each row with its distance from the middle of the list, like the face of a wheel. */
    private fun wheel() {
        if (depth3d != DrawerDepth.WHEEL) return
        val middle = list.height / 2f
        if (middle <= 0f) return
        val camera = 9000 * resources.displayMetrics.density
        for (index in 0 until list.childCount) {
            val row = list.getChildAt(index)
            val offset = ((row.top + row.height / 2f - middle) / middle).coerceIn(-1.2f, 1.2f)
            row.cameraDistance = camera
            row.pivotY = if (offset < 0) row.height.toFloat() else 0f
            row.rotationX = -offset * 38f
            val shrink = 1f - kotlin.math.abs(offset) * 0.10f
            row.scaleX = shrink
            row.scaleY = shrink
            row.alpha = 1f - kotlin.math.abs(offset) * 0.45f
        }
    }

    init {
        orientation = VERTICAL
        finishBackdrop(colors, if (wallpaper) 0xE6 else null)
        setPadding(context.dp(16), context.dp(12), context.dp(4), 0)
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(context.label(context.getString(R.string.all_apps), 26f, ink, Typeface.BOLD))
                addView(count)
            }, LayoutParams(0, -2, 1f))
            addView(context.action(context.getString(R.string.home), colors.accent, actions.onHome))
        })
        addView(search, LayoutParams(-1, context.dp(52)).apply { setMargins(0, context.dp(8), context.dp(12), context.dp(4)) })
        addView(context.label(context.getString(R.string.favorite_hint), 13f, muted).apply { setPadding(context.dp(4), 0, 0, 0) })
        // The letters lie over the list's right edge; a held letter swells out over the list.
        val listArea = android.widget.FrameLayout(context)
        list.setPadding(0, 0, context.dp(LetterStrip.GRAB_DP), 0)
        list.clipToPadding = false
        listArea.addView(list, android.widget.FrameLayout.LayoutParams(-1, -1))
        listArea.addView(letters, android.widget.FrameLayout.LayoutParams(context.dp(LetterStrip.WIDTH_DP), -1, Gravity.END))
        list.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView, state: Int) = Unit
            override fun onScroll(view: android.widget.AbsListView, first: Int, visible: Int, total: Int) {
                wheel()
                letters.current = rows.getOrNull(first)?.let { row -> headerFor(first, row) }
            }
        })
        addView(empty)
        addView(listArea, LayoutParams(-1, 0, 1f))
        list.adapter = adapter
        list.emptyView = empty
        list.setOnItemClickListener { _, view, position, _ -> (rows[position] as? DrawerRow.App)?.let { actions.onApp(it.app, view) } }
        list.setOnItemLongClickListener { _, _, position, _ ->
            (rows[position] as? DrawerRow.App)?.let { actions.onAppOptions(it.app); true } ?: false
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refresh()
    }

    // Swipe down with the list at its top to go home; the letter strip keeps its own drags.
    private val swipe = EdgeSwipe(up = false, distancePx = context.dp(swipeDistance.dp).toFloat(),
        slopPx = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
        atEdge = { !list.canScrollVertically(-1) }, onSwipe = { actions.onHome() })
    private var onLetters = false

    private fun swipeDown(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onLetters = letters.isShown && letters.grabs(event.rawX)
        return !onLetters && swipe.onTouch(event)
    }

    // Watched here rather than by intercepting: the list stops its parent intercepting once it starts to drag.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val already = swipe.fired
        if (!swipeDown(event)) return super.dispatchTouchEvent(event)
        if (!already) {
            // The list and buttons drop this touch so nothing is tapped or dragged as the drawer closes.
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
        return true
    }

    fun update(apps: List<AppEntry>, favoriteIds: Set<String>) {
        this.apps = apps
        this.favoriteIds = favoriteIds
        refresh()
    }

    private fun refresh() {
        val query = search.text.toString()
        rows = AppCatalog.drawerRows(apps, favoriteIds, query)
        adapter.notifyDataSetChanged()
        count.text = resources.getQuantityString(R.plurals.app_count, apps.size, apps.size)
        letters.visibility = if (query.isBlank()) VISIBLE else GONE
        letters.letters = rows.filterIsInstance<DrawerRow.Header>().map { it.title }
    }

    /** The section a row belongs to: the nearest header at or above it. */
    private fun headerFor(position: Int, row: DrawerRow): String? {
        if (row is DrawerRow.Header) return row.title
        for (index in position downTo 0) (rows[index] as? DrawerRow.Header)?.let { return it.title }
        return null
    }

    private fun jumpTo(title: String) {
        val position = rows.indexOfFirst { it is DrawerRow.Header && it.title == title }
        if (position >= 0) list.setSelection(position)
    }
}

/** How rows in all apps stand off the page. */
enum class DrawerDepth(val title: String) {
    FLAT("Flat list"),
    EXTRUDED("Extruded tiles · each app stands up on a solid side"),
    WHEEL("3D wheel · rows turn away toward the edges")
}

/**
 * The side alphabet. Touch and hold a letter, then slide: letters near the finger swell and bow
 * out over the list in a smooth wave, a bubble shows the letter under the finger, each new letter
 * ticks, and the list jumps to it. Screen-reader users search instead.
 */
@SuppressLint("ViewConstructor")
private class LetterStrip(context: Context, private val colors: Palette, private val onLetter: (String) -> Unit) : View(context) {
    var letters: List<String> = emptyList()
        set(value) { field = value; invalidate() }
    /** The section at the top of the list, shown in the accent colour while resting. */
    var current: String? = null
        set(value) { if (field != value) { field = value; invalidate() } }

    private val density = resources.displayMetrics.density
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val bubbleText = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        color = colors.onAccent
    }
    private val bubble = colors.depthDrawable(density, 100 * density, Role.PRIMARY, glow = true)
    private var touchY = 0f
    private var held = 0f
    private var holding = false
    private var shown: String? = null
    private var animator: android.animation.ValueAnimator? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        bubble.callback = this
        unclipAncestors()
    }

    override fun verifyDrawable(who: android.graphics.drawable.Drawable) = who === bubble || super.verifyDrawable(who)

    /** True when a touch at [rawX] lands on the letters, which are at the right edge of this view. */
    fun grabs(rawX: Float): Boolean {
        val spot = IntArray(2)
        getLocationOnScreen(spot)
        return rawX >= spot[0] + width - GRAB_DP * density
    }

    private fun cell() = if (letters.isEmpty()) 0f else (height - paddingTop - paddingBottom).toFloat() / letters.size

    private fun indexAt(y: Float) = ((y - paddingTop) / cell()).toInt().coerceIn(0, letters.lastIndex)

    override fun onDraw(canvas: android.graphics.Canvas) {
        if (letters.isEmpty()) return
        val cell = cell()
        val restX = width - GRAB_DP * density / 2
        val size = (cell * 0.72f).coerceIn(9 * density, 13 * density)
        val reach = 3.2f * cell.coerceAtLeast(14 * density)
        letters.forEachIndexed { index, letter ->
            val y = paddingTop + cell * (index + 0.5f)
            // A bell curve around the finger: 1 under it, fading to 0 a few letters away.
            val distance = (y - touchY) / reach
            val swell = held * kotlin.math.exp(-distance * distance * 2.2f)
            val x = restX - swell * BULGE_DP * density
            val picked = holding && letter == shown
            paint.textSize = size * (1f + swell * 0.9f)
            paint.color = when {
                picked -> colors.accent
                !holding && letter == current -> colors.accent
                else -> mix(colors.muted, colors.ink, swell)
            }
            paint.isFakeBoldText = picked || (!holding && letter == current)
            paint.alpha = (255 * (0.75f + 0.25f * swell)).toInt()
            canvas.drawText(letter, x, y - (paint.descent() + paint.ascent()) / 2, paint)
        }
        val letter = shown ?: return
        if (held <= 0.01f) return
        // The bubble rides beside the finger, clear of the swollen letters.
        val radius = 30 * density
        val cx = restX - BULGE_DP * density - radius - 18 * density
        val cy = touchY.coerceIn(radius, height - radius)
        bubble.alpha = (held * 255).toInt()
        bubble.setBounds((cx - radius).toInt(), (cy - radius).toInt(), (cx + radius).toInt(), (cy + radius).toInt())
        bubble.draw(canvas)
        bubbleText.textSize = radius * 1.05f
        bubbleText.alpha = (held * 255).toInt()
        canvas.drawText(letter, cx, cy - (bubbleText.descent() + bubbleText.ascent()) / 2, bubbleText)
    }

    private fun hold(target: Float) {
        animator?.cancel()
        animator = android.animation.ValueAnimator.ofFloat(held, target).apply {
            duration = if (target > held) 140 else 260
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { held = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only the letters themselves take a touch; the rest of this view lets the list scroll.
                if (event.x < width - GRAB_DP * density) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                holding = true
                shown = null
                hold(1f)
                pick(event.y)
            }
            MotionEvent.ACTION_MOVE -> pick(event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                holding = false
                hold(0f)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    private fun pick(y: Float) {
        touchY = y
        val letter = letters[indexAt(y)]
        if (letter != shown) {
            shown = letter
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            onLetter(letter)
        }
        invalidate()
    }

    override fun performClick() = super.performClick()

    companion object {
        /** Width of the touchable letter column, in dp. */
        const val GRAB_DP = 30
        /** How far a held letter bows out over the list, in dp. */
        const val BULGE_DP = 44
        /** The whole view, wide enough for the wave and the bubble. */
        const val WIDTH_DP = 160
    }
}
