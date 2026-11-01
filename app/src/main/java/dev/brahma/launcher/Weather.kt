package dev.brahma.launcher

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

data class WeatherNow(val place: String, val temperature: Double, val unit: String, val code: Int, val fetchedAt: Long) {
    fun rounded() = "${temperature.roundToInt()}${unit}"
}

// Opt-in only. Sends the typed place name (geocoding) and its coordinates (forecast) to
// Open-Meteo over HTTPS; no key, account or device identifier is involved.
object Weather {
    const val REFRESH_MILLIS = 30 * 60 * 1000L
    const val SHOW_MILLIS = 3 * 60 * 60 * 1000L
    private const val TIMEOUT_MILLIS = 10_000

    fun usesFahrenheit(locale: Locale) = locale.country in setOf("US", "LR", "MM")

    // WMO weather interpretation codes, as documented by Open-Meteo.
    fun describe(code: Int) = when (code) {
        0 -> "clear sky"
        1 -> "mainly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "foggy"
        51, 53, 55 -> "drizzly"
        56, 57 -> "freezing drizzle"
        61 -> "light rain"
        63 -> "rain"
        65 -> "heavy rain"
        66, 67 -> "freezing rain"
        71, 73, 75, 77 -> "snow"
        80, 81, 82 -> "rain showers"
        85, 86 -> "snow showers"
        95 -> "a thunderstorm"
        96, 99 -> "a thunderstorm with hail"
        else -> "unsettled"
    }

    /** Picks the first result; "City, Country" prefers a result in that country. */
    fun parseLocation(json: String, country: String?): Pair<Double, Double>? {
        val results = JSONObject(json).optJSONArray("results") ?: return null
        val wanted = country?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val all = (0 until results.length()).map { results.getJSONObject(it) }
        val match = all.firstOrNull {
            wanted.isNotEmpty() && (it.optString("country").lowercase(Locale.ROOT) == wanted ||
                it.optString("country_code").lowercase(Locale.ROOT) == wanted)
        } ?: all.firstOrNull() ?: return null
        return match.getDouble("latitude") to match.getDouble("longitude")
    }

    fun parseCurrent(json: String, place: String, unit: String, fetchedAt: Long): WeatherNow? {
        val current = JSONObject(json).optJSONObject("current") ?: return null
        if (!current.has("temperature_2m") || !current.has("weather_code")) return null
        return WeatherNow(place, current.getDouble("temperature_2m"), unit, current.getInt("weather_code"), fetchedAt)
    }

    /** Blocking network call; run off the main thread. Returns null when the place is not found. */
    fun fetch(place: String, fahrenheit: Boolean): WeatherNow? {
        val name = place.substringBefore(',').trim()
        if (name.isEmpty()) return null
        val country = place.substringAfter(',', "").takeIf { it.isNotBlank() }
        val encoded = URLEncoder.encode(name, "UTF-8")
        val (latitude, longitude) = parseLocation(
            get("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=10&format=json"), country) ?: return null
        val unit = if (fahrenheit) "fahrenheit" else "celsius"
        val forecast = get("https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,weather_code&temperature_unit=$unit")
        return parseCurrent(forecast, place.trim(), if (fahrenheit) "°F" else "°C", System.currentTimeMillis())
    }

    private fun get(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
