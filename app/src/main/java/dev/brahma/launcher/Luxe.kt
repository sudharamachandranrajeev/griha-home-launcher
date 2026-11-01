package dev.brahma.launcher

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The cards the Luxe home can show; each one can be turned off. */
enum class LuxeCard(val title: String) {
    PROFILE("Profile and stats"), PROGRESS("Daily ring and week"), SCHEDULE("Schedule checklist"), STREAK("Streak"),
    FOCUS("Focus mode"), QUOTES("Quotes"), ROADMAP("Mission roadmap"), REMINDER("Daily reminder")
}

/** What a schedule item is for; the daily ring shows each kind in its own shade of gold. */
enum class TaskKind(val title: String) { MIND("Mind"), BODY("Body"), WORK("Work"), LIFE("Life") }

data class LuxeTask(val id: String, val title: String, val minute: Int = -1, val kind: TaskKind = TaskKind.LIFE)

data class LuxeStep(val id: String, val title: String, val done: Boolean = false)

/**
 * Everything the Luxe home keeps, stored only on this phone. Days are ISO dates ("2026-11-01").
 * [done] holds the ids of schedule items ticked on each day; [focus] the focus minutes of each day.
 */
data class LuxeData(
    val tagline: String = "One calm step at a time",
    val cards: Set<LuxeCard> = LuxeCard.entries.toSet() - LuxeCard.QUOTES,
    val tasks: List<LuxeTask> = listOf(
        LuxeTask("walk", "Morning walk", 7 * 60, TaskKind.BODY),
        LuxeTask("read", "Read 20 pages", 8 * 60 + 30, TaskKind.MIND),
        LuxeTask("deep", "Deep work block", 10 * 60, TaskKind.WORK)
    ),
    val done: Map<String, Set<String>> = emptyMap(),
    val focus: Map<String, Int> = emptyMap(),
    val focusApps: Set<String> = emptySet(),
    val focusStart: Long = 0,
    val focusUntil: Long = 0,
    val steps: List<LuxeStep> = emptyList(),
    val reminder: String = "",
    val reminderMinute: Int = 21 * 60,
    val quoteTopics: Set<String> = Quotes.topics.toSet(),
    val bookmarks: Set<Int> = emptySet(),
    val quoteShift: Int = 0
) {
    fun focusActive(now: Long) = focusUntil > now

    /** Apps kept out of sight right now: only while a focus session runs. */
    fun hidden(now: Long): Set<String> = if (focusActive(now)) focusApps else emptySet()

    fun startFocus(now: Long, minutes: Int) = copy(focusStart = now, focusUntil = now + minutes * 60_000L)

    /** Ends the session, early or on time, and counts the minutes actually spent. */
    fun endFocus(now: Long, zone: ZoneId): LuxeData {
        if (focusUntil == 0L) return this
        val minutes = ((minOf(now, focusUntil) - focusStart) / 60_000L).toInt().coerceAtLeast(0)
        val day = Instant.ofEpochMilli(focusStart).atZone(zone).toLocalDate().toString()
        return copy(focus = focus + (day to (focus[day] ?: 0) + minutes), focusStart = 0, focusUntil = 0)
    }

    /** Closes a session whose time is up; everything it hid comes back. */
    fun settle(now: Long, zone: ZoneId) = if (focusUntil != 0L && now >= focusUntil) endFocus(now, zone) else this

    fun toggle(day: LocalDate, taskId: String): LuxeData {
        val key = day.toString()
        val ticked = done[key].orEmpty()
        return copy(done = done + (key to if (taskId in ticked) ticked - taskId else ticked + taskId))
    }

    fun doneOn(day: LocalDate) = done[day.toString()].orEmpty().count { id -> tasks.any { it.id == id } }

    /** A day counts toward the streak when half the schedule was ticked, or a 25-minute focus session was done. */
    fun keptDays(): Set<Long> {
        val days = HashSet<Long>()
        val need = maxOf(1, (tasks.size + 1) / 2)
        done.forEach { (day, ids) -> if (ids.size >= need) parse(day)?.let { days += it } }
        focus.forEach { (day, minutes) -> if (minutes >= 25) parse(day)?.let { days += it } }
        return days
    }

    fun focusMinutes() = focus.values.sum()

    /** Cosmetic only: it only ever goes up, and nothing is locked behind it. */
    fun level() = 1 + (keptDays().size + focusMinutes() / 60) / 3

    /** Drops history older than about a year so the stored data stays small. */
    fun pruned(today: LocalDate): LuxeData {
        val oldest = today.minusDays(400).toString()
        return copy(done = done.filterKeys { it >= oldest }, focus = focus.filterKeys { it >= oldest })
    }

    fun encode(): String = JSONObject().apply {
        put("tagline", tagline)
        put("cards", JSONArray(cards.map { it.name }))
        put("tasks", JSONArray(tasks.map { JSONObject().put("id", it.id).put("title", it.title).put("minute", it.minute).put("kind", it.kind.name) }))
        put("done", JSONObject().apply { done.forEach { (day, ids) -> put(day, JSONArray(ids.toList())) } })
        put("focus", JSONObject().apply { focus.forEach { (day, minutes) -> put(day, minutes) } })
        put("focusApps", JSONArray(focusApps.toList()))
        put("focusStart", focusStart)
        put("focusUntil", focusUntil)
        put("steps", JSONArray(steps.map { JSONObject().put("id", it.id).put("title", it.title).put("done", it.done) }))
        put("reminder", reminder)
        put("reminderMinute", reminderMinute)
        put("quoteTopics", JSONArray(quoteTopics.toList()))
        put("bookmarks", JSONArray(bookmarks.toList()))
        put("quoteShift", quoteShift)
    }.toString()

    companion object {
        fun newId() = UUID.randomUUID().toString().take(8)

        private fun parse(day: String) = try {
            LocalDate.parse(day).toEpochDay()
        } catch (_: Exception) {
            null
        }

        private fun strings(array: JSONArray?) = (0 until (array?.length() ?: 0)).mapNotNull { array?.optString(it)?.takeIf(String::isNotEmpty) }

        fun decode(text: String?): LuxeData {
            if (text.isNullOrBlank()) return LuxeData()
            return try {
                val json = JSONObject(text)
                val base = LuxeData()
                val tasks = json.optJSONArray("tasks")
                val steps = json.optJSONArray("steps")
                val done = json.optJSONObject("done")
                val focus = json.optJSONObject("focus")
                LuxeData(
                    tagline = json.optString("tagline", base.tagline),
                    cards = json.optJSONArray("cards")?.let { strings(it).mapNotNull { name -> LuxeCard.entries.find { card -> card.name == name } }.toSet() } ?: base.cards,
                    tasks = tasks?.let { (0 until it.length()).map { index ->
                        val item = it.getJSONObject(index)
                        LuxeTask(item.getString("id"), item.optString("title"), item.optInt("minute", -1),
                            TaskKind.entries.find { kind -> kind.name == item.optString("kind") } ?: TaskKind.LIFE)
                    } } ?: base.tasks,
                    done = done?.keys()?.asSequence()?.associateWith { strings(done.optJSONArray(it)).toSet() }.orEmpty(),
                    focus = focus?.keys()?.asSequence()?.associateWith { focus.optInt(it) }.orEmpty(),
                    focusApps = strings(json.optJSONArray("focusApps")).toSet(),
                    focusStart = json.optLong("focusStart"),
                    focusUntil = json.optLong("focusUntil"),
                    steps = steps?.let { (0 until it.length()).map { index ->
                        val item = it.getJSONObject(index)
                        LuxeStep(item.getString("id"), item.optString("title"), item.optBoolean("done"))
                    } }.orEmpty(),
                    reminder = json.optString("reminder"),
                    reminderMinute = json.optInt("reminderMinute", base.reminderMinute).coerceIn(0, 24 * 60 - 1),
                    quoteTopics = json.optJSONArray("quoteTopics")?.let { strings(it).toSet() } ?: base.quoteTopics,
                    bookmarks = json.optJSONArray("bookmarks")?.let { array -> (0 until array.length()).map { array.optInt(it) }.toSet() }.orEmpty(),
                    quoteShift = json.optInt("quoteShift")
                )
            } catch (_: Exception) {
                LuxeData()
            }
        }
    }
}

