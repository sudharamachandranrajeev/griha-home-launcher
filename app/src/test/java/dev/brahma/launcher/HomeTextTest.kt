package dev.brahma.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

class HomeTextTest {
    private val wednesday = LocalDateTime.of(2025, 7, 9, 11, 40)

    @Test fun greetingFollowsTimeOfDayBoundaries() {
        assertEquals("Good Night", HomeText.greeting(4))
        assertEquals("Good Morning", HomeText.greeting(5))
        assertEquals("Good Morning", HomeText.greeting(11))
        assertEquals("Good Afternoon", HomeText.greeting(12))
        assertEquals("Good Evening", HomeText.greeting(17))
        assertEquals("Good Night", HomeText.greeting(21))
        assertEquals("Good Night ☾", HomeText.greetingWithGlyph(23))
        assertEquals("Good Morning ☀", HomeText.greetingWithGlyph(9))
    }

    @Test fun newLayoutLinesUseNameOnlyWhenSet() {
        assertEquals("Good Morning", HomeText.greetingFor(9, " "))
        assertEquals("Good Evening, Asha", HomeText.greetingFor(18, " Asha "))
        assertEquals("It's Wednesday", HomeText.itsDay(wednesday, Locale.US))
        assertEquals("You are awesome", HomeText.affirmation(""))
        assertEquals("You are awesome, Asha", HomeText.affirmation("Asha"))
        assertEquals("Battery is 80%", HomeText.batteryShort(80, false))
        assertEquals("Battery is 80% · charging", HomeText.batteryShort(80, true))
        assertEquals("Battery unavailable", HomeText.batteryShort(null, false))
        assertEquals("29°C light rain", HomeText.weatherLine(WeatherNow("Pune", 28.6, "°C", 61, 0)))
    }

    @Test fun lockScreenTextAndKey() {
        assertEquals("Have a lovely Wednesday", HomeText.lockLine(wednesday, "", Locale.US))
        assertEquals("Have a lovely Wednesday, Asha", HomeText.lockLine(wednesday, "Asha ", Locale.US))
        val day = LocalDate.of(2026, 10, 31)
        val settings = LauncherSettings(name = "Asha", layout = HomeLayout.ARCHES)
        assertEquals(LockScreen.key(settings, false, day), LockScreen.key(settings.copy(name = " Asha", weather = true), false, day))
        assert(LockScreen.key(settings, false, day) != LockScreen.key(settings, true, day))
        assert(LockScreen.key(settings, false, day) != LockScreen.key(settings, false, day.plusDays(1)))
        assert(LockScreen.key(settings, false, day) != LockScreen.key(settings.copy(layout = HomeLayout.SOFT), false, day))
    }

    @Test fun weekStartsOnLocaleFirstDayAndContainsToday() {
        val sunday = HomeText.week(LocalDate.of(2026, 8, 31), DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 8, 30), sunday.first())
        assertEquals(7, sunday.size)
        val monday = HomeText.week(LocalDate.of(2026, 8, 30), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 8, 24), monday.first())
        assertEquals(LocalDate.of(2026, 8, 30), monday.last())
    }

    @Test fun daySentenceUsesWhatIsKnown() {
        assertEquals("Today is Wednesday, 9 July 2025. Current time is 11:40 AM.",
            HomeText.daySentence(wednesday, " ", null, false, Locale.US))
        assertEquals("Today is Wednesday, 9 July 2025. You are in Pune. Current time is 11:40.",
            HomeText.daySentence(wednesday, "Pune", null, true, Locale.US))
        val rain = WeatherNow("Pune", 28.6, "°C", 61, 0)
        assertEquals("Today is Wednesday, 9 July 2025. Weather outside is light rain, 29°C in Pune. Current time is 11:40 AM.",
            HomeText.daySentence(wednesday, "Pune", rain, false, Locale.US))
    }

    @Test fun everyHomeDatePatternFormats() {
        DatePatterns.all.forEach { java.time.format.DateTimeFormatter.ofPattern(it, Locale.US).format(wednesday) }
        assertEquals("#09", wednesday.format(java.time.format.DateTimeFormatter.ofPattern(DatePatterns.BANNER_NUMBER, Locale.US)))
        assertEquals("WED & JULY", wednesday.format(java.time.format.DateTimeFormatter.ofPattern(DatePatterns.BANNER_DAYS, Locale.US)).uppercase())
    }

    @Test fun batteryLineStatesChargingAndUnknown() {
        assertEquals("BATTERY LEVEL AT 66% · DISCHARGING", HomeText.battery(66, false))
        assertEquals("BATTERY LEVEL AT 100% · CHARGING", HomeText.battery(100, true))
        assertEquals("BATTERY LEVEL UNAVAILABLE", HomeText.battery(null, false))
    }
}

class WeatherTest {
    private val geocode = """{"results":[
        {"name":"Pune","latitude":1.0,"longitude":2.0,"country":"Elsewhere","country_code":"EL"},
        {"name":"Pune","latitude":18.52,"longitude":73.86,"country":"India","country_code":"IN"}]}"""

    @Test fun locationPrefersTypedCountryElseFirstResult() {
        assertEquals(18.52 to 73.86, Weather.parseLocation(geocode, " India"))
        assertEquals(18.52 to 73.86, Weather.parseLocation(geocode, "in"))
        assertEquals(1.0 to 2.0, Weather.parseLocation(geocode, null))
        assertNull(Weather.parseLocation("""{"generationtime_ms":0.5}""", null))
    }

    @Test fun currentWeatherParsesAndRejectsIncompleteAnswers() {
        val now = Weather.parseCurrent("""{"current":{"temperature_2m":29.4,"weather_code":3}}""", "Pune", "°C", 5)
        assertEquals(WeatherNow("Pune", 29.4, "°C", 3, 5), now)
        assertEquals("29°C", now!!.rounded())
        assertNull(Weather.parseCurrent("""{"current":{"temperature_2m":29.4}}""", "Pune", "°C", 5))
        assertNull(Weather.parseCurrent("{}", "Pune", "°C", 5))
    }

    @Test fun codesAndUnits() {
        assertEquals("overcast", Weather.describe(3))
        assertEquals("unsettled", Weather.describe(1234))
        assertEquals(true, Weather.usesFahrenheit(Locale.US))
        assertEquals(false, Weather.usesFahrenheit(Locale.UK))
    }
}
