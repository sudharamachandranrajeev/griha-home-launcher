package dev.brahma.launcher

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class DayPart(val word: String, val glyph: String) {
    MORNING("Morning", "☀"), AFTERNOON("Afternoon", "☀"), EVENING("Evening", "☾"), NIGHT("Night", "☾");

    companion object {
        fun at(hour: Int) = when (hour) {
            in 5..11 -> MORNING
            in 12..16 -> AFTERNOON
            in 17..20 -> EVENING
            else -> NIGHT
        }
    }
}

// Every date pattern the home screen formats; tests check each one parses, since a bad
// pattern only fails at runtime. '#', '{' and '}' are reserved and must be quoted.
object DatePatterns {
    const val DAY = "d"
    const val DAY_PADDED = "dd"
    const val MONTH = "MMMM"
    const val MONO_DATE = "EEEE '•' MMM dd, yyyy"
    const val BANNER_DAYS = "EEE '&' MMMM"
    const val BANNER_NUMBER = "'#'dd"
    const val SPOKEN_DATE = "EEEE d MMMM"
    const val CARDS_DATE = "dd-MM-yyyy  EEEE"
    const val MONTH_SHORT = "MMM"
    const val WEEKDAY = "EEEE"
    const val NIGHT_DATE = "EEE, MMM d"
    const val DIAL_DATE = "dd MMM yyyy"
    val all = listOf(DAY, DAY_PADDED, MONTH, MONO_DATE, BANNER_DAYS, BANNER_NUMBER, SPOKEN_DATE, CARDS_DATE,
        MONTH_SHORT, WEEKDAY, NIGHT_DATE, DIAL_DATE)
}

// Pure text for the home screen, kept free of Android types so it is unit tested.
object HomeText {
    fun greeting(hour: Int) = "Good ${DayPart.at(hour).word}"

    fun greetingWithGlyph(hour: Int) = "${greeting(hour)} ${DayPart.at(hour).glyph}"

    fun greetingFor(hour: Int, name: String) = if (name.isBlank()) greeting(hour) else "${greeting(hour)}, ${name.trim()}"

    fun itsDay(now: LocalDateTime, locale: Locale) = "It's ${now.format(DateTimeFormatter.ofPattern(DatePatterns.WEEKDAY, locale))}"

    fun lockLine(now: LocalDateTime, name: String, locale: Locale): String {
        val day = now.format(DateTimeFormatter.ofPattern(DatePatterns.WEEKDAY, locale))
        return if (name.isBlank()) "Have a lovely $day" else "Have a lovely $day, ${name.trim()}"
    }

    fun affirmation(name: String) = if (name.isBlank()) "You are awesome" else "You are awesome, ${name.trim()}"

    fun weatherLine(weather: WeatherNow) = "${weather.rounded()} ${Weather.describe(weather.code).lowercase()}"

    fun batteryShort(percent: Int?, charging: Boolean): String = when {
        percent == null -> "Battery unavailable"
        charging -> "Battery is $percent% · charging"
        else -> "Battery is $percent%"
    }

    fun week(today: LocalDate, firstDay: DayOfWeek): List<LocalDate> {
        val start = today.with(TemporalAdjusters.previousOrSame(firstDay))
        return (0L..6L).map { start.plusDays(it) }
    }

    fun time(now: LocalDateTime, use24Hour: Boolean, locale: Locale): String =
        now.format(DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale))

    fun daySentence(now: LocalDateTime, place: String, weather: WeatherNow?, use24Hour: Boolean, locale: Locale): String {
        val day = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", locale))
        val where = place.trim()
        val outside = when {
            weather != null && where.isNotEmpty() -> " Weather outside is ${Weather.describe(weather.code)}, " +
                "${weather.rounded()} in $where."
            weather != null -> " Weather outside is ${Weather.describe(weather.code)}, ${weather.rounded()}."
            where.isNotEmpty() -> " You are in $where."
            else -> ""
        }
        return "Today is $day.$outside Current time is ${time(now, use24Hour, locale)}."
    }

    fun battery(percent: Int?, charging: Boolean): String = when {
        percent == null -> "BATTERY LEVEL UNAVAILABLE"
        charging -> "BATTERY LEVEL AT $percent% · CHARGING"
        else -> "BATTERY LEVEL AT $percent% · DISCHARGING"
    }
}