class LuxeStore(private val preferences: SharedPreferences) {
    fun load() = LuxeData.decode(preferences.getString("data", null))
    fun save(data: LuxeData) = preferences.edit().putString("data", data.pruned(LocalDate.now()).encode()).apply()
}

/**
 * A forgiving streak. Each week brings [grace] free misses; a miss beyond them halves the streak instead of
 * wiping it, so it never drops back to zero once started. Today never counts as missed while it is still going.
 */
object Streaks {
    data class Result(val current: Int, val best: Int, val graceLeft: Int)

    fun compute(kept: Set<Long>, today: Long, grace: Int = 2): Result {
        val first = kept.minOrNull() ?: return Result(0, 0, grace)
        var current = 0
        var best = 0
        var graceLeft = grace
        for (day in first..today) {
            if ((day - first) % 7 == 0L) graceLeft = grace
            when {
                day in kept -> current++
                day == today -> Unit
                graceLeft > 0 -> graceLeft--
                else -> current = (current + 1) / 2
            }
            best = maxOf(best, current)
        }
        return Result(current, best, graceLeft)
    }
}

/** Short public-domain and widely quoted lines, grouped by topic. Only the chosen topics are shown. */
object Quotes {
    class Quote(val topic: String, val text: String, val author: String)

    val all = listOf(
        Quote("Discipline", "First say to yourself what you would be; and then do what you have to do.", "Epictetus"),
        Quote("Discipline", "Well done is better than well said.", "Benjamin Franklin"),
        Quote("Discipline", "It is not that we have a short time to live, but that we waste a lot of it.", "Seneca"),
        Quote("Discipline", "Waste no more time arguing about what a good man should be. Be one.", "Marcus Aurelius"),
        Quote("Focus", "Our life is frittered away by detail. Simplify, simplify.", "Henry David Thoreau"),
        Quote("Focus", "The impediment to action advances action. What stands in the way becomes the way.", "Marcus Aurelius"),
        Quote("Focus", "Begin at once to live, and count each separate day as a separate life.", "Seneca"),
        Quote("Calm", "Very little is needed to make a happy life; it is all within yourself, in your way of thinking.", "Marcus Aurelius"),
        Quote("Calm", "Wealth consists not in having great possessions, but in having few wants.", "Epictetus"),
        Quote("Courage", "It is not because things are difficult that we do not dare; it is because we do not dare that they are difficult.", "Seneca"),
        Quote("Courage", "Courage is resistance to fear, mastery of fear, not absence of fear.", "Mark Twain"),
        Quote("Courage", "Fall seven times, stand up eight.", "Japanese proverb"),
        Quote("Growth", "The journey of a thousand miles begins with a single step.", "Lao Tzu"),
        Quote("Growth", "He who has a why to live can bear almost any how.", "Friedrich Nietzsche"),
        Quote("Growth", "Do what you can, with what you have, where you are.", "Theodore Roosevelt"),
        Quote("Growth", "Energy and persistence conquer all things.", "Benjamin Franklin")
    )

    val topics = all.map { it.topic }.distinct()

    /** Today's quote among [topics], moved on by [shift] taps; null when no topic is chosen. */
    fun pick(topics: Set<String>, epochDay: Long, shift: Int): Pair<Int, Quote>? {
        val choices = all.withIndex().filter { it.value.topic in topics }
        if (choices.isEmpty()) return null
        val chosen = choices[Math.floorMod(epochDay + shift, choices.size.toLong()).toInt()]
        return chosen.index to chosen.value
    }
}
