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
    private val letters = LetterStrip(context) { jumpTo(it) }
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
            is DrawerRow.App -> ((convertView as? LinearLayout) ?: LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = context.dp(56)
                setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
                addView(ImageView(context).apply {
                    id = android.R.id.icon
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(context.dp(36), context.dp(36)))
                addView(context.label("", 17f, ink).apply {
                    id = android.R.id.text1
                    setPadding(context.dp(16), 0, 0, 0)
                }, LayoutParams(0, -2, 1f))
                if (effects) Effects.pop(this)
            }).apply {
                findViewById<ImageView>(android.R.id.icon).apply {
                    showIcon(row.app, icons)
                }
                findViewById<TextView>(android.R.id.text1).text = row.app.label
                contentDescription = row.app.label
            }
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
        val listArea = LinearLayout(context)
        listArea.addView(list, LayoutParams(0, -1, 1f))
        listArea.addView(letters, LayoutParams(context.dp(28), -1))
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
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onLetters = letters.isShown && event.x >= width - paddingRight - letters.width
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
        letters.removeAllViews()
        letters.visibility = if (query.isBlank()) VISIBLE else GONE
        rows.filterIsInstance<DrawerRow.Header>().forEach { header ->
            letters.addView(context.label(header.title, 11f, colors.accent, Typeface.BOLD).apply {
                tag = header.title
                gravity = Gravity.CENTER
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO // Search serves screen-reader users.
            }, LayoutParams(-1, 0, 1f))
        }
    }

    private fun jumpTo(title: String) {
        val position = rows.indexOfFirst { it is DrawerRow.Header && it.title == title }
        if (position >= 0) list.setSelection(position)
    }
}

// Touch or drag along the letters to jump to that section; screen-reader users search instead.
@SuppressLint("ViewConstructor")
private class LetterStrip(context: Context, private val onLetter: (String) -> Unit) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (childCount == 0) return false
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val index = (event.y / height * childCount).toInt().coerceIn(0, childCount - 1)
            onLetter(getChildAt(index).tag as String)
        }
        if (event.action == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick() = super.performClick()
}
