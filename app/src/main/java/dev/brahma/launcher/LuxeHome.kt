package dev.brahma.launcher

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class LuxeHooks(
    val store: LuxeStore,
    /** A focus session started or ended: the apps it hides must be hidden or shown again. */
    val onFocusChanged: () -> Unit,
    val onCustomise: () -> Unit
)

/** The Dark Luxury home cards: gold on near-black, each one optional and kept on this phone only. */
@SuppressLint("ViewConstructor")
class LuxeHome(
    context: Context,
    private val colors: Palette,
    private val name: String,
    private val use24Hour: Boolean,
    private val hooks: LuxeHooks,
    private val onAllApps: () -> Unit,
    private val onSettings: () -> Unit,
    /** Scrolls home so the view is in sight. */
    private val scrollTo: (View) -> Unit,
    registerTicker: (() -> Unit) -> Unit
) : LinearLayout(context) {
    private val store = hooks.store
    private var data = store.load()
    private val zone = ZoneId.systemDefault()
    private val locale: Locale = resources.configuration.locales[0]
    private val title = resources.getFont(R.font.cinzel)
    private val cards = LinearLayout(context).apply { orientation = VERTICAL }
    private var removed: Pair<Int, LuxeTask>? = null
    private var selectedDay = 6
    private var focusCard: View? = null
    private var scheduleCard: View? = null
    private var shownDay = LocalDate.now()
    private var shownMinute = 0L

    init {
        orientation = VERTICAL
        addView(cards)
        addView(nav())
        build()
        // Time ticks keep the countdown, the reminder and the day current, and close a finished focus session.
        registerTicker {
            val now = System.currentTimeMillis()
            val settled = data.settle(now, zone)
            if (settled != data) {
                save(settled)
                hooks.onFocusChanged()
            } else if (now / 60_000 != shownMinute && (data.focusActive(now) || LocalDate.now() != shownDay || data.reminder.isNotBlank())) build()
        }
    }

    private fun save(changed: LuxeData) {
        data = changed
        store.save(changed)
    }

    private fun change(changed: LuxeData) {
        save(changed)
        build()
    }

    private fun build() {
        shownDay = LocalDate.now()
        shownMinute = System.currentTimeMillis() / 60_000
        // Emptying the list shrinks home and moves its scroll position; hold the height and put the position back.
        val scroller = generateSequence(parent) { (it as? View)?.parent }.filterIsInstance<android.widget.ScrollView>().firstOrNull()
        val keep = scroller?.scrollY ?: 0
        cards.minimumHeight = cards.height
        cards.removeAllViews()
        focusCard = null
        scheduleCard = null
        if (LuxeCard.PROFILE in data.cards) cards.addView(profile())
        if (LuxeCard.FOCUS in data.cards || data.focusActive(System.currentTimeMillis())) cards.addView(focus().also { focusCard = it })
        if (LuxeCard.PROGRESS in data.cards) cards.addView(progress())
        if (LuxeCard.SCHEDULE in data.cards) cards.addView(schedule().also { scheduleCard = it })
        if (LuxeCard.STREAK in data.cards) cards.addView(streak())
        if (LuxeCard.REMINDER in data.cards) cards.addView(reminder())
        if (LuxeCard.QUOTES in data.cards) cards.addView(quote())
        if (LuxeCard.ROADMAP in data.cards) cards.addView(roadmap())
        if (data.cards.isEmpty()) cards.addView(card().apply {
            addView(text("Every card is hidden. Turn them back on in Customise.", 15f, colors.muted))
        })
        cards.post {
            cards.minimumHeight = 0
            scroller?.post { scroller.scrollTo(0, keep) }
        }
    }

    // ---- Shared pieces ----

    private fun card() = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = rounded(colors.surface, dp(20).toFloat(), (colors.accent and 0x00FFFFFF) or 0x55000000, dp(1))
        layoutParams = LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(6)) }
    }

    private fun heading(value: String) = TextView(context).apply {
        text = value.uppercase()
        textSize = 12f
        letterSpacing = 0.18f
        typeface = title
        setTextColor(colors.accent)
        setPadding(0, 0, 0, dp(8))
    }

    private fun text(value: CharSequence, size: Float, color: Int = colors.ink, style: Int = Typeface.NORMAL) =
        context.label(value, size, color, style)

    private fun gold(value: String, size: Float) = TextView(context).apply {
        text = value
        textSize = size
        typeface = title
        setTextColor(colors.accent)
    }

    private fun time(minute: Int) = LocalTime.of(minute / 60, minute % 60)
        .format(DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale))

    private fun apps(count: Int) = if (count == 1) "1 app" else "$count apps"

    private fun dp(value: Int) = context.dp(value)

    // ---- Profile ----

    private fun profile() = card().apply {
        val streak = Streaks.compute(data.keptDays(), LocalDate.now().toEpochDay())
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(Emblem(context, colors, name.trim().firstOrNull()?.uppercase() ?: "◆"), LayoutParams(dp(56), dp(56)))
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(14), 0, 0, 0)
                addView(gold(name.ifBlank { "Welcome" }, 22f))
                addView(text(data.tagline, 14f, colors.muted).apply {
                    contentDescription = "Tagline: ${data.tagline}. Tap to change."
                    setOnClickListener { editText("Your tagline", data.tagline, 60) { change(data.copy(tagline = it)) } }
                })
            }, LayoutParams(0, -2, 1f))
            addView(text("LVL ${data.level()}", 12f, colors.onAccent, Typeface.BOLD).apply {
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = rounded(colors.accent, dp(12).toFloat())
                contentDescription = "Level ${data.level()}"
            })
        })
        addView(LinearLayout(context).apply {
            setPadding(0, dp(14), 0, 0)
            val hours = data.focusMinutes() / 60f
            stat(this, "${data.keptDays().size}", "Days")
            stat(this, "${streak.current}", "Streak")
            stat(this, "${data.doneOn(LocalDate.now())}/${data.tasks.size}", "Today")
            stat(this, if (hours < 10) String.format(locale, "%.1f", hours) else "${hours.toInt()}", "Focus hrs")
        })
    }

    private fun stat(row: LinearLayout, value: String, label: String) {
        row.addView(LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            addView(gold(value, 20f))
            addView(text(label, 11f, colors.muted))
            contentDescription = "$label: $value"
        }, LayoutParams(0, -2, 1f))
    }

    // ---- Focus ----

    private fun focus() = card().apply {
        val now = System.currentTimeMillis()
        addView(heading("Focus mode"))
        if (data.focusActive(now)) {
            val left = ((data.focusUntil - now + 59_999) / 60_000).toInt()
            addView(gold("$left min", 34f))
            val ends = java.time.Instant.ofEpochMilli(data.focusUntil).atZone(zone).toLocalTime()
            addView(text("Ends at ${time(ends.hour * 60 + ends.minute)}. Hidden until then: ${apps(data.focusApps.size)}.", 14f, colors.muted))
            addView(text("Touch and hold to end early", 13f, colors.accent, Typeface.BOLD).apply {
                minHeight = dp(48)
                gravity = Gravity.CENTER_VERTICAL
            })
            contentDescription = "Focus session, $left minutes left. Touch and hold to end early."
            // Ending early takes a hold and a confirmation, so it is a choice, not a slip.
            setOnLongClickListener {
                AlertDialog.Builder(context).setTitle("End focus early?")
                    .setMessage("The minutes so far still count. Hidden apps come back.")
                    .setPositiveButton("End now") { _, _ ->
                        save(data.endFocus(System.currentTimeMillis(), zone))
                        hooks.onFocusChanged()
                    }
                    .setNegativeButton("Keep going", null).show()
                true
            }
        } else {
            addView(text(if (data.focusApps.isEmpty()) "Pick apps to hide in Customise, then start a session."
                else "A session hides ${apps(data.focusApps.size)}. Everything comes back when it ends.", 14f, colors.muted))
            addView(LinearLayout(context).apply {
                setPadding(0, dp(10), 0, 0)
                listOf(25, 50, 90).forEach { minutes ->
                    addView(text("$minutes min", 14f, colors.onAccent, Typeface.BOLD).apply {
                        gravity = Gravity.CENTER
                        minHeight = dp(44)
                        background = rounded(colors.accent, dp(22).toFloat())
                        contentDescription = "Start a $minutes minute focus session"
                        setOnClickListener {
                            save(data.startFocus(System.currentTimeMillis(), minutes))
                            hooks.onFocusChanged()
                        }
                    }, LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
                }
            })
        }
    }

    // ---- Daily ring and week ----

    private fun progress() = card().apply {
        addView(heading("Today"))
        val today = LocalDate.now()
        val ticked = data.done[today.toString()].orEmpty()
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            val shares = TaskKind.entries.map { kind ->
                val all = data.tasks.filter { it.kind == kind }
                kind to if (data.tasks.isEmpty()) 0f else all.count { it.id in ticked } / data.tasks.size.toFloat()
            }
            val percent = (shares.sumOf { it.second.toDouble() } * 100).toInt()
            addView(Ring(context, colors, shares.map { it.second }, "$percent%", title).apply {
                contentDescription = "$percent percent of today's schedule done"
            }, LayoutParams(dp(112), dp(112)))
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(16), 0, 0, 0)
                TaskKind.entries.forEachIndexed { index, kind ->
                    val total = data.tasks.count { it.kind == kind }
                    if (total == 0) return@forEachIndexed
                    addView(LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(View(context).apply { background = rounded(Ring.shade(colors, index), dp(5).toFloat()) }, LayoutParams(dp(10), dp(10)))
                        addView(text("${kind.title}  ${data.tasks.count { it.kind == kind && it.id in ticked }}/$total", 13f, colors.ink).apply {
                            setPadding(dp(8), dp(3), 0, dp(3))
                        })
                    })
                }
            }, LayoutParams(0, -2, 1f))
        })
        addView(heading("This week").apply { setPadding(0, dp(16), 0, dp(8)) })
        val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val picked = days[selectedDay]
        val detail = text("${picked.dayOfWeek.getDisplayName(TextStyle.FULL, locale)}: ${data.doneOn(picked)} of ${data.tasks.size} done, " +
            "${data.focus[picked.toString()] ?: 0} min focus", 13f, colors.muted)
        addView(LinearLayout(context).apply {
            days.forEachIndexed { index, day ->
                val share = if (data.tasks.isEmpty()) 0f else (data.doneOn(day) / data.tasks.size.toFloat()).coerceAtMost(1f)
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                    addView(FrameLayout(context).apply {
                        addView(View(context).apply {
                            background = rounded(if (index == selectedDay) colors.accent else mix(colors.accent, colors.surface, 0.45f), dp(5).toFloat())
                        }, FrameLayout.LayoutParams(dp(12), (dp(64) * share).toInt().coerceAtLeast(dp(4)), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
                    }, LayoutParams(-1, dp(64)))
                    addView(text(day.dayOfWeek.getDisplayName(TextStyle.NARROW, locale), 12f,
                        if (index == selectedDay) colors.accent else colors.muted).apply { gravity = Gravity.CENTER })
                    minimumHeight = dp(48)
                    contentDescription = "${day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)}, ${data.doneOn(day)} done"
                    setOnClickListener {
                        selectedDay = index
                        build()
                    }
                }, LayoutParams(0, -2, 1f))
            }
        })
        addView(detail.apply { setPadding(0, dp(8), 0, 0) })
    }

    // ---- Schedule ----

    private fun schedule() = card().apply {
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(heading("Schedule"), LayoutParams(0, -2, 1f))
            addView(context.action("+ Add", colors.accent) { editTask() })
        })
        val today = LocalDate.now()
        val ticked = data.done[today.toString()].orEmpty()
        if (data.tasks.isEmpty()) addView(text("Nothing planned. Add what you want to do today.", 14f, colors.muted))
        data.tasks.sortedBy { if (it.minute < 0) Int.MAX_VALUE else it.minute }.forEach { task ->
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                val check = CheckBox(context).apply {
                    isChecked = task.id in ticked
                    accent(colors)
                    text = task.title
                    textSize = 16f
                    setTextColor(if (isChecked) colors.muted else colors.ink)
                    setOnClickListener { change(data.toggle(today, task.id)) }
                    setOnLongClickListener { taskOptions(task); true }
                }
                addView(check, LayoutParams(0, -2, 1f))
                if (task.minute >= 0) addView(text(time(task.minute), 13f, colors.accent))
            })
        }
        removed?.let { (_, task) ->
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text("Removed “${task.title}”", 13f, colors.muted), LayoutParams(0, -2, 1f))
                addView(context.action("Undo", colors.accent) {
                    val (index, back) = removed ?: return@action
                    removed = null
                    change(data.copy(tasks = data.tasks.toMutableList().apply { add(index.coerceAtMost(size), back) }))
                })
            })
        }
        addView(text("Touch and hold an item to edit or remove it.", 12f, colors.muted))
    }

    private fun taskOptions(task: LuxeTask) {
        AlertDialog.Builder(context).setTitle(task.title)
            .setItems(arrayOf("Edit", "Remove")) { _, which ->
                if (which == 0) editTask(task) else {
                    removed = data.tasks.indexOfFirst { it.id == task.id } to task
                    change(data.copy(tasks = data.tasks.filter { it.id != task.id }))
                }
            }.show()
    }

    private fun editTask(existing: LuxeTask? = null) {
        var minute = existing?.minute ?: -1
        val field = EditText(context).apply {
            hint = "What to do"
            setText(existing?.title.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(android.text.InputFilter.LengthFilter(60))
        }
        val timeButton = context.action(if (minute < 0) "Set a time" else "At ${time(minute)}", colors.accent) {}
        timeButton.setOnClickListener {
            val start = if (minute < 0) LocalTime.now().hour * 60 else minute
            TimePickerDialog(context, { _, hour, min ->
                minute = hour * 60 + min
                timeButton.text = context.getString(R.string.at_time, time(minute))
            }, start / 60, start % 60, use24Hour || DateFormat.is24HourFormat(context)).show()
        }
        val kinds = RadioGroup(context).apply {
            orientation = HORIZONTAL
            TaskKind.entries.forEach { kind ->
                addView(RadioButton(context).apply {
                    id = View.generateViewId()
                    text = kind.title
                    tag = kind
                    isChecked = kind == (existing?.kind ?: TaskKind.LIFE)
                })
            }
        }
        val form = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(field)
            addView(timeButton)
            addView(android.widget.HorizontalScrollView(context).apply { addView(kinds) })
        }
        AlertDialog.Builder(context).setTitle(if (existing == null) "Add to schedule" else "Edit").setView(form)
            .setPositiveButton("Save") { _, _ ->
                val name = field.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val kind = kinds.findViewById<RadioButton>(kinds.checkedRadioButtonId)?.tag as? TaskKind ?: TaskKind.LIFE
                val task = LuxeTask(existing?.id ?: LuxeData.newId(), name, minute, kind)
                change(data.copy(tasks = if (existing == null) data.tasks + task else data.tasks.map { if (it.id == task.id) task else it }))
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    // ---- Streak ----

    private fun streak() = card().apply {
        val result = Streaks.compute(data.keptDays(), LocalDate.now().toEpochDay())
        addView(heading("Streak"))
        addView(LinearLayout(context).apply {
            gravity = Gravity.BOTTOM
            addView(gold("${result.current}", 40f))
            addView(text(if (result.current == 1) " day" else " days", 16f, colors.muted).apply { setPadding(dp(6), 0, 0, dp(8)) })
        })
        addView(text("Best ${result.best}. ${result.graceLeft} free ${if (result.graceLeft == 1) "miss" else "misses"} left this week. " +
            "A missed day past those halves the streak; it never drops to zero.", 13f, colors.muted))
    }

    // ---- Reminder ----

    private fun reminder() = card().apply {
        addView(heading("Daily reminder"))
        val now = LocalTime.now()
        val due = data.reminder.isNotBlank() && now.hour * 60 + now.minute >= data.reminderMinute
        if (data.reminder.isBlank()) addView(text("Tap to set one gentle reminder for each day.", 14f, colors.muted))
        else {
            addView(text(data.reminder, 17f, if (due) colors.accent else colors.ink, if (due) Typeface.BOLD else Typeface.NORMAL))
            addView(text(if (due) "Now · every day at ${time(data.reminderMinute)}" else "Every day at ${time(data.reminderMinute)}", 13f, colors.muted))
        }
        contentDescription = if (data.reminder.isBlank()) "Set a daily reminder" else "Reminder: ${data.reminder}. Tap to change."
        setOnClickListener {
            editText("Daily reminder", data.reminder, 80) { words ->
                TimePickerDialog(context, { _, hour, minute -> change(data.copy(reminder = words, reminderMinute = hour * 60 + minute)) },
                    data.reminderMinute / 60, data.reminderMinute % 60, use24Hour || DateFormat.is24HourFormat(context)).show()
            }
        }
    }

    // ---- Quotes ----

    private fun quote() = card().apply {
        val picked = Quotes.pick(data.quoteTopics, LocalDate.now().toEpochDay(), data.quoteShift)
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(heading(picked?.second?.topic ?: "Quotes"), LayoutParams(0, -2, 1f))
            if (picked != null) {
                val saved = picked.first in data.bookmarks
                addView(context.action(if (saved) "★" else "☆", colors.accent) {
                    change(data.copy(bookmarks = if (saved) data.bookmarks - picked.first else data.bookmarks + picked.first))
                }.apply { contentDescription = if (saved) "Remove bookmark" else "Bookmark this quote" })
            }
            if (data.bookmarks.isNotEmpty()) addView(context.action("Saved", colors.accent) { showSaved() })
        })
        if (picked == null) addView(text("Choose quote topics in Customise.", 14f, colors.muted))
        else {
            addView(text("“${picked.second.text}”", 17f, colors.ink, Typeface.ITALIC))
            addView(text("— ${picked.second.author}", 13f, colors.accent).apply { setPadding(0, dp(6), 0, 0) })
            addView(text("Tap for another", 12f, colors.muted).apply { setPadding(0, dp(6), 0, 0) })
            setOnClickListener { change(data.copy(quoteShift = data.quoteShift + 1)) }
        }
    }

    private fun showSaved() {
        val lines = data.bookmarks.sorted().mapNotNull { Quotes.all.getOrNull(it) }.map { "“${it.text}”\n— ${it.author}" }
        AlertDialog.Builder(context).setTitle("Saved quotes").setItems(lines.toTypedArray(), null)
            .setPositiveButton(R.string.done, null).show()
    }

    // ---- Roadmap ----

    private fun roadmap() = card().apply {
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(heading("Mission roadmap"), LayoutParams(0, -2, 1f))
            addView(context.action("+ Step", colors.accent) {
                editText("New step", "", 60) { change(data.copy(steps = data.steps + LuxeStep(LuxeData.newId(), it))) }
            })
        })
        if (data.steps.isEmpty()) addView(text("Lay out the steps to a bigger goal, then tick them off.", 14f, colors.muted))
        data.steps.forEachIndexed { index, step ->
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                addView(StepNode(context, colors, step.done, index > 0, index < data.steps.size - 1), LayoutParams(dp(24), dp(48)))
                addView(text(step.title, 15f, if (step.done) colors.muted else colors.ink).apply { setPadding(dp(12), 0, 0, 0) })
                contentDescription = "${step.title}, ${if (step.done) "done" else "not done"}. Touch and hold to remove."
                setOnClickListener { change(data.copy(steps = data.steps.map { if (it.id == step.id) it.copy(done = !it.done) else it })) }
                setOnLongClickListener {
                    AlertDialog.Builder(context).setTitle("Remove “${step.title}”?")
                        .setPositiveButton("Remove") { _, _ -> change(data.copy(steps = data.steps.filter { it.id != step.id })) }
                        .setNegativeButton(R.string.cancel, null).show()
                    true
                }
            })
        }
    }

    // ---- Bottom bar ----

    private fun nav() = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = rounded(colors.surface, dp(32).toFloat(), (colors.accent and 0x00FFFFFF) or 0x55000000, dp(1))
        layoutParams = LayoutParams(-1, -2).apply { setMargins(0, dp(14), 0, 0) }
        fun item(label: String, onTap: () -> Unit) = addView(context.action(label, colors.ink, onTap).apply {
            gravity = Gravity.CENTER
            textSize = 12f
        }, LayoutParams(0, -2, 1f))
        item("Focus") { focusCard?.let(scrollTo) ?: hooks.onCustomise() }
        item("Plan") { scheduleCard?.let(scrollTo) ?: hooks.onCustomise() }
        addView(Emblem(context, colors, "◆").apply {
            contentDescription = "All apps"
            setOnClickListener { onAllApps() }
        }, LayoutParams(dp(60), dp(60)).apply { setMargins(dp(6), 0, dp(6), 0) })
        item("Edit", hooks.onCustomise)
        item("Settings", onSettings)
    }

    private fun editText(title: String, value: String, limit: Int, onSave: (String) -> Unit) {
        val field = EditText(context).apply {
            setText(value)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(android.text.InputFilter.LengthFilter(limit))
        }
        AlertDialog.Builder(context).setTitle(title)
            .setView(FrameLayout(context).apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(field)
            })
            .setPositiveButton("Save") { _, _ -> onSave(field.text.toString().trim()) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    /** Gold medallion with a letter or mark, used for the profile and the centre of the bottom bar. */
    private class Emblem(context: Context, private val colors: Palette, private val mark: String) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = context.resources.getFont(R.font.cinzel)
            textAlign = Paint.Align.CENTER
            color = colors.onAccent
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val r = minOf(w, h) / 2f
            paint.shader = LinearGradient(0f, h / 2f - r, 0f, h / 2f + r, mix(colors.accent, Color.WHITE, 0.35f), mix(colors.accent, Color.BLACK, 0.3f), Shader.TileMode.CLAMP)
            paint.setShadowLayer(r * 0.25f, 0f, 0f, (colors.accent and 0x00FFFFFF) or 0x88000000.toInt())
        }

        override fun onDraw(canvas: Canvas) {
            val r = minOf(width, height) / 2f
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, r * 0.82f, paint)
            letter.textSize = r * 0.8f
            canvas.drawText(mark, cx, cy - (letter.descent() + letter.ascent()) / 2, letter)
        }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
    }

    /** The daily ring: a track with one arc per task kind, each in its own shade of gold. */
    private class Ring(context: Context, private val colors: Palette, private val shares: List<Float>, private val label: String,
                       font: Typeface) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.BUTT
        }
        private val box = RectF()
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font
            textAlign = Paint.Align.CENTER
            color = colors.accent
        }

        override fun onDraw(canvas: Canvas) {
            val stroke = width * 0.1f
            paint.strokeWidth = stroke
            box.set(stroke, stroke, width - stroke, height - stroke)
            paint.color = mix(colors.surface, colors.accent, 0.15f)
            canvas.drawArc(box, 0f, 360f, false, paint)
            var start = -90f
            shares.forEachIndexed { index, share ->
                if (share <= 0f) return@forEachIndexed
                paint.color = shade(colors, index)
                canvas.drawArc(box, start, share * 360f, false, paint)
                start += share * 360f
            }
            text.textSize = width * 0.2f
            canvas.drawText(label, width / 2f, height / 2f - (text.descent() + text.ascent()) / 2, text)
        }

        companion object {
            fun shade(colors: Palette, index: Int) = when (index) {
                0 -> colors.accent
                1 -> mix(colors.accent, Color.WHITE, 0.35f)
                2 -> mix(colors.accent, Color.BLACK, 0.3f)
                else -> mix(colors.accent, colors.ink, 0.55f)
            }
        }
    }

    /** One roadmap node: a gold ring, filled when done, with the line to its neighbours. */
    private class StepNode(context: Context, private val colors: Palette, private val done: Boolean,
                           private val above: Boolean, private val below: Boolean) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = width * 0.32f
            paint.color = mix(colors.accent, colors.surface, 0.4f)
            paint.strokeWidth = width * 0.08f
            if (above) canvas.drawLine(cx, 0f, cx, cy - r, paint)
            if (below) canvas.drawLine(cx, cy + r, cx, height.toFloat(), paint)
            paint.color = colors.accent
            paint.style = if (done) Paint.Style.FILL else Paint.Style.STROKE
            canvas.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.FILL
        }
    }
}
